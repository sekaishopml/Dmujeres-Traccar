#!/usr/bin/env bash
# db-timescale.sh — aplica configuración TimescaleDB (compresión/retención) a la BD.
# Uso: scripts/db-timescale.sh [apply|status]
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"
set -a; source "$ENV_FILE"; set +a
COMPOSE_FILE="${COMPOSE_FILE:-$PROJECT_ROOT/infrastructure/compose/docker-compose.yml}"
SQL_FILE="$PROJECT_ROOT/infrastructure/database/timescale-compression.sql"

cmd="${1:-apply}"

case "$cmd" in
  apply)
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
      exec -T database psql -U "${POSTGRES_USER:-traccar}" -d "${POSTGRES_DB:-traccar}" -v ON_ERROR_STOP=1 -f - < "$SQL_FILE"
    echo "OK: configuración TimescaleDB aplicada."
    ;;
  status)
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T database psql -U "${POSTGRES_USER:-traccar}" -d "${POSTGRES_DB:-traccar}" -c "
      SELECT hypertable_name, compression_enabled
      FROM timescaledb_information.hypertables
      WHERE hypertable_name IN ('tc_positions','tc_events','tc_actions');
    " -c "
      SELECT hypertable_name, time_interval
      FROM timescaledb_information.dimensions
      WHERE hypertable_name = 'tc_positions' AND dimension_number = 1;
    "
    ;;
  *)
    echo "uso: $0 {apply|status}"
    exit 1
    ;;
esac