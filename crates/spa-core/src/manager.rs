//! Provider-neutral orchestration: URL resolution, dependency planning, updates and linking.

use crate::files::PluginFiles;
use crate::instance::ServerInstance;
use crate::inventory::{InstalledPlugin, InventoryStore};
use crate::registry::ProviderRegistry;
use crate::{fail, Result};
use spa_api::{Confidence, Project, Release};
use std::collections::BTreeMap;
use std::sync::Arc;
use url::Url;

const MAX_DEPTH: usize = 64;
const MAX_SELECTED: usize = 128;

#[derive(Debug, Clone)]
pub struct PlannedInstall {
    pub release: Release,
    pub replacing: Option<InstalledPlugin>,
    pub dependency: bool,
}

/// A dependency only available from a website; the user must handle it explicitly.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ManualDependency {
    pub required_by: String,
    pub name: String,
    pub url: Option<String>,
    pub required: bool,
}

#[derive(Debug, Clone, Default)]
pub struct InstallPlan {
    pub items: Vec<PlannedInstall>,
    pub manual: Vec<ManualDependency>,
}

impl InstallPlan {
    pub fn needs_compatibility_confirmation(&self) -> bool {
        !self.manual.is_empty() || self.items.iter().any(|i| i.release.confidence != Confidence::Verified)
    }
}

#[derive(Debug, Clone)]
pub struct Update {
    pub installed: InstalledPlugin,
    pub release: Release,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Failure {
    pub item: String,
    pub detail: String,
}

#[derive(Debug, Clone, Default)]
pub struct UpdateReport {
    pub updates: Vec<Update>,
    pub failures: Vec<Failure>,
    pub checked: usize,
}

#[derive(Debug, Clone)]
pub struct Resolved {
    pub project: Project,
    pub releases: Vec<Release>,
}

#[derive(Debug, Clone, Default)]
pub struct LinkReport {
    pub linked: usize,
    pub failures: Vec<Failure>,
}

pub struct PluginManager {
    providers: ProviderRegistry,
    inventory: Arc<InventoryStore>,
    files: Arc<PluginFiles>,
}

fn key(provider: &str, project: &str) -> String {
    format!("{provider}:{project}")
}

struct Planner<'a> {
    manager: &'a PluginManager,
    instance: &'a ServerInstance,
    optional: bool,
    installed: Vec<InstalledPlugin>,
    selected: BTreeMap<String, Release>,
    order: Vec<String>,
    plan: InstallPlan,
}

impl Planner<'_> {
    fn visit(&mut self, release: Release, dependency: bool, depth: usize) -> Result<()> {
        if depth > MAX_DEPTH || self.selected.len() > MAX_SELECTED {
            return fail("Dependency graph exceeds limit");
        }
        if release.download.is_none() {
            return fail("Release has no direct download");
        }
        if release.confidence == Confidence::Verified && !release.matches(&self.instance.target) {
            return fail("Release is incompatible with target environment");
        }
        let id = key(&release.provider_id, &release.project_id);
        if let Some(existing) = self.selected.get(&id) {
            if existing.id != release.id {
                return fail(format!("Conflicting dependency versions for {id}"));
            }
            return Ok(());
        }
        self.selected.insert(id.clone(), release.clone());
        self.order.push(id.clone());
        let matches: Vec<&InstalledPlugin> = self
            .installed
            .iter()
            .filter(|p| p.linked() && p.is_linked_to(&release.provider_id, &release.project_id))
            .collect();
        if matches.len() > 1 {
            return fail(format!("Multiple installed files linked to project {id}"));
        }
        let replacing = matches.first().map(|p| (*p).clone());
        if dependency && replacing.as_ref().is_some_and(|r| !r.enabled) {
            return fail(format!(
                "Required dependency is disabled: {}",
                replacing.as_ref().map(|r| r.file_name.as_str()).unwrap_or_default()
            ));
        }
        let already = replacing.as_ref().is_some_and(|r| r.release_id.as_deref() == Some(&release.id));
        let provider = self.manager.providers.require(&release.provider_id)?;
        for dep in &release.dependencies {
            if dep.incompatible || (!dep.required && !self.optional) {
                continue;
            }
            if dep.manual() {
                let manual = ManualDependency {
                    required_by: release.project_id.clone(),
                    name: dep.label(),
                    url: dep.external_url.clone(),
                    required: dep.required,
                };
                if !self.plan.manual.contains(&manual) {
                    self.plan.manual.push(manual);
                }
                continue;
            }
            let target = &self.instance.target;
            let next = match (&dep.project_id, &dep.version_id) {
                (project, Some(version)) => provider.release(project.as_deref(), version, target)?,
                (Some(project), None) => provider
                    .releases(project, target)?
                    .into_iter()
                    .next()
                    .ok_or_else(|| crate::Error::Message(format!("No compatible dependency release: {project}")))?,
                (None, None) => unreachable!("manual dependencies handled above"),
            };
            self.visit(next, true, depth + 1)?;
        }
        if !already {
            self.plan.items.push(PlannedInstall { release, replacing, dependency });
        }
        Ok(())
    }

    fn check_incompatible(&self) -> Result<()> {
        for release in self.selected.values() {
            for dep in release.dependencies.iter().filter(|d| d.incompatible) {
                let matches = |provider: &str, project: &str, version: &str| {
                    provider == release.provider_id
                        && dep.project_id.as_deref().is_none_or(|p| p == project)
                        && dep.version_id.as_deref().is_none_or(|v| v == version)
                };
                let installed_conflict = self.installed.iter().any(|p| {
                    p.linked()
                        && matches(
                            p.provider_id.as_deref().unwrap_or_default(),
                            p.project_id.as_deref().unwrap_or_default(),
                            p.release_id.as_deref().unwrap_or_default(),
                        )
                        && !self.selected.contains_key(&key(
                            p.provider_id.as_deref().unwrap_or_default(),
                            p.project_id.as_deref().unwrap_or_default(),
                        ))
                });
                let selected_conflict = self.selected.values().any(|r| matches(&r.provider_id, &r.project_id, &r.id));
                if installed_conflict || selected_conflict {
                    return fail(format!("Incompatible dependency declared by {}", release.project_id));
                }
            }
        }
        Ok(())
    }
}

