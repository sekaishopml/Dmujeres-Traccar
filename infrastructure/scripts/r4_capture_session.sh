#!/usr/bin/env bash
# r4_capture_session.sh — Captura forense SOLO LECTURA para la campaña R4.
#
# Herramienta de QA/ops (no es código de producto: no modifica app/server/
# dashboard; solo ejecuta SELECT contra dmj-db). Respeta el CODE FREEZE de R4.
#
# Uso:
#   r4_capture_session.sh <deviceId> ["YYYY-MM-DD HH:MM" desde] ["YYYY-MM-DD HH:MM" hasta]
# Zona: columnas tc_* en hora local (−05).
set -euo pipefail

DEV="${1:?Uso: r4_capture_session.sh <deviceId> [desde] [hasta]}"
FROM="${2:-$(date -d '2 hours ago' '+%Y-%m-%d %H:%M')}"
TO="${3:-$(date '+%Y-%m-%d %H:%M')}"

Q() { docker exec dmj-db psql -U traccar -d traccar -t -A -c "$1" 2>/dev/null; }

echo "=========== R4 CAPTURE ==========="
echo "device=$DEV  ventana local: $FROM → $TO"
echo

echo "-- estado del dispositivo --"
Q "SELECT d.name, to_char(d.lastupdate,'HH24:MI:SS'), d.attributes::jsonb->>'mobile.presenceState', d.attributes::jsonb->>'mobile.presenceReason', d.attributes::jsonb->>'mobile.appVersion', d.attributes::jsonb->>'mobile.battery', d.attributes::jsonb->>'mobile.network', d.attributes::jsonb->>'mobile.pending', d.attributes::jsonb->>'mobile.mqttState' FROM tc_devices d WHERE d.id=$DEV;"
echo

echo "-- posiciones (fix | recibida | delta_s | calidad | gnss | speedSource) --"
Q "SELECT to_char(fixtime,'HH24:MI:SS'), to_char(servertime,'HH24:MI:SS'), extract(epoch from (servertime-fixtime))::int, coalesce(attributes::jsonb->>'qualityClass','-'), coalesce(attributes::jsonb->>'gnssUsed','-'), coalesce(attributes::jsonb->>'speedSource','-') FROM tc_positions WHERE deviceid=$DEV AND fixtime BETWEEN '$FROM' AND '$TO' ORDER BY fixtime;"
echo

echo "-- huecos de CAPTURA entre fixes (>30s) --"
Q "WITH p AS (SELECT fixtime, lag(fixtime) OVER (ORDER BY fixtime) prev FROM tc_positions WHERE deviceid=$DEV AND fixtime BETWEEN '$FROM' AND '$TO') SELECT to_char(prev,'HH24:MI:SS') || ' -> ' || to_char(fixtime,'HH24:MI:SS'), extract(epoch from (fixtime-prev))::int FROM p WHERE prev IS NOT NULL AND extract(epoch from (fixtime-prev)) > 30 ORDER BY fixtime;"
echo

echo "-- presencia (transiciones) --"
Q "SELECT to_char(eventtime,'HH24:MI:SS'), type, left(coalesce(attributes::text,''),90) FROM tc_events WHERE deviceid=$DEV AND type LIKE 'mobilePresence%' AND eventtime BETWEEN '$FROM' AND '$TO' ORDER BY eventtime;"
echo

echo "-- recovery (probes/etapas/timeouts) --"
Q "SELECT to_char(ts,'HH24:MI:SS'), eventtype, reason, status, source FROM tc_recovery_event WHERE deviceid=$DEV AND ts BETWEEN '$FROM' AND '$TO' ORDER BY ts;"
echo

echo "-- health local (fgs/outbox/motion/network) --"
Q "SELECT to_char(ts,'HH24:MI:SS'), eventtype, healthstate, fgs, motion, network, outbox FROM tc_device_health WHERE deviceid=$DEV AND ts BETWEEN '$FROM' AND '$TO' ORDER BY ts;"
echo

echo "-- mensajes recibidos por minuto (created) --"
Q "SELECT to_char(created,'HH24:MI'), count(*) FROM tc_mobile_messages WHERE deviceid=$DEV AND created BETWEEN '$FROM' AND '$TO' GROUP BY 1 ORDER BY 1;"
echo

echo "-- integridad ventana: capturadas | recibidas | duplicados messageid --"
Q "SELECT (SELECT count(*) FROM tc_positions WHERE deviceid=$DEV AND fixtime BETWEEN '$FROM' AND '$TO'), (SELECT count(*) FROM tc_mobile_messages WHERE deviceid=$DEV AND created BETWEEN '$FROM' AND '$TO' AND positionid > 0), (SELECT count(*) - count(DISTINCT messageid) FROM tc_mobile_messages WHERE deviceid=$DEV AND created BETWEEN '$FROM' AND '$TO');"
echo "=================================="
