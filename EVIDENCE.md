# Local execution evidence

Current delivery: final M3 documentation/attribution/prepared CI implemented and locally verified, awaiting main whole-repository independent acceptance. M1/M2 are independently accepted. Their sections below are historical; the M3 section records current source integrity, the fresh offline build, packaged smoke and explicitly unexecuted hosted/POSIX checks.

## M1 local execution evidence

Observed 2026-10-07 on the assigned HP, Windows/PowerShell, Microsoft OpenJDK/javac 17.0.18. Repository working directory for all implementation/build/CLI commands:
C:/Users/shash/Documents/CodexPortfolios/agentic-ai-2026-10-07/durable-agent-jobs

Main granted the durable-agent-jobs build lease at 2026-10-07T15:27:38.658591+00:00. No heavy bootstrap/build/integration ran before that grant. Main resource records are read-only to this worker.

## Actual commands and results

| Command/check actually executed | Observed result |
| --- | --- |
| PowerShell XML parse of pom.xml and Parser.ParseFile(tools/bootstrap.ps1); rg source/test inventory | project XML root; zero PowerShell parse errors; 24 Java files, 22 prepared tests. This was a static check, not Java compilation. |
| .\tools\bootstrap.ps1 | Exit 0. Maven 3.10.0 ZIP matched pinned upstream SHA-512; SHA-256 computed and saved to wrapper properties. Repo-local Windows wrapper cache preseeded. |
| $env:MAVEN_USER_HOME=Join-Path (Get-Location) 'work/maven-user-home'; $env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=256m'; .\mvnw.cmd -B -ntp verify | Exit 0 / BUILD SUCCESS; 21 main and 3 test source files compiled for release 17; 22 tests, 0 failures/errors/skips. Total Maven time reported 50.108 seconds; not a performance benchmark. |
| java -Xmx256m -jar target/durable-agent-jobs.jar --help | Exit 0; M1 commands/limits and deferred recovery features displayed. |
| java -Xmx256m -jar target/durable-agent-jobs.jar demo --dir work/acceptance/demo --scenario happy | Exit 0; SUCCEEDED, same-ID dedup, changed-spec rejection, persisted completed checkpoints, repeat worker processed=0, calls=1/effects=1, assertions_failed=0. |
| .\tools\verify-m1.ps1 (first run) | Exit 0; seven separate CLI processes verified submit/duplicate/run/inspect/conflict/repeat/inspect. Conflict alone expected exit 3. |
| Parse actual Surefire java.class.path and local resolved Maven POMs; hash each JAR | 12 resolved project artifacts: 3 runtime, 9 test. Names, versions, POM license statements and digests saved in docs/resolved-dependencies.json. |
| After adding project license resource and UTF-8 stdout/stderr: .\mvnw.cmd -B -ntp -o verify, same Maven environment | Exit 0 / BUILD SUCCESS from cached dependencies; again 22 tests, 0 failures/errors/skips. Maven reported 16.881 seconds. Test child heap bounded to 256 MiB/metaspace 128 MiB, one fork; Maven heap 512 MiB/metaspace 256 MiB. |
| .\tools\verify-m1.ps1 (final run, includes non-ASCII fictional title) | Exit 0; seven independent CLI processes, same ID, conflict exit 3/REQUEST_CONFLICT, SUCCEEDED/checkpoint sequence 2, repeated worker processed 0, calls=1/effects=1, identical inspect contents across processes, UTF-8 title preserved, assertions_failed=0. |
| java -Xmx256m -jar target/durable-agent-jobs.jar demo --dir work/acceptance/final-demo --scenario happy | Exit 0; final packaged artifact repeated all happy demo assertions with calls=1/effects=1. |
| Inspect ZIP archive entries and read META-INF/services/java.sql.Driver | Nine required service/license/notice entries present; service content exactly org.sqlite.JDBC. Project/Gson/error-prone/SQLite Apache/BSD license texts, SQLite notice, project attribution and merged notice preserved. |

Final Maven tests: CliTest 4, JobFlowTest 14, ValidationTest 4. They cover normal flow, request/operation/provider key conflicts, receipt persistence, storage rollback, step cap, stale completion, DB kind/version rejection, worker locking and conservative uncertainty. The lost-response test throws after a mock commit; it is **not** a process-crash test.

