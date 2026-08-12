# Mejoras implementadas frente a Traccar original

> Base: `traccar/traccar` y `traccar/traccar-web` v6.14.5 (Apache 2.0).
> Período: Fases 0–2.2 (2026-08-12). Todo validado con evidencia en TEST_STATUS.md.

## 1. Canal GPS móvil (no existe en upstream)

| Capacidad | Traccar v6.14.5 | DMujeres |
|---|---|---|
| Envelope propio con `messageId`, `deviceId`, `sequence`, `sentAt`, `observedAt` | No | Sí (`docs/mqtt/protocol-v1.md`) |
| ACK de aplicación posterior a la persistencia | No (`BaseMqttProtocolDecoder` hace PUBACK inmediato sin esperar BD) | Sí (`MobileAtomicPersistence` publica `accepted/duplicate/rejected/invalid/expired`) |
| Deduplicación persistente | No (solo `filter.duplicate` opcional contra última posición en cache) | Sí: tabla `tc_mobile_messages` con uniques `(deviceid, sequence)` y `messageid` |
| Hash de deduplicación canónico e independiente del transporte | No | Sí (`canonicalHash`: mismo mensaje por MQTT o HTTP produce `duplicate`, sin doble posición) |
| Transacción atómica posición + dedupe | No (Storage sin transacciones) | Sí (`MobileAtomicPersistence`, una conexión/commit) |
| Lease y recuperación de mensajes `processing` | No (quedaban bloqueados) | Sí (`leaseuntil`, `leasetoken`, `attempts` + reclamación) |
| Serialización por dispositivo y cola acotada | No | Sí (`deviceTails` + `Semaphore`) |
| Redelivery MQTT correcta | PUBACK prematuro | El PUBACK se confirma sólo tras ACK de aplicación exitoso; si no, redelivery |

## 2. HTTP fallback batch (nuevo)

- `POST /api/mobile/v1/positions` (X-Api-Key) comparte el mismo pipeline de ingesta
  (`MobileIngestionService`), así MQTT y HTTP son intercambiables sin duplicar posiciones.
- Devuelve estados por mensaje y 503 con `error` para reintento.

## 3. Infraestructura reproducible y operación

- Monorepo con `server/` + `dashboard/` como submódulos y documentación de continuidad
  (PROJECT_CONTEXT, ROADMAP, DECISIONS, ADR, TEST_STATUS, CHANGELOG).
- Docker Compose dev/prod, `.env.example`, backups con verificación y runbook de
  recuperación (probado con `down -v` → restore en entorno limpio, PT-009).
- Secretos fuera de Git (config por env, `conf/` ignorado, password rotado).
- Puertos de infraestructura en localhost en dev; tokens de firma estables entre reinicios.

## 4. Correcciones y hallazgos reales documentados

- **EMQX 5.8**: `EMQX_ALLOW_ANONYMOUS` es no-op (controlado por presencia de authN y
  `authorization.no_match`); no existe `backend=file` (es `built_in_database` +
  `bootstrap_file`); no hay `emqx ctl` para usuarios MQTT (API HTTP). El compose de
  upstream usaba variables inválidas.
- **TimescaleDB**: no se puede crear FK hacia `tc_positions` por ser hypertable (PK
  incompatible); `positionid` se valida a nivel de aplicación.
- **Bug upstream**: `POST /api/users` sin sesión con `administrator:true` → NPE en
  `PermissionsService`.
- **Compatibilidad de columnas**: las columnas deben ser minúsculas (`lastupdate`,
  `leaseuntil`) para el mapeo reflexivo de `QueryBuilder`.

## 5. Calidad y pruebas (lo que upstream no tenía)

- Suite de integración versionada (`infrastructure/tests/`): WS realtime, auth, CRUD,
  aislamiento de permisos (upstream traccar-web no tiene tests).
- Benchmark MQTT broker-only (`infrastructure/load-tests/`): 1/10/100/1000 dispositivos.
- Carga end-to-end con ACK de aplicación: 200/200 `accepted`, 0 pérdidas, 0 duplicados
  (~124 msg/s en el VPS dev).
- Build Gradle: 610 tests, 0 fallos; checkstyle activo.
- ACL/authN EMQX validados en 8/8 escenarios (isolados).

## 6. Lo que se mantiene del core (no reinventado)

- 267 protocolos GPS, pipeline `ProcessingHandler → DatabaseHandler → PostProcessHandler`,
  WebSocket `/api/socket` para dashboard, autenticación (sesión/token/Basic/2FA/LDAP/OIDC),
  almacenamiento HikariCP+Liquibase, reports, geocercas, eventos y notificaciones.

## Pendiente (fases siguientes)

- Multi-tenancy (empresas) — no existe en upstream; se diseña como feature propia.
- TLS real de producción con certificados y despliegue externo.
- Pruebas de caída de broker/BD a gran escala y reconexión masiva.
- App Android (Fase 3) y dashboard empresarial (Fase 4).
