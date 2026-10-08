package dev.durablejobs.api;

/** Stable diagnostic code; CLI errors deliberately do not print user payloads. */
public final class JobException extends RuntimeException {
    private final String code;

    public JobException(String code, String message) {
        super(message);
        this.code = code;
    }

    public JobException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() { return code; }
}
