package dev.durablejobs.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.durablejobs.api.*;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

public final class SqliteJobStore {
    private static final int APPLICATION_ID = 0x44414A31;
    private final SqliteDatabase database;
    private final Clock clock;

    public record Submission(String jobId, boolean duplicate) {}

    public SqliteJobStore(Path path) { this(path, Clock.systemUTC()); }

    public SqliteJobStore(Path path, Clock clock) {
        this.database = new SqliteDatabase(path);
        this.clock = clock;
        database.initialize("/schema.sql", APPLICATION_ID);
    }

    public Path path() { return database.path(); }
    public long now() { return clock.millis(); }

    public Submission submit(String requestKey, JobSpec spec) {
        if (requestKey == null || !requestKey.matches("[a-zA-Z0-9_.:-]{1,128}")) {
            throw new JobException("INVALID_REQUEST_KEY", "Request key must be 1..128 safe identifier characters.");
        }
        String hash = JsonSupport.hash(spec.json());
        String json = JsonSupport.canonical(spec.json());
        return database.transaction(connection -> {
            try (PreparedStatement query = connection.prepareStatement("SELECT job_id,spec_hash FROM jobs WHERE request_key=?")) {
                query.setString(1, requestKey);
                try (ResultSet rows = query.executeQuery()) {
                    if (rows.next()) {
                        if (!hash.equals(rows.getString("spec_hash"))) {
                            throw new JobException("REQUEST_CONFLICT", "Request key already belongs to a different specification.");
                        }
                        return new Submission(rows.getString("job_id"), true);
                    }
                }
            }
            String id = UUID.randomUUID().toString();
            long now = clock.millis();
            execute(connection, "INSERT INTO jobs(job_id,request_key,spec_hash,spec_json,state,created_at,updated_at,deadline_at) VALUES(?,?,?,?,'QUEUED',?,?,?)",
                    id, requestKey, hash, json, now, now, now + spec.policy().jobTimeoutMillis());
            execute(connection, "INSERT INTO checkpoints(job_id,sequence,state_json,created_at) VALUES(?,0,'{}',?)", id, now);
            event(connection, id, "SUBMITTED", new JsonObject());
            return new Submission(id, false);
        });
    }

    public Optional<JobRecord> claimNext() {
        return database.transaction(connection -> {
            while (true) {
                String id;
                try (PreparedStatement query = connection.prepareStatement("SELECT job_id FROM jobs WHERE state='QUEUED' OR (state='WAITING_RETRY' AND EXISTS(SELECT 1 FROM operations o WHERE o.job_id=jobs.job_id AND o.next_attempt_at<=?)) ORDER BY created_at,job_id LIMIT 1")) {
                    query.setLong(1, clock.millis());
                    try (ResultSet rows = query.executeQuery()) {
                        if (!rows.next()) return Optional.empty();
                        id = rows.getString(1);
                    }
                }
                if (stopForControl(connection, load(connection,id))) continue;
                String generation = UUID.randomUUID().toString();
                int changed = execute(connection, "UPDATE jobs SET state='RUNNING',generation=?,updated_at=? WHERE job_id=? AND state IN ('QUEUED','WAITING_RETRY')",
                        generation, clock.millis(), id);
                if (changed != 1) throw new JobException("STALE_EXECUTION", "Job claim failed.");
                event(connection, id, "CLAIMED", new JsonObject());
                return Optional.of(load(connection, id));
            }
        });
    }

    public JobRecord get(String id) { return database.read(connection -> load(connection, id)); }

    public Optional<ToolRequest> pendingOperation(JobRecord job) {
        return database.read(connection -> {
            try (PreparedStatement query=connection.prepareStatement("SELECT * FROM operations WHERE job_id=? AND state='UNKNOWN' AND next_attempt_at IS NOT NULL")) {
                query.setString(1,job.id());
                try(ResultSet rows=query.executeQuery()) {
                    if(!rows.next()) return Optional.empty();
                    return Optional.of(new ToolRequest(rows.getString("operation_key"),rows.getString("tool_version"),
                            ReplayContract.valueOf(rows.getString("contract")),JsonSupport.object(rows.getString("args_json"),JsonSupport.SPEC_BYTES)));
                }
            }
        });
    }

