//! Checksums, JAR structure validation and plugin descriptor versions.

use crate::{fail, Result};
use sha1::Sha1;
use sha2::{Digest, Sha256, Sha512};
use std::fs::File;
use std::io::{BufReader, Read};
use std::path::Path;

const MAX_EXPANDED: u64 = 1024 * 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Algorithm {
    Sha512,
    Sha256,
    Sha1,
}

impl Algorithm {
    pub fn from_key(key: &str) -> Result<Self> {
        match key.to_ascii_lowercase().as_str() {
            "sha512" => Ok(Self::Sha512),
            "sha256" => Ok(Self::Sha256),
            "sha1" => Ok(Self::Sha1),
            _ => fail(format!("Unsupported published checksum: {key}")),
        }
    }
}

fn digest<D: Digest>(mut input: impl Read) -> Result<String> {
    let mut hasher = D::new();
    let mut buffer = vec![0; 65536];
    loop {
        let count = input.read(&mut buffer)?;
        if count == 0 {
            return Ok(hex::encode(hasher.finalize()));
        }
        hasher.update(&buffer[..count]);
    }
}

pub fn hash(path: &Path, algorithm: Algorithm) -> Result<String> {
    let file = File::open(path)?;
    match algorithm {
        Algorithm::Sha512 => digest::<Sha512>(file),
        Algorithm::Sha256 => digest::<Sha256>(file),
        Algorithm::Sha1 => digest::<Sha1>(file),
    }
}

fn zip_error(error: zip::result::ZipError) -> crate::Error {
    crate::Error::Message(format!("Invalid JAR: {error}"))
}

/// Reads every entry so truncated or disguised archives fail before installation.
pub fn validate(path: &Path) -> Result<()> {
    let mut archive = zip::ZipArchive::new(BufReader::new(File::open(path)?)).map_err(zip_error)?;
    if archive.is_empty() {
        return fail("Empty JAR");
    }
    let mut expanded = 0u64;
    let mut buffer = vec![0; 65536];
    for index in 0..archive.len() {
        let mut entry = archive.by_index(index).map_err(zip_error)?;
        if entry.is_dir() {
            continue;
        }
        loop {
            let read = entry.read(&mut buffer).map_err(|e| crate::Error::Message(format!("Invalid JAR: {e}")))?;
            if read == 0 {
                break;
            }
            expanded += read as u64;
            if expanded > MAX_EXPANDED {
                return fail("JAR expanded size exceeds limit");
            }
        }
    }
    Ok(())
}

const DESCRIPTORS: [&str; 5] =
    ["plugin.yml", "paper-plugin.yml", "bungee.yml", "bungeecord.yml", "velocity-plugin.json"];

/// Version declared by the first recognized plugin descriptor, or empty.
pub fn version(path: &Path) -> Result<String> {
    let mut archive = zip::ZipArchive::new(BufReader::new(File::open(path)?)).map_err(zip_error)?;
    for name in DESCRIPTORS {
        let Ok(entry) = archive.by_name(name) else { continue };
        let mut bytes = Vec::new();
        entry.take(65537).read_to_end(&mut bytes)?;
        if bytes.len() > 65536 {
            continue;
        }
        let text = String::from_utf8_lossy(&bytes);
        let value = if name.ends_with(".json") {
            serde_json::from_str::<serde_json::Value>(&text)
                .ok()
                .and_then(|v| v.get("version").and_then(|v| v.as_str()).map(str::to_string))
                .unwrap_or_default()
        } else {
            yaml_version(&text)
        };
        if !value.trim().is_empty() {
            return Ok(value.trim().to_string());
        }
    }
    Ok(String::new())
}

fn yaml_version(text: &str) -> String {
    for line in text.lines() {
        let Some(rest) = line.strip_prefix("version") else { continue };
        let Some(value) = rest.trim_start().strip_prefix(':') else { continue };
        let value = value.trim();
        let value = value.strip_prefix(['\'', '"']).unwrap_or(value);
        let value = value.strip_suffix(['\'', '"']).unwrap_or(value);
        return value.to_string();
    }
    String::new()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn yaml_versions() {
        assert_eq!(yaml_version("name: A\nversion: '1.2.3'\n"), "1.2.3");
        assert_eq!(yaml_version("version : \"2\""), "2");
        assert_eq!(yaml_version("api-version: 1.20\n"), "");
        assert_eq!(yaml_version("versions: x\n"), "");
    }
}
