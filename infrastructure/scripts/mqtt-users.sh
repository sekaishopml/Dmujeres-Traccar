#!/usr/bin/env bash
# mqtt-users.sh — gestión de usuarios MQTT en EMQX 5.8 (built_in_database)
# EMQX 5.8 no tiene comando ctl para usuarios; se usa la API HTTP o el dashboard.
#
# Uso:
#   scripts/mqtt-users.sh hash <password>   # genera línea CSV bootstrap
#   scripts/mqtt-users.sh add <user> <pass> # alta vía API
#   scripts/mqtt-users.sh list              # listar usuarios
#   scripts/mqtt-users.sh del <user>        # borrar usuario
#
# Credenciales de la API: EMQX_API_URL, EMQX_API_KEY+EMQX_API_SECRET, o fallback
# admin/public del dashboard en dev.
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"

if [[ -f "$ENV_FILE" ]]; then
  set -a; source "$ENV_FILE"; set +a
fi

HOST="${EMQX_API_HOST:-127.0.0.1}"
PORT="${EMQX_API_PORT:-18083}"
API_URL="${EMQX_API_URL:-http://${HOST}:${PORT}}"
API_KEY="${EMQX_API_KEY:-}"
API_SECRET="${EMQX_API_SECRET:-}"
DASH_PASS="${EMQX_DASHBOARD_PASSWORD:-public}"
AUTH_ID="${EMQX_AUTH_ID:-password_based:built_in_database}"
BASE="${API_URL%/}/api/v5"
AUTH_ENDPOINT="$BASE/authentication/$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$AUTH_ID")/users"

if ! command -v curl >/dev/null 2>&1; then
  echo "ERROR: se requiere curl" >&2
  exit 1
fi

# Cabecera de autenticación: API key (Basic) si está definida; si no, login admin
# del dashboard (dev) con AVISO.
auth_header() {
  if [[ -n "$API_KEY" || -n "$API_SECRET" ]]; then
    if [[ -z "$API_KEY" || -z "$API_SECRET" ]]; then
      echo "ERROR: EMQX_API_KEY y EMQX_API_SECRET deben definirse ambas." >&2
      exit 1
    fi
    local creds
    creds="$(printf '%s:%s' "$API_KEY" "$API_SECRET" | base64 | tr -d '\n')"
    echo "Authorization: Basic $creds"
  else
    echo "AVISO: sin EMQX_API_KEY/EMQX_API_SECRET — usando admin del dashboard" >&2
    echo "       (${EMQX_DASHBOARD_USER:-admin}). Solo válido para dev; en" >&2
    echo "       producción crea una API key en EMQX y defínela en .env." >&2
    local token
    token="$(curl -fsS -X POST -H 'Content-Type: application/json' \
      -d "{\"username\":\"${EMQX_DASHBOARD_USER:-admin}\",\"password\":\"$DASH_PASS\"}" \
      "$BASE/login" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')"
    echo "Authorization: Bearer $token"
  fi
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
    curl -fsS -X POST -H "$(auth_header)" -H 'Content-Type: application/json' \
      -d "{\"user_id\":\"$2\",\"password\":\"$3\",\"is_superuser\":false}" \
      "$AUTH_ENDPOINT"
    echo
    echo "OK: usuario '$2' añadido (password hasheado por EMQX en el servidor)."
    ;;
  list)
    curl -fsS -H "$(auth_header)" "$AUTH_ENDPOINT" \
      | python3 -c 'import sys,json; [print(u["user_id"], "superuser" if u["is_superuser"] else "") for u in json.load(sys.stdin)["data"]]'
    ;;
  del)
    if [[ $# -ne 2 ]]; then
      echo "uso: $0 del <user_id>" >&2
      exit 1
    fi
    enc="$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$2")"
    curl -fsS -X DELETE -H "$(auth_header)" "$AUTH_ENDPOINT/$enc"
    echo
    echo "OK: usuario '$2' eliminado."
    ;;
  *)
    echo "uso: $0 {hash <password>|add <user_id> <password>|list|del <user_id>}" >&2
    echo "  hash  -> imprime línea CSV para auth-file.csv (bootstrap, sin tocar el broker)" >&2
    echo "  add   -> alta en caliente vía API HTTP de EMQX (también en runtime)" >&2
    exit 1
    ;;
esac
