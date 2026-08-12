#!/usr/bin/env bash
# run-server-dev.sh — arranca el server Traccar en dev contra la infraestructura local.
# Carga .env (secretos), inyecta DATABASE_* al proceso. No versionar secretos.
# Uso: scripts/run-server-dev.sh [stop|logs]
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"
[[ -f "$ENV_FILE" ]] || { echo "ERROR: falta $ENV_FILE"; exit 1; }
set -a; source "$ENV_FILE"; set +a

# Mapeo de secretos: el server lee DATABASE_* (como en el compose oficial)
export DATABASE_PASSWORD="${POSTGRES_PASSWORD}"
export DATABASE_USER="${POSTGRES_USER:-traccar}"

SERVER_JAR="$PROJECT_ROOT/server/target/tracker-server.jar"
CONFIG_FILE="$PROJECT_ROOT/server/conf/traccar-dev.xml"
LOG_FILE="/tmp/opencode/server.log"

cmd="${1:-start}"
case "$cmd" in
  start)
    echo "Arrancando server (PID en $LOG_FILE)..."
    cd "$PROJECT_ROOT/server"
    setsid nohup java -jar "$SERVER_JAR" "$CONFIG_FILE" > "$LOG_FILE" 2>&1 < /dev/null &
    sleep 12
    curl -s -w "\nhealth HTTP %{http_code}\n" http://localhost:8082/api/health || true
    ;;
  stop)
    pkill -f "[t]racker-server.jar" && echo "server detenido" || echo "(no corriendo)"
    ;;
  logs)
    tail -f "$LOG_FILE"
    ;;
  *)
    echo "uso: $0 {start|stop|logs}"
    exit 1
    ;;
esac