---
status: accepted
date: 2026-11-15
deciders: Lukáš Vlk
consulted: ADR-0082, ADR-0086, ADR-0088
---

# ADR-0113: Scan the release images nightly with grype, failing on fixable High and Critical findings

## Context and Problem Statement

OWASP dependency-check judges the backend's jars (ADR-0088) and `npm audit` the console's
dependencies, but nothing judged the rest of what the images ship: the Alpine packages, the JRE,
the Python tree the server image installs, and the global npm tree with the LLM CLIs. The first
scan found High and Critical findings with a fixed version in every image, among them older
`langgraph-checkpoint` releases that the image's Alpine `pip install` resolves while a normal
install takes the newest ones. The questions are which scanner, what it scans, and what fails.

## Decision Drivers

- Judge what a release publishes, before it is published.
- Fail only on what a bump can fix, at the same bar as dependency-check, so that a red run asks
  for an action.
- A scanner runs on every scheduled run with read access to the checkout; it must come from a
  source the project already trusts, pinned by version and checksum.
- Do not leave the nightly red for a backlog that predates the check.

## Considered Options

- **Scanner A: grype**, the release binary pinned by version and SHA-256.
- **Scanner B: trivy**, the binary or `aquasecurity/trivy-action`.
- **Target 1: images built from the checkout** in the nightly job.
- **Target 2: the latest published release image**, by digest.
- **Target 3: the SBOMs the release attests** (ADR-0086).

## Decision Outcome

Chosen: **A** and **1**. Job `image-scan` (Nightly workflow) builds `homer-app`, `homer-runner` and
`homer-console` from the checkout for linux/amd64 and scans each with grype. A High or Critical
finding with a fixed version fails the scan: High and Critical are CVSS 7.0 and above, the
threshold dependency-check fails on (ADR-0088). An unfixed finding is counted in the job summary
but does not fail it. A finding that cannot be fixed and is accepted goes into a `.grype.yaml`
ignore rule with its reason, plus a `SECURITY.md` row, as dependency-check's suppression file does.

The job starts report-first (`continue-on-error`), as ADR-0082 starts a new check in `warn` mode:
its failure warns until the three images are clean. Then `continue-on-error` is removed, and a
finding fails the run. `docs/quality-checks.md` § Open backlog tracks the findings left.

**Why not the others:**

- **B**: trivy's actions and a trivy release were compromised in March 2026
  (GHSA-69fq-xp46-6x23). A scanner from the project behind syft, which already makes the release
  SBOMs (ADR-0086), adds no new vendor to trust.
- **2**: a finding fixed on `main` would keep the run red until the next release, and a problem
  is found only after it is published.
- **3**: an SBOM exists only for a released image, with the same lag as 2.

**Scope.**
- amd64 only: the arm64 images are built from the same Dockerfiles and package sets. A
  vulnerability in an arm64-only package would be missed; accepted for half the runner time.
- `homer-app`'s slim variant (ADR-0094) is not scanned on its own: `homer-app` is built from it,
  so it holds everything the slim image does.

### Consequences

- Good: OS packages, the JRE, the Python tree and the global npm tree are judged every night,
  including what the image's own package resolution picks.
- Good: a red run names a bump that fixes it.
- Bad: findings in Go modules compiled into Alpine's own packages (`glab`, `git-lfs`) count as
  fixable but wait for Alpine to rebuild them; they are ignored with a reason or wait.
- Bad: until the backlog is fixed, the job only warns, so a new finding is visible but fails
  nothing.
- Bad: the job builds three images from scratch every night, with no build cache.
