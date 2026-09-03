# Smart Plugin Assistant 🚀

> ⚠️ **【重要聲明】**
> **本專案絕大多數代碼皆由 AI 輔助生成。**
> 在將本工具應用於重要或生產環境的伺服器前，**請務必謹慎評估、備份您的伺服器檔案與插件設定**，作者與開發者不對任何檔案遺失或伺服器異常承擔責任。

---

## 📖 專案簡介 (Overview)

**Smart Plugin Assistant** 是一款受到 **Prism Launcher** 啟發而設計的 Minecraft 伺服器插件管理器（以 JavaFX 與 Java 21+ / JDK 25 開發）。

本工具**並非直接架設伺服器程序**，而是專注於**虛擬伺服器實例與插件生態的管理**：
* 支援多種伺服器核心分類管理（Paper, Spigot, Purpur, Folia, Velocity, BungeeCord 等）。
* 深度串接 **[Modrinth API](https://docs.modrinth.com/api/)**、**[PaperMC Hangar API](https://hangar.papermc.io/)**、**[Voxel.shop API](https://voxel.shop)** 與 **[SpiGet API](https://spiget.org)**。
* 實現**線上搜尋安裝**、**貼上網址直接匯入**、**本機批量掃描與線上配對**，以及基於檔案 Hash 雜湊值的**一鍵批次自動更新**功能。
* 支援**匯出伺服器部署腳本 (deploy.sh)**，一鍵自動產生可用於 Linux 伺服器部署的 `curl` / `wget` 插件下載腳本。

---

## ✨ 核心特色 (Features)

1. **實例化管理 (Instance Management)**
   * 建立多個虛擬伺服器實例（例如：`生存伺服器 1.21.1 Paper`、`小遊戲伺服器 1.20.4 Purpur`）。
   * 支援使用預設資料夾，或直接指向您本機現有的伺服器目錄。
   * 快速從介面開啟實例根目錄或 `plugins/` 資料夾。

2. **多平台線上搜尋與安裝 (Modrinth, Hangar, Voxel.shop & SpigotMC Browser)**
   * 內建 Prism-style 整合型下載視窗，左側可直覺切換 Modrinth、Hangar、Voxel.shop 與 SpigotMC (SpiGet) 四大市場模組。
   * 市場頂部提供一鍵直達官方首頁的來源連結標籤（`API BY MODRINTH` / `API BY HANGAR` / `API BY VOXEL.SHOP` / `API BY SPIGET`）。
   * 支援 Voxel.shop 與 SpigotMC 免費資源一鍵下載，付費/外部外掛標註價格並支援直達官方購買頁面。
   * 自動依據當前實例的 Loader 核心與 Minecraft 版本進行相容性篩選。
   * 支援購物車多選暫存，一鍵非同步解析必備前置依賴 (Dependencies) 並自由勾選平行下載。
   * 點擊即可一鍵下載 `.jar` 檔案至該實例的 `plugins/` 目錄中。

3. **透過網址直接新增 (Add by URL)**
   * 支援直接貼上 Modrinth 網址（如 `https://modrinth.com/plugin/viaversion`）或 Hangar 網址。
   * 自動解析插件資訊與符合當前伺服器環境的最新發布版本並下載。

4. **批次目錄匯入與線上配對 (Bulk Import & Link)**
   * 選擇現有的 `plugins/` 目錄進行批量匯入。
   * 自動計算所有本機 `.jar` 檔案的 SHA-1，並嘗試與 Modrinth API 線上比對。
   * 成功命中後，本機舊插件將瞬間轉化為「已連結」，未來可享有一鍵更新！

5. **批次 Hash 比對與一鍵自動更新 (Auto-Updater)**
   * 本地自動計算 `plugins/` 內所有 `.jar` 的 Hash 雜湊值。
   * 透過 Modrinth / Hangar API 批次比對最新版本（支援正式版 / Beta / Alpha 切換）。
   * 提供「一鍵全部更新」或個別插件更新，自動安全置換檔案。

6. **細緻載入器相容性自訂 (Granular Compatibility Selection)**
   * 進入實例設定，可依據伺服器核心靈活勾選欲額外相容的上游核心（例如 Paper 實例可勾選 Spigot 相容）。
   * 勾選時即時呈現 API / 非同步相容性風險警告。

7. **匯出與共享部署 (Export & Deploy Scripts)**
   * **ZIP 匯出**：一鍵將伺服器實例或純插件目錄打包為 ZIP 檔案。
   * **腳本匯出 (Shell Script)**：自動解析實例內所有已安裝插件的原始下載來源（Modrinth/Hangar），並生成相容於 Linux 的 `.sh` 腳本，可用於在新環境下自動化 `curl/wget` 部署插件。

8. **多國語言與現代深色介面 (I18n & Modern Dark UI)**
   * 採用 Prism Launcher 風格的深色調介面與標籤體系（支援 Windows 11 DWM 原生深色標題列）。
   * 內建完善的多語系系統 (I18n)，提供 `zh-tw.lang`（繁體中文）、`en.lang`（英文）動態切換。

---

## 🛠️ 技術棧 (Tech Stack)

* **運行環境**：Java JDK 21+ / JDK 25
* **UI 框架**：JavaFX 21 + FXML + CSS
* **資料處理**：Jackson Databind (JSON 序列化與反序列化)
* **網路通訊**：Java 11+ 原生 `HttpClient` (非同步 CompletableFuture 與執行緒池)
* **日誌紀錄**：SLF4J + Log4j 2
* **系統整合**：JNA (Windows 深色標題列整合)
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
│   ├── MainController.java          # 主畫面與實例管理控制器
│   ├── PluginDownloaderController.java # 整合型插件下載視窗控制器 (Prism-style 側欄切換)
│   ├── ModrinthBrowserController.java # Modrinth 搜尋市場模組
│   ├── HangarBrowserController.java # Hangar 搜尋市場模組
│   ├── VoxelBrowserController.java  # Voxel.shop 搜尋市場模組
│   ├── SpigetBrowserController.java # SpigotMC (SpiGet) 搜尋市場模組
│   ├── AddByUrlDialogController.java # 網址解析新增對話框控制器
│   ├── ImportPluginsDialogController.java # 批量掃描匯入控制器
│   ├── InstanceSettingsDialogController.java # 實例獨立相容性設定控制器
│   ├── CreateInstanceDialogController.java # 建立實例對話框控制器
│   └── module/                      # 可擴充市場模組介面 (PluginBrowserModule, Context)
├── model/                           # 資料模型
│   ├── ServerInstance.java          # 伺服器實例模型
│   ├── InstalledPlugin.java         # 本地已安裝插件模型
│   ├── UpdateResult.java            # 統一更新結果模型
│   ├── hangar/                      # PaperMC Hangar API 專用資料結構 (HangarProject, HangarVersion)
│   ├── modrinth/                    # Modrinth API 專用資料結構 (ModrinthProject, ModrinthVersion 等)
│   ├── voxel/                       # Voxel.shop API 專用資料結構 (VoxelProduct)
│   └── spiget/                      # SpiGet API 專用資料結構 (SpigetResource)
├── service/                         # 業務邏輯服務
│   ├── InstanceManager.java         # 實例配置儲存、Shell 腳本生成與目錄管理
│   ├── ModrinthService.java         # Modrinth HTTP API 客戶端
│   ├── HangarService.java           # Hangar HTTP API 客戶端
│   ├── VoxelService.java            # Voxel.shop HTTP API 客戶端
│   ├── SpigetService.java           # SpiGet (SpigotMC) HTTP API 客戶端
│   ├── PluginManagerService.java    # 插件檔案掃描、Hash 比對與生命週期管理
│   ├── PluginMetadataStore.java     # 插件線上來源快取與持久化儲存
│   ├── ImageCacheService.java       # 非同步網路圖片快取服務
│   └── I18n.java                    # 多國語系動態切換服務
```

---

## 📄 開源授權與致謝 (License & Credits)

* API 支援來自 [Modrinth API](https://docs.modrinth.com/api/)、[PaperMC Hangar](https://hangar.papermc.io/)、[Voxel.shop](https://voxel.shop) 與 [SpiGet](https://spiget.org/)。
* UI 與交互設計靈感來自 [Prism Launcher](https://prismlauncher.org/)。
* 圖標表情符號字型使用 [Google Noto Color Emoji](https://github.com/googlefonts/noto-emoji)（遵循 [SIL Open Font License 1.1](https://scripts.sil.org/OFL)）。
