//! Application layer shared by every frontend: context assembly, presenter, view models,
//! translations and preferences. No UI toolkit types appear here.

pub mod artwork;
pub mod choices;
pub mod context;
pub mod controller;
pub mod feed;
pub mod i18n;
pub mod settings;
pub mod vm;

pub use context::AppContext;
pub use controller::Controller;
pub use settings::AppSettings;
pub use vm::*;
