//! Hash-bound local plugin inventory. Provider identity is trusted only for an exact hash match.

use crate::instance::ServerInstance;
use crate::{fail, jar, json, paths, Result};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

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
        }
    }
}

#[derive(Serialize, Deserialize)]
struct Index {
    schema: i32,
    plugins: Vec<InstalledPlugin>,
}

pub struct InventoryStore {
    root: PathBuf,
    lock: Mutex<()>,
}

/// Prefix of host-owned staging files, which are never inventory entries.
pub const TEMP_PREFIX: &str = ".spa-";

pub fn is_enabled_name(name: &str) -> bool {
    !name.to_lowercase().ends_with(".disabled")
}

impl InventoryStore {
    pub fn open(root: &Path) -> Result<Self> {
        Ok(Self { root: paths::directory(&root.join("inventory"))?, lock: Mutex::new(()) })
    }

    fn file(&self, instance: &ServerInstance) -> Result<PathBuf> {
        paths::child(&self.root, &format!("{}.json", instance.id))
    }

    fn guard(&self) -> std::sync::MutexGuard<'_, ()> {
        self.lock.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn records(&self, instance: &ServerInstance) -> Result<Vec<InstalledPlugin>> {
        let _guard = self.guard();
        self.read(instance)
    }

    fn read(&self, instance: &ServerInstance) -> Result<Vec<InstalledPlugin>> {
        let path = self.file(instance)?;
        if !path.exists() {
            return Ok(Vec::new());
        }
        let data: Index = json::read(&path)?;
        if data.schema != 1 {
            return fail("Invalid inventory index");
        }
        Ok(data.plugins)
    }

    pub fn save(&self, instance: &ServerInstance, plugins: &[InstalledPlugin]) -> Result<()> {
        let _guard = self.guard();
        json::write(&self.file(instance)?, &Index { schema: 1, plugins: plugins.to_vec() })
    }

    /// Current files with metadata carried over only when the SHA-512 still matches.
    pub fn scan(&self, instance: &ServerInstance) -> Result<Vec<InstalledPlugin>> {
        let _guard = self.guard();
        let directory = paths::directory(&instance.plugins_directory())?;
        let stored = self.read(instance)?;
        let previous: HashMap<&str, &InstalledPlugin> =
            stored.iter().map(|p| (p.file_name.as_str(), p)).collect();
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
            result.push(match old {
                Some(old) => InstalledPlugin {
                    file_name: name,
                    sha512: hash,
                    size: meta.len(),
                    enabled,
                    ..old.clone()
                },
                None => {
                    let version = jar::version(&path).unwrap_or_default();
                    InstalledPlugin::unlinked(name, hash, meta.len(), version, enabled)
                }
            });
        }
        Ok(result)
    }
}
