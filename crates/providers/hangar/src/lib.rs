//! PaperMC Hangar adapter (<https://hangar.papermc.io/api-docs>).
//!
//! Hangar declares Minecraft versions per platform (PAPER, WATERFALL, VELOCITY) for every
//! version. A release is `Verified` only when the target's loaders include that platform;
//! otherwise it is `Declared` and needs user confirmation. Website-only dependencies are
//! reported as manual dependencies, never silently dropped.

use serde_json::Value;
use spa_api::http::{encode, has_host, json, HttpTransport, UreqTransport};
use spa_api::*;
use std::collections::BTreeMap;
use std::sync::Arc;
use url::Url;

pub const ID: &str = "hangar";
const SITE: &str = "https://hangar.papermc.io";
const RELEASE_PAGE: u32 = 50;

pub struct HangarProvider {
    base: String,
    http: Arc<dyn HttpTransport>,
}

impl Default for HangarProvider {
    fn default() -> Self {
        Self::new("https://hangar.papermc.io/api/v1/", Arc::new(UreqTransport::default()))
    }
}

/// The Hangar platform serving a target, chosen from its effective loaders in order.
pub fn platform(target: &TargetEnvironment) -> Option<&'static str> {
    target.effective_loaders().iter().find_map(|loader| match loader.as_str() {
        "paper" | "purpur" | "folia" => Some("PAPER"),
        "waterfall" | "bungeecord" => Some("WATERFALL"),
        "velocity" => Some("VELOCITY"),
        _ => None,
    })
}

fn required<'a>(node: &'a Value, field: &str) -> ApiResult<&'a str> {
    match node.get(field).and_then(Value::as_str) {
        Some(value) if !value.trim().is_empty() => Ok(value),
        _ => invalid(format!("Missing Hangar field: {field}")),
    }
}

fn number(node: &Value, field: &str) -> ApiResult<String> {
    node.get(field)
        .and_then(Value::as_u64)
        .map(|n| n.to_string())
        .ok_or_else(|| ApiError::Invalid(format!("Missing Hangar field: {field}")))
}

fn text(node: &Value, field: &str) -> String {
    node.get(field).and_then(Value::as_str).unwrap_or_default().to_string()
}

fn strings(node: Option<&Value>) -> Vec<String> {
    node.and_then(Value::as_array)
        .map(|a| a.iter().filter_map(Value::as_str).map(str::to_string).collect())
        .unwrap_or_default()
}

fn timestamp(value: &str) -> ApiResult<i64> {
    chrono::DateTime::parse_from_rfc3339(value)
        .map(|d| d.timestamp_millis())
        .map_err(|_| ApiError::Invalid("Invalid Hangar date".into()))
}

/// Hangar channels are publisher-named. The UNSTABLE flag or a pre-release name (snapshot,
/// dev, beta, rc…) marks a test channel; other channels are the publisher's stable releases.
fn channel(node: &Value) -> Channel {
    let Some(channel) = node.get("channel") else { return Channel::Unknown };
    let unstable = strings(channel.get("flags")).iter().any(|f| f == "UNSTABLE");
    let name = text(channel, "name").to_lowercase();
    let prerelease = ["snapshot", "dev", "beta", "pre", "rc", "nightly", "test", "experimental"]
        .iter()
        .any(|marker| name.contains(marker));
    if name.contains("alpha") {
        Channel::Alpha
    } else if unstable || prerelease {
        Channel::Beta
    } else {
        Channel::Release
    }
}

impl HangarProvider {
    /// `base` must end with `/`; tests point it at a fixture.
    pub fn new(base: &str, http: Arc<dyn HttpTransport>) -> Self {
        Self { base: base.to_string(), http }
    }

    fn get(&self, path: &str) -> ApiResult<Value> {
        log::debug!("Hangar GET {path}");
        json(&self.http.get(&format!("{}{path}", self.base))?)
    }

