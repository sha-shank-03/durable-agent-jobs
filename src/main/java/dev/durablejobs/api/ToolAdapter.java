package dev.durablejobs.api;

/** Trusted adapter. A key contract is only as strong as adapter/provider enforcement. */
public interface ToolAdapter {
    String version();
    ReplayContract contract();
    ToolResult execute(ToolCall call);
}
