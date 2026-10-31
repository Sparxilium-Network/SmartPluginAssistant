//! Modrinth adapter (<https://docs.modrinth.com/api/>). Public resources need no token.

use serde_json::Value;
use spa_api::http::{encode, has_host, json, HttpTransport, UreqTransport};
use spa_api::*;
use std::collections::BTreeMap;
use std::sync::Arc;
use url::Url;

pub const ID: &str = "modrinth";

pub struct ModrinthProvider {
    base: String,
    http: Arc<dyn HttpTransport>,
}

impl Default for ModrinthProvider {
    fn default() -> Self {
        Self::new("https://api.modrinth.com/v2/", Arc::new(UreqTransport::default()))
    }
}

fn required<'a>(node: &'a Value, field: &str) -> ApiResult<&'a str> {
    match node.get(field).and_then(Value::as_str) {
        Some(value) if !value.trim().is_empty() => Ok(value),
        _ => invalid(format!("Missing Modrinth field: {field}")),
    }
}

fn text(node: &Value, field: &str) -> String {
    node.get(field).and_then(Value::as_str).unwrap_or_default().to_string()
}

fn strings(node: Option<&Value>) -> Vec<String> {
    node.and_then(Value::as_array)
        .map(|a| a.iter().filter_map(Value::as_str).map(str::to_string).collect())
        .unwrap_or_default()
}

fn timestamp(value: &str, what: &str) -> ApiResult<i64> {
    chrono::DateTime::parse_from_rfc3339(value)
        .map(|d| d.timestamp_millis())
        .map_err(|_| ApiError::Invalid(format!("Invalid Modrinth {what} date")))
}

fn icon(value: &str) -> Option<String> {
    Url::parse(value).ok().filter(|u| u.scheme() == "https").map(|u| u.to_string())
}

impl ModrinthProvider {
    /// `base` must end with `/`; tests point it at a loopback fixture.
    pub fn new(base: &str, http: Arc<dyn HttpTransport>) -> Self {
        Self { base: base.to_string(), http }
    }

    fn get(&self, path: &str) -> ApiResult<Value> {
        log::debug!("Modrinth GET {path}");
        json(&self.http.get(&format!("{}{path}", self.base))?)
    }

    fn project_from(&self, node: &Value, search: bool) -> ApiResult<Project> {
        let id = required(node, if search { "project_id" } else { "id" })?;
        let slug = node.get("slug").and_then(Value::as_str).unwrap_or(id);
        let versions = node.get(if search { "versions" } else { "game_versions" });
        Ok(Project {
            provider_id: ID.into(),
            id: id.into(),
            title: required(node, "title")?.into(),
            author: text(node, "author"),
            description: text(node, "description"),
            page: format!("https://modrinth.com/plugin/{}", encode(slug)),
            access: Access::Direct,
            tested_game_versions: strings(versions),
            icon: icon(&text(node, "icon_url")),
        })
    }

    /// With `identity`, select exactly the file carrying that SHA-512.
    fn release_from(&self, node: &Value, identity: Option<&str>) -> ApiResult<Release> {
        let id = required(node, "id")?;
        let files = node.get("files").and_then(Value::as_array).cloned().unwrap_or_default();
        let jars = files.iter().filter(|f| text(f, "filename").to_lowercase().ends_with(".jar"));
        let selected = match identity {
            Some(hash) => jars
                .into_iter()
                .find(|f| f.pointer("/hashes/sha512").and_then(Value::as_str).is_some_and(|h| h.eq_ignore_ascii_case(hash))),
            None => {
                let jars: Vec<_> = jars.collect();
                jars.iter().find(|f| f.get("primary").and_then(Value::as_bool) == Some(true)).or(jars.first()).copied()
            }
        };
        let Some(selected) = selected else {
            return invalid(format!("Release has no JAR: {id}"));
        };
        let mut hashes = BTreeMap::new();
        for algorithm in ["sha512", "sha1"] {
            if let Some(value) = selected.get("hashes").and_then(|h| h.get(algorithm)).and_then(Value::as_str) {
                hashes.insert(algorithm.to_string(), value.to_string());
            }
        }
        if hashes.is_empty() {
            return invalid("Modrinth release has no checksum");
        }
        let mut dependencies = Vec::new();
        for dep in node.get("dependencies").and_then(Value::as_array).into_iter().flatten() {
            let kind = text(dep, "dependency_type");
            let project = dep.get("project_id").and_then(Value::as_str).map(str::to_string);
            let version = dep.get("version_id").and_then(Value::as_str).map(str::to_string);
            let file = dep.get("file_name").and_then(Value::as_str).map(str::to_string);
            match kind.as_str() {
                "required" | "optional" => {
                    if project.is_none() && version.is_none() {
                        if kind == "required" {
                            return invalid(format!(
                                "Required file dependency cannot be resolved: {}",
                                file.unwrap_or_default()
                            ));
                        }
                        continue;
                    }
                    dependencies.push(Dependency::new(project, version, kind == "required", false)?);
                }
                "incompatible" if project.is_some() || version.is_some() => {
                    dependencies.push(Dependency::new(project, version, false, true)?);
                }
                _ => {}
            }
        }
        let channel = match required(node, "version_type")? {
            "release" => Channel::Release,
            "beta" => Channel::Beta,
            "alpha" => Channel::Alpha,
            _ => return invalid("Unknown Modrinth release channel"),
        };
        Ok(Release {
            provider_id: ID.into(),
            project_id: required(node, "project_id")?.into(),
            id: id.into(),
            version: required(node, "version_number")?.into(),
            published_at: timestamp(required(node, "date_published")?, "release")?,
            channel,
            file_name: required(selected, "filename")?.into(),
            download: Some(required(selected, "url")?.into()),
            hashes,
            size: selected.get("size").and_then(Value::as_u64).unwrap_or(0),
            game_versions: strings(node.get("game_versions")),
            loaders: strings(node.get("loaders")),
            dependencies,
            confidence: Confidence::Verified,
        })
    }
}

