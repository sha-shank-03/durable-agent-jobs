package dev.durablejobs;

import com.google.gson.JsonObject;
import dev.durablejobs.api.*;
import dev.durablejobs.cli.Main;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CliTest {
    @TempDir Path directory;
    private record Output(int code, String out, String err) {
        JsonObject json() { return JsonSupport.object(out, 262_144); }
    }
    private Output command(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        int code = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Output(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test void helpAndInvalidArgumentHaveExplicitExitCodes() {
        Output help = command("--help");
        assertEquals(0, help.code());
        assertTrue(help.out().contains("deterministic mock"));
        Output bad = command("worker", "--unexpected");
        assertEquals(2, bad.code());
        assertTrue(bad.err().contains("USAGE"));
        assertTrue(bad.out().isEmpty());
    }

    @Test void cliJourneyDeduplicatesAndRejectsChangedSpec() throws Exception {
        Path specFile = directory.resolve("spec.json"), jobs = directory.resolve("jobs.db");
        JobSpec spec = new JobSpec(1, JobSpec.MOCK_AGENT, "Fictional support", "Synthetic.", 20);
        Files.writeString(specFile, JsonSupport.json(spec.json()));
        Output first = command("--db", jobs.toString(), "submit", "--key", "cli-001", "--spec", specFile.toString());
        assertEquals(0, first.code(), first.err());
        Output duplicate = command("--db", jobs.toString(), "submit", "--key", "cli-001", "--spec", specFile.toString());
        assertEquals(0, duplicate.code(), duplicate.err());
        assertTrue(duplicate.json().get("duplicate").getAsBoolean());
        assertEquals(first.json().get("job_id"), duplicate.json().get("job_id"));
        Output worker = command("--db", jobs.toString(), "worker", "--drain");
        assertEquals(0, worker.code(), worker.err());
        assertEquals(1, worker.json().get("effect_count").getAsInt());
        Output inspection = command("--db", jobs.toString(), "inspect", "--key", "cli-001", "--json");
        assertEquals(0, inspection.code(), inspection.err());
        assertEquals("SUCCEEDED", inspection.json().get("state").getAsString());
        Files.writeString(specFile, JsonSupport.json(new JobSpec(1, JobSpec.MOCK_AGENT, "Changed", "Synthetic.", 20).json()));
        Output conflict = command("--db", jobs.toString(), "submit", "--key", "cli-001", "--spec", specFile.toString());
        assertEquals(3, conflict.code());
        assertTrue(conflict.err().contains("REQUEST_CONFLICT"));
        Output repeat = command("--db", jobs.toString(), "worker", "--drain");
        assertEquals(0, repeat.code(), repeat.err());
        assertEquals(0, repeat.json().get("processed").getAsInt());
        assertEquals(1, repeat.json().get("invocation_count").getAsInt());
    }

    @Test void happyDemoCreatesFreshRunsAndAssertsPersistence() {
        Output first = command("demo", "--dir", directory.toString(), "--scenario", "happy");
        Output second = command("demo", "--dir", directory.toString(), "--scenario", "happy");
        assertEquals(0, first.code(), first.err());
        assertEquals(0, second.code(), second.err());
        assertNotEquals(first.json().get("run_dir"), second.json().get("run_dir"));
        assertTrue(first.json().get("completed_checkpoint_persisted").getAsBoolean());
        assertEquals(0, first.json().get("assertions_failed").getAsInt());
        assertEquals(1, first.json().get("effect_count").getAsInt());
    }

    @Test void unknownCrashScenarioIsRejectedBeforeAnyChildStarts() {
        Output result = command("demo", "--dir", directory.toString(), "--scenario", "unknown-crash");
        assertEquals(2, result.code());
        assertTrue(result.err().contains("UNSUPPORTED_SCENARIO"));
    }
}
