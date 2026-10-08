package dev.durablejobs.runtime;

import dev.durablejobs.api.*;
import dev.durablejobs.mock.MockTicketTool;
import dev.durablejobs.mock.UnsafeMockTicketTool;
import dev.durablejobs.store.JobRecord;
import dev.durablejobs.store.SqliteJobStore;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** One control loop owns state commits. Tool results never commit from executor threads. */
public final class JobWorker {
    private static final Map<Path,WorkerLock> QUARANTINED_LOCKS=new ConcurrentHashMap<>();
    private final SqliteJobStore store;
    private final Agent agent;
    private final Map<String,ToolAdapter> tools;
    private final Sleeper sleeper;
    private final boolean haltAfterMockCommit;
    private boolean toolDidNotStop;
    @FunctionalInterface public interface Sleeper { void sleep(long millis) throws InterruptedException; }
    public record RunSummary(int processed,int succeeded,int failed,int needsReview,int cancelled,int timedOut) {
        public RunSummary(int processed,int succeeded,int failed,int needsReview) { this(processed,succeeded,failed,needsReview,0,0); }
    }
    public JobWorker(SqliteJobStore store,Agent agent,Collection<ToolAdapter> adapters) {
        this(store,agent,adapters,Thread::sleep,false);
    }
    public JobWorker(SqliteJobStore store,Agent agent,Collection<ToolAdapter> adapters,Sleeper sleeper,boolean haltAfterMockCommit) {
        this.store=store; this.agent=agent; this.sleeper=sleeper; this.haltAfterMockCommit=haltAfterMockCommit;
        Map<String,ToolAdapter> registered=new HashMap<>();
        for(ToolAdapter adapter:adapters) if(registered.putIfAbsent(adapter.version(),adapter)!=null)
            throw new JobException("DUPLICATE_TOOL","Tool versions must be unique.");
        tools=Map.copyOf(registered);
    }
    public RunSummary drain() {
        int[] counts=new int[5];
        WorkerLock lock=WorkerLock.acquire(store.path());
        try {
            for(JobState state:store.recoverAbandoned()) count(counts,state);
            while(true) {
                for(JobState state:store.settlePendingControls()) count(counts,state);
                Optional<JobRecord> next=store.claimNext();
                if(next.isPresent()) { count(counts,run(next.get())); continue; }
                Optional<Long> delay=store.nextRetryDelay();
                if(delay.isEmpty()) break;
                try { sleeper.sleep(Math.min(20,Math.max(1,delay.get()))); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new JobException("WORKER_INTERRUPTED","Worker interrupted.",e); }
            }
        } finally {
            // Prevent another embedded worker while an ignored interruption may still produce effects.
            if(toolDidNotStop) QUARANTINED_LOCKS.put(store.path(),lock);
            else lock.close();
        }
        return new RunSummary(Arrays.stream(counts).sum(),counts[0],counts[1],counts[2],counts[3],counts[4]);
    }
    private static void count(int[] counts,JobState state) {
        switch(state) {
            case SUCCEEDED -> counts[0]++;
            case FAILED -> counts[1]++;
            case NEEDS_REVIEW -> counts[2]++;
            case CANCELLED -> counts[3]++;
            case TIMED_OUT -> counts[4]++;
            default -> { }
        }
    }
    private JobState run(JobRecord initial) {
        JobRecord job=initial;
        try {
            if(!agent.version().equals(job.spec().agentVersion())) throw new JobException("UNSUPPORTED_AGENT","Worker does not support persisted agent.");
            while(true) {
                JobState control=store.controlState(job.id());
                if(SqliteJobStore.isTerminal(control)) return control;
                Optional<ToolRequest> saved=store.pendingOperation(job);
                ToolRequest request;
                if(saved.isPresent()) request=saved.get();
                else {
                    Checkpoint checkpoint=store.checkpoint(job);
                    AgentDecision decision=agent.next(job.spec(),checkpoint);
                    if(decision instanceof AgentDecision.Complete complete) { store.succeed(job,complete.result()); return JobState.SUCCEEDED; }
                    if(!(decision instanceof AgentDecision.Invoke invoke)) throw new JobException("INVALID_AGENT_DECISION","Unsupported agent decision.");
                    if(checkpoint.sequence()>=job.spec().maxSteps()) throw new JobException("STEP_LIMIT","Bounded tool-step limit reached.");
                    request=invoke.request();
                }
                ToolAdapter adapter=tools.get(request.toolVersion());
                if(adapter==null || adapter.contract()!=request.contract()) throw new JobException("TOOL_CONTRACT_MISMATCH","Unregistered tool version/contract.");
                if(store.prepareOperation(job,request).isPresent()) throw new JobException("AGENT_REPEATED_OPERATION","Agent repeated completed operation.");
                JobState outcome=execute(job,request,adapter);
                if(outcome!=JobState.RUNNING) return outcome;
                job=store.get(job.id());
            }
        } catch(JobException error) {
            if(error.code().startsWith("STORAGE") || "TOOL_DID_NOT_STOP".equals(error.code())) throw error;
            JobState current=store.controlState(job.id());
            if(SqliteJobStore.isTerminal(current)) return current;
            if("STALE_EXECUTION".equals(error.code())) throw error;
            store.fail(job,error.code());
        } catch(RuntimeException error) {
            JobState current=store.controlState(job.id());
            if(SqliteJobStore.isTerminal(current)) return current;
            store.fail(job,"AGENT_ERROR");
        }
        return store.get(job.id()).state();
    }
    private JobState execute(JobRecord job,ToolRequest request,ToolAdapter adapter) {
        ExecutorService executor=Executors.newSingleThreadExecutor(runnable -> {
            Thread thread=new Thread(runnable,"durable-tool"); thread.setDaemon(true); return thread;
        });
        Future<ToolResult> future=executor.submit(() -> adapter.execute(new ToolCall(
                SqliteJobStore.invocationKey(job.id(),request.operationKey()),request.arguments())));
        long start=System.nanoTime();
        try {
            while(true) {
                JobState control=store.controlState(job.id());
                if(SqliteJobStore.isTerminal(control)) { stop(executor,future,job,request); return control; }
                if(System.nanoTime()-start>=TimeUnit.MILLISECONDS.toNanos(job.spec().policy().toolTimeoutMillis())) {
                    stop(executor,future,job,request);
                    return store.recordToolFailure(job,request,"TOOL_TIMEOUT",true,true);
                }
                try {
                    ToolResult result=future.get(10,TimeUnit.MILLISECONDS);
                    if(System.nanoTime()-start>=TimeUnit.MILLISECONDS.toNanos(job.spec().policy().toolTimeoutMillis())) {
                        stop(executor,future,job,request);
                        return store.recordToolFailure(job,request,"TOOL_TIMEOUT",true,true);
                    }
                    if(result==null) return store.recordToolFailure(job,request,"INVALID_TOOL_RESULT",false,true);
                    if(haltAfterMockCommit && "create-ticket".equals(request.operationKey())) {
                        if(!(adapter instanceof MockTicketTool) && !(adapter instanceof UnsafeMockTicketTool))
                            throw new JobException("INVALID_MOCK_FAULT","Fault hook requires built-in fictional mock tool.");
                        // Actual halt after tool transaction, before the runtime receipt/checkpoint.
                        Runtime.getRuntime().halt(86);
                    }
                    store.completeOperation(job,request,result);
                    return JobState.RUNNING;
                } catch(TimeoutException pending) {
                    // Poll durable control state.
                } catch(ExecutionException failure) {
                    if(failure.getCause() instanceof ToolFailure classified) {
                        boolean unknown=classified.kind()==ToolFailure.Kind.AMBIGUOUS;
                        boolean retryable=classified.kind()!=ToolFailure.Kind.PERMANENT_NO_EFFECT;
                        return store.recordToolFailure(job,request,classified.code(),retryable,unknown);
                    }
                    return store.recordToolFailure(job,request,"TOOL_ERROR",false,true);
                }
            }
        } catch(InterruptedException e) {
            stop(executor,future,job,request); Thread.currentThread().interrupt();
            throw new JobException("WORKER_INTERRUPTED","Control loop interrupted.",e);
        } finally {
            executor.shutdownNow();
            if(!executor.isTerminated()) {
                try { if(!executor.awaitTermination(250,TimeUnit.MILLISECONDS)) toolDidNotStop=true; }
                catch(InterruptedException e) { toolDidNotStop=true; Thread.currentThread().interrupt(); }
            }
        }
    }
    private void stop(ExecutorService executor,Future<?> future,JobRecord job,ToolRequest request) {
        future.cancel(true); executor.shutdownNow();
        try { if(executor.awaitTermination(250,TimeUnit.MILLISECONDS)) return; }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        toolDidNotStop=true;
        store.recordToolFailure(job,request,"TOOL_DID_NOT_STOP",false,true);
        throw new JobException("TOOL_DID_NOT_STOP","Trusted tool ignored interruption; dispatch stopped and lock quarantined until process restart.");
    }
}
