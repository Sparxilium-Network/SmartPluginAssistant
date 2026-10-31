//! Plugin-only ZIP export and pinned, checksum-verified deployment scripts.

use crate::instance::ServerInstance;
use crate::inventory::{InstalledPlugin, InventoryStore};
use crate::registry::ProviderRegistry;
use crate::{fail, jar, json, paths, Result};
use serde::Serialize;
use spa_api::http::{validate_url, USER_AGENT};
use spa_api::TargetEnvironment;
use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::Path;
use std::sync::Arc;

#[derive(Serialize)]
struct Manifest<'a> {
    schema: i32,
    name: &'a str,
    target: &'a TargetEnvironment,
    plugins: &'a [InstalledPlugin],
}

pub struct ExportService {
    inventory: Arc<InventoryStore>,
    providers: ProviderRegistry,
}

/// POSIX single-quote quoting.
pub fn shell(text: &str) -> String {
    format!("'{}'", text.replace('\'', "'\"'\"'"))
}

const SCRIPT_HEADER: &str = r#"#!/usr/bin/env bash
set -u
mkdir -p plugins
failures=0
checksum() {
  if command -v sha512sum >/dev/null 2>&1; then sha512sum "$1" | awk '{print $1}';
  elif command -v shasum >/dev/null 2>&1; then shasum -a 512 "$1" | awk '{print $1}';
  else return 1; fi
}
download() {
  local name="$1" url="$2" expected="$3" tmp actual
  tmp=$(mktemp plugins/.spa-download.XXXXXX) || return 1
  if ! curl --fail --location --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 180 --user-agent '@UA@' --output "$tmp" -- "$url"; then
    rm -f -- "$tmp"; return 1
  fi
  actual=$(checksum "$tmp") || { rm -f -- "$tmp"; return 1; }
  if [ "$actual" != "$expected" ]; then rm -f -- "$tmp"; return 1; fi
  if [ -e "plugins/$name" ] || [ -L "plugins/$name" ]; then
    printf '%s\n' "Refusing to overwrite plugins/$name"; rm -f -- "$tmp"; return 1
  fi
  mv -- "$tmp" "plugins/$name"
}
"#;

impl ExportService {
    pub fn new(inventory: Arc<InventoryStore>, providers: ProviderRegistry) -> Self {
        Self { inventory, providers }
    }

    /// Only JARs and an explicit manifest; never worlds, configs or whole directories.
    pub fn zip(&self, instance: &ServerInstance, destination: &Path) -> Result<()> {
        let plugins = self.inventory.scan(instance)?;
        let target = json::prepare_destination(destination)?;
        let parent = target.parent().expect("validated destination has a parent");
        let temp = tempfile::Builder::new().prefix(".spa-export-").suffix(".zip").tempfile_in(parent)?;
        {
            let mut zip = zip::ZipWriter::new(BufWriter::new(temp.as_file()));
            let options = zip::write::SimpleFileOptions::default()
                .compression_method(zip::CompressionMethod::Deflated);
            let zip_err = |e: zip::result::ZipError| crate::Error::Message(format!("ZIP export failed: {e}"));
            zip.start_file("manifest.json", options).map_err(zip_err)?;
            let manifest = Manifest { schema: 1, name: &instance.name, target: &instance.target, plugins: &plugins };
            zip.write_all(&serde_json::to_vec_pretty(&manifest)?)?;
            for plugin in &plugins {
                let source = paths::child(&instance.plugins_directory(), &plugin.file_name)?;
                if jar::hash(&source, jar::Algorithm::Sha512)? != plugin.sha512 {
                    return fail("Plugin changed during export");
                }
                zip.start_file(format!("plugins/{}", plugin.file_name), options).map_err(zip_err)?;
                std::io::copy(&mut File::open(&source)?, &mut zip)?;
            }
            zip.finish().map_err(zip_err)?.flush()?;
        }
        temp.as_file().sync_all()?;
        temp.persist(&target).map_err(|e| e.error)?;
        log::info!("Exported plugin archive for {} to {}", instance.id, target.display());
        Ok(())
    }

    /// Pinned release URLs only; unlinked plugins are listed for manual upload.
    pub fn script(&self, instance: &ServerInstance, destination: &Path) -> Result<()> {
        let mut script = SCRIPT_HEADER.replace("@UA@", USER_AGENT);
        script.push_str(&format!("printf '%s\\n' {}\n", shell(&format!("Smart Plugin Assistant: {}", instance.name))));
        for plugin in self.inventory.scan(instance)? {
            paths::file_name(&plugin.file_name)?;
            let (Some(provider), Some(project), Some(release)) =
                (&plugin.provider_id, &plugin.project_id, &plugin.release_id)
            else {
                script.push_str(&format!(
                    "printf '%s\\n' {}\n",
                    shell(&format!("Manual upload required: {}", plugin.file_name))
                ));
                continue;
            };
            let release = self.providers.require(provider)?.release(Some(project), release, &instance.target)?;
            let Some(url) = release.download else {
                return fail(format!("No pinned download available: {}", plugin.file_name));
            };
            let url = validate_url(&url)?;
            script.push_str(&format!(
                "if ! download {} {} {}; then failures=$((failures + 1)); printf '%s\\n' {}; fi\n",
                shell(&plugin.file_name),
                shell(url.as_str()),
                shell(&plugin.sha512),
                shell(&format!("Failed: {}", plugin.file_name))
            ));
        }
        script.push_str("printf 'Failures: %s\\n' \"$failures\"\n[ \"$failures\" -eq 0 ]\n");
        json::write_bytes(destination, script.as_bytes())?;
        log::info!("Exported deployment script for {} to {}", instance.id, destination.display());
        Ok(())
    }
}
