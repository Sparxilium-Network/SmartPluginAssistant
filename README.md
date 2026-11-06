# Smart Plugin Assistant（Rust 版）

以 Rust 與 [Slint](https://slint.dev/) 重寫的 Minecraft 伺服器插件管理桌面程式。
功能與安全設計沿用 [Smart Plugin Assistant Sol](../SmartPluginAssistantSol)，內建市場為 **Modrinth** 與 **PaperMC Hangar**。
本程式管理插件，不會啟動或停止 Minecraft 伺服器。

## 建置與執行

需要 Rust stable（以 1.99 驗證）。Linux 另需 `libfontconfig1-dev libxkbcommon-dev libgtk-3-dev`。

```powershell
# 格式、Lint 與全部測試
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace

# 啟動（release 模式）
.\run.cmd

# 完整驗證並建立可攜式執行檔
.\build.ps1
```

首次打包輸出 `dist/SmartPluginAssistant/SmartPluginAssistant.exe`，再次打包會放入 `dist/<時間戳>/`，保留先前產物。
執行檔為單一檔案，不需要另外安裝 Runtime。Linux/macOS 使用 `bash ./run.sh` 與 `bash ./build.sh`，各平台必須在對應作業系統建置。

## 功能

- 建立、設定及解除管理伺服器實例，支援既有外部目錄；解除管理只移除索引。
- 自動從 Modrinth 取得伺服器核心與 Minecraft 正式版本（單選），額外相容載入器以勾選方式設定；清單快取六小時，離線時使用上次結果並明確提示。
- 開啟「探索插件」即顯示預設清單，空白搜尋也能瀏覽；往下捲動自動載入下一頁，重複項目去除，失敗時保留已載入內容並可原頁重試。
- Modrinth／Hangar 來源切換、關鍵字搜尋、官方網址匯入、版本選擇與相容性說明。
- 搜尋清單與詳情顯示插件圖片，背景下載、解碼並快取；無圖片時顯示首字替代圖。
- 安裝前預覽依賴清單；相容性未驗證或需從網站手動安裝的依賴，必須明確確認。
- 本機 JAR 批次匯入、Modrinth SHA-512 辨識、啟用／停用及可回復的移除。
- 已安裝插件表格顯示「上次更新」（檔案內容最後變更時間；非本程式寫入的檔案使用檔案修改時間）與「上次檢查」（最後一次成功檢查更新的時間）。
- 表格欄位可由「欄位」按鈕或在標題列按右鍵勾選顯示，拖曳標題右側邊界調整寬度；版面會儲存，欄位總寬超過視窗時可水平捲動。
- 依遠端發布 ID 與發布時間檢查更新，個別查詢失敗會保留並列出。
- 暫存下載、大小上限、來源雜湊（Modrinth SHA-512/SHA-1、Hangar SHA-256）檢查、JAR 結構驗證與失敗回復。
- 「匯出」下拉選單：插件 ZIP，以及鎖定版本、下載後檢查 SHA-512 的 Linux 部署腳本。
- 滑鼠停在按鈕上約 0.6 秒會顯示懸浮說明，說明該操作會做什麼、會不會連網或改動檔案。
- 繁體中文／英文介面、深色／淺色與青綠／藍／紫主色，設定即時套用。

安裝、更新或重新命名插件前，請先停止 Minecraft 伺服器。

## 資料與備份

預設資料目錄：`~/.smartpluginassistant-rust/`，與 Sol 及舊版程式分開。所有資料存放於單一 SQLite 資料庫 `spa.db`。

```text
.smartpluginassistant-rust/
├── spa.db               # 實例、插件來源紀錄、設定、環境清單快取與存檔版本
├── spa.db.v<N>-*.bak    # 升級存檔格式前自動建立的備份
├── cache/images/        # 可清理的插件圖片快取
├── servers/<UUID>/      # 未指定外部目錄時的預設伺服器資料夾
└── logs/                # 應用程式日誌
```

### 存檔格式版本

`spa.db` 的 `meta` 表記錄 `schema_version`（同步寫入 `PRAGMA user_version`）、`created_by`、`last_opened_by` 等資訊，設定視窗也會顯示目前版本。

- 版本比程式支援的新：拒絕開啟且不修改檔案，請更新程式。
- 目前版本為 2（v2 新增插件的上次更新／上次檢查時間）。
- 版本較舊：先以 `VACUUM INTO` 備份為 `spa.db.v<舊版本>-<時間>.bak`，再於單一交易中依序升級；任何一步失敗都會回滾。
- 開啟時會執行完整性檢查，損毀的資料庫不會被覆寫。
- 第一次建立資料庫時，若資料目錄中有 Sol 格式的 `instances.json`、`inventory/*.json`、`settings.json`、`environments.json`，會一次匯入，原始 JSON 不會被修改或刪除。

```powershell
sqlite3 "$env:USERPROFILE\.smartpluginassistant-rust\spa.db" "PRAGMA user_version"
```

更新前的 JAR 與被移除的 JAR 會存放在伺服器目錄的 `.spa-backups/<UUID>/`，可手動取回。
安裝失敗時會回復原檔；多個插件逐一安裝，已成功的依賴會保留。程序或電源中斷時可從 `.spa-backups/` 取回原檔。
插件 ZIP 只包含 JAR 與 `manifest.json`，不打包世界或伺服器設定。

指定隔離的資料位置：

```powershell
.\run.cmd --data-dir=C:\temp\spa-data
```

## 架構

| Crate | 職責 |
| --- | --- |
| `spa-api` | Provider trait 與共用資料契約、受限 HTTP 傳輸 |
| `spa-core` | 安全路徑、SQLite 資料庫與存檔版本遷移、實例、清單、下載驗證、安裝交易、依賴規劃、更新、匯出、環境與圖片快取 |
| `providers/modrinth` | Modrinth API 轉接器 |
| `providers/hangar` | Hangar API 轉接器 |
| `spa-app` | 與 UI 框架無關的應用層：Controller（presenter）、View Model、事件、翻譯與設定 |
| `spa-desktop` | Slint 介面，只負責呈現 View Model 與轉送操作 |

前後端分離：所有行為都在 `spa-app` 的 `Controller`，以 `UiEvent` 送出 View Model。
若要更換 UI 框架，只需重寫 `spa-desktop`；`spa-app` 的 headless 測試即以同樣方式驅動 Controller。
Provider 在編譯時註冊（`spa-app/src/context.rs`），不支援執行期外掛，詳見 [Provider 開發指引](agent/PROVIDER_GUIDE.md)。

Hangar 以平台（PAPER／WATERFALL／VELOCITY）宣告各版本支援的 Minecraft 版本；
實例的載入器包含該平台時標示為「來源宣告相容」，否則需要確認。只提供外部網址的依賴會列為手動安裝項目。

## 驗證

目前有 **57 個自動化測試**，涵蓋路徑、存檔版本建立／遷移／備份／拒絕較新版本、舊 JSON 匯入、程序鎖、截斷下載、校驗碼、偽裝 JAR、安裝回復、依賴衝突／循環／停用、手動依賴、更新判斷、匯出、環境快取、圖片快取、兩個 API 解析、Feed 去重、表格欄位設定、翻譯一致性，以及以 headless 方式驅動 Controller 的流程測試。
測試使用隔離目錄與本機 HTTP，不依賴外部服務。

`--snapshot=<目錄>` 以軟體渲染器離線繪製真實視窗，使用線上 API 走完建立實例、探索、安裝、檢查更新與設定流程並輸出 PNG。
驗證紀錄見 [agent/VALIDATION.md](agent/VALIDATION.md)，開發規範見 [AGENTS.md](AGENTS.md)。

授權沿用原專案的 GNU AGPL v3，詳見 [LICENSE](LICENSE)。
