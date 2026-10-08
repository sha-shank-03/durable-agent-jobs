package dev.durablejobs.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;

public final class JsonSupport {
    public static final int SPEC_BYTES = 65_536;
    public static final int RECEIPT_BYTES = 65_536;
    public static final int CHECKPOINT_BYTES = 262_144;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping()
            .setStrictness(Strictness.STRICT).create();

    private JsonSupport() {}

    public static JsonObject object(String text, int byteLimit) {
        bounded(text, byteLimit);
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement result = read(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT || !result.isJsonObject()) {
                throw new JobException("INVALID_JSON", "Expected one JSON object.");
            }
            return result.getAsJsonObject();
        } catch (IOException | IllegalStateException | NumberFormatException e) {
            throw new JobException("INVALID_JSON", "Malformed strict JSON object.", e);
        }
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 32) throw new JobException("INVALID_JSON", "JSON nesting exceeds 32.");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                JsonObject result = new JsonObject();
                Set<String> names = new HashSet<>();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (!names.add(name)) throw new JobException("INVALID_JSON", "Duplicate JSON member.");
                    result.add(name, read(reader, depth + 1));
                }
                reader.endObject();
                yield result;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                JsonArray result = new JsonArray();
                while (reader.hasNext()) result.add(read(reader, depth + 1));
                reader.endArray();
                yield result;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new JobException("INVALID_JSON", "Unexpected JSON token.");
        };
    }

    public static void fields(JsonObject object, Set<String> allowed, Set<String> required) {
        if (!allowed.containsAll(object.keySet()) || !object.keySet().containsAll(required)) {
            throw new JobException("INVALID_SPEC", "Unknown or missing JSON members.");
        }
    }

    public static String string(JsonObject object, String key, int maxCharacters) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new JobException("INVALID_SPEC", "Expected a string member.");
        }
        String result = value.getAsString();
        if (result.length() > maxCharacters) throw new JobException("INVALID_SPEC", "String member too long.");
        return result;
    }

    public static int integer(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new JobException("INVALID_SPEC", "Expected an integer member.");
        }
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException e) { throw new JobException("INVALID_SPEC", "Expected a bounded integer.", e); }
    }

    public static String json(JsonElement element) { return GSON.toJson(element); }

    public static String canonical(JsonElement element) { return json(sorted(element)); }

    private static JsonElement sorted(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            for (String key : new TreeSet<>(value.getAsJsonObject().keySet())) {
                result.add(key, sorted(value.getAsJsonObject().get(key)));
            }
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement child : value.getAsJsonArray()) result.add(sorted(child));
            return result;
        }
        return value.deepCopy();
    }

    public static String hash(JsonElement value) {
        try {
            byte[] bytes = canonical(value).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable.", e); }
    }

    public static void bounded(String value, int maxBytes) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new JobException("PAYLOAD_TOO_LARGE", "JSON exceeds the supported byte limit.");
        }
    }
}
