# ADR-001 — Auditoría de referencia: Traccar v6.14.5 y traccar-web v6.14.5

**Estado**: Aceptado (documento de referencia permanente)  
**Fecha**: 2026-08-12

## Contexto

Fase 0 exige auditar el upstream antes de escribir cualquier código del fork.
Este ADR fija los datos verificados (no supuestos) para decisiones futuras.

## Hallazgos verificados (server)

| Aspecto | Valor verificado |
|---|---|
| Versión | 6.14.5 (commit 17e7a330) |
| Licencia | Apache 2.0 |
| Java | source/target 21 (build.gradle); runtime oficial jlink 25 |
| Gradle | 9.5.1 |
| Web | Jetty 12.1.8 EE10 (servidor + websocket) |
| API | Jersey 4.0.2 JAX-RS, montada en `/api/*`, escaneo de paquetes `org.traccar.api.resource` |
| DI | Guice 7.0.0 (guice-servlet + guice-bridge) |
| Red | Netty 4.2.14 (incluye netty-codec-mqtt) |
| BD | HikariCP 7.0.2 + Liquibase 5.0.3 (29 changelogs XML en schema/, tablas `tc_*`) |
| Drivers | H2 default, PostgreSQL 42.7.11, MySQL 9.7, MariaDB, MSSQL, junixsocket |
| Protocolos | 267 clases `extends BaseProtocol` (675 archivos) |
| Realtime | WebSocket `/api/socket` (AsyncSocket) — push de devices/positions/events/logs |
| Auth | Sesiones Jakarta + Basic/Bearer + tokens firmados ECDSA (TokenManager) + 2FA + LDAP + OIDC |
| MQTT | ingesta: `BaseMqttProtocolDecoder` (Pui/Iotm); forwarding: HiveMQ client QoS1 |
| Multi-tenant | **NO existe** — permisos planos via `tc_user_*`, `PermissionsService` |
| Tests | JUnit 5 + Mockito, 426 archivos, base `BaseTest`/`ProtocolTest`, checkstyle |
| Empaquetado | jar + `target/lib` con Class-Path (sin shadowJar) |
| Config | archivo Properties + `Keys.java` (200+ keys tipadas) + env vars |
| Docker | 3 Dockerfiles (alpine/debian/ubuntu), compose oficial MySQL y TimescaleDB (pg17) |

## Hallazgos verificados (dashboard)

| Aspecto | Valor verificado |
|---|---|
| Versión | 6.14.5 (commit 695a473) |
| Licencia | Apache 2.0 |
| Stack | React 19.2, MUI 9.1, RTK 2.12, react-router 7, Vite 8, PWA |
| Mapas | MapLibre GL 5.24 (singleton en map/core/MapView.jsx), providers en `useMapStyles.js` (OpenFreeMap default entre los activos, OSM, CARTO, LocationIQ, Google, Bing, MapTiler, TomTom, HERE, Yandex, AutoNavi, OS UK, Mapbox, custom) |
| Realtime | WebSocket `/api/socket` + `throttleMiddleware` (buffer 3 msg/s, flush adaptativo 1.5–30s) |
| Rendimiento | clustering MapLibre, react-window en DeviceList, lazy de todas las rutas, i18n lazy (61 idiomas) |
| API client | fetch nativo + fetchOrThrow, cookies de sesión |
| Tests | **Ninguno** (sin framework; solo eslint) |
| i18n | 61 idiomas, en.json 659 claves |
| Build | outDir `build/`, placeholders `${title}` en index.html rellenados por el server |

## Implicaciones para el fork

1. Multi-tenancy (empresas) es trabajo propio: tabla `tc_companies` + `PermissionsService`.
2. El canal GPS/MQTT puede apoyarse en `BaseMqttProtocolDecoder` y `PositionForwarderMqtt`.
3. MapProvider: MapLibre ya abstrae proveedores; el default sin key = OpenFreeMap.
4. El dashboard se sirve desde el server (`web.path`); el build debe apuntar a `../dashboard/build`.
5. No hay tests frontend: nuestro fork deberá introducirlos (vitest/playwright) en Fase 4.
6. No hay shadowJar: si queremos jar único para despliegue, añadir shadow — decisión en Fase 1.

## Referencias

- Repos locales: `server/` y `dashboard/` (rama `dev` = trabajo, `master` = upstream)
- Compose oficial TimescaleDB: `server/docker/compose/traccar-timescaledb.yaml`
