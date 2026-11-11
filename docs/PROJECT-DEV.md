# Smart Plugin Assistant 開發說明

> 最後修改：2026-11-11 ｜ 基於 commit：`cd8b770`

Smart Plugin Assistant 的完全 Rust 重寫版，原本的 Java／JavaFX 實作保留在 `javafx` 分支。一般使用方式見 [README](../README.md)。本文件是開發文件的入口，給開發者與維護者，記錄專案結構、開發規範、建置、資料庫格式與測試方式。

## 開發文件

- [架構](ARCHITECTURE.md)：已確認的決策、分層、資料安全、Modrinth／Hangar 轉接、探索清單、圖片快取與介面實作。
- [Provider 開發指引](PROVIDER-GUIDE.md)：新增插件來源平台的步驟、契約與測試方式。

新增或拆分 `docs/` 下的文件時，記得更新這份清單。每份文件標題下方標註最後修改日期與當時的 `HEAD` 短雜湊，修改內容時一併更新。

AI Agent 的專屬指示與記憶放在各自的目錄（`.claude/` 等），不納入版本控制；本專案不使用 `AGENTS.md`。決策紀錄、驗證結果與開發進度等工作紀錄放在 `.cowork/`（入口 `.cowork/INDEX.md`），主要給 AI Agent 查閱。

## 結構

| Crate | 職責 |
| --- | --- |
| `crates/spa-api` | Provider trait、共用資料契約、受限的 HTTPS 傳輸 |
| `crates/providers/modrinth` | Modrinth API 轉接器 |
| `crates/providers/hangar` | Hangar API 轉接器 |
| `crates/spa-core` | 所有碰觸磁碟與下載的功能：安全路徑、SQLite 資料庫與遷移、實例、插件清單、下載驗證、安裝交易、依賴規劃、更新、匯出、環境清單與圖片快取 |
| `crates/spa-app` | 與 UI 框架無關的應用層：`Controller`（presenter）、View Model、事件、翻譯與設定 |
| `crates/spa-desktop` | Slint 介面，只呈現 View Model 並轉送操作；檔案對話框與開啟資料夾／網址也在這裡 |

## 開發規範

### 分層

- 依賴方向：`spa-api` ← providers；`spa-api` ← `spa-core` ← `spa-app` ← `spa-desktop`。
- Provider 只依賴 `spa-api`。`spa-core` 不得引用 provider 或任何 UI 套件。
- 所有行為都在 `spa-app`，且不得引用 Slint。`spa-desktop` 只把 `UiEvent` 轉成畫面並轉送操作；更換 UI 框架時不應改動其他 crate。
- 應用程式碼不得依 provider ID 分支。Provider 在編譯期註冊於 `spa-app/src/context.rs` 的 `builtin_providers`，不支援執行期外掛。新增方式見 [Provider 開發指引](PROVIDER-GUIDE.md)。

### 安全

- 絕不遞迴刪除伺服器目錄；移除實例只解除管理。
- 路徑一律經 `spa_core::paths` 驗證，受管理的寫入拒絕符號連結。
- 下載先暫存並驗證（大小、來源公布的雜湊、JAR 結構）後才取代原檔。暫存檔使用 `.spa-` 前綴與 `.tmp` 副檔名，掃描時不會被當成插件。
- 以 `log` 記錄重要操作與失敗，不記錄任何憑證。
- 不提交產生的檔案（`target/`、`dist/`）、憑證或使用者資料。

### 資料存取

- 應用程式資料只能透過 `spa_core::db::Database` 讀寫，並在交易內完成；錯誤必須回傳給呼叫端。
- 結構變更一律在 `db::MIGRATIONS` 新增下一個版本號，不得修改已發布的遷移。

### 介面文字

- 所有使用者可見的文字都經 `spa_app::i18n::tr`／`trf` 取得；Slint 端使用 `Tr.get(Tr.revision, key)`。
- 翻譯放在 `crates/spa-app/lang/zh-tw.lang` 與 `en.lang`，兩個檔案必須同時更新。測試會檢查兩邊的 key 與 `{name}` 等佔位符一致，也會檢查 Slint 與程式碼引用的 key 都存在。
- 按鈕的懸浮說明放在 `tip.*`，在 Slint 寫成 `AppButton { tip: root.t("tip.xxx"); }`。
- 語系檔編譯進執行檔，修改後需重新編譯。

### 工作方式

- 一次完成並驗證一個小功能後提交，再進行下一個。commit 標題寫英文，空一行後以繁體中文說明。
- 測試保持可重現：使用本機 HTTP fixture，不依賴外部服務。連線到真實 API 的檢查只在明確執行 `--snapshot` 時進行。

## 啟動與打包

開發需要 Rust stable（以 1.99 驗證）。Linux 另需 `libfontconfig1-dev libxkbcommon-dev libgtk-3-dev`。

以 release 模式啟動：

```powershell
.\run.cmd
```

```sh
bash ./run.sh
```

完整驗證並建立可攜式執行檔：

```powershell
.\build.ps1
```

```sh
bash ./build.sh
```

建置腳本依序執行格式檢查、Clippy、全部測試與 release 建置，再把執行檔、`LICENSE`、`README.md` 與 `docs/` 複製到 `dist/SmartPluginAssistant/`，最後以隔離資料目錄執行打包後的 `--smoke` 檢查（輸出 `smoke-result.json`）。若該目錄已存在，改放到 `dist/<時間戳>/`，保留先前產物。各平台必須在對應作業系統建置。

