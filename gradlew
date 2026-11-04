#!/bin/sh
# Lightweight Gradle bootstrapper for Netspy (keeps the wrapper reproducible without a checked-in JAR).
set -eu
GRADLE_VERSION="8.13"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
INSTALL_DIR="$GRADLE_USER_HOME/netspy-wrapper/gradle-$GRADLE_VERSION"
GRADLE_BIN="$INSTALL_DIR/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$GRADLE_USER_HOME/netspy-wrapper"
  ZIP="$GRADLE_USER_HOME/netspy-wrapper/gradle-$GRADLE_VERSION-bin.zip"
  if [ ! -f "$ZIP" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl -fL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP"
    elif command -v wget >/dev/null 2>&1; then
      wget "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -O "$ZIP"
    else
      echo "curl or wget is required to download Gradle." >&2; exit 1
    fi
  fi
  command -v unzip >/dev/null 2>&1 || { echo "unzip is required to install Gradle." >&2; exit 1; }
  unzip -q -o "$ZIP" -d "$GRADLE_USER_HOME/netspy-wrapper"
fi
exec "$GRADLE_BIN" "$@"
