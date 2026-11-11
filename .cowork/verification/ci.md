# GitHub Actions CI

- 工作流程：`.github/workflows/build.yml`，Windows／Ubuntu／macOS 執行 fmt、Clippy、測試（`--no-fail-fast`）、release 建置並上傳 artifact。不使用任何 secret，使用 GitHub 託管 runner。
- 2026-11-08（run 42402544622，commit 6891dcc）：Windows、Ubuntu 通過；macOS `settings::tests::missing_fields_gain_defaults_and_round_trip` 失敗，`Symbolic link rejected: /var`。原因見 `decisions/symlink-path-policy.md`。
- 修正 cd8b770：測試改用 `temp_dir()`；`rejects_symlinks` 因此真正測到測試建立的連結（原本在 macOS 是被 `/var` 擋下的假通過）。
- 2026-11-11：cd8b770（run 42421944518）與 6c5a138（run 43026191230）三平台全部通過，macOS 修正確認有效。
- CI artifact 只有執行檔，不含 LICENSE／README／docs；完整套件由 build.ps1／build.sh 產生。
