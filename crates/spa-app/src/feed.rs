//! One submitted query. Failed requests do not advance it; only accepted pages do.

use spa_api::{Project, SearchPage, SearchRequest, TargetEnvironment};
use std::collections::HashSet;

pub const PAGE_SIZE: u32 = 20;

#[derive(Debug, Clone)]
pub struct ProjectFeed {
    query: String,
    target: TargetEnvironment,
    seen: HashSet<(String, String)>,
    page: u32,
    has_more: bool,
}

impl ProjectFeed {
    pub fn new(query: &str, target: TargetEnvironment) -> Self {
        Self { query: query.trim().to_string(), target, seen: HashSet::new(), page: 0, has_more: true }
    }

    /// A feed holding a single, already known project (URL resolution).
    pub fn exhausted(project: &Project, target: TargetEnvironment) -> Self {
        let mut feed = Self::new("", target);
        feed.seen.insert((project.provider_id.clone(), project.id.clone()));
        feed.has_more = false;
        feed
    }

    pub fn request(&self) -> Option<SearchRequest> {
        if !self.has_more {
            return None;
        }
        SearchRequest::new(&self.query, self.target.clone(), self.page, PAGE_SIZE).ok()
    }

    /// Unique projects only; a broken or changing API cannot loop on empty/repeated pages.
    pub fn append(&mut self, response: SearchPage) -> Vec<Project> {
        let added: Vec<Project> =
            response.projects.into_iter().filter(|p| self.seen.insert((p.provider_id.clone(), p.id.clone()))).collect();
        self.page += 1;
        self.has_more = response.has_more && !added.is_empty() && self.page <= 10_000;
        added
    }

    pub fn has_more(&self) -> bool {
        self.has_more
    }

    pub fn query(&self) -> &str {
        &self.query
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use spa_api::Access;

    fn project(id: &str) -> Project {
        Project {
            provider_id: "p".into(),
            id: id.into(),
            title: id.into(),
            author: String::new(),
            description: String::new(),
            page: "https://x".into(),
            access: Access::Direct,
            tested_game_versions: vec![],
            icon: None,
        }
    }

    fn target() -> TargetEnvironment {
        TargetEnvironment::new("paper", "1.21.4", Vec::<String>::new(), false).unwrap()
    }

    #[test]
    fn deduplicates_overlapping_pages_and_advances() {
        let mut feed = ProjectFeed::new(" q ", target());
        assert_eq!(feed.request().unwrap().page, 0);
        let first = feed.append(SearchPage { projects: vec![project("a"), project("b")], has_more: true });
        assert_eq!(first.len(), 2);
        assert_eq!(feed.request().unwrap().page, 1);
        let second = feed.append(SearchPage { projects: vec![project("b"), project("c")], has_more: true });
        assert_eq!(second.iter().map(|p| p.id.as_str()).collect::<Vec<_>>(), vec!["c"]);
        assert_eq!(feed.request().unwrap().query, "q");
    }

    #[test]
    fn stops_on_empty_repeated_or_final_pages() {
        let mut feed = ProjectFeed::new("", target());
        feed.append(SearchPage { projects: vec![project("a")], has_more: true });
        feed.append(SearchPage { projects: vec![project("a")], has_more: true });
        assert!(feed.request().is_none(), "duplicate-only page stops continuation");
        let mut empty = ProjectFeed::new("", target());
        empty.append(SearchPage { projects: vec![], has_more: true });
        assert!(!empty.has_more());
        let mut last = ProjectFeed::new("", target());
        last.append(SearchPage { projects: vec![project("a")], has_more: false });
        assert!(!last.has_more());
        assert!(!ProjectFeed::exhausted(&project("x"), target()).has_more());
    }
}
