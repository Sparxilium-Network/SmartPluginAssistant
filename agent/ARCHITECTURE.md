# Architecture and decisions

## Product

A Rust rewrite of Smart Plugin Assistant Sol (Java/JavaFX) with the original application's Hangar support.
It organizes virtual Minecraft server instances, searches/installs plugins from Modrinth and Hangar,
imports existing JARs, and safely updates/exports them. It never starts or stops servers.

## Decisions (confirmed with the user, 2026-10-31)

- UI framework: Slint, with strict frontend/backend separation so the toolkit can be replaced.
- Bundled providers: Modrinth + Hangar (Spigot from Sol and Voxel from the original are not included).
- Extension model: compile-time trait implementations in separate crates; no runtime plugin loading.
- Data: new directory `~/.smartpluginassistant-rust/` with one SQLite database `spa.db` (changed from JSON on
  2026-11-06 at the user's request), with an explicit data format version. Sol JSON is imported once.

## Layers

```
spa-api  <-  providers/modrinth, providers/hangar
spa-api  <-  spa-core  <-  spa-app  <-  spa-desktop (Slint)
```

- `spa-api`: `PluginProvider` trait (blocking, `Send + Sync`), immutable contracts, `HttpTransport`
  with HTTPS-only (loopback HTTP for tests), no redirects, 8 MiB body limit and a 4-request semaphore.
- `spa-core`: everything that touches disk or downloads. No provider or UI imports.
- `spa-app`: `Controller` presenter owning state; emits `UiEvent` view models through a `Sink`
  (`Arc<dyn Fn(UiEvent) + Send + Sync>`) from background threads. Holds i18n, settings, feeds,
  artwork decoding and environment-choice policy. No UI toolkit types.
- `spa-desktop`: maps events to Slint models on the event loop (`invoke_from_event_loop`) and wires
  callbacks to controller methods. File dialogs (`rfd`) and opening folders/URLs (`open`) live here.

## Data safety

- Central instance index with UUID identity; unregister never deletes files.
- Process lock (`application.lock`, `File::try_lock`) prevents concurrent writers.
- Storage: `spa_core::db::Database` wraps one bundled-SQLite connection (WAL, `synchronous=FULL`,
  `foreign_keys=ON`, STRICT tables) behind a mutex; every change runs in an immediate transaction.
  Tables: `meta`, `instances`, `plugins` (FK cascade on unregister; server files untouched),
  `settings` (key -> JSON value), `environment_cache` (single row).
- Data format history: v1 initial schema; v2 adds `plugins.updated_at` (file content last changed;
  install/update/import write the current time, unknown files fall back to their modification time)
  and `plugins.checked_at` (last successful update check, keyed by SHA-512; failures keep the old value).
- Data format version: `meta.schema_version`, mirrored to `PRAGMA user_version`, plus `created_by`,
  `last_opened_by`, `created_at`, `migrated_at`. On open: `quick_check`; refuse newer versions without
  writing; `VACUUM INTO spa.db.v<old>-<millis>.bak` before migrating an older file; apply numbered
  migrations and the version bump in one transaction. Foreign or unversioned databases are refused.
- Legacy import: only when creating the database (version 0 -> current), Sol-format `instances.json`,
  `inventory/<id>.json`, `settings.json` and `environments.json` are imported in the same transaction;
  invalid files abort the open, JSON files are never modified. `spa_core::json` remains for that import
  and for atomic file writes (exports, image cache).
- Downloads: temp file in the plugins directory (`.spa-download-*.tmp`), manual redirects (max 5,
  no HTTPS downgrade), 256 MiB limit, declared-length truncation check, provider size/hashes, full
  JAR read (1 GiB expanded limit). Publishing uses `persist_noclobber`; replaced files move to
  `.spa-backups/<UUID>/` on the same volume; metadata failure rolls back file moves.
- Inventory identity is bound to SHA-512; renamed files keep identity only for a unique hash match.
- Updates use release ID and publication time; never version-string ordering.
- Plugin mutations are serialized by `PluginFiles`' mutex and the presenter's busy flag.

## Providers

Modrinth follows Sol: loader/game-version facets, filtered versions, exact releases, dependency kinds,
multi-file SHA-512 identity, strict `modrinth.com/plugin/<slug>[/version/<id>]` URLs, `tag/loader` +
`tag/game_version` environment catalog.

Hangar (`https://hangar.papermc.io/api/v1/`, checked live 2026-10-31):
- Platform from the target's effective loaders: paper/purpur/folia -> PAPER, waterfall/bungeecord ->
  WATERFALL, velocity -> VELOCITY; other loaders get no Hangar results (not an error).
- Search `projects?platform&version&offset&limit` (`sort=-downloads` for empty queries).
- Versions `projects/{id}/versions?platform&platformVersion`; exact `projects/{id}/versions/{id|name}`
  and `versions/{id}`. Release ID is the numeric version ID; official URLs may carry the name.
- `platformDependencies[platform]` lists MC versions. Confidence is `Verified` only when the target's
  effective loaders include the platform name; otherwise `Declared` (confirmation required).
- Channels: UNSTABLE flag or snapshot/dev/beta/rc-like names -> Beta, "alpha" -> Alpha, else Release.
- `fileInfo.sha256Hash` is verified; external-only downloads have no direct download.
- `pluginDependencies` with `projectId` resolve through Hangar; website-only ones become
  `ManualDependency` plan items that require explicit confirmation (never silently dropped).
- No hash identification API; local JAR linking uses Modrinth only.

## Discovery feeds

`ProjectFeed` snapshots query + target, deduplicates provider/project identity, advances only on
accepted pages and stops on empty, duplicate-only or final pages. One request per feed; session
tokens fence stale completions after source/query/instance changes or tab deactivation. Failures
keep rows and require explicit retry. The Slint list requests the next page when the remaining
content height falls under 300 px (`changed content-y`), with a "Load more" fallback button.
Provider page indices are zero-based.

## Artwork and preferences

`ImageCache` (core) stores validated raster bytes (PNG/JPEG/GIF/BMP/WebP magic, 4 MiB each) as
`<sha256>.img`, coalesces requests on a 4-worker pool with an 80-job bounded queue, keeps a sliding
seven-day freshness window, reuses stale bytes when refresh fails, evicts least recently used files
over the limit, and fences in-flight writes with a generation counter on clear/disable.
`spa-app::artwork` decodes the first frame with dimension/allocation limits into 128 px RGBA and keeps
a 128-entry memory cache. `AppSettings` keeps Sol's fields (one `settings` row each) and missing fields take defaults.
`libraryColumns` stores the installed-plugin table layout (id, visible, width); `spa-app::columns::normalize`
drops unknown ids, appends missing ones, clamps widths to 48-800 px and keeps one column visible.

## UI notes

- Translations: Slint `Tr.get(Tr.revision, key)` pure callback; bumping `revision` re-evaluates labels.
- Dialogs are in-window modals selected by `dialog`; messages are a separate top layer so errors never
  discard a form in progress.
- Library table: columns have fixed widths inside a horizontally scrolling `ScrollView`. Dragging a header's
  right edge updates only the Slint model (`column-drag`); release commits through
  `Controller::set_column_width`, which validates, persists and re-emits `UiEvent::Columns`. The
  chooser ("Columns" button or header right-click) calls `set_column_visible` / `reset_columns`.
- Hover hints: `AppButton.tip` starts a 600 ms `Timer` while hovered (also on disabled buttons) and writes
  the `Tip` global (text + anchor in window coordinates); MainWindow draws one `TipBox` last, so hints are
  never clipped, and flips it above the control near the bottom edge. Texts are `tip.*` catalog keys.
- Menus are root-level overlays like dialogs (the export menu is `export-menu`), anchored from the
  button's `absolute-position`; a full-window TouchArea closes them on an outside click.
- `--snapshot=<dir>` installs an offscreen `MinimalSoftwareWindow` platform with its own event queue
  and drives the real UI against live APIs; `--smoke` writes `smoke-result.json`.
