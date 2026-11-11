# Verifies formatting, lints and tests, then builds a portable release executable.
# dist/SmartPluginAssistant/ holds only the newest package. It is assembled and smoke-checked in a
# staging folder first; only then is the previous package removed and the new one moved into place.
# Other folders in dist/ (for example builds kept on purpose for comparison) are left alone.
# Native tools write progress to stderr; failures are detected through exit codes.
$ErrorActionPreference = "Continue"
Set-Location $PSScriptRoot
cargo fmt --all -- --check; if ($LASTEXITCODE) { throw "Formatting check failed; run cargo fmt --all" }
cargo clippy --locked --workspace --all-targets -- -D warnings; if ($LASTEXITCODE) { throw "Clippy failed" }
cargo test --locked --workspace; if ($LASTEXITCODE) { throw "Tests failed" }
cargo build --locked --release -p spa-desktop; if ($LASTEXITCODE) { throw "Release build failed" }
$final = Join-Path "dist" "SmartPluginAssistant"
# Leftovers of earlier failed runs are this script's own temporary folders.
Get-ChildItem "dist" -Directory -Force -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like ".staging-*" -or $_.Name -like ".old-*" } |
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
$staging = Join-Path "dist" (".staging-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Force $staging | Out-Null
Copy-Item "target/release/SmartPluginAssistant.exe" $staging
Copy-Item "LICENSE", "README.md" $staging
Copy-Item "docs" $staging -Recurse
$smoke = Join-Path $env:TEMP ("spa-smoke-" + [guid]::NewGuid())
& (Join-Path $staging "SmartPluginAssistant.exe") "--data-dir=$smoke" --smoke | Out-Host
if ($LASTEXITCODE) { throw "Packaged smoke check failed; the previous package is unchanged and the failed one is in $staging" }
# A copy running from dist keeps its executable locked, which would leave the old package behind.
$finalFull = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot $final))
$running = Get-Process SmartPluginAssistant -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -and $_.Path.StartsWith($finalFull, [StringComparison]::OrdinalIgnoreCase) }
if ($running) { throw "Close SmartPluginAssistant running from $final and run build.ps1 again; the new package is in $staging" }
# Swap by renaming, so a failure never leaves a half-deleted package with the new one nested inside.
if (Test-Path $final) {
    $old = Join-Path "dist" (".old-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
    try { Move-Item $final $old -ErrorAction Stop }
    catch { throw "Cannot replace $final (close SmartPluginAssistant if it is running from there); the new package is in $staging" }
}
Move-Item $staging $final -ErrorAction Stop
if ($old) { try { Remove-Item $old -Recurse -Force -ErrorAction Stop } catch { Write-Warning "Could not remove the previous package $old; delete it manually" } }
if (-not (Test-Path (Join-Path $final "SmartPluginAssistant.exe"))) { throw "Packaging failed: $final has no executable" }
Write-Host "Built $final"
