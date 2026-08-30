# Smart Plugin Assistant 🚀

> ⚠️ **【重要聲明】**
> **本專案絕大多數代碼皆由 AI 輔助生成。**
> 在將本工具應用於重要或生產環境的伺服器前，**請務必謹慎評估、備份您的伺服器檔案與插件設定**，作者與開發者不對任何檔案遺失或伺服器異常承擔責任。

---

## 📖 專案簡介 (Overview)

**Smart Plugin Assistant** 是一款受到 **Prism Launcher** 啟發而設計的 Minecraft 伺服器插件管理器（以 JavaFX 與 Java 21+ / JDK 25 開發）。

本工具**並非直接架設伺服器程序**，而是專注於**虛擬伺服器實例與插件生態的管理**：
* 支援多種伺服器核心分類管理（Paper, Spigot, Purpur, Folia, Velocity, BungeeCord 等）。
* 串接官方 **[Modrinth API](https://docs.modrinth.com/api/)**。
* 實現**線上搜尋安裝**、**貼上網址直接匯入**，以及基於檔案 SHA-1 雜湊值的**一鍵批次自動更新**功能。

---

## ✨ 核心特色 (Features)

1. **實例化管理 (Instance Management)**
   * 建立多個虛擬伺服器實例（例如：`生存伺服器 1.21.1 Paper`、`小遊戲伺服器 1.20.4 Purpur`）。
   * 支援使用預設資料夾，或直接指向您本機現有的伺服器目錄。
   * 快速從介面開啟實例根目錄或 `plugins/` 資料夾。

2. **Modrinth API 線上搜尋與安裝 (Modrinth Browser)**
   * 內建 Modrinth 插件市場瀏覽器。
   * 自動依據當前實例的 Loader 核心與 Minecraft 版本進行相容性篩選。
   * 點擊即可一鍵下載 `.jar` 檔案至該實例的 `plugins/` 目錄中。

3. **透過網址或 Slug 直接新增 (Add by URL)**
   * 支援直接貼上 Modrinth 網址（例如 `https://modrinth.com/plugin/viaversion`）或專案 ID。
   * 自動解析插件資訊與符合當前伺服器環境的最新發布版本並下載。

4. **批次 Hash 比對與一鍵自動更新 (Auto-Updater)**
   * 本地自動計算 `plugins/` 內所有 `.jar` 的 SHA-1 雜湊值。
   * 透過 Modrinth `/v2/version_files/update` API 批次比對最新版本。
   * 提供「一鍵全部更新」或個別插件更新，自動安全置換檔案。

5. **細緻載入器相容性自訂 (Granular Compatibility Selection)**
   * 進入實例設定，可依據伺服器核心靈活勾選欲額外相容的上游核心：
     * **Folia 實例**：可獨立勾選是否相容 `Paper`、`Spigot`、`Bukkit`、`Purpur`。
     * **Paper 實例**：可勾選相容 `Spigot`、`Bukkit`。
     * **Velocity 等無下游相容之核心**：相容選項將自動隱藏並提示無額外相容核心。
   * 勾選時即時呈現 API / 非同步相容性風險警告。

6. **多國語言支援 (.lang 檔案翻譯系統)**
   * 主介面右上角提供 `🌐` 語言即時下拉切換。
   * 內建標準化語系檔：`zh-tw.lang`（繁體中文）、`en.lang`（英文）。
   * 任何人皆可透過簡單鍵值對文字檔（`key=value`）擴充其他語言。

7. **現代化深色介面 (Modern Dark UI)**
   * 採用 Prism Launcher 風格的深色調介面與標籤體系，操作直覺流暢。

---

## 🛠️ 技術棧 (Tech Stack)

* **運行環境**：Java JDK 21+ / JDK 25
* **UI 框架**：JavaFX 21 + FXML + CSS
* **資料處理**：Jackson Databind (JSON 序列化與反序列化)
* **網路通訊**：Java 11+ 原生 `HttpClient` (支援非同步 CompletableFuture)
* **圖標庫**：Ikonli FontAwesome5

---

## 🚀 快速開始 (Getting Started)

### 系統需求
* 已安裝 **Java JDK 21** 或 **JDK 25**。
* 建議在專案目錄下使用內建的 Maven Wrapper (`mvnw` / `mvnw.cmd`)。

### 1. 編譯專案
```powershell
.\mvnw.cmd clean compile
```

### 2. 啟動應用程式
```powershell
.\mvnw.cmd javafx:run
```

---

## 📂 專案架構概覽 (Project Structure)

```text
src/main/java/com/sparxilium/smartpluginassistant/
├── HelloApplication.java            # 應用程式主入口 (JavaFX)
├── Launcher.java                    # 啟動器轉發
├── controller/                      # 介面控制器
│   ├── MainController.java          # 主畫面與插件列表控制器
│   ├── ModrinthBrowserController.java # Modrinth 搜尋市場控制器
│   ├── AddByUrlDialogController.java # 網址新增對話框控制器
│   └── CreateInstanceDialogController.java # 建立實例對話框控制器
├── model/                           # 資料模型
│   ├── ServerInstance.java          # 伺服器實例模型
│   ├── InstalledPlugin.java         # 本地已安裝插件模型
│   └── Modrinth*.java               # Modrinth API 回傳資料結構
└── service/                         # 業務邏輯服務
    ├── InstanceManager.java         # 實例配置儲存與資料夾管理
    ├── ModrinthService.java         # Modrinth HTTP API 客戶端
    └── PluginManagerService.java    # 插件掃描、Hash 比對與更新管理
```

---

## 📄 開源授權與致謝 (License & Credits)

* API 支援來自 [Modrinth API](https://docs.modrinth.com/api/)。
* UI 與交互設計靈感來自 [Prism Launcher](https://prismlauncher.org/)。
