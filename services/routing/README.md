# services/routing — ruteo estimado por calles

Servicio local que reconstruye, sobre la red de calles, los tramos que en el
Replay quedan a saltos (huecos de señal o fixes muy separados). La API lo
consulta por cada tramo y la web lo dibuja como una parte más del recorrido.

- Grafo: `/opt/graphhopper/graph-cache-8` (importado del PBF de Ecuador con el
  perfil `car` del matcher retirado). No se reimporta: se carga en modo MMAP.
- Escucha solo en `127.0.0.1:8992`; lo consume `services/api/src/ruteo.js`.
- Unidad: `dmj-routing.service` (ver `infrastructure/production/systemd/`).

## Compilar y arrancar

El jar sombreado del matcher (GraphHopper 8 + Jackson) vive en
`/opt/route-service/matchservice.jar`; no hace falta Maven.

```bash
bash services/routing/build.sh          # compila RouteService.java
sudo systemctl restart dmj-routing     # aplica el cambio
curl http://127.0.0.1:8992/health      # -> ok
```

## API

```
POST /route {"from":[lon,lat],"to":[lon,lat]}
  -> 200 {"points":[[lon,lat],...],"distance":m,"time":ms}
  -> 400 datos inválidos | 422 sin ruta | 500 error interno
```

## Umbrales (en `services/api/src/ruteo.js`)

- Se estima un tramo solo si los fixes están a **≥ 45 s** y **≥ 150 m**
  (por debajo es parado con jitter: el ruteo devolvería vueltas absurdas).
- Tope de **4 h** por tramo y de **200 tramos por carga** del Replay, con un
  presupuesto de 3 s; lo que no entra queda dibujado como siempre.
- Caché en memoria por par de coordenadas (aciertos 7 días, fallos 1 min).
