//! Path validation for every managed write: no symbolic links, no escapes, portable names.

use crate::{fail, Result};
use std::fs;
use std::path::{Component, Path, PathBuf};

/// Absolute, lexically normalized path (`.`/`..` removed) without touching the filesystem.
pub fn normalize(path: &Path) -> Result<PathBuf> {
    let absolute = std::path::absolute(path)?;
    let mut result = PathBuf::new();
    for component in absolute.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => {
                result.pop();
            }
            other => result.push(other),
        }
    }
    Ok(result)
}

pub fn reject_links(path: &Path) -> Result<()> {
    let normalized = normalize(path)?;
    for ancestor in normalized.ancestors() {
        if let Ok(meta) = fs::symlink_metadata(ancestor) {
            if meta.file_type().is_symlink() {
                return fail(format!("Symbolic link rejected: {}", ancestor.display()));
            }
        }
    }
    Ok(())
}

/// Normalizes, rejects links and creates the directory.
pub fn directory(path: &Path) -> Result<PathBuf> {
    let result = normalize(path)?;
    reject_links(&result)?;
    fs::create_dir_all(&result)?;
    Ok(result)
}

pub fn file_name(name: &str) -> Result<&str> {
    let unsafe_char = |c: char| c.is_control() || "\\/:*?\"<>|".contains(c);
    if name.trim().is_empty()
        || name.chars().count() > 220
        || name == "."
        || name == ".."
        || name.chars().any(unsafe_char)
        || name.ends_with('.')
        || name.ends_with(' ')
    {
        return fail(format!("Unsafe filename: {name}"));
    }
    let stem = name.split('.').next().unwrap_or_default().to_ascii_uppercase();
    let reserved = matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL")
        || ((stem.starts_with("COM") || stem.starts_with("LPT"))
            && stem.len() == 4
            && matches!(stem.as_bytes()[3], b'1'..=b'9'));
    if reserved {
        return fail(format!("Reserved filename: {name}"));
    }
    Ok(name)
}

/// A direct child of `parent` with a validated name.
pub fn child(parent: &Path, name: &str) -> Result<PathBuf> {
    let base = normalize(parent)?;
    let result = base.join(file_name(name)?);
    if result.parent() != Some(base.as_path()) {
        return fail("Path escapes parent");
    }
    reject_links(&result)?;
    Ok(result)
}

pub fn is_plugin(name: &str) -> bool {
    let lower = name.to_lowercase();
    lower.ends_with(".jar") || lower.ends_with(".jar.disabled")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_unsafe_names() {
        for bad in ["", " ", ".", "..", "a/b", "a\\b", "c:x", "x.", "x ", "CON", "nul.jar", "COM1.txt", "a\u{1}"] {
            assert!(file_name(bad).is_err(), "{bad:?}");
        }
        for good in ["LuckPerms-5.4.jar", "COM0.jar", "console.jar", "外掛.jar"] {
            assert!(file_name(good).is_ok(), "{good:?}");
        }
    }

    #[test]
    fn child_stays_inside_parent() {
        let dir = crate::test_support::temp_dir();
        assert!(child(dir.path(), "a.jar").unwrap().starts_with(normalize(dir.path()).unwrap()));
        assert!(child(dir.path(), "..").is_err());
        assert!(is_plugin("A.JAR") && is_plugin("a.jar.disabled") && !is_plugin("a.zip"));
    }

    #[cfg(unix)]
    #[test]
    fn rejects_symlinks() {
        let dir = crate::test_support::temp_dir();
        let real = dir.path().join("real");
        fs::create_dir(&real).unwrap();
        std::os::unix::fs::symlink(&real, dir.path().join("link")).unwrap();
        assert!(directory(&dir.path().join("link/inner")).is_err());
    }
}
