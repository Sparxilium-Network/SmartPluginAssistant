//! Bounded HTTP helpers shared by providers. Tests inject a local transport or endpoint.

use crate::{ApiError, ApiResult};
use std::io::Read;
use std::sync::{Condvar, Mutex};
use std::time::Duration;
use url::Url;

pub const USER_AGENT: &str = "SmartPluginAssistantRust/0.1 (Sparxilium)";
const MAX_RESPONSE: u64 = 8 * 1024 * 1024;

pub trait HttpTransport: Send + Sync {
    fn get(&self, url: &str) -> ApiResult<Vec<u8>>;
}

/// Counting semaphore bounding concurrent requests.
pub struct Semaphore {
    permits: Mutex<usize>,
    available: Condvar,
}

pub struct Permit<'a>(&'a Semaphore);

impl Semaphore {
    pub const fn new(permits: usize) -> Self {
        Self { permits: Mutex::new(permits), available: Condvar::new() }
    }

    pub fn acquire(&self) -> Permit<'_> {
        let mut permits = self.permits.lock().unwrap_or_else(|e| e.into_inner());
        while *permits == 0 {
            permits = self.available.wait(permits).unwrap_or_else(|e| e.into_inner());
        }
        *permits -= 1;
        Permit(self)
    }
}

impl Drop for Permit<'_> {
    fn drop(&mut self) {
        *self.0.permits.lock().unwrap_or_else(|e| e.into_inner()) += 1;
        self.0.available.notify_one();
    }
}

static REQUESTS: Semaphore = Semaphore::new(4);

/// HTTPS anywhere, plain HTTP only for loopback test fixtures; never embedded credentials.
pub fn validate_url(text: &str) -> ApiResult<Url> {
    let url = Url::parse(text).map_err(|_| ApiError::Transport("Unsafe URL".into()))?;
    let local = matches!(url.host_str(), Some("localhost" | "127.0.0.1" | "[::1]"));
    let scheme_ok = url.scheme() == "https" || (url.scheme() == "http" && local);
    if !scheme_ok || url.host_str().is_none() || !url.username().is_empty() || url.password().is_some()
    {
        return Err(ApiError::Transport("Unsafe URL".into()));
    }
    Ok(url)
}

/// Percent-encodes a query or path component (spaces as `%20`).
pub fn encode(text: &str) -> String {
    url::form_urlencoded::byte_serialize(text.as_bytes()).collect::<String>().replace('+', "%20")
}

/// Exact HTTPS host match without user info or a non-default port.
pub fn has_host(url: &Url, host: &str) -> bool {
    url.scheme() == "https"
        && url.host_str().is_some_and(|h| h.eq_ignore_ascii_case(host))
        && url.username().is_empty()
        && url.password().is_none()
        && url.port().is_none_or(|p| p == 443)
}

/// Blocking JSON API transport with timeouts, no redirects and a bounded body.
pub struct UreqTransport {
    agent: ureq::Agent,
}

impl Default for UreqTransport {
    fn default() -> Self {
        Self {
            agent: ureq::AgentBuilder::new()
                .timeout_connect(Duration::from_secs(12))
                .timeout(Duration::from_secs(30))
                .redirects(0)
                .user_agent(USER_AGENT)
                .build(),
        }
    }
}

impl HttpTransport for UreqTransport {
    fn get(&self, url: &str) -> ApiResult<Vec<u8>> {
        validate_url(url)?;
        let _permit = REQUESTS.acquire();
        let response = match self.agent.get(url).set("Accept", "application/json").call() {
            Ok(response) => response,
            Err(ureq::Error::Status(code, _)) => return Err(ApiError::Status(code)),
            Err(e) => return Err(ApiError::Transport(format!("API request failed: {e}"))),
        };
        if response.status() != 200 {
            return Err(ApiError::Status(response.status()));
        }
        let mut body = Vec::new();
        response
            .into_reader()
            .take(MAX_RESPONSE + 1)
            .read_to_end(&mut body)
            .map_err(|e| ApiError::Transport(format!("API response failed: {e}")))?;
        if body.len() as u64 > MAX_RESPONSE {
            return Err(ApiError::Transport("API response exceeds size limit".into()));
        }
        Ok(body)
    }
}

/// Parses JSON and maps syntax errors to `Invalid`.
pub fn json(bytes: &[u8]) -> ApiResult<serde_json::Value> {
    serde_json::from_slice(bytes).map_err(|e| ApiError::Invalid(format!("Invalid JSON: {e}")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn host_and_url_checks() {
        let ok = Url::parse("https://modrinth.com/plugin/x").unwrap();
        assert!(has_host(&ok, "MODRINTH.com"));
        assert!(!has_host(&Url::parse("https://evilmodrinth.com/").unwrap(), "modrinth.com"));
        assert!(!has_host(&Url::parse("https://u@modrinth.com/").unwrap(), "modrinth.com"));
        assert!(!has_host(&Url::parse("https://modrinth.com:8443/").unwrap(), "modrinth.com"));
        assert!(!has_host(&Url::parse("http://modrinth.com/").unwrap(), "modrinth.com"));
        assert!(validate_url("http://127.0.0.1:9/x").is_ok());
        assert!(validate_url("http://example.com/x").is_err());
        assert!(validate_url("https://a:b@example.com/x").is_err());
        assert!(validate_url("file:///etc/passwd").is_err());
        assert_eq!(encode("a b+c/é"), "a%20b%2Bc%2F%C3%A9");
    }
}
