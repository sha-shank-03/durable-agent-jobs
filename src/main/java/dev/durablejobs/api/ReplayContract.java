package dev.durablejobs.api;

/** Adapter assertion, not a runtime proof about an external provider. */
public enum ReplayContract { PURE, IDEMPOTENT_WITH_KEY, NON_IDEMPOTENT }
