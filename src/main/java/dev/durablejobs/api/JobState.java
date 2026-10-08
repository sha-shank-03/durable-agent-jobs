package dev.durablejobs.api;

public enum JobState { QUEUED, RUNNING, WAITING_RETRY, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, NEEDS_REVIEW }
