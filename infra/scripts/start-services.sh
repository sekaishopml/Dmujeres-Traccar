#!/usr/bin/env bash
# Arranque idempotente de los servicios de infraestructura para desarrollo:
# PostgreSQL (+TimescaleDB), Redis y Mosquitto (MQTT).
# Pensado para la VM del Cloud Agent, donde no hay systemd activo.
set -euo pipefail

log() { echo "[start-services] $*"; }

# --- PostgreSQL ---
if sudo -u postgres pg_isready -q 2>/dev/null; then
  log "PostgreSQL ya esta en ejecucion"
else
  log "Iniciando PostgreSQL..."
  sudo pg_ctlcluster 16 main start || sudo pg_ctlcluster 16 main restart
  # Esperar readiness
  for i in $(seq 1 30); do
    if sudo -u postgres pg_isready -q 2>/dev/null; then break; fi
    sleep 1
  done
fi

# --- Base de datos y rol (idempotente) ---
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='traccar'" | grep -q 1; then
  log "Creando rol traccar"
  sudo -u postgres psql -c "CREATE USER traccar WITH PASSWORD 'traccar';"
fi
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='traccar'" | grep -q 1; then
  log "Creando base de datos traccar"
  sudo -u postgres psql -c "CREATE DATABASE traccar OWNER traccar;"
fi
sudo -u postgres psql -d traccar -c "CREATE EXTENSION IF NOT EXISTS timescaledb;" >/dev/null 2>&1 || \
  log "Aviso: no se pudo habilitar TimescaleDB (verifica shared_preload_libraries)"

# --- Redis ---
if redis-cli ping >/dev/null 2>&1; then
  log "Redis ya esta en ejecucion"
else
  log "Iniciando Redis..."
  sudo redis-server /etc/redis/redis.conf --daemonize yes
fi

# --- Mosquitto (MQTT) ---
if pgrep -x mosquitto >/dev/null 2>&1; then
  log "Mosquitto ya esta en ejecucion"
else
  log "Iniciando Mosquitto..."
  sudo mosquitto -c /etc/mosquitto/mosquitto.conf -d
fi

log "Servicios listos:"
log "  PostgreSQL -> localhost:5432 (db=traccar user=traccar)"
log "  Redis      -> localhost:6379"
log "  Mosquitto  -> localhost:1883"
