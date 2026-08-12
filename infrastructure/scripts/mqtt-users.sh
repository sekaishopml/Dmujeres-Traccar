#!/usr/bin/env bash
# =============================================================================
# mqtt-users.sh — gestión de usuarios MQTT (authN file) en EMQX 5.8
# =============================================================================
# EMQX 5.8 NO expone un comando `emqx ctl` para usuarios MQTT (solo dashboard
# admins). La vía oficial es la API HTTP del dashboard (o la UI). Este script
# la usa y además genera líneas de bootstrap para auth-file.csv.
#
# Autenticador esperado: password_based / built_in_database (ver emqx.conf).
#
# Uso:
#   scripts/mqtt-users.sh hash <password>              # línea CSV bootstrap (sha256+sal prefix)
#   scripts/mqtt-users.sh add <user_id> <password>     # alta en caliente vía API
#   scripts/mqtt-users.sh list                         # listar usuarios
#   scripts/mqtt-users.sh del <user_id>                # borrar usuario
#
# Variables (o .env):
#   EMQX_API_HOST (default 127.0.0.1)
#   EMQX_API_PORT (default 18083)         # dashboard HTTP (dev) o HTTPS (prod vía tunel/fw)
#   EMQX_DASHBOARD_PASSWORD (default public, dev)
#   EMQX_AUTH_ID (default password_based:built_in_database)
#
# Ejemplo:
#   ./infrastructure/scripts/mqtt-users.sh hash 'misecreto'
#   ./infrastructure/scripts/mqtt-users.sh add dmj-device-abc123 'misecreto'
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"

if [[ -f "$ENV_FILE" ]]; then
  set -a; source "$ENV_FILE"; set +a
fi

HOST="${EMQX_API_HOST:-127.0.0.1}"
PORT="${EMQX_API_PORT:-18083}"
DASH_PASS="${EMQX_DASHBOARD_PASSWORD:-public}"
AUTH_ID="${EMQX_AUTH_ID:-password_based:built_in_database}"
BASE="http://${HOST}:${PORT}/api/v5"
AUTH_ENDPOINT="$BASE/authentication/$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$AUTH_ID")/users"

if ! command -v curl >/dev/null 2>&1; then
  echo "ERROR: se requiere curl" >&2
  exit 1
fi

dashboard_token() {
  curl -fsS -X POST -H 'Content-Type: application/json' \
    -d "{\"username\":\"admin\",\"password\":\"$DASH_PASS\"}" \
    "$BASE/login" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])'
}

cmd="${1:-}"

case "$cmd" in
  hash)
    if [[ $# -ne 2 ]]; then
      echo "uso: $0 hash <password>" >&2
      exit 1
    fi
    password="$2"
    salt="$(openssl rand -hex 16)"
    hash="$(printf '%s' "${salt}${password}" | openssl dgst -sha256 -hex | cut -d' ' -f2)"
    echo "user_id,password_hash,salt,is_superuser"
    echo "<user_id>,${hash},${salt},false"
    echo "# salt_position=prefix ; sha256(salt ++ password)" >&2
    ;;
  add)
    if [[ $# -ne 3 ]]; then
      echo "uso: $0 add <user_id> <password>" >&2
      exit 1
    fi
    token="$(dashboard_token)"
    curl -fsS -X POST -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
      -d "{\"user_id\":\"$2\",\"password\":\"$3\",\"is_superuser\":false}" \
      "$AUTH_ENDPOINT"
    echo
    echo "OK: usuario '$2' añadido (password hasheado por EMQX en el servidor)."
    ;;
  list)
    token="$(dashboard_token)"
    curl -fsS -H "Authorization: Bearer $token" "$AUTH_ENDPOINT" \
      | python3 -c 'import sys,json; [print(u["user_id"], "superuser" if u["is_superuser"] else "") for u in json.load(sys.stdin)["data"]]'
    ;;
  del)
    if [[ $# -ne 2 ]]; then
      echo "uso: $0 del <user_id>" >&2
      exit 1
    fi
    token="$(dashboard_token)"
    enc="$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$2")"
    curl -fsS -X DELETE -H "Authorization: Bearer $token" "$AUTH_ENDPOINT/$enc"
    echo
    echo "OK: usuario '$2' eliminado."
    ;;
  *)
    echo "uso: $0 {hash <password>|add <user_id> <password>|list|del <user_id>}" >&2
    echo "  hash  -> imprime línea CSV para auth-file.csv (bootstrap, sin tocar el broker)" >&2
    echo "  add   -> alta en caliente vía API HTTP del dashboard EMQX" >&2
    exit 1
    ;;
esac
