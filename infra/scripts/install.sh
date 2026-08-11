#!/usr/bin/env bash
# Instalacion idempotente del entorno de desarrollo (Cloud Agent / VM Ubuntu 24.04).
# Instala PostgreSQL + TimescaleDB, Redis y Mosquitto, y compila server y web.
set -euo pipefail

log() { echo "[install] $*"; }
export DEBIAN_FRONTEND=noninteractive

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# --- Paquetes base ---
if ! command -v psql >/dev/null 2>&1 || ! command -v redis-server >/dev/null 2>&1 || ! command -v mosquitto >/dev/null 2>&1; then
  log "Instalando PostgreSQL, Redis y Mosquitto..."
  sudo apt-get update -qq
  sudo apt-get install -y -qq postgresql postgresql-contrib redis-server mosquitto mosquitto-clients
fi

# --- TimescaleDB ---
if ! dpkg -l | grep -q timescaledb-2-postgresql-16; then
  log "Instalando TimescaleDB..."
  curl -fsSL https://packagecloud.io/timescale/timescaledb/gpgkey | sudo gpg --dearmor -o /etc/apt/trusted.gpg.d/timescaledb.gpg
  echo "deb https://packagecloud.io/timescale/timescaledb/ubuntu/ noble main" | sudo tee /etc/apt/sources.list.d/timescaledb.list >/dev/null
  sudo apt-get update -qq
  sudo apt-get install -y -qq timescaledb-2-postgresql-16
  sudo timescaledb-tune --quiet --yes
fi

# --- Arrancar Postgres y preload de TimescaleDB ---
sudo pg_ctlcluster 16 main start 2>/dev/null || sudo pg_ctlcluster 16 main restart
for i in $(seq 1 30); do sudo -u postgres pg_isready -q 2>/dev/null && break; sleep 1; done

if ! sudo -u postgres psql -tAc "SHOW shared_preload_libraries" | grep -q timescaledb; then
  log "Habilitando shared_preload_libraries=timescaledb"
  sudo -u postgres psql -c "ALTER SYSTEM SET shared_preload_libraries = 'timescaledb';"
  sudo pg_ctlcluster 16 main restart
  for i in $(seq 1 30); do sudo -u postgres pg_isready -q 2>/dev/null && break; sleep 1; done
fi

# --- Rol, BD y extension ---
sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='traccar'" | grep -q 1 || \
  sudo -u postgres psql -c "CREATE USER traccar WITH PASSWORD 'traccar';"
sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='traccar'" | grep -q 1 || \
  sudo -u postgres psql -c "CREATE DATABASE traccar OWNER traccar;"
sudo -u postgres psql -d traccar -c "CREATE EXTENSION IF NOT EXISTS timescaledb;" >/dev/null 2>&1 || true

# --- Compilar server (Java 21 / Gradle wrapper) ---
log "Compilando server (gradle assemble)..."
(cd "$ROOT_DIR/server" && chmod +x gradlew && ./gradlew assemble --no-daemon)

# --- Dependencias y build del web ---
log "Instalando dependencias del web (npm ci)..."
(cd "$ROOT_DIR/web" && npm ci && npm run build)

log "Instalacion completada."