    fn project_from(&self, node: &Value) -> ApiResult<Project> {
        let namespace = node.get("namespace").ok_or_else(|| ApiError::Invalid("Missing Hangar namespace".into()))?;
        let owner = required(namespace, "owner")?;
        let slug = required(namespace, "slug")?;
        let mut tested: Vec<String> = Vec::new();
        if let Some(platforms) = node.get("supportedPlatforms").and_then(Value::as_object) {
            for versions in platforms.values() {
                for version in strings(Some(versions)) {
                    if !tested.contains(&version) {
                        tested.push(version);
                    }
                }
            }
        }
        Ok(Project {
            provider_id: ID.into(),
            id: number(node, "id")?,
            title: required(node, "name")?.into(),
            author: owner.into(),
            description: text(node, "description"),
            page: format!("{SITE}/{}/{}", encode(owner), encode(slug)),
            access: Access::Direct,
            tested_game_versions: tested,
            icon: Url::parse(&text(node, "avatarUrl")).ok().filter(|u| u.scheme() == "https").map(|u| u.to_string()),
        })
    }

    /// `None` when the version has no file for the platform serving this target.
    fn release_from(&self, node: &Value, target: &TargetEnvironment) -> ApiResult<Option<Release>> {
        let Some(platform) = platform(target) else { return Ok(None) };
        let Some(download) = node.get("downloads").and_then(|d| d.get(platform)) else { return Ok(None) };
        let id = number(node, "id")?;
        let file = download.get("fileInfo").filter(|f| !f.is_null());
        let direct = download.get("downloadUrl").and_then(Value::as_str).map(str::to_string);
        let (file_name, size, mut hashes) = match file {
            Some(file) => {
                let mut hashes = BTreeMap::new();
                if let Some(hash) = file.get("sha256Hash").and_then(Value::as_str) {
                    hashes.insert("sha256".to_string(), hash.to_string());
                }
                (
                    required(file, "name")?.to_string(),
                    file.get("sizeBytes").and_then(Value::as_u64).unwrap_or(0),
                    hashes,
                )
            }
            None => (String::new(), 0, BTreeMap::new()),
        };
        // External downloads cannot be verified or pinned; only the website can supply them.
        let download_url = direct.filter(|_| file.is_some() && file_name.to_lowercase().ends_with(".jar"));
        if download_url.is_none() {
            hashes.clear();
        }
        let mut dependencies = Vec::new();
        for dep in
            node.pointer(&format!("/pluginDependencies/{platform}")).and_then(Value::as_array).into_iter().flatten()
        {
            let project = dep.get("projectId").and_then(Value::as_u64).map(|n| n.to_string());
            let external = dep.get("externalUrl").and_then(Value::as_str).filter(|s| !s.is_empty()).map(str::to_string);
            let name = dep.get("name").and_then(Value::as_str).map(str::to_string);
            let required = dep.get("required").and_then(Value::as_bool).unwrap_or(false);
            if project.is_none() && external.is_none() && name.is_none() {
                continue;
            }
            let external = external.or_else(|| {
                (project.is_none()).then(|| format!("{SITE}/?query={}", encode(name.as_deref().unwrap_or_default())))
            });
            dependencies.push(Dependency::named(project, None, name, external, required, false)?);
        }
        let mut release = Release {
            provider_id: ID.into(),
            project_id: number(node, "projectId")?,
            id,
            version: required(node, "name")?.into(),
            published_at: timestamp(required(node, "createdAt")?)?,
            channel: channel(node),
            file_name,
            download: download_url,
            hashes,
            size,
            game_versions: strings(node.pointer(&format!("/platformDependencies/{platform}"))),
            loaders: vec![platform.to_lowercase()],
            dependencies,
            confidence: Confidence::Declared,
        };
        if release.matches(target) {
            release.confidence = Confidence::Verified;
        }
        Ok(Some(release))
    }

    /// Channel and Minecraft version always hold; the platform choice may need confirmation.
    fn eligible(release: &Release, target: &TargetEnvironment) -> bool {
        (target.allow_prereleases || release.channel == Channel::Release)
            && release.game_versions.contains(&target.minecraft_version)
    }
}

