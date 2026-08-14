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

## D-012 (2026-08-12): SIN multi-tenancy — plataforma privada de una sola empresa
**Estado**: ADOPTADA (supersede la idea de empresas de D-006)
**Decisión**: El despliegue es un **entorno privado y confidencial para una sola empresa**.
No se implementa el modelo de empresas/tenants. Se mantiene el modelo plano de Traccar
(usuarios + grupos + dispositivos con permisos).
**Consecuencias**:
- NO se crea `tc_companies` ni filtros por tenant ni tests de aislamiento entre empresas.
- La seguridad se centra en proteger el acceso al entorno: TLS, autenticación fuerte,
  firewall/VPN, mínima exposición pública, backups y auditoría (Fase 5).
- El dashboard conserva su diseño original; no habrá selector de empresas.
- El administrador gestiona usuarios/dispositivos/grupos del modelo plano de Traccar.

## D-013 (2026-08-12): Permisos de usuarios en entorno de una sola empresa
**Estado**: ADOPTADA
Se mantiene el RBAC plano de Traccar (administrador → usuarios con limites de dispositivos
y grupos). Suficiente para una empresa; sin tenant layer.

## D-015 (2026-08-13): Google Maps sin API key — tiles clásicos mt0-3.google.com
**Estado**: ADOPTADA (decisión explícita del cliente)
**Decisión**: Google Maps (Carreteras/Satélite/Híbrido) se ofrece SIEMPRE en el dashboard:
con API key legítima si está configurada, y sin key usando los tiles `mt0-3.google.com/vt/...`
(comportamiento del Traccar original que el cliente usa desde hace años). Google Carreteras
es el mapa por defecto.
**Riesgo documentado**: esos endpoints no son un servicio público autorizado (no es el
acceso oficial por API) y Google podría bloquearlos en el futuro; en ese caso el mapa se
vería en gris hasta configurar una API key legítima. Fallback técnico: OpenFreeMap/OSM
siguen disponibles en el selector.

## D-014 (2026-08-13): Conservar TODO el histórico GPS; compresión TimescaleDB
**Estado**: ADOPTADA
**Decisión**: No hay borrado automático (retención desactivada). El histórico se conserva
mes a mes y año a año. Para caber en ~100 GB / 5 años se usa compresión TimescaleDB sobre
`tc_positions` (segment_by=deviceid, order_by=fixtime DESC, comprimir chunks > 1 día).
**Evidencia**: ratio de compresión 5.19x (80.7%), consultas 2-4 ms sobre datos comprimidos;
proyección: 10 dispositivos @10s ≈ 7.5 GB/5 años; ~134 dispositivos @10s caben en 100 GB
(ver `infrastructure/database/measurement-results.md`).
**Consecuencia**: si la flota supera ~130 dispositivos @10s, subir el intervalo de la app
a 30 s (≈12 GB/5 años para 50 dispositivos).
