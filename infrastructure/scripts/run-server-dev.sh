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
LOG_DIR="$PROJECT_ROOT/server/logs"
LOG_FILE="$LOG_DIR/server-dev.log"
PID_FILE="$LOG_DIR/server-dev.pid"
HEALTH_URL="http://localhost:${SERVER_WEB_PORT:-999}/api/health"
HEALTH_TIMEOUT="${SERVER_HEALTH_TIMEOUT:-90}"

cmd="${1:-start}"

# Mapeo de secretos: el server lee DATABASE_*/WEB_* (igual que el compose oficial)
export DATABASE_PASSWORD="${POSTGRES_PASSWORD}"
export DATABASE_USER="${POSTGRES_USER:-traccar}"
export WEB_SECRET_TOKEN="${WEB_SECRET_TOKEN:-}"
# Canal móvil ACTIVO por defecto (la app Android depende de MQTT)
export MOBILE_MQTT_ENABLE="${MOBILE_MQTT_ENABLE:-true}"
export MOBILE_MQTT_URL="${MOBILE_MQTT_URL:-mqtt://127.0.0.1:1883}"
export MOBILE_HTTP_ENABLE="${MOBILE_HTTP_ENABLE:-true}"
export MOBILE_HTTP_API_KEY="${MOBILE_HTTP_API_KEY:-dmj-dev-fallback-key}"
# Cliente MQTT del server en EMQX. Usuario dmj-consumer (ACL subscribe +/telemetry,
# publish +/ack). El password default es solo dev; definir MOBILE_MQTT_PASSWORD en .env
# si auth-file.csv se regenera con otro password.
export MOBILE_MQTT_USERNAME="${MOBILE_MQTT_USERNAME:-dmj-consumer}"
export MOBILE_MQTT_PASSWORD="${MOBILE_MQTT_PASSWORD:-dmj-consumer-dev-pass}"
if [[ "${MOBILE_MQTT_PASSWORD}" == "dmj-consumer-dev-pass" ]]; then
  echo "AVISO: MOBILE_MQTT_PASSWORD usa el default dev (dmj-consumer-dev-pass)."
  echo "       Definir MOBILE_MQTT_PASSWORD en .env si el CSV cambió."
fi
if [[ -z "${WEB_SECRET_TOKEN}" ]]; then
  echo "AVISO: WEB_SECRET_TOKEN no definido en .env — las sesiones/tokens se invalidan al reiniciar."
fi

make_config() {
  [[ -f "$CONFIG_TEMPLATE" ]] || { echo "ERROR: falta $CONFIG_TEMPLATE"; exit 1; }
  if [[ -f "$CONFIG_FILE" ]]; then
    backup="$CONFIG_FILE.bak-$(date +%Y%m%d-%H%M%S)"
    cp "$CONFIG_FILE" "$backup"
    echo "Backup config previa: $backup"
  fi
  cp "$CONFIG_TEMPLATE" "$CONFIG_FILE"
  chmod 600 "$CONFIG_FILE"
  echo "OK: $CONFIG_FILE generado desde el template (sin secretos; se inyectan por env)."
}

wait_health() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT))
  while (( SECONDS < deadline )); do
    if curl -sf -o /dev/null "$HEALTH_URL" 2>/dev/null; then
      curl -s -w "\nhealth HTTP %{http_code}\n" "$HEALTH_URL" || true
      return 0
    fi
    sleep 2
  done
  return 1
}

case "$cmd" in
  make-config)
    make_config
    ;;
  start)
    [[ -f "$CONFIG_FILE" ]] || make_config
    mkdir -p "$LOG_DIR"
    if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
      echo "El server ya está corriendo (PID $(cat "$PID_FILE")). Usa '$0 stop' o '$0 restart'."
      exit 1
    fi
    rm -f "$PID_FILE"
    echo "Arrancando server (log: $LOG_FILE, pid: $PID_FILE)..."
    cd "$PROJECT_ROOT/server"
    setsid nohup java -jar "$SERVER_JAR" "$CONFIG_FILE" > "$LOG_FILE" 2>&1 < /dev/null &
    echo $! > "$PID_FILE"
    if wait_health; then
      echo "OK: server healthy ($HEALTH_URL), PID $(cat "$PID_FILE")"
    else
      echo "ERROR: el server no respondió $HEALTH_URL en ${HEALTH_TIMEOUT}s."
      echo "Revisar: tail -50 $LOG_FILE  (PID $(cat "$PID_FILE" 2>/dev/null || echo '?'))"
      exit 1
    fi
    ;;
  stop)
    if [[ -f "$PID_FILE" ]]; then
      pid="$(cat "$PID_FILE")"
      if kill -0 "$pid" 2>/dev/null; then
        kill "$pid" && echo "server detenido (PID $pid)" || echo "(no se pudo detener PID $pid)"
      else
        echo "(PID $pid ya no existe; limpiando)"
      fi
      rm -f "$PID_FILE"
    fi
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