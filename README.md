# Dmujeres GPS — Fork de Traccar (dashboard + app)

Hard-fork de [Traccar](https://github.com/traccar/traccar) (server) y
[Traccar Web](https://github.com/traccar/traccar-web) (dashboard), más una **app Android
propia** para rastreo GPS continuo en teléfonos de empresa.

## Objetivos del fork
- **Motor de BD**: PostgreSQL 16 + **TimescaleDB** (hypertables automáticas para el histórico).
- **Mapas**: **Google Maps** (estilo ya incluido en el web de Traccar; requiere API key con billing).
- **GPS constante**: app Android con *foreground service*, envío continuo tipo "ubicación en vivo",
  transporte **MQTT** (QoS 1 + LWT) con cola offline, reconexión y watchdog.
- **Dashboard**: optimización de rendimiento para muchos dispositivos en tiempo real.

## Estructura del monorepo
```
.
├── server/        # Fork de traccar/traccar (Java 21, Netty, Jetty, Jersey, Liquibase)
├── web/           # Fork de traccar/traccar-web (React 19 + Vite + MapLibre/Google)
├── mobile/        # App Android nativa (Kotlin) — Fase 3
├── infra/         # Docker Compose (ref. produccion), scripts, config MQTT
└── .cursor/       # environment.json para Cloud Agents
```

## Servicios de infraestructura (desarrollo)
En la VM se ejecutan de forma nativa (sin Docker):
- **PostgreSQL 16 + TimescaleDB** → `localhost:5432` (db=`traccar`, user=`traccar`)
- **Redis** → `localhost:6379`
- **Mosquitto (MQTT)** → `localhost:1883`

Arranque idempotente: `bash infra/scripts/start-services.sh`

## Cómo correr en local
```bash
# 1. Instalar dependencias, servicios y compilar (idempotente)
bash infra/scripts/install.sh

# 2. Arrancar servicios de infraestructura
bash infra/scripts/start-services.sh

# 3. Backend (API + WebSocket + protocolos GPS)
cd server && java -jar target/tracker-server.jar dmujeres.xml
#   API:     http://localhost:8082
#   OsmAnd:  puerto 5055

# 4. Dashboard (dev server con proxy a la API)
cd web && npm run start   # http://localhost:3000
```

### Ingesta GPS por MQTT (protocolo `dmujeres`)
La app Android se conecta por **MQTT directamente al servidor** (puerto `8010`) y publica posiciones:
- **clientId MQTT** = `uniqueId` del dispositivo (identifica el equipo en el CONNECT).
- **Publicar** (QoS 1) un JSON de posición a cualquier tópico, p. ej. `dmujeres/position`:
```json
{"lat":-2.19,"lon":-79.88,"ts":1786453618,"speed":8.5,"course":45,"alt":10,"acc":4,"batt":76}
```
`speed` en m/s, `ts` epoch (s/ms) o ISO-8601. Traccar decodifica, almacena en TimescaleDB
y reenvía en tiempo real por WebSocket, reusando todo su pipeline (distancia, movimiento, etc.).

### Primer uso
El primer usuario creado es administrador:
```bash
curl -H "Content-Type: application/json" \
  -d '{"name":"Admin","email":"admin@dmujeres.local","password":"admin123"}' \
  http://localhost:8082/api/users
```

## Configuración
- Desarrollo: `server/dmujeres.xml` (apunta a PostgreSQL+TimescaleDB).
- Producción: usar variables de entorno / Secrets, nunca credenciales en el repo.

## Roadmap
- **Fase 0** — Fundaciones: monorepo, infra, entorno reproducible, fork corriendo e2e. ✅
- **Fase 1** — Backend base sobre PostgreSQL+TimescaleDB (API + WebSocket en tiempo real). ✅
- **Fase 2** — Ingesta GPS de alta frecuencia por **MQTT** (protocolo `dmujeres`). ✅
- **Fase 3** — App Android (foreground service, cola offline, watchdog, notificaciones de señal).
- **Fase 4** — Dashboard con Google Maps forzado + optimización de rendimiento.
- **Fase 5** — Hardening (secretos, sesiones, retención Timescale, observabilidad) y despliegue.
