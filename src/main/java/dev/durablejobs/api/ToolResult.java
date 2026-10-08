package dev.durablejobs.api;

import com.google.gson.JsonObject;

public record ToolResult(JsonObject receipt) {
    public ToolResult {
        if (receipt == null) throw new JobException("INVALID_TOOL_RESULT", "Receipt must be an object.");
        JsonSupport.bounded(JsonSupport.json(receipt), JsonSupport.RECEIPT_BYTES);
        receipt = receipt.deepCopy();
    }
    @Override public JsonObject receipt() { return receipt.deepCopy(); }
}
