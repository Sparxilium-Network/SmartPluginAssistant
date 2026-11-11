# GitHub Actions CI

- 工作流程：2026-11-12 起為 `.github/workflows/build-prerelease.yml`（見 `decisions/ci-prerelease.md`）；以下 2026-11-08～11 的紀錄是舊的 `build.yml`。
- 2026-11-08（run 42402544622，commit 6891dcc）：Windows、Ubuntu 通過；macOS `settings::tests::missing_fields_gain_defaults_and_round_trip` 失敗，`Symbolic link rejected: /var`。原因見 `decisions/symlink-path-policy.md`。
- 修正 cd8b770：測試改用 `temp_dir()`；`rejects_symlinks` 因此真正測到測試建立的連結（原本在 macOS 是被 `/var` 擋下的假通過）。
- 2026-11-11：cd8b770（run 42421944518）與 6c5a138（run 43026191230）三平台全部通過，macOS 修正確認有效。
- 2026-11-12 本機驗證新流程：`--locked` 的 build.ps1 檢查、測試、建置、smoke 通過（替換步驟因使用者正開著 dist 程式而依設計中止）；Windows zip 與 tar.gz 封存步驟在 scratchpad 實測，內容為 `SmartPluginAssistant/`（exe、LICENSE、README、docs），`sha256sum --check` 通過。
- 未驗證：新 workflow 尚未在 GitHub 上執行（未 push）；Linux xvfb smoke、macOS build.sh、publish job 需第一次 push 到 main 後確認。沒有 actionlint 可用，YAML 只人工檢查。
