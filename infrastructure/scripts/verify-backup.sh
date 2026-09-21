#!/usr/bin/env bash
# verify-backup.sh — verificación NO destructiva del último dump de respaldo.
# Corre semanal por cron: si el dump más reciente está corrupto o viejo,
# falla con código != 0 y queda en el log (y el panel lo muestra si se revisa).
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/var/backups/dmj}"
MAX_AGE_HOURS="${MAX_AGE_HOURS:-26}"

LATEST="$(ls -t "$BACKUP_DIR"/traccar-*.dump 2>/dev/null | head -1 || true)"
if [[ -z "$LATEST" ]]; then
  echo "FALLO: no hay ningún dump en $BACKUP_DIR" >&2
  exit 1
fi

AGE_S=$(( $(date +%s) - $(stat -c %Y "$LATEST") ))
AGE_H=$(( AGE_S / 3600 ))
if (( AGE_H > MAX_AGE_HOURS )); then
  echo "FALLO: el dump más reciente tiene ${AGE_H}h (> ${MAX_AGE_HOURS}h): $LATEST" >&2
  exit 1
fi

# Verificación de integridad del archivo (sin tocar la BD en uso).
docker exec -i dmj-db sh -c "cat > /tmp/verify.dump && pg_restore --list /tmp/verify.dump > /dev/null" < "$LATEST"
echo "OK: $LATEST (${AGE_H}h) verificable y reciente"
