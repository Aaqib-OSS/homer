# Workaround registry

An upstream workaround is code that exists only because a dependency, a base image or a tool has a
bug or lacks a feature. Each one gets a row here with a `W-NNN` id, and the code that carries it
cites that id in a comment ([ADR-0117](adr/0117-code-says-what-adrs-say-why.md)). This table is
the only place an upstream issue key or URL lives.

The columns:

- **ID**: `W-` and three digits. The next free number; an id is unique and never reused.
- **Upstream**: the upstream issue key or URL.
- **Source**: the version the workaround depends on. `gradle:<key>` is a key in `[versions]` of
  `app/gradle/libs.versions.toml` (a rich version by its `strictly`, else its `require`);
  `npm:<package>` a dependency in `console/package.json`; `pip:<package>` a pin in
  `clients/python/requirements.lock`; `image:<name>` the tag of a `FROM <name>:<tag>@sha256:…` line
  in a Dockerfile; `ci:<ENV_NAME>` an `ENV_NAME: <version>` value in `.github/workflows/`.
- **Version**: the exact version the workaround was verified against.
- **Exit condition**: what must ship upstream before the workaround can go.
- **Where**: the repository paths of the code that carries it, comma-separated. Each one cites the id.

The required `decision-records` check fails when a source's current version differs from
**Version**. An upgrade therefore re-checks the exit condition: the same change either updates the
row or removes the workaround. A removed workaround takes its row with it.

| ID | Upstream | Source | Version | Exit condition | Where |
|---|---|---|---|---|---|
| W-001 | [KTOR-9621](https://youtrack.jetbrains.com/issue/KTOR-9621) | `gradle:ktor` | 3.5.0 | A Ktor release runs rate limiting after authentication again, or the per-caller limiter of [ADR-0014](adr/0014-per-caller-rate-limiting.md) is reworked to key requests before authentication. W-002 goes with this row | `app/gradle/libs.versions.toml` |
| W-002 | [KTOR-9621](https://youtrack.jetbrains.com/issue/KTOR-9621) | `gradle:mcpKotlinSdk` | 0.14.0 | Goes with W-001: an MCP Kotlin SDK release on a Ktor release Homer can take (0.15.0 needs Ktor 3.5.1) | `app/gradle/libs.versions.toml` |
| W-003 | [okhttp 5.5.0 POM](https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/5.5.0/okhttp-5.5.0.pom) | `gradle:okhttp` | 5.4.0 | Square confirms the lysine.dev / github.com/lysine-dev project metadata that okhttp 5.5.0 and later name, or ships a release under its own | `app/gradle/libs.versions.toml`, `app/app/build.gradle.kts` |
| W-004 | [okio 3.18.1 POM](https://repo1.maven.org/maven2/com/squareup/okio/okio/3.18.1/okio-3.18.1.pom) | `gradle:okio` | 3.17.0 | Square confirms the github.com/lysine-dev project metadata that okio 3.18.1 and later name, or ships a release under its own | `app/gradle/libs.versions.toml`, `app/app/build.gradle.kts` |
| W-005 | [langfuse-java](https://github.com/langfuse/langfuse-java) | `gradle:langfuse` | 0.3.0 | A langfuse-java release declares `junit-jupiter-api` in test scope instead of compile scope | `app/app/build.gradle.kts` |
| W-006 | [Ktor](https://github.com/ktorio/ktor) | `gradle:ktor` | 3.5.0 | Ktor resolves Netty 4.2.17 or later on its own: 4.2.13, which Ktor 3.5.0 resolves, carries high-severity advisories that 4.2.17 fixes (3.5.1 resolves 4.2.15). The Netty BOM goes only when W-011 is gone too | `app/app/build.gradle.kts` |
| W-007 | [langfuse-java](https://github.com/langfuse/langfuse-java) | `gradle:langfuse` | 0.3.0 | langfuse-java resolves `com.fasterxml.jackson.core:jackson-databind` 2.22.3 or later on its own (0.3.0 resolves 2.18.6). The constraint goes only when W-012 is gone too | `app/app/build.gradle.kts` |
| W-008 | [json-schema-validator](https://github.com/networknt/json-schema-validator) | `gradle:jsonSchemaValidator` | 3.0.8 | json-schema-validator (3.2.1 under 3.0.8) resolves `tools.jackson.core:jackson-databind` 3.2.3 or later on its own | `app/app/build.gradle.kts` |
| W-009 | [Kubernetes client-java](https://github.com/kubernetes-client/java) | `gradle:kubernetesClient` | 27.0.0 | client-java (bcpkix 1.84 under 27.0.0) resolves BouncyCastle `bcpkix`, `bcutil` and `bcprov` 1.86 or later on its own | `app/app/build.gradle.kts` |
| W-010 | [AWS SDK for Java v2](https://github.com/aws/aws-sdk-java-v2) | `gradle:kubernetesClient` | 27.0.0 | The AWS SDK that client-java pulls (2.46.18 under 27.0.0, on httpclient5 5.6.1 and httpcore5 5.4.3) resolves httpclient5 5.6.4 and httpcore5 / httpcore5-h2 5.4.4 or later on its own | `app/app/build.gradle.kts` |
| W-011 | [AWS SDK for Java v2](https://github.com/aws/aws-sdk-java-v2) | `gradle:kubernetesClient` | 27.0.0 | The AWS SDK `netty-nio-client` that client-java pulls resolves a Netty release with the fixes that 4.2.17 carries, on its own (client-java 27.0.0 brings SDK 2.46.18 on Netty 4.1.135). The Netty BOM goes only when W-006 is gone too | `app/app/build.gradle.kts` |
| W-012 | [Kubernetes client-java](https://github.com/kubernetes-client/java) | `gradle:kubernetesClient` | 27.0.0 | client-java resolves `com.fasterxml.jackson.core:jackson-databind` 2.22.3 or later on its own (27.0.0 resolves 2.22.0). The constraint goes only when W-007 is gone too | `app/app/build.gradle.kts` |
| W-013 | CVE-2026-53914 | `gradle:ktor` | 3.5.0 | Ktor resolves a kotlin-reflect release with the CVE-2026-53914 fix on its own (3.5.0 resolves 2.3.21). The constraint goes only when W-014 is gone too | `app/app/build.gradle.kts` |
| W-014 | CVE-2026-53914 | `gradle:exposed` | 1.5.0 | Exposed resolves a kotlin-reflect release with the CVE-2026-53914 fix on its own (1.5.0 resolves 2.3.20). The constraint goes only when W-013 is gone too | `app/app/build.gradle.kts` |
| W-015 | [detekt](https://github.com/detekt/detekt) | `gradle:detekt` | 2.0.0-alpha.6 | detekt 2.0.0 stable ships, on the K2 Analysis API that Kotlin 2.4 needs ([ADR-0082](adr/0082-quality-reports-report-first.md)) | `app/gradle/libs.versions.toml` |
