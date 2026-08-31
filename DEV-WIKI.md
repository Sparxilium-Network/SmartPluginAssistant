# 📚 Smart Plugin Assistant - 開發者維基 (DEV-WIKI)

本檔案彙整 Smart Plugin Assistant 專案的核心架構、技術選型、目錄結構、關鍵演算法與打包指南，供開發與維護參考。

---

## 🛠️ 1. 技術棧與關鍵依賴 (Tech Stack)

* **JDK 版本**：Java 21 (LTS)
* **GUI 框架**：JavaFX 21.0.6 (`javafx-controls`, `javafx-fxml`)
* **JSON 序列化**：Jackson 2.18.3 (`jackson-databind`, `jackson-datatype-jsr310`)
* **日誌框架**：Log4j 2.26.1 (`log4j-api`, `log4j-core`, `log4j-slf4j2-impl`) + SLF4J 2.0.17
* **本地系統整合 (JNA)**：JNA 5.16.0 (`com.sun.jna.platform.win32` 支援 Windows DWM 深色標題列)
* **圖像處理**：TwelveMonkeys ImageIO 3.12.0 (支援 WebP, JPEG, PNG 快取與非同步載入)

---

## 🏗️ 2. 核心架構與設計模式

### 2.1 實例管理、目錄結構與設定 (`InstanceManager` & `InstanceSettingsDialogController`)
* **資料儲存路徑**：
  * Windows / Linux / macOS：`~/.smartpluginassistant/`
* **實例配置儲存**：
  * 每個實例獨立存放在 `~/.smartpluginassistant/instances/<instance_id>/`
  * 實例配置檔案為 `instance.json`，包含核心架構 (`loader`)、MC 版本 (`mcVersion`)、向下相容核心清單 (`extraCompatibleLoaders`) 以及 **允許測試版本檢查 (`allowPrereleases`)**。
  * 實例自訂圖示為 `icon.png`（由 `ImageCacheService` 支援免重啟即時驅逐快取）。
  * 插件儲存於 `plugins/` 目錄（或使用者自訂的外部插件目錄）。
* **測試版 (Alpha/Beta) 檢查與警告標記**：
  * 若在實例設定中勾選「允許測試版本 (Alpha / Beta) 檢查與更新」，檢查更新時會納入 Alpha 與 Beta 發布版本。
  * 主畫面版本狀態若檢測到新版為測試版，會顯示專屬警示徽章：`⚠️ 測試版: <版本號> (beta/alpha)`，讓使用者明確知曉版本穩定度。

### 2.2 插件元數據與下載歷史記錄 (`PluginMetadataStore`)
* 存放於各實例 `plugins/.plugin_metadata.json`。
* 下載插件時即時記錄：
  * `projectId`：Modrinth 專案識別碼（如 `luckperms`）
  * `versionId`：Modrinth 發布版本 ID（如 `5zQ9hQ4x`）
  * `versionNumber`：Modrinth 原始完整版本字串（如 `v5.5.71-bukkit`）
  * `fileName`：下載儲存檔名（如 `LuckPerms-Bukkit-5.5.71.jar`）
  * `sha1`：檔案雜湊值
* **更新比對機制**：
  1. 優先透過 `versionId` 進行 1:1 比對。
  2. 其次透過 `versionNumber`（含前後綴標籤）進行精確字串比對。
  3. 最後透過正規化語義版本演算法 (`normalizeVersionNumber`)，徹底消除平台前後綴（如 `-bukkit`, `-spigot`, `v`）帶來的版本誤報。

### 2.3 Prism-style 購物車式 Modrinth 瀏覽器 (`ModrinthBrowserController`)
* **多選佇列 (Shopping Cart)**：使用者可連續挑選多個插件版本，卡片即時顯示選取徽章。
* **測試版篩選 (Alpha/Beta Pre-releases)**：
  * 預設僅列出正式穩定版本 (`release`)，避免誤裝不穩定測試版。
  * 頂部搜尋欄提供「允許測試版 (Alpha/Beta)」勾選項，勾選後立即顯示包含 Alpha 與 Beta 的所有發布版本。
* **批次依賴解析與確認**：
  * 點擊右下角「檢查並確認 (X)」後，自動非同步解析所有選取項目的 Required 與 Optional 依賴。
  * 彈出原生深色模態對話框 (`Stage`)，供使用者自由勾選或剔除依賴項目。
  * 確認後進行平行下載與安裝，並寫入下載元數據。

### 2.4 網址解析新增插件、特定版本指定與不相容警示機制 (`AddByUrlDialogController`)
* **支援多種網址格式**：
  * 專案首頁網址（如 `https://modrinth.com/plugin/huskhomes` 或 `huskhomes`）。
  * **特定發布版本直接網址**（如 `https://modrinth.com/plugin/huskhomes/version/4.11-7a2d09a`）。
* **相容性判斷與安全確認機制（不強制阻擋，改以警告 + 勾選授權）**：
  * **完全相容正式版**：直接解鎖下載。
  * **僅有相容測試版 (Beta / Alpha)**：顯示測試版提示，需主動勾選確認後解鎖下載。
  * **環境不相容或指定非相容版本**：
    * 以前遇到不相容會直接跳出錯誤並終止下載，現在會自動抓取目標版本資訊。
    * 顯示醒目警告：標明該版本支援的核心與 MC 版本，與當前實例環境不同。
    * 提供「確認忽視環境不相容，強制下載並安裝此版本」核取方塊，勾選後即可順利下載。

