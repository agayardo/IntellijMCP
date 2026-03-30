#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./gradlew buildPlugin :stdio-mcp-server:installDist

version="1.0.0.0"
tag="v$version"

plugin_zip="build/distributions/DevelopmentMcp-1.0-SNAPSHOT.zip"
mcp_binary="stdio-mcp-server/build/install/stdio-mcp-server/bin/stdio-mcp-server"
mcp_dist="stdio-mcp-server/build/install/stdio-mcp-server"
mcp_zip="stdio-mcp-server/build/stdio-mcp-server-${version}.zip"

for f in "$plugin_zip" "$mcp_binary"; do
  if [[ ! -f "$f" ]]; then
    echo "ERROR: $f not found" >&2
    exit 1
  fi
done

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
echo "MCP server binary:   $mcp_binary"
echo ""
echo "Restart IntelliJ to activate the plugin."

if ! command -v gh &>/dev/null; then
  echo "ERROR: gh CLI is not installed" >&2
  exit 1
fi

# Package the MCP bridge distribution into a zip
(cd "$(dirname "$mcp_dist")" && zip -r - "$(basename "$mcp_dist")") > "$mcp_zip"

gh release delete "$tag" --cleanup-tag --yes 2>/dev/null || true

if ! gh release create "$tag" \
  --title "Release $version" \
  --generate-notes \
  "$plugin_zip#DevelopmentMcp-plugin.zip" \
  "$mcp_zip#stdio-mcp-server.zip"; then
  echo "ERROR: Failed to create GitHub release $tag" >&2
  exit 1
fi

echo "Created release $tag with plugin zip and MCP bridge artifacts."