impl PluginProvider for ModrinthProvider {
    fn info(&self) -> ProviderInfo {
        ProviderInfo::new(ID, "Modrinth", "https://modrinth.com/plugins").expect("valid provider info")
    }

    fn environments(&self) -> ApiResult<Option<EnvironmentCatalog>> {
        let loader_tags = self.get("tag/loader")?;
        let version_tags = self.get("tag/game_version")?;
        let (Some(loader_tags), Some(version_tags)) = (loader_tags.as_array(), version_tags.as_array()) else {
            return invalid("Invalid Modrinth environment catalog");
        };
        let mut loaders = Vec::new();
        for node in loader_tags {
            if strings(node.get("supported_project_types")).iter().any(|t| t == "plugin") {
                loaders.push(required(node, "name")?.to_string());
            }
        }
        loaders.sort();
        let mut versions = Vec::new();
        for node in version_tags.iter().filter(|n| text(n, "version_type") == "release") {
            versions.push((timestamp(required(node, "date")?, "game version")?, required(node, "version")?.to_string()));
        }
        versions.sort_by(|a, b| b.0.cmp(&a.0));
        let catalog = EnvironmentCatalog::new(loaders, versions.into_iter().map(|(_, v)| v).collect())?;
        log::info!(
            "Retrieved {} plugin loaders and {} release game versions",
            catalog.loaders.len(),
            catalog.game_versions.len()
        );
        Ok(Some(catalog))
    }

    fn search(&self, request: &SearchRequest) -> ApiResult<SearchPage> {
        let loaders: Vec<String> = request.target.effective_loaders().iter().map(|l| format!("categories:{l}")).collect();
        let facets = serde_json::json!([
            ["all_project_types:plugin"],
            loaders,
            [format!("versions:{}", request.target.minecraft_version)]
        ]);
        let root = self.get(&format!(
            "search?query={}&facets={}&offset={}&limit={}&index=relevance",
            encode(&request.query),
            encode(&facets.to_string()),
            request.offset(),
            request.size
        ))?;
        let (Some(hits), Some(total)) =
            (root.get("hits").and_then(Value::as_array), root.get("total_hits").and_then(Value::as_u64))
        else {
            return invalid("Invalid Modrinth search response");
        };
        let projects = hits.iter().map(|hit| self.project_from(hit, true)).collect::<ApiResult<Vec<_>>>()?;
        Ok(SearchPage { projects, has_more: request.offset() + u64::from(request.size) < total })
    }

    fn project(&self, project_id: &str) -> ApiResult<Project> {
        self.project_from(&self.get(&format!("project/{}", encode(project_id)))?, false)
    }