### 2.5 主畫面插件表格、平台標記、多選操作、過濾與即時搜尋 (`MainController`)
* 採用 `FilteredList` + `SortedList` 雙向綁定 `TableView`。
* **託管平台識別 (Platform Column)**：
  * 主表格新增「託管平台」欄位。
  * **Modrinth 插件**：顯示亮綠色徽章 `Modrinth`（代表已連結專案，支援檢查更新與一鍵升級）。
  * **Hangar 插件**：顯示深藍色徽章 `Hangar`（代表自 PaperMC 官方 Hangar 倉庫下載並連結，支援版本檢查與更新）。
  * **本地插件**：顯示深灰色徽章 `本地`（代表本機匯入或未關聯遠端平台的自製/本機插件）。
* **多選與快捷鍵支援 (Ctrl / Shift / Space)**：
  * 支援 `Ctrl + 左鍵` 點擊不連續多選、`Shift + 左鍵` 連續範圍多選。
  * 表格反白選取狀態會與左側勾選框即時雙向連動。
  * 按下 `Space`（空白鍵）可一鍵切換所有反白選取列的勾選狀態。
* **篩選下拉選單**：支援「全部插件」、「已啟用」、「已停用」、「有新版本」並附帶即時數量徽章。
* **即時關鍵字搜尋**：輸入檔名或版本號即時聯動篩選。

### 2.6 批量目錄匯入與 Modrinth 自動配對連結 (`ImportPluginsDialogController`)
* **功能入口**：主畫面插件工具列「📂 匯入插件」按鈕。
* **掃描與讀取**：選擇目標目錄後，自動掃描所有 `.jar` 與 `.jar.disabled` 檔案。
* **自動讀取與配對流程**：
  1. 讀取 jar 內部描述檔（`plugin.yml` / `paper-plugin.yml` 等）以取得內部名稱與版本。
  2. 計算每個 jar 檔案的 SHA-1 Hash，並調用 Modrinth `/version_files` 批次端點反查。
  3. 若 SHA-1 命中，直接取得 Modrinth `projectId`、`versionId`、`versionNumber` 與發布類型。
  4. 未命中者標註為「未連結 (本機插件)」，仍可勾選匯入。
* **批量匯入與元數據持久化**：
  * 勾選項目後點擊「開始匯入」，將檔案複製到實例 `plugins/` 目錄。
  * 自動寫入 `.plugin_metadata.json`，使匯入的插件未來支援一鍵檢查更新與版本對比。


---

## 🌐 3. 國際化多語系規範 (I18n)

* 語系檔案路徑：`src/main/resources/com/sparxilium/smartpluginassistant/lang/`
  * 繁體中文：`zh-tw.lang`
  * 英文：`en.lang`
* **使用準則**：
  * 嚴禁在 Java 程式碼或 FXML 中寫死文字。
  * 一律透過 `I18n.get("key", params...)` 取得在地化字串。

---

## 💻 4. 跨平台相容性 (Cross-Platform)

1. **Windows**：
   * 透過 `WindowsTitleBarTheme.java` 調用 Windows 10/11 DWM API (`DWMWA_USE_IMMERSIVE_DARK_MODE`)，視窗標題列全面原生黑化。
2. **Linux**：
   * 檔案總管與目錄開啟相容 `xdg-open`。
   * CSS 備援中文字型：`"Noto Sans CJK TC"`, `"WenQuanYi Zen Hei"`, `"Source Han Sans TC"`。
3. **macOS**：
   * 相容 `open` 指令與標準家目錄路徑。

---

## 📦 5. 打包與發布指南

### 5.1 本地編譯與測試
```bash
# Windows
.\mvnw.cmd clean test
.\mvnw.cmd clean package

# Linux / macOS
./mvnw clean test
./mvnw clean package
```

### 5.2 Windows 原生綠色版 EXE 打包 (`build-exe.bat`)
* 專案內建 `build-exe.bat`，依賴 JDK 21 內建的 `jpackage` 工具：
```cmd
build-exe.bat
```
* 打包產物位於 `dist/SmartPluginAssistant/SmartPluginAssistant.exe`，免裝 Java 環境即可直接執行。

### 5.3 Linux 獨立應用打包 (`build-linux.sh`)
```bash
chmod +x build-linux.sh
./build-linux.sh
```
* 打包產物位於 `dist-linux/SmartPluginAssistant/bin/SmartPluginAssistant`。

---

## 📜 6. 開發者維護守則 (Agent Rules)

1. **翻譯與文字**：新增任何 UI 元素或提示訊息，必須同時更新 `zh-tw.lang` 與 `en.lang`。
2. **日誌記錄**：所有關鍵動作與異常皆使用 `LogManager.getLogger(...)` 記錄。
3. **版本控制**：每次完成特定功能或修復後，必須執行 `git commit` 並撰寫詳盡 Commit Message。
