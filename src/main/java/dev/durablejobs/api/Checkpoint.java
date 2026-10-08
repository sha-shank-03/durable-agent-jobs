package dev.durablejobs.api;

import com.google.gson.JsonObject;

public record Checkpoint(long sequence, JsonObject state) {
    public Checkpoint {
        if (sequence < 0 || state == null) throw new IllegalArgumentException("Invalid checkpoint.");
        JsonSupport.bounded(JsonSupport.json(state), JsonSupport.CHECKPOINT_BYTES);
        state = state.deepCopy();
    }
    @Override public JsonObject state() { return state.deepCopy(); }
}
