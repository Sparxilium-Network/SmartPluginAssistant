#!/usr/bin/env bash
# Verifies formatting, lints and tests, then builds a release executable for this platform.
# dist/SmartPluginAssistant/ holds only the newest package. It is assembled and smoke-checked in a
# staging folder first; only then is the previous package removed and the new one moved into place.
# Other folders in dist/ (for example builds kept on purpose for comparison) are left alone.
set -euo pipefail
cd "$(dirname "$0")"
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cargo build --release -p spa-desktop
final=dist/SmartPluginAssistant
staging="dist/.staging-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$staging"
cp target/release/SmartPluginAssistant LICENSE README.md "$staging/"
cp -R docs "$staging/"
smoke=$(mktemp -d)
if ! "$staging/SmartPluginAssistant" "--data-dir=$smoke" --smoke; then
    echo "Packaged smoke check failed; the previous package is unchanged and the failed one is in $staging" >&2
    exit 1
fi
rm -rf -- "$final"
mv "$staging" "$final"
echo "Built $final"
