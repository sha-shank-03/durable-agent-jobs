package dev.durablejobs;

import com.google.gson.JsonObject;
import dev.durablejobs.api.*;
import dev.durablejobs.mock.*;
import dev.durablejobs.runtime.JobWorker;
import dev.durablejobs.runtime.WorkerLock;
import dev.durablejobs.store.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JobFlowTest {
    @TempDir Path directory;
    private JobSpec spec() { return new JobSpec(1, JobSpec.MOCK_AGENT, " Fictional export request ", "Synthetic data.", 20); }
    private SqliteJobStore store() { return new SqliteJobStore(directory.resolve("jobs.db")); }
    private MockTicketTool tool() { return new MockTicketTool(directory.resolve("tool.db")); }
    private JobWorker worker(SqliteJobStore store, MockTicketTool tool) {
        return new JobWorker(store, new MockTicketAgent(), List.of(new NormalizeTool(), tool));
    }

    @Test void normalJobPersistsBothToolReceiptsAndOrderedCheckpoints() {
        SqliteJobStore store = store();
        MockTicketTool tool = tool();
        String id = store.submit("request-001", spec()).jobId();
        JobWorker.RunSummary summary = worker(store, tool).drain();
        assertEquals(new JobWorker.RunSummary(1, 1, 0, 0), summary);
        assertEquals(JobState.SUCCEEDED, store.get(id).state());
        JsonObject inspection = store.inspectKey("request-001");
        assertEquals(2, inspection.get("checkpoint_sequence").getAsInt());
        assertEquals(3, inspection.getAsJsonArray("checkpoints").size());
        assertEquals(2, inspection.getAsJsonArray("operations").size());
        assertEquals("Fictional export request", inspection.getAsJsonObject("result").get("title").getAsString());
        assertEquals("deterministic_mock", inspection.get("execution_mode").getAsString());
        assertEquals(7, inspection.getAsJsonArray("events").size());
        for (int i = 0; i < 7; i++) {
            assertEquals(i + 1, inspection.getAsJsonArray("events").get(i).getAsJsonObject().get("sequence").getAsInt());
        }
        assertEquals(new MockTicketTool.Stats(1, 1), tool.stats());
    }

    @Test void duplicateDeliveryBeforeAndAfterCompletionKeepsIdentityAndEffectCount() {
        SqliteJobStore store = store();
        MockTicketTool tool = tool();
        SqliteJobStore.Submission first = store.submit("delivery-001", spec());
        SqliteJobStore.Submission duplicate = store.submit("delivery-001", JobSpec.parse(JsonSupport.json(spec().json())));
        assertFalse(first.duplicate());
        assertTrue(duplicate.duplicate());
        assertEquals(first.jobId(), duplicate.jobId());
        worker(store, tool).drain();
        JsonObject persisted = store.inspectKey("delivery-001");
        SqliteJobStore reopened = store();
        MockTicketTool reopenedTool = tool();
        assertEquals(first.jobId(), reopened.submit("delivery-001", spec()).jobId());
        assertEquals(0, worker(reopened, reopenedTool).drain().processed());
        assertEquals(new MockTicketTool.Stats(1, 1), reopenedTool.stats());
        assertEquals(persisted, reopened.inspectKey("delivery-001"));
    }

    @Test void changedSpecUnderRequestKeyFailsBeforeAnyToolEffect() {
        SqliteJobStore store = store();
        MockTicketTool tool = tool();
        String id = store.submit("conflict", spec()).jobId();
        JobException conflict = assertThrows(JobException.class, () ->
                store.submit("conflict", new JobSpec(1, JobSpec.MOCK_AGENT, "Changed title", "Synthetic data.", 20)));
        assertEquals("REQUEST_CONFLICT", conflict.code());
        assertEquals(JobState.QUEUED, store.get(id).state());
        assertEquals(new MockTicketTool.Stats(0, 0), tool.stats());
    }

    @Test void explicitDefaultAndMemberOrderDoNotChangeValidatedSpecHash() {
        SqliteJobStore store = store();
        JobSpec omitted = JobSpec.parse("""
                {"request":{"body":"Synthetic data.","title":" Fictional export request "},
                 "agent":"mock-ticket/v1","schema_version":1}
                """);
        assertEquals(store.submit("defaults", spec()).jobId(), store.submit("defaults", omitted).jobId());
    }

    @Test void logicalOperationConflictLeavesOriginalIntentUnchanged() {
        SqliteJobStore store = store();
        store.submit("intent", spec());
        JobRecord job = store.claimNext().orElseThrow();
        ToolRequest request = ((AgentDecision.Invoke) new MockTicketAgent().next(job.spec(), store.checkpoint(job))).request();
        assertTrue(store.prepareOperation(job, request).isEmpty());
        JsonObject changed = request.arguments();
        changed.addProperty("title", "Changed title");
        JobException conflict = assertThrows(JobException.class, () -> store.prepareOperation(job,
                new ToolRequest(request.operationKey(), request.toolVersion(), request.contract(), changed)));
        assertEquals("OPERATION_CONFLICT", conflict.code());
        assertEquals(0, store.get(job.id()).checkpointSequence());
        assertEquals(1, store.inspectKey("intent").getAsJsonArray("operations").size());
    }

    @Test void keyedToolAtomicallyDeduplicatesAndRejectsDifferentPayload() {
        MockTicketTool tool = tool();
        JsonObject arguments = spec().json().getAsJsonObject("request");
        ToolCall call = new ToolCall("job-1:create-ticket", arguments);
        ToolResult first = tool.execute(call);
        assertEquals(first.receipt(), tool.execute(call).receipt());
        JsonObject changed = arguments.deepCopy();
        changed.addProperty("title", "Changed");
        assertEquals("TOOL_KEY_CONFLICT", assertThrows(JobException.class,
                () -> tool.execute(new ToolCall(call.invocationKey(), changed))).code());
        assertEquals(new MockTicketTool.Stats(3, 1), tool.stats());
    }

    @Test void stepBoundPreventsTicketDispatchAfterNormalization() {
        SqliteJobStore store = store();
        MockTicketTool tool = tool();
        store.submit("bounded", new JobSpec(1, JobSpec.MOCK_AGENT, "Fictional request", "Synthetic.", 1));
        assertEquals(1, worker(store, tool).drain().failed());
        JsonObject result = store.inspectKey("bounded");
        assertEquals("FAILED", result.get("state").getAsString());
        assertEquals("STEP_LIMIT", result.get("error_code").getAsString());
        assertEquals(new MockTicketTool.Stats(0, 0), tool.stats());
    }

    @Test void staleCompletionCannotOverwriteNewCheckpoint() {
        SqliteJobStore store = store();
        store.submit("fence", spec());
        JobRecord job = store.claimNext().orElseThrow();
        ToolRequest request = ((AgentDecision.Invoke) new MockTicketAgent().next(job.spec(), store.checkpoint(job))).request();
        store.prepareOperation(job, request);
        store.completeOperation(job, request, new NormalizeTool().execute(
                new ToolCall(SqliteJobStore.invocationKey(job.id(), request.operationKey()), request.arguments())));
        assertEquals("STALE_EXECUTION", assertThrows(JobException.class, () -> store.succeed(job, new JsonObject())).code());
        assertEquals(1, store.get(job.id()).checkpointSequence());
        assertEquals(JobState.RUNNING, store.get(job.id()).state());
    }

    @Test void abandonedJobWithoutIntentResumesFromItsEmptyCheckpoint() {
        SqliteJobStore store = store();
        MockTicketTool tool = tool();
        store.submit("abandoned", spec());
        store.claimNext().orElseThrow();
        assertEquals(1,worker(store,tool).drain().succeeded());
        assertEquals(new MockTicketTool.Stats(1, 1), tool.stats());
    }

    @Test void responseFailureAfterMockEffectRemainsExplicitlyUncertain() {
        SqliteJobStore store = store();
        MockTicketTool actual = tool();
        ToolAdapter throwing = new ToolAdapter() {
            public String version() { return actual.version(); }
            public ReplayContract contract() { return actual.contract(); }
            public ToolResult execute(ToolCall call) {
                actual.execute(call);
                throw new JobException("SIMULATED_LOST_RESPONSE", "Mock response failed after commit.");
            }
        };
        store.submit("lost-response", spec());
        assertEquals(1, new JobWorker(store, new MockTicketAgent(), List.of(new NormalizeTool(), throwing)).drain().needsReview());
        assertEquals("NEEDS_REVIEW", store.inspectKey("lost-response").get("state").getAsString());
        assertEquals(new MockTicketTool.Stats(1, 1), actual.stats());
        assertEquals(0, worker(store, actual).drain().processed());
        assertEquals(new MockTicketTool.Stats(1, 1), actual.stats());
    }

    @Test void missingCheckpointTableRollsBackSubmissionInsteadOfReportingSuccess() {
        SqliteJobStore store = store();
        SqliteDatabase raw = new SqliteDatabase(store.path());
        raw.transaction(connection -> {
            try (Statement statement = connection.createStatement()) { statement.execute("DROP TABLE checkpoints"); }
            return null;
        });
        assertEquals("STORAGE_ERROR", assertThrows(JobException.class, () -> store.submit("broken", spec())).code());
        long jobs = raw.read(connection -> {
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT count(*) FROM jobs")) {
                rows.next(); return rows.getLong(1);
            }
        });
        assertEquals(0, jobs);
    }

    @Test void unknownDatabaseVersionIsRejected() {
        SqliteJobStore store = store();
        new SqliteDatabase(store.path()).transaction(connection -> {
            try (Statement statement = connection.createStatement()) { statement.execute("PRAGMA user_version=2"); }
            return null;
        });
        assertEquals("UNSUPPORTED_DATABASE", assertThrows(JobException.class, this::store).code());
    }

    @Test void mockToolFileCannotBeMistakenForJobDatabase() {
        MockTicketTool tool = tool();
        assertEquals("UNSUPPORTED_DATABASE", assertThrows(JobException.class, () -> new SqliteJobStore(tool.path())).code());
    }

    @Test void secondWorkerLockFailsAndCloseAllowsNextWorker() {
        SqliteJobStore store = store();
        try (WorkerLock first = WorkerLock.acquire(store.path())) {
            assertEquals("WORKER_ALREADY_RUNNING", assertThrows(JobException.class, () -> WorkerLock.acquire(store.path())).code());
        }
        try (WorkerLock next = WorkerLock.acquire(store.path())) { assertNotNull(next); }
    }
}
