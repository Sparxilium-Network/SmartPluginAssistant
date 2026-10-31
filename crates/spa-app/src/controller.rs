//! Presenter owning application state. UI actions return immediately; work runs on background
//! threads and results arrive as `UiEvent`s. Workspace mutations are serialized by a busy flag;
//! discovery and release inspection use session tokens so stale completions are ignored.

use crate::artwork::{self, MemoryCache};
use crate::choices;
use crate::context::AppContext;
use crate::feed::ProjectFeed;
use crate::i18n::{self, tr, trf};
use crate::settings::AppSettings;
use crate::vm::*;
use spa_api::{Access, Channel, Confidence, EnvironmentCatalog, PluginProvider, Project, Release, TargetEnvironment};
use spa_core::instance::ServerInstance;
use spa_core::inventory::InstalledPlugin;
use spa_core::manager::{Failure, InstallPlan, PlannedInstall, Update};
use std::collections::{HashMap, HashSet};
use std::fmt::Display;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};
use uuid::Uuid;

#[derive(Default)]
struct Market {
    active: bool,
    source: usize,
    query: String,
    feed: Option<ProjectFeed>,
    session: u64,
    loading: bool,
    failed: bool,
    projects: Vec<Project>,
    selected: Option<Project>,
    releases: Vec<Release>,
    release_session: u64,
}

struct Dialog {
    editing: Option<Uuid>,
    saved: Option<TargetEnvironment>,
    catalog: Option<EnvironmentCatalog>,
}

enum Pending {
    Unregister(Uuid),
    Remove(Vec<InstalledPlugin>),
    Update(Vec<Update>),
}

#[derive(Default)]
struct State {
    instances: Vec<ServerInstance>,
    selected: Option<Uuid>,
    plugins: Vec<InstalledPlugin>,
    updates: HashMap<String, Update>,
    filter: String,
    checked: HashSet<String>,
    busy: bool,
    market: Market,
    plan: Option<(Uuid, InstallPlan)>,
    pending: Option<Pending>,
    dialog: Option<Dialog>,
    catalog_session: u64,
}

impl State {
    fn instance(&self) -> Option<ServerInstance> {
        self.selected.and_then(|id| self.instances.iter().find(|i| i.id == id).cloned())
    }
}

struct Inner {
    ctx: AppContext,
    sink: Sink,
    state: Mutex<State>,
    memory: MemoryCache,
    settings: Mutex<AppSettings>,
}

#[derive(Clone)]
pub struct Controller {
    inner: Arc<Inner>,
}

fn failures_text(failures: &[Failure]) -> Vec<String> {
    failures.iter().map(|f| format!("{}: {}", f.item, f.detail)).collect()
}

fn initial(title: &str) -> String {
    title.chars().find(|c| c.is_alphanumeric()).map(|c| c.to_uppercase().collect()).unwrap_or_else(|| tr("artwork.placeholder"))
}

fn channel_name(channel: Channel) -> String {
    tr(match channel {
        Channel::Release => "channel.release",
        Channel::Beta => "channel.beta",
        Channel::Alpha => "channel.alpha",
        Channel::Unknown => "channel.unknown",
    })
}

fn date(millis: i64) -> String {
    chrono::DateTime::from_timestamp_millis(millis)
        .map(|d| d.with_timezone(&chrono::Local).format("%Y-%m-%d").to_string())
        .unwrap_or_default()
}

fn mib(bytes: u64) -> String {
    format!("{:.1}", bytes as f64 / 1024.0 / 1024.0)
}

