package dev.durablejobs.api;

import com.google.gson.JsonObject;

public record ToolRequest(String operationKey, String toolVersion, ReplayContract contract, JsonObject arguments) {
    public ToolRequest {
        if (operationKey == null || !operationKey.matches("[a-zA-Z0-9_.-]{1,80}")
                || toolVersion == null || toolVersion.length() > 100 || contract == null || arguments == null) {
            throw new JobException("INVALID_TOOL_REQUEST", "Invalid tool request identity.");
        }
        JsonSupport.bounded(JsonSupport.json(arguments), JsonSupport.SPEC_BYTES);
        arguments = arguments.deepCopy();
    }
    @Override public JsonObject arguments() { return arguments.deepCopy(); }
}
