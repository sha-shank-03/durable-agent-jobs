package dev.durablejobs;

import com.google.gson.JsonObject;
import dev.durablejobs.api.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ValidationTest {
    @Test void strictJsonRejectsDuplicateMembersAndTrailingContent() {
        assertEquals("INVALID_JSON", assertThrows(JobException.class,
                () -> JsonSupport.object("{\"a\":1,\"a\":2}", 100)).code());
        assertThrows(JobException.class, () -> JsonSupport.object("{\"a\":1} trailing", 100));
        assertThrows(JobException.class, () -> JsonSupport.object("{/*comment*/\"a\":1}", 100));
    }

    @Test void schemaAgentAndUnknownMembersAreRejected() {
        assertEquals("UNSUPPORTED_SCHEMA", assertThrows(JobException.class,
                () -> new JobSpec(2, JobSpec.MOCK_AGENT, "Title", "Body", 20)).code());
        assertEquals("UNSUPPORTED_AGENT", assertThrows(JobException.class,
                () -> new JobSpec(1, "unknown/v1", "Title", "Body", 20)).code());
        JsonObject object = new JobSpec(1, JobSpec.MOCK_AGENT, "Title", "Body", 20).json();
        object.addProperty("unrecognized", true);
        assertEquals("INVALID_SPEC", assertThrows(JobException.class, () -> JobSpec.parse(JsonSupport.json(object))).code());
    }

    @Test void boundsAndNumericStringCoercionAreRejected() {
        assertThrows(JobException.class, () -> new JobSpec(1, JobSpec.MOCK_AGENT, " ", "Body", 20));
        assertThrows(JobException.class, () -> new JobSpec(1, JobSpec.MOCK_AGENT, "Title", "Body", 101));
        assertEquals("PAYLOAD_TOO_LARGE", assertThrows(JobException.class, () -> JsonSupport.object(" ".repeat(101), 100)).code());
        JsonObject object = new JobSpec(1, JobSpec.MOCK_AGENT, "Title", "Body", 20).json();
        object.addProperty("max_steps", "20");
        assertEquals("INVALID_SPEC", assertThrows(JobException.class, () -> JobSpec.parse(JsonSupport.json(object))).code());
    }

    @Test void defaultPolicyPreservesRequestIdentityAndInvalidBoundsFail() {
        JobSpec original=new JobSpec(1,JobSpec.MOCK_AGENT,"Title","Body",20);
        JsonObject explicit=original.json(); explicit.add("policy",JobPolicy.DEFAULT.json());
        assertEquals(JsonSupport.hash(original.json()),JsonSupport.hash(JobSpec.parse(JsonSupport.json(explicit)).json()));
        assertThrows(JobException.class,() -> new JobPolicy(11,0,100,100));
        assertThrows(JobException.class,() -> new JobPolicy(3,-1,100,100));
        assertThrows(JobException.class,() -> new JobPolicy(3,0,0,100));
        JsonObject policy=JobPolicy.DEFAULT.json(); policy.addProperty("max_attempts","3"); explicit.add("policy",policy);
        assertThrows(JobException.class,() -> JobSpec.parse(JsonSupport.json(explicit)));
        policy=JobPolicy.DEFAULT.json(); policy.addProperty("unknown",1); explicit.add("policy",policy);
        assertThrows(JobException.class,() -> JobSpec.parse(JsonSupport.json(explicit)));
    }
    @Test void publicJsonRecordsDefensivelyCopyStateAndArguments() {
        JsonObject original = new JsonObject();
        original.addProperty("title", "First");
        Checkpoint checkpoint = new Checkpoint(0, original);
        ToolCall call = new ToolCall("job:key", original);
        original.addProperty("title", "Changed");
        checkpoint.state().addProperty("title", "Also changed");
        assertEquals("First", checkpoint.state().get("title").getAsString());
        assertEquals("First", call.arguments().get("title").getAsString());
    }
}
