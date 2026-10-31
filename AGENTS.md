# Smart Plugin Assistant (Rust) development rules

- Read `agent/ARCHITECTURE.md`, `agent/PROGRESS.md` and `agent/MEMORY.md` before changing architecture.
- Rust stable, Slint desktop UI. Bundled providers: **Modrinth** and **Hangar** only.
- Keep the layers: `spa-api` <- providers; `spa-api` <- `spa-core` <- `spa-app` <- `spa-desktop`.
  Providers depend on `spa-api` only. `spa-core` must not import providers or any UI toolkit.
  `spa-app` holds all behaviour and must not import Slint. `spa-desktop` only renders `UiEvent`
  view models and forwards actions; replacing the UI framework must not touch other crates.
- Application code never branches on provider IDs. Providers are registered at compile time in
  `spa-app/src/context.rs::builtin_providers`.
- All user-visible strings come from `spa_app::i18n::tr/trf` (Slint: `Tr.get(Tr.revision, key)`);
  update both `crates/spa-app/lang/*.lang` catalogs. Parity tests enforce keys and placeholders.
- Use `log` for important operations and failures. Do not log credentials.
- Never recursively delete a server directory. Removing an instance only unregisters it.
- Validate paths (`spa_core::paths`), reject symbolic links for managed writes, and stage/verify
  downloads (size, published hashes, JAR structure) before replacement. Staging files use the
  `.spa-` prefix and a `.tmp` suffix so they are never scanned as plugins.
- Persist JSON using atomic replacement (`spa_core::json`); errors must reach the caller.
  Keep JSON field names compatible with Sol (camelCase records).
- Use dedicated file editing tools. Keep tests deterministic with local HTTP fixtures; live
  network checks are opt-in (`--snapshot`).
- Complete and verify one small feature, update `agent/PROGRESS.md`, then commit before the next.
- Do not commit generated outputs (`target/`, `dist/`), credentials or user data.

## Verification

```powershell
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
```

`./build.ps1` (Windows) or `bash ./build.sh` runs all of the above, builds the release
executable and runs the packaged `--smoke` check. Visual checks: see `agent/VALIDATION.md`.
