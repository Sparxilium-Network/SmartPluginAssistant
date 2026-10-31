//! Provider-neutral environment discovery with a persisted six-hour last-known-good catalog.

use crate::registry::ProviderRegistry;
use crate::{fail, json, now_millis, paths, Result};
use serde::{Deserialize, Serialize};
use spa_api::EnvironmentCatalog;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

const CACHE_MILLIS: i64 = 6 * 60 * 60 * 1000;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Cache {
    schema: i32,
    fetched_at: i64,
    catalog: EnvironmentCatalog,
    sources: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EnvironmentResult {
    pub catalog: EnvironmentCatalog,
    pub cached: bool,
    /// Refresh failed and an older catalog is being reused.
    pub stale: bool,
    pub sources: Vec<String>,
    pub failures: Vec<String>,
}

pub type Clock = Arc<dyn Fn() -> i64 + Send + Sync>;

pub struct EnvironmentService {
    providers: ProviderRegistry,
    file: PathBuf,
    clock: Clock,
    lock: Mutex<()>,
}

impl EnvironmentService {
    pub fn new(providers: ProviderRegistry, root: &Path) -> Result<Self> {
        Self::with_clock(providers, root, Arc::new(now_millis))
    }

    pub fn with_clock(providers: ProviderRegistry, root: &Path, clock: Clock) -> Result<Self> {
        let file = paths::child(&paths::directory(root)?, "environments.json")?;
        Ok(Self { providers, file, clock, lock: Mutex::new(()) })
    }

    fn previous(&self) -> Option<Cache> {
        if !self.file.exists() {
            return None;
        }
        match json::read::<Cache>(&self.file) {
            Ok(cache) if cache.schema == 1 && cache.catalog.validate().is_ok() => Some(cache),
            Ok(_) => {
                log::warn!("Ignoring invalid environment cache");
                None
            }
            Err(e) => {
                log::warn!("Cannot read environment cache: {e}");
                None
            }
        }
    }

    /// No invented defaults: the first launch without any usable catalog fails visibly.
    pub fn load(&self, force: bool) -> Result<EnvironmentResult> {
        let _guard = self.lock.lock().unwrap_or_else(|e| e.into_inner());
        let previous = self.previous();
        let now = (self.clock)();
        if let Some(cache) = &previous {
            if !force && now >= cache.fetched_at && now - cache.fetched_at < CACHE_MILLIS {
                return Ok(EnvironmentResult {
                    catalog: cache.catalog.clone(),
                    cached: true,
                    stale: false,
                    sources: cache.sources.clone(),
                    failures: vec![],
                });
            }
        }
        let (mut loaders, mut versions) = (Vec::new(), Vec::new());
        let (mut sources, mut failures) = (Vec::new(), Vec::new());
        for provider in self.providers.all() {
            let info = provider.info();
            match provider.environments() {
                Ok(Some(found)) => {
                    loaders.extend(found.loaders);
                    versions.extend(found.game_versions);
                    sources.push(info.name);
                }
                Ok(None) => {}
                Err(e) => {
                    log::warn!("Environment metadata request failed for {}: {e}", info.id);
                    failures.push(format!("{}: {e}", info.name));
                }
            }
        }
        if !sources.is_empty() {
            let catalog = EnvironmentCatalog::new(loaders, versions)?;
            let cache = Cache { schema: 1, fetched_at: now, catalog: catalog.clone(), sources: sources.clone() };
            if let Err(e) = json::write(&self.file, &cache) {
                log::warn!("Cannot persist environment catalog: {e}");
                failures.push(e.to_string());
            }
            return Ok(EnvironmentResult { catalog, cached: false, stale: false, sources, failures });
        }
        if let Some(cache) = previous {
            return Ok(EnvironmentResult {
                catalog: cache.catalog,
                cached: true,
                stale: true,
                sources: cache.sources,
                failures,
            });
        }
        let detail = if failures.is_empty() { String::new() } else { format!(": {}", failures.join("; ")) };
        fail(format!("Environment catalog unavailable{detail}"))
    }
}
