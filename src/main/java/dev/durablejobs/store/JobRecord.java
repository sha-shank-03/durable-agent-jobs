package dev.durablejobs.store;

import dev.durablejobs.api.JobSpec;
import dev.durablejobs.api.JobState;

public record JobRecord(String id, String requestKey, JobSpec spec, JobState state,
                        long checkpointSequence, String generation, long deadlineAt, boolean cancelRequested) {}