No compilation/test failure was observed. Source-writing tool calls initially needed correction for JavaScript quoting/interpolation before patch execution; they did not run tests or alter files. Initial wrapper-template/checksum URL guesses returned 404; the actual upstream tree located the pinned wrapper sources. The Maven ZIP had no SHA-256 sidecar, so verified archive bytes supplied the computed SHA-256.

## Integrity and inspectable local files

Maven upstream SHA-512 matched downloaded archive bytes:
22d31676d5b92ed53308c19ecded3245d0efd0cddfec51ec7bab62f4f13ad70ddddceff8e62940e0e8e2421f3cde8106737f4becec0aa034ffeff2a754e88337

Computed Maven ZIP SHA-256:
1f6d9909266510f039f59aa0e13dcd2c66da85f043e56276e41f2918f8bddaff

Final executable JAR: target/durable-agent-jobs.jar, 12,412,403 bytes.
Observed SHA-256: eb7ea61c8cd039954a17cc2a9e0b91c2c70296e1c7fc21f660ceeca74640c21a.

Raw build/bootstrap records: work/m1-build.log, work/m1-final-build.log, work/bootstrap/evidence.json.
Final independent CLI output/argument arrays/exits: work/acceptance/cli-3564d96ab74d45848ee93cb7ae7204e7/commands.json and summary.json, with per-command stdout/stderr and persistent databases.
Final mock demo output: work/acceptance/final-demo.json.
Package check: work/acceptance/package-check.json.
Surefire XML/text reports: target/surefire-reports/.
Runtime/test license/digest snapshot: docs/resolved-dependencies.json.
Wrapper/upstream license provenance: docs/vendor-source-manifest.json.

Generated paths and IDs are machine/run specific. tools/verify-m1.ps1 creates fresh directories and reproduces the assertions without overwriting these databases.

## Limits and unexecuted checks

All execution was deterministic mock/local; no model API, credentials, production action, remote service, publication or paid call.

The build reported only overlapping META-INF/MANIFEST.MF resources during shading. Manifest/service transformers configured the executable entry point and preserved JDBC loading; the packaged CLI worked. This warning was not silently treated as proof that all possible shading behavior is safe.

Actual project runtime/test classpath licenses were inspected; the complete Maven plugin implementation/transitive inventory is not captured in that snapshot. No PGP signature verification, reproducible-build comparison, Linux run or remote CI run was performed.

M2 unexecuted/unimplemented: actual child-JVM crash-gap recovery, retries/exhaustion/due-time recovery, cancellation/timeouts/cooperative shutdown monitoring, unsafe replay fixture. M1 deliberately refuses abandoned RUNNING jobs and never claims crash-gap recovery. M3 CI/docs completion remains a later assignment.

No claim of exactly-once arbitrary external effects, hard interruption, hardware/power-loss durability, security certification, performance improvement or adoption follows from these results.

## M2 local execution evidence

Observed 2026-10-07 in the same assigned repository, Windows/PowerShell, OpenJDK 17.0.18. Optional independent verifier used installed Python 3.12.7 and only its standard library. Main independently accepted M1 and granted the named M2 lease at 2026-10-07T16:00:39.934183+00:00. Only one worker-owned heavy build/integration command ran at a time.

Implemented persisted per-operation attempt bounds, capped retry due times, typed failure classification, safe saved-intent recovery, cancellation, submission deadlines, monotonic tool-attempt timeout, transactional generation/control fencing and bounded mock-only process halts. The unsafe fixture has a genuinely nonunique invocation key/effect table. No dependencies or pins changed.

