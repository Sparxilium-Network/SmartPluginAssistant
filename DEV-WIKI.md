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

### 2.1 實例管理與目錄結構 (`InstanceManager`)
* **資料儲存路徑**：
  * Windows / Linux / macOS：`~/.smartpluginassistant/`
* **實例配置儲存**：
  * 每個實例獨立存放在 `~/.smartpluginassistant/instances/<instance_id>/`
  * 實例配置檔案為 `instance.json`
  * 實例自訂圖示為 `icon.png`（由 `ImageCacheService` 支援免重啟即時驅逐快取）
  * 插件儲存於 `plugins/` 目錄（或使用者自訂的外部插件目錄）

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

### 2.4 網址解析新增插件與測試版安全機制 (`AddByUrlDialogController`)
* 支援輸入 Modrinth 插件網址或 Slug。
* 自動比對當前實例相容的遊戲版本與架構核心。
* **測試版提示與確認機制**：
  * 若專案存在相容的正式版 (`release`)，優先選取並允許直接下載。
  * 若專案**無相容的正式版，但有相容的 Beta / Alpha 測試版**：
    * 預設不會直接啟用下載按鈕。
    * 顯示醒目的棕橘色警告提示框：說明目前僅有測試版本。
    * 需由使用者手動勾選「確認下載並安裝此測試版本」核取方塊後，方可點擊下載。

### 2.5 主畫面插件過濾與即時搜尋 (`MainController`)
* 採用 `FilteredList` + `SortedList` 雙向綁定 `TableView`。
* **篩選下拉選單**：支援「全部插件」、「已啟用」、「已停用」、「有新版本」並附帶即時數量徽章。
* **即時關鍵字搜尋**：輸入檔名或版本號即時聯動篩選。

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
