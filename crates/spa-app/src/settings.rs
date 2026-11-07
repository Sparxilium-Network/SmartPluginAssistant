//! Persisted preferences, one database row per field (JSON-encoded value). Missing fields take
//! defaults so older data keeps working; values are validated before use.

use crate::columns::{self, ColumnSetting};
use serde::{Deserialize, Serialize};
use spa_core::db::Database;
use spa_core::{fail, Result};

pub const LANGUAGES: [&str; 2] = ["zh-tw", "en"];
pub const THEMES: [&str; 2] = ["dark", "light"];
pub const ACCENTS: [&str; 3] = ["teal", "blue", "violet"];

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct AppSettings {
    pub language: String,
    pub width: f64,
    pub height: f64,
    pub theme: String,
    pub accent: String,
    pub images_enabled: bool,
    pub image_cache_mb: u32,
    /// Installed-plugin table layout.
    pub library_columns: Vec<ColumnSetting>,
}

impl Default for AppSettings {
    fn default() -> Self {
        Self {
            language: "zh-tw".into(),
            width: 1280.0,
            height: 820.0,
            theme: "dark".into(),
            accent: "teal".into(),
            images_enabled: true,
            image_cache_mb: 128,
            library_columns: columns::defaults(),
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
        self.library_columns = columns::normalize(self.library_columns);
        Ok(self)
    }

    /// Unknown keys are ignored; an invalid value fails rather than being silently replaced.
    pub fn load(db: &Database) -> Result<Self> {
        let mut object = serde_json::Map::new();
        for (key, value) in db.settings()? {
            object.insert(key, serde_json::from_str(&value)?);
        }
        serde_json::from_value::<Self>(serde_json::Value::Object(object))?.validated()
    }

    /// All fields are written in one transaction.
    pub fn save(&self, db: &Database) -> Result<()> {
        let serde_json::Value::Object(object) = serde_json::to_value(self.clone().validated()?)? else {
            return fail("Settings must serialize to an object");
        };
        let rows: Vec<(String, String)> = object.into_iter().map(|(k, v)| (k, v.to_string())).collect();
        db.put_settings(&rows)
    }

    pub fn cache_bytes(&self) -> u64 {
        u64::from(self.image_cache_mb) * 1024 * 1024
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Temporary directory with no symbolic links in its path. On macOS the default temporary
    /// directory sits under `/var`, a link to `/private/var`, which the path checks reject.
    fn temp_dir() -> tempfile::TempDir {
        let base = std::env::temp_dir();
        #[cfg(unix)]
        let base = base.canonicalize().unwrap();
        tempfile::tempdir_in(base).unwrap()
    }

    #[test]
    fn missing_fields_gain_defaults_and_round_trip() {
        let dir = temp_dir();
        let db = Database::open(dir.path()).unwrap();
        assert_eq!(AppSettings::load(&db).unwrap(), AppSettings::default());
        db.put_settings(&[
            ("language".into(), "\"en\"".into()),
            ("width".into(), "5000".into()),
            ("height".into(), "10".into()),
        ])
        .unwrap();
        let loaded = AppSettings::load(&db).unwrap();
        assert_eq!((loaded.theme.as_str(), loaded.accent.as_str(), loaded.image_cache_mb), ("dark", "teal", 128));
        assert_eq!((loaded.language.as_str(), loaded.width, loaded.height), ("en", 3000.0, 720.0));
        let changed = AppSettings { theme: "light".into(), accent: "violet".into(), images_enabled: false, ..loaded };
        changed.save(&db).unwrap();
        assert_eq!(AppSettings::load(&db).unwrap(), changed);
        let bad = AppSettings { image_cache_mb: 8, ..AppSettings::default() };
        assert!(bad.save(&db).is_err());
        assert_eq!(AppSettings::load(&db).unwrap(), changed, "rejected save leaves stored settings intact");
        db.put_settings(&[("theme".into(), "\"neon\"".into())]).unwrap();
        assert!(AppSettings::load(&db).is_err());
    }
}
