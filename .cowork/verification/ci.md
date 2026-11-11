# GitHub Actions CI

- 工作流程：`.github/workflows/build.yml`，Windows／Ubuntu／macOS 執行 fmt、Clippy、測試（`--no-fail-fast`）、release 建置並上傳 artifact。不使用任何 secret，使用 GitHub 託管 runner。
- 2026-11-08（run 42402544622，commit 6891dcc）：Windows、Ubuntu 通過；macOS `settings::tests::missing_fields_gain_defaults_and_round_trip` 失敗，`Symbolic link rejected: /var`。原因見 `decisions/symlink-path-policy.md`。
- 修正 cd8b770：測試改用 `temp_dir()`；`rejects_symlinks` 因此真正測到測試建立的連結（原本在 macOS 是被 `/var` 擋下的假通過）。
- 待確認：cd8b770 推送後的 macOS 結果。
