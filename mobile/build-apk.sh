#!/usr/bin/env bash
# build-apk.sh — compila el APK de DMujeres Tracking con tus textos personalizados.
#
# Uso (ejecutar desde la carpeta mobile/):
#   ./build-apk.sh debug                  # APK debug: rápido, para probar en tu celular
#   ./build-apk.sh release                # APK release: para publicar en GitHub
#   ./build-apk.sh debug 1.0.63           # además cambia el nombre de versión
#   ./build-apk.sh release 1.0.63         # release con nombre de versión nuevo
#
# Requisitos: JDK 17+ y Android SDK (variable ANDROID_HOME o /opt/android-sdk).
# El APK queda en: app/build/outputs/apk/<debug|release>/app-<debug|release>.apk
set -euo pipefail

MODE="${1:-debug}"
NEW_VERSION="${2:-}"

if [[ "$MODE" != "debug" && "$MODE" != "release" ]]; then
  echo "Uso: $0 [debug|release] [versionName]"
  echo "Ejemplo: $0 debug   |   $0 release 1.0.63"
  exit 1
fi

cd "$(dirname "${BASH_SOURCE[0]}")"

# --- 1. Localizar el Android SDK ---
if [[ -z "${ANDROID_HOME:-}" ]]; then
  for candidate in /opt/android-sdk "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    if [[ -d "$candidate" ]]; then
      export ANDROID_HOME="$candidate"
      break
    fi
  done
fi
if [[ -z "${ANDROID_HOME:-}" || ! -d "$ANDROID_HOME" ]]; then
  echo "ERROR: no encuentro el Android SDK. Define ANDROID_HOME con su ruta."
  exit 1
fi
echo "SDK: $ANDROID_HOME"

# --- 2. Comprobar Java ---
if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: no hay Java instalado (se necesita JDK 17+)."
  exit 1
fi
java -version 2>&1 | head -1

# --- 3. Cambiar versión si se pidió ---
GRADLE_FILE="app/build.gradle.kts"
if [[ -n "$NEW_VERSION" ]]; then
  CURRENT_CODE=$(grep -oP 'versionCode\s*=\s*\K[0-9]+' "$GRADLE_FILE" | head -1)
  NEW_CODE=$((CURRENT_CODE + 1))
  sed -i -E "s/versionCode = [0-9]+/versionCode = $NEW_CODE/" "$GRADLE_FILE"
  sed -i -E "s/versionName = \"[^\"]+\"/versionName = \"$NEW_VERSION\"/" "$GRADLE_FILE"
  echo "Versión: $NEW_VERSION (código $NEW_CODE)"
fi

# --- 4. Compilar + tests ---
# Sin --offline cuando hay internet (la primera vez descarga el plugin Android
# y dependencias a ~/.gradle). Con --offline solo funciona si ya compilaste
# antes con ese mismo usuario (la caché es por usuario: root y opencode no
# la comparten).
TASK="assembleDebug"
[[ "$MODE" == "release" ]] && TASK="assembleRelease"
OFFLINE_FLAG="--offline"
if curl -s -m 8 -o /dev/null https://dl.google.com/android/repository/repository2-1.xml; then
  echo "Internet OK: se permite descargar dependencias si faltan."
  OFFLINE_FLAG=""
else
  echo "Sin internet: compilando offline (requiere caché previa de este usuario)."
fi
echo "Compilando ($MODE)..."
./gradlew :app:testDebugUnitTest :app:$TASK $OFFLINE_FLAG

# --- 5. Mostrar resultado ---
APK="app/build/outputs/apk/$MODE/app-$MODE.apk"
echo ""
echo "OK ✅ APK listo:"
ls -la "$APK"
echo ""
echo "Versión incluida:"
AAPT_BIN=$(ls -d /opt/android-sdk/build-tools/*/aapt 2>/dev/null | sort -V | tail -1)
if [[ -n "$AAPT_BIN" ]]; then
  "$AAPT_BIN" dump badging "$APK" 2>/dev/null | grep -oE "version(Code|Name)='[^']+'" | tr '\n' ' '
  echo ""
fi
echo ""
echo "Pásalo al celular (cable/USB, WhatsApp, Drive o descarga) e instálalo encima."
