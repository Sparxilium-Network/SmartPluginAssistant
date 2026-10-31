//! The single mutation coordinator for plugin files. Every change is rollback-safe.

use crate::download::{DownloadService, MAX_BYTES};
use crate::instance::ServerInstance;
use crate::inventory::{is_enabled_name, InstalledPlugin, InventoryStore};
use crate::{fail, jar, paths, Result};
use spa_api::Release;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use uuid::Uuid;

pub const BACKUP_DIRECTORY: &str = ".spa-backups";
const DISABLED: &str = ".disabled";

pub struct PluginFiles {
    inventory: Arc<InventoryStore>,
    download: DownloadService,
    lock: Mutex<()>,
}

fn require_current(current: &[InstalledPlugin], expected: Option<&InstalledPlugin>) -> Result<()> {
    if let Some(expected) = expected {
        if !current.iter().any(|p| p.file_name == expected.file_name && p.sha512 == expected.sha512) {
            return fail("Plugin changed since scan; refresh first");
        }
    }
    Ok(())
}

fn without(plugins: &[InstalledPlugin], file_name: &str) -> Vec<InstalledPlugin> {
    plugins.iter().filter(|p| p.file_name != file_name).cloned().collect()
}

impl PluginFiles {
    pub fn new(inventory: Arc<InventoryStore>, download: DownloadService) -> Self {
        Self { inventory, download, lock: Mutex::new(()) }
    }

    fn guard(&self) -> std::sync::MutexGuard<'_, ()> {
        self.lock.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Installs a release, replacing `replacing` (kept in `.spa-backups`) when given.
    pub fn install(
        &self,
        instance: &ServerInstance,
        release: &Release,
        replacing: Option<&InstalledPlugin>,
    ) -> Result<InstalledPlugin> {
        let _guard = self.guard();
        let directory = paths::directory(&instance.plugins_directory())?;
        let mut name = paths::file_name(&release.file_name)?.to_string();
        if !name.to_lowercase().ends_with(".jar") {
            return fail("Release must be a JAR");
        }
        if replacing.is_some_and(|r| !r.enabled) {
            name.push_str(DISABLED);
        }
        let target = paths::child(&directory, &name)?;
        let conflicts = |target: &Path| target.exists() && replacing.is_none_or(|r| r.file_name != name);
        require_current(&self.inventory.scan(instance)?, replacing)?;
        if conflicts(&target) {
            return fail(format!("Destination already exists: {name}"));
        }
        let staged = self.download.stage(release, &directory, None)?;
        // Re-check after the network transfer; the directory may have changed meanwhile.
        let before = self.inventory.scan(instance)?;
        require_current(&before, replacing)?;
        if conflicts(&target) {
            return fail("Destination changed during download");
        }
        let installed = InstalledPlugin {
            file_name: name,
            sha512: jar::hash(&staged, jar::Algorithm::Sha512)?,
            size: fs::metadata(&staged)?.len(),
            version: release.version.clone(),
            provider_id: Some(release.provider_id.clone()),
            project_id: Some(release.project_id.clone()),
            release_id: Some(release.id.clone()),
            published_at: release.published_at,
            enabled: replacing.is_none_or(|r| r.enabled),
        };
        self.publish(instance, staged, &target, replacing, &before, installed.clone())?;
        log::info!(
            "Installed {}:{}:{} into instance {}",
            release.provider_id,
            release.project_id,
            release.id,
            instance.id
        );
        Ok(installed)
    }

    pub fn import_jar(&self, instance: &ServerInstance, source: &Path) -> Result<InstalledPlugin> {
        let _guard = self.guard();
        paths::reject_links(source)?;
        let file_name = source.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        if !paths::is_plugin(&file_name) {
            return fail("Only JAR files can be imported");
        }
        if fs::metadata(source)?.len() > MAX_BYTES {
            return fail("Import exceeds size limit");
        }
        jar::validate(source)?;
        let directory = paths::directory(&instance.plugins_directory())?;
        let target = paths::child(&directory, &file_name)?;
        let before = self.inventory.scan(instance)?;
        if target.exists() {
            return fail("Import destination already exists");
        }
        let staged = tempfile::Builder::new().prefix(".spa-import-").suffix(".tmp").tempfile_in(&directory)?;
        let staged = staged.into_temp_path();
        fs::copy(source, &staged)?;
        let installed = InstalledPlugin {
            file_name: file_name.clone(),
            sha512: jar::hash(&staged, jar::Algorithm::Sha512)?,
            size: fs::metadata(&staged)?.len(),
            version: jar::version(&staged).unwrap_or_default(),
            provider_id: None,
            project_id: None,
            release_id: None,
            published_at: 0,
            enabled: is_enabled_name(&file_name),
        };
        self.publish(instance, staged, &target, None, &before, installed.clone())?;
        log::info!("Imported {file_name} into instance {}", instance.id);
        Ok(installed)
    }

