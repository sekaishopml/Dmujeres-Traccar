#!/usr/bin/env bash
# migrate.sh — valida el estado de las migraciones Liquibase aplicadas a la BD
# (el server las aplica en su arranque; este script solo inspecciona).
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"
set -a; source "$ENV_FILE"; set +a
COMPOSE_FILE="${COMPOSE_FILE:-$PROJECT_ROOT/infrastructure/compose/docker-compose.yml}"

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
  exec -T database psql -U "${POSTGRES_USER:-traccar}" -d "${POSTGRES_DB:-traccar}" -c \
  "SELECT id, filename || ' -> ' || orderexecuted AS applied FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 5;"

echo "(changelogs Liquibase aplicados por el server en su arranque)"