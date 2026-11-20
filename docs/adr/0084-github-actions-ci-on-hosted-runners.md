---
status: accepted
date: 2026-10-14
deciders: Lukáš Vlk
amended: 2026-11-15, 2026-11-20
---

# ADR-0084: GitHub Actions CI on GitHub-hosted runners, without Artifactory

## Context and Problem Statement

Homer is moving from a private upstream repository to a public GitHub
repository. The upstream pipeline runs on the team's own runners and resolves Gradle through an in-cluster
Artifactory proxy, added because Maven Central rate-limited (`429`) the
office's shared egress IP. A public repository takes pull requests from forks,
and a fork's workflow runs code that nobody has reviewed. The per-commit checks
need a home that is safe for that code and that does not reintroduce the
`429`.

## Decision Drivers

- Fork pull requests must get the full per-commit checks without any secret:
  GitHub withholds secrets from them, and any credential they could reach is
  exposed to code nobody has reviewed.
- Unreviewed fork code must not run on the team's own infrastructure or network.
- Maven Central must not rate-limit CI again.
- The checks and their gate semantics stay those of the upstream pipeline
  and ADR-0082.

## Considered Options

- GitHub-hosted runners, resolving from Maven Central with the Gradle cache in
  GitHub's Actions cache.
- Self-hosted runners on the team's infrastructure, keeping Artifactory.
- GitHub-hosted runners that trigger the existing upstream pipeline and wait
  for its result.

## Decision Outcome

Chosen option: **GitHub-hosted runners without Artifactory**
(`.github/workflows/ci.yml`). Every job runs on a fresh VM, so fork code never
reaches the team's network. No job needs a secret, so fork pull requests run
the same checks as maintainers' branches. The `429` came from one egress IP
fetching an uncached dependency graph on every job. Hosted runners have
different IPs, and `setup-gradle` restores the Gradle user home from the
Actions cache. Maven Central is therefore contacted only when dependencies
change. `setup-gradle` writes that cache only from the default branch, so a
pull request cannot seed entries that `main` later reuses. This matches the
Artifactory build cache's protected-refs-only rule.

The Artifactory gate stays in the Gradle settings for builds that set
`ARTIFACTORY_URL`, `ARTIFACTORY_USER` and `ARTIFACTORY_TOKEN`, such as the
upstream pipeline during the migration. Its origin moved from a literal host into
`ARTIFACTORY_URL`, so the public tree names no internal host. Anything but a
bare `https://` origin (another scheme, a path, embedded credentials) fails the
build (`ArtifactoryTransportTest`).

Until development moves to GitHub, GitHub is a one-way mirror of the upstream `main`,
fed from a single initial commit by a CI job; see
[ADR-0085](0085-one-way-github-mirror-during-migration.md).

Self-hosted runners were rejected because they would run fork code inside the
team's network. Triggering the upstream pipeline was rejected for the same reason, and it
would also need a credential that fork pull requests do not receive.

### Consequences

- Good: fork pull requests are checked automatically; nothing they run can
  read a secret or reach internal hosts.
- Good: no runners or proxy to operate for the public repository.
- Bad: the upstream merge-request widgets (JUnit, coverage, Code Quality) have
  no GitHub equivalent here. Reports are workflow artifacts, and the quality gate
  marks warn-mode findings with an annotation instead of "passed with warnings".
- Bad: if Maven Central throttles GitHub's runner IPs, the fallback is a mirror
  in the settings scripts, not a new proxy.
- Neutral: E2E runs in parallel with the unit-test jobs instead of after them.
  Hosted minutes are free for a public repository, and feedback comes sooner.
- Neutral: image publishing (`release.yml`, GHCR on a semver tag), Pages
  (`pages.yml`) and the scheduled checks (`nightly.yml`: dependency check,
  mutation runs) are ported. While the repository is private, Pages is skipped,
  as the organization's plan does not serve it, and the schedule does not run,
  to keep Actions minutes for per-commit CI. Releases publish only from a
  protected ref (`github.ref_protected`), as the upstream pipeline requires a
  protected tag, so
  the tag and `main` rulesets are what allow a release once the repository is
  public. The Slack release announcement is not ported.

