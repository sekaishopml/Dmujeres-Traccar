# DECISIONS — DMujeres Traccar Platform

Registro de decisiones de arquitectura (ADR resumido; detalle en docs/adr/).

## D-001 (2026-08-12): Hard fork sobre Traccar 6.14.5, sin reescritura
**Estado**: ADOPTADA
**Contexto**: El prompt maestro exige aprovechar la infraestructura madura de Traccar.
**Decisión**: Clones completos de `traccar/traccar` y `traccar/traccar-web` @ v6.14.5.
Rama `dev` para el trabajo; `master` queda como launcher de referencia upstream.
**Alternativas descartadas**: reescritura desde cero (derrochadora), versión anterior del
proyecto previo (no fiable, no se continuará).

## D-002 (2026-08-12): Monolito extendido, no microservicios
**Estado**: ADOPTADA
**Decisión**: Extender el monolito Traccar. Separar únicamente con razón demostrable.
**Justificación**: Regla 20 del prompt (simple + robusto + escalable + mantenible).

## D-003 (2026-08-12): PostgreSQL + TimescaleDB como BD principal
**Estado**: ADOPTADA (validación de hipertablas en Fase 2)
**Decisión**: PostgreSQL para datos relacionales; posiciones GPS en hipertabla
TimescaleDB (compresión + retención + índices temporales). El compose oficial de
Traccar ya usa `timescale/timescaledb` → adoptar esa ruta en vez de personalizar.
**Justificación**: series temporales de alta frecuencia (GPS) es el caso de uso canónico
de TimescaleDB; no añadir infraestructura por apariencia.

## D-004 (2026-08-12): Realtime sobre WebSocket /api/socket de Traccar
**Estado**: ADOPTADA (para Fase 1)
**Decisión**: Reutilizar el canal realtime existente (Jetty WebSocket + ConnectionManager
+ AsyncSocket) para el dashboard. Evaluación de canales para GPS/MQTT en Fase 2.

## D-005 (2026-08-12): MQTT — pendiente de comparativa técnica en Fase 2
**Estado**: EN EVALUACIÓN
**Decisión**: No asumir que MQTT es la solución. En Fase 2 se comparará: MQTT QoS1,
WebSocket, HTTP batching, y lo que ya existe en Traccar (BaseMqttProtocolDecoder,
PositionForwarderMqtt). Evidencia de carga: 1/10/100/1000 dispositivos.

## D-006 (2026-08-12): Multi-tenancy nuevo en el fork
**Estado**: ADOPTADA (diseño detallado en Fase 1)
**Decisión**: Upstream NO tiene empresas (auditado, cero coincidencias de "company").
Se añadirá modelo Company + tabla `tc_companies` + aislamiento por permisos.
Riesgo: afecta esquema Liquibase, PermissionsService y recursos API → se aborda
como feature propia, no como parche.

## D-007 (2026-08-12): MapProvider = MapLibre GL (ya abstraído en traccar-web)
**Estado**: ADOPTADA
**Decisión**: traccar-web v6.14.5 ya usa MapLibre GL 5 con providers configurables
(`useMapStyles.js`). No se acopla a Google. Provider default: OpenFreeMap.
Google/Bing/MapTiler solo si hay API key legítima vía configuración.

## D-008 (2026-08-12): Entorno reproducible vía Docker Compose
**Estado**: ADOPTADA
**Decisión**: Dev y prod desde docker compose versionados + .env.example + scripts
(backup/restore/start/stop/migrate). VPS reemplazable: backup → VPS2 → restore →
up compose. Dominios estables para API/dashboard/MQTT (nunca IPs en la app).

## D-009 (2026-08-12): Java 21 para build, runtime del fork en JDK 21+
**Estado**: ADOPTADA
**Decisión**: Upstream compila con 21 y corre con 25 (jlink). El fork mantiene Java 21
en compilación; runtime será la imagen temurin que elijamos (21 o 25) — documentado
en Fase de despliegue. No alterar build upstream sin razón.

## D-010 (2026-08-12): Android app nueva (Kotlin), no fork del app existente
**Estado**: ADOPTADA (Fase 3)
**Decisión**: App propia: foreground service, Fused Location, MQTT QoS1, Room,
offline queue, watchdog, boot receiver. Verificar políticas Android 14+/15+ y Play Store.
## D-011 (2026-08-12): Consola web de debug y binds en dev
**Estado**: ADOPTADA
`web.console=true` SOLO en dev (bind 127.0.0.1). En producción (Fase 5): `web.console=false`
y bind por reverse proxy con TLS. `WEB_SECRET_TOKEN` debe inyectarse como env para
sesiones/tokens estables entre reinicios (evitado en dev por defecto).
