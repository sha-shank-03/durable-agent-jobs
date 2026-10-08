package dev.durablejobs.api;

import com.google.gson.JsonObject;

public record ToolCall(String invocationKey, JsonObject arguments) {
    public ToolCall {
        if (invocationKey == null || invocationKey.isBlank() || invocationKey.length() > 160 || arguments == null) {
            throw new JobException("INVALID_TOOL_REQUEST", "Invalid invocation key.");
        }
        JsonSupport.bounded(JsonSupport.json(arguments), JsonSupport.SPEC_BYTES);
        arguments = arguments.deepCopy();
    }
    @Override public JsonObject arguments() { return arguments.deepCopy(); }
}