## Amendment (2026-11-15): the build history the backend guide used to carry

`app/CLAUDE.md` § Build commands carried the history behind the build it
describes. It is recorded here, so the guide keeps the commands and points at
[`docs/quality-checks.md`](../../docs/quality-checks.md) for the report commands.

**CI resolves from Maven Central, with the Gradle cache in GitHub's Actions
cache, and sets no Artifactory variable** — the decision above. The Artifactory
proxy and its remote build cache remain an optional path for other environments,
on only when all three of `ARTIFACTORY_URL` (a bare `https://` origin; a path or
embedded credentials in it fail the build), `ARTIFACTORY_USER` and
`ARTIFACTORY_TOKEN` are set. Inside a Docker build the three are BuildKit secrets,
never an `ARG`/`ENV` (root §12 rule 7), and the e2e runner keeps them as run-time
environment; `ArtifactorySecretExposureTest` guards both halves. The remote cache
is pushed only when `CI_COMMIT_REF_PROTECTED` is `true`, matching the
protected-refs-only rule recorded above. The operator-facing half is
[`DOCKER.md`](../../DOCKER.md) § Artifactory build secrets.

**A cold or `--rerun-tasks` build that dies in codegen** with
`OutOfMemoryError: GC overhead limit exceeded` is the Kotlin daemon's heap, not
the test the failure names: check the live daemon's `-Xmx`
(`ps | grep KotlinCompileDaemon`) before blaming anything else, because a daemon
already running with a **larger** heap is reused and its settings come from
[`gradle.properties`](../../app/gradle.properties) (`kotlin.daemon.jvmargs`). detekt
left the `homer-app-tests` build for its own job for the same reason — its
Analysis API loads into the Gradle daemon and that job ran out of memory
(ADR-0082).

## Amendment (2026-11-20): the Artifactory remote build cache is read-only

The remote cache pushed only when `CI_COMMIT_REF_PROTECTED` was `true`. That is a
GitLab CI variable, and the upstream pipeline that set it ended with the mirror
([ADR-0085](0085-one-way-github-mirror-during-migration.md)). The flag is gone, so
the remote cache is read-only in every environment: Gradle's default for a remote
cache. GitHub CI never turns it on, since it sets no Artifactory variable; its
Gradle cache is `setup-gradle`'s, which only the default branch writes.

## Amendment (2026-11-20): the optional Artifactory build path is retired

The decision above kept the Artifactory gate for builds that set `ARTIFACTORY_URL`,
`ARTIFACTORY_USER` and `ARTIFACTORY_TOKEN`, "such as the upstream pipeline during the migration".
The migration is over, and no supported build sets the three. The path is removed:

- the plugin and dependency repository gates in both settings scripts and
  `gradle/artifactory.settings.gradle.kts`;
- the remote build cache, read-only since the amendment above, with nothing that writes it;
- the BuildKit secret mounts in `app/Dockerfile` and `app/e2e/Dockerfile`, the `build.secrets` and
  the E2E runner's run-time pass-through in `docker-compose.e2e.yml`;
- `DOCKER.md` § Artifactory build secrets, and the tests that guarded the path
  (`ArtifactoryTransportTest`, `ArtifactorySecretExposureTest`).

The accepted risk of the E2E runner holding the credentials, AR-03 in `SECURITY.md`, goes with the
pass-through; its id is not reused. Every build now resolves from Maven Central and the Gradle
Plugin Portal, as CI already did, and Gradle dependency verification
([ADR-0115](0115-gradle-dependency-verification.md)) checks what they serve. An environment that
meets the `429` again has the Gradle user-home cache as its remedy, as CI does, not a proxy in this
repository.
