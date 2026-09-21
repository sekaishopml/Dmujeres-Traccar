#!/usr/bin/env bash
# traccar-watchdog.sh — vigila que el servidor Traccar responda en localhost:5055
# y lo reinicia si está colgado (proceso vivo pero sin atender) o muerto sin que
# systemd lo detecte (Restart=always cubre la salida limpia; esto cubre el cuelgue).
set -euo pipefail

URL="http://127.0.0.1:5055/"
UNIT="dmj-traccar.service"
LOG_FILE="${WATCHDOG_LOG:-/var/log/dmj/watchdog.log}"

code() {
  curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$URL" 2>/dev/null || true
}

# Escribe a stdout (comportamiento previo para journald) y, best-effort, a la
# bitácora que lee el panel de alertas. Un fallo del log nunca altera el flujo.
log() {
  echo "$1"
  if mkdir -p "$(dirname "$LOG_FILE")" 2>/dev/null; then
    echo "$1" >>"$LOG_FILE" 2>/dev/null || true
  fi
}

CODE="$(code)"
# Un 000 puede ser un reinicio en curso: reintento a los 10 s antes de tocar nada.
if [[ "$CODE" == "000" ]]; then
  sleep 10
  CODE="$(code)"
fi

if [[ "$CODE" == "000" ]]; then
  log "$(date '+%F %T') watchdog: sin respuesta del servidor (code=$CODE); reinicio $UNIT"
  systemctl restart "$UNIT"
  sleep 5
  log "$(date '+%F %T') watchdog: estado tras reinicio: $(code)"
else
  log "$(date '+%F %T') watchdog: ok (HTTP $CODE)"
fi
