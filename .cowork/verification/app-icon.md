# 應用程式圖示驗證

- 2026-11-11：`System.Drawing.Icon.ExtractAssociatedIcon` 取出 debug exe 圖示，畫面為草方塊加拼圖（32×32），確認 exe 資源已嵌入。
- 2026-11-11：實際啟動程式（隔離 `--data-dir`），WM_GETICON ICON_SMALL 取得 256×256 圖示，內容正確；ICON_BIG 為 0，工作列使用 exe 內嵌圖示。
- 未驗證：Linux／macOS 視窗圖示實際顯示（macOS 的 Dock 圖示需 .app bundle，目前未打包）。
