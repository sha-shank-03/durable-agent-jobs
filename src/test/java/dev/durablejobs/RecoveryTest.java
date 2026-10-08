package dev.durablejobs;

import com.google.gson.JsonObject;
import dev.durablejobs.api.*;
import dev.durablejobs.cli.RecoveryDemo;
import dev.durablejobs.mock.*;
import dev.durablejobs.runtime.*;
import dev.durablejobs.store.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryTest {
    @TempDir Path root;
    private JobSpec spec(JobPolicy policy) { return new JobSpec(1,JobSpec.MOCK_AGENT,"Fictional recovery","Synthetic.",20,policy); }
    private JobWorker worker(SqliteJobStore store,ToolAdapter first,MockTicketTool ticket) {
        return new JobWorker(store,new MockTicketAgent(),List.of(first,ticket));
    }
    private static ToolRequest normalize(JobRecord job,SqliteJobStore store) {
        return ((AgentDecision.Invoke)new MockTicketAgent().next(job.spec(),store.checkpoint(job))).request();
    }
    private static final class TestClock extends Clock {
        private final AtomicLong now=new AtomicLong(Instant.parse("2026-10-07T00:00:00Z").toEpochMilli());
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        public long millis() { return now.get(); }
        void advance(long millis) { now.addAndGet(millis); }
    }
    @Test void ownedJvmReallyHaltsInKeyedToolCommitGapAndRecoversOneEffect() throws Exception {
        JsonObject result=RecoveryDemo.crash(root.resolve("keyed"),false);
        assertEquals(86,result.get("child_exit").getAsInt());
        assertEquals(2,result.get("invocation_count").getAsInt());
        assertEquals(1,result.get("effect_count").getAsInt());
        assertEquals("SUCCEEDED",result.get("state").getAsString());
    }
    @Test void ownedUnsafeJvmGapPausesAndNeverReinvokes() throws Exception {
        JsonObject result=RecoveryDemo.crash(root.resolve("unsafe"),true);
        assertEquals(86,result.get("child_exit").getAsInt());
        assertEquals(1,result.get("invocation_count").getAsInt());
        assertEquals("NEEDS_REVIEW",result.get("state").getAsString());
    }
    @Test void classifiedTransientFailuresRetryWithinPersistedBoundsThenSucceed() {
        SqliteJobStore store=new SqliteJobStore(root.resolve("jobs.db"));
        MockTicketTool ticket=new MockTicketTool(root.resolve("tool.db"));
        AtomicInteger calls=new AtomicInteger();
        ToolAdapter retrying=new ToolAdapter() {
            public String version() { return ticket.version(); }
            public ReplayContract contract() { return ticket.contract(); }
            public ToolResult execute(ToolCall call) {
                if(calls.incrementAndGet()<3) throw new ToolFailure("MOCK_TRANSIENT",ToolFailure.Kind.TRANSIENT_NO_EFFECT);
                return ticket.execute(call);
            }
        };
        store.submit("retry",spec(new JobPolicy(3,0,5000,60000)));
        assertEquals(1,new JobWorker(store,new MockTicketAgent(),List.of(new NormalizeTool(),retrying)).drain().succeeded());
        assertEquals(3,calls.get()); assertEquals(1,ticket.stats().effectCount());
        assertEquals(3,RecoveryDemo.sqlCount(store.path(),"SELECT attempt_count FROM operations WHERE operation_key='create-ticket'"));
        assertFalse(store.inspectKey("retry").has("error_code"));
    }
    @Test void retryDueTimeAndAttemptCountSurviveStoreReopenWithoutEarlyDispatch() {
        TestClock clock=new TestClock();
        SqliteJobStore store=new SqliteJobStore(root.resolve("jobs.db"),clock);
        store.submit("due",spec(new JobPolicy(3,500,5000,60000)));
        JobRecord first=store.claimNext().orElseThrow(); ToolRequest request=normalize(first,store);
        store.prepareOperation(first,request);
        assertEquals(JobState.WAITING_RETRY,store.recordToolFailure(first,request,"TRANSIENT",true,false));
        SqliteJobStore reopened=new SqliteJobStore(store.path(),clock);
        assertTrue(reopened.claimNext().isEmpty());
        clock.advance(499); assertTrue(reopened.claimNext().isEmpty());
        clock.advance(1);
        JobRecord second=reopened.claimNext().orElseThrow();
        ToolRequest persisted=reopened.pendingOperation(second).orElseThrow();
        assertEquals(request,persisted);
        reopened.prepareOperation(second,persisted);
        assertEquals(2,RecoveryDemo.sqlCount(store.path(),"SELECT attempt_count FROM operations"));
        assertNotEquals(first.generation(),second.generation());
    }
    @Test void repeatedSyntheticAbandonmentsConsumeBudgetAndCannotResetOnRestart() {
        TestClock clock=new TestClock();
        SqliteJobStore store=new SqliteJobStore(root.resolve("jobs.db"),clock);
        String id=store.submit("crashes",spec(new JobPolicy(3,0,5000,60000))).jobId();
        for(int attempt=1;attempt<=3;attempt++) {
            SqliteJobStore reopened=new SqliteJobStore(store.path(),clock);
            JobRecord job=reopened.claimNext().orElseThrow();
            ToolRequest request=reopened.pendingOperation(job).orElseGet(() -> normalize(job,reopened));
            reopened.prepareOperation(job,request);
            reopened.recoverAbandoned();
        }
        assertEquals(JobState.FAILED,store.get(id).state());
        assertEquals(3,RecoveryDemo.sqlCount(store.path(),"SELECT attempt_count FROM operations"));
        assertTrue(new SqliteJobStore(store.path(),clock).claimNext().isEmpty());
    }
    @Test void classifiedExhaustionRemainsTerminalAfterReopenWithoutDispatch() {
        SqliteJobStore store=new SqliteJobStore(root.resolve("jobs.db")); MockTicketTool ticket=new MockTicketTool(root.resolve("tool.db"));
        AtomicInteger calls=new AtomicInteger();
        ToolAdapter failing=new ToolAdapter() {
            public String version() { return NormalizeTool.VERSION; }
            public ReplayContract contract() { return ReplayContract.PURE; }
            public ToolResult execute(ToolCall call) { calls.incrementAndGet(); throw new ToolFailure("TRANSIENT",ToolFailure.Kind.TRANSIENT_NO_EFFECT); }
        };
        store.submit("exhaust",spec(new JobPolicy(2,0,5000,60000)));
        assertEquals(1,worker(store,failing,ticket).drain().failed());
        JsonObject before=store.inspectKey("exhaust");
        assertEquals("ATTEMPTS_EXHAUSTED",before.get("error_code").getAsString());
        SqliteJobStore reopened=new SqliteJobStore(store.path());
        assertEquals(0,worker(reopened,failing,ticket).drain().processed());
        assertEquals(before,reopened.inspectKey("exhaust"));
        assertEquals(2,calls.get()); assertEquals(2,RecoveryDemo.sqlCount(store.path(),"SELECT attempt_count FROM operations"));
        assertEquals(0,ticket.stats().effectCount());
    }
    @Test void cooperativeAttemptTimeoutDiscardsReturnedResultsAndExhausts() {
        SqliteJobStore store=new SqliteJobStore(root.resolve("jobs.db")); MockTicketTool ticket=new MockTicketTool(root.resolve("tool.db"));
        AtomicInteger calls=new AtomicInteger(),stopped=new AtomicInteger();
        ToolAdapter slow=new ToolAdapter() {
            public String version() { return NormalizeTool.VERSION; }
            public ReplayContract contract() { return ReplayContract.PURE; }
            public ToolResult execute(ToolCall call) {
                calls.incrementAndGet();
                try { new CountDownLatch(1).await(); } catch(InterruptedException e) { stopped.incrementAndGet(); Thread.currentThread().interrupt(); }
                return new NormalizeTool().execute(call);
            }
        };
        String id=store.submit("timeout",spec(new JobPolicy(3,0,100,60000))).jobId();
        assertEquals(1,worker(store,slow,ticket).drain().failed());
        assertEquals(3,calls.get()); assertEquals(3,stopped.get());
        assertEquals(0,store.get(id).checkpointSequence()); assertEquals(0,ticket.stats().effectCount());
        assertEquals(0,RecoveryDemo.sqlCount(store.path(),"SELECT count(*) FROM operations WHERE result_json IS NOT NULL"));
    }
    @Test void receiptCommitRechecksDeadlineAndPersistsTimeoutBeforeRejecting() {
        TestClock clock=new TestClock(); SqliteJobStore store=new SqliteJobStore(root.resolve("jobs.db"),clock);
        String id=store.submit("commit-expiry",spec(new JobPolicy(3,0,5000,20))).jobId();
        JobRecord job=store.claimNext().orElseThrow(); ToolRequest request=normalize(job,store);
        store.prepareOperation(job,request);
        ToolResult result=new NormalizeTool().execute(new ToolCall(SqliteJobStore.invocationKey(id,request.operationKey()),request.arguments()));
        clock.advance(20);
        assertEquals("CONTROL_STOPPED",assertThrows(JobException.class,() -> store.completeOperation(job,request,result)).code());
        assertEquals(JobState.TIMED_OUT,store.get(id).state()); assertNull(store.get(id).generation());
        assertEquals(0,store.get(id).checkpointSequence());
        assertEquals(0,RecoveryDemo.sqlCount(store.path(),"SELECT count(*) FROM operations WHERE result_json IS NOT NULL"));
    }
    @Test void failureWritePreservesConcurrentCancellationAndExpiry() {
        TestClock clock=new TestClock(); SqliteJobStore store=new SqliteJobStore(root.resolve("jobs.db"),clock);
        String cancelled=store.submit("cancel-before-fail",spec(JobPolicy.DEFAULT)).jobId();
        JobRecord first=store.claimNext().orElseThrow(); store.cancelKey("cancel-before-fail");
        JsonObject before=store.inspectKey("cancel-before-fail"); store.fail(first,"AGENT_ERROR");
        assertEquals(before,store.inspectKey("cancel-before-fail")); assertEquals(JobState.CANCELLED,store.get(cancelled).state());
        String expired=store.submit("expire-before-fail",spec(new JobPolicy(3,0,5000,20))).jobId();
        JobRecord second=store.claimNext().orElseThrow(); clock.advance(20); store.fail(second,"AGENT_ERROR");
        assertEquals(JobState.TIMED_OUT,store.get(expired).state());
        assertEquals("JOB_DEADLINE",store.inspectKey("expire-before-fail").get("error_code").getAsString());
    }
    @Test void expiredHeadOfQueueDoesNotHideReadyJob() {
        TestClock clock=new TestClock(); SqliteJobStore store=new SqliteJobStore(root.resolve("jobs.db"),clock);
        String expired=store.submit("expired-first",spec(new JobPolicy(3,0,5000,20))).jobId(); clock.advance(1);
        String ready=store.submit("ready",spec(JobPolicy.DEFAULT)).jobId(); clock.advance(19);
        assertEquals(ready,store.claimNext().orElseThrow().id());
        assertEquals(JobState.TIMED_OUT,store.get(expired).state());
    }
    @Test void permanentNoEffectFailureUsesOneAttempt() {
        SqliteJobStore store=new SqliteJobStore(root.resolve("jobs.db")); MockTicketTool ticket=new MockTicketTool(root.resolve("tool.db"));
        AtomicInteger calls=new AtomicInteger();
        ToolAdapter permanent=new ToolAdapter() {
            public String version() { return NormalizeTool.VERSION; }
            public ReplayContract contract() { return ReplayContract.PURE; }
            public ToolResult execute(ToolCall call) { calls.incrementAndGet(); throw new ToolFailure("PERMANENT",ToolFailure.Kind.PERMANENT_NO_EFFECT); }
        };
        store.submit("permanent",spec(JobPolicy.DEFAULT));
        assertEquals(1,worker(store,permanent,ticket).drain().failed());
        assertEquals(1,calls.get()); assertEquals(0,ticket.stats().effectCount());
    }
    @Test void queuedCancelledAndExpiredJobsNeverDispatch() {
        TestClock clock=new TestClock();
        SqliteJobStore store=new SqliteJobStore(root.resolve("jobs.db"),clock);
        MockTicketTool ticket=new MockTicketTool(root.resolve("tool.db"));
        store.submit("cancel",spec(JobPolicy.DEFAULT)); assertTrue(store.cancelKey("cancel"));
        store.submit("expired",spec(new JobPolicy(3,0,100,20))); clock.advance(21);
        worker(store,new NormalizeTool(),ticket).drain();
        assertEquals("CANCELLED",store.inspectKey("cancel").get("state").getAsString());
        assertEquals("TIMED_OUT",store.inspectKey("expired").get("state").getAsString());
        assertEquals(0,RecoveryDemo.sqlCount(store.path(),"SELECT count(*) FROM operations"));
        assertEquals(0,ticket.stats().effectCount());
    }
    @Test void durableCancelStopsRunningCooperativeToolAndRejectsLateCommit() throws Exception {
        SqliteJobStore store=new SqliteJobStore(root.resolve("jobs.db")); MockTicketTool ticket=new MockTicketTool(root.resolve("tool.db"));
        String id=store.submit("running-cancel",spec(JobPolicy.DEFAULT)).jobId();
        CountDownLatch entered=new CountDownLatch(1), exited=new CountDownLatch(1);
        ToolAdapter gated=new ToolAdapter() {
            public String version() { return NormalizeTool.VERSION; }
            public ReplayContract contract() { return ReplayContract.PURE; }
            public ToolResult execute(ToolCall call) {
                entered.countDown();
                try { new CountDownLatch(1).await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { exited.countDown(); }
                return new NormalizeTool().execute(call);
            }
        };
        ExecutorService control=Executors.newSingleThreadExecutor();
        try {
            Future<JobWorker.RunSummary> result=control.submit(() -> worker(store,gated,ticket).drain());
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            JobRecord stale=store.get(id); ToolRequest request=normalize(stale,store);
            assertTrue(new SqliteJobStore(store.path()).cancelKey("running-cancel"));
            assertEquals(1,result.get(5,TimeUnit.SECONDS).cancelled());
            assertTrue(exited.await(1,TimeUnit.SECONDS));
            assertThrows(JobException.class,() -> store.completeOperation(stale,request,new NormalizeTool().execute(
                    new ToolCall(SqliteJobStore.invocationKey(id,request.operationKey()),request.arguments()))));
            assertEquals(0,store.get(id).checkpointSequence()); assertEquals(0,ticket.stats().effectCount());
        } finally { control.shutdownNow(); assertTrue(control.awaitTermination(5,TimeUnit.SECONDS)); }
    }
    @Test void expiryDuringRunningToolInvalidatesGenerationAndStopsLaterSteps() throws Exception {
        TestClock clock=new TestClock(); SqliteJobStore store=new SqliteJobStore(root.resolve("jobs.db"),clock);
        MockTicketTool ticket=new MockTicketTool(root.resolve("tool.db"));
        String id=store.submit("running-expiry",spec(new JobPolicy(3,0,5000,1000))).jobId();
        CountDownLatch entered=new CountDownLatch(1);
        ToolAdapter gated=new ToolAdapter() {
            public String version() { return NormalizeTool.VERSION; }
            public ReplayContract contract() { return ReplayContract.PURE; }
            public ToolResult execute(ToolCall call) {
                entered.countDown();
                try { new CountDownLatch(1).await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                return new NormalizeTool().execute(call);
            }
        };
        ExecutorService control=Executors.newSingleThreadExecutor();
        try {
            Future<JobWorker.RunSummary> result=control.submit(() -> worker(store,gated,ticket).drain());
            assertTrue(entered.await(5,TimeUnit.SECONDS)); clock.advance(1001);
            assertEquals(1,result.get(5,TimeUnit.SECONDS).timedOut());
            assertEquals(JobState.TIMED_OUT,store.get(id).state()); assertNull(store.get(id).generation());
            assertEquals(0,store.get(id).checkpointSequence()); assertEquals(0,ticket.stats().effectCount());
        } finally { control.shutdownNow(); assertTrue(control.awaitTermination(5,TimeUnit.SECONDS)); }
    }
    @Test void ignoredInterruptionStopsDispatchAndQuarantinesWorkerLock() throws Exception {
        SqliteJobStore store=new SqliteJobStore(root.resolve("jobs.db")); MockTicketTool ticket=new MockTicketTool(root.resolve("tool.db"));
        String id=store.submit("ignored",spec(new JobPolicy(3,0,100,60000))).jobId();
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1), exited=new CountDownLatch(1);
        ToolAdapter stubborn=new ToolAdapter() {
            public String version() { return NormalizeTool.VERSION; }
            public ReplayContract contract() { return ReplayContract.PURE; }
            public ToolResult execute(ToolCall call) {
                entered.countDown();
                while(release.getCount()>0) { try { release.await(); } catch(InterruptedException ignored) {} }
                exited.countDown(); return new NormalizeTool().execute(call);
            }
        };
        try {
            assertEquals("TOOL_DID_NOT_STOP",assertThrows(JobException.class,() -> worker(store,stubborn,ticket).drain()).code());
            assertEquals(0,entered.getCount()); assertEquals(JobState.NEEDS_REVIEW,store.get(id).state());
            assertEquals("WORKER_ALREADY_RUNNING",assertThrows(JobException.class,() -> WorkerLock.acquire(store.path())).code());
            assertEquals(0,ticket.stats().effectCount()); assertEquals(0,store.get(id).checkpointSequence());
        } finally { release.countDown(); assertTrue(exited.await(5,TimeUnit.SECONDS)); }
    }
    @Test void ownedSecondWorkerIsRejectedWithoutChangingJob() throws Exception {
        Path dir=root.resolve("lock"); java.nio.file.Files.createDirectories(dir);
        SqliteJobStore store=new SqliteJobStore(dir.resolve("jobs.db")); store.submit("lock",spec(JobPolicy.DEFAULT));
        JsonObject before=store.inspectKey("lock");
        try(WorkerLock held=WorkerLock.acquire(store.path())) {
            assertEquals(4,RecoveryDemo.child(dir,"second",List.of("--db",store.path().toString(),"worker","--drain")));
        }
        assertEquals(before,store.inspectKey("lock"));
    }
}
