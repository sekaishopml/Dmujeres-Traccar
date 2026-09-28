#!/usr/bin/env bash
# Compila el servicio de ruteo contra el jar sombreado del matcher (GraphHopper
# 8 + Jackson ya vienen dentro): no hace falta Maven ni descargar dependencias.
# El jar vive fuera del repo, en /opt/route-service/matchservice.jar (copia del
# respaldo del matcher retirado); el grafo se lee de /opt/graphhopper.
#
# Uso: bash services/routing/build.sh
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DESTINO="${DESTINO:-/opt/route-service}"
JAR="${JAR:-$DESTINO/matchservice.jar}"

if [[ ! -f "$JAR" ]]; then
  echo "Falta el jar de GraphHopper en $JAR (copiar matchservice.jar del respaldo)." >&2
  exit 1
fi

mkdir -p "$DESTINO/clases"
javac -cp "$JAR" -d "$DESTINO/clases" "$AQUI/RouteService.java"
echo "Compilado. Arranque: java -Xmx768m -cp $JAR:$DESTINO/clases dmj.RouteService"