impl PluginProvider for HangarProvider {
    fn info(&self) -> ProviderInfo {
        ProviderInfo::new(ID, "Hangar", SITE).expect("valid provider info")
    }

    fn search(&self, request: &SearchRequest) -> ApiResult<SearchPage> {
        let Some(platform) = platform(&request.target) else {
            return Ok(SearchPage { projects: vec![], has_more: false });
        };
        let mut path = format!(
            "projects?platform={platform}&version={}&offset={}&limit={}",
            encode(&request.target.minecraft_version),
            request.offset(),
            request.size
        );
        if request.query.is_empty() {
            path.push_str("&sort=-downloads");
        } else {
            path.push_str(&format!("&query={}", encode(&request.query)));
        }
        let root = self.get(&path)?;
        let (Some(result), Some(count)) =
            (root.get("result").and_then(Value::as_array), root.pointer("/pagination/count").and_then(Value::as_u64))
        else {
            return invalid("Invalid Hangar search response");
        };
        let projects = result.iter().map(|n| self.project_from(n)).collect::<ApiResult<Vec<_>>>()?;
        Ok(SearchPage { projects, has_more: request.offset() + u64::from(request.size) < count })
    }

    fn project(&self, project_id: &str) -> ApiResult<Project> {
        self.project_from(&self.get(&format!("projects/{}", encode(project_id)))?)
    }

    fn releases(&self, project_id: &str, target: &TargetEnvironment) -> ApiResult<Vec<Release>> {
        let Some(platform) = platform(target) else { return Ok(vec![]) };
        let root = self.get(&format!(
            "projects/{}/versions?platform={platform}&platformVersion={}&offset=0&limit={RELEASE_PAGE}",
            encode(project_id),
            encode(&target.minecraft_version)
        ))?;
        let Some(result) = root.get("result").and_then(Value::as_array) else {
            return invalid("Invalid Hangar version list");
        };
        let mut releases = Vec::new();
        for node in result {
            if let Some(release) = self.release_from(node, target)? {
                if Self::eligible(&release, target) {
                    releases.push(release);
                }
            }
        }
        releases.sort_by_key(|r| std::cmp::Reverse(r.published_at));
        Ok(releases)
    }

    /// `version_id` may be the numeric ID or the version name from an official URL.
    fn release(&self, project_id: Option<&str>, version_id: &str, target: &TargetEnvironment) -> ApiResult<Release> {
        let path = match project_id {
            Some(project) => format!("projects/{}/versions/{}", encode(project), encode(version_id)),
            None => format!("versions/{}", encode(version_id)),
        };
        let release = self
            .release_from(&self.get(&path)?, target)?
            .filter(|r| Self::eligible(r, target))
            .ok_or_else(|| ApiError::Invalid("Exact release does not match project/environment".into()))?;
        let numeric = |s: &str| s.bytes().all(|b| b.is_ascii_digit());
        if project_id.is_some_and(|p| numeric(p) && p != release.project_id) {
            return invalid("Exact release does not match project/environment");
        }
        Ok(release)
    }

