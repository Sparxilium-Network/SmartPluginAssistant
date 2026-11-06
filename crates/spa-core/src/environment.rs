//! Provider-neutral environment discovery with a persisted six-hour last-known-good catalog.

use crate::db::Database;
use crate::registry::ProviderRegistry;
use crate::{fail, now_millis, Result};
use rusqlite::{params, Connection, OptionalExtension};
use spa_api::EnvironmentCatalog;
use std::sync::{Arc, Mutex};

const CACHE_MILLIS: i64 = 6 * 60 * 60 * 1000;

#[derive(Debug, Clone)]
struct Cache {
    fetched_at: i64,
    catalog: EnvironmentCatalog,
    sources: Vec<String>,
}

/// Replaces the single cached catalog row (also used by the legacy JSON import).
pub(crate) fn store(
    connection: &Connection,
    fetched_at: i64,
    catalog: &EnvironmentCatalog,
    sources: &[String],
) -> Result<()> {
    connection.execute(
        "INSERT INTO environment_cache (id, fetched_at, loaders, game_versions, sources) VALUES (1, ?1, ?2, ?3, ?4)
         ON CONFLICT (id) DO UPDATE SET fetched_at = excluded.fetched_at, loaders = excluded.loaders,
         game_versions = excluded.game_versions, sources = excluded.sources",
        params![
            fetched_at,
            serde_json::to_string(&catalog.loaders)?,
            serde_json::to_string(&catalog.game_versions)?,
            serde_json::to_string(sources)?,
        ],
    )?;
    Ok(())
}

fn load(connection: &Connection) -> Result<Option<Cache>> {
    let row = connection
        .query_row("SELECT fetched_at, loaders, game_versions, sources FROM environment_cache WHERE id = 1", [], |r| {
            Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?, r.get::<_, String>(3)?))
        })
        .optional()?;
    let Some((fetched_at, loaders, versions, sources)) = row else { return Ok(None) };
    let catalog = EnvironmentCatalog {
        loaders: serde_json::from_str(&loaders)?,
        game_versions: serde_json::from_str(&versions)?,
    };
    catalog.validate()?;
    Ok(Some(Cache { fetched_at, catalog, sources: serde_json::from_str(&sources)? }))
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
    db: Arc<Database>,
    clock: Clock,
    lock: Mutex<()>,
}

impl EnvironmentService {
    pub fn new(providers: ProviderRegistry, db: Arc<Database>) -> Self {
        Self::with_clock(providers, db, Arc::new(now_millis))
    }

    pub fn with_clock(providers: ProviderRegistry, db: Arc<Database>, clock: Clock) -> Self {
        Self { providers, db, clock, lock: Mutex::new(()) }
    }

    fn previous(&self) -> Option<Cache> {
        match self.db.read(load) {
            Ok(cache) => cache,
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
            if let Err(e) = self.db.write(|t| store(t, now, &catalog, &sources)) {
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