## 自動建置

[GitHub Actions](../.github/workflows/build.yml) 在每次 push 與 PR 時，於 Windows、Ubuntu 與 macOS 執行格式檢查、Clippy、測試與 release 建置，並上傳執行檔為 artifact。測試使用 `--no-fail-fast`，一次列出所有失敗。工作流程不使用任何 secret。

## 資料儲存

預設資料目錄為使用者目錄下的 `.smartpluginassistant-rust/`，可用 `--data-dir=<目錄>` 改變。目錄內的 `application.lock` 以檔案鎖防止兩個程式同時寫入。

所有資料存放在單一 SQLite 資料庫 `spa.db`（bundled SQLite，WAL、`synchronous=FULL`、`foreign_keys=ON`、STRICT 資料表）：

| 資料表 | 內容 |
| --- | --- |
| `meta` | 存檔格式版本與建立／開啟紀錄 |
| `instances` | 伺服器實例 |
| `plugins` | 插件來源紀錄，以 SHA-512 識別；解除管理實例時連帶刪除（伺服器檔案不動） |
| `settings` | 偏好設定，每個欄位一列（key → JSON 值） |
| `environment_cache` | 伺服器核心與 Minecraft 版本清單快取（單列） |

### 存檔格式版本

`meta.schema_version` 是存檔格式版本，同步寫入 `PRAGMA user_version`；另記錄 `created_by`、`last_opened_by`、`created_at` 與 `migrated_at`。設定視窗會顯示目前版本。

| 版本 | 變更 |
| --- | --- |
| 1 | 初始結構 |
| 2 | `plugins.updated_at`（檔案內容最後變更時間）與 `plugins.checked_at`（最後一次成功檢查更新的時間），單位為 Unix 毫秒，0 表示未知 |

開啟資料庫時：

1. 執行 `quick_check`；損毀的資料庫不會被覆寫。
2. 不是本程式建立、或沒有版本紀錄的資料庫一律拒絕。
3. 版本比程式支援的新：拒絕開啟，不寫入任何內容。
4. 版本較舊：先以 `VACUUM INTO` 備份為 `spa.db.v<舊版本>-<毫秒>.bak`，再於單一交易內依序套用遷移並更新版本；任何一步失敗都會回滾。

確認版本：

```powershell
sqlite3 "$env:USERPROFILE\.smartpluginassistant-rust\spa.db" "PRAGMA user_version"
```

### 從 Sol 匯入

只在第一次建立資料庫時（版本 0 → 目前版本），若資料目錄中有 Sol 格式的 `instances.json`、`inventory/<id>.json`、`settings.json` 與 `environments.json`，會在同一個交易內匯入。任一檔案無效即中止開啟；JSON 檔案不會被修改或刪除。`spa_core::json` 保留給此匯入，以及匯出與圖片快取的原子寫檔。

### 設定

`AppSettings` 沿用 Sol 的欄位，缺少的欄位使用預設值。`libraryColumns` 保存已安裝插件表格的版面 `[{id, visible, width}]`，由 `spa-app::columns::normalize` 驗證：移除未知或重複的 ID、補上缺少的欄位、寬度限制在 48–800 px，且至少保留一欄顯示。

## 日誌

日誌寫入資料目錄的 `logs/app.log`，等級 INFO，超過 5 MiB 時在啟動時改名為 `app.1.log`。HTTP 函式庫（ureq、rustls）的訊息不記錄。不記錄任何憑證。

## 測試

```powershell
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
```

測試全部使用隔離目錄與本機 HTTP，不連線外部服務：

- `spa-core/tests/common`：本機 HTTP 伺服器、JAR 產生器與假 provider，供核心流程測試使用。
- `spa-app/tests/controller.rs`：以 headless 方式驅動 `Controller`，與 UI 使用相同的事件流程。
- 各 provider crate 以記憶體內的 `HttpTransport` fixture 測試 JSON 解析、分頁與網址辨識。
- 回滾測試以 SQLite trigger（`RAISE(ABORT)`）注入中繼資料寫入失敗。
- 暫存目錄一律用各測試模組的 `temp_dir()`（`spa-core` 的 `test_support`、`tests/common`），不要直接呼叫 `tempfile::tempdir()`。macOS 的暫存目錄在 `/var` 底下，而 `/var` 是符號連結，會被路徑檢查拒絕。
- Controller 測試啟用探索前，先等切換實例產生的 `MarketReset`，否則兩個探索清單會競爭，請求次數不固定。

## 畫面擷取

`--snapshot=<目錄>` 以 Slint 軟體渲染器離線繪製真實視窗，使用線上 API 走完建立實例、探索兩個來源、安裝、檢查更新、表格欄位、懸浮說明、匯出選單與設定流程，並輸出 PNG。懸浮說明與匯出選單以真實的指標事件（`WindowEvent::PointerMoved`／`PointerPressed`／`PointerReleased`）觸發。

```powershell
cargo build -p spa-desktop
.\target\debug\SmartPluginAssistant.exe --data-dir=target\ui-check\data --snapshot=target\ui-check\shots
```

每次使用新的資料目錄。此流程會下載公開的插件 JAR，但不會執行它們。
