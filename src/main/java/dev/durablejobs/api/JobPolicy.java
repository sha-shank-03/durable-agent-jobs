package dev.durablejobs.api;

import com.google.gson.JsonObject;
import java.util.Set;

public record JobPolicy(int maxAttempts, long retryBaseMillis, long toolTimeoutMillis, long jobTimeoutMillis) {
    public static final JobPolicy DEFAULT = new JobPolicy(3, 100, 5_000, 60_000);
    public JobPolicy {
        if (maxAttempts < 1 || maxAttempts > 10 || retryBaseMillis < 0 || retryBaseMillis > 2_000
                || toolTimeoutMillis < 1 || toolTimeoutMillis > 60_000
                || jobTimeoutMillis < 1 || jobTimeoutMillis > 86_400_000) {
            throw new JobException("INVALID_SPEC", "Policy attempts/durations exceed supported bounds.");
        }
    }
    public long backoff(int attempt) { return Math.min(2_000, retryBaseMillis * (1L << Math.min(9, attempt - 1))); }
    public static JobPolicy parse(JsonObject object) {
        JsonSupport.fields(object, Set.of("max_attempts","retry_base_ms","tool_timeout_ms","job_timeout_ms"), Set.of());
        return new JobPolicy(value(object,"max_attempts",3), value(object,"retry_base_ms",100),
                value(object,"tool_timeout_ms",5_000), value(object,"job_timeout_ms",60_000));
    }
    private static int value(JsonObject object, String key, int fallback) {
        return object.has(key) ? JsonSupport.integer(object,key) : fallback;
    }
    public JsonObject json() {
        JsonObject result = new JsonObject();
        result.addProperty("max_attempts",maxAttempts); result.addProperty("retry_base_ms",retryBaseMillis);
        result.addProperty("tool_timeout_ms",toolTimeoutMillis); result.addProperty("job_timeout_ms",jobTimeoutMillis);
        return result;
    }
}
