# durable-agent-jobs

[![CI](https://github.com/sha-shank-03/durable-agent-jobs/actions/workflows/ci.yml/badge.svg)](https://github.com/sha-shank-03/durable-agent-jobs/actions/workflows/ci.yml)

Open-source Apache-2.0 project. [Project context](docs/PROJECT_CONTEXT.md) · [Independent local verification](docs/INDEPENDENT_VERIFICATION.md) · [Contributing](CONTRIBUTING.md)

A small local Java runtime for inspectable agent jobs. It persists tool intents, checkpoints and bounded retry schedules, executes a deterministic mock ticket agent, and recovers according to explicit tool contracts.

**Current status:** The agreed local MVP passed final coordinating-agent verification on Windows/JDK 17: 39 unit tests, fresh packaged crash/recovery demonstrations, and direct SQL/package checks. See [independent verification](docs/INDEPENDENT_VERIFICATION.md) and [EVIDENCE.md](EVIDENCE.md). Hosted CI and POSIX execution were unexecuted at local acceptance; current hosted results appear in Actions.

## Quickstart on Windows

Requires JDK 17 on PATH (or JAVA_HOME), with writable local work/temp directories. Optional verifiers use Python 3.10+ standard library. No machine-wide Maven installation, model credentials, broker or service.

The first build downloads checksum-pinned Maven and pinned dependencies. The packaged mock runtime needs no network. After warming the cache, add -o to the Maven command for offline verification.

From the repository:

~~~powershell
.\tools\bootstrap.ps1
$env:MAVEN_USER_HOME = Join-Path (Get-Location) 'work/maven-user-home'
$env:MAVEN_OPTS = '-Xmx512m -XX:MaxMetaspaceSize=256m'
.\mvnw.cmd -B -ntp verify
java -Xmx256m -XX:MaxMetaspaceSize=128m -jar target/durable-agent-jobs.jar --help
java -Xmx256m -XX:MaxMetaspaceSize=128m -jar target/durable-agent-jobs.jar demo --dir work/demo --scenario happy
java -Xmx256m -XX:MaxMetaspaceSize=128m -jar target/durable-agent-jobs.jar demo --dir work/demo --scenario all
~~~

Bootstrap verifies the Maven 3.10.0 archive against its pinned upstream SHA-512 and writes its computed SHA-256 into wrapper properties. That verification ran locally; the computed SHA-256 is now pinned. Bootstrap preloads the upstream Windows wrapper cache under work/; dependencies also stay under work/m2. A preexisting wrapper cache is trusted local build state.

## POSIX quickstart

Prepared for a Java 17 POSIX host, with sh plus curl or wget and unzip for initial wrapper setup. This path has not been executed on this Windows machine.

~~~sh
export MAVEN_USER_HOME="$PWD/work/maven-user-home"
export MAVEN_OPTS="-Xmx512m -XX:MaxMetaspaceSize=256m"
sh ./mvnw -B -ntp verify
java -Xmx256m -XX:MaxMetaspaceSize=128m -jar target/durable-agent-jobs.jar --help
java -Xmx256m -XX:MaxMetaspaceSize=128m -jar target/durable-agent-jobs.jar demo --dir work/demo --scenario happy
java -Xmx256m -XX:MaxMetaspaceSize=128m -jar target/durable-agent-jobs.jar demo --dir work/demo --scenario all
~~~

The pinned clean plugin has not been cached/executed locally; -o clean verify would need that plugin and its dependencies already resolved. The final local build starts with a fresh generated target directory and uses -o verify. Keep retained databases under work/ when rebuilding.

The happy demo creates a fresh run directory and asserts successful completion, same-ID duplicate requests, changed-spec rejection, two tool checkpoints and one ticket effect after reopening both databases. The all demo supervises owned child JVMs that really halt with exit 86 after committing the fictional tool effect, before the runtime receipt/checkpoint. It verifies keyed recovery with two calls/one effect, unsafe recovery with one call/NEEDS_REVIEW, exhaustion after restart, cancellation/expiry, cooperative timeout and worker locking. Individual scenarios are crash-idempotent and crash-unsafe. Inputs are fictional; there are no model calls or recorded model replays.

Run individual commands:

~~~powershell
java -jar target/durable-agent-jobs.jar --db work/example/jobs.db submit --key request-001 --spec examples/ticket.json
java -jar target/durable-agent-jobs.jar --db work/example/jobs.db worker --drain
java -jar target/durable-agent-jobs.jar --db work/example/jobs.db inspect --key request-001 --json
# The identical submission returns the original ID with duplicate=true:
java -jar target/durable-agent-jobs.jar --db work/example/jobs.db submit --key request-001 --spec examples/ticket.json
java -jar target/durable-agent-jobs.jar --db work/example/jobs.db worker --drain
~~~

Cancel a queued or running job from another CLI process:

~~~powershell
java -jar target/durable-agent-jobs.jar --db work/example/jobs.db cancel --key request-001
~~~

Cancellation of an already terminal job reports cancel_requested=false. Cancellation cannot undo a tool effect. An optional policy object in the job JSON controls max_attempts (default 3, range 1..10), retry_base_ms (100, 0..2000), tool_timeout_ms (5000, 1..60000), and job_timeout_ms (60000, 1..86400000). Attempts apply per logical operation; the submission deadline includes queue/retry time. Backoff doubles per failed attempt, capped at 2000 ms. Omitted and explicit default policy preserve request identity; a changed policy conflicts under a reused request key.

Default job DB: work/jobs.db. Default tool DB: mock-tool.db beside the job DB; --tool-db selects another local path. Both databases retain fictional demo inputs/receipts. Inspection shows ordered events, operation contracts, attempts and checkpoint contents.

A reused request key with different validated fields returns REQUEST_CONFLICT and exit 3. JSON field ordering and an omitted max_steps default do not change identity. No operation is dispatched by submit.

## Java embedding

~~~java
var store = new SqliteJobStore(Path.of("work/jobs.db"));
var tool = new MockTicketTool(Path.of("work/mock-tool.db"));
var spec = new JobSpec(1, JobSpec.MOCK_AGENT, "Fictional request", "Synthetic data.", 20);
var submission = store.submit("request-001", spec);
var worker = new JobWorker(store, new MockTicketAgent(), List.of(new NormalizeTool(), tool));
var summary = worker.drain();
var report = store.inspectKey("request-001");
~~~

API classes are under dev.durablejobs.api; store, runtime and mock provide the corresponding concrete types. The CLI supports built-in keyed and unsafe mock agents; use --unsafe-mock for mock-unsafe-ticket/v1 and a fresh separate tool DB. Compiled adapters are trusted Java code; there is no plugin loader or untrusted-code isolation.

## Contracts and limits

See [architecture](docs/architecture.md), [tool contracts](docs/tool-contracts.md), and [limits and contribution](docs/limitations.md). A stable operation key is independent of the worker process/attempt. The mock keyed tool commits deduplication key, fictional effect and receipt in its own transaction. Its invocation counter differs from effect count.

ReplayContract is an adapter assertion. It does not prove provider deduplication or exactly-once external effects. An abandoned PURE/keyed intent may retry its saved version, arguments and stable invocation key within the persisted budget. An abandoned non-idempotent intent pauses at UNKNOWN/NEEDS_REVIEW. Only typed retryable ToolFailure classifications and attempt timeouts allow bounded safe retries; an unclassified adapter exception remains UNKNOWN/NEEDS_REVIEW.

One worker, one executing job, same host/local filesystem. Tool steps default to 20, maximum 100. Spec/receipt limits are 64 KiB; checkpoints 256 KiB; JSON nesting 32; title 200 characters/body 10,000. Unknown fields/versions and duplicate JSON members fail validation. History/keys accumulate across jobs. The control loop polls durable controls and monotonic attempt duration; receipt/final commits recheck deadline, cancellation and generation inside their transaction. Tool threads never write runtime progress.

Java interruption is cooperative. A tool that ignores the bounded interruption grace stops dispatch and quarantines the worker lock until JVM exit; its late result cannot commit runtime progress, but its external effects may continue. Agent.next is synchronous trusted code and must promptly return a pure decision; there is no hard agent/thread termination. The process-level fault hook --mock-fault after-tool-commit is explicit and limited to built-in fictional ticket adapters.

The contribution is an inspectable integration of established job/checkpoint/idempotency patterns with reproducible failure demonstrations. It does not replace Temporal or LangGraph, claim novel guarantees, or report benchmark/adoption results.

## Tests and evidence

The 39 passing tests cover CLI/API happy flow and conflicts, atomic storage, checkpoint persistence, actual keyed/unsafe child-JVM crash recovery, persisted due times/budgets, cancellation/expiry during execution, cooperative and ignored interruption, stale commits and second-worker rejection. Scheduling tests use injected clocks/gates; owned child processes have bounded waits and scoped cleanup.

Run the independent seven-process CLI acceptance script after building:

~~~powershell
.\tools\verify-m1.ps1
# Optional Python 3 stdlib verifier: independent SQLite reads before/after actual JVM recovery:
python tools/verify-m2.py
~~~

The PowerShell verifier records seven process argument arrays/exits/outputs under a fresh work/acceptance/cli-* directory and checks UTF-8 title preservation. The Python verifier creates work/acceptance/m2-* and independently reads the real crash gaps before recovery; both retain fictional DBs and never remove existing ones.

To verify packaged demos and shipped licenses with independent SQLite reads:

~~~powershell
New-Item -ItemType Directory -Force -Path work/smoke | Out-Null
java -Xmx256m -XX:MaxMetaspaceSize=128m -jar target/durable-agent-jobs.jar demo --dir work/smoke/happy --scenario happy > work/smoke/happy.json
java -Xmx256m -XX:MaxMetaspaceSize=128m -jar target/durable-agent-jobs.jar demo --dir work/smoke/recovery --scenario all > work/smoke/all.json
python tools/verify-release.py --happy work/smoke/happy.json --all work/smoke/all.json --output work/smoke/package-check.json
~~~

On POSIX, create the directory with mkdir -p work/smoke and use the same Java commands; invoke the verifier with python3. A nonzero command exit is a failure; worker exit 3 can also describe a reported terminal/review job, while intentional fault exit is 86. [CI notes](docs/ci.md) describe the prepared public-repository Windows/Linux matrix, pins and limits.

[EVIDENCE.md](EVIDENCE.md) distinguishes static checks from Maven tests and runtime acceptance. Linux/hosted CI has not run. Apache-2.0 project license; pinned runtime/test/build-tool attribution and coverage limits are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the [dependency inventory](docs/dependency-inventory.json).

## Get the source

```sh
git clone https://github.com/sha-shank-03/durable-agent-jobs.git
cd durable-agent-jobs
```

Follow the quickstart above for runtime/dependency setup. Java projects download pinned build dependencies once; cached mock demos can then run offline.
