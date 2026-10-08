# Tool execution contracts

Agent.next must be deterministic, pure and versioned. It must propose stable logical operation keys and inspect completed receipts from the checkpoint. ToolRequest contains a tool version, contract and bounded arguments. The registry enforces a matching compiled adapter/version/contract before invocation.

PURE means no side effects under repeat execution. IDEMPOTENT_WITH_KEY means the adapter/provider must atomically deduplicate the same key and payload with its effect, retain that key for the relevant recovery window, reject payload conflicts, and return a stable receipt. NON_IDEMPOTENT means the runtime cannot infer repetition is safe. An enum declaration is not enforcement or proof.

The local mock enforces keyed behavior with a SQLite primary key and one transaction covering receipt/effect. Keys remain for the tool DB lifetime. Its separate invocation table counts repeated calls and conflicts; ticket_receipts counts committed effects. This mock does not contact an external ticket system.

The runtime's key is job ID:logical-operation-key, without an attempt/process suffix. Different tool/version/contract/payload under an existing operation key raises OPERATION_CONFLICT. Different payload at the mock provider key raises TOOL_KEY_CONFLICT without another ticket.

M2 retries abandoned PURE/keyed intents and typed retryable safe failures within a persisted per-operation attempt budget. The saved operation version/arguments/key survive generation changes and process restarts. NON_IDEMPOTENT uncertainty always requires NEEDS_REVIEW; even a declared no-effect failure is conservatively terminal for that contract. Ordinary exceptions do not authorize automatic reinvocation. ToolFailure is an adapter assertion, just like the replay contract; incorrect classification can compromise safety.

An attempt timeout is ambiguous and retryable only for PURE/keyed operations after cooperative shutdown. Durable cancellation and submission expiry stop further progress and fence receipt/checkpoint commits. Java interruption cannot prove an outside effect stopped or undo it; ignored interruption halts dispatch and quarantines the lock until JVM exit. A late result is discarded. Trusted Agent.next must promptly return because it is synchronous.

Do not describe the two-database boundary as exactly-once execution. Keyed recovery invokes tools repeatedly; safety depends on actual provider enforcement and key retention. Non-idempotent ambiguous calls require investigation rather than automatic repetition. The separate UnsafeMockTicketTool intentionally stores each call as a new fictional effect without a unique invocation key, demonstrating this distinction.
