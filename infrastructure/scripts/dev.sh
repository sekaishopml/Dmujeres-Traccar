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

# COMPOSE_FILE puede ser una lista separada por ':' (p. ej. compose dev + override
# docker-compose.emqx-auth.yml). `docker compose -f` NO divide por ':' en v2, así
# que se expande la lista a múltiples -f.
IFS=':' read -r -a COMPOSE_FILES <<< "$COMPOSE_FILE"
COMPOSE_ARGS=()
for f in "${COMPOSE_FILES[@]}"; do
  COMPOSE_ARGS+=(-f "$f")
done

cd "$PROJECT_ROOT"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: falta $ENV_FILE (copiar .env.example -> .env y ajustar secretos)"
  exit 1
fi

cmd="${1:-up}"

case "$cmd" in
  up)
    docker compose --env-file "$ENV_FILE" "${COMPOSE_ARGS[@]}" up -d
    echo "OK. Servicios: PG(127.0.0.1:5433) Redis(127.0.0.1:6379) MQTT(127.0.0.1:1883) EMQX-dash(127.0.0.1:18083)"
    ;;
  down)
    shift || true
    docker compose --env-file "$ENV_FILE" "${COMPOSE_ARGS[@]}" down "$@"
    ;;
  restart)
    docker compose --env-file "$ENV_FILE" "${COMPOSE_ARGS[@]}" restart
    ;;
  logs)
    shift || true
    docker compose --env-file "$ENV_FILE" "${COMPOSE_ARGS[@]}" logs -f "$@"
    ;;
  status)
    docker compose --env-file "$ENV_FILE" "${COMPOSE_ARGS[@]}" ps
    ;;
  psql)
    shift || true
    docker compose --env-file "$ENV_FILE" "${COMPOSE_ARGS[@]}" exec database \
      psql -U "${POSTGRES_USER:-traccar}" -d "${POSTGRES_DB:-traccar}" "$@"
    ;;
  redis)
    shift || true
    docker compose --env-file "$ENV_FILE" "${COMPOSE_ARGS[@]}" exec redis redis-cli "$@"
    ;;
  mqtt)
    shift || true
    docker compose --env-file "$ENV_FILE" "${COMPOSE_ARGS[@]}" exec mqtt emqx ctl "$@"
    ;;
  *)
    echo "uso: $0 {up|down|restart|logs|status|psql|redis|mqtt}"
    exit 1
    ;;
esac