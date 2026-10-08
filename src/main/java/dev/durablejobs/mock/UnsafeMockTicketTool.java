package dev.durablejobs.mock;

import com.google.gson.JsonObject;
import dev.durablejobs.api.*;
import dev.durablejobs.store.SqliteDatabase;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Set;

/** Fictional fixture deliberately has no unique invocation-key constraint. */
public final class UnsafeMockTicketTool implements ToolAdapter {
    public static final String VERSION="mock-unsafe-ticket-tool/v1";
    private final SqliteDatabase database;
    public UnsafeMockTicketTool(Path path) { database=new SqliteDatabase(path); database.initialize("/unsafe-tool-schema.sql",0x55415431); }
    public String version() { return VERSION; }
    public ReplayContract contract() { return ReplayContract.NON_IDEMPOTENT; }
    public ToolResult execute(ToolCall call) {
        JsonObject arguments=call.arguments();
        JsonSupport.fields(arguments,Set.of("title","body"),Set.of("title","body"));
        String title=JsonSupport.string(arguments,"title",200);
        return database.transaction(connection -> {
            try(PreparedStatement statement=connection.prepareStatement("INSERT INTO unsafe_ticket_effects(invocation_key,title) VALUES(?,?)")) {
                statement.setString(1,call.invocationKey()); statement.setString(2,title); statement.executeUpdate();
            }
            JsonObject receipt=new JsonObject(); receipt.addProperty("execution_mode","deterministic_mock");
            try(PreparedStatement query=connection.prepareStatement("SELECT last_insert_rowid()");ResultSet rows=query.executeQuery()) {
                rows.next(); receipt.addProperty("ticket_id","unsafe-"+rows.getLong(1));
            }
            receipt.addProperty("title",title); return new ToolResult(receipt);
        });
    }
    public long effectCount() {
        return database.read(connection -> {
            try(PreparedStatement query=connection.prepareStatement("SELECT count(*) FROM unsafe_ticket_effects");ResultSet rows=query.executeQuery()) {
                rows.next(); return rows.getLong(1);
            }
        });
    }
}
