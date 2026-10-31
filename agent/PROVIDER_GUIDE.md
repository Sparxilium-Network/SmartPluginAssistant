# Provider development guide

Providers are ordinary crates that depend only on `spa-api` (plus their own parsing crates).
They are compiled into the application; there is no runtime plugin loading.

## Add a provider

1. Create `crates/providers/<name>` with `spa-api`, `serde_json`, `url`, `log` dependencies.
2. Implement `spa_api::PluginProvider` (see below). Accept a base URL and an
   `Arc<dyn HttpTransport>` in a constructor so tests can inject fixtures; `Default` uses the
   public endpoint with `UreqTransport`.
3. Add the crate to the workspace and to `spa-app/Cargo.toml`, then register it in
   `spa-app/src/context.rs::builtin_providers`. No other host code changes; the host never
   branches on provider IDs. Duplicate IDs are rejected at startup.

## Contract

- `info`: stable ID matching `[a-z][a-z0-9-]{1,40}`, display name, homepage.
- `environments`: optional plugin-loader and release game-version catalog, newest version first.
  Return `Ok(None)` when unsupported. The host merges and caches catalogs for six hours.
- `search`: zero-based pages; return `Err` on failure, never an empty success.
  An unsupported target environment may return an empty, exhausted page.
- `project`: canonical stable ID, plain-text description, official page, `Access`, optional HTTPS icon.
- `releases`: newest first; never fall back to an incompatible release. Include immutable release ID,
  publication time, safe JAR filename, pinned download URL (or `None` for website-only files), every
  published checksum (`sha512`/`sha256`/`sha1`), declared game versions/loaders and dependencies.
- `release`: exact release only (by ID, or by a name from an official URL if the API supports it).
- `resolve`: exact official HTTPS host (`spa_api::http::has_host`) and path shapes; no substring checks.
- `identify_sha512`: optional exact file identity; return the file whose hash matched.

Confidence: `Verified` only when release metadata declares the target Minecraft version and one of
the target's effective loaders; `Declared`/`Unknown` require user confirmation. This describes
metadata, not runtime testing. Do not invent a stable channel.

Dependencies: required, optional or incompatible. Website-only dependencies use
`Dependency::named(None, None, name, external_url, required, false)`; the planner lists them for
manual installation and requires confirmation. Required dependencies are never dropped.

Providers are called off the UI thread and may be shared across threads. They never write files.

## Tests

Use an in-memory `HttpTransport` fixture (see the provider crates' tests) for JSON parsing, paging,
URL recognition and exact-release checks. Host-level flows use `spa-core/tests/common` (loopback HTTP
server, JAR builder, fake provider) and `spa-app/tests/controller.rs` (headless presenter).
