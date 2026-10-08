package dev.durablejobs.api;

/** Explicit adapter outcome classification; ordinary exceptions remain ambiguous. */
public final class ToolFailure extends RuntimeException {
    public enum Kind { TRANSIENT_NO_EFFECT, PERMANENT_NO_EFFECT, AMBIGUOUS }
    private final String code;
    private final Kind kind;
    public ToolFailure(String code, Kind kind) {
        super("Classified tool failure.");
        if (code == null || !code.matches("[A-Z0-9_]{1,80}") || kind == null) throw new IllegalArgumentException();
        this.code = code; this.kind = kind;
    }
    public String code() { return code; }
    public Kind kind() { return kind; }
}
