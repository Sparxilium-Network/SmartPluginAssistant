# 開發進度

## 已完成

### 2026-10-31：初版

1. 建立 workspace 並確認決策：Slint 介面搭配與 UI 框架無關的應用層、Modrinth + Hangar、編譯期 provider crate、新的資料目錄。
2. `spa-api`：provider trait、資料契約、驗證規則與受限的 HTTPS 傳輸。
3. `spa-core`：安全路徑、原子寫檔、程序鎖、實例索引、以雜湊綁定的插件清單、暫存並驗證的下載、可回滾的安裝／匯入／停用／移除／辨識、依賴規劃（含網站手動依賴）、更新報告、ZIP／腳本匯出、環境清單快取與隔離的圖片快取。

   移植時修正：暫存下載原本使用 `.jar` 副檔名，會被重新掃描當成插件；現在改為 `.tmp`，掃描也忽略 `.spa-` 前綴。
4. Provider：Modrinth（由 Sol 移植）與 Hangar（新寫，已對照線上 API）。
5. `spa-app`：Controller、View Model 與事件、繁中／英文翻譯、設定、探索清單、圖片解碼、headless Controller 測試。
6. `spa-desktop`：Slint 介面（側欄、已安裝插件、探索、對話框、設定、主題）、離線 `--snapshot` 擷取與打包用的 `--smoke` 檢查。

   依截圖修正：預設核心改為 Paper、卡片說明只顯示一行、表格欄位對齊、淺色模式按鈕對比、Hangar 的 snapshot 頻道視為測試版。
7. 工具：rustfmt（120 欄）、Clippy 無警告、`build.ps1`／`build.sh`、啟動腳本、三個作業系統的 CI。

### 2026-11-06

8. 儲存由 JSON 改為 SQLite `spa.db`，加入明確的存檔格式版本（`meta.schema_version`／`PRAGMA user_version`）、遷移前備份、拒絕較新格式、完整性檢查與一次性的 Sol JSON 匯入；設定視窗顯示版本。
9. 已安裝插件表格新增「上次更新」與「上次檢查」（存檔格式 v2）；所有欄位可勾選顯示、拖曳調整寬度，版面會儲存，過寬時可水平捲動。
10. 兩個匯出按鈕合併為「匯出」下拉選單，每個項目說明輸出內容；工具列與對話框的按鈕加上延遲顯示的懸浮說明。

### 2026-11-07

11. 文件重寫為繁體中文並移到 `docs/`，移除 `AGENTS.md`（Agent 指示改放各 Agent 專屬目錄）；打包時一併附上 `docs/`。

## 後續候選

- 在 Linux／macOS 執行 CI 並記錄結果。
- 使用者需要時，再加回 Sol 曾有的 SpiGet provider。
- Windows 執行檔的圖示與版本資訊。