| Command/check actually executed | Observed result |
| --- | --- |
| $env:MAVEN_USER_HOME=Join-Path (Get-Location) 'work/maven-user-home'; $env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=256m'; .\mvnw.cmd -B -ntp -o verify > work/m2-build.log | Exit 0 / BUILD SUCCESS; initial M2 33 tests, zero failures/errors/skips. Includes actual owned keyed/unsafe JVM halt/recovery. |
| Same bounded environment; .\mvnw.cmd -B -ntp -o verify > work/m2-final-build.log | Exit 0 / BUILD SUCCESS; 25 main/4 test source files compiled; 39 tests, zero failures/errors/skips after adding atomic failure-control checks, receipt/deadline boundary, cooperative timeout and exhaustion/reopen assertions. |
| python tools/verify-m2.py (initial probe) | Exit 1 in verifier JSON parsing, after six owned JVM commands all returned expected exits. It incorrectly assumed the unsafe worker's expected exit-3 summary used stderr. Actual stdout reports NEEDS_REVIEW/calls=1/effects=1. Corrected verifier stream selection; runtime/test assertions unchanged. Failed run retained. |
| .\tools\verify-m1.ps1 > work/m2-m1-regression.log | Exit 0. Seven separate packaged CLI processes preserve same-ID dedup, changed-spec conflict exit 3, SUCCEEDED/checkpoint 2, exact inspection across reopen, UTF-8 title and one call/one effect. |
| python tools/verify-m2.py > work/m2-packaged-verification.log | Exit 0. Nine supervised owned packaged CLI JVMs; 17 independent Python sqlite3 queries; assertions_failed=0. Reads actual job/tool tables after each halt, before starting recovery. Verifies separate-process cancel and zero dispatch/effects. |
| java -Xmx256m -XX:MaxMetaspaceSize=128m -jar target/durable-agent-jobs.jar demo --dir work/acceptance/m2-demo --scenario all > work/acceptance/m2-all-demo.json | Exit 0; execution_mode=deterministic_mock; all six scenarios pass; assertions_failed=0. Six owned child JVMs exit normally or with the expected fault/review/lock code. Parent observes and waits for each exit. |
| python work/audit-m2.py (initial package probe) | Exit 1 after its 15 independent SQL checks passed: wrong expectation of META-INF/LICENSE. Inspected actual entries; the explicit project license is META-INF/licenses/project-LICENSE.txt, preserved by the existing shade configuration. Corrected the probe to compare that entry byte-for-byte with LICENSE. No package/source change. |
| python work/audit-m2.py > work/m2-package-sql-audit.log | Exit 0; 15 independent post-demo sqlite3 queries pass; seven nonempty packaged license/notice files, exact project license bytes, merged notice/third-party attribution, JDBC service/manifest and all three schema resources verified. |
| git diff --check; PowerShell Parser.ParseFile(tools/verify-m1.ps1); source/status inventory | No whitespace diagnostic or PowerShell syntax error; initial repository still has no commits and all intended files are untracked. No other worker/main-owned files changed. |

The command table uses shell redirection shorthand; the actual bounded PowerShell launchers retained exit codes before reading their logs. Java sources did not produce a compilation or test failure. Maven again reported the overlapping manifest warning already described in M1; executable manifest/JDBC/service checks passed.

Final tests: CliTest 4, JobFlowTest 14, RecoveryTest 16, ValidationTest 5. Recovery assertions include:

- Real child exit 86 after tool effect commit, runtime job RUNNING/checkpoint 1 and create-ticket INTENT with no runtime receipt. These are actual halts, not exceptions presented as process crashes.
- Keyed restart: child exit 0, SUCCEEDED/checkpoint 2, attempt count 2, two tool invocations with one distinct stable key and one effect. Unsafe restart: child exit 3, NEEDS_REVIEW/checkpoint 1, attempt count 1, one effect/no reinvocation.
- Due time survives reopen; injected clock at due-minus-1 does not claim work, exact due does. Saved arguments/tool/contract survive a new generation. Synthetic repeated abandonment consumes the persisted budget; classified exhaustion also survives reopen and an actual owned CLI restart.
- Queued cancellation and expiry dispatch no operation. Gated running tools are interrupted on cancellation/expiry; generation cleared and later steps/effects absent. Atomic receipt/failure transactions recheck controls; a result at exact deadline cannot commit. Existing stale-generation/checkpoint tests remain.
- Cooperative attempt timeouts retry only safe operations, exhaust at the configured bound and discard returned late results. The ignored-interruption fixture stops dispatch, retains NEEDS_REVIEW and quarantines the lock; its test explicitly releases/observes the owned tool thread before the test JVM exits.
- A second owned JVM receives WORKER_ALREADY_RUNNING/exit 4 without changing the job. No unrelated process was terminated, and no existing database was removed.

