//! Host core: safe storage, instance index, install transactions, updates and exports.
//! Depends on `spa-api` only; never on a provider implementation or UI framework.

pub mod db;
pub mod download;
pub mod environment;
pub mod export;
pub mod files;
pub mod image_cache;
pub mod instance;
pub mod inventory;
pub mod jar;
pub mod json;
pub mod lock;
pub mod manager;
pub mod paths;
pub mod registry;
#[cfg(test)]
mod test_support;

pub use spa_api as api;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("{0}")]
    Io(#[from] std::io::Error),
    #[error("{0}")]
    Api(#[from] spa_api::ApiError),
    #[error("Database error: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("Invalid JSON: {0}")]
    Json(#[from] serde_json::Error),
    #[error("{0}")]
    Message(String),
}

pub type Result<T> = std::result::Result<T, Error>;

pub fn fail<T>(message: impl Into<String>) -> Result<T> {
    Err(Error::Message(message.into()))
}

pub fn now_millis() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}
