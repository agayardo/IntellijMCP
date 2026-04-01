#!/usr/bin/env bash
set -euo pipefail

version="1.0.0.0"
tag="v$version"
repo="agayardo/IntellijMCP"

plugin_url="https://github.com/$repo/releases/download/$tag/DevelopmentMcp-plugin.zip"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

echo "Downloading IntelliJ Dev MCP $tag..."
curl -fSL -o "$tmp/plugin.zip" "$plugin_url"

# Install the IntelliJ plugin
jetbrains_dir="$HOME/Library/Application Support/JetBrains"
idea_dir=$(ls -1d "$jetbrains_dir"/IntelliJIdea* 2>/dev/null | sort -V | tail -1)
if [[ -z "$idea_dir" ]]; then
  install_dir="$HOME/.intellij-dev-mcp"
  mkdir -p "$install_dir"
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

echo ""
echo "Done. Restart IntelliJ to activate the plugin."
echo "The stdio bridge will be installed automatically on first startup."
echo ""
echo "Add this to your MCP client config:"
echo ""
echo '  {'
echo '    "mcpServers": {'
echo '      "intellij-dev-mcp": {'
echo '        "command": "bash",'
echo '        "args": ["-c", "~/.intellij-dev-mcp/bin/stdio-mcp-server"]'
echo '      }'
echo '    }'
echo '  }'
