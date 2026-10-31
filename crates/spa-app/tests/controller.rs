//! Drives the presenter headlessly, as any frontend would, with a deterministic provider.

use spa_api::*;
use spa_app::{AppContext, AppSettings, Controller, InstanceForm, ReleasesVm, UiEvent};
use std::collections::BTreeMap;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;

#[derive(Default)]
struct Provider {
    fail_next: AtomicBool,
    searches: AtomicUsize,
}

fn project(id: usize) -> Project {
    Project {
        provider_id: "test".into(),
        id: format!("p{id}"),
        title: format!("Project {id}"),
        author: "author".into(),
        description: String::new(),
        page: format!("https://test.example/p{id}"),
        access: Access::Direct,
        tested_game_versions: vec![],
        icon: None,
    }
}

impl PluginProvider for Provider {
    fn info(&self) -> ProviderInfo {
        ProviderInfo::new("test", "Test", "https://test.example").unwrap()
    }

    fn environments(&self) -> ApiResult<Option<EnvironmentCatalog>> {
        Ok(Some(EnvironmentCatalog::new(
            vec!["paper".into(), "spigot".into(), "bukkit".into()],
            vec!["1.21.4".into()],
        )?))
    }

    fn search(&self, request: &SearchRequest) -> ApiResult<SearchPage> {
        self.searches.fetch_add(1, Ordering::SeqCst);
        if self.fail_next.swap(false, Ordering::SeqCst) {
            return Err(ApiError::Transport("offline".into()));
        }
        // Pages overlap by one project to exercise deduplication; three pages in total.
        let start = (request.page * request.size).saturating_sub(1) as usize;
        let projects = (start..start + request.size as usize).map(project).collect();
        Ok(SearchPage { projects, has_more: request.page < 2 })
    }

    fn project(&self, project_id: &str) -> ApiResult<Project> {
        Ok(project(project_id.trim_start_matches('p').parse().unwrap_or(0)))
    }

    fn releases(&self, project_id: &str, target: &TargetEnvironment) -> ApiResult<Vec<Release>> {
        Ok(vec![Release {
            provider_id: "test".into(),
            project_id: project_id.into(),
            id: "r1".into(),
            version: "1.0".into(),
            published_at: 0,
            channel: Channel::Release,
            file_name: format!("{project_id}.jar"),
            download: Some("https://test.example/file.jar".into()),
            hashes: BTreeMap::new(),
            size: 0,
            game_versions: vec![target.minecraft_version.clone()],
            loaders: vec!["paper".into()],
            dependencies: vec![],
            confidence: Confidence::Verified,
        }])
    }

    fn resolve(&self, url: &url::Url) -> Option<ProjectReference> {
        (url.host_str() == Some("test.example"))
            .then(|| ProjectReference { project_id: url.path().trim_start_matches('/').into(), version_id: None })
    }
}

#[derive(Default)]
struct Events {
    list: Mutex<Vec<UiEvent>>,
    changed: Condvar,
}

impl Events {
    fn wait<T>(&self, mut find: impl FnMut(&UiEvent) -> Option<T>) -> T {
        let mut list = self.list.lock().unwrap();
        loop {
            if let Some(position) = list.iter().position(|e| find(e).is_some()) {
                let event = list.remove(position);
                list.drain(..position);
                return find(&event).unwrap();
            }
            let (next, timeout) = self.changed.wait_timeout(list, Duration::from_secs(10)).unwrap();
            assert!(!timeout.timed_out(), "timed out; pending events: {:#?}", *next);
            list = next;
        }
    }
}

fn setup() -> (tempfile::TempDir, Controller, Arc<Events>, Arc<Provider>) {
    let dir = tempfile::tempdir().unwrap();
    let provider = Arc::new(Provider::default());
    let ctx = AppContext::open(dir.path(), vec![provider.clone()], 16 * 1024 * 1024).unwrap();
    let events = Arc::new(Events::default());
    let sink = events.clone();
    let settings = AppSettings { language: "en".into(), ..AppSettings::default() };
    let controller = Controller::new(
        ctx,
        settings,
        Arc::new(move |event| {
            sink.list.lock().unwrap().push(event);
            sink.changed.notify_all();
        }),
    );
    controller.start();
    (dir, controller, events, provider)
}

#[test]
fn second_application_instance_cannot_open_the_same_data() {
    let (dir, _controller, _events, provider) = setup();
    assert!(AppContext::open(dir.path(), vec![provider], 1024).is_err());
}

