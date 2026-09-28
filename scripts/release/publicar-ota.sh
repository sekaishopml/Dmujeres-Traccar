#!/usr/bin/env bash
# Publica una OTA de la app: compila el release firmado, lo copia a ota/,
# actualiza latest.json y lo commitea. El push se hace aparte (el token lo
# pone el operador, nunca queda en este script).
#
# Uso: publicar-ota.sh <versionName> "<notas en español>"
# Ejemplo: publicar-ota.sh 2.1.78 "Paradas unidas y ajustes de caminata"
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$RAIZ"

VERSION="${1:?Uso: publicar-ota.sh <version> \"<notas>\"}"
NOTAS="${2:?Uso: publicar-ota.sh <version> \"<notas>\"}"
RAMA="${RAMA:-plataforma}"

# versionCode = siguiente al publicado en latest.json.
ACTUAL=$(node -e "console.log(require('$RAIZ/ota/latest.json').versionCode)")
VC=$((ACTUAL + 1))

echo "== $VERSION (vc $VC) en rama $RAMA =="

# 1. Bumpear versión en el código.
sed -i -E "s/versionCode [0-9]+/versionCode $VC/" fallback/app/build.gradle
sed -i -E "s/versionName '[^']+'/versionName '$VERSION'/" fallback/app/build.gradle
git diff -- fallback/app/build.gradle | head -12

# 2. Compilar release firmado (misma clave de flota de /opt/dmj-keys).
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
# shellcheck disable=SC1091
cd fallback && ./gradlew assembleGoogleRelease --offline 2>&1 | tail -2
cd "$RAIZ"
APK=fallback/app/build/outputs/apk/google/release/app-google-release.apk
test -f "$APK" || { echo "ERROR: no se generó $APK"; exit 1; }

# 3. Verificar firma de flota (sin esto, los teléfonos rechazan la actualización).
FIRMA=$(keytool -printcert -jarfile "$APK" 2>/dev/null | grep -m1 "SHA256" || true)
case "$FIRMA" in
  *86:7B:A1:29*) echo "firma de flota OK" ;;
  *) echo "ERROR: firma inesperada: $FIRMA"; exit 1 ;;
esac

# 4. Publicar en ota/ (se conserva el APK anterior para reversión).
DESTINO="ota/DMujeres-Tracking-$VERSION.apk"
cp "$APK" "$DESTINO"
SHA=$(sha256sum "$DESTINO" | cut -d' ' -f1)
node -e "
const fs = require('fs');
const m = { version: '$VERSION', versionCode: $VC,
  url: 'http://68.168.20.219:999/DMujeres-Tracking-$VERSION.apk',
  notes: process.argv[1], sha256: '$SHA' };
fs.writeFileSync('ota/latest.json', JSON.stringify(m, null, 2) + '\n');
" "$NOTAS"
cat ota/latest.json

# 5. Descarga servida por nginx.
CODIGO=$(curl -s -m 20 -o /dev/null -w "%{http_code}" "http://68.168.20.219:999/DMujeres-Tracking-$VERSION.apk")
test "$CODIGO" = "200" || { echo "ERROR: nginx no sirve el APK (HTTP $CODIGO)"; exit 1; }
echo "descarga OK (HTTP 200)"

# 6. Commit (sin secretos: las claves viven en /opt/dmj-keys, fuera de git).
git add fallback/app/build.gradle ota/latest.json "$DESTINO"
if git status --short | grep -iE "keystore|google-services|\.jks" >/dev/null; then
  echo "ERROR: posible secreto en el commit, abortando"; exit 1
fi
git commit -m "OTA $VERSION (vc $VC) publicada" | tail -1
echo "== Listo. Para lanzar el banner a la flota: =="
echo "git push https://<usuario>:<token>@github.com/sekaishopml/Dmujeres-Traccar.git $RAMA"
