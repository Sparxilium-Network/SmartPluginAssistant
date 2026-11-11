# 路徑中的符號連結

- `spa_core::paths::reject_links` 拒絕路徑中任何一層符號連結，包括系統層級的上層目錄。
- 2026-11-08：macOS CI 因 `/var` → `/private/var` 失敗後，使用者決定**維持現狀**，不接受上層路徑的系統符號連結（例如 macOS 的 `/tmp`、`/var`）。
- 影響：macOS／Linux 上伺服器目錄或 `--data-dir` 放在這類路徑下會被拒絕；預設資料目錄在使用者目錄下，不受影響。
- 只修正測試：測試暫存目錄一律用 `temp_dir()`（先 canonicalize 暫存目錄），不放寬檢查。
- 若日後要放寬（例如只對使用者指定的根目錄先 canonicalize），屬於放寬驗證，必須先問使用者。
