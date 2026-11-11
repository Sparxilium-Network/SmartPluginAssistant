# 前後端分離檢查

2026-11-11 檢查結果（commit cd8b770）：

## 結構上已分離
- `spa-desktop` 的相依只有 `spa-app`（加 slint、rfd、open、simplelog、dirs 等 UI／平台套件）；不直接引用 `spa-core`、`spa-api` 或 provider。
- `spa-app`、`spa-core` 沒有任何 slint 引用。
- 介面只透過 `Controller` 方法送出操作，透過 `UiEvent`（純資料 View Model，含 `Rgba` 圖片）接收狀態。
- `spa-app/tests/controller.rs` 以 headless 方式跑完主要流程，證明行為不依賴 Slint。

## `spa-desktop/src/main.rs` 中仍有的少量非呈現邏輯
1. `file_stem`：從 `library_title` 產生匯出 ZIP 的預設檔名（清理字元、空白時用 `server`）。屬應用規則，換 UI 要重寫。
2. 設定儲存時 `cache_mb.clamp(16, 512)`：與 `AppSettings::validated` 的 16..=512 重複，兩邊可能不同步；且 UI 先夾住會讓 Controller 的拒絕訊息永遠不出現。
3. `init_logging`：日誌位置、5 MiB 輪替、過濾 ureq／rustls 都在 desktop。換 UI 要重寫。
4. `--smoke` 寫 `smoke-result.json`：打包檢查用，影響小。

## 可接受、屬於呈現層
- 主題色計算、表格總寬、拖曳中的寬度預覽（`clamp_width` 由 spa-app 提供）、檔案對話框（rfd）、開啟資料夾／網址（open）、命令列參數解析。

## 建議做法（尚未實作，待使用者決定）
- `Controller::export_file_name(script)` 取代 `file_stem`。
- UI 不夾值，或由 spa-app 匯出 `CACHE_MB_RANGE` 供 UI 設定 spinbox 範圍。
- 日誌初始化移到 `spa-app::logging`。
