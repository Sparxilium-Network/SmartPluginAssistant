//! Application log file shared by every frontend.

use std::fs;
use std::path::Path;

/// Rotated once at startup when larger than this.
const MAX_BYTES: u64 = 5 * 1024 * 1024;

/// Logs at INFO to `<data dir>/logs/app.log`, moving an oversized log to `app.1.log` first.
/// HTTP library messages are dropped. Failures leave logging disabled rather than stopping
/// the application.
pub fn init(data_dir: &Path) {
    let logs = data_dir.join("logs");
    let _ = fs::create_dir_all(&logs);
    let file = logs.join("app.log");
    if fs::metadata(&file).is_ok_and(|m| m.len() > MAX_BYTES) {
        let _ = fs::rename(&file, logs.join("app.1.log"));
    }
    let config = simplelog::ConfigBuilder::new().add_filter_ignore_str("ureq").add_filter_ignore_str("rustls").build();
    if let Ok(output) = fs::OpenOptions::new().create(true).append(true).open(&file) {
        let _ = simplelog::WriteLogger::init(log::LevelFilter::Info, config, output);
    }
}
