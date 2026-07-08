#!/usr/bin/env bash
# Populates ./data/plugins with the LspSkript plugin (built locally) and Skript 2.15.4.
# Run this before `docker compose up`.
set -euo pipefail
cd "$(dirname "$0")"

mkdir -p data/plugins

# LspSkript plugin (produced by `gradle :skript-lsp:jar`).
cp ../skript-lsp/build/libs/LspSkript-0.1.0.jar data/plugins/LspSkript.jar

# Skript 2.15.4: prefer the local Gradle cache, else download from GitHub.
SKRIPT_JAR="data/plugins/Skript-2.15.4.jar"
if [ ! -f "$SKRIPT_JAR" ]; then
  SRC=$(find "$HOME/.gradle" -name "Skript-2.15.4.jar" 2>/dev/null | head -1 || true)
  if [ -n "$SRC" ]; then
    cp "$SRC" "$SKRIPT_JAR"
  else
    curl -sSL -o "$SKRIPT_JAR" \
      https://github.com/SkriptLang/Skript/releases/download/2.15.4/Skript-2.15.4.jar
  fi
fi

echo "Plugins ready in data/plugins:"
ls -la data/plugins