    /** Invoke only while holding the exclusive worker lock. Conservatively consumes abandoned attempts. */
    public List<JobState> recoverAbandoned() {
        return database.transaction(connection -> {
            List<JobState> terminalStates=new ArrayList<>();
            for(String id: ids(connection,"RUNNING")) {
                JobRecord job=load(connection,id);
                if(stopForControl(connection,job)) { terminalStates.add(load(connection,id).state()); continue; }
                String key=null,contract=null; int attempts=0;
                try(PreparedStatement query=connection.prepareStatement("SELECT operation_key,contract,attempt_count FROM operations WHERE job_id=? AND state='INTENT'")) {
                    query.setString(1,id);
                    try(ResultSet rows=query.executeQuery()) {
                        if(rows.next()) { key=rows.getString(1); contract=rows.getString(2); attempts=rows.getInt(3); }
                    }
                }
                if(key==null) {
                    execute(connection,"UPDATE jobs SET state='QUEUED',generation=NULL,updated_at=? WHERE job_id=?",clock.millis(),id);
                    event(connection,id,"RECOVERED_CHECKPOINT",new JsonObject());
                } else if("NON_IDEMPOTENT".equals(contract)) {
                    terminal(connection,id,JobState.NEEDS_REVIEW,"UNCERTAIN_NON_IDEMPOTENT",true);
                    terminalStates.add(JobState.NEEDS_REVIEW);
                } else if(attempts>=job.spec().policy().maxAttempts()) {
                    terminal(connection,id,JobState.FAILED,"ATTEMPTS_EXHAUSTED",true);
                    terminalStates.add(JobState.FAILED);
                } else {
                    long due=Math.min(job.deadlineAt(),clock.millis()+job.spec().policy().backoff(attempts));
                    execute(connection,"UPDATE operations SET state='UNKNOWN',next_attempt_at=?,updated_at=? WHERE job_id=? AND operation_key=?",
                            due,clock.millis(),id,key);
                    execute(connection,"UPDATE attempts SET outcome='ABANDONED',ended_at=? WHERE job_id=? AND generation=? AND outcome='STARTED'",
                            clock.millis(),id,job.generation());
                    execute(connection,"UPDATE jobs SET state='WAITING_RETRY',generation=NULL,updated_at=? WHERE job_id=?",clock.millis(),id);
                    event(connection,id,"RECOVERY_RETRY_SCHEDULED",details(key,"next_attempt_at",due));
                }
            }
            return terminalStates;
        });
    }

    public List<JobState> settlePendingControls() {
        return database.transaction(connection -> {
            List<JobState> changed=new ArrayList<>();
            for(String state:List.of("QUEUED","WAITING_RETRY")) {
                for(String id:ids(connection,state)) if(stopForControl(connection,load(connection,id))) changed.add(load(connection,id).state());
            }
            return changed;
        });
    }

    public JobState controlState(String jobId) {
        return database.transaction(connection -> {
            stopForControl(connection,load(connection,jobId));
            return load(connection,jobId).state();
        });
    }

    public boolean cancelKey(String requestKey) {
        return database.transaction(connection -> {
            String id;
            try(PreparedStatement query=connection.prepareStatement("SELECT job_id FROM jobs WHERE request_key=?")) {
                query.setString(1,requestKey);
                try(ResultSet rows=query.executeQuery()) {
                    if(!rows.next()) throw new JobException("JOB_NOT_FOUND","Request key not found.");
                    id=rows.getString(1);
                }
            }
            if(isTerminal(load(connection,id).state())) return false;
            execute(connection,"UPDATE jobs SET cancel_requested=1 WHERE job_id=?",id);
            terminal(connection,id,JobState.CANCELLED,"CANCEL_REQUESTED",true);
            return true;
        });
    }

