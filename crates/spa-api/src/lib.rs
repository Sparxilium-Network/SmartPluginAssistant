//! Provider SPI and immutable data contracts shared by the host and every provider crate.
//!
//! Providers are blocking: hosts must call them off the UI thread. Implementations must be
//! thread safe (`Send + Sync`). Providers never write server files themselves.

pub mod http;

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use url::Url;

#[derive(Debug, thiserror::Error)]
pub enum ApiError {
    /// Non-success HTTP status from the remote API.
    #[error("API HTTP {0}")]
    Status(u16),
    /// Transport failure, timeout or oversized response.
    #[error("{0}")]
    Transport(String),
    /// Malformed, missing or contract-violating data.
    #[error("{0}")]
    Invalid(String),
}

pub type ApiResult<T> = Result<T, ApiError>;

pub fn invalid<T>(message: impl Into<String>) -> ApiResult<T> {
    Err(ApiError::Invalid(message.into()))
}

/// `[a-z][a-z0-9_-]{0,30}`
pub fn is_loader(value: &str) -> bool {
    let mut chars = value.chars();
    matches!(chars.next(), Some('a'..='z'))
        && value.len() <= 31
        && chars.all(|c| matches!(c, 'a'..='z' | '0'..='9' | '_' | '-'))
}

/// `[a-zA-Z0-9][a-zA-Z0-9._+-]{0,50}`
pub fn is_game_version(value: &str) -> bool {
    let mut chars = value.chars();
    matches!(chars.next(), Some(c) if c.is_ascii_alphanumeric())
        && value.len() <= 51
        && chars.all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '+' | '-'))
}

fn dedup(values: impl IntoIterator<Item = String>) -> Vec<String> {
    let mut result: Vec<String> = Vec::new();
    for value in values {
        if !result.contains(&value) {
            result.push(value);
        }
    }
    result
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProviderInfo {
    pub id: String,
    pub name: String,
    pub homepage: String,
}

impl ProviderInfo {
    pub fn new(id: &str, name: &str, homepage: &str) -> ApiResult<Self> {
        let valid = id.len() >= 2
            && id.len() <= 41
            && id.starts_with(|c: char| c.is_ascii_lowercase())
            && id.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-');
        if !valid {
            return invalid("Invalid provider ID");
        }
        Ok(Self { id: id.into(), name: name.into(), homepage: homepage.into() })
    }
}

/// Persisted with the same field names as Sol's Jackson records.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TargetEnvironment {
    pub loader: String,
    pub minecraft_version: String,
    #[serde(default)]
    pub extra_loaders: Vec<String>,
    #[serde(default)]
    pub allow_prereleases: bool,
}

impl TargetEnvironment {
    pub fn new(
        loader: &str,
        minecraft_version: &str,
        extra_loaders: impl IntoIterator<Item = String>,
        allow_prereleases: bool,
    ) -> ApiResult<Self> {
        let value = Self {
            loader: loader.trim().to_lowercase(),
            minecraft_version: minecraft_version.trim().into(),
            extra_loaders: dedup(extra_loaders),
            allow_prereleases,
        };
        value.validate()?;
        Ok(value)
    }

    /// Also applied to deserialized data before it is trusted.
    pub fn validate(&self) -> ApiResult<()> {
        if !is_loader(&self.loader) || !is_game_version(&self.minecraft_version) {
            return invalid("Invalid target environment");
        }
        if !self.extra_loaders.iter().all(|l| is_loader(l)) {
            return invalid("Invalid compatible loader");
        }
        Ok(())
    }

    /// Primary loader first, then the explicitly selected compatible loaders.
    pub fn effective_loaders(&self) -> Vec<String> {
        dedup(std::iter::once(self.loader.clone()).chain(self.extra_loaders.iter().cloned()))
    }
}

/// Provider-supplied plugin loaders and release game versions, newest game version first.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EnvironmentCatalog {
    pub loaders: Vec<String>,
    pub game_versions: Vec<String>,
}

impl EnvironmentCatalog {
    pub fn new(loaders: Vec<String>, game_versions: Vec<String>) -> ApiResult<Self> {
        let value = Self { loaders: dedup(loaders), game_versions: dedup(game_versions) };
        value.validate()?;
        Ok(value)
    }

