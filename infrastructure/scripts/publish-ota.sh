#!/usr/bin/env bash
# publish-ota.sh — Publica una versión de la app DMujeres Tracking por el canal
# OTA del servidor Traccar (:999).
#
# Uso: publish-ota.sh <ruta-apk> <version> <notes>
#   version: X.Y.Z (sin "v")
#   notes:   texto exacto que verá el usuario antes de actualizar
#
# Escribe latest.json y copia el APK en dashboard/build (directorio servido por
# web.path) y dashboard/public (fuente de Vite, se copia al build).
#
# REGLA: la URL de descarga NUNCA debe apuntar a direcciones de administración
# (Tailscale/LAN). Los teléfonos en datos móviles no las alcanzan y la
# actualización falla con "problemas de conexión con el servidor".
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APK="${1:?Uso: publish-ota.sh <ruta-apk> <version> <notes>}"
VERSION="${2:?Uso: publish-ota.sh <ruta-apk> <version> <notes>}"
NOTES="${3:?Uso: publish-ota.sh <ruta-apk> <version> <notes>}"
OTA_HOST="${OTA_HOST:-68.168.20.219}"
OTA_PORT="${OTA_PORT:-999}"
ASSET="DMujeres-Tracking-$VERSION.apk"
APK_URL="http://$OTA_HOST:$OTA_PORT/$ASSET"

[[ -f "$APK" ]] || { echo "ERROR: no existe el APK: $APK" >&2; exit 1; }
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "ERROR: versión inválida: $VERSION" >&2; exit 1; }

AAPT="$(command -v aapt || true)"
[[ -n "$AAPT" ]] || AAPT="/opt/android-sdk/build-tools/35.0.0/aapt"
VERSION_CODE="$("$AAPT" dump badging "$APK" 2>/dev/null | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)"
[[ -n "$VERSION_CODE" ]] || { echo "ERROR: no pude leer versionCode del APK" >&2; exit 1; }

for dir in "$ROOT/dashboard/build" "$ROOT/dashboard/public"; do
  [[ -d "$dir" ]] || { echo "ERROR: falta $dir" >&2; exit 1; }
  cp "$APK" "$dir/$ASSET"
  VERSION="$VERSION" VERSION_CODE="$VERSION_CODE" APK_URL="$APK_URL" NOTES="$NOTES" \
    python3 - "$dir/latest.json" <<'PY'
import json, os, sys
with open(sys.argv[1], "w") as f:
    json.dump({
        "version": os.environ["VERSION"],
        "versionCode": int(os.environ["VERSION_CODE"]),
        "url": os.environ["APK_URL"],
        "notes": os.environ["NOTES"],
    }, f, ensure_ascii=False, indent=2)
    f.write("\n")
PY
  echo ">> publicado: $dir/$ASSET + latest.json"
done

echo ">> versión: $VERSION (code $VERSION_CODE)"
echo ">> url:     $APK_URL"
echo ">> notas:   $NOTES"

if curl -sf -m 5 "http://localhost:$OTA_PORT/latest.json" >/dev/null 2>&1; then
  curl -sf -m 20 -o /dev/null -w ">> verificado en el servidor: APK HTTP %{http_code}, %{size_download} bytes\n" "$APK_URL"
else
  echo ">> AVISO: el servidor local :$OTA_PORT no responde; verifica cuando esté arriba"
fi
