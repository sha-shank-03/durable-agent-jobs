package dev.durablejobs.cli;

import com.google.gson.*;
import dev.durablejobs.api.*;
import dev.durablejobs.mock.*;
import dev.durablejobs.runtime.*;
import dev.durablejobs.store.*;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Supervised owned child JVMs and fictional local databases; never recorded model replay. */
public final class RecoveryDemo {
    private RecoveryDemo() {}
    public static JsonObject run(Path parent,String scenario) throws IOException {
        if(!Set.of("crash-idempotent","crash-unsafe","all").contains(scenario))
            throw new JobException("UNSUPPORTED_SCENARIO","Unknown mock scenario.");
        Files.createDirectories(parent);
        Path root=Files.createTempDirectory(parent,"recovery-");
        JsonArray reports=new JsonArray();
        if(!"crash-unsafe".equals(scenario)) reports.add(crash(root.resolve("keyed"),false));
        if(!"crash-idempotent".equals(scenario)) reports.add(crash(root.resolve("unsafe"),true));
        if("all".equals(scenario)) {
            reports.add(exhaustion(root.resolve("exhaustion")));
            reports.add(controls(root.resolve("controls")));
            reports.add(timeout(root.resolve("timeout")));
            reports.add(exclusivity(root.resolve("lock")));
        }
        JsonObject report=new JsonObject();
        report.addProperty("execution_mode","deterministic_mock"); report.addProperty("run_dir",root.toString());
        report.addProperty("scenario",scenario); report.addProperty("assertions_failed",0); report.add("scenarios",reports);
        return report;
    }
    public static JsonObject crash(Path root,boolean unsafe) throws IOException {
        Files.createDirectories(root);
        Path db=root.resolve("jobs.db"), tool=root.resolve("tool.db");
        SqliteJobStore store=new SqliteJobStore(db);
        JobSpec spec=new JobSpec(1,unsafe?JobSpec.UNSAFE_MOCK_AGENT:JobSpec.MOCK_AGENT,"Fictional crash ticket","Synthetic.",20);
        store.submit("crash-001",spec);
        List<String> args=new ArrayList<>(List.of("--db",db.toString(),"--tool-db",tool.toString(),"worker","--drain"));
        if(unsafe) args.add("--unsafe-mock");
        List<String> crashArgs=new ArrayList<>(args); crashArgs.addAll(List.of("--mock-fault","after-tool-commit"));
        int crashed=child(root,"crashed",crashArgs);
        require(crashed==86,"Owned child did not halt at the specified boundary.");
        JsonObject gap=store.inspectKey("crash-001");
        require("RUNNING".equals(gap.get("state").getAsString()) && gap.get("checkpoint_sequence").getAsInt()==1,"Crash did not retain the runtime gap.");
        require(sqlCount(db,"SELECT count(*) FROM operations WHERE operation_key='create-ticket' AND state='INTENT' AND result_json IS NULL")==1,"Missing unfinished persisted intent.");
        require(sqlCount(tool,unsafe?"SELECT count(*) FROM unsafe_ticket_effects":"SELECT count(*) FROM ticket_receipts")==1,"Tool did not commit before halt.");
        Files.writeString(root.resolve("gap-inspect.json"),JsonSupport.json(gap));
        int recovered=child(root,"recovered",args);
        require(recovered==(unsafe?3:0),"Recovery child returned an unexpected exit.");
        JsonObject after=new SqliteJobStore(db).inspectKey("crash-001");
        String expected=unsafe?"NEEDS_REVIEW":"SUCCEEDED";
        require(expected.equals(after.get("state").getAsString()),"Unexpected recovered state.");
        long calls=sqlCount(tool,unsafe?"SELECT count(*) FROM unsafe_ticket_effects":"SELECT count(*) FROM tool_invocations");
        long effects=sqlCount(tool,unsafe?"SELECT count(*) FROM unsafe_ticket_effects":"SELECT count(*) FROM ticket_receipts");
        require(calls==(unsafe?1:2) && effects==1,"Reinvocation/effect count violated contract.");
        if(!unsafe) require(sqlCount(tool,"SELECT count(DISTINCT invocation_key) FROM tool_invocations")==1,"Recovery changed its stable invocation key.");
        JsonObject result=base(unsafe?"crash-unsafe":"crash-idempotent",expected);
        result.addProperty("child_exit",crashed); result.addProperty("recovery_child_exit",recovered);
        result.addProperty("invocation_count",calls); result.addProperty("effect_count",effects);
        result.addProperty("stable_operation_key",true); result.addProperty("runtime_gap_checkpoint",1);
        result.add("job",after);
        return result;
    }
    private static JsonObject exhaustion(Path root) throws IOException {
        Files.createDirectories(root);
        Path db=root.resolve("jobs.db"),toolPath=root.resolve("tool.db");
        SqliteJobStore store=new SqliteJobStore(db); MockTicketTool tool=new MockTicketTool(toolPath);
        AtomicInteger calls=new AtomicInteger();
        ToolAdapter transientTool=new ToolAdapter() {
            public String version() { return tool.version(); }
            public ReplayContract contract() { return tool.contract(); }
            public ToolResult execute(ToolCall call) { calls.incrementAndGet(); throw new ToolFailure("MOCK_TRANSIENT",ToolFailure.Kind.TRANSIENT_NO_EFFECT); }
        };
        store.submit("exhaust-001",new JobSpec(1,JobSpec.MOCK_AGENT,"Fictional retry","Synthetic.",20,new JobPolicy(3,0,5000,60000)));
        new JobWorker(store,new MockTicketAgent(),List.of(new NormalizeTool(),transientTool)).drain();
        require(calls.get()==3 && sqlCount(db,"SELECT attempt_count FROM operations WHERE operation_key='create-ticket'")==3,"Attempt bound not persisted.");
        JsonObject before=store.inspectKey("exhaust-001");
        require("FAILED".equals(before.get("state").getAsString()),"Retry exhaustion did not fail.");
        require(child(root,"restart",List.of("--db",db.toString(),"--tool-db",toolPath.toString(),"worker","--drain"))==0,"Terminal restart failed.");
        require(before.equals(store.inspectKey("exhaust-001")) && tool.stats().effectCount()==0,"Restart reset exhausted attempts.");
        JsonObject result=base("retry-exhaustion","FAILED"); result.addProperty("attempt_count",3);
        result.addProperty("restart_preserved_exhaustion",true); result.addProperty("effect_count",0); return result;
    }
    private static JsonObject controls(Path root) throws IOException {
        Files.createDirectories(root);
        Clock initial=Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"),ZoneOffset.UTC);
        SqliteJobStore store=new SqliteJobStore(root.resolve("jobs.db"),initial);
        MockTicketTool tool=new MockTicketTool(root.resolve("tool.db"));
        JobSpec spec=new JobSpec(1,JobSpec.MOCK_AGENT,"Fictional controlled job","Synthetic.",20,new JobPolicy(3,0,100,20));
        store.submit("cancel-001",spec); store.cancelKey("cancel-001");
        store.submit("expire-001",spec);
        SqliteJobStore later=new SqliteJobStore(store.path(),Clock.offset(initial,Duration.ofMillis(21)));
        new JobWorker(later,new MockTicketAgent(),List.of(new NormalizeTool(),tool)).drain();
        require("CANCELLED".equals(later.inspectKey("cancel-001").get("state").getAsString()),"Queued cancellation not durable.");
        require("TIMED_OUT".equals(later.inspectKey("expire-001").get("state").getAsString()),"Expired job dispatched.");
        require(tool.stats().invocationCount()==0,"Cancelled/expired job reached ticket tool.");
        JsonObject result=base("cancel-deadline","PASSED"); result.addProperty("clock_mode","injected_fixed_clock");
        result.addProperty("cancelled_state","CANCELLED"); result.addProperty("expired_state","TIMED_OUT"); result.addProperty("effect_count",0); return result;
    }
    private static JsonObject timeout(Path root) throws IOException {
        Files.createDirectories(root);
        SqliteJobStore store=new SqliteJobStore(root.resolve("jobs.db")); MockTicketTool tool=new MockTicketTool(root.resolve("tool.db"));
        ToolAdapter slow=new ToolAdapter() {
            public String version() { return NormalizeTool.VERSION; }
            public ReplayContract contract() { return ReplayContract.PURE; }
            public ToolResult execute(ToolCall call) {
                try { Thread.sleep(10_000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                return new NormalizeTool().execute(call);
            }
        };
        store.submit("timeout-001",new JobSpec(1,JobSpec.MOCK_AGENT,"Fictional timeout","Synthetic.",20,new JobPolicy(3,0,30,60000)));
        new JobWorker(store,new MockTicketAgent(),List.of(slow,tool)).drain();
        require("FAILED".equals(store.inspectKey("timeout-001").get("state").getAsString()),"Cooperative timeout did not exhaust.");
        require(sqlCount(store.path(),"SELECT attempt_count FROM operations WHERE operation_key='normalize'")==3,"Timeout bound wrong.");
        require(tool.stats().effectCount()==0,"Late timeout result advanced to ticket.");
        JsonObject result=base("cooperative-timeout","FAILED"); result.addProperty("attempt_count",3); result.addProperty("late_result_committed",false); return result;
    }
    private static JsonObject exclusivity(Path root) throws IOException {
        Files.createDirectories(root);
        SqliteJobStore store=new SqliteJobStore(root.resolve("jobs.db"));
        try(WorkerLock held=WorkerLock.acquire(store.path())) {
            int exit=child(root,"second-worker",List.of("--db",store.path().toString(),"worker","--drain"));
            require(exit==4,"Second owned worker was not safely rejected.");
        }
        JsonObject result=base("worker-lock","REJECTED"); result.addProperty("second_worker_exit",4); return result;
    }
    public static int child(Path root,String label,List<String> cliArgs) throws IOException {
        String binary=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        List<String> command=new ArrayList<>(List.of(binary,"-Xmx256m","-XX:MaxMetaspaceSize=128m","-cp",System.getProperty("java.class.path"),Main.class.getName()));
        command.addAll(cliArgs);
        Process process=new ProcessBuilder(command).redirectOutput(root.resolve(label+".stdout.json").toFile())
                .redirectError(root.resolve(label+".stderr.json").toFile()).start();
        try {
            if(!process.waitFor(20,TimeUnit.SECONDS)) {
                terminate(process);
                throw new JobException("CHILD_TIMEOUT","Supervised owned child exceeded demo deadline.");
            }
            return process.exitValue();
        } catch(InterruptedException e) {
            terminate(process); Thread.currentThread().interrupt();
            throw new JobException("CHILD_INTERRUPTED","Owned child interrupted.",e);
        } finally {
            if(process.isAlive()) terminate(process);
        }
    }
    private static void terminate(Process process) {
        process.destroyForcibly();
        boolean interrupted=false;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        try {
            while(process.isAlive() && System.nanoTime()<deadline) {
                try { process.waitFor(100,TimeUnit.MILLISECONDS); }
                catch(InterruptedException e) { interrupted=true; }
            }
            if(process.isAlive()) throw new JobException("CHILD_DID_NOT_STOP","Owned child did not exit.");
        } finally { if(interrupted) Thread.currentThread().interrupt(); }
    }
    public static long sqlCount(Path database,String fixedSql) {
        return new SqliteDatabase(database).read(connection -> {
            try(Statement query=connection.createStatement();ResultSet rows=query.executeQuery(fixedSql)) { rows.next(); return rows.getLong(1); }
        });
    }
    private static JsonObject base(String scenario,String state) {
        JsonObject result=new JsonObject(); result.addProperty("scenario",scenario); result.addProperty("state",state); return result;
    }
    private static void require(boolean condition,String message) { if(!condition) throw new JobException("DEMO_ASSERTION_FAILED",message); }
}