The packaged all demo's independent SQL results confirm attempts=3 and FAILED/ATTEMPTS_EXHAUSTED after exhaustion, no effects; CANCELLED and TIMED_OUT with checkpoint 0/no operations; timeout attempts=3/no receipt/checkpoint 0/no effect. Its controls scenario explicitly labels injected_fixed_clock; it does not claim a wall-clock waiting benchmark.

Maven heap/metaspace remained 512/256 MiB with one Surefire fork at 256/128 MiB. RecoveryDemo and the Python verifier's owned JVMs use 256/128 MiB; the unchanged M1 acceptance script caps heap at 256 MiB. Child JVM deadlines are 20 seconds with a 5-second owned-process termination budget. Executor interruption grace is 250 ms, with an additional bounded termination check in finally; there is no hard thread-stop guarantee. All owned build/test/CLI processes exited; the named lease is ready for main release.

### M2 retained evidence and artifact

Final executable JAR: target/durable-agent-jobs.jar, 12,440,077 bytes.
SHA-256: 5e626713061aba4e9cc464fb9a314aa01f5d7685e4559936ae42ae008d0abc5f.

Raw builds: work/m2-build.log and work/m2-final-build.log. Final Surefire reports: target/surefire-reports/.

M2 external verifier: work/acceptance/m2-f18bde9008964abc86a83cc5d40466c2/summary.json, commands.json (arguments/PIDs/observed exits), independent-sql.json (exact queries/rows) and per-command stdout/stderr. Persistent keyed/unsafe/cancel databases remain there. Each invocation creates a fresh UUID directory.

Initial failed verifier: work/m2-packaged-first-failure.log and work/acceptance/m2-d9c4ba6176024d99bdeac4097af40e24/commands.json, independent-sql.json and six process receipts. All six exited with their expected codes; no success summary was written for that failed probe.

M1 packaged regression: work/acceptance/cli-c0a55a33ca4e41c681f5a0a755ea8db7/summary.json and commands.json; launcher log work/m2-m1-regression.log.

Packaged all demo: work/acceptance/m2-all-demo.json and m2-all-demo.stderr.json; retained run work/acceptance/m2-demo/recovery-10766840992422337778/. Each crash subdirectory contains gap-inspect.json, crashed/recovered stdout/stderr and persistent DBs; exhaustion and lock child receipts remain in their subdirectories.

Independent SQL/package audit: work/audit-m2.py, work/m2-package-sql-audit.log and work/acceptance/m2-package-sql-check.json. Initial missing-entry traceback was observed in the tool transcript; the corrected script compares the existing explicit project license rather than dropping the license requirement.

### M2 limits and unexecuted checks

All runs were local deterministic mocks; no model/API calls, recorded model replay, remote services, production accounts, publication or paid operation. The tool's enum/classification is a trusted adapter assertion. Key retention and actual provider deduplication determine keyed recovery safety. Cancellation/timeout can discard a result and stop further dispatch; they cannot undo an effect, prove arbitrary external work stopped, or forcibly terminate trusted Java code. Synchronous pure Agent.next must promptly return. The worker lock is for one host/local filesystem and does not implement distributed ownership.

M2 local checks above are executed. Independent main M2 review, M3 CI/documentation completion, Linux/hosted CI, full Maven plugin/transitive license inventory, PGP verification, reproducible-build comparison, disk/power-loss injection and live external-provider behavior remain unexecuted. No production-hardening, performance or exactly-once claim follows from this evidence. Stop after M2 and await the next scoped assignment.

## M3 final local evidence

Observed 2026-10-08, same assigned Windows/PowerShell repository and OpenJDK 17.0.18. Main independently accepted M2: 39 tests, real packaged/main-owned halt-86 recovery, 7 commands and 32 retained SQL observations. Main review source: coordination/reviews/durable-agent-jobs-M3.md, read before M3. Main's named assigned_worker M3 lease was granted at 2026-10-08T01:55:39.213164+00:00 for one fresh offline build and bounded sequential packaged smoke.

M3 changes only documentation, attribution/inventory/verification tooling, text attributes and prepared CI. All 40 preexisting source/test/tool/wrapper/POM files match the pre-M3 baseline, and all 39 packaged runtime class files are byte-identical to the artifact present after main M2 review. docs/m3-source-integrity.json preserves those exact SHA-256 records. Runtime features, dependencies and acceptance tests were not changed.

