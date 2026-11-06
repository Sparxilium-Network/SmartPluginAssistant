//! The single SQLite store for application data, identified by an explicit data format version.
//!
//! `meta.schema_version` (mirrored to `PRAGMA user_version`) names the save format. Opening:
//! - verifies integrity and refuses files written by a newer application (they are never modified);
//! - backs up an older database with `VACUUM INTO` before migrating it;
//! - applies pending migrations and the version change in one transaction;
//! - on first creation, imports Sol-format JSON files found in the data directory (left untouched).

use crate::instance::{self, ServerInstance};
use crate::inventory::{self, InstalledPlugin};
use crate::{fail, json, now_millis, paths, Result};
use rusqlite::{params, Connection, OpenFlags, OptionalExtension, Transaction, TransactionBehavior};
use serde::Deserialize;
use spa_api::EnvironmentCatalog;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard};
use std::time::Duration;

pub const FILE_NAME: &str = "spa.db";
pub const APP_VERSION: &str = env!("CARGO_PKG_VERSION");

/// One forward-only schema step. Versions start at 1 and increase by one.
pub struct Migration {
    pub version: i64,
    pub description: &'static str,
    pub sql: &'static str,
}

const SCHEMA_V1: &str = r#"
CREATE TABLE meta (
    key TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL
) STRICT;

CREATE TABLE instances (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    directory TEXT NOT NULL,
    loader TEXT NOT NULL,
    minecraft_version TEXT NOT NULL,
    extra_loaders TEXT NOT NULL DEFAULT '[]',
    allow_prereleases INTEGER NOT NULL DEFAULT 0 CHECK (allow_prereleases IN (0, 1)),
    created_at INTEGER NOT NULL
) STRICT;
CREATE UNIQUE INDEX instances_directory ON instances (directory COLLATE NOCASE);

CREATE TABLE plugins (
    instance_id TEXT NOT NULL REFERENCES instances (id) ON DELETE CASCADE,
    file_name TEXT NOT NULL,
    sha512 TEXT NOT NULL,
    size INTEGER NOT NULL CHECK (size >= 0),
    version TEXT NOT NULL DEFAULT '',
    provider_id TEXT,
    project_id TEXT,
    release_id TEXT,
    published_at INTEGER NOT NULL DEFAULT 0,
    enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
    PRIMARY KEY (instance_id, file_name)
) STRICT;

CREATE TABLE settings (
    key TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL
) STRICT;

CREATE TABLE environment_cache (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    fetched_at INTEGER NOT NULL,
    loaders TEXT NOT NULL,
    game_versions TEXT NOT NULL,
    sources TEXT NOT NULL
) STRICT;
"#;

pub const MIGRATIONS: &[Migration] = &[Migration { version: 1, description: "Initial schema", sql: SCHEMA_V1 }];

/// The data format version this build reads and writes.
pub fn current_version() -> i64 {
    latest(MIGRATIONS)
}

