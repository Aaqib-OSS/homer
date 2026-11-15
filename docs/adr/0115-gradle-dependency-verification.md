---
status: accepted
date: 2026-11-15
deciders: Lukáš Vlk
consulted: ADR-0047, ADR-0084, ADR-0086, ADR-0088
---

# ADR-0115: Gradle verifies every dependency against committed checksums, and signatures where a key is trusted

## Context and Problem Statement

The build resolves several hundred artifacts from Maven Central and the Gradle Plugin Portal: the
plugins, the libraries, and the dependency-check, detekt, Kover and PIT tooling. TLS proves only
which host served them, not that the bytes are the ones the maintainers published. A compromised
repository or a swapped artifact would run inside every build — CI included — with the build's
credentials. The wrapper is verified by `distributionSha256Sum` (#228); the dependencies were not.
The questions are which mechanism, how strict, and what the gate costs per bump.

## Decision Drivers

- A tampered or republished artifact fails the build instead of running (CWE-494, CWE-345).
- Verification must work on a runner that reaches no key server: CI and the Docker builds resolve
  keyless from Maven Central (ADR-0084).
- The gate must not be weakenable by accident — no lenient mode, no per-artifact exemption, no
  metadata rewritten by the build that downloads the artifact.
- One documented workflow per bump, not a discovery process (Renovate included).
- Do not restate ADR-0088's scope: that judges *known vulnerabilities* in what we ship; this judges
  the *integrity* of what we build with.

## Considered Options

- **Mechanism A: Gradle dependency verification** — `verification-metadata.xml` with PGP + SHA-256,
  plus a committed public keyring.
- **Mechanism B: a checksum manifest checked in CI** — a script comparing the resolved artifacts
  against a committed list.
- **Mechanism C: an internal proxy with its own integrity gate** — Artifactory as the only source.
- **Strictness 1: strict** — Gradle's default; a mismatch fails resolution.
- **Strictness 2: lenient first**, to measure the churn, then flip to strict.

## Decision Outcome

Chosen: **A** and **1**, in both Gradle builds.

`app/gradle/verification-metadata.xml` and `app/buildSrc/gradle/verification-metadata.xml` set
`verify-metadata` and `verify-signatures` true. Every artifact is checked against a SHA-256 entry;
a signature is verified only where the metadata also records a `<pgp>` entry for the artifact, and
such an entry trusts a key by **full fingerprint** in `<trusted-key>`, restricted to the group the
key signs for. Most of the graph is checksum-only today: the keys of 422 of the main build's 583
artifacts are served by no key server Gradle queries, so their entries carry the reason "A key
couldn't be downloaded" next to the checksum — a downgrade this ADR records as a known gap, not
one it hides (closing it means fetching those keys by id, one at a time). The public keys live in
the committed `gradle/verification-keyring.keys`; the binary `.gpg` variant is not committed (Gradle
reads either, and one artifact of truth is enough). Verification happens at dependency resolution,
so no task has to be invoked for it to apply — a build that resolves a mismatching artifact fails
before anything runs it.

**Scope: both builds.** `buildSrc` is a separate build (`app/buildSrc/settings.gradle.kts`) that CI
checks on its own, and it resolves the convention plugins' own dependencies, so it carries its own
metadata and keyring. Verification is keyed by `group:module:version`, so it applies whether the
artifact came from Maven Central, the Plugin Portal or the optional Artifactory proxy
(`gradle/artifactory.settings.gradle.kts`) — the proxy changes who serves an artifact, not what it
must be.

**Nothing is exempted.** The metadata holds no `<ignored-key>`: an ignored key downgrades every
artifact that key signs to a checksum check, which is the gap the decision exists to close. The
checksum-only entries above are the same downgrade held in the open, with its reason recorded —
the way out of one is the way out of the other: fetch the key by id from a public key server and
commit it in the keyring, where it is trusted like any other key, never exempt it.
`DependencyVerificationGateTest` fails on an ignored key, on a key trusted without a group
restriction, on a pattern that matches every group, and on a keyring holding private material.

**The Docker builds need no change.** Both Dockerfiles `COPY app/gradle` wholesale, so the metadata
and the keyring travel with the build context and the in-image resolution is verified too
(`app/Dockerfile:13,17`, `app/e2e/Dockerfile:11,13`). Never add `--dependency-verification` there:
strict is the default, and an explicit flag is one more thing to get wrong.

**The bump workflow** is documented in `app/CLAUDE.md` § Build commands: regenerate with
`--write-verification-metadata pgp,sha256 --export-keys` over the tasks CI runs, **in the same
commit** as the bump. Renovate cannot do it for us — hosted Renovate rejects a `postUpgradeTasks`
command that is not allow-listed — so a Renovate PR is regenerated by hand and the result pushed to
its branch. The gate test fails when the version catalog moves ahead of the metadata, which is what
a bump without regeneration looks like.

**Why not the others:**

- **B**: a hand-rolled manifest is a second description of Gradle's resolution (variants, BOMs,
  plugin markers) to keep in step, and it would check no signature at all.
- **C**: it verifies the proxy's cache rather than the artifact, and it makes the proxy mandatory —
  CI resolves from Maven Central by design (ADR-0084).
- **Strictness 2**: lenient mode logs a failure and continues, so measuring the churn leniently is
  the same information a strict run reports as failures — one local run, and nothing left in the
  repository to weaken later.

### Consequences

- Good: a swapped, truncated or republished artifact fails resolution in every build — local, CI and
  both Docker images.
- Good: the keyring is public material, so a cold keyless runner verifies with no key-server access.
- Good: a bump that forgets the metadata fails the gate test, not the next person's build.
- Bad: every dependency or plugin bump touches two metadata files, and a Renovate PR needs a by-hand
  regeneration before it can merge.
- Bad: an artifact with no published signature is only as trustworthy as the checksum list it came
  with; the `<sha256>` entry records that list, it does not authenticate it.
- Bad: an upstream key rotation fails the build until the new key is fetched and committed — the
  intended failure, but it lands on whoever bumps first.
- Bad: the metadata is large — 620 artifacts and 187 public keys across the two files, ~2 900 lines
  — and its diff is noise in a dependency PR.
