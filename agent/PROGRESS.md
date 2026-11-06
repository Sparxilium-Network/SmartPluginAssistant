# Progress

## Completed (2026-10-31)

1. Workspace scaffold and decisions: Slint UI with a framework-neutral application layer,
   Modrinth + Hangar providers, compile-time provider crates, Sol-compatible JSON in a new data directory.
2. `spa-api`: provider trait, contracts, validation, bounded HTTPS transport.
3. `spa-core`: safe paths, atomic JSON, process lock, instance index, hash-bound inventory, verified
   staged downloads, rollback-safe install/import/toggle/remove/link, dependency planning (with manual
   website dependencies), update reports, ZIP/script export, environment cache, fenced image cache.
   Fixed during porting: staged downloads used a `.jar` suffix and were picked up by rescans; staging
   files now end in `.tmp` and the scanner ignores the `.spa-` prefix.
4. Providers: Modrinth (ported from Sol) and Hangar (new, verified against the live API).
5. `spa-app`: Controller presenter, view models/events, i18n catalogs (zh-tw/en), settings, feeds,
   artwork decoding, headless controller tests.
6. `spa-desktop`: Slint UI (sidebar, library, discovery, dialogs, settings, themes), offscreen
   `--snapshot` probe and `--smoke` packaging check. UI fixes from snapshot review: Paper default
   loader, single-line card descriptions, aligned table columns, light-mode button contrast, Hangar
   snapshot channels treated as pre-releases.
7. Tooling: rustfmt (120 columns), clippy clean, `build.ps1`/`build.sh`, run scripts, three-OS CI.

8. 2026-11-06: replaced JSON persistence with SQLite (`spa.db`) and an explicit data format version
   (`meta.schema_version` / `PRAGMA user_version`), versioned migrations with pre-migration backups,
   refusal of newer formats, integrity check, one-time Sol JSON import; settings dialog shows the version.

9. 2026-11-06: installed-plugin table gains "last updated" and "last checked" columns (data format
   version 2 migration); every column can be shown/hidden and resized by dragging, with the layout
   persisted in settings and horizontal scrolling for wide layouts.

10. 2026-11-06: the two export buttons became one "Export" button with a drop-down menu (each entry
    explains its output), and toolbar/dialog buttons gained delayed hover hints.

## Next candidates

- Run the CI matrix on Linux/macOS and record results.
- Optional SpiGet provider crate (Sol had one) if the user wants it back.
- Application icon/metadata for the Windows executable.
