//! Framework-neutral view models and events. A frontend only renders these and forwards actions,
//! so replacing the UI toolkit never touches application behaviour.

use crate::artwork::Rgba;
use std::sync::Arc;

pub type Sink = Arc<dyn Fn(UiEvent) + Send + Sync>;

#[derive(Debug, Clone, PartialEq)]
pub struct InstanceVm {
    pub id: String,
    pub name: String,
    pub detail: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PluginVm {
    pub file_name: String,
    pub version: String,
    pub source: String,
    pub state: String,
    pub update: String,
    /// When the file content last changed.
    pub updated_at: String,
    /// When an update check last succeeded.
    pub checked_at: String,
    pub enabled: bool,
    pub linked: bool,
    pub has_update: bool,
    pub checked: bool,
}

/// One installed-plugin table column. Widths are logical pixels.
#[derive(Debug, Clone, PartialEq)]
pub struct ColumnVm {
    pub id: String,
    pub title: String,
    pub visible: bool,
    pub width: f32,
}

#[derive(Debug, Clone, PartialEq)]
pub struct LibraryVm {
    pub title: String,
    pub environment: String,
    pub summary: String,
    pub plugins: Vec<PluginVm>,
    /// No plugin files at all (not merely filtered out).
    pub empty: bool,
    pub selected_count: usize,
}

#[derive(Debug, Clone, PartialEq)]
pub struct CardVm {
    pub key: String,
    pub title: String,
    pub author: String,
    pub description: String,
    pub provider: String,
    pub initial: String,
    pub icon_url: Option<String>,
    pub artwork: Option<Rgba>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct MarketStatusVm {
    pub text: String,
    pub loading: bool,
    pub failed: bool,
    pub can_load_more: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct DetailVm {
    pub key: String,
    pub title: String,
    pub author: String,
    pub provider: String,
    pub description: String,
    pub page: String,
    pub initial: String,
    pub icon_url: Option<String>,
    pub artwork: Option<Rgba>,
    /// Purchase or download happens on the official website.
    pub external: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ReleaseVm {
    pub label: String,
    pub detail: String,
    pub notice: String,
    pub verified: bool,
    pub installable: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub enum ReleasesVm {
    Loading,
    /// Empty means no eligible direct-download release exists.
    Ready(Vec<ReleaseVm>),
    Failed(String),
}

#[derive(Debug, Clone, PartialEq)]
pub struct PlanVm {
    pub summary: String,
    /// Website-only dependencies, empty when none.
    pub manual: String,
    pub needs_confirmation: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct InstanceDialogVm {
    pub editing: bool,
    pub name: String,
    pub directory: String,
    pub prerelease: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Choice {
    pub name: String,
    pub checked: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct CatalogVm {
    pub status: String,
    pub loading: bool,
    pub failed: bool,
    pub loaders: Vec<String>,
    pub versions: Vec<String>,
    pub loader_index: i32,
    pub version_index: i32,
    pub extras: Vec<Choice>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct InstanceForm {
    pub name: String,
    pub directory: String,
    pub loader: String,
    pub version: String,
    pub extras: Vec<String>,
    pub prerelease: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SettingsVm {
    pub dark: bool,
    pub accent: String,
    pub language: String,
    pub images_enabled: bool,
    pub cache_mb: u32,
}

#[derive(Debug, Clone, PartialEq)]
pub enum UiEvent {
    Busy(bool),
    Status(String),
    Message {
        title: String,
        text: String,
        error: bool,
    },
    /// Ask the user; answer with `Controller::confirm`.
    Confirm(String),
    Appearance {
        dark: bool,
        accent: String,
    },
    /// Translated strings changed; rebind static labels.
    Language(String),
    Instances {
        items: Vec<InstanceVm>,
        selected: i32,
    },
    Library(Option<LibraryVm>),
    /// Installed-plugin table layout, in display order.
    Columns(Vec<ColumnVm>),
    MarketSources {
        names: Vec<String>,
        selected: i32,
    },
    MarketReset,
    MarketAppend(Vec<CardVm>),
    MarketStatus(MarketStatusVm),
    /// `None` removes artwork (images disabled or cache cleared).
    Artwork {
        url: String,
        image: Option<Rgba>,
    },
    Detail(Option<DetailVm>),
    Releases(ReleasesVm),
    Plan(PlanVm),
    InstanceDialog(InstanceDialogVm),
    Catalog(CatalogVm),
    Extras(Vec<Choice>),
    CloseInstanceDialog,
    Settings(SettingsVm),
    CacheUsage(String),
    /// Data format version and database location.
    Storage(String),
}
