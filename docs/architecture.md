# Architecture

The CLI validates input and calls SqliteJobStore. A unique request key and canonical hash of validated specification fields define submission identity. An immediate SQLite transaction either returns a matching job, rejects a conflict, or creates its QUEUED row, initial checkpoint and SUBMITTED event.

JobWorker holds a FileChannel lock alongside the canonical job database. It claims QUEUED work with a new generation, reads the latest checkpoint, and calls a trusted deterministic Agent.next. The mock first requests PURE normalization, then IDEMPOTENT_WITH_KEY ticket creation, then completes a summary.

Before calling a tool, the store commits immutable tool/version/contract/arguments and a stable invocation key. The key is job ID plus logical operation key. ToolAdapter.execute runs outside that database transaction. On success, operation receipt, attempt completion, checkpoint and job progress commit together. A generation/checkpoint check fences stale completions.

MockTicketTool uses a separate database application ID/schema. In one tool DB transaction it records its invocation, atomically creates key/effect/receipt on first delivery, reuses a matching receipt on repeat, or rejects a changed payload. The runtime database and tool database are not atomically committed together.

The two tools yield checkpoint sequences 1 and 2, with initial checkpoint 0. Their receipts are inputs to the next pure decision. SUCCEEDED then persists the final summary. Reopening completed records and redelivering the same request never queues another job.

On startup, the exclusively locked worker recovers surviving RUNNING jobs. A job without an unfinished intent resumes from its latest committed checkpoint. A saved PURE/keyed intent consumes its prior attempt and schedules the next due time; a saved non-idempotent intent becomes UNKNOWN/NEEDS_REVIEW. Recovery dispatch reconstructs the saved operation rather than asking the agent to invent a new one. Exhausted budgets remain terminal across restart.

Typed ToolFailure classifications distinguish transient/permanent no-effect failures from ambiguity. Safe retryable failures move RUNNING to WAITING_RETRY, retain the operation identity/arguments, clear the generation and persist a due time and attempt outcome. Claiming due work issues a new generation; preparing its intent increments the persisted attempt count. Backoff is bounded and deadline-clamped. Success atomically persists receipt/checkpoint and later clears the previous retry error. Ordinary exceptions remain uncertain and pause for review.

The worker runs one trusted tool at a time on a daemon executor and owns all runtime commits on its control thread. It monitors durable cancellation/absolute submission deadline and monotonic attempt duration. Cancellation/expiry invalidates the generation; commit transactions repeat those checks. Interrupted tool results cannot advance the checkpoint. Ignored interruption stops dispatch, reports uncertainty and retains the lock until process exit; arbitrary adapter effects cannot be undone or forcibly stopped. Pure Agent.next remains synchronous and must promptly return.

The supervised crash demo starts an owned JVM, halts it with exit 86 after the mock tool transaction, inspects the retained runtime gap, and restarts on the same DBs. Keyed recovery invokes the same key twice with one effect; the unsafe fixture deliberately omits key deduplication and is never automatically reinvoked. Child waits are bounded to 20 seconds plus a 5-second owned-process termination budget. The optional Python verifier reads these tables independently through sqlite3 before recovery and after it.

Storage uses WAL/FULL/foreign keys, bounded busy timeout and short connections/transactions. Schema version and application ID reject foreign databases. Persistence errors stop the worker; a failed commit never becomes a reported success.

The runtime enforces the cancellation/deadline/retry fields reserved in schema v1. Default policy keeps M1 submission hashes stable; old rows with a null deadline derive it from their creation time and default policy. No general hot-upgrade compatibility is promised. Final documentation/attribution and prepared CI add no runtime behavior or dependency changes.

Job transitions are QUEUED -> RUNNING -> SUCCEEDED, WAITING_RETRY or a terminal state. WAITING_RETRY is eligible only at its saved due time, then returns to RUNNING with a fresh generation. FAILED, NEEDS_REVIEW, CANCELLED and TIMED_OUT are terminal in this MVP. A FAILED job may retain an UNKNOWN operation after exhausted ambiguous attempts; job failure does not prove an outside effect failed. No force-retry/reconciliation command bypasses the saved contract.

The original contribution is the small Java integration and inspectable failure harness around these established patterns. Tests deliberately separate runtime progress from a fictional provider's committed effect and verify both databases. The CLI supports the two built-in versioned mock agents; this is not a general graph framework, provider integration or production deployment.
