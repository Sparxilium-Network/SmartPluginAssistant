//! Hash-bound local plugin inventory. Provider identity is trusted only for an exact hash match.

use crate::db::Database;
use crate::instance::ServerInstance;
use crate::{jar, paths, Result};
use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::sync::{Arc, Mutex};
use uuid::Uuid;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InstalledPlugin {
    pub file_name: String,
    pub sha512: String,
    pub size: u64,
    pub version: String,
    pub provider_id: Option<String>,
    pub project_id: Option<String>,
    pub release_id: Option<String>,
    #[serde(default)]
    pub published_at: i64,
    pub enabled: bool,
    /// When the file content last changed (Unix ms, 0 = unknown).
    #[serde(default)]
    pub updated_at: i64,
    /// When an update check last succeeded for this file (Unix ms, 0 = never).
    #[serde(default)]
    pub checked_at: i64,
}

impl InstalledPlugin {
    pub fn linked(&self) -> bool {
        self.provider_id.is_some() && self.project_id.is_some() && self.release_id.is_some()
    }

    pub fn is_linked_to(&self, provider: &str, project: &str) -> bool {
        self.provider_id.as_deref() == Some(provider) && self.project_id.as_deref() == Some(project)
    }

    fn unlinked(file_name: String, sha512: String, size: u64, version: String, enabled: bool) -> Self {
        Self {
            file_name,
            sha512,
            size,
            version,
            provider_id: None,
            project_id: None,
            release_id: None,
            published_at: 0,
            enabled,
            updated_at: 0,
            checked_at: 0,
        }
    }
}

/// Inserts one record (also used by the legacy JSON import).
pub(crate) fn insert(connection: &Connection, instance: Uuid, plugin: &InstalledPlugin) -> Result<()> {
    connection.execute(
        "INSERT INTO plugins (instance_id, file_name, sha512, size, version, provider_id, project_id, release_id,
         published_at, enabled, updated_at, checked_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)",
        params![
            instance.to_string(),
            paths::file_name(&plugin.file_name)?,
            plugin.sha512,
            i64::try_from(plugin.size).unwrap_or(i64::MAX),
            plugin.version,
            plugin.provider_id,
            plugin.project_id,
            plugin.release_id,
            plugin.published_at,
            plugin.enabled,
            plugin.updated_at,
            plugin.checked_at,
        ],
    )?;
    Ok(())
}

pub struct InventoryStore {
    db: Arc<Database>,
    lock: Mutex<()>,
}

/// Prefix of host-owned staging files, which are never inventory entries.
pub const TEMP_PREFIX: &str = ".spa-";

fn modified_millis(meta: &fs::Metadata) -> i64 {
    meta.modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(0))
}

pub fn is_enabled_name(name: &str) -> bool {
    !name.to_lowercase().ends_with(".disabled")
}

impl InventoryStore {
    pub fn open(db: Arc<Database>) -> Self {
        Self { db, lock: Mutex::new(()) }
    }

    fn guard(&self) -> std::sync::MutexGuard<'_, ()> {
        self.lock.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn records(&self, instance: &ServerInstance) -> Result<Vec<InstalledPlugin>> {
        let _guard = self.guard();
        self.read(instance)
    }

    fn read(&self, instance: &ServerInstance) -> Result<Vec<InstalledPlugin>> {
        self.db.read(|c| {
            let mut statement = c.prepare(
                "SELECT file_name, sha512, size, version, provider_id, project_id, release_id, published_at, enabled,
                 updated_at, checked_at FROM plugins WHERE instance_id = ?1 ORDER BY file_name",
            )?;
            let rows = statement.query_map([instance.id.to_string()], |r| {
                Ok(InstalledPlugin {
                    file_name: r.get(0)?,
                    sha512: r.get(1)?,
                    size: r.get::<_, i64>(2)?.max(0) as u64,
                    version: r.get(3)?,
                    provider_id: r.get(4)?,
                    project_id: r.get(5)?,
                    release_id: r.get(6)?,
                    published_at: r.get(7)?,
                    enabled: r.get(8)?,
                    updated_at: r.get(9)?,
                    checked_at: r.get(10)?,
                })
            })?;
            Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
        })
    }

    /// Replaces the instance's records in one transaction.
    pub fn save(&self, instance: &ServerInstance, plugins: &[InstalledPlugin]) -> Result<()> {
        let _guard = self.guard();
        self.db.write(|t| {
            t.execute("DELETE FROM plugins WHERE instance_id = ?1", [instance.id.to_string()])?;
            for plugin in plugins {
                insert(t, instance.id, plugin)?;
            }
            Ok(())
        })
    }

    /// Records a successful update check for the given file hashes.
    pub fn mark_checked(&self, instance: &ServerInstance, hashes: &[String], at: i64) -> Result<()> {
        let _guard = self.guard();
        self.db.write(|t| {
            for hash in hashes {
                t.execute(
                    "UPDATE plugins SET checked_at = ?1 WHERE instance_id = ?2 AND sha512 = ?3",
                    params![at, instance.id.to_string(), hash],
                )?;
            }
            Ok(())
        })
    }

    /// Current files with metadata carried over only when the SHA-512 still matches.
    pub fn scan(&self, instance: &ServerInstance) -> Result<Vec<InstalledPlugin>> {
        let _guard = self.guard();
        let directory = paths::directory(&instance.plugins_directory())?;
        let stored = self.read(instance)?;
        let previous: HashMap<&str, &InstalledPlugin> = stored.iter().map(|p| (p.file_name.as_str(), p)).collect();
        let mut names: Vec<String> = fs::read_dir(&directory)?
            .filter_map(|e| e.ok())
            .map(|e| e.file_name().to_string_lossy().into_owned())
            .filter(|n| paths::is_plugin(n) && !n.starts_with(TEMP_PREFIX))
            .collect();
        names.sort();
        let mut result = Vec::new();
        for name in names {
            let path = directory.join(&name);
            paths::reject_links(&path)?;
            let meta = fs::metadata(&path)?;
            if !meta.is_file() {
                continue;
            }
            let hash = jar::hash(&path, jar::Algorithm::Sha512)?;
            let mut old = previous.get(name.as_str()).copied().filter(|p| p.sha512 == hash);
            if old.is_none() {
                // A renamed file keeps its identity only when exactly one record has its hash.
                let matches: Vec<_> = stored.iter().filter(|p| p.sha512 == hash).collect();
                old = (matches.len() == 1).then(|| matches[0]);
            }
            let enabled = is_enabled_name(&name);
            let mut plugin = match old {
                Some(old) => {
                    InstalledPlugin { file_name: name, sha512: hash, size: meta.len(), enabled, ..old.clone() }
                }
                None => {
                    let version = jar::version(&path).unwrap_or_default();
                    InstalledPlugin::unlinked(name, hash, meta.len(), version, enabled)
                }
            };
            if plugin.updated_at == 0 {
                // Files not written by this application fall back to their modification time.
                plugin.updated_at = modified_millis(&meta);
            }
            result.push(plugin);
        }
        Ok(result)
    }
}
