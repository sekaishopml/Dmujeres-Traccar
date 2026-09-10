#!/usr/bin/env bash
# traccar-systemd.sh — arranque de Traccar para systemd (Type=simple).
# Replica el mapeo de secretos de run-server-dev.sh y hace exec de java
# para que systemd supervise el proceso directamente.
set -euo pipefail

PROJECT_ROOT="/DMujeres-Tracking"
ENV_FILE="$PROJECT_ROOT/.env"
[[ -f "$ENV_FILE" ]] || { echo "ERROR: falta $ENV_FILE" >&2; exit 1; }
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

# Mismo mapeo que infrastructure/scripts/run-server-dev.sh
export DATABASE_PASSWORD="${POSTGRES_PASSWORD:?falta POSTGRES_PASSWORD en .env}"
export DATABASE_USER="${POSTGRES_USER:-traccar}"
export WEB_SECRET_TOKEN="${WEB_SECRET_TOKEN:-}"
export MOBILE_MQTT_ENABLE="${MOBILE_MQTT_ENABLE:-true}"
export MOBILE_MQTT_URL="${MOBILE_MQTT_URL:-mqtt://127.0.0.1:1883}"
export MOBILE_HTTP_ENABLE="${MOBILE_HTTP_ENABLE:-true}"
export MOBILE_HTTP_API_KEY="${MOBILE_HTTP_API_KEY:-dmj-dev-fallback-key}"
export MOBILE_MQTT_USERNAME="${MOBILE_MQTT_USERNAME:-dmj-consumer}"
export MOBILE_MQTT_PASSWORD="${MOBILE_MQTT_PASSWORD:-dmj-consumer-dev-pass}"

cd "$PROJECT_ROOT/server"
exec java -jar "$PROJECT_ROOT/server/target/tracker-server.jar" "$PROJECT_ROOT/server/conf/traccar-dev.xml"
