#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./gradlew buildPlugin

version="1.0.0.0"
tag="v$version"

plugin_zip="build/distributions/DevelopmentMcp-1.0-SNAPSHOT.zip"

if [[ ! -f "$plugin_zip" ]]; then
  echo "ERROR: $plugin_zip not found" >&2
  exit 1
fi

# Find the most recent IntelliJ IDEA config directory
jetbrains_dir="$HOME/Library/Application Support/JetBrains"
idea_dir=$(ls -1d "$jetbrains_dir"/IntelliJIdea* 2>/dev/null | sort -V | tail -1)
if [[ -z "$idea_dir" ]]; then
  echo "ERROR: No IntelliJIdea directory found in $jetbrains_dir" >&2
  exit 1
fi

plugins_dir="$idea_dir/plugins"
mkdir -p "$plugins_dir"
unzip -o "$plugin_zip" -d "$plugins_dir"

echo "Plugin installed to: $plugins_dir"
echo ""
echo "Restart IntelliJ to activate the plugin."
echo "The stdio bridge will be installed automatically on first startup."

if ! command -v gh &>/dev/null; then
  echo "ERROR: gh CLI is not installed" >&2
  exit 1
fi

gh release delete "$tag" --cleanup-tag --yes 2>/dev/null || true

if ! gh release create "$tag" \
  --title "Release $version" \
  --generate-notes \
  "$plugin_zip#DevelopmentMcp-plugin.zip"; then
  echo "ERROR: Failed to create GitHub release $tag" >&2
  exit 1
fi

echo "Created release $tag with plugin zip."
