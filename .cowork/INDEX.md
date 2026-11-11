# .cowork 索引

給 AI 查閱的工作紀錄。先讀本檔，再只開啟相關檔案。規則見 `.claude/Common-dev-instruct.md` 第 5 章（各 Agent 的規範檔）。

| 檔案 | 摘要 | 關鍵字 |
|------|------|--------|
| `decisions/rust-rewrite-scope.md` | Rust 重寫的來源、Slint 與前後端分離、平台範圍、資料目錄、預設 Paper | rewrite, slint, provider, modrinth, hangar, data-dir, sol, javafx |
| `decisions/sqlite-storage.md` | SQLite spa.db、存檔格式版本、遷移與備份、Sol JSON 匯入、v2 欄位 | sqlite, schema, migration, backup, user_version |
| `decisions/library-table-and-menus.md` | 上次更新／上次檢查欄位、欄位自訂、匯出選單、懸浮說明、overlay 理由、設定視窗導覽式版面與即時儲存、本機插件選單、欄位只在設定 | table, columns, export, tooltip, overlay, slint, settings, about, providers |
| `decisions/repository-and-branches.md` | 目標 GitHub 儲存庫、名稱、javafx／main 分支、User-Agent、不主動 push | github, branch, javafx, main, user-agent, push |
| `decisions/symlink-path-policy.md` | 拒絕路徑中任何符號連結，使用者決定維持；測試用 temp_dir() | symlink, paths, macos, security, tempdir |
| `decisions/ci-prerelease.md` | CI 自動發布 Pre-release：命名 dev-run.attempt-sha、平台、權限與安全設定 | ci, release, prerelease, github-actions, workflow, permissions |
| `decisions/app-icon.md` | 圖示來源 assets/icon.png、build.rs 產生 ico 與視窗圖示、Slint 圖示需編譯期嵌入的原因 | icon, ico, winresource, build.rs, slint |
| `verification/workspace-tests.md` | 最新 fmt／Clippy／測試結果與 58 個測試的涵蓋範圍 | test, clippy, fmt, coverage |
| `verification/snapshot-walkthroughs.md` | 各階段 --snapshot 線上流程、截圖檢查、build.ps1／--smoke 結果與 dist 打包位置 | snapshot, smoke, build, ui, screenshot |
| `verification/ci.md` | GitHub Actions 設定、macOS /var 符號連結失敗與修正、三平台已通過、新發布流程的本機驗證 | ci, github-actions, macos |
| `verification/app-icon.md` | exe 圖示與視窗圖示的實際驗證結果 | icon, verification, windows |
| `verification/unverified.md` | 尚未驗證項目與已知限制 | unverified, limitation, todo |
| `progress/history.md` | 已完成項目與對應 commit | progress, history, commit |
| `progress/backlog.md` | 後續候選工作 | backlog, todo, next |
| `reasoning/frontend-separation.md` | 前後端分離檢查與補強：main.rs 剩餘應用邏輯已移到 spa-app | architecture, frontend, backend, controller, spa-desktop |