    pub fn validate(&self) -> ApiResult<()> {
        if self.loaders.is_empty() || self.game_versions.is_empty() {
            return invalid("Empty environment catalog");
        }
        if !self.loaders.iter().all(|s| is_loader(s)) || !self.game_versions.iter().all(|s| is_game_version(s)) {
            return invalid("Invalid environment catalog value");
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Access {
    Direct,
    External,
    Premium,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Project {
    pub provider_id: String,
    pub id: String,
    pub title: String,
    pub author: String,
    /// Plain text.
    pub description: String,
    /// Official project page.
    pub page: String,
    pub access: Access,
    pub tested_game_versions: Vec<String>,
    /// Optional absolute HTTPS raster artwork URL.
    pub icon: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Channel {
    Release,
    Beta,
    Alpha,
    Unknown,
}

/// `Verified` means the provider metadata declares the target; not that it was executed.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Confidence {
    Verified,
    Declared,
    Unknown,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Dependency {
    pub project_id: Option<String>,
    pub version_id: Option<String>,
    /// Display name supplied by the provider, when known.
    pub name: Option<String>,
    /// Website for dependencies that only exist outside the provider.
    pub external_url: Option<String>,
    pub required: bool,
    pub incompatible: bool,
}

impl Dependency {
    pub fn new(
        project_id: Option<String>,
        version_id: Option<String>,
        required: bool,
        incompatible: bool,
    ) -> ApiResult<Self> {
        Self::named(project_id, version_id, None, None, required, incompatible)
    }

    pub fn named(
        project_id: Option<String>,
        version_id: Option<String>,
        name: Option<String>,
        external_url: Option<String>,
        required: bool,
        incompatible: bool,
    ) -> ApiResult<Self> {
        let blank = |v: &Option<String>| v.as_deref().is_none_or(|s| s.trim().is_empty());
        if required && incompatible {
            return invalid("Conflicting dependency kind");
        }
        if blank(&project_id) && blank(&version_id) && blank(&external_url) {
            return invalid("Dependency must reference a project, version or website");
        }
        Ok(Self { project_id, version_id, name, external_url, required, incompatible })
    }

    /// Only installable through a website; the host must ask the user to handle it.
    pub fn manual(&self) -> bool {
        self.project_id.is_none() && self.version_id.is_none()
    }

    pub fn label(&self) -> String {
        self.name
            .clone()
            .or_else(|| self.project_id.clone())
            .or_else(|| self.version_id.clone())
            .or_else(|| self.external_url.clone())
            .unwrap_or_default()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Release {
    pub provider_id: String,
    pub project_id: String,
    /// Immutable provider release ID.
    pub id: String,
    pub version: String,
    /// Epoch milliseconds.
    pub published_at: i64,
    pub channel: Channel,
    pub file_name: String,
    /// Pinned direct download; `None` when only the website can supply the file.
    pub download: Option<String>,
    /// Lower-case algorithm (`sha512`, `sha256`, `sha1`) to hex digest.
    pub hashes: BTreeMap<String, String>,
    /// Zero when unknown.
    pub size: u64,
    pub game_versions: Vec<String>,
    pub loaders: Vec<String>,
    pub dependencies: Vec<Dependency>,
    pub confidence: Confidence,
}

impl Release {
    pub fn matches(&self, target: &TargetEnvironment) -> bool {
        (target.allow_prereleases || self.channel == Channel::Release)
            && self.game_versions.contains(&target.minecraft_version)
            && target.effective_loaders().iter().any(|l| self.loaders.contains(l))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProjectReference {
    pub project_id: String,
    pub version_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchRequest {
    pub query: String,
    pub target: TargetEnvironment,
    /// Zero-based page index.
    pub page: u32,
    pub size: u32,
}

impl SearchRequest {
    pub fn new(query: &str, target: TargetEnvironment, page: u32, size: u32) -> ApiResult<Self> {
        if page > 10_000 || !(1..=100).contains(&size) {
            return invalid("Invalid pagination");
        }
        Ok(Self { query: query.trim().into(), target, page, size })
    }

    pub fn offset(&self) -> u64 {
        u64::from(self.page) * u64::from(self.size)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchPage {
    pub projects: Vec<Project>,
    pub has_more: bool,
}

/// Blocking provider SPI. Never return an empty success for a failed request.
pub trait PluginProvider: Send + Sync {
    fn info(&self) -> ProviderInfo;

    /// Optional metadata capability; values must come from the provider.
    fn environments(&self) -> ApiResult<Option<EnvironmentCatalog>> {
        Ok(None)
    }

    fn search(&self, request: &SearchRequest) -> ApiResult<SearchPage>;

    fn project(&self, project_id: &str) -> ApiResult<Project>;

    /// Eligible releases ordered newest first. Never silently fall back to an incompatible one.
    fn releases(&self, project_id: &str, target: &TargetEnvironment) -> ApiResult<Vec<Release>>;

    /// Exact release only; must not fall back to another version.
    fn release(&self, project_id: Option<&str>, version_id: &str, target: &TargetEnvironment) -> ApiResult<Release> {
        let Some(project_id) = project_id else {
            return invalid("Version-only references are not supported by this provider");
        };
        self.releases(project_id, target)?
            .into_iter()
            .find(|r| r.id == version_id)
            .ok_or_else(|| ApiError::Invalid(format!("Exact compatible release unavailable: {version_id}")))
    }

    /// Recognize exact official hosts and paths only.
    fn resolve(&self, url: &Url) -> Option<ProjectReference>;

    /// Exact file identity; return the matching file, not another file of the release.
    fn identify_sha512(&self, _hash: &str, _target: &TargetEnvironment) -> ApiResult<Option<Release>> {
        Ok(None)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn release(channel: Channel, versions: &[&str], loaders: &[&str]) -> Release {
        Release {
            provider_id: "test".into(),
            project_id: "p".into(),
            id: "r".into(),
            version: "1".into(),
            published_at: 0,
            channel,
            file_name: "a.jar".into(),
            download: None,
            hashes: BTreeMap::new(),
            size: 0,
            game_versions: versions.iter().map(|s| s.to_string()).collect(),
            loaders: loaders.iter().map(|s| s.to_string()).collect(),
            dependencies: vec![],
            confidence: Confidence::Verified,
        }
    }

    #[test]
    fn compatibility_requires_version_loader_and_channel() {
        let target = TargetEnvironment::new("paper", "1.21.4", vec!["spigot".into()], false).unwrap();
        assert!(release(Channel::Release, &["1.21.4"], &["spigot"]).matches(&target));
        assert!(!release(Channel::Release, &["1.21.3"], &["paper"]).matches(&target));
        assert!(!release(Channel::Release, &["1.21.4"], &["fabric"]).matches(&target));
        assert!(!release(Channel::Beta, &["1.21.4"], &["paper"]).matches(&target));
        let pre = TargetEnvironment::new("paper", "1.21.4", vec![], true).unwrap();
        assert!(release(Channel::Beta, &["1.21.4"], &["paper"]).matches(&pre));
    }

    #[test]
    fn target_validation_and_effective_loaders() {
        assert!(TargetEnvironment::new("Paper ", "1.21", vec![], false).is_ok());
        assert!(TargetEnvironment::new("pa per", "1.21", vec![], false).is_err());
        assert!(TargetEnvironment::new("paper", "../1", vec![], false).is_err());
        assert!(TargetEnvironment::new("paper", "1.21", vec!["Bad".into()], false).is_err());
        let t = TargetEnvironment::new("paper", "1.21", vec!["paper".into(), "spigot".into()], false).unwrap();
        assert_eq!(t.effective_loaders(), vec!["paper", "spigot"]);
    }

    #[test]
    fn pagination_and_dependency_validation() {
        let t = TargetEnvironment::new("paper", "1.21", vec![], false).unwrap();
        assert!(SearchRequest::new("", t.clone(), 0, 0).is_err());
        assert!(SearchRequest::new("", t.clone(), 10_001, 20).is_err());
        assert_eq!(SearchRequest::new(" a ", t, 2, 20).unwrap().offset(), 40);
        assert!(Dependency::new(None, None, true, false).is_err());
        assert!(Dependency::new(Some("x".into()), None, true, true).is_err());
        assert!(ProviderInfo::new("Modrinth", "M", "https://x").is_err());
        assert!(EnvironmentCatalog::new(vec![], vec!["1.21".into()]).is_err());
    }
}
