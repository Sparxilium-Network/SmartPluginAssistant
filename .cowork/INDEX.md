# .cowork 索引

給 AI 查閱的工作紀錄。先讀本檔，再只開啟相關檔案。規則見 `.claude/Common-dev-instruct.md` 第 5 章（各 Agent 的規範檔）。

| 檔案 | 摘要 | 關鍵字 |
|------|------|--------|
| `decisions/rust-rewrite-scope.md` | Rust 重寫的來源、Slint 與前後端分離、平台範圍、資料目錄、預設 Paper | rewrite, slint, provider, modrinth, hangar, data-dir, sol, javafx |
| `decisions/sqlite-storage.md` | SQLite spa.db、存檔格式版本、遷移與備份、Sol JSON 匯入、v2 欄位 | sqlite, schema, migration, backup, user_version |
| `decisions/library-table-and-menus.md` | 上次更新／上次檢查欄位、欄位自訂、匯出選單、懸浮說明、overlay 理由 | table, columns, export, tooltip, overlay, slint |
| `decisions/repository-and-branches.md` | 目標 GitHub 儲存庫、名稱、javafx／main 分支、User-Agent、不主動 push | github, branch, javafx, main, user-agent, push |
| `decisions/symlink-path-policy.md` | 拒絕路徑中任何符號連結，使用者決定維持；測試用 temp_dir() | symlink, paths, macos, security, tempdir |
| `verification/workspace-tests.md` | 最新 fmt／Clippy／測試結果與 57 個測試的涵蓋範圍 | test, clippy, fmt, coverage |
| `verification/snapshot-walkthroughs.md` | 各階段 --snapshot 線上流程、截圖檢查與 build.ps1／--smoke 結果 | snapshot, smoke, build, ui, screenshot |
| `verification/ci.md` | GitHub Actions 設定、macOS /var 符號連結失敗與修正 | ci, github-actions, macos |
| `verification/unverified.md` | 尚未驗證項目與已知限制 | unverified, limitation, todo |
| `progress/history.md` | 已完成項目與對應 commit | progress, history, commit |
| `progress/backlog.md` | 後續候選工作 | backlog, todo, next |
| `reasoning/frontend-separation.md` | 前後端分離檢查：已分離的證據與 main.rs 剩餘的應用邏輯 | architecture, frontend, backend, controller, spa-desktop |
