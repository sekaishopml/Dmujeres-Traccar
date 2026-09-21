#!/usr/bin/env bash
# package-project.sh — empaqueta el proyecto EXCLUYENDO secretos, con
# verificación automática que FALLA si un secreto entra al zip.
#
# Uso:
#   package-project.sh [salida.zip]        # crea el zip verificado
#   package-project.sh --verify-only x.zip # solo verifica un zip existente
#
# Motivo (incidente 20-sep-2026): dos zips públicos llevaron .env y keystores.
# Regla: .env, *.keystore, *.jks, keystore.properties, secrets.properties y
# local.properties NUNCA entran en un paquete.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${1:-$ROOT/dist/DMujeres-Tracking-$(date +%Y%m%d).zip}"

# Patrón de archivos prohibidos (rutas relativas al raíz del proyecto).
FORBIDDEN='(^|/)(\.env|.*\.keystore|.*\.jks|keystore\.properties|secrets\.properties|local\.properties)$'

verify_zip() {
  local zip="$1"
  if ! unzip -l "$zip" >/dev/null 2>&1; then
    echo "ERROR: no se puede leer el paquete ($zip)" >&2
    return 1
  fi
  local hits
  hits="$(unzip -l "$zip" | awk '{print $NF}' | grep -Ei "$FORBIDDEN" || true)"
  if [[ -n "$hits" ]]; then
    echo "ERROR: el paquete contiene archivos prohibidos:" >&2
    echo "$hits" >&2
    return 1
  fi
  echo "OK: sin secretos en el paquete ($zip)"
}

if [[ "${1:-}" == "--verify-only" ]]; then
  [[ -n "${2:-}" ]] || { echo "Uso: $0 --verify-only <zip>" >&2; exit 2; }
  verify_zip "$2"
  exit $?
fi

mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
cd "$ROOT"
zip -r -q "$OUT" . \
  -x "*/node_modules/*" "*/node_modules" \
     "*/.gradle/*" "*/.gradle" \
     "*/build/*" "*/build" \
     "*/target/*" "*/target" \
     ".git/*" ".git" "*/.git/*" "*/.git" \
     "*/logs/*" "*/logs" \
     "*.apk" "*.aab" "*.dex" "*.zip" \
     "*/__pycache__/*" "*/.idea/*" "*/.idea" \
     "*.log" "backups/*" "dashboard/public/*.zip" \
     ".env" "*/.env" "*.keystore" "*.jks" \
     "keystore.properties" "*/keystore.properties" \
     "secrets.properties" "*/secrets.properties" \
     "local.properties" "*/local.properties"

verify_zip "$OUT"
ls -lh "$OUT" | awk '{print "Paquete:", $5, $9}'
