package dev.durablejobs.mock;

import com.google.gson.JsonObject;
import dev.durablejobs.api.*;
import dev.durablejobs.store.SqliteDatabase;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;

/** Own DB transaction atomically commits the key, fictional effect and receipt. */
public final class MockTicketTool implements ToolAdapter {
    public static final String VERSION = "mock-ticket-tool/v1";
    private static final int APPLICATION_ID = 0x44415431;
    private final SqliteDatabase database;
    private final Clock clock;

    public record Stats(long invocationCount, long effectCount) {}
    private record Outcome(ToolResult result, boolean conflict) {}

    public MockTicketTool(Path path) { this(path, Clock.systemUTC()); }
    public MockTicketTool(Path path, Clock clock) {
        database = new SqliteDatabase(path);
        this.clock = clock;
        database.initialize("/mock-tool-schema.sql", APPLICATION_ID);
    }

    @Override public String version() { return VERSION; }
    @Override public ReplayContract contract() { return ReplayContract.IDEMPOTENT_WITH_KEY; }
    public Path path() { return database.path(); }

    @Override public ToolResult execute(ToolCall call) {
        JsonObject arguments = call.arguments();
        JsonSupport.fields(arguments, Set.of("title", "body"), Set.of("title", "body"));
        String title = JsonSupport.string(arguments, "title", 200);
        String body = JsonSupport.string(arguments, "body", 10_000);
        if (title.isBlank()) throw new JobException("INVALID_SPEC", "Ticket title is empty.");
        String hash = JsonSupport.hash(arguments);
        Outcome outcome = database.transaction(connection -> {
            ToolResult existing = null;
            boolean conflict = false;
            try (PreparedStatement query = connection.prepareStatement("SELECT args_hash,receipt_json FROM ticket_receipts WHERE invocation_key=?")) {
                query.setString(1, call.invocationKey());
                try (ResultSet rows = query.executeQuery()) {
                    if (rows.next()) {
                        conflict = !hash.equals(rows.getString("args_hash"));
                        if (!conflict) existing = new ToolResult(JsonSupport.object(rows.getString("receipt_json"), JsonSupport.RECEIPT_BYTES));
                    }
                }
            }
            long now = clock.millis();
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO tool_invocations(invocation_key,args_hash,outcome,created_at) VALUES(?,?,?,?)")) {
                statement.setString(1, call.invocationKey());
                statement.setString(2, hash);
                statement.setString(3, conflict ? "CONFLICT" : existing == null ? "CREATED" : "DEDUPLICATED");
                statement.setLong(4, now);
                statement.executeUpdate();
            }
            if (conflict) return new Outcome(null, true);
            if (existing != null) return new Outcome(existing, false);
            JsonObject receipt = new JsonObject();
            receipt.addProperty("execution_mode", "deterministic_mock");
            receipt.addProperty("ticket_id", UUID.randomUUID().toString());
            receipt.addProperty("title", title);
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO ticket_receipts(invocation_key,args_hash,ticket_id,title,body,receipt_json,created_at) VALUES(?,?,?,?,?,?,?)")) {
                statement.setString(1, call.invocationKey());
                statement.setString(2, hash);
                statement.setString(3, receipt.get("ticket_id").getAsString());
                statement.setString(4, title);
                statement.setString(5, body);
                statement.setString(6, JsonSupport.canonical(receipt));
                statement.setLong(7, now);
                statement.executeUpdate();
            }
            return new Outcome(new ToolResult(receipt), false);
        });
        if (outcome.conflict()) throw new JobException("TOOL_KEY_CONFLICT", "Invocation key was previously used with different arguments.");
        return outcome.result();
    }

    public Stats stats() {
        return database.transaction(connection -> {
            long calls, effects;
            try (PreparedStatement query = connection.prepareStatement("SELECT count(*) FROM tool_invocations"); ResultSet rows = query.executeQuery()) {
                rows.next(); calls = rows.getLong(1);
            }
            try (PreparedStatement query = connection.prepareStatement("SELECT count(*) FROM ticket_receipts"); ResultSet rows = query.executeQuery()) {
                rows.next(); effects = rows.getLong(1);
            }
            return new Stats(calls, effects);
        });
    }
}
