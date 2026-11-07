//! Helpers shared by unit tests.

/// Temporary directory with no symbolic links in its path. On macOS the default temporary
/// directory sits under `/var`, a link to `/private/var`, which the path checks reject.
pub fn temp_dir() -> tempfile::TempDir {
    let base = std::env::temp_dir();
    #[cfg(unix)]
    let base = base.canonicalize().unwrap();
    tempfile::tempdir_in(base).unwrap()
}
