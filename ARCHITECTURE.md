# ARCHITECTURE — DMujeres Traccar Platform

## Visión de alto nivel (objetivo final)

```
                    PLATAFORMA
                        │
        ┌───────────────┼───────────────┐
        │               │               │
      SERVER        DASHBOARD         MOBILE
   (Java, fork    (React 19, fork   (Kotlin, app
    traccar)       traccar-web)       nueva)
        │               │
        ▼               ▼
       PostgreSQL / TimescaleDB / Redis / MQTT
```

## Decisiones de arquitectura adoptadas (resumen)

1. **Monolito Traccar extendido** (regla 20 del prompt maestro): sin microservicios.
   Solo separar si hay razón demostrable (escalabilidad/aislamiento).
2. **Base de datos**: PostgreSQL; TimescaleDB evaluado para posiciones (hipertabla).
   Upstream ya publica compose oficial con TimescaleDB → adoptamos ese camino.
3. **Mensajería**: MQTT evaluado en Fase 2 con comparativa técnica vs WebSocket y
   HTTP batching. Traccar ya trae `netty-codec-mqtt` (ingesta) y HiveMQ (forwarding).
4. **Realtime**: WebSocket `/api/socket` de Traccar (Jetty) — maduro, reutilizar.

## Stack auditado (real, no supuesto)

### Server — traccar v6.14.5
- Java 21 (compilación) / Gradle 9.5.1; runtime oficial Java 25 (jlink), aquí JDK 21
- Jetty 12.1 EE10, Jersey 4.0.2 (JAX-RS), Guice 7.0.0, Netty 4.2.14
- Liquibase 5.0.3 (changelogs XML en `schema/`, tablas `tc_*`)
- HikariCP 7.0.2; drivers: H2 (default), PostgreSQL 42.7.11, MySQL/MariaDB, MSSQL
- 267 protocolos GPS (750+ archivos en `org.traccar.protocol`)
- Config: archivo Properties + `Keys.java` (keys tipadas), soporta env vars
- API: `/api/*` (JAX-RS), WebSocket `/api/socket` (AsyncSocket + ConnectionManager)
- Auth: sesiones + Basic/Bearer + tokens firmados ECDSA + 2FA + LDAP + OIDC
- Multi-tenancy: **NO existe** en upstream (permisos planos users/groups, tablas `tc_user_*`)
- Docker oficial: compose con `timescale/timescaledb:latest-pg17`

### Dashboard — traccar-web v6.14.5
- React 19.2, MUI 9, Redux Toolkit 2.12, react-router 7, Vite 8 (+ PWA)
- Mapas: **MapLibre GL 5.24** (instancia singleton) con providers ya abstraídos
  (OpenFreeMap, OSM, CARTO, LocationIQ, Google, Bing, MapTiler...)
- WebSocket `/api/socket` con throttle middleware (3 msg/s → buffer adaptativo)
- Clustering nativo MapLibre, virtualización react-window, lazy routes, 61 idiomas
- Sin tests (a añadir en nuestro fork), eslint como único control

## Estructura de paquetes clave del server (a conservar)

| Paquete | Rol |
|---|---|
| `org.traccar` | Framework base (BaseProtocol, TrackerServer, ProcessingHandler, Main) |
| `org.traccar.protocol` | 267 protocolos GPS |
| `org.traccar.api` | REST (BaseResource, BaseObjectResource, resource/*) |
| `org.traccar.storage` | Storage + DatabaseStorage + QueryBuilder |
| `org.traccar.session` | ConnectionManager (realtime), DeviceSession, caches |
| `org.traccar.handler` | Pipeline de posiciones (Filter, Geofence, Motion, Database...) |
| `org.traccar.forward` | Forwarding (JSON, MQTT, AMQP, Kafka, Redis, Wialon) |
| `org.traccar.web` | Jetty WebServer, WebModule, servlets |

## Puntos de extensión para el hard fork

1. **Multi-tenancy**: tabla `tc_companies` + FKs + extender `PermissionsService`
   (upstream: permisos planos vía `tc_user_*`)
2. **MQTT propio**: BaseMqttProtocolDecoder existe (Pui/Iotm) — punto de partida
3. **MapProvider**: en dashboard ya hay `useMapStyles.js` (providers configurables)
4. **Config**: `Keys.java` para nuevas keys (empresas, mqtt interno, retención)
5. **Migraciones**: nuevos changelog Liquibase numerados (siguiente: 6.14.0)

## Infraestructura (dev actual)

- Docker Compose dev: TimescaleDB (pg17), Redis, broker MQTT (EMQX o Mosquitto)
- Server Java corre fuera de Docker en dev (build gradle + `conf/traccar.xml`)
- El server sirve el dashboard estático vía `web.path`
- Nombre de dominio: pendiente de asignación (decisión humana en Fase 2 de despliegue)