#!/usr/bin/env bash
# pack-project.sh — empaqueta el monorepo para compartir, SIN SECRETOS.
#
# Uso: pack-project.sh [ruta-salida.zip]
#
# Nunca incluye: .env, secrets.properties, keystore.properties, *.keystore,
# *.jks, google-services.json, cuentas de servicio, logs, data, ni cachés de
# build. Al terminar VERIFICA el contenido y falla si aparece un secreto.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${1:-/tmp/DMujeres-Tracking-src.zip}"

cd "$ROOT"
rm -f "$OUT"
zip -rq "$OUT" . \
  -x "*/node_modules/*" -x "*/.gradle/*" -x "*/build/*" -x "*/target/*" \
  -x "*/logs/*" -x "*/.git/*" -x "*/.opencode/*" \
  -x "*/backups/*" -x "backups/*" \
  -x ".env" -x "*/.env" -x "*/.env.local" -x "*/local.properties" \
  -x "*/secrets.properties" -x "*/keystore.properties" \
  -x "*.keystore" -x "*.jks" \
  -x "*/google-services.json" -x "*firebase-adminsdk*.json" -x "*service*account*.json" \
  -x "*/dashboard/public/*.zip" -x "*/dashboard/build/*.zip"

if unzip -l "$OUT" | grep -qiE "(^|/)(\.env$|secrets\.properties$|keystore\.properties$|[^/]*\.keystore$|[^/]*\.jks$|google-services\.json$|firebase-adminsdk[^/]*\.json$|[^/]*\.tar\.gz$|[^/]*\.zip$)"; then
  echo "ERROR: el ZIP contiene secretos o snapshots; no se publica." >&2
  rm -f "$OUT"
  exit 1
fi

echo "OK: $OUT ($(du -h "$OUT" | cut -f1)) sin secretos"
