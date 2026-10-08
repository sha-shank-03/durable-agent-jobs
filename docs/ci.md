# Prepared CI

.github/workflows/ci.yml is a prepared Java 17 matrix on standard ubuntu-24.04 and windows-2022 runners. It runs only for public repositories, uses read-only contents permission and disables persisted checkout credentials. No secrets, paid model calls, deploy, release, package publication, cache upload or artifact upload is configured. GitHub documents standard hosted runner usage as free for public repositories; the public-only guard prevents private-repository minute consumption. [GitHub billing source](https://docs.github.com/en/billing/concepts/product-billing/github-actions) read 2026-10-08.

The immutable action pins were resolved from official v5.0.0 tag refs on 2026-10-08:

| Action | Commit |
| --- | --- |
| actions/checkout | 08c6903cd8c0fde910a37f88322edcfb5dd907a8 |
| actions/setup-java | dded0888837ed1f317902acf8a20df0ad188d165 |

Exact API sources, retrieval times and action MIT license digests are in ci-provenance.json. [Checkout source](https://github.com/actions/checkout/blob/08c6903cd8c0fde910a37f88322edcfb5dd907a8/README.md) and [Java setup source](https://github.com/actions/setup-java/blob/dded0888837ed1f317902acf8a20df0ad188d165/README.md) were read. These v5 actions use Node 24 and require supported modern runners. Runner images and the current Java 17 patch are not immutable; this workflow does not claim a hermetic environment.

The matrix allows one job at a time, with a 15-minute job budget, 10-minute resolution/test/package step and 3-minute mock smoke step. Maven is capped at 512 MiB heap/256 MiB metaspace; one Surefire fork uses 256/128 MiB. Packaged smoke JVMs and their supervised children use 256/128 MiB. Smoke commands run sequentially.

A fresh checkout resolves pinned Maven/dependencies over the network, then tests/packages. Windows uses the checksum-verifying bootstrap and mvnw.cmd; Linux uses sh ./mvnw. The happy and all packaged demos run offline with fictional inputs and assert actual child JVM halt/recovery, exhaustion, cancellation/expiry, timeout and locking. Python standard library then verifies reports, 15 independent read-only SQLite queries and exact shipped license/schema/service bytes. PowerShell/Python are provided by the standard runner images; no extra plugin/dependency is added.

Locally, YAML parsing and policy assertions plus PowerShell block parsing validate preparation only. The final local Windows build/smoke has executed. The repository has not been published and no hosted CI or POSIX runtime has executed; a green local result is not a green hosted matrix. See EVIDENCE.md for actual commands and receipts.
