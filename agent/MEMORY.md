# Working memory for future sessions

- Repository: `C:\code\SmartPluginAssistantRust`. Sources ported from `C:\code\SmartPluginAssistantSol`
  (latest rules/architecture) and `C:\code\SmartPluginAssistant` (original; Hangar API usage). Both stay unchanged.
- User choices: Slint UI with frontend/backend separated so the UI framework can be swapped; providers
  Modrinth + Hangar only; compile-time provider crates; data in `~/.smartpluginassistant-rust/` with Sol JSON format.
- User prefers small verified features with a Git commit for each.
- All behaviour lives in `spa-app::Controller`; `spa-desktop` only maps `UiEvent`s and wires callbacks.
  Never put logic in `.slint` beyond presentation.
- Strings: `spa-app/lang/{zh-tw,en}.lang`; Slint uses `Tr.get(Tr.revision, key)`. Tests check parity and
  that quoted keys in controller/UI sources exist (`i18n::missing_keys`; file-like literals are ignored).
- Backups: `.spa-backups/<UUID>/` inside the server directory. Staging files: `.spa-*.tmp`.
- Hangar: numeric project/version IDs; platform from loaders; Verified only when the loader list includes
  the platform; website-only dependencies become manual plan items; snapshot-like channels are Beta.
- New-instance default loader is Paper when available (alphabetical first was `bukkit`, which Hangar cannot serve).
- Controller tests: wait for the instance-change `MarketReset` before activating Discover, otherwise two
  feeds race (correctly, but test counts change).
- PowerShell 5.1: do not use `$ErrorActionPreference = "Stop"` around cargo (stderr progress becomes an error).
- rustfmt rewrites `| |` to `||`; the `on!` binding macro in `main.rs` accepts both.
- Verify with `cargo fmt --all -- --check`, `cargo clippy --workspace --all-targets -- -D warnings`,
  `cargo test --workspace` (51 tests), and the `--snapshot` probe for UI changes (inspect PNGs).
