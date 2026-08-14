# DMujeres Traccar Platform

Plataforma privada de tracking GPS (una sola empresa) basada en **Traccar v6.14.5**
(hard fork), con canal móvil confiable, dashboard optimizado y app Android propia.

> Entorno privado y confidencial — sin multi-tenancy (decisión D-012).

## Componentes

| Carpeta | Qué es |
|---|---|
| `server/` | Fork del server Traccar (Java 21, Jetty 12, PostgreSQL/TimescaleDB) con canal MQTT QoS1 + ACK + deduplicación atómica y HTTP fallback |
| `dashboard/` | Fork de traccar-web (React 19, MapLibre) optimizado sin rediseño; Google Maps por defecto |
| `mobile/` | App Android (Kotlin): foreground service, GPS, cola offline, watchdog |
| `infrastructure/` | Docker Compose dev/prod, scripts (backup/restore/compresión), tests de integración y carga |
| `docs/` | Arquitectura, ADRs, decisiones, seguridad, despliegue y mediciones |

## Requisitos

- Docker + Docker Compose (TimescaleDB, Redis, EMQX)
- JDK 21 (server) · Node 20 (dashboard) · Android SDK 34 (app)

## Puesta en marcha rápida

```bash
cp .env.example .env                 # y ajustar secretos
./infrastructure/scripts/dev.sh up   # TimescaleDB + Redis + EMQX
# Server:
cd server && ./gradlew build
cd .. && ./infrastructure/scripts/run-server-dev.sh start
# Dashboard (ya servido por el server en :8082)
```

Documentación completa en `docs/` (empezar por `PROJECT_CONTEXT.md` y `ROADMAP.md`).

## Releases

La app Android se publica como release descargable: ver tags `v1.x.x` (APK en los assets).
