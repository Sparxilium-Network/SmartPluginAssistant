#!/usr/bin/env bash
# Verifies formatting, lints and tests, then builds a release executable for this platform.
set -euo pipefail
cd "$(dirname "$0")"
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cargo build --release -p spa-desktop
target=dist/SmartPluginAssistant
if [ -e "$target" ]; then target="dist/$(date +%Y%m%d-%H%M%S)"; fi
mkdir -p "$target"
cp target/release/SmartPluginAssistant LICENSE README.md "$target/"
cp -R docs "$target/"
smoke=$(mktemp -d)
"$target/SmartPluginAssistant" "--data-dir=$smoke" --smoke
echo "Built $target"