    fn releases(&self, project_id: &str, target: &TargetEnvironment) -> ApiResult<Vec<Release>> {
        let loaders = serde_json::to_string(&target.effective_loaders()).expect("strings serialize");
        let versions = serde_json::to_string(&[&target.minecraft_version]).expect("strings serialize");
        let array = self.get(&format!(
            "project/{}/version?loaders={}&game_versions={}",
            encode(project_id),
            encode(&loaders),
            encode(&versions)
        ))?;
        let Some(array) = array.as_array() else {
            return invalid("Invalid Modrinth version list");
        };
        let mut releases = Vec::new();
        for node in array {
            let release = self.release_from(node, None)?;
            if release.matches(target) {
                releases.push(release);
            }
        }
        releases.sort_by(|a, b| b.published_at.cmp(&a.published_at));
        Ok(releases)
    }

    fn release(&self, project_id: Option<&str>, version_id: &str, target: &TargetEnvironment) -> ApiResult<Release> {
        let path = match project_id {
            Some(project) => format!("project/{}/version/{}", encode(project), encode(version_id)),
            None => format!("version/{}", encode(version_id)),
        };
        let release = self.release_from(&self.get(&path)?, None)?;
        if project_id.is_some_and(|p| p != release.project_id) || !release.matches(target) {
            return invalid("Exact release does not match project/environment");
        }
        Ok(release)
    }

    fn resolve(&self, url: &Url) -> Option<ProjectReference> {
        if !(has_host(url, "modrinth.com") || has_host(url, "www.modrinth.com")) {
            return None;
        }
        let parts: Vec<&str> = url.path().split('/').collect();
        match parts.as_slice() {
            ["", "plugin", project] if !project.is_empty() => {
                Some(ProjectReference { project_id: (*project).into(), version_id: None })
            }
            ["", "plugin", project, "version", version] if !project.is_empty() && !version.is_empty() => {
                Some(ProjectReference { project_id: (*project).into(), version_id: Some((*version).into()) })
            }
            _ => None,
        }
    }

    fn identify_sha512(&self, hash: &str, _target: &TargetEnvironment) -> ApiResult<Option<Release>> {
        if hash.len() != 128 || !hash.bytes().all(|b| b.is_ascii_hexdigit()) {
            return invalid("Invalid SHA-512");
        }
        match self.get(&format!("version_file/{hash}?algorithm=sha512")) {
            Ok(node) => Ok(Some(self.release_from(&node, Some(hash))?)),
            Err(ApiError::Status(404)) => Ok(None),
            Err(e) => Err(e),
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
            let key = path.split('?').next().unwrap();
            self.routes.get(key).map(|b| b.clone().into_bytes()).ok_or(ApiError::Status(404))
        }
    }

    fn provider(routes: &[(&str, &str)]) -> (ModrinthProvider, Arc<Fixture>) {
        let fixture = Arc::new(Fixture {
            routes: routes.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect(),
            ..Default::default()
        });
        (ModrinthProvider::new("https://api.test/", fixture.clone()), fixture)
    }

    fn target() -> TargetEnvironment {
        TargetEnvironment::new("paper", "1.21.4", vec!["spigot".into()], false).unwrap()
    }

