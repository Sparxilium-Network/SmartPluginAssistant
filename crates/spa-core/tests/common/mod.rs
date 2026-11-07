#![allow(dead_code)]
//! Deterministic local fixtures: a raw loopback HTTP server, JAR builder and fake provider.

use spa_api::*;
use std::collections::{BTreeMap, HashMap};
use std::io::{BufRead, BufReader, Write};
use std::net::TcpListener;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// Temporary directory with no symbolic links in its path. On macOS the default temporary
/// directory sits under `/var`, a link to `/private/var`, which the path checks reject.
pub fn temp_dir() -> tempfile::TempDir {
    let base = std::env::temp_dir();
    #[cfg(unix)]
    let base = base.canonicalize().unwrap();
    tempfile::tempdir_in(base).unwrap()
}

#[derive(Clone)]
pub struct Route {
    pub raw: Vec<u8>,
    pub delay: Duration,
}

#[derive(Clone, Default)]
pub struct Server {
    pub base: String,
    routes: Arc<Mutex<HashMap<String, Route>>>,
    hits: Arc<Mutex<HashMap<String, usize>>>,
}

pub fn ok(content_type: &str, body: &[u8]) -> Vec<u8> {
    let mut raw = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        body.len()
    )
    .into_bytes();
    raw.extend_from_slice(body);
    raw
}

impl Server {
    pub fn start() -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let server = Server {
            base: format!("http://127.0.0.1:{}", listener.local_addr().unwrap().port()),
            ..Default::default()
        };
        let (routes, hits) = (server.routes.clone(), server.hits.clone());
        std::thread::spawn(move || {
            for stream in listener.incoming().flatten() {
                let (routes, hits) = (routes.clone(), hits.clone());
                std::thread::spawn(move || {
                    let mut reader = BufReader::new(stream.try_clone().unwrap());
                    let mut line = String::new();
                    reader.read_line(&mut line).unwrap_or(0);
                    let path = line.split_whitespace().nth(1).unwrap_or("/").to_string();
                    loop {
                        let mut header = String::new();
                        if reader.read_line(&mut header).unwrap_or(0) == 0 || header == "\r\n" {
                            break;
                        }
                    }
                    *hits.lock().unwrap().entry(path.clone()).or_default() += 1;
                    let route = routes.lock().unwrap().get(&path).cloned();
                    let mut stream = stream;
                    match route {
                        Some(route) => {
                            std::thread::sleep(route.delay);
                            let _ = stream.write_all(&route.raw);
                        }
                        None => {
                            let _ = stream
                                .write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                        }
                    }
                });
            }
        });
        server
    }

    pub fn raw(&self, path: &str, raw: Vec<u8>) -> String {
        self.routes.lock().unwrap().insert(path.into(), Route { raw, delay: Duration::ZERO });
        format!("{}{path}", self.base)
    }

    pub fn delayed(&self, path: &str, raw: Vec<u8>, delay: Duration) -> String {
        self.routes.lock().unwrap().insert(path.into(), Route { raw, delay });
        format!("{}{path}", self.base)
    }

    pub fn file(&self, path: &str, body: &[u8]) -> String {
        self.raw(path, ok("application/java-archive", body))
    }

    pub fn hits(&self, path: &str) -> usize {
        self.hits.lock().unwrap().get(path).copied().unwrap_or(0)
    }
}

pub fn jar(entries: &[(&str, &str)]) -> Vec<u8> {
    let mut cursor = std::io::Cursor::new(Vec::new());
    {
        let mut zip = zip::ZipWriter::new(&mut cursor);
        for (name, body) in entries {
            zip.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
            zip.write_all(body.as_bytes()).unwrap();
        }
        zip.finish().unwrap();
    }
    cursor.into_inner()
}

pub fn plugin_jar(name: &str, version: &str) -> Vec<u8> {
    jar(&[("plugin.yml", &format!("name: {name}\nversion: '{version}'\nmain: a.B\n"))])
}

pub fn sha512(bytes: &[u8]) -> String {
    use sha2::Digest;
    hex::encode(sha2::Sha512::digest(bytes))
}

pub fn target() -> TargetEnvironment {
    TargetEnvironment::new("paper", "1.21.4", vec!["spigot".to_string()], false).unwrap()
}

pub fn release(project: &str, id: &str, published: i64, download: Option<String>, bytes: &[u8]) -> Release {
    let mut hashes = BTreeMap::new();
    hashes.insert("sha512".to_string(), sha512(bytes));
    Release {
        provider_id: "fake".into(),
        project_id: project.into(),
        id: id.into(),
        version: id.into(),
        published_at: published,
        channel: Channel::Release,
        file_name: format!("{project}-{id}.jar"),
        download,
        hashes,
        size: bytes.len() as u64,
        game_versions: vec!["1.21.4".into()],
        loaders: vec!["paper".into()],
        dependencies: vec![],
        confidence: Confidence::Verified,
    }
}

pub fn requires(mut release: Release, project: &str) -> Release {
    release.dependencies.push(Dependency::new(Some(project.into()), None, true, false).unwrap());
    release
}

/// Releases per project, newest first. Failing projects return a transport error.
#[derive(Default)]
pub struct FakeProvider {
    pub releases: Mutex<HashMap<String, Vec<Release>>>,
    pub failing: Mutex<Vec<String>>,
    pub identities: Mutex<HashMap<String, Release>>,
    pub catalog: Mutex<Option<ApiResult<EnvironmentCatalog>>>,
    pub calls: AtomicUsize,
}

impl FakeProvider {
    pub fn add(&self, release: Release) {
        self.releases.lock().unwrap().entry(release.project_id.clone()).or_default().insert(0, release);
    }
}

impl PluginProvider for FakeProvider {
    fn info(&self) -> ProviderInfo {
        ProviderInfo::new("fake", "Fake", "https://example.invalid").unwrap()
    }

    fn environments(&self) -> ApiResult<Option<EnvironmentCatalog>> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        match &*self.catalog.lock().unwrap() {
            None => Ok(None),
            Some(Ok(c)) => Ok(Some(c.clone())),
            Some(Err(e)) => Err(ApiError::Transport(e.to_string())),
        }
    }

    fn search(&self, _request: &SearchRequest) -> ApiResult<SearchPage> {
        Ok(SearchPage { projects: vec![], has_more: false })
    }

    fn project(&self, project_id: &str) -> ApiResult<Project> {
        Ok(Project {
            provider_id: "fake".into(),
            id: project_id.into(),
            title: project_id.into(),
            author: String::new(),
            description: String::new(),
            page: "https://example.invalid".into(),
            access: Access::Direct,
            tested_game_versions: vec![],
            icon: None,
        })
    }

    fn releases(&self, project_id: &str, target: &TargetEnvironment) -> ApiResult<Vec<Release>> {
        if self.failing.lock().unwrap().iter().any(|p| p == project_id) {
            return Err(ApiError::Transport("Outage".into()));
        }
        Ok(self
            .releases
            .lock()
            .unwrap()
            .get(project_id)
            .map(|r| r.iter().filter(|r| r.matches(target)).cloned().collect())
            .unwrap_or_default())
    }

    fn resolve(&self, url: &url::Url) -> Option<ProjectReference> {
        (url.host_str() == Some("fake.example"))
            .then(|| ProjectReference { project_id: url.path().trim_start_matches('/').into(), version_id: None })
    }

    fn identify_sha512(&self, hash: &str, _target: &TargetEnvironment) -> ApiResult<Option<Release>> {
        Ok(self.identities.lock().unwrap().get(hash).cloned())
    }
}
