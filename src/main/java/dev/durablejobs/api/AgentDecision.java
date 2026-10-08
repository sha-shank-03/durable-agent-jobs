package dev.durablejobs.api;

import com.google.gson.JsonObject;
import java.util.Objects;

public sealed interface AgentDecision permits AgentDecision.Invoke, AgentDecision.Complete {
    record Invoke(ToolRequest request) implements AgentDecision {
        public Invoke { Objects.requireNonNull(request); }
    }
    record Complete(JsonObject result) implements AgentDecision {
        public Complete {
            Objects.requireNonNull(result);
            JsonSupport.bounded(JsonSupport.json(result), JsonSupport.RECEIPT_BYTES);
            result = result.deepCopy();
        }
        @Override public JsonObject result() { return result.deepCopy(); }
    }
}
