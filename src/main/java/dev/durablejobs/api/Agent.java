package dev.durablejobs.api;

/** Trusted deterministic decision code: no I/O, threads or side effects. */
public interface Agent {
    String version();
    AgentDecision next(JobSpec spec, Checkpoint checkpoint);
}
