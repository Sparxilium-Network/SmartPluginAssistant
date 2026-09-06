# Smart Plugin Assistant - Agent Rules and Guidelines

以下是本專案開發時，AI Agent 必須遵循的開發規範與準則：

## 1. 介面與視窗翻譯 (I18n Translation)
* **規範**：所有 UI 視窗、對話框、提示訊息及文字標籤，皆必須透過專案內建的 `I18n.get(...)` 進行翻譯處理，嚴禁在 Java 程式碼或 FXML 中寫死（Hardcode）中英文或其他語言的文字。
* **語系檔案**：翻譯資源應同步更新至 `src/main/resources/com/sparxilium/smartpluginassistant/resources/lang/` 下的語系檔（如 `zh-tw.lang`、`en.lang`）。
* **使用範例**：
  ```java
  titleLabel.setText(I18n.get("app_settings.title"));
  ```

## 2. 日誌紀錄 (Log4j Logging)
* **規範**：專案中所有關鍵動作、異常處理、網路請求與狀態變更，皆應盡量透過 Log4j 寫入日誌。
* **使用範例**：
  ```java
  private static final Logger logger = LogManager.getLogger(MyController.class);
  
  // 在動作發生時記錄
  logger.info("Starting instance: {}", instanceName);
  logger.error("Failed to load instance configuration", exception);
  ```

## 3. 版本控制與提交 (Git Commit)
* **規範**：每一次完成程式碼或檔案的修改/新增後，必須進行 Git Commit。
* **提交訊息**：Commit Message 必須清晰描述本次修改的具體內容與相關資料，例如修復的 Bug、新增的功能、修改的類別或對應的設計考量。

## 4. 檔案修改工具使用限制 (File Editing Restrictions)
* **規範**：修改檔案時，應優先使用專用的檔案編輯工具（例如 `replace_file_content` 或 `write_to_file`），除非必要，否則不要調用命令列（Shell/Terminal 命令，例如 PowerShell 的 `Set-Content` 等）來編輯或修改檔案內容。

## 5. 精準搜尋與避免盲目讀檔 (Targeted Search & Research Guidelines)
* **規範**：在調查或搜尋專案內容時，**嚴禁**無目的、亂槍打鳥式地直接讀取大量無關檔案。
* **準則與作法**：
  1. **優先查詢架構文檔**：遇到架構、實作位置或模組關係問題時，優先查閱 `DEV-WIKI.md` 或 `README.md`，思考可能的類別或檔案所在位置。
  2. **善用精確篩選工具**：優先使用類似 `find`（`find_by_name`）鎖定特定目錄與副檔名，或利用 `grep_search` 進行精確字串搜尋。
  3. **精準閱讀**：只讀取明確與問題核心相關的檔案與行數範圍，確保 Context 專注且高效。