impl PluginManager {
    pub fn new(providers: ProviderRegistry, inventory: Arc<InventoryStore>, files: Arc<PluginFiles>) -> Self {
        Self { providers, inventory, files }
    }

    pub fn resolve(&self, text: &str, instance: &ServerInstance) -> Result<Resolved> {
        let Ok(url) = Url::parse(text.trim()) else {
            return fail("Invalid project URL");
        };
        for provider in self.providers.all() {
            let Some(reference) = provider.resolve(&url) else { continue };
            let project = provider.project(&reference.project_id)?;
            let releases = match reference.version_id {
                None => provider.releases(&project.id, &instance.target)?,
                Some(version) => vec![provider.release(Some(&project.id), &version, &instance.target)?],
            };
            return Ok(Resolved { project, releases });
        }
        fail("No provider recognizes this official project URL")
    }

    /// Dependencies come before their dependents. Already-installed exact releases are skipped.
    pub fn plan(&self, instance: &ServerInstance, root: Release, include_optional: bool) -> Result<InstallPlan> {
        let mut planner = Planner {
            manager: self,
            instance,
            optional: include_optional,
            installed: self.inventory.scan(instance)?,
            selected: BTreeMap::new(),
            order: Vec::new(),
            plan: InstallPlan::default(),
        };
        planner.visit(root, false, 0)?;
        planner.check_incompatible()?;
        Ok(planner.plan)
    }

    /// Each plugin is its own rollback-safe transaction; completed dependencies are retained.
    pub fn execute(&self, instance: &ServerInstance, plan: &InstallPlan) -> Result<()> {
        for item in &plan.items {
            self.files.install(instance, &item.release, item.replacing.as_ref())?;
        }
        Ok(())
    }

    /// Updates follow release IDs and publication time, never stripped version strings.
    pub fn check_updates(&self, instance: &ServerInstance) -> Result<UpdateReport> {
        let mut report = UpdateReport::default();
        for plugin in self.inventory.scan(instance)? {
            let (Some(provider_id), Some(project_id), Some(release_id)) =
                (&plugin.provider_id, &plugin.project_id, &plugin.release_id)
            else {
                continue;
            };
            report.checked += 1;
            let latest =
                self.providers.require(provider_id).and_then(|p| Ok(p.releases(project_id, &instance.target)?));
            match latest {
                Ok(releases) => {
                    if let Some(latest) = releases.into_iter().next() {
                        if &latest.id != release_id
                            && (plugin.published_at == 0 || latest.published_at > plugin.published_at)
                        {
                            report.updates.push(Update { installed: plugin.clone(), release: latest });
                        }
                    }
                }
                Err(e) => {
                    log::error!("Update check failed for {} / {e}", plugin.file_name);
                    report.failures.push(Failure { item: plugin.file_name.clone(), detail: e.to_string() });
                }
            }
        }
        Ok(report)
    }

    pub fn link_unlinked(&self, instance: &ServerInstance) -> Result<LinkReport> {
        let mut report = LinkReport::default();
        for plugin in self.inventory.scan(instance)?.into_iter().filter(|p| !p.linked()) {
            for provider in self.providers.all() {
                let identified = provider
                    .identify_sha512(&plugin.sha512, &instance.target)
                    .map_err(crate::Error::from)
                    .and_then(|found| match found {
                        Some(release) => self.files.link(instance, &plugin, &release).map(|_| true),
                        None => Ok(false),
                    });
                match identified {
                    Ok(true) => {
                        report.linked += 1;
                        break;
                    }
                    Ok(false) => {}
                    Err(e) => {
                        log::error!("Plugin identification failed for {}: {e}", plugin.file_name);
                        report.failures.push(Failure { item: plugin.file_name.clone(), detail: e.to_string() });
                    }
                }
            }
        }
        Ok(report)
    }
}
