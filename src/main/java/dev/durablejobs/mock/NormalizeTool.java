package dev.durablejobs.mock;

import com.google.gson.JsonObject;
import dev.durablejobs.api.*;
import java.util.Set;

public final class NormalizeTool implements ToolAdapter {
    public static final String VERSION = "mock-normalize/v1";
    @Override public String version() { return VERSION; }
    @Override public ReplayContract contract() { return ReplayContract.PURE; }

    @Override public ToolResult execute(ToolCall call) {
        JsonObject arguments = call.arguments();
        JsonSupport.fields(arguments, Set.of("title", "body"), Set.of("title", "body"));
        JsonObject result = new JsonObject();
        String title = JsonSupport.string(arguments, "title", 200).strip();
        if (title.isBlank()) throw new JobException("INVALID_SPEC", "Normalized title is empty.");
        result.addProperty("title", title);
        result.addProperty("body", JsonSupport.string(arguments, "body", 10_000));
        return new ToolResult(result);
    }
}
