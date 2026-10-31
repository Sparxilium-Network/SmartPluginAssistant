//! Strict UTF-8 catalogs. Every user-visible string goes through `tr`/`trf`.

use std::collections::HashMap;
use std::fmt::Display;
use std::sync::{OnceLock, RwLock};

const CATALOGS: [(&str, &str); 2] =
    [("zh-tw", include_str!("../lang/zh-tw.lang")), ("en", include_str!("../lang/en.lang"))];

pub fn parse(text: &str) -> Result<HashMap<String, String>, String> {
    let mut result = HashMap::new();
    for line in text.lines() {
        if line.trim().is_empty() || line.starts_with('#') {
            continue;
        }
        let Some((key, value)) = line.split_once('=').filter(|(k, _)| !k.trim().is_empty()) else {
            return Err(format!("Invalid catalog entry: {line}"));
        };
        if result.insert(key.trim().to_string(), value.replace("\\n", "\n")).is_some() {
            return Err(format!("Duplicate translation: {key}"));
        }
    }
    Ok(result)
}

fn catalogs() -> &'static HashMap<&'static str, HashMap<String, String>> {
    static VALUE: OnceLock<HashMap<&'static str, HashMap<String, String>>> = OnceLock::new();
    VALUE.get_or_init(|| {
        CATALOGS.iter().map(|(code, text)| (*code, parse(text).expect("bundled catalog is valid"))).collect()
    })
}

static LANGUAGE: RwLock<&'static str> = RwLock::new("zh-tw");

pub fn set_language(code: &str) -> bool {
    match CATALOGS.iter().find(|(c, _)| *c == code) {
        Some((code, _)) => {
            *LANGUAGE.write().unwrap_or_else(|e| e.into_inner()) = code;
            true
        }
        None => false,
    }
}

pub fn language() -> &'static str {
    *LANGUAGE.read().unwrap_or_else(|e| e.into_inner())
}

/// Missing keys are a programming error caught by the parity tests; show the key.
pub fn tr(key: &str) -> String {
    catalogs()[language()].get(key).cloned().unwrap_or_else(|| {
        log::error!("Missing translation: {key}");
        key.to_string()
    })
}

/// Quoted literals in `source` that look like catalog keys but are missing. Frontends use this
/// in their own tests to keep their labels in sync with the catalogs.
pub fn missing_keys(source: &str) -> Vec<String> {
    let en = &catalogs()["en"];
    let groups: Vec<&str> = en.keys().filter_map(|k| k.split('.').next()).collect();
    let file_like = |part: &str| part.rsplit('.').next().is_some_and(|ext| ["log", "json", "jar", "zip", "sh"].contains(&ext));
    source
        .split('"')
        .skip(1)
        .step_by(2)
        .filter(|part| {
            !file_like(part)
                && part.contains('.')
                && part.chars().all(|c| c.is_ascii_lowercase() || c == '.' || c == '_' || c == '-')
                && groups.contains(&part.split('.').next().unwrap_or_default())
                && !en.contains_key(*part)
        })
        .map(str::to_string)
        .collect()
}

pub fn trf(key: &str, args: &[&dyn Display]) -> String {
    let mut result = tr(key);
    for (index, arg) in args.iter().enumerate() {
        result = result.replace(&format!("{{{index}}}"), &arg.to_string());
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeSet;

    fn placeholders(text: &str) -> BTreeSet<String> {
        let mut found = BTreeSet::new();
        let mut rest = text;
        while let Some(start) = rest.find('{') {
            match rest[start..].find('}') {
                Some(end) => {
                    found.insert(rest[start..start + end + 1].to_string());
                    rest = &rest[start + end + 1..];
                }
                None => break,
            }
        }
        found
    }

    #[test]
    fn catalogs_have_key_and_placeholder_parity() {
        let zh = &catalogs()["zh-tw"];
        let en = &catalogs()["en"];
        let zh_keys: BTreeSet<_> = zh.keys().collect();
        let en_keys: BTreeSet<_> = en.keys().collect();
        assert_eq!(zh_keys, en_keys);
        for key in zh.keys() {
            assert_eq!(placeholders(&zh[key]), placeholders(&en[key]), "{key}");
        }
    }

    #[test]
    fn controller_keys_exist() {
        assert_eq!(missing_keys(include_str!("controller.rs")), Vec::<String>::new());
    }

    #[test]
    fn parse_rejects_duplicates_and_decodes_newlines() {
        assert!(parse("a=1\na=2").is_err());
        assert!(parse("novalue").is_err());
        assert_eq!(parse("# c\na=x\\ny").unwrap()["a"], "x\ny");
    }
}
