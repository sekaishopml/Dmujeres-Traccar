#!/usr/bin/env bash
# restore.sh — restaura un backup de la plataforma sobre el servicio 'database'.
# Uso: scripts/restore.sh /ruta/a/traccar-YYYYMMDD-HHMMSS.dump
# Detiene el server, recrea la BD y carga el dump. Destruye los datos actuales.
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "uso: $0 <archivo.dump>"
  exit 1
fi
DUMP_FILE="$1"
[[ -f "$DUMP_FILE" ]] || { echo "ERROR: no existe $DUMP_FILE"; exit 1; }

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"
set -a; source "$ENV_FILE"; set +a
COMPOSE_FILE="${COMPOSE_FILE:-$PROJECT_ROOT/infrastructure/compose/docker-compose.yml}"

DB_NAME="${POSTGRES_DB:-traccar}"
DB_USER="${POSTGRES_USER:-traccar}"

echo "AVISO: se restaurará $DUMP_FILE sobre $DB_NAME (DESTRUYE datos actuales)."
read -r -p "¿Continuar? [y/N] " ans
[[ "$ans" == "y" || "$ans" == "Y" ]] || exit 1

echo "==> 1. Deteniendo server Traccar (si está corriendo)..."
pkill -f 'tracker-server.jar' 2>/dev/null && echo "    server detenido" || echo "    (no estaba corriendo)"

echo "==> 2. Asegurando contenedor database arriba..."
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d database

echo "==> 3. Recreando BD limpia..."
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T database \
  psql -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 \
  -c "DROP DATABASE IF EXISTS $DB_NAME WITH (FORCE);" \
  -c "CREATE DATABASE $DB_NAME OWNER $DB_USER;"

echo "==> 4. Restaurando dump..."
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T database \
  pg_restore -U "$DB_USER" -d "$DB_NAME" --no-owner < "$DUMP_FILE"

echo "==> 5. Verificación post-restore:"
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T database \
  psql -U "$DB_USER" -d "$DB_NAME" -c "SELECT count(*) AS users FROM tc_users; SELECT count(*) AS devices FROM tc_devices;"

echo "OK. Database restaurada. Reiniciar el server Traccar cuando corresponda."