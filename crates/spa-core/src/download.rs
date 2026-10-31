//! Downloads only into a new temporary file beside the destination; never into an installed JAR.

use crate::{fail, jar, paths, Result};
use spa_api::http::{validate_url, USER_AGENT};
use spa_api::Release;
use std::io::{Read, Write};
use std::path::Path;
use std::time::Duration;
use tempfile::TempPath;

pub const MAX_BYTES: u64 = 256 * 1024 * 1024;
const MAX_REDIRECTS: usize = 5;

pub struct DownloadService {
    agent: ureq::Agent,
}

impl Default for DownloadService {
    fn default() -> Self {
        Self::new(Duration::from_secs(180))
    }
}

/// Follows at most five redirects manually, refusing HTTPS to HTTP downgrades.
pub(crate) fn get_following(agent: &ureq::Agent, start: &str, accept: &str) -> Result<ureq::Response> {
    let mut url = validate_url(start)?;
    for _ in 0..=MAX_REDIRECTS {
        let response = match agent.get(url.as_str()).set("Accept", accept).call() {
            Ok(response) => response,
            Err(ureq::Error::Status(code, _)) => return fail(format!("HTTP {code}")),
            Err(e) => return fail(format!("Request failed: {e}")),
        };
        match response.status() {
            200 => return Ok(response),
            301 | 302 | 303 | 307 | 308 => {
                let Some(location) = response.header("Location") else {
                    return fail("Redirect has no location");
                };
                let next = url.join(location).map_err(|_| crate::Error::Message("Invalid redirect".into()))?;
                if url.scheme() == "https" && next.scheme() != "https" {
                    return fail("Insecure redirect rejected");
                }
                url = validate_url(next.as_str())?;
            }
            status => return fail(format!("HTTP {status}")),
        }
    }
    fail("Too many redirects")
}

pub(crate) fn agent(timeout: Duration) -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(15))
        .timeout(timeout)
        .redirects(0)
        .user_agent(USER_AGENT)
        .build()
}

impl DownloadService {
    pub fn new(timeout: Duration) -> Self {
        Self { agent: agent(timeout) }
    }

    /// Downloads and verifies size, every published checksum and JAR structure.
    /// The returned temporary file is deleted when dropped unless persisted.
    pub fn stage(&self, release: &Release, directory: &Path, progress: Option<&dyn Fn(u64)>) -> Result<TempPath> {
        let Some(url) = release.download.as_deref() else {
            return fail("Release has no direct download");
        };
        let directory = paths::directory(directory)?;
        let mut temp = tempfile::Builder::new().prefix(".spa-download-").suffix(".tmp").tempfile_in(&directory)?;
        let response = get_following(&self.agent, url, "*/*")
            .map_err(|e| crate::Error::Message(format!("Download failed: {e}")))?;
        // A declared length describes the encoded body; only compare it for identity transfers.
        let encoded = response.header("Content-Encoding").is_some_and(|v| !v.eq_ignore_ascii_case("identity"));
        let declared =
            response.header("Content-Length").and_then(|v| v.trim().parse::<u64>().ok()).filter(|_| !encoded);
        if declared.is_some_and(|d| d > MAX_BYTES) {
            return fail("Download exceeds size limit");
        }
        let mut reader = response.into_reader().take(MAX_BYTES + 1);
        let mut buffer = vec![0; 65536];
        let mut received = 0u64;
        loop {
            let count = reader.read(&mut buffer).map_err(|e| crate::Error::Message(format!("Download failed: {e}")))?;
            if count == 0 {
                break;
            }
            received += count as u64;
            if received > MAX_BYTES {
                return fail("Download exceeds size limit");
            }
            temp.write_all(&buffer[..count])?;
            if let Some(progress) = progress {
                progress(received);
            }
        }
        if declared.is_some_and(|d| d != received) {
            return fail("Truncated download");
        }
        temp.as_file().sync_all()?;
        let staged = temp.into_temp_path();
        if release.size > 0 && received != release.size {
            return fail("Download size mismatch");
        }
        for (key, expected) in &release.hashes {
            let algorithm = jar::Algorithm::from_key(key)?;
            if !jar::hash(&staged, algorithm)?.eq_ignore_ascii_case(expected) {
                return fail(format!("Download checksum mismatch: {key}"));
            }
        }
        jar::validate(&staged)?;
        log::info!(
            "Verified staged release {}:{}:{} ({received} bytes)",
            release.provider_id,
            release.project_id,
            release.id
        );
        Ok(staged)
    }
}
