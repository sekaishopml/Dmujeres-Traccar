# dmj-match: servicio de map-matching (GraphHopper 8.0, Ecuador OSM)

Mini-servicio Java que casa tracks GPS con la red vial. Lo consume el
endpoint del servidor `GET/POST /api/positions/match`.

## Construir e instalar
```
cd infrastructure/matchservice
/opt/maven/bin/mvn -q package   # genera target/matchservice.jar
sudo cp target/matchservice.jar /opt/matchservice/target/
sudo cp ../systemd/dmj-match.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now dmj-match
```

## Datos
- Grafo: `/opt/graphhopper/ecuador-latest.osm.pbf` (Geofabrik) + caché
  `/opt/graphhopper/graph-cache-8` (se importa solo al primer arranque).
- Escucha solo loopback: 127.0.0.1:8991 (`/match`, `/health`).