    fn publish(
        &self,
        instance: &ServerInstance,
        staged: tempfile::TempPath,
        target: &Path,
        old: Option<&InstalledPlugin>,
        before: &[InstalledPlugin],
        installed: InstalledPlugin,
    ) -> Result<()> {
        let old_paths = match old {
            Some(old) => Some((
                paths::child(&instance.plugins_directory(), &old.file_name)?,
                self.backup_path(instance, &old.file_name)?,
            )),
            None => None,
        };
        let mut published = false;
        let result = (|| -> Result<()> {
            if let Some((old_path, backup)) = &old_paths {
                fs::rename(old_path, backup)?;
            }
            staged.persist_noclobber(target).map_err(|e| e.error)?;
            published = true;
            let mut after = match old {
                Some(old) => without(before, &old.file_name),
                None => before.to_vec(),
            };
            after.push(installed);
            self.inventory.save(instance, &after)
        })();
        if let Err(error) = result {
            if published {
                if let Err(e) = fs::remove_file(target) {
                    log::error!("Rollback could not remove {}: {e}", target.display());
                }
            }
            if let Some((old_path, backup)) = &old_paths {
                if backup.exists() {
                    if let Err(e) = fs::rename(backup, old_path) {
                        log::error!("Rollback could not restore {}: {e}", old_path.display());
                    }
                }
            }
            return Err(error);
        }
        Ok(())
    }

    /// Backups stay on the server volume so renames are atomic.
    fn backup_path(&self, instance: &ServerInstance, name: &str) -> Result<PathBuf> {
        let directory = paths::directory(
            &instance.server_directory().join(BACKUP_DIRECTORY).join(Uuid::new_v4().to_string()),
        )?;
        paths::child(&directory, name)
    }

    pub fn toggle(&self, instance: &ServerInstance, plugin: &InstalledPlugin) -> Result<InstalledPlugin> {
        let _guard = self.guard();
        let before = self.inventory.scan(instance)?;
        require_current(&before, Some(plugin))?;
        let name = if plugin.enabled {
            format!("{}{DISABLED}", plugin.file_name)
        } else {
            plugin.file_name[..plugin.file_name.len() - DISABLED.len()].to_string()
        };
        let source = paths::child(&instance.plugins_directory(), &plugin.file_name)?;
        let target = paths::child(&instance.plugins_directory(), &name)?;
        if target.exists() {
            return fail("Enabled/disabled filename conflict");
        }
        let changed = InstalledPlugin { file_name: name, enabled: !plugin.enabled, ..plugin.clone() };
        fs::rename(&source, &target)?;
        let mut after = without(&before, &plugin.file_name);
        after.push(changed.clone());
        if let Err(e) = self.inventory.save(instance, &after) {
            fs::rename(&target, &source)?;
            return Err(e);
        }
        log::info!("Toggled {} in instance {}", plugin.file_name, instance.id);
        Ok(changed)
    }

    /// Moves the file to `.spa-backups/<UUID>/`; nothing is deleted.
    pub fn remove(&self, instance: &ServerInstance, plugin: &InstalledPlugin) -> Result<PathBuf> {
        let _guard = self.guard();
        let before = self.inventory.scan(instance)?;
        require_current(&before, Some(plugin))?;
        let source = paths::child(&instance.plugins_directory(), &plugin.file_name)?;
        let backup = self.backup_path(instance, &plugin.file_name)?;
        fs::rename(&source, &backup)?;
        if let Err(e) = self.inventory.save(instance, &without(&before, &plugin.file_name)) {
            fs::rename(&backup, &source)?;
            return Err(e);
        }
        log::info!("Moved removed plugin {} to {}", plugin.file_name, backup.display());
        Ok(backup)
    }

    /// Records provider identity only when the release's SHA-512 equals the file's.
    pub fn link(&self, instance: &ServerInstance, plugin: &InstalledPlugin, release: &Release) -> Result<()> {
        let _guard = self.guard();
        let before = self.inventory.scan(instance)?;
        require_current(&before, Some(plugin))?;
        if !release.hashes.get("sha512").is_some_and(|h| h.eq_ignore_ascii_case(&plugin.sha512)) {
            return fail("Identity hash mismatch");
        }
        let linked = InstalledPlugin {
            version: release.version.clone(),
            provider_id: Some(release.provider_id.clone()),
            project_id: Some(release.project_id.clone()),
            release_id: Some(release.id.clone()),
            published_at: release.published_at,
            ..plugin.clone()
        };
        let mut after = without(&before, &plugin.file_name);
        after.push(linked);
        self.inventory.save(instance, &after)
    }
}
