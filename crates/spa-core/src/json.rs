//! JSON persistence by sibling temporary file, flush and atomic replacement.

use crate::{fail, paths, Result};
use serde::{de::DeserializeOwned, Serialize};
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

const MAX_JSON: u64 = 8 * 1024 * 1024;

pub fn read<T: DeserializeOwned>(path: &Path) -> Result<T> {
    paths::reject_links(path)?;
    if fs::metadata(path)?.len() > MAX_JSON {
        return fail(format!("JSON exceeds size limit: {}", path.display()));
    }
    Ok(serde_json::from_slice(&fs::read(path)?)?)
}

pub fn write<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    write_bytes(path, &serde_json::to_vec_pretty(value)?)
}

fn destination(path: &Path) -> Result<PathBuf> {
    let normalized = paths::normalize(path)?;
    let (Some(parent), Some(name)) = (normalized.parent(), normalized.file_name()) else {
        return fail("Invalid destination");
    };
    paths::child(&paths::directory(parent)?, &name.to_string_lossy())
}

/// Atomically replaces `path` with `bytes`; errors reach the caller and leave the old file.
pub fn write_bytes(path: &Path, bytes: &[u8]) -> Result<()> {
    let destination = destination(path)?;
    let parent = destination.parent().expect("validated child has a parent");
    let mut temp = tempfile::Builder::new().prefix(".spa-write-").suffix(".tmp").tempfile_in(parent)?;
    temp.write_all(bytes)?;
    temp.as_file().sync_all()?;
    temp.persist(&destination).map_err(|e| e.error)?;
    Ok(())
}

/// Validated destination whose parent directory exists; used by exporters.
pub fn prepare_destination(path: &Path) -> Result<PathBuf> {
    destination(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip_and_replace() {
        let dir = crate::test_support::temp_dir();
        let file = dir.path().join("nested/value.json");
        write(&file, &vec![1, 2]).unwrap();
        write(&file, &vec![3]).unwrap();
        assert_eq!(read::<Vec<i32>>(&file).unwrap(), vec![3]);
        let leftovers = fs::read_dir(dir.path().join("nested")).unwrap().count();
        assert_eq!(leftovers, 1);
    }
}
