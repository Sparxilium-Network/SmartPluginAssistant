# CI 自動發布 Pre-release

- 2026-11-12：使用者要求 CI 自動發布，命名與流程參考 `Sparxilium-Network/MISF-Management-Terminal` 的 `build-prerelease.yml`。
- 取代原本的 `build.yml`（每次任何分支 push 都跑、只上傳執行檔）為 `.github/workflows/build-prerelease.yml`。觸發改為 main push、`v*` tag、對 main 的 PR、手動；`javafx` 分支不再跑 Rust CI。
- 命名比照參考：tag `dev-${run_number}.${run_attempt}-${sha7}`；tag 推送時用 tag 名稱；標題 `SmartPluginAssistant <tag> (Pre-release)`；`--prerelease --latest=false`；先建 draft、上傳、再公開；已公開的不覆蓋。
- 平台：windows-x86_64（zip）、linux-x86_64、macos-aarch64（tar.gz，macos-latest 是 Apple Silicon）。參考專案沒有 macOS，本專案原 CI 有，保留。
- 打包直接呼叫 build.ps1／build.sh，與本機同一流程（含 smoke）；腳本加 `--locked`。Linux smoke 會建立視窗，用 `xvfb-run --auto-servernum`，另裝 `xvfb libxkbcommon-x11-0`。
- 安全：預設 `contents: read`，只有 publish job `contents: write`，只用 `GITHUB_TOKEN`；PR 不發布（fork PR 無寫入權）；`persist-credentials: false`；不用第三方 rust-toolchain／rust-cache action，改 rustup；checkout／upload-artifact／download-artifact 以 commit SHA 固定（與參考專案相同的 SHA）。
- 未採用參考中的 cargo-deny（需另寫 deny.toml），可列為後續。
- 發布說明以繁中寫明 Pre-release、只有 Windows 實際操作過、Linux／macOS 只經 CI、macOS 未簽署。
