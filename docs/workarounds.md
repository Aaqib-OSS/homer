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
