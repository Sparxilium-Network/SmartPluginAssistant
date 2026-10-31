//! Persisted preferences. Older JSON gains defaults; values are validated before use.

use serde::{Deserialize, Serialize};
use spa_core::{fail, json, Result};
use std::path::Path;

pub const LANGUAGES: [&str; 2] = ["zh-tw", "en"];
pub const THEMES: [&str; 2] = ["dark", "light"];
pub const ACCENTS: [&str; 3] = ["teal", "blue", "violet"];

fn dark() -> String {
    "dark".into()
}
fn teal() -> String {
    "teal".into()
}
fn yes() -> bool {
    true
}
fn cache() -> u32 {
    128
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppSettings {
    pub language: String,
    pub width: f64,
    pub height: f64,
    #[serde(default = "dark")]
    pub theme: String,
    #[serde(default = "teal")]
    pub accent: String,
    #[serde(default = "yes")]
    pub images_enabled: bool,
    #[serde(default = "cache")]
    pub image_cache_mb: u32,
}

impl Default for AppSettings {
    fn default() -> Self {
        Self {
            language: "zh-tw".into(),
            width: 1280.0,
            height: 820.0,
            theme: dark(),
            accent: teal(),
            images_enabled: true,
            image_cache_mb: cache(),
        }
    }
}

impl AppSettings {
    /// Rejects unknown values and clamps window dimensions.
    pub fn validated(mut self) -> Result<Self> {
        if !THEMES.contains(&self.theme.as_str()) || !ACCENTS.contains(&self.accent.as_str()) {
            return fail("Invalid appearance setting");
        }
        if !(16..=512).contains(&self.image_cache_mb) {
            return fail("Invalid image cache limit");
        }
        if !LANGUAGES.contains(&self.language.as_str()) {
            return fail("Invalid language setting");
        }
        if !self.width.is_finite() || !self.height.is_finite() {
            return fail("Invalid window dimensions");
        }
        self.width = self.width.clamp(1060.0, 3000.0);
        self.height = self.height.clamp(720.0, 2000.0);
        Ok(self)
    }

    pub fn load(root: &Path) -> Result<Self> {
        let file = root.join("settings.json");
        if file.exists() {
            json::read::<Self>(&file)?.validated()
        } else {
            Ok(Self::default())
        }
    }

    pub fn save(&self, root: &Path) -> Result<()> {
        json::write(&root.join("settings.json"), &self.clone().validated()?)
    }

    pub fn cache_bytes(&self) -> u64 {
        u64::from(self.image_cache_mb) * 1024 * 1024
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn old_json_gains_defaults_and_round_trips() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("settings.json"), r#"{"language":"en","width":5000,"height":10}"#).unwrap();
        let loaded = AppSettings::load(dir.path()).unwrap();
        assert_eq!((loaded.theme.as_str(), loaded.accent.as_str(), loaded.image_cache_mb), ("dark", "teal", 128));
        assert_eq!((loaded.width, loaded.height), (3000.0, 720.0));
        let changed = AppSettings { theme: "light".into(), accent: "violet".into(), images_enabled: false, ..loaded };
        changed.save(dir.path()).unwrap();
        assert_eq!(AppSettings::load(dir.path()).unwrap(), changed);
        let bad = AppSettings { image_cache_mb: 8, ..AppSettings::default() };
        assert!(bad.save(dir.path()).is_err());
        assert!(AppSettings { language: "fr".into(), ..AppSettings::default() }.validated().is_err());
    }
}
