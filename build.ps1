# Verifies formatting, lints and tests, then builds a portable release executable.
# First output: dist/SmartPluginAssistant/; later builds go to dist/<timestamp>/ to keep earlier outputs.
# Native tools write progress to stderr; failures are detected through exit codes.
$ErrorActionPreference = "Continue"
Set-Location $PSScriptRoot
cargo fmt --all -- --check; if ($LASTEXITCODE) { throw "Formatting check failed; run cargo fmt --all" }
cargo clippy --workspace --all-targets -- -D warnings; if ($LASTEXITCODE) { throw "Clippy failed" }
cargo test --workspace; if ($LASTEXITCODE) { throw "Tests failed" }
cargo build --release -p spa-desktop; if ($LASTEXITCODE) { throw "Release build failed" }
$target = Join-Path "dist" "SmartPluginAssistant"
if (Test-Path $target) { $target = Join-Path "dist" (Get-Date -Format "yyyyMMdd-HHmmss") }
New-Item -ItemType Directory -Force $target | Out-Null
Copy-Item "target/release/SmartPluginAssistant.exe" $target
Copy-Item "LICENSE", "README.md" $target
$smoke = Join-Path $env:TEMP ("spa-smoke-" + [guid]::NewGuid())
& (Join-Path $target "SmartPluginAssistant.exe") "--data-dir=$smoke" --smoke | Out-Host
if ($LASTEXITCODE) { throw "Packaged smoke check failed" }
Write-Host "Built $target"
