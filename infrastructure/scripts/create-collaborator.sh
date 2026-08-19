#!/usr/bin/env bash
# create-collaborator.sh — crea el acceso de un colaborador:
# 1) Dispositivo en Traccar (uniqueId = usuario) para que el panel lo muestre online
# 2) Usuario MQTT en EMQX (usuario/contraseña) para que la app conecte
# Uso: scripts/create-collaborator.sh <usuario> <contraseña>
# Requiere en .env: DASH_URL, DASH_ADMIN_EMAIL, DASH_ADMIN_PASSWORD.
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "uso: $0 <usuario> <contraseña>"
  exit 1
fi
USERNAME="$1"
PASSWORD="$2"

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"
set -a; source "$ENV_FILE"; set +a

DASH_URL="${DASH_URL:-http://localhost:8082}"
DASH_ADMIN_EMAIL="${DASH_ADMIN_EMAIL:?falta DASH_ADMIN_EMAIL en .env}"
DASH_ADMIN_PASSWORD="${DASH_ADMIN_PASSWORD:?falta DASH_ADMIN_PASSWORD en .env}"

[[ "$USERNAME" =~ ^[A-Za-z0-9._-]{1,128}$ ]] || { echo "ERROR: usuario inválido (solo letras, números, . _ -)"; exit 1; }

echo "==> 1/2 Creando dispositivo en Traccar (uniqueId=$USERNAME)..."
COOKIE=$(mktemp)
curl -s -c "$COOKIE" -X POST "$DASH_URL/api/session" \
  -d "email=$DASH_ADMIN_EMAIL&password=$DASH_ADMIN_PASSWORD" -o /dev/null
if ! curl -s -b "$COOKIE" -X POST "$DASH_URL/api/devices" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"$USERNAME\",\"uniqueId\":\"$USERNAME\",\"category\":\"default\"}" \
    -o /tmp/opencode/collab-device.json -w '%{http_code}' | grep -q 200; then
  echo "   (puede que ya exista; se ignora)"
else
  echo "   dispositivo creado: $(python3 -c "import json;print(json.load(open('/tmp/opencode/collab-device.json'))['id'])" 2>/dev/null || echo OK)"
fi
rm -f "$COOKIE"

echo "==> 2/2 Creando usuario MQTT en EMQX ($USERNAME)..."
"$PROJECT_ROOT/infrastructure/scripts/mqtt-users.sh" add "$USERNAME" "$PASSWORD"

echo ""
echo "=============================================================="
echo " COLABORADOR LISTO"
echo "  Usuario:     $USERNAME"
echo "  Contraseña:  $PASSWORD"
echo "  (entregar estos datos; en la app solo escribir usuario/contraseña)"
echo "=============================================================="