    fn resolve(&self, url: &Url) -> Option<ProjectReference> {
        if !has_host(url, "hangar.papermc.io") {
            return None;
        }
        let parts: Vec<&str> = url.path().trim_end_matches('/').split('/').collect();
        let valid = |s: &&str| !s.is_empty();
        match parts.as_slice() {
            ["", owner, slug]
                if valid(owner) && valid(slug) && !["api", "auth", "admin", "tools", "staff"].contains(owner) =>
            {
                Some(ProjectReference { project_id: (*slug).into(), version_id: None })
            }
            ["", owner, slug, "versions", version] if valid(owner) && valid(slug) && valid(version) => {
                Some(ProjectReference { project_id: (*slug).into(), version_id: Some((*version).into()) })
            }
            _ => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;
    use std::sync::Mutex;

    #[derive(Default)]
    struct Fixture {
        routes: HashMap<String, String>,
        requested: Mutex<Vec<String>>,
    }

    impl HttpTransport for Fixture {
        fn get(&self, url: &str) -> ApiResult<Vec<u8>> {
            let path = url.trim_start_matches("https://api.test/");
            self.requested.lock().unwrap().push(path.to_string());
            self.routes
                .get(path.split('?').next().unwrap())
                .map(|b| b.clone().into_bytes())
                .ok_or(ApiError::Status(404))
        }
    }

    fn provider(routes: &[(&str, &str)]) -> (HangarProvider, Arc<Fixture>) {
        let fixture = Arc::new(Fixture {
            routes: routes.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect(),
            ..Default::default()
        });
        (HangarProvider::new("https://api.test/", fixture.clone()), fixture)
    }

    fn target(loader: &str, extras: &[&str]) -> TargetEnvironment {
        TargetEnvironment::new(loader, "1.21.4", extras.iter().map(|s| s.to_string()), false).unwrap()
    }

    const VERSION: &str = r#"{"createdAt":"2026-10-29T17:23:50Z","id":32888,"projectId":463,"name":"4.11.1",
      "channel":{"name":"Release","flags":["FROZEN"]},
      "downloads":{"PAPER":{"fileInfo":{"name":"HuskHomes-Paper-4.11.1.jar","sizeBytes":2307738,"sha256Hash":"f522"},"externalUrl":null,
                   "downloadUrl":"https://hangarcdn.papermc.io/plugins/William278/HuskHomes/versions/4.11.1/PAPER/HuskHomes-Paper-4.11.1.jar"}},
      "pluginDependencies":{"PAPER":[{"name":"Multiverse-Core","projectId":593,"required":true,"externalUrl":null},
                                      {"name":"Vault","projectId":null,"required":true,"externalUrl":"https://github.com/MilkBowl/Vault"},
                                      {"name":"PlaceholderAPI","projectId":null,"required":false,"externalUrl":null}]},
      "platformDependencies":{"PAPER":["1.21.3","1.21.4"]}}"#;

    #[test]
    fn parses_versions_dependencies_and_confidence() {
        let beta = VERSION
            .replace("32888", "32999")
            .replace(r#"["FROZEN"]"#, r#"["UNSTABLE"]"#)
            .replace("Release", "Beta")
            .replace("10-29", "10-30");
        let older = VERSION.replace("32888", "30000").replace("10-29", "09-01");
        let list = format!(r#"{{"pagination":{{"count":3}},"result":[{older},{beta},{VERSION}]}}"#);
        let (p, fixture) = provider(&[("projects/463/versions", &list)]);
        let releases = p.releases("463", &target("paper", &[])).unwrap();
        assert_eq!(releases.iter().map(|r| r.id.as_str()).collect::<Vec<_>>(), vec!["32888", "30000"]);
        let r = &releases[0];
        assert_eq!(r.confidence, Confidence::Verified);
        assert_eq!((r.hashes["sha256"].as_str(), r.size, r.loaders[0].as_str()), ("f522", 2307738, "paper"));
        assert_eq!(r.dependencies.len(), 3);
        assert_eq!(r.dependencies[0].project_id.as_deref(), Some("593"));
        assert!(r.dependencies[1].manual() && r.dependencies[1].required);
        assert!(r.dependencies[2].external_url.as_deref().unwrap().starts_with("https://hangar.papermc.io/?query="));
        assert!(fixture.requested.lock().unwrap()[0].contains("platform=PAPER&platformVersion=1.21.4"));

        let purpur = p.releases("463", &target("purpur", &[])).unwrap();
        assert_eq!(purpur[0].confidence, Confidence::Declared, "purpur without paper compatibility needs confirmation");
        let purpur_paper = p.releases("463", &target("purpur", &["paper"])).unwrap();
        assert_eq!(purpur_paper[0].confidence, Confidence::Verified);
        assert!(p.releases("463", &target("spigot", &[])).unwrap().is_empty());
    }

    #[test]
    fn channel_names_and_flags() {
        let parse = |json: &str| channel(&serde_json::from_str(json).unwrap());
        assert_eq!(parse(r#"{"channel":{"name":"Release","flags":["PINNED"]}}"#), Channel::Release);
        assert_eq!(parse(r#"{"channel":{"name":"Snapshot","flags":[]}}"#), Channel::Beta);
        assert_eq!(parse(r#"{"channel":{"name":"Stable","flags":["UNSTABLE"]}}"#), Channel::Beta);
        assert_eq!(parse(r#"{"channel":{"name":"Alpha","flags":["UNSTABLE"]}}"#), Channel::Alpha);
        assert_eq!(parse(r#"{}"#), Channel::Unknown);
    }

    #[test]
    fn external_downloads_are_not_direct() {
        let external = VERSION.replace(r#""downloadUrl":"https://hangarcdn"#, r#""ignored":"https://hangarcdn"#);
        let (p, _) = provider(&[("versions/32888", &external)]);
        let release = p.release(None, "32888", &target("paper", &[])).unwrap();
        assert!(release.download.is_none() && release.hashes.is_empty());
    }

    #[test]
    fn exact_release_by_id_or_name() {
        let (p, _) = provider(&[("projects/463/versions/4.11.1", VERSION), ("projects/999/versions/32888", VERSION)]);
        assert_eq!(p.release(Some("463"), "4.11.1", &target("paper", &[])).unwrap().id, "32888");
        assert!(p.release(Some("999"), "32888", &target("paper", &[])).is_err(), "project mismatch");
        let old = TargetEnvironment::new("paper", "1.8.8", Vec::<String>::new(), false).unwrap();
        assert!(p.release(Some("463"), "4.11.1", &old).is_err());
    }

    #[test]
    fn search_projects_and_paging() {
        let search = r#"{"pagination":{"count":45,"limit":20,"offset":20},"result":[{"id":463,"name":"HuskHomes",
          "namespace":{"owner":"William278","slug":"HuskHomes"},"description":"Homes","avatarUrl":"https://hangarcdn.papermc.io/avatars/project/463.webp?v=2",
          "supportedPlatforms":{"PAPER":["1.21.3","1.21.4"],"VELOCITY":["3.3"]}}]}"#;
        let (p, fixture) = provider(&[("projects", search)]);
        let page = p.search(&SearchRequest::new("", target("paper", &[]), 1, 20).unwrap()).unwrap();
        assert!(page.has_more);
        let project = &page.projects[0];
        assert_eq!((project.id.as_str(), project.author.as_str()), ("463", "William278"));
        assert_eq!(project.page, "https://hangar.papermc.io/William278/HuskHomes");
        assert_eq!(project.tested_game_versions, vec!["1.21.3", "1.21.4", "3.3"]);
        assert!(project.icon.as_deref().unwrap().ends_with(".webp?v=2"));
        let request = fixture.requested.lock().unwrap()[0].clone();
        assert!(request.contains("offset=20&limit=20&sort=-downloads"), "{request}");
        p.search(&SearchRequest::new("husk homes", target("velocity", &[]), 0, 20).unwrap()).unwrap();
        assert!(
            fixture.requested.lock().unwrap()[1].contains("platform=VELOCITY")
                && fixture.requested.lock().unwrap()[1].contains("query=husk%20homes")
        );
        assert!(p.search(&SearchRequest::new("", target("fabric", &[]), 0, 20).unwrap()).unwrap().projects.is_empty());
    }

    #[test]
    fn resolves_official_urls() {
        let (p, _) = provider(&[]);
        let parse = |s: &str| p.resolve(&Url::parse(s).unwrap());
        assert_eq!(parse("https://hangar.papermc.io/William278/HuskHomes").unwrap().project_id, "HuskHomes");
        let version = parse("https://hangar.papermc.io/William278/HuskHomes/versions/4.11.1").unwrap();
        assert_eq!(version.version_id.as_deref(), Some("4.11.1"));
        assert!(parse("https://hangar.papermc.io/api/v1").is_none());
        assert!(parse("https://hangar.papermc.io.evil.example/a/b").is_none());
        assert!(parse("https://hangar.papermc.io/William278").is_none());
    }
}