fn latest(migrations: &[Migration]) -> i64 {
    migrations.last().map_or(0, |m| m.version)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StorageInfo {
    pub path: PathBuf,
    pub schema_version: i64,
    pub created_by: String,
    /// Version the file had before this launch migrated it.
    pub migrated_from: Option<i64>,
    /// Copy taken before migrating an existing database.
    pub backup: Option<PathBuf>,
    /// Sol-format JSON files were imported while creating the database.
    pub imported_legacy: bool,
}

pub struct Database {
    connection: Mutex<Connection>,
    info: StorageInfo,
}

fn meta(connection: &Connection, key: &str) -> Result<Option<String>> {
    Ok(connection.query_row("SELECT value FROM meta WHERE key = ?1", [key], |r| r.get(0)).optional()?)
}

fn set_meta(connection: &Connection, key: &str, value: &str) -> Result<()> {
    connection.execute(
        "INSERT INTO meta (key, value) VALUES (?1, ?2) ON CONFLICT (key) DO UPDATE SET value = excluded.value",
        params![key, value],
    )?;
    Ok(())
}

/// 0 for an empty file; fails for foreign or unversioned databases.
fn stored_version(connection: &Connection) -> Result<i64> {
    let table = |name: &str| -> Result<bool> {
        Ok(connection.query_row(
            "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?1",
            [name],
            |r| r.get::<_, i64>(0),
        )? > 0)
    };
    if !table("meta")? {
        let tables: i64 =
            connection.query_row("SELECT count(*) FROM sqlite_master WHERE type = 'table'", [], |r| r.get(0))?;
        if tables > 0 {
            return fail("Not a Smart Plugin Assistant database");
        }
        return Ok(0);
    }
    match meta(connection, "schema_version")?.map(|v| v.parse::<i64>()) {
        Some(Ok(version)) if version > 0 => Ok(version),
        _ => fail("Database has no valid data format version"),
    }
}

impl Database {
    pub fn open(root: &Path) -> Result<Self> {
        Self::open_with(root, MIGRATIONS)
    }

    /// `migrations` is a parameter so tests can exercise upgrades with extra steps.
    pub fn open_with(root: &Path, migrations: &[Migration]) -> Result<Self> {
        if migrations.iter().enumerate().any(|(i, m)| m.version != i as i64 + 1) {
            return fail("Migrations must be numbered consecutively from 1");
        }
        let root = paths::directory(root)?;
        let path = paths::child(&root, FILE_NAME)?;
        let mut connection = Connection::open_with_flags(
            &path,
            OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_CREATE | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )?;
        connection.busy_timeout(Duration::from_secs(5))?;
        connection.pragma_update(None, "foreign_keys", true)?;
        connection.query_row("PRAGMA journal_mode = WAL", [], |r| r.get::<_, String>(0))?;
        connection.pragma_update(None, "synchronous", "FULL")?;
        let check: String = connection.query_row("PRAGMA quick_check", [], |r| r.get(0))?;
        if check != "ok" {
            return fail(format!("Database integrity check failed: {check}"));
        }

        let current = stored_version(&connection)?;
        let target = latest(migrations);
        if current > target {
            return fail(format!(
                "Data format version {current} is newer than this application supports ({target}); update the application"
            ));
        }
        let mut backup = None;
        if current > 0 && current < target {
            let copy = paths::child(&root, &format!("{FILE_NAME}.v{current}-{}.bak", now_millis()))?;
            connection.execute("VACUUM INTO ?1", [copy.to_string_lossy()])?;
            log::info!("Backed up data format version {current} to {}", copy.display());
            backup = Some(copy);
        }

        let mut imported_legacy = false;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        for migration in migrations.iter().filter(|m| m.version > current) {
            transaction.execute_batch(migration.sql)?;
            log::info!("Applied data format migration {}: {}", migration.version, migration.description);
        }
        if current < target {
            set_meta(&transaction, "schema_version", &target.to_string())?;
            transaction.pragma_update(None, "user_version", target)?;
            set_meta(&transaction, "migrated_at", &now_millis().to_string())?;
            if current == 0 {
                set_meta(&transaction, "created_at", &now_millis().to_string())?;
                set_meta(&transaction, "created_by", APP_VERSION)?;
                imported_legacy = import_legacy(&transaction, &root)?;
            }
        }
        set_meta(&transaction, "last_opened_by", APP_VERSION)?;
        transaction.commit()?;

        let info = StorageInfo {
            created_by: meta(&connection, "created_by")?.unwrap_or_default(),
            path,
            schema_version: target,
            migrated_from: (current > 0 && current < target).then_some(current),
            backup,
            imported_legacy,
        };
        log::info!("Opened {} (data format version {target})", info.path.display());
        Ok(Self { connection: Mutex::new(connection), info })
    }

    pub fn info(&self) -> &StorageInfo {
        &self.info
    }

    fn lock(&self) -> MutexGuard<'_, Connection> {
        self.connection.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn read<T>(&self, query: impl FnOnce(&Connection) -> Result<T>) -> Result<T> {
        query(&self.lock())
    }

    /// Runs `change` in an immediate transaction; any error rolls everything back.
    pub fn write<T>(&self, change: impl FnOnce(&Transaction) -> Result<T>) -> Result<T> {
        let mut connection = self.lock();
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let value = change(&transaction)?;
        transaction.commit()?;
        Ok(value)
    }

    /// Application preferences as key -> JSON-encoded value.
    pub fn settings(&self) -> Result<Vec<(String, String)>> {
        self.read(|c| {
            let mut statement = c.prepare("SELECT key, value FROM settings ORDER BY key")?;
            let rows = statement.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?;
            Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
        })
    }

    pub fn put_settings(&self, values: &[(String, String)]) -> Result<()> {
        self.write(|t| {
            for (key, value) in values {
                t.execute(
                    "INSERT INTO settings (key, value) VALUES (?1, ?2) ON CONFLICT (key) DO UPDATE SET value = excluded.value",
                    params![key, value],
                )?;
            }
            Ok(())
        })
    }
}

#[derive(Deserialize)]
struct LegacyIndex {
    schema: i32,
    instances: Vec<ServerInstance>,
}

#[derive(Deserialize)]
struct LegacyInventory {
    schema: i32,
    plugins: Vec<InstalledPlugin>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct LegacyEnvironment {
    schema: i32,
    fetched_at: i64,
    catalog: EnvironmentCatalog,
    sources: Vec<String>,
}

/// Imports Sol-format JSON in the same transaction as database creation. Invalid files fail
/// the whole open so nothing is half-imported; the JSON files themselves are never changed.
fn import_legacy(transaction: &Transaction, root: &Path) -> Result<bool> {
    let mut imported = false;
    let index = root.join("instances.json");
    if index.exists() {
        let data: LegacyIndex = json::read(&index)?;
        if data.schema != 1 {
            return fail("Unsupported legacy instance index");
        }
        for item in &data.instances {
            instance::insert(transaction, item)?;
            let file = root.join("inventory").join(format!("{}.json", item.id));
            if file.exists() {
                let inventory: LegacyInventory = json::read(&file)?;
                if inventory.schema != 1 {
                    return fail("Unsupported legacy inventory");
                }
                for plugin in &inventory.plugins {
                    inventory::insert(transaction, item.id, plugin)?;
                }
            }
        }
        log::info!("Imported {} instances from legacy instances.json", data.instances.len());
        imported = true;
    }
    let settings = root.join("settings.json");
    if settings.exists() {
        let value: serde_json::Value = json::read(&settings)?;
        let Some(object) = value.as_object() else {
            return fail("Invalid legacy settings");
        };
        for (key, value) in object {
            transaction
                .execute("INSERT INTO settings (key, value) VALUES (?1, ?2)", params![key, value.to_string()])?;
        }
        imported = true;
    }
    let environments = root.join("environments.json");
    if environments.exists() {
        // A cache only: an unreadable one is skipped rather than blocking startup.
        match json::read::<LegacyEnvironment>(&environments) {
            Ok(cache) if cache.schema == 1 && cache.catalog.validate().is_ok() => {
                crate::environment::store(transaction, cache.fetched_at, &cache.catalog, &cache.sources)?;
                imported = true;
            }
            _ => log::warn!("Skipping unreadable legacy environment cache"),
        }
    }
    Ok(imported)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn creates_versioned_database_and_reopens() {
        let dir = tempfile::tempdir().unwrap();
        let db = Database::open(dir.path()).unwrap();
        assert_eq!(db.info().schema_version, current_version());
        assert_eq!(db.info().created_by, APP_VERSION);
        assert!(db.info().migrated_from.is_none() && !db.info().imported_legacy);
        let user_version: i64 = db.read(|c| Ok(c.query_row("PRAGMA user_version", [], |r| r.get(0))?)).unwrap();
        assert_eq!(user_version, current_version());
        drop(db);
        let again = Database::open(dir.path()).unwrap();
        assert!(again.info().backup.is_none());
    }

    #[test]
    fn refuses_newer_and_foreign_databases_without_changing_them() {
        let dir = tempfile::tempdir().unwrap();
        Database::open(dir.path()).unwrap().write(|t| set_meta(t, "schema_version", "999")).unwrap();
        let before = std::fs::read(dir.path().join(FILE_NAME)).unwrap();
        let error = Database::open(dir.path()).err().unwrap().to_string();
        assert!(error.contains("newer"), "{error}");
        assert_eq!(std::fs::read(dir.path().join(FILE_NAME)).unwrap(), before);

        let foreign = tempfile::tempdir().unwrap();
        Connection::open(foreign.path().join(FILE_NAME)).unwrap().execute_batch("CREATE TABLE other (x)").unwrap();
        assert!(Database::open(foreign.path()).is_err());
    }

    #[test]
    fn migrates_older_database_after_backup() {
        let dir = tempfile::tempdir().unwrap();
        let v1 = &MIGRATIONS[..1];
        Database::open_with(dir.path(), v1).unwrap().put_settings(&[("language".into(), "\"en\"".into())]).unwrap();
        const V2: Migration =
            Migration { version: 2, description: "test column", sql: "ALTER TABLE settings ADD COLUMN note TEXT" };
        let upgraded = Database::open_with(dir.path(), &[MIGRATIONS[0].clone_static(), V2]).unwrap();
        assert_eq!(upgraded.info().schema_version, 2);
        assert_eq!(upgraded.info().migrated_from, Some(1));
        let backup = upgraded.info().backup.clone().unwrap();
        assert!(backup.exists());
        let old = Connection::open(&backup).unwrap();
        assert_eq!(meta(&old, "schema_version").unwrap().as_deref(), Some("1"));
        assert_eq!(upgraded.settings().unwrap(), vec![("language".to_string(), "\"en\"".to_string())]);
        // A failing migration rolls back and leaves the version unchanged.
        drop(upgraded);
        const BAD: Migration = Migration { version: 3, description: "broken", sql: "CREATE TABLE settings (x)" };
        assert!(Database::open_with(dir.path(), &[MIGRATIONS[0].clone_static(), V2, BAD]).is_err());
        let reopened = Database::open_with(dir.path(), &[MIGRATIONS[0].clone_static(), V2]).unwrap();
        assert_eq!(reopened.info().schema_version, 2);
    }

    impl Migration {
        fn clone_static(&self) -> Migration {
            Migration { version: self.version, description: self.description, sql: self.sql }
        }
    }
}
