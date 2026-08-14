#!/usr/bin/env bash
# run-server-dev.sh — arranca el server Traccar en dev contra la infraestructura local.
# Carga .env (secretos), inyecta DATABASE_*/WEB_* al proceso. No versionar secretos.
# Uso: scripts/run-server-dev.sh {start|stop|restart|logs|make-config}
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"
[[ -f "$ENV_FILE" ]] || { echo "ERROR: falta $ENV_FILE"; exit 1; }
set -a; source "$ENV_FILE"; set +a

SERVER_JAR="$PROJECT_ROOT/server/target/tracker-server.jar"
CONFIG_TEMPLATE="$PROJECT_ROOT/server/conf/traccar-dev.xml.example"
CONFIG_FILE="$PROJECT_ROOT/server/conf/traccar-dev.xml"
LOG_FILE="$PROJECT_ROOT/server/logs/server-dev.log"

cmd="${1:-start}"

# Mapeo de secretos: el server lee DATABASE_*/WEB_* (igual que el compose oficial)
export DATABASE_PASSWORD="${POSTGRES_PASSWORD}"
export DATABASE_USER="${POSTGRES_USER:-traccar}"
export WEB_SECRET_TOKEN="${WEB_SECRET_TOKEN:-}"
# Canal móvil ACTIVO por defecto (la app Android depende de MQTT)
export MOBILE_MQTT_ENABLE="${MOBILE_MQTT_ENABLE:-true}"
export MOBILE_MQTT_URL="${MOBILE_MQTT_URL:-mqtt://127.0.0.1:1883}"
if [[ -z "${WEB_SECRET_TOKEN}" ]]; then
  echo "AVISO: WEB_SECRET_TOKEN no definido en .env — las sesiones/tokens se invalidan al reiniciar."
fi

make_config() {
  [[ -f "$CONFIG_TEMPLATE" ]] || { echo "ERROR: falta $CONFIG_TEMPLATE"; exit 1; }
  cp "$CONFIG_TEMPLATE" "$CONFIG_FILE"
  chmod 600 "$CONFIG_FILE"
  echo "OK: $CONFIG_FILE generado desde el template (sin secretos; se inyectan por env)."
}

case "$cmd" in
  make-config)
    make_config
    ;;
  start)
    [[ -f "$CONFIG_FILE" ]] || make_config
    echo "Arrancando server (log: $LOG_FILE)..."
    cd "$PROJECT_ROOT/server"
    setsid nohup java -jar "$SERVER_JAR" "$CONFIG_FILE" > "$LOG_FILE" 2>&1 < /dev/null &
    sleep 12
    curl -s -w "\nhealth HTTP %{http_code}\n" http://localhost:8082/api/health || true
    ;;
  stop)
    pkill -f "[t]racker-server.jar" && echo "server detenido" || echo "(no corriendo)"
    ;;
  restart)
    "$0" stop
    sleep 1
    "$0" start
    ;;
  logs)
    tail -f "$LOG_FILE"
    ;;
  *)
    echo "uso: $0 {start|stop|restart|logs|make-config}"
    exit 1
    ;;
esac