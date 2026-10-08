package dev.durablejobs.api;

import com.google.gson.JsonObject;
import java.util.Set;

public record JobSpec(int schemaVersion, String agentVersion, String title, String body, int maxSteps, JobPolicy policy) {
    public static final String MOCK_AGENT = "mock-ticket/v1";
    public static final String UNSAFE_MOCK_AGENT = "mock-unsafe-ticket/v1";
    public JobSpec(int schemaVersion, String agentVersion, String title, String body, int maxSteps) {
        this(schemaVersion, agentVersion, title, body, maxSteps, JobPolicy.DEFAULT);
    }

    public JobSpec {
        if (schemaVersion != 1) throw new JobException("UNSUPPORTED_SCHEMA", "Only job schema 1 is supported.");
        if (!MOCK_AGENT.equals(agentVersion) && !UNSAFE_MOCK_AGENT.equals(agentVersion)) throw new JobException("UNSUPPORTED_AGENT", "Unsupported agent version.");
        if (policy == null) throw new JobException("INVALID_SPEC", "Policy is required.");
        if (title == null || title.isBlank() || title.length() > 200 || body == null || body.length() > 10_000) {
            throw new JobException("INVALID_SPEC", "Title/body are outside supported bounds.");
        }
        if (maxSteps < 1 || maxSteps > 100) throw new JobException("INVALID_SPEC", "max_steps must be 1..100.");
    }

    public static JobSpec parse(String text) {
        JsonObject object = JsonSupport.object(text, JsonSupport.SPEC_BYTES);
        JsonSupport.fields(object, Set.of("schema_version", "agent", "request", "max_steps", "policy"),
                Set.of("schema_version", "agent", "request"));
        if (!object.get("request").isJsonObject()) throw new JobException("INVALID_SPEC", "request must be an object.");
        JsonObject request = object.getAsJsonObject("request");
        JsonSupport.fields(request, Set.of("title", "body"), Set.of("title", "body"));
        return new JobSpec(JsonSupport.integer(object, "schema_version"), JsonSupport.string(object, "agent", 80),
                JsonSupport.string(request, "title", 200), JsonSupport.string(request, "body", 10_000),
                object.has("max_steps") ? JsonSupport.integer(object, "max_steps") : 20,
                object.has("policy") ? parsePolicy(object) : JobPolicy.DEFAULT);
    }
    private static JobPolicy parsePolicy(JsonObject object) {
        if (!object.get("policy").isJsonObject()) throw new JobException("INVALID_SPEC", "policy must be an object.");
        return JobPolicy.parse(object.getAsJsonObject("policy"));
    }

    public JsonObject json() {
        JsonObject result = new JsonObject();
        result.addProperty("schema_version", schemaVersion);
        result.addProperty("agent", agentVersion);
        JsonObject request = new JsonObject();
        request.addProperty("title", title);
        request.addProperty("body", body);
        result.add("request", request);
        result.addProperty("max_steps", maxSteps);
        // Keep default M1 request identity stable. Custom policy is part of submission identity.
        if (!policy.equals(JobPolicy.DEFAULT)) result.add("policy", policy.json());
        return result;
    }
}