    public Optional<Long> nextRetryDelay() {
        return database.read(connection -> {
            try(PreparedStatement query=connection.prepareStatement("SELECT min(o.next_attempt_at) FROM operations o JOIN jobs j ON j.job_id=o.job_id WHERE j.state='WAITING_RETRY'");
                ResultSet rows=query.executeQuery()) {
                if(!rows.next() || rows.getObject(1)==null) return Optional.empty();
                return Optional.of(Math.max(0,rows.getLong(1)-clock.millis()));
            }
        });
    }

    public JobState recordToolFailure(JobRecord job,ToolRequest request,String code,boolean retryable,boolean unknown) {
        return database.transaction(connection -> {
            if(stopForControl(connection,load(connection,job.id()))) return load(connection,job.id()).state();
            assertCurrent(connection,job);
            int attempt;
            try(PreparedStatement query=connection.prepareStatement("SELECT attempt_count FROM operations WHERE job_id=? AND operation_key=? AND state='INTENT'")) {
                query.setString(1,job.id()); query.setString(2,request.operationKey());
                try(ResultSet rows=query.executeQuery()) {
                    if(!rows.next()) throw new JobException("STALE_EXECUTION","No active operation intent.");
                    attempt=rows.getInt(1);
                }
            }
            long now=clock.millis();
            execute(connection,"UPDATE attempts SET outcome=?,ended_at=? WHERE job_id=? AND operation_key=? AND generation=? AND outcome='STARTED'",
                    unknown?"UNKNOWN":retryable?"TRANSIENT_NO_EFFECT":"PERMANENT_NO_EFFECT",now,job.id(),request.operationKey(),job.generation());
            if(unknown && request.contract()==ReplayContract.NON_IDEMPOTENT) {
                terminal(connection,job.id(),JobState.NEEDS_REVIEW,code,true);
                return JobState.NEEDS_REVIEW;
            }
            if(retryable && request.contract()!=ReplayContract.NON_IDEMPOTENT && attempt<job.spec().policy().maxAttempts()) {
                long due=Math.min(job.deadlineAt(),now+job.spec().policy().backoff(attempt));
                execute(connection,"UPDATE operations SET state='UNKNOWN',next_attempt_at=?,updated_at=? WHERE job_id=? AND operation_key=?",due,now,job.id(),request.operationKey());
                execute(connection,"UPDATE jobs SET state='WAITING_RETRY',generation=NULL,error_code=?,updated_at=? WHERE job_id=?",code,now,job.id());
                event(connection,job.id(),"RETRY_SCHEDULED",details(request.operationKey(),"next_attempt_at",due));
                return JobState.WAITING_RETRY;
            }
            JobState terminalState=unknown && !retryable ? JobState.NEEDS_REVIEW:JobState.FAILED;
            terminal(connection,job.id(),terminalState,retryable?"ATTEMPTS_EXHAUSTED":code,unknown);
            return terminalState;
        });
    }

    private boolean stopForControl(Connection connection,JobRecord job) throws SQLException {
        if(isTerminal(job.state())) return true;
        if(job.cancelRequested()) { terminal(connection,job.id(),JobState.CANCELLED,"CANCEL_REQUESTED",true); return true; }
        if(clock.millis()>=job.deadlineAt()) { terminal(connection,job.id(),JobState.TIMED_OUT,"JOB_DEADLINE",true); return true; }
        return false;
    }

