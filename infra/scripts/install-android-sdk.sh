#!/usr/bin/env bash
# Instala el Android SDK (command-line tools) para compilar el APK sin Android Studio.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_VERSION="11076708"   # command-line tools (latest estable)
PLATFORM="android-35"
BUILD_TOOLS="35.0.0"

echo "[android-sdk] ANDROID_HOME=$ANDROID_HOME"
mkdir -p "$ANDROID_HOME/cmdline-tools"

if [ ! -d "$ANDROID_HOME/cmdline-tools/latest" ]; then
  echo "[android-sdk] Descargando command-line tools..."
  tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/tools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_VERSION}_latest.zip"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi

export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "[android-sdk] Aceptando licencias..."
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true

echo "[android-sdk] Instalando plataformas y build-tools..."
sdkmanager --sdk_root="$ANDROID_HOME" \
  "platform-tools" \
  "platforms;${PLATFORM}" \
  "build-tools;${BUILD_TOOLS}" >/dev/null

echo "[android-sdk] Listo. SDK en $ANDROID_HOME"
echo "  platforms: ${PLATFORM}, build-tools: ${BUILD_TOOLS}"
