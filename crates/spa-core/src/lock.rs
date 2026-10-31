//! Process lock preventing concurrent application writers on one data directory.

use crate::{paths, Error, Result};
use std::fs::{File, OpenOptions, TryLockError};
use std::path::Path;

pub struct ApplicationLock {
    _file: File,
}

impl ApplicationLock {
    pub fn acquire(root: &Path) -> Result<Self> {
        let path = paths::child(&paths::directory(root)?, "application.lock")?;
        let file = OpenOptions::new().create(true).write(true).truncate(false).open(path)?;
        match file.try_lock() {
            Ok(()) => Ok(Self { _file: file }),
            Err(TryLockError::WouldBlock) => Err(Error::Message("Application data is already in use".into())),
            Err(TryLockError::Error(e)) => Err(e.into()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn second_lock_fails_until_released() {
        let dir = tempfile::tempdir().unwrap();
        let first = ApplicationLock::acquire(dir.path()).unwrap();
        assert!(ApplicationLock::acquire(dir.path()).is_err());
        drop(first);
        assert!(ApplicationLock::acquire(dir.path()).is_ok());
    }
}
