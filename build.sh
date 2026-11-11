#!/usr/bin/env bash
# Verifies formatting, lints and tests, then builds a release executable for this platform.
# The newest package is always dist/SmartPluginAssistant/. It is assembled and smoke-checked in a
# staging folder first; only then is the previous package renamed to dist/SmartPluginAssistant-<time>/
# (never deleted) and the new one moved into place.
set -euo pipefail
cd "$(dirname "$0")"
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cargo build --release -p spa-desktop
stamp=$(date +%Y%m%d-%H%M%S)
final=dist/SmartPluginAssistant
staging="dist/.staging-$stamp"
mkdir -p "$staging"
cp target/release/SmartPluginAssistant LICENSE README.md "$staging/"
cp -R docs "$staging/"
smoke=$(mktemp -d)
if ! "$staging/SmartPluginAssistant" "--data-dir=$smoke" --smoke; then
    echo "Packaged smoke check failed; the failed package is left in $staging" >&2
    exit 1
fi
if [ -e "$final" ]; then
    previous="dist/SmartPluginAssistant-$(date -r "$final" +%Y%m%d-%H%M%S)"
    if [ -e "$previous" ]; then previous="$previous-$stamp"; fi
    mv "$final" "$previous"
    echo "Previous package kept in $previous"
fi
mv "$staging" "$final"
echo "Built $final"