impl Inner {
    fn lock(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn emit(&self, event: UiEvent) {
        (self.sink)(event);
    }

    fn message(&self, text: String) {
        self.emit(UiEvent::Message { title: tr("app.title"), text, error: false });
    }

    fn fail(&self, error: impl Display) {
        log::error!("Operation failed: {error}");
        self.emit(UiEvent::Status(tr("app.failed")));
        self.emit(UiEvent::Message { title: tr("app.failed"), text: trf("app.error_detail", &[&error]), error: true });
    }

    fn report_failures(&self, failures: Vec<String>) {
        if !failures.is_empty() {
            self.emit(UiEvent::Message {
                title: tr("app.failed"),
                text: trf("library.failures", &[&failures.join("\n")]),
                error: true,
            });
        }
    }

    fn provider_name(&self, id: &str) -> String {
        self.ctx.providers.require(id).map(|p| p.info().name).unwrap_or_else(|_| id.to_string())
    }

    fn images_enabled(&self) -> bool {
        self.settings.lock().unwrap_or_else(|e| e.into_inner()).images_enabled
    }

    // ---------- instances & library ----------

    fn emit_instances(&self) {
        let state = self.lock();
        let items = state
            .instances
            .iter()
            .map(|i| InstanceVm {
                id: i.id.to_string(),
                name: i.name.clone(),
                detail: trf("app.environment", &[&i.target.loader, &i.target.minecraft_version]),
            })
            .collect();
        let selected = state.selected.and_then(|id| state.instances.iter().position(|i| i.id == id));
        self.emit(UiEvent::Instances { items, selected: selected.map_or(-1, |i| i as i32) });
    }

    fn emit_library(&self) {
        let state = self.lock();
        let Some(instance) = state.instance() else {
            drop(state);
            return self.emit(UiEvent::Library(None));
        };
        let filter = state.filter.to_lowercase();
        let plugins = state
            .plugins
            .iter()
            .filter(|p| filter.is_empty() || p.file_name.to_lowercase().contains(&filter) || p.version.to_lowercase().contains(&filter))
            .map(|p| {
                let update = state.updates.get(&p.file_name);
                PluginVm {
                    file_name: p.file_name.clone(),
                    version: if p.version.is_empty() { tr("update.none") } else { p.version.clone() },
                    source: p.provider_id.as_deref().map(|id| self.provider_name(id)).unwrap_or_else(|| tr("source.local")),
                    state: tr(if p.enabled { "state.enabled" } else { "state.disabled" }),
                    update: update.map(|u| trf("update.available", &[&u.release.version])).unwrap_or_else(|| tr("update.none")),
                    enabled: p.enabled,
                    linked: p.linked(),
                    has_update: update.is_some(),
                    checked: state.checked.contains(&p.file_name),
                }
            })
            .collect();
        let linked = state.plugins.iter().filter(|p| p.linked()).count();
        let vm = LibraryVm {
            title: instance.name.clone(),
            environment: format!(
                "{} · {}",
                trf("app.environment", &[&instance.target.loader, &instance.target.minecraft_version]),
                instance.directory
            ),
            summary: trf("app.inventory_count", &[&state.plugins.len(), &linked]),
            plugins,
            empty: state.plugins.is_empty(),
            selected_count: state.checked.len(),
        };
        drop(state);
        self.emit(UiEvent::Library(Some(vm)));
    }

    fn set_plugins(&self, plugins: Vec<InstalledPlugin>, clear_updates: bool) {
        let mut state = self.lock();
        let names: HashSet<_> = plugins.iter().map(|p| p.file_name.clone()).collect();
        state.checked.retain(|n| names.contains(n));
        if clear_updates {
            state.updates.clear();
        }
        state.plugins = plugins;
        drop(state);
        self.emit_library();
    }

    fn reload_instances(self: &Arc<Self>, select: Option<Uuid>) {
        let changed = {
            let mut state = self.lock();
            state.instances = self.ctx.instances.all();
            let previous = state.selected;
            let wanted = select.or(previous).filter(|id| state.instances.iter().any(|i| i.id == *id));
            state.selected = wanted.or_else(|| state.instances.first().map(|i| i.id));
            state.selected != previous
        };
        self.emit_instances();
        self.instance_changed(changed);
    }

    /// Re-scans the library and resets discovery for a different target.
    fn instance_changed(self: &Arc<Self>, reset: bool) {
        {
            let mut state = self.lock();
            state.plugins.clear();
            state.updates.clear();
            state.checked.clear();
            state.filter.clear();
        }
        self.emit_library();
        self.scan_library();
        if reset {
            self.reset_feed();
        }
    }

    fn scan_library(self: &Arc<Self>) {
        let Some(instance) = self.lock().instance() else { return };
        let inner = self.clone();
        std::thread::spawn(move || match inner.ctx.inventory.scan(&instance) {
            Ok(plugins) if inner.lock().selected == Some(instance.id) => inner.set_plugins(plugins, false),
            Ok(_) => {}
            Err(e) => inner.fail(e),
        });
    }

    /// Runs one workspace mutation at a time; the busy flag disables mutating controls.
    fn run<T: Send + 'static>(
        self: &Arc<Self>,
        work: impl FnOnce(&Inner) -> spa_core::Result<T> + Send + 'static,
        done: impl FnOnce(&Arc<Inner>, T) + Send + 'static,
    ) {
        {
            let mut state = self.lock();
            if state.busy {
                return;
            }
            state.busy = true;
        }
        self.emit(UiEvent::Busy(true));
        self.emit(UiEvent::Status(tr("app.working")));
        let inner = self.clone();
        std::thread::spawn(move || {
            let result = work(&inner);
            inner.lock().busy = false;
            inner.emit(UiEvent::Busy(false));
            match result {
                Ok(value) => {
                    inner.emit(UiEvent::Status(tr("app.done")));
                    done(&inner, value);
                }
                Err(e) => {
                    inner.fail(e);
                    inner.scan_library();
                }
            }
        });
    }

