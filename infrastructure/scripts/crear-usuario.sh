#!/usr/bin/env bash
# Crea un usuario (trabajador) en DMujeres Tracking con un solo comando.
#
# - Siempre crea el DISPOSITIVO con el que la app valida el login
#   (nombre "Jeremy", identificador "jeremy" en minúsculas).
# - Con --panel crea además el USUARIO DEL PANEL web (correo + contraseña).
#
# Uso:
#   bash infrastructure/scripts/crear-usuario.sh jeremy
#   bash infrastructure/scripts/crear-usuario.sh jeremy --panel
#   CLAVE=otraclave bash infrastructure/scripts/crear-usuario.sh jeremy --panel
set -euo pipefail

USUARIO="${1:?Uso: crear-usuario.sh <usuario> [--panel]}"
CON_PANEL="${2:-}"
CLAVE="${CLAVE:-cctv2026}"
DB="${DB_CONTAINER:-dmj-db}"
PG_USER="${PG_USER:-traccar}"
PG_DB="${PG_DB:-traccar}"

# El identificador va SIEMPRE en minúsculas (el servidor busca exacto).
ID="$(printf '%s' "$USUARIO" | tr '[:upper:]' '[:lower:]')"
NOMBRE="$(printf '%s' "$ID" | sed 's/^./\U&/')"

echo ">> creando dispositivo: $NOMBRE (id $ID)"
docker exec "$DB" psql -U "$PG_USER" -d "$PG_DB" -c "
INSERT INTO tc_devices (name, uniqueid)
SELECT '$NOMBRE', '$ID'
WHERE NOT EXISTS (SELECT 1 FROM tc_devices WHERE uniqueid = '$ID');
" >/dev/null

if [[ "$CON_PANEL" == "--panel" ]]; then
  # Traccar guarda la clave con PBKDF2-HMAC-SHA1 (1000 iteraciones, salt 24 bytes, hex).
  read -r HASH SALT <<<"$(python3 - "$CLAVE" <<'PY'
import hashlib, os, sys
salt = os.urandom(24)
h = hashlib.pbkdf2_hmac('sha1', sys.argv[1].encode(), salt, 1000, dklen=24)
print(h.hex(), salt.hex())
PY
)"
  CORREO="$ID@dmujeres.local"
  echo ">> creando usuario del panel: $CORREO (clave $CLAVE)"
  docker exec "$DB" psql -U "$PG_USER" -d "$PG_DB" -c "
INSERT INTO tc_users (id, name, email, hashedpassword, salt, readonly, administrator, latitude, longitude, zoom)
SELECT (SELECT COALESCE(MAX(id),0)+1 FROM tc_users), '$NOMBRE', '$CORREO', '$HASH', '$SALT', false, false, 0, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM tc_users WHERE email = '$CORREO');
" >/dev/null
  echo ">> panel: entra con $CORREO y la clave indicada"
fi

echo ">> listo. La app valida el login con el usuario: $ID"
docker exec "$DB" psql -U "$PG_USER" -d "$PG_DB" -tAc \
  "SELECT 'dispositivo: ' || name || ' (' || uniqueid || ')' FROM tc_devices WHERE uniqueid = '$ID';"
