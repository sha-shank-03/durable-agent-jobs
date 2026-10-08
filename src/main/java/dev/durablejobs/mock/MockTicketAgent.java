package dev.durablejobs.mock;

import com.google.gson.JsonObject;
import dev.durablejobs.api.*;

/** Fixed, deterministic policy. This is not a model call or a live LLM evaluation. */
public final class MockTicketAgent implements Agent {
    private final boolean unsafe;
    public MockTicketAgent() { this(false); }
    public MockTicketAgent(boolean unsafe) { this.unsafe=unsafe; }
    @Override public String version() { return unsafe?JobSpec.UNSAFE_MOCK_AGENT:JobSpec.MOCK_AGENT; }

    @Override public AgentDecision next(JobSpec spec, Checkpoint checkpoint) {
        JsonObject state = checkpoint.state();
        if (!state.has("normalize")) {
            return new AgentDecision.Invoke(new ToolRequest("normalize", NormalizeTool.VERSION,
                    ReplayContract.PURE, spec.json().getAsJsonObject("request")));
        }
        JsonObject normalized = state.getAsJsonObject("normalize");
        if (!state.has("create-ticket")) {
            JsonObject arguments = new JsonObject();
            arguments.add("title", normalized.get("title"));
            arguments.add("body", normalized.get("body"));
            return new AgentDecision.Invoke(new ToolRequest("create-ticket", unsafe?UnsafeMockTicketTool.VERSION:MockTicketTool.VERSION,
                    unsafe?ReplayContract.NON_IDEMPOTENT:ReplayContract.IDEMPOTENT_WITH_KEY, arguments));
        }
        JsonObject result = new JsonObject();
        result.addProperty("execution_mode", "deterministic_mock");
        result.add("ticket_id", state.getAsJsonObject("create-ticket").get("ticket_id"));
        result.add("title", normalized.get("title"));
        result.addProperty("summary", "Created a fictional ticket through a local mock tool.");
        return new AgentDecision.Complete(result);
    }
}
