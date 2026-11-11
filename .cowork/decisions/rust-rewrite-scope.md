# Rust 重寫範圍

- 2026-10-31：由 `C:\code\SmartPluginAssistant`（JavaFX 原版，參考 Hangar 用法）與 `C:\code\SmartPluginAssistantSol`（Sol，最新功能與安全規範）重寫為 Rust。兩個原專案只讀，不修改。
- UI 使用 Slint；前後端分離以便日後更換 UI 框架：所有行為在 `spa-app::Controller`，`spa-desktop` 只呈現 `UiEvent` 並轉送操作。
- 內建平台只有 Modrinth 與 Hangar；不含 Sol 的 Spigot、原版的 Voxel。SpiGet 等使用者要求時再加。
- Provider 為編譯期 trait crate，不支援執行期載入外掛。
- 資料目錄 `~/.smartpluginassistant-rust/`，與 JavaFX 版 `~/.smartpluginassistant/` 分開（格式不同，共用會衝突）。
- 新實例預設核心為 Paper：字母排序第一個是 bukkit，Hangar 無法提供。
- 移植時修正：Sol 的暫存下載使用 `.jar` 副檔名，會被重新掃描成插件；改為 `.spa-*.tmp`，掃描也忽略 `.spa-` 前綴。