    private void terminal(Connection connection,String id,JobState state,String code,boolean unknown) throws SQLException {
        long now=clock.millis();
        execute(connection,"UPDATE jobs SET state=?,generation=NULL,error_code=?,updated_at=? WHERE job_id=?",state.name(),code,now,id);
        execute(connection,"UPDATE operations SET state=?,next_attempt_at=NULL,updated_at=? WHERE job_id=? AND state IN ('INTENT','UNKNOWN')",unknown?"UNKNOWN":"FAILED",now,id);
        execute(connection,"UPDATE attempts SET outcome=?,ended_at=? WHERE job_id=? AND outcome='STARTED'",unknown?"UNKNOWN":"FAILED",now,id);
        JsonObject details=new JsonObject(); details.addProperty("error_code",code); details.addProperty("unknown_operation",unknown);
        event(connection,id,state.name(),details);
    }

    public static boolean isTerminal(JobState state) {
        return state!=JobState.QUEUED && state!=JobState.RUNNING && state!=JobState.WAITING_RETRY;
    }
    private static List<String> ids(Connection connection,String state) throws SQLException {
        List<String> ids=new ArrayList<>();
        try(PreparedStatement query=connection.prepareStatement("SELECT job_id FROM jobs WHERE state=?")) {
            query.setString(1,state);
            try(ResultSet rows=query.executeQuery()) { while(rows.next()) ids.add(rows.getString(1)); }
        }
        return ids;
    }
    private static JsonObject details(String key,String name,long value) {
        JsonObject result=new JsonObject(); result.addProperty("operation_key",key); result.addProperty(name,value); return result;
    }

