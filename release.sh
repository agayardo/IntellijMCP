#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./gradlew buildPlugin :stdio-mcp-server:installDist

plugin_zip="build/distributions/DevelopmentMcp-1.0-SNAPSHOT.zip"
mcp_binary="stdio-mcp-server/build/install/stdio-mcp-server/bin/stdio-mcp-server"

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
