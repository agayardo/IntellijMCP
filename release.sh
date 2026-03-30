#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./gradlew buildPlugin :stdio-mcp-server:installDist

version="1.0.0.0"
tag="v$version"

plugin_zip="build/distributions/DevelopmentMcp-1.0-SNAPSHOT.zip"
mcp_dist="stdio-mcp-server/build/install/stdio-mcp-server"
mcp_zip="stdio-mcp-server/build/stdio-mcp-server-${version}.zip"

if [[ ! -f "$plugin_zip" ]]; then
  echo "ERROR: $plugin_zip not found" >&2
  exit 1
fi
if [[ ! -d "$mcp_dist" ]]; then
  echo "ERROR: $mcp_dist not found" >&2
  exit 1
fi

# Package the MCP bridge distribution into a zip
(cd "$(dirname "$mcp_dist")" && zip -r - "$(basename "$mcp_dist")") > "$mcp_zip"

gh release create "$tag" \
  --title "Release $version" \
  --generate-notes \
  "$plugin_zip#DevelopmentMcp-plugin.zip" \
  "$mcp_zip#stdio-mcp-server.zip"

echo "Created release $tag with plugin zip and MCP bridge artifacts."