    public Checkpoint checkpoint(JobRecord job) {
        return database.read(connection -> {
            try (PreparedStatement query = connection.prepareStatement("SELECT state_json FROM checkpoints WHERE job_id=? AND sequence=?")) {
                query.setString(1, job.id());
                query.setLong(2, job.checkpointSequence());
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) throw new JobException("CORRUPT_CHECKPOINT", "Job references a missing checkpoint.");
                    return new Checkpoint(job.checkpointSequence(), JsonSupport.object(rows.getString(1), JsonSupport.CHECKPOINT_BYTES));
                }
            }
        });
    }

    /** Returns a stored receipt without dispatch, or persists the first intent before dispatch. */
    public Optional<ToolResult> prepareOperation(JobRecord job, ToolRequest request) {
        String args = JsonSupport.canonical(request.arguments());
        String hash = JsonSupport.hash(request.arguments());
        Optional<ToolResult> prepared = database.transaction(connection -> {
            if (stopForControl(connection, load(connection,job.id()))) return null;
            assertCurrent(connection, job);
            try (PreparedStatement query = connection.prepareStatement("SELECT * FROM operations WHERE job_id=? AND operation_key=?")) {
                query.setString(1, job.id());
                query.setString(2, request.operationKey());
                try (ResultSet rows = query.executeQuery()) {
                    if (rows.next()) {
                        if (!hash.equals(rows.getString("args_hash"))
                                || !request.toolVersion().equals(rows.getString("tool_version"))
                                || !request.contract().name().equals(rows.getString("contract"))) {
                            throw new JobException("OPERATION_CONFLICT", "Logical operation key changed payload, tool or contract.");
                        }
                        if ("COMPLETED".equals(rows.getString("state"))) {
                            return Optional.of(new ToolResult(JsonSupport.object(rows.getString("result_json"), JsonSupport.RECEIPT_BYTES)));
                        }
                        if (!"UNKNOWN".equals(rows.getString("state")) || request.contract() == ReplayContract.NON_IDEMPOTENT) {
                            throw new JobException("UNSAFE_REPLAY", "This operation is not eligible for automatic reinvocation.");
                        }
                        int attempt = rows.getInt("attempt_count") + 1;
                        if (attempt > job.spec().policy().maxAttempts()) throw new JobException("ATTEMPTS_EXHAUSTED", "Persisted attempt bound reached.");
                        execute(connection,"UPDATE operations SET state='INTENT',attempt_count=?,next_attempt_at=NULL,updated_at=? WHERE job_id=? AND operation_key=?",
                                attempt,clock.millis(),job.id(),request.operationKey());
                        execute(connection,"INSERT INTO attempts(job_id,operation_key,attempt_number,generation,outcome,started_at) VALUES(?,?,?,?,'STARTED',?)",
                                job.id(),request.operationKey(),attempt,job.generation(),clock.millis());
                        event(connection,job.id(),"OPERATION_REINVOKED",details(request.operationKey(),"attempt",attempt));
                        return Optional.empty();
                    }
                }
            }
            long now = clock.millis();
            execute(connection, "INSERT INTO operations(job_id,operation_key,invocation_key,tool_version,contract,args_hash,args_json,state,attempt_count,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'INTENT',1,?,?)",
                    job.id(), request.operationKey(), invocationKey(job.id(), request.operationKey()),
                    request.toolVersion(), request.contract().name(), hash, args, now, now);
            execute(connection, "INSERT INTO attempts(job_id,operation_key,attempt_number,generation,outcome,started_at) VALUES(?,?,1,?,'STARTED',?)",
                    job.id(), request.operationKey(), job.generation(), now);
            JsonObject details = new JsonObject();
            details.addProperty("operation_key", request.operationKey());
            details.addProperty("tool", request.toolVersion());
            details.addProperty("contract", request.contract().name());
            event(connection, job.id(), "OPERATION_INTENT", details);
            return Optional.empty();
        });
        if (prepared == null) throw new JobException("CONTROL_STOPPED","Job cancelled or expired before dispatch.");
        return prepared;
    }

    public void completeOperation(JobRecord job, ToolRequest request, ToolResult result) {
        String receipt = JsonSupport.canonical(result.receipt());
        Boolean accepted = database.transaction(connection -> {
            if (stopForControl(connection,load(connection,job.id()))) return false;
            assertCurrent(connection, job);
            String stateJson;
            try (PreparedStatement query = connection.prepareStatement("SELECT state_json FROM checkpoints WHERE job_id=? AND sequence=?")) {
                query.setString(1, job.id());
                query.setLong(2, job.checkpointSequence());
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) throw new JobException("CORRUPT_CHECKPOINT", "Missing checkpoint.");
                    stateJson = rows.getString(1);
                }
            }
            JsonObject state = JsonSupport.object(stateJson, JsonSupport.CHECKPOINT_BYTES);
            state.add(request.operationKey(), result.receipt());
            String nextJson = JsonSupport.canonical(state);
            JsonSupport.bounded(nextJson, JsonSupport.CHECKPOINT_BYTES);
            long now = clock.millis();
            int changed = execute(connection, "UPDATE operations SET state='COMPLETED',result_json=?,updated_at=? WHERE job_id=? AND operation_key=? AND state='INTENT' AND args_hash=? AND tool_version=? AND contract=?",
                    receipt, now, job.id(), request.operationKey(), JsonSupport.hash(request.arguments()),
                    request.toolVersion(), request.contract().name());
            if (changed != 1) throw new JobException("STALE_EXECUTION", "Operation completion did not match its intent.");
            execute(connection, "UPDATE attempts SET outcome='COMPLETED',ended_at=? WHERE job_id=? AND operation_key=? AND generation=? AND outcome='STARTED'",
                    now, job.id(), request.operationKey(), job.generation());
            execute(connection, "INSERT INTO checkpoints(job_id,sequence,state_json,created_at) VALUES(?,?,?,?)",
                    job.id(), job.checkpointSequence() + 1, nextJson, now);
            execute(connection, "UPDATE jobs SET checkpoint_seq=?,updated_at=? WHERE job_id=?",
                    job.checkpointSequence() + 1, now, job.id());
            JsonObject details = new JsonObject();
            details.addProperty("operation_key", request.operationKey());
            details.addProperty("checkpoint_sequence", job.checkpointSequence() + 1);
            event(connection, job.id(), "CHECKPOINT_COMMITTED", details);
            return true;
        });
        if (!accepted) throw new JobException("CONTROL_STOPPED","Job cancelled or expired before checkpoint commit.");
    }

    public void succeed(JobRecord job, JsonObject result) {
        JsonSupport.bounded(JsonSupport.json(result), JsonSupport.RECEIPT_BYTES);
        Boolean accepted = database.transaction(connection -> {
            if (stopForControl(connection,load(connection,job.id()))) return false;
            assertCurrent(connection, job);
            execute(connection, "UPDATE jobs SET state='SUCCEEDED',generation=NULL,error_code=NULL,result_json=?,updated_at=? WHERE job_id=?",
                    JsonSupport.canonical(result), clock.millis(), job.id());
            event(connection, job.id(), "SUCCEEDED", new JsonObject());
            return true;
        });
        if (!accepted) throw new JobException("CONTROL_STOPPED","Job cancelled or expired before completion.");
    }

    /** Unclassified errors may follow effects: preserve uncertainty without automatic retry. */
    public void fail(JobRecord job, String errorCode) {
        database.transaction(connection -> {
            if (stopForControl(connection,load(connection,job.id()))) return null;
            assertCurrent(connection, job);
            long now = clock.millis();
            int unknown = execute(connection, "UPDATE operations SET state='UNKNOWN',updated_at=? WHERE job_id=? AND state='INTENT'", now, job.id());
            execute(connection, "UPDATE attempts SET outcome='UNKNOWN',ended_at=? WHERE job_id=? AND generation=? AND outcome='STARTED'",
                    now, job.id(), job.generation());
            String state = unknown > 0 ? "NEEDS_REVIEW" : "FAILED";
            execute(connection, "UPDATE jobs SET state=?,generation=NULL,error_code=?,updated_at=? WHERE job_id=?",
                    state, errorCode, now, job.id());
            JsonObject details = new JsonObject();
            details.addProperty("error_code", errorCode);
            details.addProperty("unknown_operation", unknown > 0);
            event(connection, job.id(), state, details);
            return null;
        });
    }

    public JsonObject inspectKey(String requestKey) {
        return database.transaction(connection -> {
            String id;
            try (PreparedStatement query = connection.prepareStatement("SELECT job_id FROM jobs WHERE request_key=?")) {
                query.setString(1, requestKey);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) throw new JobException("JOB_NOT_FOUND", "Request key not found.");
                    id = rows.getString(1);
                }
            }
            JsonObject output = new JsonObject();
            output.addProperty("execution_mode", "deterministic_mock");
            output.addProperty("milestone", "M2");
            JobRecord job = load(connection, id);
            output.addProperty("job_id", id);
            output.addProperty("request_key", job.requestKey());
            output.addProperty("state", job.state().name());
            output.addProperty("checkpoint_sequence", job.checkpointSequence());
            output.addProperty("deadline_at",job.deadlineAt());
            output.addProperty("cancel_requested",job.cancelRequested());
            try (PreparedStatement query = connection.prepareStatement("SELECT result_json,error_code FROM jobs WHERE job_id=?")) {
                query.setString(1, id);
                try (ResultSet rows = query.executeQuery()) {
                    rows.next();
                    if (rows.getString(1) != null) output.add("result", JsonSupport.object(rows.getString(1), JsonSupport.RECEIPT_BYTES));
                    if (rows.getString(2) != null) output.addProperty("error_code", rows.getString(2));
                }
            }
            output.add("checkpoints", jsonRows(connection, "SELECT sequence,state_json FROM checkpoints WHERE job_id=? ORDER BY sequence", id, "checkpoint"));
            output.add("operations", jsonRows(connection, "SELECT operation_key,invocation_key,tool_version,contract,state,attempt_count,next_attempt_at FROM operations WHERE job_id=? ORDER BY created_at,operation_key", id, "operation"));
            output.add("attempts", jsonRows(connection,"SELECT operation_key,attempt_number,outcome,generation,started_at,ended_at FROM attempts WHERE job_id=? ORDER BY started_at,operation_key,attempt_number",id,"attempt"));
            output.add("events", jsonRows(connection, "SELECT sequence,type,details_json FROM events WHERE job_id=? ORDER BY sequence", id, "event"));
            return output;
        });
    }

    private static JsonArray jsonRows(Connection connection, String sql, String id, String kind) throws SQLException {
        JsonArray result = new JsonArray();
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, id);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    JsonObject item = new JsonObject();
                    if ("checkpoint".equals(kind)) {
                        item.addProperty("sequence", rows.getLong("sequence"));
                        item.add("state", JsonSupport.object(rows.getString("state_json"), JsonSupport.CHECKPOINT_BYTES));
                    } else if ("event".equals(kind)) {
                        item.addProperty("sequence", rows.getLong("sequence"));
                        item.addProperty("type", rows.getString("type"));
                        item.add("details", JsonSupport.object(rows.getString("details_json"), JsonSupport.RECEIPT_BYTES));
                    } else if ("attempt".equals(kind)) {
                        item.addProperty("operation_key",rows.getString("operation_key"));
                        item.addProperty("attempt_number",rows.getInt("attempt_number"));
                        item.addProperty("outcome",rows.getString("outcome"));
                        item.addProperty("generation",rows.getString("generation"));
                        item.addProperty("started_at",rows.getLong("started_at"));
                        if(rows.getObject("ended_at")!=null) item.addProperty("ended_at",rows.getLong("ended_at"));
                    } else {
                        for (String column : new String[]{"operation_key","invocation_key","tool_version","contract","state"}) item.addProperty(column, rows.getString(column));
                        item.addProperty("attempt_count", rows.getInt("attempt_count"));
                        if(rows.getObject("next_attempt_at")!=null) item.addProperty("next_attempt_at",rows.getLong("next_attempt_at"));
                    }
                    result.add(item);
                }
            }
        }
        return result;
    }

    private static JobRecord load(Connection connection, String id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM jobs WHERE job_id=?")) {
            query.setString(1, id);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new JobException("JOB_NOT_FOUND", "Job ID not found.");
                JobSpec spec = JobSpec.parse(rows.getString("spec_json"));
                long deadline = rows.getObject("deadline_at")==null ? rows.getLong("created_at")+spec.policy().jobTimeoutMillis() : rows.getLong("deadline_at");
                return new JobRecord(id, rows.getString("request_key"), spec,
                        JobState.valueOf(rows.getString("state")), rows.getLong("checkpoint_seq"), rows.getString("generation"),
                        deadline,rows.getInt("cancel_requested")!=0);
            }
        }
    }

    private static void assertCurrent(Connection connection, JobRecord expected) throws SQLException {
        JobRecord current = load(connection, expected.id());
        if (current.state() != JobState.RUNNING || expected.generation() == null
                || !expected.generation().equals(current.generation())
                || expected.checkpointSequence() != current.checkpointSequence()) {
            throw new JobException("STALE_EXECUTION", "Completion does not own the current execution.");
        }
    }

    private void event(Connection connection, String jobId, String type, JsonObject details) throws SQLException {
        long sequence;
        try (PreparedStatement query = connection.prepareStatement("SELECT COALESCE(MAX(sequence),0)+1 FROM events WHERE job_id=?")) {
            query.setString(1, jobId);
            try (ResultSet rows = query.executeQuery()) { rows.next(); sequence = rows.getLong(1); }
        }
        execute(connection, "INSERT INTO events(job_id,sequence,type,details_json,created_at) VALUES(?,?,?,?,?)",
                jobId, sequence, type, JsonSupport.canonical(details), clock.millis());
    }

    private static int execute(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement.executeUpdate();
        }
    }

    public static String invocationKey(String jobId, String operationKey) { return jobId + ":" + operationKey; }
}