    const VERSION: &str = r#"{"id":"v2","project_id":"P1","version_number":"2.0","version_type":"release",
      "date_published":"2026-10-01T00:00:00Z","game_versions":["1.21.4"],"loaders":["paper"],
      "files":[{"filename":"extra.jar","url":"https://cdn.modrinth.com/x.jar","hashes":{"sha512":"bb","sha1":"b"},"size":3,"primary":false},
               {"filename":"main.jar","url":"https://cdn.modrinth.com/main.jar","hashes":{"sha512":"aa","sha1":"a"},"size":5,"primary":true}],
      "dependencies":[{"project_id":"DEP","version_id":null,"dependency_type":"required"},
                      {"project_id":"OPT","dependency_type":"optional"},
                      {"project_id":"BAD","dependency_type":"incompatible"},
                      {"project_id":"EMB","dependency_type":"embedded"}]}"#;

    #[test]
    fn parses_releases_dependencies_and_order() {
        let old = VERSION.replace("\"v2\"", "\"v1\"").replace("2026-10-01", "2026-09-01");
        let beta = VERSION.replace("\"v2\"", "\"v3\"").replace("\"release\"", "\"beta\"").replace("2026-10-01", "2026-11-01");
        let list = format!("[{old},{VERSION},{beta}]");
        let (p, fixture) = provider(&[("project/P1/version", &list)]);
        let releases = p.releases("P1", &target()).unwrap();
        assert_eq!(releases.iter().map(|r| r.id.as_str()).collect::<Vec<_>>(), vec!["v2", "v1"]);
        let r = &releases[0];
        assert_eq!((r.file_name.as_str(), r.size, r.hashes["sha512"].as_str()), ("main.jar", 5, "aa"));
        assert_eq!(r.dependencies.len(), 3);
        assert!(r.dependencies[0].required && !r.dependencies[1].required && r.dependencies[2].incompatible);
        let request = fixture.requested.lock().unwrap()[0].clone();
        assert!(request.contains("loaders=%5B%22paper%22%2C%22spigot%22%5D"), "{request}");
    }

    #[test]
    fn identity_selects_matching_file() {
        let (p, _) = provider(&[(&format!("version_file/{}", "b".repeat(128)), &VERSION.replace("\"bb\"", &format!("\"{}\"", "b".repeat(128))))]);
        let found = p.identify_sha512(&"b".repeat(128), &target()).unwrap().unwrap();
        assert_eq!(found.file_name, "extra.jar");
        assert!(p.identify_sha512(&"c".repeat(128), &target()).unwrap().is_none());
        assert!(p.identify_sha512("zz", &target()).is_err());
    }

    #[test]
    fn search_paging_icons_and_catalog() {
        let search = r#"{"total_hits":45,"hits":[{"project_id":"P1","slug":"luck perms","title":"LuckPerms","author":"Luck",
           "description":"Perms","versions":["1.21.4"],"icon_url":"https://cdn.modrinth.com/icon.png"},
           {"project_id":"P2","title":"NoIcon","icon_url":"javascript:alert(1)"}]}"#;
        let loaders = r#"[{"name":"paper","supported_project_types":["plugin"]},{"name":"fabric","supported_project_types":["mod"]},{"name":"bukkit","supported_project_types":["plugin"]}]"#;
        let versions = r#"[{"version":"1.21.3","version_type":"release","date":"2024-10-23T00:00:00Z"},
            {"version":"1.21.4","version_type":"release","date":"2024-12-03T00:00:00Z"},
            {"version":"25w01a","version_type":"snapshot","date":"2025-01-01T00:00:00Z"}]"#;
        let (p, fixture) = provider(&[("search", search), ("tag/loader", loaders), ("tag/game_version", versions)]);
        let page = p.search(&SearchRequest::new("luck", target(), 1, 20).unwrap()).unwrap();
        assert!(page.has_more);
        assert_eq!(page.projects[0].page, "https://modrinth.com/plugin/luck%20perms");
        assert_eq!(page.projects[0].icon.as_deref(), Some("https://cdn.modrinth.com/icon.png"));
        assert_eq!(page.projects[1].icon, None);
        assert!(fixture.requested.lock().unwrap()[0].contains("offset=20&limit=20"));
        let catalog = p.environments().unwrap().unwrap();
        assert_eq!(catalog.loaders, vec!["bukkit", "paper"]);
        assert_eq!(catalog.game_versions, vec!["1.21.4", "1.21.3"]);
        let last = p.search(&SearchRequest::new("", target(), 2, 20).unwrap()).unwrap();
        assert!(!last.has_more);
    }

    #[test]
    fn resolves_only_official_urls() {
        let (p, _) = provider(&[]);
        let parse = |s: &str| p.resolve(&Url::parse(s).unwrap());
        assert_eq!(parse("https://modrinth.com/plugin/luckperms").unwrap().project_id, "luckperms");
        assert_eq!(parse("https://modrinth.com/plugin/luckperms/version/abc").unwrap().version_id.as_deref(), Some("abc"));
        assert!(parse("https://modrinth.com.evil.example/plugin/x").is_none());
        assert!(parse("http://modrinth.com/plugin/x").is_none());
        assert!(parse("https://modrinth.com/mod/x").is_none());
    }

    #[test]
    fn exact_release_must_match_project_and_target() {
        let (p, _) = provider(&[("project/P1/version/v2", VERSION), ("version/v2", VERSION)]);
        assert!(p.release(Some("P1"), "v2", &target()).is_ok());
        assert!(p.release(None, "v2", &target()).is_ok());
        let other = TargetEnvironment::new("paper", "1.20.1", vec![], false).unwrap();
        assert!(p.release(Some("P1"), "v2", &other).is_err());
        let required_file = VERSION.replace(r#"{"project_id":"DEP","version_id":null,"dependency_type":"required"}"#, r#"{"file_name":"x.jar","dependency_type":"required"}"#);
        let (p, _) = provider(&[("version/v2", &required_file)]);
        assert!(p.release(None, "v2", &target()).is_err());
    }
}
