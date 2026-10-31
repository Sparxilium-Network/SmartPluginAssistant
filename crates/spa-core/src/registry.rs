//! Compile-time provider registration. The host never branches on a provider ID.

use crate::{fail, Result};
use spa_api::PluginProvider;
use std::sync::Arc;

#[derive(Clone)]
pub struct ProviderRegistry {
    providers: Vec<Arc<dyn PluginProvider>>,
}

impl ProviderRegistry {
    /// Rejects duplicate provider IDs.
    pub fn new(providers: Vec<Arc<dyn PluginProvider>>) -> Result<Self> {
        let mut ids: Vec<String> = Vec::new();
        for provider in &providers {
            let info = provider.info();
            if ids.contains(&info.id) {
                return fail(format!("Duplicate provider: {}", info.id));
            }
            log::info!("Registered provider {} ({})", info.id, info.name);
            ids.push(info.id);
        }
        Ok(Self { providers })
    }

    pub fn all(&self) -> &[Arc<dyn PluginProvider>] {
        &self.providers
    }

    pub fn require(&self, id: &str) -> Result<Arc<dyn PluginProvider>> {
        match self.providers.iter().find(|p| p.info().id == id) {
            Some(provider) => Ok(provider.clone()),
            None => fail(format!("Provider unavailable: {id}")),
        }
    }
}
