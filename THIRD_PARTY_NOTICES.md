# Dependency attribution

Project code: Apache-2.0; full text in LICENSE. Initial runtime/test source retrieval: 2026-10-07; offline build-tool inventory and CI provenance: 2026-10-08. docs/resolved-dependencies.json records the actual Maven-resolved test classpath, POM licenses and JAR digests (three runtime and nine test artifacts). docs/dependency-inventory.json extends attribution to cached plugin/provider artifacts and the installed Maven distribution, recording exact JAR hashes, POM/parent evidence and retained upstream license/notice bytes. It does not label every cached artifact as loaded or redistributed.

## Packaged runtime

| Artifact | Version | License/source |
| --- | --- | --- |
| org.xerial:sqlite-jdbc | 3.53.4.0 | Apache-2.0; legacy BSD-2-Clause attribution in LICENSE.zentus; bundled SQLite public domain. https://github.com/xerial/sqlite-jdbc/releases/tag/3.53.4.0 |
| com.google.code.gson:gson | 2.14.0 | Apache-2.0. https://github.com/google/gson/releases/tag/gson-parent-2.14.0 |
| com.google.errorprone:error_prone_annotations | 2.48.0 | Apache-2.0, Gson transitive. https://github.com/google/error-prone/tree/v2.48.0 |

Full upstream runtime license/notice files are in licenses/ and configured for META-INF/licenses/ in the shaded JAR. SQLite JDBC's NOTICE references David Crawshaw's legacy contribution and NestedVM. SQLite's statement: https://www.sqlite.org/copyright.html. The SQLite JDBC SLF4J dependency is optional and GraalVM dependency provided; neither is deliberately introduced into the runtime.

## Build/test tools

Maven 3.10.0 and Maven Wrapper 3.3.4: Apache-2.0, The Apache Software Foundation. Wrapper source references/digests are in docs/vendor-source-manifest.json; only the upstream version placeholder was replaced. Its NOTICE is retained in licenses/maven-wrapper-NOTICE.txt. https://maven.apache.org/tools/wrapper/

Pinned Maven compiler/Surefire/Shade/clean/resources/jar plugins: 3.14.1/3.5.5/3.6.1/3.5.0/3.3.1/3.4.2, Apache-2.0. Plugins are build tooling, not runtime libraries. The compiler/resources/Surefire/jar/Shade artifacts and transitives are covered by the local inventory. The pinned clean plugin is absent from this cache and has not executed; its future resolved transitives are not covered. The final offline build starts from fresh generated output without resolving that plugin.

JUnit BOM/Jupiter/Platform 6.1.3: EPL-2.0, test only. https://raw.githubusercontent.com/junit-team/junit-framework/r6.1.3/LICENSE.md

JUnit test transitives: opentest4j 1.3.0, apiguardian-api 1.1.2 and jspecify 1.0.0, Apache-2.0 per actual Maven Central POM reads. JDK 17 is a prerequisite and is not redistributed.

No upstream adoption/test/security claims are implied by these dependencies. Shading preserves JDBC services and configures Apache notice handling; the built JAR still requires actual inspection.

## Actual build-tool coverage

The offline inventory scans every cached JAR in this repository's work/m2 and every JAR in the checksum-verified installed Maven 3.10.0 distribution. It retains available embedded LICENSE/NOTICE/COPYING files and the distribution's complete top-level and per-library license texts under docs/third-party-licenses, deduplicated by SHA-256 without altering bytes. POM licenses resolve through cached parents; evidence paths/digests are recorded. Actual plugin realm contexts, when present in the final build log, distinguish loaded plugin artifacts from the broader cached provider/build candidate set. Maven core-provided imports remain covered by the distribution inventory rather than being silently omitted.

This includes Apache-2.0 Maven/Plexus/Commons components, BSD-style ASM/QDox/JDOM components, MIT SLF4J, EPL JUnit components and other exact declarations recorded per artifact. Inspect the full upstream text and POM evidence for each version; this summary is not a replacement for those notices. Build/test artifacts and their texts are repository attribution, not runtime JAR dependencies. The seven existing project/runtime license/notice files remain in the shaded output, with JDBC services and upstream SQLite notices retained.

Two cached POM declarations remain incomplete. javax.inject:javax.inject:1 has no declaration in its cached POM, but its JAR is byte-identical to Maven's bundled artifact and Maven supplies its Apache-2.0 license. org.jdom:jdom2:2.0.6.1 has no POM declaration, but its exact JAR includes the retained JDOM BSD-style text with an additional naming condition; it is not relabeled as standard BSD-3-Clause. These supplemental observations and unresolved POM metadata are explicit in the inventory.

The inventory is suitable local artifact attribution, not a claim to audit all bundled implementation code, native-toolchain inputs, global settings, upstream source trees or future dependency resolution. Only locally available actual artifacts were read; no additional binary was downloaded for finalization. The clean plugin, hosted runner/action-bundled implementation graph and installed JDK/Python distributions are outside the resolved project artifact snapshot. No legal/security certification is claimed.

## Prepared CI and external prerequisites

Prepared CI references actions/checkout v5.0.0 at 08c6903cd8c0fde910a37f88322edcfb5dd907a8 and actions/setup-java v5.0.0 at dded0888837ed1f317902acf8a20df0ad188d165. Both official action licenses are MIT; immutable refs, source URLs, retrieval times and exact retained license digests are in docs/ci-provenance.json. The workflow does not vendor or redistribute their bundled JavaScript implementations. No hosted action execution has occurred.

JDK 17, Python 3.10+ for optional standard-library verifiers, a POSIX shell/downloader/unzip for first POSIX wrapper setup, and hosted runner images are operator/CI prerequisites, not redistributed project artifacts. Their versions/platform licenses must be considered by their operators. No model SDK, API credentials or paid-service dependency is included.