| Exact command/check executed | Observed result |
| --- | --- |
| Read AGENTS.md, main M3 review, accepted PLAN/status and resources.json | M2 independently accepted; bounded final M3 authorized; named lease active; main-owned records read-only. |
| rg cache inventory; python tools/audit-dependencies.py | No binary download or resolution. Inventoried 73 cached artifacts/56 installed Maven JARs and retained upstream license/notice texts. Two POM metadata gaps remain explicit; supplemental exact JDOM/Maven javax.inject texts supply evidence. |
| Official GitHub API GET v5.0.0 tag refs for actions/checkout and actions/setup-java; read pinned README/LICENSE and official billing/runner docs | Checkout commit 08c6903cd8c0fde910a37f88322edcfb5dd907a8; setup-java commit dded0888837ed1f317902acf8a20df0ad188d165. Both action MIT texts/digests retained; sources/times in docs/ci-provenance.json. Metadata/source reads only, no action/JDK/library binary download. |
| python work/check-m3.py; PowerShell Parser.ParseFile on both extracted CI run blocks, bootstrap.ps1 and verify-m1.ps1 | Exit 0; installed PyYAML 6.0.3 YAML parse/policy assertions pass; two immutable actions/public-only guard/read-only permissions/sequential Windows/Linux matrix/budgets verified. Four PowerShell parses have zero errors. Python ASTs/local Markdown links/license digests pass; 40 baseline files unchanged. |
| Canonical path/reparse/existence checks then Move-Item -LiteralPath generated target -Destination work/m3-baseline/target-before-final-build | Generated output preserved within this repository; no existing DB or source removed. Target/source/destination absolute checks retained in work/m3-baseline/clean-path-check.json. Prior reports also preserved. Maven clean 3.5.0 is not cached, so this performs a fresh-output build without resolving it. |
| .\mvnw.cmd -B -ntp -o -Dorg.slf4j.simpleLogger.log.org.apache.maven.classrealm.DefaultClassRealmManager=debug verify (first launch) | Exit 1 before compilation, 0.291 seconds: PowerShell split the unquoted dotted option and Maven reported unknown lifecycle phase. Failed log retained; corrected argument quoting only. No test/runtime source changed. |
| $env:MAVEN_USER_HOME=Join-Path (Get-Location) 'work/maven-user-home'; $env:MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=256m'; .\mvnw.cmd -B -ntp -o '-Dorg.slf4j.simpleLogger.log.org.apache.maven.classrealm.DefaultClassRealmManager=debug' verify | Exit 0 / BUILD SUCCESS from fresh target; 25 main/4 test source files compiled; 39 tests, 0 failures/errors/skips (4 CLI, 14 flow, 16 recovery, 5 validation). One Surefire fork uses 256/128 MiB. No downloads. Selective logging emitted no plugin-realm details; no exact load mapping is claimed and no extra build was run to obtain one. |
| java -Xmx256m -XX:MaxMetaspaceSize=128m -jar target/durable-agent-jobs.jar --help | Exit 0; accepted M2 runtime command surface unchanged. |
| java -Xmx256m -XX:MaxMetaspaceSize=128m -jar target/durable-agent-jobs.jar demo --dir work/m3-smoke/happy --scenario happy | Exit 0; fictional deterministic mock, one call/one effect, SUCCEEDED, dedup/conflict/persisted checkpoint assertions pass. |
| java -Xmx256m -XX:MaxMetaspaceSize=128m -jar target/durable-agent-jobs.jar demo --dir work/m3-smoke/recovery --scenario all | Exit 0; all six scenarios pass, assertions_failed=0; real owned halts/restarts, keyed 2 calls/1 effect/SUCCEEDED; unsafe 1 call/NEEDS_REVIEW; persisted exhaustion, controls, timeout and real worker lock rejection. Six supervised child JVMs exit with observed expected codes. |
| python tools/verify-release.py --happy work/m3-smoke/happy.json --all work/m3-smoke/all.json --output work/m3-smoke/package-check.json | Exit 0; 15 independent read-only sqlite3 queries, exact seven shipped license texts, current packaged third-party notice, three exact schemas, executable manifest and JDBC service verified; assertions_failed=0. |
| python tools/audit-dependencies.py --realm-log work/m3-clean-build.log | Exit 0; final actual Surefire classpath contains 12 JARs; cached descriptors for five executed-build plugins captured; 73 cached artifacts/56 distribution JARs/90 unique retained texts. No actual plugin-realm lines; explicit cached/descriptor candidate labels retained. Clean plugin absent/unexecuted. |
| Repeat final lightweight static/source/license/link checks after inventory finalization; ZIP comparison with preserved prior JAR | Exit 0; 40 baseline file hashes unchanged; exact 39 runtime class names/bytes unchanged. Attributions updated without a runtime feature or dependency change. |
| Repository-scoped Java/javaw process inventory; git diff --check | Zero repository-identifiable JVMs at 2026-10-08T02:17:29.0882479Z; all owned commands exited; no whitespace diagnostic. No unrelated process terminated. |

