# Validation record

Date: 2026-10-31 (Windows 11, Rust 1.99.1, Slint 1.18.1)

## Automated checks

`./build.ps1` passed: `cargo fmt --check`, `cargo clippy --workspace --all-targets -D warnings`,
**51 deterministic tests** (zero failures), release build and the packaged `--smoke` check
(`{"providers":["modrinth","hangar"],"success":true}`). Output: `dist/SmartPluginAssistant/SmartPluginAssistant.exe`
(single 23 MB executable, no runtime installation).

Coverage:

- API contracts: target/loader validation, compatibility rules, pagination, dependency kinds, URL host checks.
- Storage: atomic JSON replacement, process lock, instance persistence/unregister-only, corrupt index
  preservation, Sol `instances.json` compatibility, unsafe/reserved file names, symlink rejection (Unix).
- Downloads: truncated bodies, checksum mismatch, HTML masquerading as a JAR, redirects, temp cleanup.
- Transactions: install/update with backups, disabled-state preservation, stale-scan rejection,
  rollback when metadata persistence fails (install, toggle, remove), import conflicts, recoverable removal.
- Identity: hash-bound inventory, renamed-file identity, exact-hash linking.
- Planning: required/optional dependency order, cycles, conflicting pinned versions, declared
  incompatibility, disabled required dependency, missing dependency, manual website dependencies,
  unverified confidence confirmation, verified-but-incompatible rejection.
- Updates: release-ID/time comparison, downgrade prevention, per-item failure reports.
- Export: plugin-only ZIP contents and manifest, shell quoting, pinned script lines, manual-upload notes.
- Environment cache: no invented defaults, reuse, stale fallback, restart persistence.
- Image cache: coalescing, disk reuse across instances, cleanup fence during in-flight downloads,
  owned-file-only deletion, invalid content, disabled state, LRU eviction; bounded decoding of
  PNG/GIF/BMP/WebP and memory-cache eviction.
- Providers: Modrinth search/catalog/releases/identity/URLs; Hangar search/paging/platforms,
  confidence, channels, sha256, external downloads, manual dependencies, exact releases, URLs.
- Application: feed deduplication/termination, settings migration, catalog key/placeholder parity,
  UI/source key existence, and headless controller flows (create instance, default browse, failed
  page retry, deduplicated continuation, exhaustion, release inspection, plan, URL resolution,
  confirmed unregister keeping files, second process lock).

## Live UI probe

`--snapshot=<dir>` renders the real Slint window offscreen (software renderer) and drives it against
public APIs in an isolated data directory. Passed with both the debug build and the packaged release:

| Step | Result |
| --- | --- |
| Modrinth environment catalog | 10 plugin loaders, 103 release Minecraft versions |
| Create instance via dialog | Paper default, saved and selected |
| Modrinth default browse | 20 projects, artwork loaded, 4 releases for the first project |
| Hangar default browse | 20 projects, continuation 20 -> 40 |
| Hangar install (ViaBackwards) | Plan included required dependency ViaVersion; both downloaded, sha256 + JAR verified, installed |
| Update check | 2 linked plugins, 0 updates, 0 failures |
| Settings | Light theme, violet accent and English applied live |

Screenshots (zh-TW dark, en light, dialogs, discovery for both sources, plan, installed library)
were inspected. Re-run:

```powershell
cargo build -p spa-desktop
.\target\debug\SmartPluginAssistant.exe --data-dir=target\ui-check\data --snapshot=target\ui-check\shots
```

Use a fresh data directory per run. The probe downloads public plugin JARs but never executes them.

The native window (winit + FemtoVG) was launched for 6 seconds with an isolated data directory and
stayed up without errors.

## Scope limits

- Only Windows was tested locally. Linux/macOS CI jobs are configured but have not run yet.
- Interactive mouse/keyboard behaviour of the native window was not exercised by automation;
  the offscreen probe calls the same callbacks the widgets invoke.
- No Minecraft server was started; runtime plugin behaviour is the administrator's responsibility.
- Multi-plugin installs are sequences of individual transactions; completed dependencies remain if a
  later item fails. Abrupt power loss mid-replacement requires manual recovery from `.spa-backups/`.
- No ZIP import wizard, private/premium credential downloads, runtime provider loading or self-update.
