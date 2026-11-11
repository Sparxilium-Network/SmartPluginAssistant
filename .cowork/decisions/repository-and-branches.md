# 儲存庫與分支

- 2026-11-08：目標儲存庫 `https://github.com/Sparxilium-Network/SmartPluginAssistant.git`。
- 名稱維持 SmartPluginAssistant（不加 -Rust）；文件說明本專案是 Smart Plugin Assistant 的完全 Rust 重寫版。
- 遠端原 `main`（JavaFX 版）以 GitHub 分支改名移到 `javafx`，Rust 版推送為新的 `main`。Git 操作由使用者自行執行。
- 兩個分支沒有共同歷史，無法互相開 PR，屬預期。
- HTTP User-Agent 為 `SmartPluginAssistant/0.1 (Sparxilium)`；`Cargo.toml` 的 `repository` 指向目標儲存庫。
- Agent 不主動 push（見 `Common-dev-instruct.md` 第 6 章）。
