#!/usr/bin/env bash
set -euo pipefail

version="1.0.0.0"
tag="v$version"
repo="agayardo/IntellijMCP"
install_dir="$HOME/.intellij-dev-mcp"

plugin_url="https://github.com/$repo/releases/download/$tag/DevelopmentMcp-plugin.zip"
bridge_url="https://github.com/$repo/releases/download/$tag/stdio-mcp-server.zip"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

echo "Downloading IntelliJ Dev MCP $tag..."
curl -fSL -o "$tmp/plugin.zip" "$plugin_url"
curl -fSL -o "$tmp/bridge.zip" "$bridge_url"

# Install the stdio bridge
rm -rf "$install_dir/bridge"
mkdir -p "$install_dir"
unzip -qo "$tmp/bridge.zip" -d "$install_dir"
mv "$install_dir/stdio-mcp-server" "$install_dir/bridge"
chmod +x "$install_dir/bridge/bin/stdio-mcp-server"

# Install the IntelliJ plugin
jetbrains_dir="$HOME/Library/Application Support/JetBrains"
idea_dir=$(ls -1d "$jetbrains_dir"/IntelliJIdea* 2>/dev/null | sort -V | tail -1)
if [[ -z "$idea_dir" ]]; then
  echo "WARNING: No IntelliJ IDEA config directory found in $jetbrains_dir" >&2
  echo "         Install the plugin manually: Settings → Plugins → ⚙️ → Install Plugin from Disk..." >&2
  echo "         Plugin zip saved to: $install_dir/DevelopmentMcp-plugin.zip" >&2
  cp "$tmp/plugin.zip" "$install_dir/DevelopmentMcp-plugin.zip"
else
  plugins_dir="$idea_dir/plugins"
  mkdir -p "$plugins_dir"
  unzip -qo "$tmp/plugin.zip" -d "$plugins_dir"
  echo "Plugin installed to $plugins_dir"
fi

bridge_path="$install_dir/bridge/bin/stdio-mcp-server"
echo ""
echo "Done. Bridge installed to $bridge_path"
echo ""
echo "Add this to your MCP client config:"
echo ""
echo '  {'
echo '    "mcpServers": {'
echo '      "intellij-dev-mcp": {'
echo "        \"command\": \"$bridge_path\""
echo '      }'
echo '    }'
echo '  }'
echo ""
echo "Restart IntelliJ to activate the plugin."
