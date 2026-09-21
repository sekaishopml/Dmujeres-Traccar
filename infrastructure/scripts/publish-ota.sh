#!/usr/bin/env bash
# publish-ota.sh — Publica una versión de la app DMujeres Tracking por el canal
# OTA del servidor Traccar (:999).
#
# Uso: publish-ota.sh <ruta-apk> <version> <notes>
#   version: X.Y.Z (sin "v")
#   notes:   texto exacto que verá el usuario antes de actualizar
#
# Rollout gradual OPCIONAL (lo lee el endpoint /api/mobile/v1/ota en caliente):
#   OTA_ROLLOUT_PERCENT=25   fija el porcentaje 0-100 de equipos que pueden
#                            actualizar; si no se pasa se CONSERVA el
#                            rollout.json existente (o 100 si no existe).
#   OTA_ROLLOUT_PAUSED=1     pausa el despliegue (salvo versiones forzadas por
#                            minVersionCode); si no se pasa se conserva.
#
# Escribe latest.json (+ rollout.json) y copia el APK en dashboard/build
# (directorio servido por web.path) y dashboard/public (fuente de Vite, se
# copia al build).
#
# REGLA: la URL de descarga NUNCA debe apuntar a direcciones de administración
# (Tailscale/LAN). Los teléfonos en datos móviles no las alcanzan y la
# actualización falla con "problemas de conexión con el servidor".
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APK="${1:?Uso: publish-ota.sh <ruta-apk> <version> <notes>}"
VERSION="${2:?Uso: publish-ota.sh <ruta-apk> <version> <notes>}"
NOTES="${3:?Uso: publish-ota.sh <ruta-apk> <version> <notes>}"
# P1 (R3.5-A7): sin URLs hardcodeadas. Configura el entorno SIEMPRE:
#   OTA_PUBLIC_BASE_URL=https://ota.tudominio        (producción, HTTPS)
#   OTA_PUBLIC_BASE_URL=http://68.168.20.219:999 + OTA_ALLOW_HTTP=1  (legado)
OTA_PUBLIC_BASE_URL="${OTA_PUBLIC_BASE_URL:?ERROR: define OTA_PUBLIC_BASE_URL (p.ej. https://ota.tudominio o http://host:999 con OTA_ALLOW_HTTP=1)}"
OTA_PUBLIC_BASE_URL="${OTA_PUBLIC_BASE_URL%/}"
if [[ "$OTA_PUBLIC_BASE_URL" != https://* && "${OTA_ALLOW_HTTP:-0}" != "1" ]]; then
  echo "ERROR (fail closed): OTA_PUBLIC_BASE_URL no es HTTPS. Para el host legado sin TLS usa OTA_ALLOW_HTTP=1 (documentado)." >&2
  exit 1
fi
[[ "$OTA_PUBLIC_BASE_URL" == https://* ]] || echo "AVISO: publicando por HTTP (modo legado/dev explícito OTA_ALLOW_HTTP=1)"
ASSET="DMujeres-Tracking-$VERSION.apk"
APK_URL="$OTA_PUBLIC_BASE_URL/$ASSET"

[[ -f "$APK" ]] || { echo "ERROR: no existe el APK: $APK" >&2; exit 1; }
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "ERROR: versión inválida: $VERSION" >&2; exit 1; }
if [[ -n "${OTA_ROLLOUT_PERCENT:-}" ]]; then
  [[ "$OTA_ROLLOUT_PERCENT" =~ ^[0-9]+$ && "$OTA_ROLLOUT_PERCENT" -ge 0 && "$OTA_ROLLOUT_PERCENT" -le 100 ]] \
    || { echo "ERROR: OTA_ROLLOUT_PERCENT debe ser un entero 0-100: $OTA_ROLLOUT_PERCENT" >&2; exit 1; }
fi

AAPT="$(command -v aapt || true)"
[[ -n "$AAPT" ]] || AAPT="/opt/android-sdk/build-tools/35.0.0/aapt"
VERSION_CODE="$("$AAPT" dump badging "$APK" 2>/dev/null | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)"
[[ -n "$VERSION_CODE" ]] || { echo "ERROR: no pude leer versionCode del APK" >&2; exit 1; }

for dir in "$ROOT/dashboard/build" "$ROOT/dashboard/public"; do
  [[ -d "$dir" ]] || { echo "ERROR: falta $dir" >&2; exit 1; }
  cp "$APK" "$dir/$ASSET"
  SHA256="$(sha256sum "$dir/$ASSET" | cut -d' ' -f1)"
  VERSION="$VERSION" VERSION_CODE="$VERSION_CODE" APK_URL="$APK_URL" NOTES="$NOTES" SHA256="$SHA256" \
    python3 - "$dir/latest.json" <<'PY'
import json, os, sys
with open(sys.argv[1], "w") as f:
    json.dump({
        "version": os.environ["VERSION"],
        "versionCode": int(os.environ["VERSION_CODE"]),
        "url": os.environ["APK_URL"],
        "notes": os.environ["NOTES"],
        "sha256": os.environ["SHA256"],
    }, f, ensure_ascii=False, indent=2)
    f.write("\n")
PY
  # Rollout gradual: OTA_ROLLOUT_PERCENT/OTA_ROLLOUT_PAUSED lo actualizan;
  # sin variables se conserva lo existente (o 100/activo si no hay archivo).
  OTA_ROLLOUT_PERCENT="${OTA_ROLLOUT_PERCENT:-}" OTA_ROLLOUT_PAUSED="${OTA_ROLLOUT_PAUSED:-}" \
    python3 - "$dir/rollout.json" <<'PY'
import json, os, sys
path = sys.argv[1]
data = {}
if os.path.exists(path):
    try:
        with open(path) as f:
            loaded = json.load(f)
        if isinstance(loaded, dict):
            data = loaded
    except Exception:
        data = {}
data.setdefault("percent", 100)
data.setdefault("paused", False)
percent = os.environ.get("OTA_ROLLOUT_PERCENT", "").strip()
paused = os.environ.get("OTA_ROLLOUT_PAUSED", "").strip()
if percent != "":
    data["percent"] = max(0, min(100, int(percent)))
if paused != "":
    data["paused"] = paused.lower() in ("1", "true", "yes", "on")
with open(path, "w") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
    f.write("\n")
PY
  echo ">> publicado: $dir/$ASSET + latest.json (sha256 ${SHA256:0:12}…)"
done

echo ">> versión: $VERSION (code $VERSION_CODE)"
echo ">> url:     $APK_URL"
echo ">> notas:   $NOTES"

if curl -sf -m 5 "$OTA_PUBLIC_BASE_URL/latest.json" >/dev/null 2>&1 \
  || curl -sf -m 5 "http://localhost:999/latest.json" >/dev/null 2>&1; then
  curl -sf -m 20 -o /dev/null -w ">> verificado en el servidor: APK HTTP %{http_code}, %{size_download} bytes\n" "$APK_URL"
else
  echo ">> AVISO: el servidor no responde en $OTA_PUBLIC_BASE_URL; verifica cuando esté arriba"
fi
