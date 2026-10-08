# durable-agent-jobs — implementation plan

Status: **accepted by main; M1/M2 independently accepted; bounded final M3 docs/attribution/prepared CI implemented for whole-repository review**. Initial research retrieved: **2026-10-07**; final attribution/CI provenance: **2026-10-08**. Project version: **0.1.0**.

Assigned repository: C:/Users/shash/Documents/CodexPortfolios/agentic-ai-2026-10-07/durable-agent-jobs
Assigned coordination record: C:/Users/shash/Documents/CodexPortfolios/agentic-ai-2026-10-07/coordination/workers/durable-agent-jobs

## User problem and useful journey

A Java developer has an agent job that reads input, decides on tool calls, and produces a result. A process crash after a tool accepts a write but before the worker saves its receipt can duplicate the write on restart. Retrying the whole job also discards completed work. Developers need to inspect durable progress, uncertain effects, and allowed recovery.

Deliver a local Java CLI and small embeddable API. A developer submits a versioned job specification with a request key, runs a worker, inspects checkpoints/attempts, and restarts after an injected crash. The example is a **deterministic mock ticket agent**: normalize a fictional request, create a mock ticket, summarize its receipt. It supports adapter development and recovery testing before real tools are connected.

Proposed commands are acceptance targets, **not executed results**:

~~~powershell
.\mvnw.cmd -B -ntp verify
java -jar target/durable-agent-jobs.jar --db work/example/jobs.db submit --key request-001 --spec examples/ticket.json
java -jar target/durable-agent-jobs.jar --db work/example/jobs.db worker --drain
java -jar target/durable-agent-jobs.jar --db work/example/jobs.db inspect --key request-001 --json
java -jar target/durable-agent-jobs.jar demo --dir work/demo
~~~

Demo output must label execution_mode=deterministic_mock and distinguish tool invocations from effects. Receipt reuse is recovery from persisted data; neither it nor the mock agent is fresh model execution.

## Existing tools and contribution

| Tool/pattern | Overlap and decision |
| --- | --- |
| Temporal | Durable activities, retries, timeouts, cancellation, idempotency guidance. Prefer it for broader distributed requirements; do not include a server or claim to replace it. |
| LangGraph | Graph checkpoint persistence/fault recovery overlaps this problem. Do not implement a graph framework. |
| Transactional job tables | Reuse unique request keys, durable queue rows and retry scheduling. Those alone do not close an external effect/receipt gap. |

