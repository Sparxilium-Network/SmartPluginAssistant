mod common;

use common::*;
use spa_api::*;
use spa_core::db::Database;
use spa_core::download::DownloadService;
use spa_core::environment::EnvironmentService;
use spa_core::export::{shell, ExportService};
use spa_core::files::{PluginFiles, BACKUP_DIRECTORY};
use spa_core::image_cache::ImageCache;
use spa_core::instance::{InstanceStore, ServerInstance};
use spa_core::inventory::InventoryStore;
use spa_core::manager::PluginManager;
use spa_core::registry::ProviderRegistry;
use std::fs;
use std::io::Read;
use std::sync::atomic::Ordering;
use std::sync::{mpsc, Arc};
use std::time::Duration;

fn open_db(root: &std::path::Path) -> Arc<Database> {
    Arc::new(Database::open(root).unwrap())
}

struct Fixture {
    _dir: tempfile::TempDir,
    root: std::path::PathBuf,
    db: Arc<Database>,
    server: Server,
    provider: Arc<FakeProvider>,
    inventory: Arc<InventoryStore>,
    files: Arc<PluginFiles>,
    manager: PluginManager,
    exports: ExportService,
    instance: ServerInstance,
}

fn fixture() -> Fixture {
    let dir = temp_dir();
    let root = dir.path().join("data");
    let provider = Arc::new(FakeProvider::default());
    let registry = ProviderRegistry::new(vec![provider.clone()]).unwrap();
    let db = open_db(&root);
    let inventory = Arc::new(InventoryStore::open(db.clone()));
    let files = Arc::new(PluginFiles::new(inventory.clone(), DownloadService::new(Duration::from_secs(10))));
    let manager = PluginManager::new(registry.clone(), inventory.clone(), files.clone());
    let exports = ExportService::new(inventory.clone(), registry);
    let instance = InstanceStore::open(db.clone(), &root)
        .unwrap()
        .create("Survival", Some(&dir.path().join("server")), target())
        .unwrap();
    Fixture { _dir: dir, root, db, server: Server::start(), provider, inventory, files, manager, exports, instance }
}

impl Fixture {
    /// Serves the JAR and registers its release with the fake provider.
    fn publish(&self, project: &str, id: &str, published: i64) -> Release {
        let bytes = plugin_jar(project, id);
        let url = self.server.file(&format!("/{project}/{id}.jar"), &bytes);
        let release = release(project, id, published, Some(url), &bytes);
        self.provider.add(release.clone());
        release
    }

    fn plugins(&self) -> Vec<String> {
        let mut names: Vec<_> = fs::read_dir(self.instance.plugins_directory())
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        names.sort();
        names
    }

    fn scan(&self) -> Vec<spa_core::inventory::InstalledPlugin> {
        self.inventory.scan(&self.instance).unwrap()
    }
}

// ---------- storage ----------

#[test]
fn instances_persist_and_unregister_keeps_files() {
    let dir = temp_dir();
    let db = open_db(dir.path());
    let store = InstanceStore::open(db.clone(), dir.path()).unwrap();
    let managed = store.create("Lobby", None, target()).unwrap();
    let external = store.create("External", Some(&dir.path().join("ext")), target()).unwrap();
    assert!(managed.directory.contains(&managed.id.to_string()));
    assert!(store.create("Again", Some(&dir.path().join("EXT")), target()).is_err());
    fs::write(external.plugins_directory().join("keep.jar"), b"x").unwrap();
    store.unregister(external.id).unwrap();
    assert!(external.plugins_directory().join("keep.jar").exists());
    drop(store);
    drop(db);
    let reopened = InstanceStore::open(open_db(dir.path()), dir.path()).unwrap();
    assert_eq!(reopened.all(), vec![managed.clone()]);
    let mut renamed = managed.clone();
    renamed.name = "Hub".into();
    reopened.update(renamed).unwrap();
    let mut moved = reopened.all()[0].clone();
    moved.directory = dir.path().join("other").to_string_lossy().into();
    assert!(reopened.update(moved).is_err());
    assert!(reopened.create("  ", None, target()).is_err());
}

