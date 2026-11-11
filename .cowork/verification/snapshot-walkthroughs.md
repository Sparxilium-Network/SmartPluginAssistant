# 離線畫面擷取（--snapshot）與打包檢查

## 2026-11-11：設定視窗改版
- `--snapshot` 全流程通過，新增截圖 07-settings（外觀與語言）、07-settings-images／storage／providers／about、07-settings-light-english、07-settings-collapsed；06c-columns 改為設定的表格欄位頁。
- 已看過：深色繁中各頁、淺色英文（即時儲存後主題、主色、語言立即套用且視窗保持開啟）、收合「偏好設定」群組。
- 未驗證：原生視窗中實際點選 NavItem 與「開啟官網」／「開啟原始碼頁面」按鈕（離線擷取直接設定 `settings-page`）。

## 2026-11-11：打包位置
- 問題：`dist/SmartPluginAssistant/` 自 2026-10-31 存在，之後的打包都放到 `dist/<時間戳>/`，使用者看到的一直是舊版，誤以為沒打包。
- 修正後 `./build.ps1` 通過：smoke `{"providers":["modrinth","hangar"],"success":true}`，舊產物改名為 `dist/SmartPluginAssistant-20261031-164210`，新產物在 `dist/SmartPluginAssistant/`。`build.sh` 只做 `bash -n` 語法檢查，未在 Linux／macOS 實際執行。
- `dist/20261106-231135`、`dist/20261111-211758` 是舊規則留下的產物，未刪除，由使用者決定。

## 2026-10-31：初版
- `./build.ps1` 通過：51 個測試、release 建置、打包後 `--smoke`（`{"providers":["modrinth","hangar"],"success":true}`）。輸出單一 23 MB `SmartPluginAssistant.exe`。
- `--snapshot` 以 debug 與打包 release 各跑一次（隔離資料目錄、公開 API）：
  - Modrinth 環境清單：10 個插件載入器、103 個正式版 Minecraft 版本。
  - 建立實例：預設 Paper，已儲存並選取。
  - Modrinth 預設瀏覽 20 個專案，圖片載入，第一個專案 4 個版本。
  - Hangar 預設瀏覽 20 個，續頁 20 → 40。
  - Hangar 安裝 ViaBackwards：規劃含必要依賴 ViaVersion，兩者通過 SHA-256 與 JAR 檢查後安裝。
  - 檢查更新：2 個已連結，0 更新，0 失敗。
  - 設定：淺色、紫色、英文即時套用。
- 已看過截圖：繁中深色、英文淺色、各對話框、兩來源探索頁、安裝規劃、已安裝清單。原生視窗（winit + FemtoVG）開啟 6 秒無錯誤。
- Hangar API 行為於同日以線上 API 核對。

## 2026-11-06：SQLite
- 54 個測試通過；`--snapshot` 再次通過（同上流程）。
- 資料目錄只有 `spa.db`（含 WAL）：`schema_version` 1、`user_version` 1、實例、兩筆插件、設定、環境快取。設定視窗顯示版本。

## 2026-11-06：表格欄位
- 57 個測試通過。`--snapshot`：安裝與檢查後兩個時間欄位有值；看過欄位選擇對話框、隱藏一欄、拖曳加寬、800 px 欄出現水平捲軸。`spa.db` 為 v2，`updated_at`／`checked_at` 非 0，`libraryColumns` 已保存。
- 限制：離線擷取直接呼叫拖曳回呼，未在原生視窗用真實滑鼠拖曳。

## 2026-11-06：匯出選單與懸浮說明
- 57 個測試通過，翻譯一致性測試涵蓋 `tip.*`。
- `--snapshot` 改送真實指標事件：停在「重新整理」1.2 秒後說明出現在按鈕下方；點「匯出」開選單，點外部關閉。看過繁中深色與英文淺色截圖。
