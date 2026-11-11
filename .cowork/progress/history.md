# 開發進度

## 2026-10-31：初版
1. 建立 workspace，確認決策（見 `decisions/rust-rewrite-scope.md`）。
2. `spa-api`：provider trait、資料契約、驗證規則、受限 HTTPS 傳輸。
3. `spa-core`：安全路徑、原子寫檔、程序鎖、實例索引、雜湊綁定清單、暫存驗證下載、可回滾的安裝／匯入／停用／移除／辨識、依賴規劃、更新報告、ZIP／腳本匯出、環境清單快取、隔離的圖片快取。
4. Provider：Modrinth（由 Sol 移植）、Hangar（新寫）。
5. `spa-app`：Controller、View Model、事件、繁中／英文翻譯、設定、探索清單、圖片解碼、headless 測試。
6. `spa-desktop`：Slint 介面、`--snapshot`、`--smoke`。依截圖修正：預設 Paper、卡片說明一行、欄位對齊、淺色按鈕對比、Hangar snapshot 頻道視為測試版。
7. 工具：rustfmt（120 欄）、Clippy 無警告、`build.ps1`／`build.sh`、啟動腳本、三平台 CI。

## 2026-11-06
8. SQLite `spa.db` 與存檔格式版本（48dcd21）。
9. 表格「上次更新」「上次檢查」、可自訂欄位（257469f）。
10. 匯出下拉選單與懸浮說明（a834ec5）。

## 2026-11-07 ～ 2026-11-11
11. 文件改繁中並移到 `docs/`，不使用 `AGENTS.md`（1fe507b）。
12. 文件結構規範 v1：入口 `docs/PROJECT-DEV.md`、文件標頭（9c569ba）。
13. 定位為 Smart Plugin Assistant 的完全 Rust 重寫版，User-Agent 去掉 Rust（6891dcc）。
14. macOS CI 測試暫存目錄修正、CI `--no-fail-fast`（cd8b770）。
15. 2026-11-11：依規範 v2 建立 `.cowork/`，`docs/VALIDATION.md`、`docs/PROGRESS.md` 與 MEMORY 中的決策搬入（dce44c0）。
16. 2026-11-11：前後端分離補強：匯出預設檔名、快取上限範圍、日誌初始化移到 spa-app。
