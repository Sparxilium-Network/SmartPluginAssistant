# Smart Plugin Assistant

> 最後修改：2026-11-12 ｜ 基於 commit：`4456339`

Minecraft 伺服器插件管理桌面程式，內建市場為 **Modrinth** 與 **PaperMC Hangar**。

這是 Smart Plugin Assistant 以 Rust 與 [Slint](https://slint.dev/) 完全重寫的版本，不含任何 Java 程式碼，也不需要安裝 Java。原本的 Java／JavaFX 版本保留在 [`javafx` 分支](https://github.com/Sparxilium-Network/SmartPluginAssistant/tree/javafx)，不再是主要開發版本。

本程式只管理插件檔案，不會啟動、停止或連線到 Minecraft 伺服器。安裝、更新、停用或移除插件前，請先停止伺服器。

從原始碼建置與參與開發，請見 [開發說明](docs/PROJECT-DEV.md)。

## 取得與執行

發行版是單一執行檔，不需要另外安裝 Runtime。目前只提供開發中的 Pre-release，可從 [Releases](https://github.com/Sparxilium-Network/SmartPluginAssistant/releases) 下載對應平台的壓縮檔（Windows x86_64、Linux x86_64、macOS Apple Silicon），每個檔案附 `.sha256` 校驗值。


- Windows：執行 `SmartPluginAssistant.exe`。
- Linux／macOS：執行 `SmartPluginAssistant`。Linux 需要圖形桌面與 fontconfig、xkbcommon、GTK 3 等系統函式庫。macOS 執行檔未簽署，第一次執行需在「系統設定 › 隱私權與安全性」允許。

從原始碼執行需要 Rust stable：

```powershell
.\run.cmd
```

```sh
bash ./run.sh
```

## 功能

### 伺服器實例

- 建立、設定及解除管理伺服器實例，可指向既有的伺服器目錄。解除管理只移除索引，伺服器檔案全部保留。
- 伺服器核心與 Minecraft 正式版本清單自動從 Modrinth 取得，額外相容的載入器以勾選方式設定。清單快取六小時；離線時使用上次結果並明確提示。

### 探索與安裝

- 開啟「探索插件」即顯示熱門清單，空白搜尋也能瀏覽；往下捲動自動載入下一頁。載入失敗時保留已顯示的內容，可原地重試。
- 切換 Modrinth／Hangar 來源、關鍵字搜尋，或貼上官方專案網址直接開啟。
- 清單與詳情顯示插件圖片；沒有圖片時以首字代替。
- 選擇版本時顯示相容性說明。安裝前預覽完整的依賴清單；相容性未經來源確認、或只能從網站手動下載的依賴，必須明確勾選確認。

### 已安裝插件

- 「本機插件」下拉選單：重新整理（重新掃描 plugins 資料夾）、匯入電腦上的 JAR、以 SHA-512 向 Modrinth 辨識來源。
- 啟用／停用，以及可回復的移除。
- 檢查更新並更新選取或全部插件；個別查詢失敗會保留並列出，不影響其他插件。
- 表格欄位：
  - 「上次更新」是檔案內容最後變更的時間；不是由本程式寫入的檔案，使用檔案修改時間。
  - 「上次檢查」是最後一次成功檢查更新的時間。
  - 在「設定 › 偏好設定 › 已安裝插件表格」勾選要顯示的欄位；拖曳標題右側邊界可調整寬度。版面會儲存，總寬超過視窗時可水平捲動。
- 「匯出」下拉選單：
  - **插件 ZIP**：只包含插件 JAR 與 `manifest.json`，不含世界與伺服器設定。
  - **部署腳本**：Linux shell 腳本，鎖定目前版本，下載後檢查 SHA-512；只能從網站下載的插件列為需手動上傳。

### 介面

- 滑鼠停在按鈕上約 0.6 秒會顯示說明，告訴你這個操作會做什麼、會不會連網或改動檔案；停用中的按鈕也會顯示。
- 繁體中文／英文介面、深色／淺色主題與青綠／藍／紫主色。
- 「設定」按鈕在視窗左上角。設定視窗左側是分類，右側是內容：偏好設定（外觀與語言、已安裝插件表格、插件圖片）、資料與儲存、平台插件模組與關於本程式。變更會立即套用並自動儲存。

## 安全設計

- 下載先寫入暫存檔，檢查大小上限、來源公布的雜湊（Modrinth SHA-512／SHA-1、Hangar SHA-256）與 JAR 結構後才放入 `plugins/`。
- 更新或移除時，舊檔移到伺服器目錄的 `.spa-backups/<UUID>/`，不會永久刪除；安裝失敗會回復原檔。
- 一次安裝多個插件時逐一進行，已成功的依賴會保留；程序或電源中斷時，可從 `.spa-backups/` 手動取回原檔。
- 網路連線一律使用 HTTPS，轉址不可降級為 HTTP；貼上的專案網址只接受官方網域。
- 同一個資料目錄同時只能由一個程式開啟。

## 資料位置

預設資料目錄為使用者目錄下的 `.smartpluginassistant-rust/`。JavaFX 版使用 `.smartpluginassistant/`，兩者分開存放、互不影響：

```text
.smartpluginassistant-rust/
├── spa.db               # 實例、插件來源紀錄、設定、環境清單快取與存檔版本
├── spa.db.v<N>-*.bak    # 升級存檔格式前自動建立的備份
├── cache/images/        # 插件圖片快取，可在設定中清理
├── servers/<UUID>/      # 未指定外部目錄時的預設伺服器資料夾
└── logs/                # 應用程式日誌
```

- 設定視窗會顯示存檔格式版本（目前為 2），並可開啟資料目錄。
- 程式較新版本建立的資料庫不會被開啟或修改，請更新程式。
- 升級存檔格式前會先自動備份。
- 第一次啟動時，若資料目錄中有 Smart Plugin Assistant Sol 格式的 JSON 資料，會一次匯入；原始檔案不會被修改或刪除。

使用另一個資料目錄（例如測試用）：

```powershell
.\SmartPluginAssistant.exe --data-dir=C:\temp\spa-data
```

## 開發

開發者文件的入口是 [docs/PROJECT-DEV.md](docs/PROJECT-DEV.md)。

## 授權

沿用 JavaFX 版的 GNU AGPL v3，詳見 [LICENSE](LICENSE)。
