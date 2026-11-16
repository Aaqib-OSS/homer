# Contributing to Homer

## Getting started

- Backend (Kotlin/Ktor): see [`app/CLAUDE.md`](app/CLAUDE.md) for
  architecture and conventions. Requires JDK 25.
- Console (Next.js): see [`console/CLAUDE.md`](console/CLAUDE.md) for
  architecture and conventions. Requires Node 24.
- Using a coding agent? The `CLAUDE.md` files are its rules whatever the
  harness. The root, `app/`, `console/` and `clients/python/homer_langgraph/`
  ones sit next to an `AGENTS.md` pointer, starting with the root
  [`AGENTS.md`](AGENTS.md), so agents other than Claude Code find them too.
- Questions and help go to
  [GitHub Discussions](https://github.com/livesport/homer/discussions/categories/q-a).
- Looking for something to pick up? See the
  [good first issues](https://github.com/livesport/homer/issues?q=is%3Aissue%20state%3Aopen%20label%3A%22good%20first%20issue%22)
  and [help wanted](https://github.com/livesport/homer/issues?q=is%3Aissue%20state%3Aopen%20label%3A%22help%20wanted%22)
  issues, then follow [Submitting changes](#submitting-changes). Issues
  aren't assigned or reserved, so there's no need to ask first: open a pull
  request when you're ready. The first one that closes the issue and passes
  review is merged.

## Before submitting

Run the relevant verification command and make sure it's green:

| Area touched | Verify |
| --- | --- |
| `app/**` | `JAVA_HOME=<path to a JDK 25> app/gradlew -p app ktlintFormat :app:test :app:checkThirdPartyNotices` |
| `app/buildSrc/**` | `JAVA_HOME=<path to a JDK 25> app/gradlew -p app/buildSrc check` (a separate build — `-p app check` does not cover it) |
| `console/**` | `cd console && npm run lint && npm test && npm run build && npm run licenses:check` |
| `clients/python/**` | `cd clients/python && .venv/bin/python -m pytest` (venv setup: [`clients/python/homer_langgraph/CLAUDE.md`](clients/python/homer_langgraph/CLAUDE.md)) |
| `tools/quality/**` | `cd tools/quality && npm ci && npm test` |
| a `*.sh` file, `.github/workflows/**`, a `Dockerfile` or `.hadolint.yaml` | `node tools/quality/infra-lint.mjs` (shellcheck, actionlint, zizmor and hadolint must be on the `PATH`; CI's versions are in [`docs/quality-checks.md`](docs/quality-checks.md)) |
| `site/**`, any tracked `*.md` file, or a file the site publishes (listed in `site/scripts/sources.mjs`, every ADR) | `cd site && npm ci && npm test && npm run docs:build` (fails on a link to a missing file or to a heading the target page does not have; `npm test` also on a dead relative link or heading anchor in a tracked `*.md` outside `site/pages/` (the site build checks those), or a missing backticked path in a `CLAUDE.md`) |
| `app/schemas/**`, `app/AGENT.md`, `app/SERVER.md`, or `Agent.kt` / `AppConfig.kt` | backend verify (runs the contract tests: `ModelSchemaContractTest`, `SchemaDocTableContractTest`, `SchemaValidationTest`, `AgentMdExamplesTest`, `ServerMdExamplesTest`, `AuthoringPluginBundleTest`) |
| `.github/ISSUE_TEMPLATE/**` or `.github/pull_request_template.md` | `JAVA_HOME=<path to a JDK 25> app/gradlew -p app :app:test --tests 'homer.app.CommunityTemplatesTest'` (the community-template guard; `node tools/quality/changed.mjs` selects no check for a template-only change) |

## Expectations

- Tests are required for new code — see each dev guide's testing
  section for the stack and layout.
- Every pull request runs the quality checks listed in
  [`docs/quality-checks.md`](docs/quality-checks.md); `required` ones
  fail the CI run. `node tools/quality/changed.mjs` runs them locally
  against your change since `origin/main` (uncommitted edits included),
  with only the related tests.
- One logical change per commit; imperative subject, body explains why.
- User-visible changes get a `CHANGELOG.md` entry under `[Unreleased]`.
- Architectural decisions with lasting impact get an ADR under
  [`docs/adr/`](docs/adr/) — see [`docs/adr/README.md`](docs/adr/README.md).

## Submitting changes

Open a pull request on [github.com/livesport/homer](https://github.com/livesport/homer).

1. Open an issue first. A maintainer or triager labels it `accepted` once
   the change belongs in Homer and is welcome there; only those roles can set
   labels.
2. Link the accepted issue from the pull request: `Closes #123` in the
   description, or the pull request's *Development* field. The `PR policy`
   check fails a pull request that links no accepted issue of this
   repository. Labelling the issue does not re-run the check, so re-run it
   afterwards. [ADR-0090](docs/adr/0090-accepted-issue-pull-request-gate.md)
   records why the gate works this way.
3. CI runs on GitHub-hosted runners. A first-time contributor's first run
   waits until a maintainer approves it, as GitHub does by default.
4. Reviews start once every automated check is green. A pull request from
   a member of the `livesport` organization, or from Homer's own factory,
   gets the AI review and a maintainer's review. One from anyone else gets
   a maintainer's review first, and the AI review only after the maintainer
   approves, so no automated review runs on code nobody has read. A new
   push dismisses the approval.
5. A pull request merges once every check, the AI review and a maintainer's
   approval are green on its latest commit and every review conversation is
   resolved. The AI review can block a merge but never approves one. Only
   maintainers merge.

## Releasing

A release is one version-bump commit on a branch, that branch's merge into
`main`, and a tag on the merge. The tag publishes the images and the
documentation site; nothing else is done by hand. This section documents the
procedure as it is performed today — it does not change it.

1. **Bump the version** in one commit, subject `release: <version>`, on a branch
   named `aif/release-<version>` (`aif/release-1.43.0`):
   - `CHANGELOG.md` — insert `## [<version>] - <YYYY-MM-DD>` under
     `[Unreleased]`, so the entries accumulated there become the release's.
   - `docker-compose.yml` — the pinned default of `${HOMER_VERSION:-<version>}`
     on both services, backend and console
     ([ADR-0093](docs/adr/0093-the-default-compose-stack-runs-published-images.md)).
   - `.env.example` — the commented `# HOMER_VERSION=<version>`.
   - `README.md` — the `version=` line of the image-verification snippet.
   - `DOCKER.md` — the release image tags in its examples.
2. **Merge** the branch into `main` through a pull request; every check must be
   green, as for any other change.
3. **Tag** the merge commit with the bare version (`1.43.0`). The tag is
   lightweight, and it is what starts the release:
   [`.github/workflows/release.yml`](.github/workflows/release.yml) triggers on
   a pushed `[0-9]+.[0-9]+.[0-9]+` tag, and only on a protected ref.
4. **`release.yml` publishes**, once its `ci` job — the full CI workflow — has
   passed:
   - **the documentation** — the site is built for the tag and attached to the
     GitHub release as `homer-docs.tar.gz`, and the Pages workflow is started
     ([ADR-0087](docs/adr/0087-versioned-documentation-site.md));
   - **the images** — each is built natively for `linux/amd64` and
     `linux/arm64`, pushed to GHCR by digest, joined into one multi-arch index
     per tag, signed with cosign (keyless) and attested with SLSA provenance and
     a CycloneDX SBOM
     ([ADR-0086](docs/adr/0086-sign-and-attest-release-images.md));
   - **the release page** — `tools/release/notes.sh` writes its notes: the
     version's `CHANGELOG.md` section (`notes.sh changelog`), then the images'
     digests and the `cosign verify` command for each (`notes.sh images`, put
     below the changelog by `notes.sh merge`). A tag whose version has no
     changelog section fails before anything is published.
