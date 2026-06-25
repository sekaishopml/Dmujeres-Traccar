#!/bin/bash
# Script de instalación inicial para Dmujeres-Traccar en Ubuntu
set -e

echo "=== Iniciando configuración del sistema para Dmujeres-Traccar ==="

# 1. Actualizar e instalar dependencias del sistema
echo "Instalando paquetes básicos, PostgreSQL, PostGIS, Redis y Nginx..."
apt-get update
apt-get install -y curl git make nginx redis-server postgresql postgresql-contrib postgresql-16-postgis-3

# 2. Instalar Go (si no está instalado)
if ! command -v go &> /dev/null; then
    echo "Instalando Go..."
    apt-get install -y golang-go
fi
go version

# 3. Instalar Node.js y npm (si no están instalados)
if ! command -v node &> /dev/null; then
    echo "Instalando Node.js v20..."
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
    apt-get install -y nodejs
fi
node -v
npm -v

# 4. Habilitar base de datos
echo "Creando base de datos traccar y habilitando extensiones..."
sudo -u postgres psql -c "CREATE DATABASE traccar;" || true
sudo -u postgres psql -d traccar -c "CREATE EXTENSION IF NOT EXISTS postgis;"
sudo -u postgres psql -d traccar -c "CREATE EXTENSION IF NOT EXISTS timescaledb;"

# Crear usuario traccar
sudo -u postgres psql -c "CREATE USER traccar WITH PASSWORD 'traccar_pass_556';" || true
sudo -u postgres psql -c "ALTER DATABASE traccar OWNER TO traccar;"
sudo -u postgres psql -d traccar -c "GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO traccar;"
sudo -u postgres psql -d traccar -c "GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO traccar;"

# 5. Crear directorio de caché para tiles de Google
echo "Configurando directorio de caché de Nginx..."
mkdir -p /var/cache/nginx/google_tiles
chown -R www-data:www-data /var/cache/nginx/google_tiles

# 6. Compilar
echo "Instalando dependencias y compilando frontend..."
cd /home/Dmujeres-Traccar/frontend
npm install
npm run build

echo "Compilando backend..."
cd /home/Dmujeres-Traccar/backend
go build -o dmujeres-backend cmd/server/main.go

# 7. Desplegar
echo "Realizando despliegue de archivos de configuración..."
cd /home/Dmujeres-Traccar
make deploy

echo "=== Configuración de Dmujeres-Traccar completada con éxito ==="
echo "La aplicación está activa en: http://$(curl -s https://api.ipify.org)"
