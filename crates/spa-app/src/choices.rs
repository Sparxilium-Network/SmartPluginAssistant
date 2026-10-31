//! Selection policy only: available options always come from the fetched catalog.

use spa_api::{EnvironmentCatalog, TargetEnvironment};

fn push_unique(list: &mut Vec<String>, value: &str) {
    if !list.iter().any(|v| v == value) {
        list.push(value.to_string());
    }
}

/// Catalog loaders plus any saved values that disappeared from the remote catalog.
pub fn loaders(catalog: &EnvironmentCatalog, saved: Option<&TargetEnvironment>) -> Vec<String> {
    let mut result = catalog.loaders.clone();
    if let Some(saved) = saved {
        push_unique(&mut result, &saved.loader);
        for extra in &saved.extra_loaders {
            push_unique(&mut result, extra);
        }
    }
    result
}

pub fn versions(catalog: &EnvironmentCatalog, saved: Option<&TargetEnvironment>) -> Vec<String> {
    let mut result = catalog.game_versions.clone();
    if let Some(saved) = saved {
        push_unique(&mut result, &saved.minecraft_version);
    }
    result
}

/// Upstream loaders commonly compatible with `loader`, limited to available options.
pub fn defaults(loader: &str, available: &[String]) -> Vec<String> {
    let preferred: &[&str] = match loader {
        "paper" => &["spigot", "bukkit"],
        "purpur" => &["paper", "spigot", "bukkit"],
        "spigot" => &["bukkit"],
        "waterfall" => &["bungeecord"],
        _ => &[],
    };
    preferred
        .iter()
        .filter(|p| **p != loader && available.iter().any(|a| a == *p))
        .map(|p| p.to_string())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_saved_values_and_filters_defaults() {
        let catalog = EnvironmentCatalog::new(vec!["paper".into(), "spigot".into()], vec!["1.21.4".into()]).unwrap();
        let saved = TargetEnvironment::new("folia", "1.20.1", vec!["bukkit".into()], false).unwrap();
        assert_eq!(loaders(&catalog, Some(&saved)), vec!["paper", "spigot", "folia", "bukkit"]);
        assert_eq!(versions(&catalog, Some(&saved)), vec!["1.21.4", "1.20.1"]);
        assert_eq!(defaults("paper", &catalog.loaders), vec!["spigot"]);
        assert!(defaults("velocity", &catalog.loaders).is_empty());
    }
}
