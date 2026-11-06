//! Application assembly: one locked data directory and the compile-time provider set.

use spa_api::PluginProvider;
use spa_core::db::Database;
use spa_core::download::DownloadService;
use spa_core::environment::EnvironmentService;
use spa_core::export::ExportService;
use spa_core::files::PluginFiles;
use spa_core::image_cache::{ImageCache, DEFAULT_LIMIT};
use spa_core::instance::InstanceStore;
use spa_core::inventory::InventoryStore;
use spa_core::lock::ApplicationLock;
use spa_core::manager::PluginManager;
use spa_core::registry::ProviderRegistry;
use spa_core::{paths, Result};
use std::path::{Path, PathBuf};
use std::sync::Arc;

pub struct AppContext {
    pub root: PathBuf,
    pub db: Arc<Database>,
    pub providers: ProviderRegistry,
    pub instances: InstanceStore,
    pub inventory: Arc<InventoryStore>,
    pub files: Arc<PluginFiles>,
    pub plugins: PluginManager,
    pub exports: ExportService,
    pub environments: EnvironmentService,
    pub images: ImageCache,
    _lock: ApplicationLock,
}

/// Bundled providers. Adding a provider means adding a crate and one line here.
pub fn builtin_providers() -> Vec<Arc<dyn PluginProvider>> {
    vec![Arc::new(spa_modrinth::ModrinthProvider::default()), Arc::new(spa_hangar::HangarProvider::default())]
}

pub fn default_data_dir(home: &Path) -> PathBuf {
    home.join(".smartpluginassistant-rust")
}

impl AppContext {
    /// Takes the process lock, then opens (creating or migrating) the versioned database.
    /// The image cache starts with the default limit; the controller applies saved settings.
    pub fn open(root: &Path, providers: Vec<Arc<dyn PluginProvider>>) -> Result<Self> {
        let root = paths::directory(root)?;
        let lock = ApplicationLock::acquire(&root)?;
        let db = Arc::new(Database::open(&root)?);
        let providers = ProviderRegistry::new(providers)?;
        let inventory = Arc::new(InventoryStore::open(db.clone()));
        let files = Arc::new(PluginFiles::new(inventory.clone(), DownloadService::default()));
        Ok(Self {
            instances: InstanceStore::open(db.clone(), &root)?,
            plugins: PluginManager::new(providers.clone(), inventory.clone(), files.clone()),
            exports: ExportService::new(inventory.clone(), providers.clone()),
            environments: EnvironmentService::new(providers.clone(), db.clone()),
            images: ImageCache::new(&root, DEFAULT_LIMIT)?,
            db,
            root,
            providers,
            inventory,
            files,
            _lock: lock,
        })
    }
}