The corrected launch is the only actual clean compilation/test/package run in M3. The failed argument parse did not enter a build lifecycle. Maven's existing manifest overlap warning remains recorded; actual package/service/license checks pass. Maven times are command evidence, not benchmarks.

### M3 final artifact and retained receipts

Executable JAR: target/durable-agent-jobs.jar, 12,441,556 bytes.
SHA-256: 12572a9886b54fa458d293f93817bf62339b510669d04a96d9e52f08937d2671.

The pre-M3 input JAR present after main review had SHA-256 09a8582c91186ad385458e5c94a3748e9ab7aaf042215d4fbb519f27bc6c6e46. That is distinct from the historical worker M2 JAR hash above because main rebuilt it. Class-byte/source integrity is explicitly verified; no entire reproducible-build claim follows. The packaged third-party attribution intentionally changed.

Build: work/m3-clean-build.log; failed pre-compilation launch: work/m3-build-launch-failure.log. Preserved pre-M3 target: work/m3-baseline/target-before-final-build/; baseline source hashes and clean-path check: work/m3-baseline/. Current Surefire reports: target/surefire-reports/.

Smoke: work/m3-smoke/commands.json (three exact Java argument arrays/exits/stdout/stderr), help.json, happy.json, all.json, verify-release.log, package-check.json (15 exact queries/rows plus JAR/license hash), runtime-integrity.json and process-check.json. Demo subdirectories retain fictional DBs and per-child outputs/gap inspections.

Static: work/m3-static/summary.json, powershell-parse.json, extracted ci-step-1.ps1/ci-step-2.ps1 and repository-file-hashes.json; launcher work/m3-static-check.log. The repository file snapshot is a point-in-time receipt; EVIDENCE is amended afterward with observed results.

Attribution: docs/dependency-inventory.json, docs/resolved-dependencies.json, docs/vendor-source-manifest.json, docs/ci-provenance.json, docs/third-party-licenses/ and THIRD_PARTY_NOTICES.md. Inventory logs: work/m3-inventory-prebuild.log and work/m3-inventory-final.log. Maven archive/wrapper pins remain unchanged. Text attributes preserve upstream license bytes across future checkouts.

### Final unexecuted checks and limits

Prepared .github/workflows/ci.yml uses immutable official actions, Java 17, standard Windows/Linux runners, a public-only guard, read-only checkout credentials, one matrix job at a time, bounded steps and asserting offline fictional demos. Initial CI dependency resolution needs network. It has not been hosted, published, deployed or triggered; no billing/paid/model call occurred. Runner images/Java 17 patch are not immutable; YAML/PowerShell syntax checks are not hosted-platform execution.

POSIX quickstart and hosted Windows/Linux CI remain unexecuted. Only a Windows WSL launcher was discovered, so no Linux/WSL installation or POSIX execution was attempted. No PGP verification, whole reproducible-build comparison, disk/power-loss injection, external provider/model behavior, distributed ownership, benchmark or production security/durability guarantee was tested.

Build attribution covers actual available artifact bytes and declared/embedded licenses; cache candidates are a superset. Exact plugin load order/mapping was not emitted, pinned clean-plugin transitives are unavailable, and external hosted-action implementation/runner/JDK/Python distributions are outside the project snapshot. Those coverage limits are retained rather than described as a complete global license audit.

Tool contracts/classifications remain trusted assertions; interruption remains cooperative; cancellation cannot undo effects; Agent.next remains synchronous trusted code. All prior M1/M2 limitations remain. Final M3 is delivered for main whole-repository independent acceptance; all owned heavy processes exited and named lease release is requested.
