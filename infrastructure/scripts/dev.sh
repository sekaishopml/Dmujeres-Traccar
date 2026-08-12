#!/usr/bin/env bash
# DMujeres Traccar Platform — operaciones de infraestructura
# Uso: scripts/dev.sh {up|down|restart|logs|status|psql|redis|mqtt}
# El compose file se puede sobreescribir con COMPOSE_FILE en .env (ver .env.example).
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"

if [[ -f "$ENV_FILE" ]]; then
  set -a; source "$ENV_FILE"; set +a
fi
COMPOSE_FILE="${COMPOSE_FILE:-$PROJECT_ROOT/infrastructure/compose/docker-compose.yml}"

cd "$PROJECT_ROOT"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: falta $ENV_FILE (copiar .env.example -> .env y ajustar secretos)"
  exit 1
fi

cmd="${1:-up}"

case "$cmd" in
  up)
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d
    echo "OK. Servicios: PG(127.0.0.1:5433) Redis(127.0.0.1:6379) MQTT(127.0.0.1:1883) EMQX-dash(127.0.0.1:18083)"
    ;;
  down)
    shift || true
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" down "$@"
    ;;
  restart)
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" restart
    ;;
  logs)
    shift || true
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" logs -f "$@"
    ;;
  status)
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps
    ;;
  psql)
    shift || true
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec database \
      psql -U "${POSTGRES_USER:-traccar}" -d "${POSTGRES_DB:-traccar}" "$@"
    ;;
  redis)
    shift || true
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec redis redis-cli "$@"
    ;;
  mqtt)
    shift || true
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec mqtt emqx ctl "$@"
    ;;
  *)
    echo "uso: $0 {up|down|restart|logs|status|psql|redis|mqtt}"
    exit 1
    ;;
esac