#[test]
fn create_instance_browse_paginate_retry_and_review() {
    let (dir, controller, events, provider) = setup();
    events.wait(|e| matches!(e, UiEvent::Instances { items, .. } if items.is_empty()).then_some(()));

    controller.open_new_instance();
    let catalog = events.wait(|e| match e {
        UiEvent::Catalog(c) if !c.loading => Some(c.clone()),
        _ => None,
    });
    assert_eq!(catalog.loaders, vec!["paper", "spigot", "bukkit"]);
    let defaults: Vec<_> = catalog.extras.iter().filter(|c| c.checked).map(|c| c.name.as_str()).collect();
    assert_eq!(defaults, vec!["spigot", "bukkit"], "paper defaults to its upstream loaders");
    controller.save_instance(InstanceForm {
        name: "Survival".into(),
        directory: dir.path().join("server").to_string_lossy().into(),
        loader: "paper".into(),
        version: "1.21.4".into(),
        extras: vec!["spigot".into()],
        prerelease: false,
    });
    events.wait(|e| matches!(e, UiEvent::CloseInstanceDialog).then_some(()));
    let selected = events.wait(|e| match e {
        UiEvent::Instances { items, selected } if items.len() == 1 => Some(*selected),
        _ => None,
    });
    assert_eq!(selected, 0);
    let library = events.wait(|e| match e {
        UiEvent::Library(Some(l)) => Some(l.clone()),
        _ => None,
    });
    assert!(library.empty && library.title == "Survival");
    events.wait(|e| matches!(e, UiEvent::MarketReset).then_some(()));

    // Opening Discover browses defaults without a query.
    controller.market_activated(true);
    let first = events.wait(|e| match e {
        UiEvent::MarketAppend(cards) => Some(cards.len()),
        _ => None,
    });
    assert_eq!(first, 20);
    events.wait(|e| matches!(e, UiEvent::MarketStatus(s) if s.can_load_more).then_some(()));

    // A failed continuation keeps rows and retries the same page.
    provider.fail_next.store(true, Ordering::SeqCst);
    controller.load_more();
    events.wait(|e| matches!(e, UiEvent::MarketStatus(s) if s.failed).then_some(()));
    controller.load_more();
    std::thread::sleep(Duration::from_millis(100));
    assert_eq!(provider.searches.load(Ordering::SeqCst), 2, "failed feed waits for explicit retry");
    controller.retry_market();
    let second = events.wait(|e| match e {
        UiEvent::MarketAppend(cards) => Some(cards.clone()),
        _ => None,
    });
    assert_eq!(second.len(), 19, "overlapping project is deduplicated");
    assert_eq!(second[0].key, "test:p20");
    controller.load_more();
    events.wait(|e| matches!(e, UiEvent::MarketAppend(_)).then_some(()));
    events.wait(|e| matches!(e, UiEvent::MarketStatus(s) if !s.can_load_more && !s.loading).then_some(()));
    let searches = provider.searches.load(Ordering::SeqCst);
    controller.load_more();
    std::thread::sleep(Duration::from_millis(100));
    assert_eq!(provider.searches.load(Ordering::SeqCst), searches, "exhausted feed stops");

    controller.select_project(3);
    let detail = events.wait(|e| match e {
        UiEvent::Detail(Some(d)) => Some(d.clone()),
        _ => None,
    });
    assert_eq!(detail.title, "Project 3");
    let releases = events.wait(|e| match e {
        UiEvent::Releases(ReleasesVm::Ready(r)) => Some(r.clone()),
        _ => None,
    });
    assert!(releases[0].verified && releases[0].installable);

    controller.review_install(0, false);
    let plan = events.wait(|e| match e {
        UiEvent::Plan(p) => Some(p.clone()),
        _ => None,
    });
    assert!(plan.summary.contains("p3.jar") && !plan.needs_confirmation);
    controller.cancel_plan();

    // A new query resets the feed; URL resolution produces a single exhausted project.
    controller.search("anything");
    events.wait(|e| matches!(e, UiEvent::MarketReset).then_some(()));
    controller.add_by_url("https://test.example/p7");
    let cards = events.wait(|e| match e {
        UiEvent::MarketAppend(cards) if cards.len() == 1 => Some(cards.clone()),
        _ => None,
    });
    assert_eq!(cards[0].title, "Project 7");
}

#[test]
fn unregister_requires_confirmation_and_keeps_files() {
    let (dir, controller, events, _) = setup();
    controller.open_new_instance();
    events.wait(|e| matches!(e, UiEvent::Catalog(c) if !c.loading).then_some(()));
    let server = dir.path().join("kept");
    controller.save_instance(InstanceForm {
        name: "Kept".into(),
        directory: server.to_string_lossy().into(),
        loader: "paper".into(),
        version: "1.21.4".into(),
        extras: vec![],
        prerelease: false,
    });
    events.wait(|e| matches!(e, UiEvent::Instances { items, .. } if items.len() == 1).then_some(()));
    controller.unregister_instance();
    let question = events.wait(|e| match e {
        UiEvent::Confirm(text) => Some(text.clone()),
        _ => None,
    });
    assert!(question.contains("Kept"));
    controller.confirm(true);
    events.wait(|e| matches!(e, UiEvent::Instances { items, .. } if items.is_empty()).then_some(()));
    assert!(server.join("plugins").is_dir());
}