    /// A mutation on the selected instance followed by a rescan.
    fn mutate<T: Send + 'static>(
        self: &Arc<Self>,
        work: impl FnOnce(&Inner, &ServerInstance) -> spa_core::Result<T> + Send + 'static,
        done: impl FnOnce(&Arc<Inner>, T) + Send + 'static,
    ) {
        let Some(instance) = self.lock().instance() else { return };
        self.run(
            move |inner| {
                let value = work(inner, &instance)?;
                Ok((value, inner.ctx.inventory.scan(&instance)?, instance.id))
            },
            |inner, (value, plugins, id)| {
                if inner.lock().selected == Some(id) {
                    inner.set_plugins(plugins, true);
                }
                done(inner, value);
            },
        );
    }

    fn selection(&self) -> Vec<InstalledPlugin> {
        let state = self.lock();
        state.plugins.iter().filter(|p| state.checked.contains(&p.file_name)).cloned().collect()
    }

    // ---------- discovery ----------

    fn provider(&self, index: usize) -> Option<Arc<dyn PluginProvider>> {
        self.ctx.providers.all().get(index).cloned()
    }

    fn card(&self, project: &Project) -> CardVm {
        let artwork = project.icon.as_deref().filter(|_| self.images_enabled()).and_then(|u| self.memory.get(u));
        CardVm {
            key: format!("{}:{}", project.provider_id, project.id),
            title: project.title.clone(),
            author: project.author.clone(),
            description: project.description.split_whitespace().collect::<Vec<_>>().join(" "),
            provider: self.provider_name(&project.provider_id),
            initial: initial(&project.title),
            icon_url: project.icon.clone(),
            artwork,
        }
    }

    fn request_artwork(self: &Arc<Self>, projects: &[Project]) {
        if !self.images_enabled() {
            return;
        }
        for url in projects.iter().filter_map(|p| p.icon.clone()) {
            if self.memory.get(&url).is_some() {
                continue;
            }
            let inner = self.clone();
            let key = url.clone();
            self.ctx.images.load(
                &url,
                Box::new(move |result| match result.map_err(|e| e.to_string()).and_then(|b| artwork::decode(&b)) {
                    Ok(image) if inner.images_enabled() => {
                        inner.memory.put(&key, image.clone());
                        inner.emit(UiEvent::Artwork { url: key, image: Some(image) });
                    }
                    Ok(_) => {}
                    Err(e) => log::debug!("Artwork unavailable for {key}: {e}"),
                }),
            );
        }
    }

    fn market_status(&self) {
        let state = self.lock();
        let market = &state.market;
        let has_more = market.feed.as_ref().is_some_and(ProjectFeed::has_more);
        let count = market.projects.len();
        let text = if state.selected.is_none() {
            tr("app.choose_instance")
        } else if market.failed {
            tr("market.load_failed")
        } else if market.loading {
            tr("market.loading")
        } else if !has_more && count == 0 {
            tr("market.no_results")
        } else if !has_more {
            format!("{} · {}", trf("market.loaded", &[&count]), tr("market.end"))
        } else {
            format!("{} · {}", trf("market.loaded", &[&count]), tr("market.scroll_more"))
        };
        let vm = MarketStatusVm { text, loading: market.loading, failed: market.failed, can_load_more: has_more && !market.loading };
        drop(state);
        self.emit(UiEvent::MarketStatus(vm));
    }

    /// New feed snapshot for the submitted query, source and target; stale work is fenced off.
    fn reset_feed(self: &Arc<Self>) {
        {
            let mut state = self.lock();
            let target = state.instance().map(|i| i.target);
            let market = &mut state.market;
            market.session += 1;
            market.release_session += 1;
            market.loading = false;
            market.failed = false;
            market.projects.clear();
            market.selected = None;
            market.releases.clear();
            market.feed = match (market.active, target) {
                (true, Some(target)) => Some(ProjectFeed::new(&market.query, target)),
                _ => None,
            };
        }
        self.emit(UiEvent::MarketReset);
        self.emit(UiEvent::Detail(None));
        self.market_status();
        self.fetch_next();
    }

    fn fetch_next(self: &Arc<Self>) {
        let (request, session, provider) = {
            let mut state = self.lock();
            let source = state.market.source;
            let market = &mut state.market;
            if !market.active || market.loading || market.failed {
                return;
            }
            let Some(request) = market.feed.as_ref().and_then(ProjectFeed::request) else { return };
            let Some(provider) = self.provider(source) else { return };
            market.loading = true;
            (request, market.session, provider)
        };
        self.market_status();
        let inner = self.clone();
        std::thread::spawn(move || {
            let result = provider.search(&request);
            let added = {
                let mut state = inner.lock();
                let market = &mut state.market;
                if market.session != session {
                    return;
                }
                market.loading = false;
                match result {
                    Ok(page) => {
                        let added = market.feed.as_mut().map(|f| f.append(page)).unwrap_or_default();
                        market.projects.extend(added.iter().cloned());
                        Some(added)
                    }
                    Err(e) => {
                        log::warn!("Discovery request failed: {e}");
                        market.failed = true;
                        None
                    }
                }
            };
            if let Some(added) = added {
                let cards = added.iter().map(|p| inner.card(p)).collect();
                inner.emit(UiEvent::MarketAppend(cards));
                inner.request_artwork(&added);
            }
            inner.market_status();
        });
    }

    fn detail(&self, project: &Project) -> DetailVm {
        let card = self.card(project);
        DetailVm {
            key: card.key,
            title: card.title,
            author: card.author,
            provider: card.provider,
            description: project.description.clone(),
            page: project.page.clone(),
            initial: card.initial,
            icon_url: card.icon_url,
            artwork: card.artwork,
            external: project.access != Access::Direct,
        }
    }

    fn release_vm(&self, release: &Release) -> ReleaseVm {
        let verified = release.confidence == Confidence::Verified;
        let confidence = tr(match release.confidence {
            Confidence::Verified => "confidence.verified",
            Confidence::Declared => "confidence.declared",
            Confidence::Unknown => "confidence.unknown",
        });
        ReleaseVm {
            label: format!("{} · {}", release.version, channel_name(release.channel)),
            detail: format!("{} · {} · {}", date(release.published_at), confidence, release.file_name),
            notice: tr(if verified { "market.verified" } else { "market.compat_notice" }),
            verified,
            installable: release.download.is_some(),
        }
    }

    fn emit_releases(&self) {
        let releases = self.lock().market.releases.clone();
        let vms = releases.iter().map(|r| self.release_vm(r)).collect();
        self.emit(UiEvent::Releases(ReleasesVm::Ready(vms)));
    }

    fn load_releases(self: &Arc<Self>) {
        let (project, target, session) = {
            let mut state = self.lock();
            let target = state.instance().map(|i| i.target);
            let market = &mut state.market;
            market.release_session += 1;
            market.releases.clear();
            let (Some(project), Some(target)) = (market.selected.clone(), target) else { return };
            (project, target, market.release_session)
        };
        self.emit(UiEvent::Releases(ReleasesVm::Loading));
        let Ok(provider) = self.ctx.providers.require(&project.provider_id) else { return };
        let inner = self.clone();
        std::thread::spawn(move || {
            let result = provider.releases(&project.id, &target);
            let mut state = inner.lock();
            if state.market.release_session != session {
                return;
            }
            match result {
                Ok(releases) => {
                    state.market.releases = releases;
                    drop(state);
                    inner.emit_releases();
                }
                Err(e) => {
                    drop(state);
                    inner.emit(UiEvent::Releases(ReleasesVm::Failed(trf("market.release_failed", &[&e]))));
                }
            }
        });
    }

    fn plan_vm(&self, plan: &InstallPlan) -> PlanVm {
        let line = |i: &PlannedInstall| {
            format!(
                "{}  →  {}  ({})",
                i.release.file_name,
                i.release.version,
                tr(if i.dependency { "plan.dependency" } else { "plan.root" })
            )
        };
        let details: Vec<String> = plan.items.iter().map(line).collect();
        let manual: Vec<String> = plan
            .manual
            .iter()
            .map(|m| {
                let kind = tr(if m.required { "plan.manual_required" } else { "plan.manual_optional" });
                format!("{} ({kind}) {}", m.name, m.url.as_deref().unwrap_or_default()).trim_end().to_string()
            })
            .collect();
        PlanVm {
            summary: trf("plan.intro", &[&details.join("\n")]),
            manual: if manual.is_empty() { String::new() } else { trf("plan.manual", &[&manual.join("\n")]) },
            needs_confirmation: plan.needs_compatibility_confirmation(),
        }
    }

    fn present_plan(&self, instance: Uuid, plan: InstallPlan) {
        if plan.items.is_empty() && plan.manual.is_empty() {
            return self.message(tr("plan.empty"));
        }
        let vm = self.plan_vm(&plan);
        self.lock().plan = Some((instance, plan));
        self.emit(UiEvent::Plan(vm));
    }

    // ---------- instance dialog ----------

    fn catalog_vm(&self, result: spa_core::Result<spa_core::environment::EnvironmentResult>) -> CatalogVm {
        let mut state = self.lock();
        let Some(dialog) = state.dialog.as_mut() else {
            return CatalogVm { status: String::new(), loading: false, failed: true, loaders: vec![], versions: vec![], loader_index: -1, version_index: -1, extras: vec![] };
        };
        match result {
            Ok(result) => {
                let saved = dialog.saved.clone();
                let loaders = choices::loaders(&result.catalog, saved.as_ref());
                let versions = choices::versions(&result.catalog, saved.as_ref());
                let loader = saved.as_ref().map(|s| s.loader.clone()).unwrap_or_else(|| choices::preferred_loader(&loaders));
                let version = saved.as_ref().map(|s| s.minecraft_version.clone()).unwrap_or_else(|| versions[0].clone());
                let checked = match &saved {
                    Some(saved) => saved.extra_loaders.clone(),
                    None => choices::defaults(&loader, &loaders),
                };
                let (l, v) = (result.catalog.loaders.len(), result.catalog.game_versions.len());
                let key = if result.stale {
                    "instance.catalog_stale"
                } else if result.cached {
                    "instance.catalog_cached"
                } else {
                    "instance.catalog_ready"
                };
                dialog.catalog = Some(result.catalog);
                CatalogVm {
                    status: trf(key, &[&l, &v]),
                    loading: false,
                    failed: false,
                    loader_index: loaders.iter().position(|x| *x == loader).map_or(0, |i| i as i32),
                    version_index: versions.iter().position(|x| *x == version).map_or(0, |i| i as i32),
                    extras: Self::extras(&loaders, &loader, &checked),
                    loaders,
                    versions,
                }
            }
            Err(e) => {
                log::warn!("Environment catalog unavailable: {e}");
                CatalogVm {
                    status: format!("{}\n{e}", tr("instance.catalog_failed")),
                    loading: false,
                    failed: true,
                    loaders: vec![],
                    versions: vec![],
                    loader_index: -1,
                    version_index: -1,
                    extras: vec![],
                }
            }
        }
    }

    fn extras(loaders: &[String], loader: &str, checked: &[String]) -> Vec<Choice> {
        loaders
            .iter()
            .filter(|l| *l != loader)
            .map(|l| Choice { name: l.clone(), checked: checked.contains(l) })
            .collect()
    }

    fn load_catalog(self: &Arc<Self>, force: bool) {
        let session = {
            let mut state = self.lock();
            state.catalog_session += 1;
            state.catalog_session
        };
        self.emit(UiEvent::Catalog(CatalogVm {
            status: tr("instance.catalog_loading"),
            loading: true,
            failed: false,
            loaders: vec![],
            versions: vec![],
            loader_index: -1,
            version_index: -1,
            extras: vec![],
        }));
        let inner = self.clone();
        std::thread::spawn(move || {
            let result = inner.ctx.environments.load(force);
            let current = {
                let state = inner.lock();
                state.catalog_session == session && state.dialog.is_some()
            };
            if current {
                let vm = inner.catalog_vm(result);
                inner.emit(UiEvent::Catalog(vm));
            }
        });
    }

    fn emit_settings(&self) {
        let settings = self.settings.lock().unwrap_or_else(|e| e.into_inner()).clone();
        self.emit(UiEvent::Settings(SettingsVm {
            dark: settings.theme == "dark",
            accent: settings.accent.clone(),
            language: settings.language.clone(),
            images_enabled: settings.images_enabled,
            cache_mb: settings.image_cache_mb,
        }));
    }

    fn emit_appearance(&self) {
        let settings = self.settings.lock().unwrap_or_else(|e| e.into_inner()).clone();
        self.emit(UiEvent::Appearance { dark: settings.theme == "dark", accent: settings.accent });
    }

    fn emit_sources(&self) {
        let names = self.ctx.providers.all().iter().map(|p| p.info().name).collect();
        let selected = self.lock().market.source as i32;
        self.emit(UiEvent::MarketSources { names, selected });
    }

    fn cache_usage(self: &Arc<Self>) {
        self.emit(UiEvent::CacheUsage(tr("settings.cache_loading")));
        let inner = self.clone();
        std::thread::spawn(move || {
            let text = match inner.ctx.images.stats() {
                Ok(stats) => trf("settings.cache_usage", &[&mib(stats.bytes), &stats.files]),
                Err(e) => trf("settings.cache_failed", &[&e]),
            };
            inner.emit(UiEvent::CacheUsage(text));
        });
    }
}

