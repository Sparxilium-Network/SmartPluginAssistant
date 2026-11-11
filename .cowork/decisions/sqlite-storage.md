# SQLite 儲存與存檔格式版本

- 2026-11-06：使用者要求由 JSON 改為單一 SQLite `spa.db`，並有明確的存檔格式版本。
- 版本存在 `meta.schema_version`，同步 `PRAGMA user_version`。
- 較新版本：拒絕開啟、不寫入（避免舊程式破壞新資料）。較舊版本：先 `VACUUM INTO` 備份再於單一交易內遷移。
- 首次建立時一次匯入 Sol JSON，原檔不動；之後不再讀 JSON。
- 遷移只能新增版本號，不修改已發布的遷移。
- 2026-11-06：存檔格式 v2 新增 `plugins.updated_at`、`checked_at`（Unix 毫秒，0 = 未知）。v1 → v2 遷移把兩者設為 0。
