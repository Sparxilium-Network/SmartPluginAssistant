# Provider 開發指引

> 最後修改：2026-11-07 ｜ 基於 commit：`1fe507b`

Provider 是一般的 crate，只依賴 `spa-api`（以及自己的解析套件），在編譯時連結進程式；不支援執行期載入外掛。

## 新增 Provider

1. 建立 `crates/providers/<名稱>`，依賴 `spa-api`、`serde_json`、`url` 與 `log`。
2. 實作 `spa_api::PluginProvider`（契約見下方）。提供接受 base URL 與 `Arc<dyn HttpTransport>` 的建構函式，讓測試能注入 fixture；`Default` 使用公開端點與 `UreqTransport`。
3. 把 crate 加入 workspace 與 `spa-app/Cargo.toml`，再到 `spa-app/src/context.rs` 的 `builtin_providers` 註冊。其他程式碼都不需要修改，主程式不會依 provider ID 分支。重複的 ID 會在啟動時被拒絕。

## 契約

| 方法 | 要求 |
| --- | --- |
| `info` | 穩定 ID（符合 `[a-z][a-z0-9-]{1,40}`）、顯示名稱與首頁 |
| `environments` | 選用。回傳插件載入器與正式版 Minecraft 版本清單，最新版本在前；不支援時回傳 `Ok(None)`。主程式會合併各平台的清單並快取六小時 |
| `search` | 頁碼從 0 開始。失敗時回傳 `Err`，不得回傳空的成功結果；不支援的目標環境可回傳已結束的空白頁 |
| `project` | 標準穩定 ID、純文字說明、官方頁面、`Access`，以及選用的 HTTPS 圖示 |
| `releases` | 最新在前；不得退回不相容的版本。每個發布須包含不可變的發布 ID、發布時間、安全的 JAR 檔名、固定版本的下載網址（只能從網站下載時為 `None`）、所有公布的雜湊（`sha512`／`sha256`／`sha1`）、宣告的遊戲版本／載入器與依賴 |
| `release` | 只回傳指定的版本（依 ID；API 支援時也可依官方網址中的名稱） |
| `resolve` | 以 `spa_api::http::has_host` 比對完全相同的官方 HTTPS 主機與路徑格式，不使用子字串比對 |
| `identify_sha512` | 選用。以檔案雜湊精確辨識，回傳雜湊相符的那個檔案 |

### 相容性

只有發布資料同時宣告目標 Minecraft 版本與實例的任一有效載入器時才回傳 `Verified`；`Declared`／`Unknown` 需要使用者確認。這只描述發布資料，不代表實際執行測試過。不要自行推斷穩定頻道。

### 依賴

依賴分為必要、選用與不相容。只能從網站取得的依賴使用 `Dependency::named(None, None, name, external_url, required, false)`；依賴規劃會把它列為手動安裝並要求確認。必要依賴絕不可被略過。

### 執行緒與檔案

Provider 在 UI 執行緒以外呼叫，可能被多個執行緒共用。Provider 不得寫入任何檔案。

## 測試

- JSON 解析、分頁、網址辨識與指定版本檢查：使用記憶體內的 `HttpTransport` fixture，參考現有 provider crate 的測試。
- 主程式層級的流程：使用 `spa-core/tests/common`（本機 HTTP 伺服器、JAR 產生器、假 provider）與 `spa-app/tests/controller.rs`（headless Controller）。
