# Verifies formatting, lints and tests, then builds a portable release executable.
# The newest package is always dist/SmartPluginAssistant/. It is assembled and smoke-checked in a
# staging folder first; only then is the previous package renamed to dist/SmartPluginAssistant-<time>/
# (never deleted) and the new one moved into place.
# Native tools write progress to stderr; failures are detected through exit codes.
$ErrorActionPreference = "Continue"
Set-Location $PSScriptRoot
cargo fmt --all -- --check; if ($LASTEXITCODE) { throw "Formatting check failed; run cargo fmt --all" }
cargo clippy --workspace --all-targets -- -D warnings; if ($LASTEXITCODE) { throw "Clippy failed" }
cargo test --workspace; if ($LASTEXITCODE) { throw "Tests failed" }
cargo build --release -p spa-desktop; if ($LASTEXITCODE) { throw "Release build failed" }
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$final = Join-Path "dist" "SmartPluginAssistant"
$staging = Join-Path "dist" ".staging-$stamp"
New-Item -ItemType Directory -Force $staging | Out-Null
Copy-Item "target/release/SmartPluginAssistant.exe" $staging
Copy-Item "LICENSE", "README.md" $staging
Copy-Item "docs" $staging -Recurse
$smoke = Join-Path $env:TEMP ("spa-smoke-" + [guid]::NewGuid())
& (Join-Path $staging "SmartPluginAssistant.exe") "--data-dir=$smoke" --smoke | Out-Host
if ($LASTEXITCODE) { throw "Packaged smoke check failed; the failed package is left in $staging" }
if (Test-Path $final) {
    $previous = Join-Path "dist" ("SmartPluginAssistant-" + (Get-Item $final).LastWriteTime.ToString("yyyyMMdd-HHmmss"))
    if (Test-Path $previous) { $previous = "$previous-$stamp" }
    Move-Item $final $previous
    Write-Host "Previous package kept in $previous"
}
Move-Item $staging $final
Write-Host "Built $final"
