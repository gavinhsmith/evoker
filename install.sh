#!/usr/bin/env bash
# Installs evoker: downloads evoker.jar and an `evoker` launcher into a directory.
#
#   curl -fsSL https://raw.githubusercontent.com/gavinhsmith/evoker/main/install.sh | bash
#   curl -fsSL https://raw.githubusercontent.com/gavinhsmith/evoker/main/install.sh | bash -s -- /opt/evoker
#
# Directory: first argument, else $EVOKER_DIR, else ~/.evoker
# Version:   $EVOKER_VERSION (e.g. v0.1.0), else the latest release
set -euo pipefail

dir="${1:-${EVOKER_DIR:-$HOME/.evoker}}"
version="${EVOKER_VERSION:-latest}"
if [ "$version" = latest ]; then
  url="https://github.com/gavinhsmith/evoker/releases/latest/download/evoker.jar"
else
  url="https://github.com/gavinhsmith/evoker/releases/download/$version/evoker.jar"
fi
url="${EVOKER_URL:-$url}" # override for testing (e.g. file:///path/to/evoker.jar)

mkdir -p "$dir"
dir="$(cd "$dir" && pwd)"

echo "Downloading evoker ($version) to $dir"
if command -v curl >/dev/null 2>&1; then
  curl -fsSL "$url" -o "$dir/evoker.jar.part"
elif command -v wget >/dev/null 2>&1; then
  wget -q "$url" -O "$dir/evoker.jar.part"
else
  echo "error: curl or wget is required" >&2
  exit 1
fi
mv "$dir/evoker.jar.part" "$dir/evoker.jar"

cat > "$dir/evoker" <<EOF
#!/bin/sh
# evoker launcher: uses \$JAVA_HOME if set, else java on PATH
exec "\${JAVA_HOME:+\$JAVA_HOME/bin/}java" -jar "$dir/evoker.jar" "\$@"
EOF
chmod +x "$dir/evoker"

java_cmd="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if ! command -v "$java_cmd" >/dev/null 2>&1; then
  echo "warning: Java not found. evoker needs Java 21 or newer (https://adoptium.net)." >&2
else
  major="$("$java_cmd" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)"
  if [ -n "$major" ] && [ "$major" -lt 21 ]; then
    echo "warning: found Java $major; evoker needs Java 21 or newer (https://adoptium.net)." >&2
  fi
fi

echo "Installed: $("$dir/evoker" version 2>/dev/null || echo "evoker ($dir/evoker)")"
case ":$PATH:" in
  *":$dir:"*) ;;
  *) echo "Add it to your PATH, e.g. in ~/.bashrc or ~/.zshrc:"
     echo "  export PATH=\"$dir:\$PATH\"" ;;
esac
