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
# Leftovers of earlier failed runs are this script's own temporary folders.
rm -rf -- dist/.staging-* dist/.old-*
staging="dist/.staging-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$staging"
cp target/release/SmartPluginAssistant LICENSE README.md "$staging/"
cp -R docs "$staging/"
smoke=$(mktemp -d)
if ! "$staging/SmartPluginAssistant" "--data-dir=$smoke" --smoke; then
    echo "Packaged smoke check failed; the previous package is unchanged and the failed one is in $staging" >&2
    exit 1
fi
# Swap by renaming so a failure never leaves a half-deleted package with the new one nested inside.
if [ -e "$final" ]; then
    old="dist/.old-$(date +%Y%m%d-%H%M%S)"
    mv -- "$final" "$old"
fi
mv -- "$staging" "$final"
if [ -n "${old:-}" ]; then rm -rf -- "$old"; fi
echo "Built $final"
