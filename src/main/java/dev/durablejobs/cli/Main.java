package dev.durablejobs.cli;

import com.google.gson.JsonObject;
import dev.durablejobs.api.*;
import dev.durablejobs.mock.*;
import dev.durablejobs.runtime.JobWorker;
import dev.durablejobs.store.SqliteJobStore;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, new PrintStream(System.out, true, StandardCharsets.UTF_8),
                new PrintStream(System.err, true, StandardCharsets.UTF_8)));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 0 || (args.length == 1 && Set.of("--help", "-h").contains(args[0]))) {
                help(out);
                return 0;
            }
            Arguments parsed = Arguments.parse(args);
            return switch (parsed.command()) {
                case "submit" -> submit(parsed, out);
                case "worker" -> worker(parsed, out);
                case "inspect" -> inspect(parsed, out);
                case "cancel" -> cancel(parsed,out);
                case "demo" -> demo(parsed, out);
                default -> throw new JobException("USAGE", "Unknown command; use --help.");
            };
        } catch (JobException error) {
            JsonObject report = new JsonObject();
            report.addProperty("error_code", error.code());
            report.addProperty("message", error.getMessage());
            err.println(JsonSupport.json(report));
            if ("USAGE".equals(error.code()) || error.code().startsWith("INVALID")
                    || error.code().startsWith("UNSUPPORTED") || "PAYLOAD_TOO_LARGE".equals(error.code())) return 2;
            if (error.code().startsWith("STORAGE") || "MISSING_SCHEMA".equals(error.code())) return 5;
            if (error.code().startsWith("WORKER") || "RECOVERY_PENDING_M2".equals(error.code())) return 4;
            return 3;
        } catch (IOException error) {
            err.println("{\"error_code\":\"FILE_ERROR\",\"message\":\"Cannot read or create the requested local file.\"}");
            return 5;
        } catch (RuntimeException error) {
            err.println("{\"error_code\":\"UNEXPECTED_ERROR\",\"message\":\"Execution stopped; success is not assumed.\"}");
            return 6;
        }
    }

    private static int submit(Arguments args, PrintStream out) throws IOException {
        args.only(Set.of("--key", "--spec"), Set.of("--key", "--spec"));
        Path specPath = Path.of(args.value("--spec"));
        if (Files.size(specPath) > JsonSupport.SPEC_BYTES) throw new JobException("PAYLOAD_TOO_LARGE", "Spec exceeds 64 KiB.");
        JobSpec spec = JobSpec.parse(Files.readString(specPath, StandardCharsets.UTF_8));
        SqliteJobStore store = new SqliteJobStore(args.db());
        SqliteJobStore.Submission submission = store.submit(args.value("--key"), spec);
        JsonObject report = new JsonObject();
        report.addProperty("execution_mode", "deterministic_mock");
        report.addProperty("job_id", submission.jobId());
        report.addProperty("duplicate", submission.duplicate());
        report.addProperty("state", store.get(submission.jobId()).state().name());
        out.println(JsonSupport.json(report));
        return 0;
    }

    private static int worker(Arguments args, PrintStream out) {
        args.only(Set.of("--drain","--unsafe-mock","--mock-fault"), Set.of("--drain"));
        boolean unsafe=args.options().containsKey("--unsafe-mock");
        boolean fault=args.options().containsKey("--mock-fault");
        if(fault && !"after-tool-commit".equals(args.value("--mock-fault"))) throw new JobException("USAGE","Unknown mock fault.");
        SqliteJobStore store = new SqliteJobStore(args.db());
        ToolAdapter tool = unsafe?new UnsafeMockTicketTool(args.toolDb()):new MockTicketTool(args.toolDb());
        JobWorker.RunSummary summary = new JobWorker(store, new MockTicketAgent(unsafe), List.of(new NormalizeTool(), tool),Thread::sleep,fault).drain();
        JsonObject report = new JsonObject();
        report.addProperty("execution_mode", "deterministic_mock");
        report.addProperty("processed", summary.processed());
        report.addProperty("succeeded", summary.succeeded());
        report.addProperty("failed", summary.failed());
        report.addProperty("needs_review", summary.needsReview());
        report.addProperty("cancelled",summary.cancelled()); report.addProperty("timed_out",summary.timedOut());
        report.addProperty("invocation_count", unsafe?((UnsafeMockTicketTool)tool).effectCount():((MockTicketTool)tool).stats().invocationCount());
        report.addProperty("effect_count", unsafe?((UnsafeMockTicketTool)tool).effectCount():((MockTicketTool)tool).stats().effectCount());
        out.println(JsonSupport.json(report));
        return summary.failed() == 0 && summary.needsReview() == 0 ? 0 : 3;
    }

    private static int inspect(Arguments args, PrintStream out) {
        args.only(Set.of("--key", "--json"), Set.of("--key"));
        out.println(JsonSupport.json(new SqliteJobStore(args.db()).inspectKey(args.value("--key"))));
        return 0;
    }
    private static int cancel(Arguments args,PrintStream out) {
        args.only(Set.of("--key"),Set.of("--key"));
        SqliteJobStore store=new SqliteJobStore(args.db());
        JsonObject report=new JsonObject(); report.addProperty("cancel_requested",store.cancelKey(args.value("--key")));
        report.add("job",store.inspectKey(args.value("--key"))); out.println(JsonSupport.json(report)); return 0;
    }

    private static int demo(Arguments args, PrintStream out) throws IOException {
        args.only(Set.of("--dir", "--scenario"), Set.of());
        String scenario = args.options().getOrDefault("--scenario", "happy");
        Path parent = Path.of(args.options().getOrDefault("--dir", "work/demo")).toAbsolutePath().normalize();
        if(!"happy".equals(scenario)) {
            out.println(JsonSupport.json(RecoveryDemo.run(parent,scenario)));
            return 0;
        }
        Files.createDirectories(parent);
        Path run = Files.createTempDirectory(parent, "run-");
        Path jobs = run.resolve("jobs.db");
        Path toolDb = run.resolve("mock-tool.db");
        SqliteJobStore store = new SqliteJobStore(jobs);
        MockTicketTool tool = new MockTicketTool(toolDb);
        JobSpec spec = new JobSpec(1, JobSpec.MOCK_AGENT, " Fictional export support request ", "Synthetic demo data.", 20);
        SqliteJobStore.Submission first = store.submit("demo-001", spec);
        SqliteJobStore.Submission second = store.submit("demo-001", spec);
        require(second.duplicate() && first.jobId().equals(second.jobId()), "Duplicate did not return the same job.");
        boolean rejected = false;
        try { store.submit("demo-001", new JobSpec(1, JobSpec.MOCK_AGENT, "Changed fictional request", spec.body(), 20)); }
        catch (JobException expected) {
            if (!"REQUEST_CONFLICT".equals(expected.code())) throw expected;
            rejected = true;
        }
        require(rejected, "Changed specification was not rejected.");
        JobWorker.RunSummary initial = new JobWorker(store, new MockTicketAgent(), List.of(new NormalizeTool(), tool)).drain();
        require(initial.succeeded() == 1 && initial.processed() == 1, "Mock job did not succeed.");
        JsonObject before = store.inspectKey("demo-001");
        require(before.get("checkpoint_sequence").getAsLong() == 2, "Expected both tool checkpoints.");
        require(tool.stats().invocationCount() == 1 && tool.stats().effectCount() == 1, "Expected one mock ticket effect.");
        SqliteJobStore reopened = new SqliteJobStore(jobs);
        MockTicketTool reopenedTool = new MockTicketTool(toolDb);
        SqliteJobStore.Submission after = reopened.submit("demo-001", spec);
        JobWorker.RunSummary repeated = new JobWorker(reopened, new MockTicketAgent(), List.of(new NormalizeTool(), reopenedTool)).drain();
        require(after.duplicate() && after.jobId().equals(first.jobId()), "Completed duplicate changed job identity.");
        require(repeated.processed() == 0 && reopenedTool.stats().invocationCount() == 1
                && reopenedTool.stats().effectCount() == 1, "Completed delivery repeated an effect.");
        require(before.equals(reopened.inspectKey("demo-001")), "Completed checkpoint/result changed on reopen.");
        JsonObject report = new JsonObject();
        report.addProperty("execution_mode", "deterministic_mock");
        report.addProperty("scenario", "happy");
        report.addProperty("run_dir", run.toString());
        report.addProperty("state", "SUCCEEDED");
        report.addProperty("duplicate_same_job", true);
        report.addProperty("changed_spec_rejected", true);
        report.addProperty("completed_checkpoint_persisted", true);
        report.addProperty("repeat_worker_processed", repeated.processed());
        report.addProperty("invocation_count", reopenedTool.stats().invocationCount());
        report.addProperty("effect_count", reopenedTool.stats().effectCount());
        report.addProperty("assertions_failed", 0);
        report.add("job", before);
        out.println(JsonSupport.json(report));
        return 0;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new JobException("DEMO_ASSERTION_FAILED", message);
    }

    private static void help(PrintStream out) {
        out.println("""
                durable-agent-jobs 0.1.0 — M2 deterministic mock runtime
                java -jar target/durable-agent-jobs.jar [--db PATH] [--tool-db PATH] COMMAND
                  submit  --key KEY --spec JSON_FILE
                  worker  --drain [--unsafe-mock] [--mock-fault after-tool-commit]
                  inspect --key KEY [--json]
                  cancel  --key KEY
                  demo    [--dir PARENT] [--scenario happy|crash-idempotent|crash-unsafe|all]
                Default DB: work/jobs.db; default tool DB: mock-tool.db beside job DB.
                Demo creates a new directory and never removes an existing database.
                Explicit mock fault halts only this JVM at the tool/runtime commit gap, exit 86.
                Policy: max_attempts, retry_base_ms, tool_timeout_ms, job_timeout_ms.
                No model calls. Replay contracts describe adapter obligations, not exactly-once guarantees.
                Exit: 0 success; 2 usage/input; 3 conflict/job/demo failure; 4 worker/recovery;
                      5 storage/file; 6 unexpected runtime error.
                """);
    }

    private record Arguments(Path db, Path explicitToolDb, String command, Map<String, String> options) {
        Path toolDb() { return explicitToolDb != null ? explicitToolDb : db.toAbsolutePath().normalize().resolveSibling("mock-tool.db"); }
        String value(String key) { return options.get(key); }
        void only(Set<String> allowed, Set<String> required) {
            if (!allowed.containsAll(options.keySet()) || !options.keySet().containsAll(required)) {
                throw new JobException("USAGE", "Unknown or missing command option; use --help.");
            }
        }
        static Arguments parse(String[] args) {
            Path db = Path.of("work/jobs.db"), toolDb = null;
            String command = null;
            Set<String> valued = Set.of("--db", "--tool-db", "--key", "--spec", "--dir", "--scenario","--mock-fault");
            Set<String> flags = Set.of("--drain", "--json","--unsafe-mock");
            Map<String, String> options = new HashMap<>();
            Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < args.length; i++) {
                String token = args[i];
                if (valued.contains(token)) {
                    if (!seen.add(token) || i + 1 >= args.length || args[i + 1].startsWith("--")) {
                        throw new JobException("USAGE", "Repeated option or missing option value.");
                    }
                    String value = args[++i];
                    if (value.isBlank()) throw new JobException("USAGE", "Option value must not be empty.");
                    if ("--db".equals(token)) db = Path.of(value);
                    else if ("--tool-db".equals(token)) toolDb = Path.of(value);
                    else options.put(token, value);
                } else if (flags.contains(token)) {
                    if (!seen.add(token)) throw new JobException("USAGE", "Repeated command option.");
                    options.put(token, "true");
                } else if (Set.of("submit", "worker", "inspect", "cancel", "demo").contains(token) && command == null) {
                    command = token;
                } else throw new JobException("USAGE", "Unsupported argument; use --help.");
            }
            if (command == null) throw new JobException("USAGE", "Missing command.");
            return new Arguments(db, toolDb, command, Map.copyOf(options));
        }
    }
}