impl Controller {
    /// Applies settings and loads instances. `sink` receives events on background threads.
    pub fn new(ctx: AppContext, settings: AppSettings, sink: Sink) -> Self {
        i18n::set_language(&settings.language);
        if let Err(e) = ctx.images.configure(settings.images_enabled, settings.cache_bytes()) {
            log::warn!("Cannot configure image cache: {e}");
        }
        let inner = Arc::new(Inner {
            ctx,
            sink,
            state: Mutex::new(State::default()),
            memory: MemoryCache::default(),
            settings: Mutex::new(settings),
        });
        Self { inner }
    }

    pub fn start(&self) {
        let inner = &self.inner;
        inner.emit(UiEvent::Language(i18n::language().to_string()));
        inner.emit_appearance();
        inner.emit_sources();
        inner.emit(UiEvent::Status(tr("app.ready")));
        inner.reload_instances(None);
    }

    pub fn data_dir(&self) -> PathBuf {
        self.inner.ctx.root.clone()
    }

    pub fn settings(&self) -> AppSettings {
        self.inner.settings.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    pub fn provider_ids(&self) -> Vec<String> {
        self.inner.ctx.providers.all().iter().map(|p| p.info().id).collect()
    }

    // ---------- instances ----------

    pub fn select_instance(&self, index: i32) {
        let changed = {
            let mut state = self.inner.lock();
            let Some(id) = usize::try_from(index).ok().and_then(|i| state.instances.get(i)).map(|i| i.id) else { return };
            let changed = state.selected != Some(id);
            state.selected = Some(id);
            changed
        };
        if changed {
            self.inner.emit_instances();
            self.inner.instance_changed(true);
        }
    }

    pub fn server_directory(&self) -> Option<PathBuf> {
        self.inner.lock().instance().map(|i| i.server_directory())
    }

    pub fn open_new_instance(&self) {
        self.inner.lock().dialog = Some(Dialog { editing: None, saved: None, catalog: None });
        self.inner.emit(UiEvent::InstanceDialog(InstanceDialogVm {
            editing: false,
            name: String::new(),
            directory: String::new(),
            prerelease: false,
        }));
        self.inner.load_catalog(false);
    }

    pub fn open_edit_instance(&self) {
        let Some(instance) = self.inner.lock().instance() else { return };
        self.inner.lock().dialog =
            Some(Dialog { editing: Some(instance.id), saved: Some(instance.target.clone()), catalog: None });
        self.inner.emit(UiEvent::InstanceDialog(InstanceDialogVm {
            editing: true,
            name: instance.name.clone(),
            directory: instance.directory.clone(),
            prerelease: instance.target.allow_prereleases,
        }));
        self.inner.load_catalog(false);
    }

    pub fn refresh_catalog(&self) {
        self.inner.load_catalog(true);
    }

    /// Compatible-loader checkboxes for a newly chosen server software.
    pub fn loader_changed(&self, loader: &str) {
        let state = self.inner.lock();
        let Some(catalog) = state.dialog.as_ref().and_then(|d| d.catalog.clone()) else { return };
        let saved = state.dialog.as_ref().and_then(|d| d.saved.clone());
        drop(state);
        let loaders = choices::loaders(&catalog, saved.as_ref());
        let defaults = choices::defaults(loader, &loaders);
        self.inner.emit(UiEvent::Extras(Inner::extras(&loaders, loader, &defaults)));
    }

    pub fn cancel_instance_dialog(&self) {
        let mut state = self.inner.lock();
        state.dialog = None;
        state.catalog_session += 1;
    }

    pub fn save_instance(&self, form: InstanceForm) {
        let editing = match self.inner.lock().dialog.as_ref() {
            Some(dialog) => dialog.editing,
            None => return,
        };
        if form.loader.is_empty() || form.version.is_empty() {
            return self.inner.message(tr("instance.catalog_required"));
        }
        let target = match TargetEnvironment::new(&form.loader, &form.version, form.extras.clone(), form.prerelease) {
            Ok(target) => target,
            Err(e) => return self.inner.fail(e),
        };
        self.inner.run(
            move |inner| match editing {
                Some(id) => {
                    let Some(mut instance) = inner.ctx.instances.get(id) else {
                        return spa_core::fail("Instance not found");
                    };
                    instance.name = spa_core::instance::validate_name(&form.name)?;
                    instance.target = target;
                    inner.ctx.instances.update(instance)?;
                    Ok(id)
                }
                None => {
                    let directory = form.directory.trim();
                    let custom = (!directory.is_empty()).then(|| Path::new(directory));
                    Ok(inner.ctx.instances.create(&form.name, custom, target)?.id)
                }
            },
            |inner, id| {
                inner.lock().dialog = None;
                inner.emit(UiEvent::CloseInstanceDialog);
                inner.emit(UiEvent::Status(tr("instance.saved")));
                // An edited target changes compatibility, so always rescan and reset discovery.
                inner.lock().selected = None;
                inner.reload_instances(Some(id));
            },
        );
    }

    pub fn unregister_instance(&self) {
        let Some(instance) = self.inner.lock().instance() else { return };
        self.inner.lock().pending = Some(Pending::Unregister(instance.id));
        self.inner.emit(UiEvent::Confirm(trf("instances.remove_confirm", &[&instance.name])));
    }

    /// Answer to the last `UiEvent::Confirm`.
    pub fn confirm(&self, accepted: bool) {
        let Some(pending) = self.inner.lock().pending.take() else { return };
        if !accepted {
            return;
        }
        match pending {
            Pending::Unregister(id) => self.inner.run(
                move |inner| Ok(inner.ctx.instances.unregister(id)?),
                |inner, ()| {
                    inner.lock().selected = None;
                    inner.reload_instances(None);
                },
            ),
            Pending::Remove(chosen) => self.inner.mutate(
                move |inner, instance| {
                    for plugin in &chosen {
                        inner.ctx.files.remove(instance, plugin)?;
                    }
                    Ok(())
                },
                |_, ()| {},
            ),
            Pending::Update(chosen) => {
                let Some(instance) = self.inner.lock().instance() else { return };
                self.inner.run(
                    move |inner| {
                        let mut merged = InstallPlan::default();
                        let mut keys: HashMap<String, String> = HashMap::new();
                        for update in &chosen {
                            let plan = inner.ctx.plugins.plan(&instance, update.release.clone(), false)?;
                            for item in plan.items {
                                let key = format!("{}:{}", item.release.provider_id, item.release.project_id);
                                match keys.get(&key) {
                                    Some(id) if *id != item.release.id => {
                                        return spa_core::fail("Conflicting update dependency versions")
                                    }
                                    Some(_) => {}
                                    None => {
                                        keys.insert(key, item.release.id.clone());
                                        merged.items.push(item);
                                    }
                                }
                            }
                            for manual in plan.manual {
                                if !merged.manual.contains(&manual) {
                                    merged.manual.push(manual);
                                }
                            }
                        }
                        Ok((instance.id, merged))
                    },
                    |inner, (id, plan)| inner.present_plan(id, plan),
                );
            }
        }
    }

    // ---------- library ----------

    pub fn refresh_library(&self) {
        self.inner.scan_library();
    }

    pub fn filter_library(&self, text: &str) {
        self.inner.lock().filter = text.trim().to_string();
        self.inner.emit_library();
    }

    pub fn check_plugin(&self, file_name: &str, checked: bool) {
        {
            let mut state = self.inner.lock();
            if checked {
                state.checked.insert(file_name.to_string());
            } else {
                state.checked.remove(file_name);
            }
        }
        self.inner.emit_library();
    }

    pub fn check_all(&self, checked: bool) {
        {
            let mut state = self.inner.lock();
            let filter = state.filter.to_lowercase();
            let visible: Vec<String> = state
                .plugins
                .iter()
                .filter(|p| filter.is_empty() || p.file_name.to_lowercase().contains(&filter))
                .map(|p| p.file_name.clone())
                .collect();
            for name in visible {
                if checked {
                    state.checked.insert(name);
                } else {
                    state.checked.remove(&name);
                }
            }
        }
        self.inner.emit_library();
    }

    pub fn import_jars(&self, sources: Vec<PathBuf>) {
        if sources.is_empty() {
            return;
        }
        self.inner.mutate(
            move |inner, instance| {
                let mut failures = Vec::new();
                let mut count = 0;
                for source in &sources {
                    match inner.ctx.files.import_jar(instance, source) {
                        Ok(_) => count += 1,
                        Err(e) => {
                            log::error!("Import failed for {}: {e}", source.display());
                            let name = source.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
                            failures.push(format!("{name}: {e}"));
                        }
                    }
                }
                Ok((count, failures))
            },
            |inner, (count, failures)| {
                inner.emit(UiEvent::Status(trf("library.import_result", &[&count, &failures.len()])));
                inner.report_failures(failures);
            },
        );
    }

    pub fn link_plugins(&self) {
        self.inner.mutate(
            |inner, instance| Ok(inner.ctx.plugins.link_unlinked(instance)?),
            |inner, report| {
                inner.emit(UiEvent::Status(trf("library.link_result", &[&report.linked, &report.failures.len()])));
                inner.report_failures(failures_text(&report.failures));
            },
        );
    }

    pub fn check_updates(&self) {
        let Some(instance) = self.inner.lock().instance() else { return };
        self.inner.run(
            move |inner| Ok((instance.id, inner.ctx.plugins.check_updates(&instance)?)),
            |inner, (id, report)| {
                {
                    let mut state = inner.lock();
                    if state.selected != Some(id) {
                        return;
                    }
                    state.updates = report.updates.iter().map(|u| (u.installed.file_name.clone(), u.clone())).collect();
                }
                inner.emit_library();
                inner.emit(UiEvent::Status(trf(
                    "library.update_result",
                    &[&report.checked, &report.updates.len(), &report.failures.len()],
                )));
                inner.report_failures(failures_text(&report.failures));
            },
        );
    }

    pub fn update_plugins(&self, all: bool) {
        let chosen: Vec<Update> = {
            let state = self.inner.lock();
            if all {
                state.updates.values().cloned().collect()
            } else {
                state.checked.iter().filter_map(|name| state.updates.get(name).cloned()).collect()
            }
        };
        if chosen.is_empty() {
            return self.inner.message(tr("library.no_updates"));
        }
        let count = chosen.len();
        self.inner.lock().pending = Some(Pending::Update(chosen));
        self.inner.emit(UiEvent::Confirm(trf("plan.update_confirm", &[&count])));
    }

    pub fn toggle_plugins(&self) {
        let chosen = self.inner.selection();
        if chosen.is_empty() {
            return self.inner.message(tr("library.select_first"));
        }
        self.inner.mutate(
            move |inner, instance| {
                for plugin in &chosen {
                    inner.ctx.files.toggle(instance, plugin)?;
                }
                Ok(())
            },
            |_, ()| {},
        );
    }

    pub fn remove_plugins(&self) {
        let chosen = self.inner.selection();
        if chosen.is_empty() {
            return self.inner.message(tr("library.select_first"));
        }
        let count = chosen.len();
        self.inner.lock().pending = Some(Pending::Remove(chosen));
        self.inner.emit(UiEvent::Confirm(trf("library.remove_confirm", &[&count])));
    }

    pub fn export(&self, destination: PathBuf, script: bool) {
        let Some(instance) = self.inner.lock().instance() else { return };
        self.inner.run(
            move |inner| {
                if script {
                    inner.ctx.exports.script(&instance, &destination)?;
                } else {
                    inner.ctx.exports.zip(&instance, &destination)?;
                }
                Ok(destination)
            },
            |inner, destination| inner.message(trf("library.export_done", &[&destination.display()])),
        );
    }

    // ---------- discovery ----------

    /// Opening Discover browses defaults once; hiding it cancels outstanding requests.
    pub fn market_activated(&self, active: bool) {
        let start = {
            let mut state = self.inner.lock();
            let market = &mut state.market;
            market.active = active;
            if !active {
                market.session += 1;
                market.loading = false;
            }
            active && market.feed.is_none()
        };
        if start {
            self.inner.reset_feed();
        } else if active {
            self.inner.market_status();
            self.inner.fetch_next();
        }
    }

    pub fn set_source(&self, index: i32) {
        let Ok(index) = usize::try_from(index) else { return };
        if index >= self.inner.ctx.providers.all().len() || self.inner.lock().market.source == index {
            return;
        }
        self.inner.lock().market.source = index;
        self.inner.reset_feed();
    }

    /// Submits a query snapshot; an empty query browses defaults.
    pub fn search(&self, query: &str) {
        self.inner.lock().market.query = query.trim().to_string();
        self.inner.reset_feed();
    }

    /// Requested by the frontend when the viewport nears the end of the list.
    pub fn load_more(&self) {
        self.inner.fetch_next();
    }

    pub fn retry_market(&self) {
        self.inner.lock().market.failed = false;
        self.inner.fetch_next();
    }

    pub fn select_project(&self, index: i32) {
        let project = {
            let mut state = self.inner.lock();
            let Some(project) = usize::try_from(index).ok().and_then(|i| state.market.projects.get(i)).cloned() else { return };
            if state.market.selected.as_ref() == Some(&project) {
                return;
            }
            state.market.selected = Some(project.clone());
            project
        };
        self.inner.emit(UiEvent::Detail(Some(self.inner.detail(&project))));
        self.inner.load_releases();
    }

    pub fn retry_releases(&self) {
        self.inner.load_releases();
    }

    pub fn project_page(&self) -> Option<String> {
        self.inner.lock().market.selected.as_ref().map(|p| p.page.clone())
    }

    pub fn add_by_url(&self, text: &str) {
        let Some(instance) = self.inner.lock().instance() else { return };
        let text = text.trim().to_string();
        let session = {
            let mut state = self.inner.lock();
            state.market.session += 1;
            state.market.release_session += 1;
            state.market.loading = true;
            state.market.session
        };
        self.inner.market_status();
        let inner = self.inner.clone();
        std::thread::spawn(move || {
            let result = inner.ctx.plugins.resolve(&text, &instance);
            {
                let mut state = inner.lock();
                if state.market.session != session {
                    return;
                }
                state.market.loading = false;
            }
            match result {
                Ok(resolved) => {
                    {
                        let mut state = inner.lock();
                        let market = &mut state.market;
                        market.feed = Some(ProjectFeed::exhausted(&resolved.project, instance.target.clone()));
                        market.failed = false;
                        market.projects = vec![resolved.project.clone()];
                        market.selected = Some(resolved.project.clone());
                        market.releases = resolved.releases;
                    }
                    inner.emit(UiEvent::MarketReset);
                    inner.emit(UiEvent::MarketAppend(vec![inner.card(&resolved.project)]));
                    inner.request_artwork(std::slice::from_ref(&resolved.project));
                    inner.emit(UiEvent::Detail(Some(inner.detail(&resolved.project))));
                    inner.emit_releases();
                    inner.market_status();
                }
                Err(e) => {
                    inner.market_status();
                    inner.fail(e);
                }
            }
        });
    }

    pub fn review_install(&self, release_index: i32, include_optional: bool) {
        let (instance, release) = {
            let state = self.inner.lock();
            let Some(instance) = state.instance() else { return };
            let Some(release) = usize::try_from(release_index).ok().and_then(|i| state.market.releases.get(i)).cloned() else {
                return;
            };
            (instance, release)
        };
        self.inner.run(
            move |inner| Ok((instance.id, inner.ctx.plugins.plan(&instance, release, include_optional)?)),
            |inner, (id, plan)| inner.present_plan(id, plan),
        );
    }

    pub fn execute_plan(&self) {
        let Some((id, plan)) = self.inner.lock().plan.take() else { return };
        if self.inner.lock().selected != Some(id) {
            return;
        }
        self.inner.mutate(move |inner, instance| Ok(inner.ctx.plugins.execute(instance, &plan)?), |_, ()| {});
    }

    pub fn cancel_plan(&self) {
        self.inner.lock().plan = None;
    }

    // ---------- settings ----------

    pub fn open_settings(&self) {
        self.inner.emit_settings();
        self.inner.cache_usage();
    }

    pub fn save_settings(&self, vm: SettingsVm) {
        let current = self.settings();
        let next = AppSettings {
            theme: if vm.dark { "dark" } else { "light" }.into(),
            accent: vm.accent,
            language: vm.language,
            images_enabled: vm.images_enabled,
            image_cache_mb: vm.cache_mb,
            ..current.clone()
        };
        let next = match next.validated() {
            Ok(next) => next,
            Err(e) => return self.inner.fail(e),
        };
        if let Err(e) = next.save(&self.inner.ctx.root) {
            return self.inner.fail(e);
        }
        *self.inner.settings.lock().unwrap_or_else(|e| e.into_inner()) = next.clone();
        if let Err(e) = self.inner.ctx.images.configure(next.images_enabled, next.cache_bytes()) {
            self.inner.fail(e);
        }
        self.inner.emit_appearance();
        if next.language != current.language {
            i18n::set_language(&next.language);
            self.inner.emit(UiEvent::Language(next.language.clone()));
            self.inner.emit(UiEvent::Status(tr("app.ready")));
            self.inner.emit_instances();
            self.inner.emit_library();
            self.inner.market_status();
            self.rerender_market();
        }
        if next.images_enabled != current.images_enabled {
            self.rerender_market();
        }
    }

    /// Re-emits the loaded discovery rows with current artwork and translations.
    fn rerender_market(&self) {
        let (projects, selected, releases_loaded) = {
            let state = self.inner.lock();
            (state.market.projects.clone(), state.market.selected.clone(), !state.market.releases.is_empty())
        };
        self.inner.emit(UiEvent::MarketReset);
        self.inner.emit(UiEvent::MarketAppend(projects.iter().map(|p| self.inner.card(p)).collect()));
        self.inner.request_artwork(&projects);
        if let Some(project) = selected {
            self.inner.emit(UiEvent::Detail(Some(self.inner.detail(&project))));
            if releases_loaded {
                self.inner.emit_releases();
            }
        }
        self.inner.market_status();
    }

    pub fn clear_image_cache(&self) {
        let inner = self.inner.clone();
        std::thread::spawn(move || {
            inner.memory.clear();
            match inner.ctx.images.clear() {
                Ok(stats) => inner.emit(UiEvent::CacheUsage(trf("settings.cache_cleared", &[&mib(stats.bytes), &stats.files]))),
                Err(e) => inner.emit(UiEvent::CacheUsage(trf("settings.cache_failed", &[&e]))),
            }
        });
    }

    pub fn providers_text(&self) -> String {
        let lines: Vec<String> = self
            .inner
            .ctx
            .providers
            .all()
            .iter()
            .map(|p| {
                let info = p.info();
                format!("• {} ({}) — {}", info.name, info.id, info.homepage)
            })
            .collect();
        format!("{}\n\n{}", lines.join("\n"), tr("providers.hint"))
    }

    pub fn is_busy(&self) -> bool {
        self.inner.lock().busy
    }

    /// Persists the window size; call once when the window closes.
    pub fn shutdown(&self, width: f64, height: f64) {
        let mut settings = self.settings();
        settings.width = width;
        settings.height = height;
        if let Ok(settings) = settings.validated() {
            if let Err(e) = settings.save(&self.inner.ctx.root) {
                log::error!("Cannot save window settings: {e}");
            }
        }
    }
}