#[test]
fn corrupt_legacy_index_and_database_are_preserved() {
    let dir = temp_dir();
    fs::write(dir.path().join("instances.json"), b"{broken").unwrap();
    assert!(Database::open(dir.path()).is_err(), "nothing is half-imported");
    assert_eq!(fs::read(dir.path().join("instances.json")).unwrap(), b"{broken");

    let damaged = temp_dir();
    let garbage = vec![0x5a; 8192];
    fs::write(damaged.path().join(spa_core::db::FILE_NAME), &garbage).unwrap();
    assert!(Database::open(damaged.path()).is_err());
    assert_eq!(fs::read(damaged.path().join(spa_core::db::FILE_NAME)).unwrap(), garbage);
}

#[test]
fn sol_json_files_are_imported_once_and_left_untouched() {
    let dir = temp_dir();
    let id = "7d444840-9dc0-11d1-b245-5ffdce74fad2";
    let server = dir.path().join("srv").to_string_lossy().replace('\\', "\\\\");
    let index = format!(
        r#"{{"schema":1,"instances":[{{"id":"{id}","name":"Sol","directory":"{server}","target":{{"loader":"paper","minecraftVersion":"1.21.4","extraLoaders":["spigot"],"allowPrereleases":false}},"createdAt":1}}]}}"#
    );
    fs::write(dir.path().join("instances.json"), &index).unwrap();
    fs::create_dir(dir.path().join("inventory")).unwrap();
    fs::write(
        dir.path().join(format!("inventory/{id}.json")),
        r#"{"schema":1,"plugins":[{"fileName":"a.jar","sha512":"ab","size":3,"version":"1","providerId":"modrinth","projectId":"P","releaseId":"R","publishedAt":5,"enabled":true}]}"#,
    )
    .unwrap();
    fs::write(dir.path().join("settings.json"), r#"{"language":"en","width":1300.0,"theme":"light"}"#).unwrap();
    fs::write(
        dir.path().join("environments.json"),
        r#"{"schema":1,"fetchedAt":7,"catalog":{"loaders":["paper"],"gameVersions":["1.21.4"]},"sources":["Modrinth"]}"#,
    )
    .unwrap();

    let db = open_db(dir.path());
    assert!(db.info().imported_legacy);
    let store = InstanceStore::open(db.clone(), dir.path()).unwrap();
    let instance = store.all().remove(0);
    assert_eq!(instance.target.extra_loaders, vec!["spigot"]);
    let records = InventoryStore::open(db.clone()).records(&instance).unwrap();
    assert_eq!((records[0].file_name.as_str(), records[0].release_id.as_deref()), ("a.jar", Some("R")));
    assert!(db.settings().unwrap().contains(&("language".to_string(), "\"en\"".to_string())));
    assert_eq!(fs::read_to_string(dir.path().join("instances.json")).unwrap(), index);

    // Later launches never import again, even if the JSON changes.
    drop(store);
    drop(db);
    fs::write(dir.path().join("instances.json"), r#"{"schema":1,"instances":[]}"#).unwrap();
    let db = open_db(dir.path());
    assert!(!db.info().imported_legacy);
    assert_eq!(InstanceStore::open(db, dir.path()).unwrap().all().len(), 1);
}

#[test]
fn scan_binds_identity_to_hash() {
    let f = fixture();
    let v1 = f.publish("alpha", "a1", 10);
    f.files.install(&f.instance, &v1, None).unwrap();
    assert!(f.scan()[0].linked());
    let dir = f.instance.plugins_directory();
    fs::rename(dir.join("alpha-a1.jar"), dir.join("renamed.jar")).unwrap();
    let renamed = f.scan();
    assert_eq!(renamed[0].file_name, "renamed.jar");
    assert!(renamed[0].linked(), "rename keeps identity for unique hash");
    fs::write(dir.join("renamed.jar"), plugin_jar("alpha", "changed")).unwrap();
    let changed = f.scan();
    assert!(!changed[0].linked(), "changed content must not trust old metadata");
    assert_eq!(changed[0].version, "changed");
    assert!(changed[0].updated_at > 0, "unknown files fall back to their modification time");
}

// ---------- downloads ----------

#[test]
fn download_rejects_truncation_checksum_and_html() {
    let f = fixture();
    let staging = f.root.join("staging");
    let bytes = plugin_jar("x", "1");
    let mut truncated =
        format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", bytes.len() + 50).into_bytes();
    truncated.extend_from_slice(&bytes);
    let mut r = release("x", "1", 0, Some(f.server.raw("/t.jar", truncated)), &bytes);
    r.size = 0;
    let service = DownloadService::new(Duration::from_secs(10));
    assert!(service.stage(&r, &staging, None).is_err());

    r.download = Some(f.server.file("/ok.jar", &bytes));
    r.hashes.insert("sha512".into(), "00".repeat(64));
    assert!(service.stage(&r, &staging, None).unwrap_err().to_string().contains("checksum"));

    let html = b"<!doctype html><html>login</html>";
    let mut h = release("x", "1", 0, Some(f.server.file("/h.jar", html)), html);
    h.size = html.len() as u64;
    assert!(service.stage(&h, &staging, None).is_err());

    let mut redirect = release(
        "x",
        "1",
        0,
        Some(f.server.raw(
            "/r",
            b"HTTP/1.1 302 Found\r\nLocation: /ok.jar\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_vec(),
        )),
        &bytes,
    );
    redirect.hashes.insert("sha512".into(), sha512(&bytes));
    let staged = service.stage(&redirect, &staging, None).unwrap();
    assert_eq!(fs::read(&staged).unwrap(), bytes);
    drop(staged);
    assert_eq!(fs::read_dir(&staging).unwrap().count(), 0, "temporary files are cleaned up");
}

// ---------- transactions ----------

#[test]
fn install_update_preserves_disabled_state_and_backups() {
    let f = fixture();
    let v1 = f.publish("alpha", "a1", 10);
    let installed = f.files.install(&f.instance, &v1, None).unwrap();
    assert_eq!(installed.version, "a1");
    assert!(f.files.install(&f.instance, &v1, None).is_err(), "destination exists");
    let disabled = f.files.toggle(&f.instance, &f.scan()[0]).unwrap();
    assert_eq!(disabled.file_name, "alpha-a1.jar.disabled");
    let v2 = f.publish("alpha", "a2", 20);
    let updated = f.files.install(&f.instance, &v2, Some(&disabled)).unwrap();
    assert!(!updated.enabled);
    assert_eq!(f.plugins(), vec!["alpha-a2.jar.disabled"]);
    let backups = f.instance.server_directory().join(BACKUP_DIRECTORY);
    assert_eq!(fs::read_dir(backups).unwrap().count(), 1);
}

#[test]
fn stale_scan_and_failed_metadata_roll_back() {
    let f = fixture();
    let v1 = f.publish("alpha", "a1", 10);
    let installed = f.files.install(&f.instance, &v1, None).unwrap();
    let mut stale = installed.clone();
    stale.sha512 = "0".repeat(128);
    assert!(f.files.remove(&f.instance, &stale).is_err());

    // Keep the inventory readable but make every metadata write fail.
    f.db.write(|t| {
        t.execute_batch(
            "CREATE TRIGGER reject_plugins BEFORE DELETE ON plugins BEGIN SELECT RAISE(ABORT, 'injected'); END;",
        )?;
        Ok(())
    })
    .unwrap();
    let v2 = f.publish("alpha", "a2", 20);
    let current = f.scan().remove(0);
    assert!(f.files.install(&f.instance, &v2, Some(&current)).is_err());
    assert_eq!(f.plugins(), vec!["alpha-a1.jar"], "original restored after failure");
    assert!(f.files.toggle(&f.instance, &current).is_err());
    assert_eq!(f.plugins(), vec!["alpha-a1.jar"]);
    assert!(f.files.remove(&f.instance, &current).is_err());
    assert_eq!(f.plugins(), vec!["alpha-a1.jar"]);
}

#[test]
fn import_remove_and_conflicts() {
    let f = fixture();
    let source = f.root.join("Local.jar");
    fs::write(&source, plugin_jar("Local", "3.1")).unwrap();
    let imported = f.files.import_jar(&f.instance, &source).unwrap();
    assert_eq!((imported.version.as_str(), imported.linked()), ("3.1", false));
    assert!(f.files.import_jar(&f.instance, &source).is_err(), "conflict");
    let fake = f.root.join("fake.jar");
    fs::write(&fake, b"not a zip").unwrap();
    assert!(f.files.import_jar(&f.instance, &fake).is_err());
    let backup = f.files.remove(&f.instance, &imported).unwrap();
    assert!(backup.exists() && f.plugins().is_empty());
    assert!(f.scan().is_empty());
}

#[test]
fn link_requires_exact_hash() {
    let f = fixture();
    let bytes = plugin_jar("Local", "3.1");
    let source = f.root.join("Local.jar");
    fs::write(&source, &bytes).unwrap();
    f.files.import_jar(&f.instance, &source).unwrap();
    let mut wrong = release("local", "l1", 5, Some("https://x.invalid/a.jar".into()), b"other");
    f.provider.identities.lock().unwrap().insert(sha512(&bytes), wrong.clone());
    let report = f.manager.link_unlinked(&f.instance).unwrap();
    assert_eq!((report.linked, report.failures.len()), (0, 1));
    wrong.hashes.insert("sha512".into(), sha512(&bytes));
    f.provider.identities.lock().unwrap().insert(sha512(&bytes), wrong);
    assert_eq!(f.manager.link_unlinked(&f.instance).unwrap().linked, 1);
    assert_eq!(f.scan()[0].release_id.as_deref(), Some("l1"));
}

// ---------- planning ----------

#[test]
fn plan_orders_required_dependencies_and_executes() {
    let f = fixture();
    let lib = f.publish("lib", "l1", 5);
    f.publish("core", "c1", 5);
    let mut root = requires(f.publish("app", "p1", 5), "core");
    root.dependencies.push(Dependency::new(Some("lib".into()), None, false, false).unwrap());
    let plan = f.manager.plan(&f.instance, root.clone(), false).unwrap();
    let ids: Vec<_> = plan.items.iter().map(|i| i.release.project_id.as_str()).collect();
    assert_eq!(ids, vec!["core", "app"]);
    assert!(!plan.needs_compatibility_confirmation());
    let with_optional = f.manager.plan(&f.instance, root, true).unwrap();
    assert_eq!(with_optional.items.len(), 3);
    f.manager.execute(&f.instance, &with_optional).unwrap();
    assert_eq!(f.plugins().len(), 3);
    let again = f.manager.plan(&f.instance, lib, false).unwrap();
    assert!(again.items.is_empty(), "exact installed release is skipped");
}

#[test]
fn plan_rejects_conflicts_cycles_and_disabled_dependencies() {
    let f = fixture();
    // Cycle a -> b -> a terminates with both selected.
    let a = requires(f.publish("a", "a1", 5), "b");
    let b = requires(
        release("b", "b1", 5, Some(f.server.file("/b.jar", &plugin_jar("b", "1"))), &plugin_jar("b", "1")),
        "a",
    );
    f.provider.add(b);
    f.provider.releases.lock().unwrap().insert("a".into(), vec![a.clone()]);
    assert_eq!(f.manager.plan(&f.instance, a, false).unwrap().items.len(), 2);

    // Pinned conflicting versions.
    let d1 = f.publish("dep", "d1", 5);
    let d2 = f.publish("dep", "d2", 6);
    let mut x = f.publish("x", "x1", 5);
    x.dependencies.push(Dependency::new(Some("dep".into()), Some(d1.id.clone()), true, false).unwrap());
    let mut y = f.publish("y", "y1", 5);
    y.dependencies.push(Dependency::new(Some("dep".into()), Some(d2.id.clone()), true, false).unwrap());
    f.provider.add(y.clone());
    x.dependencies.push(Dependency::new(Some("y".into()), None, true, false).unwrap());
    assert!(f.manager.plan(&f.instance, x, false).unwrap_err().to_string().contains("Conflicting"));

    // Declared incompatibility with an installed plugin.
    f.files.install(&f.instance, &d2, None).unwrap();
    let mut z = f.publish("z", "z1", 5);
    z.dependencies.push(Dependency::new(Some("dep".into()), None, false, true).unwrap());
    assert!(f.manager.plan(&f.instance, z, false).unwrap_err().to_string().contains("Incompatible"));

    // Disabled required dependency is never silently enabled.
    let installed = f.scan().into_iter().find(|p| p.project_id.as_deref() == Some("dep")).unwrap();
    f.files.toggle(&f.instance, &installed).unwrap();
    let needs = requires(f.publish("needs", "n1", 5), "dep");
    assert!(f.manager.plan(&f.instance, needs, false).unwrap_err().to_string().contains("disabled"));

    // Missing compatible dependency.
    let orphan = requires(f.publish("orphan", "o1", 5), "missing");
    assert!(f.manager.plan(&f.instance, orphan, false).is_err());
}

#[test]
fn manual_and_unverified_dependencies_need_confirmation() {
    let f = fixture();
    let mut root = f.publish("web", "w1", 5);
    root.dependencies.push(
        Dependency::named(None, None, Some("Vault".into()), Some("https://example.invalid/vault".into()), true, false)
            .unwrap(),
    );
    let plan = f.manager.plan(&f.instance, root, false).unwrap();
    assert_eq!(plan.manual.len(), 1);
    assert!(plan.needs_compatibility_confirmation());
    let mut declared = f.publish("declared", "d1", 5);
    declared.confidence = Confidence::Declared;
    declared.game_versions = vec!["1.20".into()];
    assert!(f.manager.plan(&f.instance, declared, false).unwrap().needs_compatibility_confirmation());
    let mut wrong = f.publish("wrong", "w1", 5);
    wrong.game_versions = vec!["1.8".into()];
    assert!(f.manager.plan(&f.instance, wrong, false).is_err(), "verified incompatible release rejected");
}

// ---------- updates ----------

#[test]
fn updates_follow_release_identity_and_report_failures() {
    let f = fixture();
    let old = f.publish("alpha", "a1", 10);
    f.files.install(&f.instance, &old, None).unwrap();
    let other = f.publish("beta", "b1", 10);
    f.files.install(&f.instance, &other, None).unwrap();
    assert!(f.scan().iter().all(|p| p.updated_at > 0 && p.checked_at == 0), "installs record their time");
    let report = f.manager.check_updates(&f.instance).unwrap();
    assert_eq!((report.checked, report.updates.len()), (2, 0));
    assert!(report.plugins.iter().all(|p| p.checked_at > 0));
    let stored = f.scan();
    assert!(stored.iter().all(|p| p.checked_at > 0), "check time is persisted");
    f.publish("alpha", "a2", 20);
    let mut downgrade = release("beta", "b0", 5, Some("https://x.invalid/b0.jar".into()), b"b0");
    downgrade.file_name = "b0.jar".into();
    f.provider.add(downgrade);
    let report = f.manager.check_updates(&f.instance).unwrap();
    assert_eq!(report.updates.len(), 1);
    assert_eq!(report.updates[0].release.id, "a2");
    let beta_checked = report.plugins.iter().find(|p| p.project_id.as_deref() == Some("beta")).unwrap().checked_at;
    f.provider.failing.lock().unwrap().push("beta".into());
    let report = f.manager.check_updates(&f.instance).unwrap();
    assert_eq!(report.failures.len(), 1);
    assert_eq!(report.failures[0].detail, "Outage");
    let beta = report.plugins.iter().find(|p| p.project_id.as_deref() == Some("beta")).unwrap();
    assert_eq!(beta.checked_at, beta_checked, "a failed check keeps the last successful time");
}

#[test]
fn resolve_uses_provider_url_recognition() {
    let f = fixture();
    f.publish("alpha", "a1", 10);
    let resolved = f.manager.resolve("https://fake.example/alpha", &f.instance).unwrap();
    assert_eq!(resolved.releases.len(), 1);
    assert!(f.manager.resolve("https://unknown.example/alpha", &f.instance).is_err());
    assert!(f.manager.resolve("not a url", &f.instance).is_err());
}

// ---------- export ----------

#[test]
fn exports_contain_only_plugins_and_quoted_pinned_script() {
    let f = fixture();
    let v1 = f.publish("alpha", "a1", 10);
    f.files.install(&f.instance, &v1, None).unwrap();
    let local = f.root.join("it's.jar");
    fs::write(&local, plugin_jar("local", "1")).unwrap();
    f.files.import_jar(&f.instance, &local).unwrap();
    fs::write(f.instance.server_directory().join("server.properties"), "secret").unwrap();

    let zip_path = f.root.join("out/plugins.zip");
    f.exports.zip(&f.instance, &zip_path).unwrap();
    let mut archive = zip::ZipArchive::new(fs::File::open(&zip_path).unwrap()).unwrap();
    let mut names: Vec<_> = archive.file_names().map(str::to_string).collect();
    names.sort();
    assert_eq!(names, vec!["manifest.json", "plugins/alpha-a1.jar", "plugins/it's.jar"]);
    let mut manifest = String::new();
    archive.by_name("manifest.json").unwrap().read_to_string(&mut manifest).unwrap();
    assert!(manifest.contains("\"minecraftVersion\": \"1.21.4\""));

    let script_path = f.root.join("out/deploy.sh");
    f.exports.script(&f.instance, &script_path).unwrap();
    let script = fs::read_to_string(script_path).unwrap();
    assert!(script.contains(&shell("Manual upload required: it's.jar")));
    assert!(script.contains(&format!(
        "if ! download 'alpha-a1.jar' '{}' '{}'",
        v1.download.unwrap(),
        v1.hashes["sha512"]
    )));
    assert_eq!(shell("a'b"), "'a'\"'\"'b'");
}

// ---------- environments ----------

#[test]
fn environment_cache_reuse_stale_fallback_and_no_defaults() {
    let dir = temp_dir();
    let provider = Arc::new(FakeProvider::default());
    let registry = ProviderRegistry::new(vec![provider.clone()]).unwrap();
    let now = Arc::new(std::sync::atomic::AtomicI64::new(1_000));
    let clock = now.clone();
    let service = EnvironmentService::with_clock(
        registry.clone(),
        open_db(dir.path()),
        Arc::new(move || clock.load(Ordering::SeqCst)),
    );
    *provider.catalog.lock().unwrap() = Some(Err(ApiError::Transport("offline".into())));
    assert!(service.load(false).is_err(), "first launch never invents defaults");

    let catalog = EnvironmentCatalog::new(vec!["paper".into()], vec!["1.21.4".into(), "1.21.3".into()]).unwrap();
    *provider.catalog.lock().unwrap() = Some(Ok(catalog.clone()));
    let fresh = service.load(false).unwrap();
    assert!(!fresh.cached && fresh.catalog == catalog);
    let calls = provider.calls.load(Ordering::SeqCst);
    let reused = service.load(false).unwrap();
    assert!(reused.cached && !reused.stale);
    assert_eq!(provider.calls.load(Ordering::SeqCst), calls);

    now.store(1_000 + 7 * 3600 * 1000, Ordering::SeqCst);
    *provider.catalog.lock().unwrap() = Some(Err(ApiError::Transport("offline".into())));
    let stale = service.load(false).unwrap();
    assert!(stale.stale && stale.catalog == catalog && stale.failures.len() == 1);

    // Persisted across restarts.
    drop(service);
    let restarted = EnvironmentService::with_clock(registry, open_db(dir.path()), Arc::new(|| 2_000));
    assert!(restarted.load(false).unwrap().cached);
}

// ---------- images ----------

fn png() -> Vec<u8> {
    let mut bytes = vec![137, b'P', b'N', b'G', 13, 10, 26, 10];
    bytes.extend_from_slice(&[0; 32]);
    bytes
}

fn load(cache: &ImageCache, url: &str) -> Result<Arc<Vec<u8>>, String> {
    let (tx, rx) = mpsc::channel();
    cache.load(url, Box::new(move |r| tx.send(r).unwrap()));
    rx.recv_timeout(Duration::from_secs(10)).unwrap()
}

#[test]
fn image_cache_reuses_coalesces_and_fences_cleanup() {
    let dir = temp_dir();
    let server = Server::start();
    let url = server.delayed("/icon.png", ok("image/png", &png()), Duration::from_millis(300));
    let cache = ImageCache::new(dir.path(), 1024 * 1024).unwrap();
    let (tx, rx) = mpsc::channel();
    for _ in 0..3 {
        let tx = tx.clone();
        cache.load(&url, Box::new(move |r| tx.send(r.is_ok()).unwrap()));
    }
    for _ in 0..3 {
        assert!(rx.recv_timeout(Duration::from_secs(10)).unwrap());
    }
    assert_eq!(server.hits("/icon.png"), 1, "concurrent requests are coalesced");
    assert_eq!(cache.stats().unwrap().files, 1);

    let second = ImageCache::new(dir.path(), 1024 * 1024).unwrap();
    assert_eq!(*load(&second, &url).unwrap(), png());
    assert_eq!(server.hits("/icon.png"), 1, "disk hit across cache instances");

    // Cleanup during an in-flight request must not repopulate the cache.
    let slow = server.delayed("/slow.png", ok("image/png", &png()), Duration::from_millis(500));
    let (tx, rx) = mpsc::channel();
    cache.load(&slow, Box::new(move |r| tx.send(r.is_ok()).unwrap()));
    std::thread::sleep(Duration::from_millis(100));
    assert_eq!(cache.clear().unwrap().files, 1);
    assert!(rx.recv_timeout(Duration::from_secs(10)).unwrap());
    assert_eq!(cache.stats().unwrap().files, 0);

    fs::write(dir.path().join("cache/images/keep.txt"), b"not owned").unwrap();
    cache.clear().unwrap();
    assert!(dir.path().join("cache/images/keep.txt").exists(), "only owned files are deleted");

    let html = server.raw("/page.png", ok("text/html", b"<html>not an image</html>"));
    assert!(load(&cache, &html).is_err());
    cache.configure(false, 1024 * 1024).unwrap();
    assert!(load(&cache, &url).is_err(), "disabled cache issues no requests");
}

#[test]
fn image_cache_evicts_least_recent_entries() {
    let dir = temp_dir();
    let server = Server::start();
    let cache = ImageCache::new(dir.path(), 100).unwrap();
    for i in 0..3 {
        let mut bytes = png();
        bytes.push(i);
        let url = server.raw(&format!("/{i}.png"), ok("image/png", &bytes));
        load(&cache, &url).unwrap();
        std::thread::sleep(Duration::from_millis(20));
    }
    let stats = cache.stats().unwrap();
    assert!(stats.bytes <= 100 && stats.files == 2, "{stats:?}");
}
