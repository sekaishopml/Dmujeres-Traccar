#!/usr/bin/env bash
# backup.sh — snapshot de recuperación: BD, .env, config del server y versiones.
# Uso: scripts/backup.sh [tag]
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"
set -a; source "$ENV_FILE"; set +a
COMPOSE_FILE="${COMPOSE_FILE:-$PROJECT_ROOT/infrastructure/compose/docker-compose.yml}"

BACKUP_DIR="${BACKUP_DIR:-/var/backups/dmj}"
TAG="${1:-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

echo "==> Backup BD -> $BACKUP_DIR/traccar-$TAG.dump"
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
  exec -T database pg_dump -U "${POSTGRES_USER:-traccar}" -d "${POSTGRES_DB:-traccar}" \
  --format=custom > "$BACKUP_DIR/traccar-$TAG.dump"

# Verificación post-backup (el --list falla si el dump está corrupto)
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T database \
  sh -c "cat > /tmp/verify.dump && pg_restore --list /tmp/verify.dump > /dev/null && echo 'dump OK'" \
  < "$BACKUP_DIR/traccar-$TAG.dump"

# Config y secretos necesarios para reconstruir en VPS nuevo (permisos 600)
echo "==> Backup config/secretos -> $BACKUP_DIR/restore/"
RESTORE_DIR="$BACKUP_DIR/restore"
mkdir -p "$RESTORE_DIR"
install -m 600 "$ENV_FILE" "$RESTORE_DIR/env"
if [[ -f "$PROJECT_ROOT/server/conf/traccar-dev.xml" ]]; then
  install -m 600 "$PROJECT_ROOT/server/conf/traccar-dev.xml" "$RESTORE_DIR/traccar-dev.xml"
fi
{
  echo "server:  $(git -C "$PROJECT_ROOT/server" describe --tags --always 2>/dev/null || echo n/a) ($(git -C "$PROJECT_ROOT/server" rev-parse --short HEAD 2>/dev/null))"
  echo "dashboard: $(git -C "$PROJECT_ROOT/dashboard" describe --tags --always 2>/dev/null || echo n/a) ($(git -C "$PROJECT_ROOT/dashboard" rev-parse --short HEAD 2>/dev/null))"
} > "$RESTORE_DIR/VERSIONS.txt"

# Retención
find "$BACKUP_DIR" -name 'traccar-*.dump' -mtime "+${BACKUP_RETENTION_DAYS:-30}" -delete

echo "OK. Backup completo en $BACKUP_DIR/traccar-$TAG.dump + $RESTORE_DIR"
ls -lh "$BACKUP_DIR"/traccar-"$TAG".dump