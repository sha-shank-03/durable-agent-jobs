# Design sources and reuse

Primary sources were read during accepted planning on 2026-10-07; the Java 17 FileLock/Future pages were also read during finalization on 2026-10-08. These explain the reused patterns and chosen boundaries; they are not evidence that this small runtime matches their implementations or production guarantees.

| Source | Design use |
| --- | --- |
| [Temporal activity execution](https://docs.temporal.io/activity-execution) | Durable activity/retry overlap; explicit effect uncertainty and cancellation limits. |
| [Temporal idempotency guidance](https://temporal.io/blog/idempotency-and-durable-execution) | Stable logical invocation keys and provider-side deduplication obligations. |
| [LangGraph persistence](https://docs.langchain.com/oss/python/langgraph/persistence) | Checkpoint/fault-recovery overlap; avoid implementing a general graph framework. |
| [SQLite transactions](https://www.sqlite.org/lang_transaction.html) | Short local transactional state/intent/receipt writes. |
| [SQLite WAL](https://www.sqlite.org/wal.html) | Local WAL constraints; avoid cross-host/distributed durability claims. |
| [Java 17 FileLock](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileLock.html) | Same-host worker exclusivity. |
| [Java 17 Future](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Future.html) | Cooperative cancellation; discard late tool results and stop dispatch on ignored interruption. |

The original contribution is the Java integration, saved-contract recovery and inspectable failure demonstrations. It reuses JDK concurrency/JDBC, SQLite persistence and established retry/checkpoint/key patterns. SQLite JDBC, Gson, JUnit and Maven are pinned; actual artifact/license evidence is separate from these design references in dependency-inventory.json and THIRD_PARTY_NOTICES.md.

On 2026-10-08 final CI preparation read official pinned checkout/setup-java sources and GitHub runner/billing documentation; immutable pin/license provenance is in ci-provenance.json and ci.md. Hosted CI remains unexecuted. No external source is used to claim benchmark results, user adoption, exactly-once effects or hard Java termination.