Actual primary reads: [Temporal activity execution](https://docs.temporal.io/activity-execution), [Temporal idempotency guidance](https://temporal.io/blog/idempotency-and-durable-execution), [LangGraph persistence](https://docs.langchain.com/oss/python/langgraph/persistence).

The contribution is an inspectable integration of a Java agent decision interface, durable intents/receipts, explicit tool replay contracts, and reproducible failure scenarios in one local package. A separately persisted mock tool deliberately exposes the external transaction boundary. These are established patterns; claim no new workflow algorithm, adoption, production readiness, or performance advantage.

## Narrow MVP and non-goals

MVP: one worker/process, one executing job, one host/local filesystem; persisted jobs, checkpoints, intents, attempts and ordered events; duplicate delivery/request deduplication; deterministic versioned agent decisions; PURE, IDEMPOTENT_WITH_KEY and NON_IDEMPOTENT tools; persisted bounded retries/due time; tool-attempt timeout and overall deadline; cancellation and restart recovery; CLI submit/worker/inspect/cancel/demo; meaningful failure tests, docs, CI, attribution and actual evidence.

No model SDK/paid call/live LLM evaluation, HTTP server, UI, MCP/A2A, broker, cloud, Docker, Redis or Postgres. No multi-worker/distributed claims, leases/heartbeats, schedules, priorities/token budgets, DAGs/subagents, arbitrary untrusted code, hot upgrades, retention service, hardware durability certification or benchmark. No exactly-once external-side-effect claim, rollback of external effects, hard Java thread termination, or generic reconciliation. NEEDS_REVIEW is inspectable; there is no MVP command to force an uncertain call to rerun.

## Architecture and contracts

Use Java 17 records/interfaces, JDBC, JDK concurrency and a small hand-parsed CLI. Java matches the brief, installed JDK and embedding audience; Python adds no required capability. Avoid Spring, ORM, connection pool, logging framework and general agent framework.

Packages: api (Agent, AgentDecision, JobSpec, ToolAdapter, ToolRequest, ToolResult, ReplayContract); store (schema/transactions); runtime (dispatch/retry/control/recovery); mock (ticket agent/tool); cli (entry/report). Tests use temp directories and controlled tools. Deliver README.md, examples/ticket.json, docs/architecture.md, docs/tool-contracts.md, docs/limitations.md, docs/research.md, LICENSE, THIRD_PARTY_NOTICES.md, EVIDENCE.md and .github/workflows/ci.yml.

### Persistence schema v1

- jobs: ID, unique request key, normalized spec hash/JSON, agent version, state, latest checkpoint sequence, cancellation flag, absolute deadline, execution generation, timestamps/error.
- checkpoints: immutable sequence, bounded agent state JSON and completed operation references.
- operations: unique (job ID, logical operation key), stable invocation key, tool/version, arguments hash/JSON, replay contract, intent/receipt state, result, attempt count and retry due time.
- attempts: operation/number, generation, start/end, classified outcome/error.
- events: per-job sequence, type, operation reference and sanitized bounded details.

Use WAL, synchronous=FULL, foreign_keys=ON and finite busy_timeout on appropriate connections. Keep transactions short and never invoke a tool inside a runtime DB transaction. SQLite WAL is same-host and transactions across databases are not atomic as a set. The mock tool therefore uses a **separate SQLite file**. [SQLite WAL](https://www.sqlite.org/wal.html)

A FileChannel lock beside the canonical DB path admits one worker; a second worker fails before recovery/dispatch. CLI submission/inspection/cancellation use independent short transactions. Support a canonical local path; filesystem alias/hard-link misuse is outside the supported contract.

### Agent/tool boundary

Agent.next(spec, checkpoint) returns a bounded ToolRequest or completed result. Decisions must be pure, deterministic, versioned and free of I/O. Trusted compiled Java adapters own tools; the runtime owns execution/persistence. The mock is a fixed policy, not an LLM.

Before invocation, atomically persist immutable intent/payload hash/contract and increment attempt count. Commit successful receipt, new checkpoint and progress in one runtime transaction. Recover saved intents/receipts rather than ask an agent to invent another request at an uncertain boundary.

Stable operation ID derives from job ID plus the agent's stable logical operation key, never an attempt/process ID. Persist tool/version/hash. Changed payload or contract under the same key is a permanent violation. Reject unknown schema/agent/tool/version before effects. Initial spec and persistence version: 1; built-in versions: mock-ticket/v1 and mock-ticket-tool/v1. No external wire protocol is introduced.

| Contract | Recovery rule | Required adapter behavior |
| --- | --- | --- |
| PURE | Repeat within persisted attempt bounds. | No side effects; repetition safe. |
| IDEMPOTENT_WITH_KEY | Repeat identical key/payload within bounds. | Atomic dedupe with effect, stable receipt, payload conflict rejection, key retention. Declaration alone is not proof. |
| NON_IDEMPOTENT | Unfinished intent after crash, timeout or ambiguous error -> NEEDS_REVIEW; no automatic reinvocation. | Explain effects and possible manual investigation. |

The keyed mock atomically inserts operation key/payload hash, ticket and receipt in its own DB. Repetition returns that receipt; changed payload fails. Retain keys for DB lifetime. Track invocation attempts separately so the crash test can show two invocations and one effect. This proves only the mock's tested behavior.

### States and control

States: QUEUED, RUNNING, WAITING_RETRY, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, NEEDS_REVIEW.

Claim transactionally and establish a generation; execute one step at a time. Retry classified transient failures only for replay-safe tools. Default maxAttempts=3 per operation includes the initial attempt; deterministic backoff starts at 100 ms, capped at 2 seconds. Reject invalid/unbounded config. Persist attempts **before** invocation; crash may conservatively consume an attempt even if execution never started. Restart never resets count/due time/deadline. Bound total decisions/operations (default 20), duration configuration and per-record JSON sizes.

Store absolute job deadline at submission; use monotonic time for active attempt elapsed time. Recovery uses persisted wall-clock deadline; clock changes can affect it. Before dispatch and accepting completion, check cancellation/deadline/generation. Only the control loop commits results, preventing late completion from resurrecting terminal state.

Cancellation is a durable request: once observed, stop subsequent steps, invalidate generation, request interruption and record CANCELLED. Existing effects may remain; expose unknown operation outcomes. Overall deadline -> TIMED_OUT with no further dispatch. Attempt timeout requests interruption; replay-safe retry is allowed only after the previous in-process invocation ends. Unsafe ambiguous invocation -> NEEDS_REVIEW.

Java interruption is cooperative. Require bounded/cooperative adapters. If a tool ignores interruption beyond a bounded grace, stop new dispatch, report TOOL_DID_NOT_STOP and require process restart; never overlap its retry. Fencing protects runtime records, not outside effects. [Java Future](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Future.html)

After obtaining the exclusive lock, startup examines abandoned RUNNING jobs: honor cancellation/deadline first, reuse completed receipts, recover unfinished replay-safe intents under existing budgets/due time, pause unsafe ones. Persistence failure visibly stops dispatch; failed commit must never report success. Cancellation can race with dispatch/effects; guarantees concern orchestration after observation, not reversal or prevention of an already sent request.

## Dependencies, pins, availability and licenses

All listed POMs were actually read and selected JAR/ZIP HEADs returned HTTP 200 on 2026-10-07. No binary download/resolution occurred; headers do not establish functioning binaries on this HP. Pins are deliberate stable choices, not a claim every component is latest.

| Component | Pin | Purpose/license |
| --- | --- | --- |
| Java | release 17; local Microsoft OpenJDK 17.0.18+8-LTS observed | Installed runtime, not redistributed. javac=17.0.18. |
| Maven / wrapper | 3.10.0 / 3.3.4 only-script | Repo-scoped bootstrap; Apache-2.0. ZIP HEAD=10,102,082 bytes. |
| org.xerial:sqlite-jdbc | 3.53.4.0 default JAR | SQLite 3.53.4/native JDBC; Apache-2.0, legacy BSD-2-Clause attribution, SQLite public domain. HEAD=12,002,370 bytes. |
| com.google.code.gson:gson | 2.14.0 | Strict bounded JSON/checkpoints; Apache-2.0. HEAD=313,604 bytes. |
| error_prone_annotations (Gson transitive) | 2.48.0 | Apache-2.0; actual POM read. |
| org.junit:junit-bom / junit-jupiter | 6.1.3, test only | Java 17 baseline; EPL-2.0; align Platform through BOM. |
| JUnit test transitives | opentest4j 1.3.0; apiguardian-api 1.1.2; jspecify 1.0.0 | Apache-2.0 from actual POMs; all POM/JAR checks 200. |
| Maven compiler / Surefire / Shade | 3.14.1 / 3.5.5 / 3.6.1 | Release=17, test discovery, executable JAR; Apache-2.0. |
| Maven clean / resources / jar | 3.5.0 / 3.3.1 / 3.4.2 | Pin lifecycle plugins instead of implicit defaults; Apache-2.0. |

sqlite-jdbc SLF4J 1.7.36 is optional and GraalVM nativeimage is provided; do not add without demonstrated need. Default JAR includes natives; no native compilation. Shade must preserve JDBC service metadata and packaged upstream notices.

Maven ZIP: https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.10.0/apache-maven-3.10.0-bin.zip
Upstream SHA-512 sidecar was read (not locally computed):
22d31676d5b92ed53308c19ecded3245d0efd0cddfec51ec7bab62f4f13ad70ddddceff8e62940e0e8e2421f3cde8106737f4becec0aa034ffeff2a754e88337

In authorized M1 bootstrap, verify bytes against SHA-512, compute wrapper distributionSha256Sum and record both. Use repository-local work caches/MAVEN_USER_HOME/maven.repo.local; no machine-wide install. A first build needs network/cache; **offline runtime** means the packaged mock demo needs no network.

Project license: Apache-2.0. THIRD_PARTY_NOTICES distinguishes runtime/test/build tools. Implementation must record resolved tree and complete packaged notices; planning is not a full redistribution audit. Verify and pin CI action commit SHAs when authoring CI.

## Bounded milestones

**M1 — first runnable slice:** pinned bootstrap/wrapper/POM/JAR; schema; worker lock; submit dedupe/validation; agent/tool interfaces and three-stage mock/separate keyed tool DB; transactional receipt/checkpoint; inspect JSON; quickstart/license/attribution and happy/dedupe/conflict tests. Acceptance: submit -> run -> inspect succeeds; duplicate returns same ID; conflicting spec rejected; repeated run adds no effect; completed checkpoint survives restart. Do not claim crash-gap recovery before M2.

**M2 — failures/recovery:** retry/due time/bounds; deadline/timeout/cancellation/generation; unfinished intent recovery; bounded fault hooks; actual child-JVM termination after mock-tool commit but before runtime receipt; crash, unsafe, exhaustion, cancellation and lock tests; asserting demo. Mock-only explicit crash flag halts only its child JVM. Use new work subdirectories; never remove an existing user DB or kill unrelated processes. Acceptance: keyed crash yields two calls/one effect/success; unsafe crash yields NEEDS_REVIEW/no reinvocation; exhausted attempts stay exhausted; cancelled/expired jobs stop progress; second worker safely rejected.

**M3 — docs/CI/evidence:** final Windows/POSIX quickstart, architecture/contracts/contribution/limits/research, complete attribution, pinned-action Windows/Linux Java 17 CI with tests/package/mock demo and no paid calls/secrets. EVIDENCE.md records actual commands/cwd/environment/exit codes/counts/assertions, and unexecuted Linux/remote CI. Preserve preexisting AGENTS.md. No GitHub creation/publication/push, deployment or account changes.

Each milestone stops for main inspection and next scoped assignment. A named durable-agent-jobs lease in main-owned resources.json is required before heavy bootstrap/build/integration. Worker cannot grant its own lease. No implementation until explicit accepted-plan dispatch.

## Meaningful tests and acceptance

Use injectable scheduling time, controlled outcomes and real subprocesses. Caught exceptions do not substitute for crash tests. Synchronize on ready/intent events with bounded deadlines, not fixed sleeps.

| Test | Assertion |
| --- | --- |
| Normal job | SUCCEEDED, expected fictional ticket/summary and ordered checkpoints/events. |
| Duplicate delivery/request | One job ID; repeated completed delivery yields no extra effect. |
| Key conflict | Changed spec/operation payload rejected before dispatch. |
| Runtime commit fault | No partial receipt/checkpoint advance; prior intent remains recoverable. |
| Crash after tool commit | Child exits 86; same operation key on restart; invocation_count=2, effect_count=1, SUCCEEDED. |
| Receipt already committed | Restart skips operation, zero extra invocation. |
| Unsafe crash | One effect/call; recovery NEEDS_REVIEW, no second call. |
| Retry classification/bounds | Two transient failures then success use three attempts; permanent uses one; third transient exhausts to FAILED. |
| Retry restart | Counters/due time preserved, no early retry/reset. Repeated crashes consume bounds instead of looping. |
| Cancellation races | Queued tool never runs; cooperative running tool stops; no later steps/terminal resurrection. |
| Deadline/timeout/late result | Expired queued/recovered job never dispatches; attempt timeout obeys contract; late result cannot advance state. |
| Ignored interruption | After grace, worker stops dispatch and reports TOOL_DID_NOT_STOP; outside outcome remains uncertain. |
| Worker exclusivity | Second worker fails without altering first; process crash releases OS lock. |
| Invalid input/storage | Unknown versions/tools, oversized JSON fail; persistence failure never reports success. |

Final commands (targets, not yet run):

~~~powershell
.\mvnw.cmd -B -ntp verify
java -jar target/durable-agent-jobs.jar --help
java -jar target/durable-agent-jobs.jar demo --dir work/acceptance --scenario happy
java -jar target/durable-agent-jobs.jar demo --dir work/acceptance --scenario crash-idempotent
java -jar target/durable-agent-jobs.jar demo --dir work/acceptance --scenario crash-unsafe
java -jar target/durable-agent-jobs.jar demo --dir work/acceptance --scenario all
~~~

Each demo creates a new run directory below its parent. Build/help exit 0; tests have zero failures/errors and recorded actual count; JAR exists. Happy asserts execution_mode=deterministic_mock, SUCCEEDED, duplicate_same_job=true, effect_count=1. Keyed crash asserts observed child exit=86, recovery=SUCCEEDED, calls=2/effects=1/stable key. Unsafe crash asserts observed exit=86, NEEDS_REVIEW, calls=1/effects=1. All also asserts retries/cancel/deadline/lock, assertions_failed=0 and exit 0. Semantic failures have documented nonzero codes. Never copy predicted output into evidence; main runs commands independently.

## Risks and essential unanswered questions

Tool contracts do not prove arbitrary provider behavior. External effects/runtime receipt cannot generally be atomic; timeout/cancellation do not undo effects. Java interruption is cooperative. SQLite native extraction, shaded loading and Maven resolution remain untested on this HP. WAL/storage/hardware faults have distinct failure domains; test process crashes/simulated storage faults without hardware certification claims. Unknown schema/agent versions fail closed. Per-job bounds exist but accumulated history/keys grow; document intended small local use. Remote CI is unexecuted until observed.

No essential product question blocks assigned M3. Required next step: **main independently reviews the whole repository and releases the named M3 lease**. Stop after this assignment; publication/deployment/live-provider work remains unauthorized.

## Research and planning evidence

Actual primary-page/POM reads retrieved 2026-10-07 (search snippets only located pages):

- https://docs.temporal.io/activity-execution — overlap/lifecycle/cancellation.
- https://temporal.io/blog/idempotency-and-durable-execution — retry/stable keys/provider enforcement.
- https://docs.langchain.com/oss/python/langgraph/persistence — checkpoint scope/restart. Requested durable-execution URL redirected here.
- https://www.sqlite.org/wal.html and https://www.sqlite.org/pragma.html#pragma_synchronous — local WAL, sync and transaction limits.
- https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Future.html — cooperative interruption.
- https://github.com/xerial/sqlite-jdbc and https://github.com/xerial/sqlite-jdbc/releases/tag/3.53.4.0 — native/default JAR, release, JDBC services.
- https://raw.githubusercontent.com/xerial/sqlite-jdbc/3.53.4.0/LICENSE and https://raw.githubusercontent.com/xerial/sqlite-jdbc/3.53.4.0/LICENSE.zentus — Apache/BSD texts.
- https://www.sqlite.org/copyright.html — public domain.
- https://github.com/google/gson/releases/tag/gson-parent-2.14.0 and https://raw.githubusercontent.com/google/gson/gson-parent-2.14.0/LICENSE — release/Apache text.
- https://docs.junit.org/6.1.3/release-notes.html and https://docs.junit.org/6.1.3/running-tests/build-support.html — Java 17/BOM/Surefire support.
- https://raw.githubusercontent.com/junit-team/junit-framework/r6.1.3/LICENSE.md — EPL text.
- https://maven.apache.org/download.cgi and https://maven.apache.org/tools/wrapper/ — release/Apache license/Windows scripts/checksum properties.
- https://maven.apache.org/plugins/maven-compiler-plugin/ ; https://maven.apache.org/surefire/maven-surefire-plugin/ ; https://maven.apache.org/plugins/maven-shade-plugin/ — build behavior/official license links.
- https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.10.0/apache-maven-3.10.0-bin.zip.sha512 — actual sidecar GET=200.

Exact runtime POM reads:
https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.53.4.0/sqlite-jdbc-3.53.4.0.pom
https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.14.0/gson-2.14.0.pom
https://repo.maven.apache.org/maven2/com/google/errorprone/error_prone_annotations/2.48.0/error_prone_annotations-2.48.0.pom

Other exact artifact URLs and HTTP results are recorded in the own coordination artifact-checks.json. POM URLs use Maven Central group/artifact/version; JAR HEAD uses the same base. Checked JUnit API/engine/Platform POMs confirmed pinned test transitives. Surefire 3.5.4 was initially checked, then 3.5.5 selected from current JUnit guidance; both available. Three attempted Apache /licenses.html web reads returned Internal Error; official license links/POMs/upstream license texts supplied licensing evidence instead. Complete build-plugin transitive attribution remains an implementation check.

Observed local checks: read assigned AGENTS.md, own status/assignment and human user-request/resources; initial git status=?? AGENTS.md and main had no commits; java/javac=17.0.18; Get-Command mvn returned no command. A git log mistakenly in coordination returned not a Git repository, then was corrected; no mutation. First large document-write command exceeded Windows command-length limit before starting; smaller patch writes used instead.

Unexecuted: binary/bootstrap/hash verification, dependency resolution/tree, all builds/runtime/failure/demo tests, shaded driver loading, packaged attribution, Linux/remote CI. No paid calls, installs, heavy processes, implementation or publication during planning.
