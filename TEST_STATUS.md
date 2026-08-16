# TEST STATUS — DMujeres Traccar Platform

> Estado de pruebas con evidencia. Nunca marcar "verificado" sin evidencia.
> Última actualización: 2026-08-12 (Fase 2.1).

## FASE 0

| ID | Prueba | Estado | Evidencia |
|---|---|---|---|
| PT-001 | JDK 21 instalado y funcional | ✔ PASÓ | `java -version` → OpenJDK 21.0.11 |
| PT-002 | Clones upstream íntegros (server+web) | ✔ PASÓ | `git log`: v6.14.5 (5c5e710 / 695a473) |
| PT-003 | Build server Gradle (tests + checkstyle + jar) | ✔ PASÓ | BUILD SUCCESSFUL; **597 tests, 0 fallos, 0 errores** (27 skipped, 424 suites); target/tracker-server.jar |
| PT-004 | Arranque server + `/api/health` | ✔ PASÓ | HTTP 200 "OK"; Jetty 12.1.8 en 0.0.0.0:8082 |
| PT-005 | Migraciones Liquibase sobre PostgreSQL/TimescaleDB | ✔ PASÓ | 33 changesets aplicados en BD limpia; log "Update command completed successfully" |
| PT-006 | Build dashboard Vite | ✔ PASÓ | vite build OK; 238 entradas PWA precacheadas; build/index.html |
| PT-007 | Server sirve dashboard (E2E minimal) | ✔ PASÓ | `GET /` → HTTP 200 (web.path → dashboard/build) |
| PT-008 | Compose infraestructura levanta (PG/Redis/MQTT) | ✔ PASÓ | dmj-db/dmj-redis/dmj-mqtt `healthy`; EMQX 5.8.5 |
| PT-009 | Recuperación en entorno limpio (backup/restore) | ✔ PASÓ | `down -v` (volúmenes destruidos) → `up` → `restore.sh` → 1 usuario, 1 dispositivo, 1 posición, 33 changelogs → server: health 200, login 200, posición visible |
| PT-010 | Pipeline GPS E2E (registro→login→device→posición) | ✔ PASÓ | POST /api/users (admin auto), /api/session 200, /api/devices 200, OsmAnd `5055/?id=demo-001` → tc_positions fila OK + REST /api/positions 200 |
| PT-011 | TimescaleDB hipertablas activas | ✔ PASÓ | `tc_positions`, `tc_events`, `tc_actions` hypertables (timescaledb 2.29.1) |
| PT-012 | Backup verificado post-generación | ✔ PASÓ | `pg_restore --list` OK en backup.sh; artefactos: dump + env(600) + conf(600) + VERSIONS.txt |

## Hallazgos y correcciones registradas

1. **Bug upstream menor** (documentado): `POST /api/users` sin sesión con `administrator:true`
   → NPE en `PermissionsService.checkAdmin`. Flujo correcto: omitir el flag (el primer
   usuario recibe admin automáticamente, UserResource.add L102-127).
2. **Bug propio corregido**: `dev.sh down` no reenviaba argumentos (`-v` se perdía) → los
   volúmenes sobrevivían y un password rotado no tomaba efecto. Corregido con `shift`.
3. **Entorno**: puerto 5432 ocupado por otro proyecto (sekai-dev-db) → dev usa 5433 externo.

## FASE 1 — Server baseline (upstream sin modificar)

Suite ejecutable versionada en `infrastructure/tests/` (README con instrucciones).
Ejecutada: 2026-08-12, server v6.14.5 + PostgreSQL/TimescaleDB.

| ID | Prueba | Estado | Evidencia |
|---|---|---|---|
| PT-101 | WebSocket realtime: conexión con token, push de posiciones en tiempo real, keepalive 55s | ✔ PASÓ (10/10) | `node ws-test.js`; push `{"positions":[...]}` recibido <4s tras envío OsmAnd; `{}` keepalive |
| PT-102 | Autenticación: cookie, token firmado ECDSA (base64url), Basic | ✔ PASÓ | AUTH-1..4: 200 en login/emisión/validación/Basic |
| PT-103 | CRUD API + aislamiento de permisos multi-usuario | ✔ PASÓ (10/10) | `node crud-test.js`; operador no-admin ve solo su dispositivo (1/4) |
| PT-104a | Eventos de estado por WS (deviceOnline con mensaje) | ✔ PASÓ | `node event-test.js`; evento `{"events":[{"type":"deviceOnline","message":...}]}` en tiempo real |
| PT-104b | Persistencia tras reinicio del server | ✔ PASÓ | 2 usuarios, 1 dispositivo, 11 posiciones, 2 notificaciones, eventos; login OK; 10 posiciones históricas vía API |

**Nota**: el push de eventos requiere transición de estado (unknown→online) y notificación
tipo web `always=true` vinculada al usuario (ver README de la suite).

## FASE 2.1 — contrato, deduplicación y baseline MQTT

| ID | Prueba | Estado | Evidencia |
|---|---|---|---|
| PT-201 | Migración `tc_mobile_messages` aplicada en TimescaleDB | ✔ PASÓ | Liquibase exitoso; uniques `(deviceid, sequence)`/`messageid`; FK device; FK a `tc_positions` omitido por incompatibilidad hypertable y documentado |
| PT-202 | Validación envelope v1 | ✔ PASÓ | `MobileEnvelopeValidatorTest`: schema, topic/device, timestamps, coordenadas y límites |
| PT-203 | Baseline MQTT QoS1 broker-only | ✔ PASÓ | `infrastructure/load-tests`: 1/10/100/1000 dispositivos, 0 pérdidas PUBACK; p99 2.1/7.3/10.8/53.6 ms en este VPS |
| PT-204 | MQTT experimental end-to-end accepted/duplicate | ✔ PASÓ (experimental) | `mqtt:e2e`: `accepted` con `positionid=18`; redelivery byte-identical `duplicate`; 1 posición física |
| PT-205 | Persistencia atómica posición+dedupe (JDBC transaccional) | ✔ PASÓ | Mismo flujo con `MobileAtomicPersistence`: `accepted` (positionid=19) y redelivery `duplicate`; una sola posición física; lease limpio tras commit |
| PT-206 | Lease/recovery de mensajes `processing` | ✔ PASÓ | Reserva manual con lease vencido (attempts=5) → reclamada, procesada y `accepted` (attempts=6, positionid=20) |
| PT-207 | ACL/authN EMQX 5.8 (override dev) | ✔ PASÓ (aislado) | 8/8 escenarios: consumer suscribe `+/telemetry`+publica `+/ack`; device publica/suscribe su propio topic; wildcards y topics ajenos denegados |
| PT-208 | Compose dev/dev+auth/prod validan | ✔ PASÓ | `docker compose config` OK para las 3 variantes |
| PT-209 | HTTP fallback batch (accepted/duplicate) | ✔ PASÓ | `http:e2e`: primer envío `accepted`, segundo `duplicate` |
| PT-210 | Idempotencia cruzada MQTT↔HTTP | ✔ PASÓ | Mensaje aceptado por MQTT → HTTP devuelve `duplicate` (hash canónico, sin doble posición) |
| PT-211 | Carga end-to-end MQTT (ACK de aplicación) | ✔ PASÓ | `mqtt:e2e:load`: 20 dispositivos × 10 mensajes = 200/200 `accepted`, 0 pérdidas, 0 duplicados, ~124 msg/s |

**Alcance PT-203**: mide sólo PUBACK del broker EMQX. No es todavía ACK de negocio,
persistencia en TimescaleDB ni deduplicación end-to-end. Esos resultados quedan
pendientes del consumidor MQTT.

**Alcance PT-204/205/206**: flujo local con broker dev. La atomicidad queda garantizada
por transacción JDBC (posición + `accepted` en un commit); un crash antes del commit revierte
todo y un crash después deja `accepted` para redelivery `duplicate`. La auth+ACL está
integrada de forma permanente en el compose dev principal (`docker-compose.yml`); los
load-tests conectan con `MQTT_USER`/`MQTT_PASSWORD` (ver `infrastructure/load-tests/README.md`).

## Fases 2.2-5
- Pendiente consumidor MQTT, pipeline común, ACK post-commit, HTTP fallback y carga end-to-end.

## FASE 3 — App Android (compilación y revisión)

| ID | Prueba | Estado | Evidencia |
|---|---|---|---|
| PT-301 | Compilación APK debug (Kotlin + Room + MQTT) | ✔ PASÓ | `./gradlew assembleDebug` BUILD SUCCESSFUL; `app-debug.apk` 6.6 MB; package `com.dmujeres.traccar`, targetSdk 34 |
| PT-302 | Revisión técnica/seguridad app | ✔ PASÓ (correcciones aplicadas) | 2 críticos + 7 medios corregidos: re-suscripción ACK, backoff/techo reintentos, @Volatile, guard trackingEnabled, try/catch startForeground, scope recreado |
| PT-303 | Permisos/políticas manifest | ✔ PASÓ | FGS tipo location + ServiceCompat; sin ACCESS_BACKGROUND_LOCATION (correcto); boot receiver con opt-in |

**Limitación**: la prueba real de GPS/MQTT necesita un teléfono físico; no es emulable.

## FASE 4 — Dashboard (optimización, sin regresión)

| ID | Prueba | Estado | Evidencia |
|---|---|---|---|
| PT-401 | Chunk inicial reducido | ✔ PASÓ | `index` 1.6 MB → 124 KB (manualChunks) |
| PT-402 | Compresión gzip del server | ✔ PASÓ | JS+CSS 6631 KB → 2028 KB (69%); `Content-Encoding: gzip` |
| PT-403 | Sin regresión API/WS tras gzip | ✔ PASÓ | ws-test 10/10 + crud-test 10/10 |
| PT-404 | Build dashboard con cambios MapProvider | ✔ PASÓ | `npm run build` OK; mapa default OpenFreeMap; fallback seguro |

## Optimización de datos — compresión TimescaleDB (D-014)

| ID | Prueba | Estado | Evidencia |
|---|---|---|---|
| PT-501 | Compresión habilitada y aplicada | ✔ PASÓ | `tc_positions` compression_enabled=t; 24/38 chunks comprimidos en ~6 s |
| PT-502 | Ratio de compresión real | ✔ PASÓ | 5.19x (80.7%) compresión pura; 2.34x con ventana reciente |
| PT-503 | Rendimiento de consulta sobre datos comprimidos | ✔ PASÓ | Ruta 1 día/dispositivo (8.7k filas): execution 2.3–4.4 ms |
| PT-504 | Proyección 5 años / 100GB | ✔ PASÓ | 10 disp @10s=7.5GB; 50=37.3GB; 100=74.6GB; ~134 disp @10s caben |

Detalle en `infrastructure/database/measurement-results.md`.

## Flujo colaborador — usuario/contraseña (1.0.4)

| ID | Prueba | Estado | Evidencia |
|---|---|---|---|
| PT-601 | EMQX auth+ACL permanentes (anónimo rechazado, ACL por usuario) | ✔ PASÓ | mqtt-auth-evidence.md: pub/sus topics ajenos DENEGADOS |
| PT-602 | E2E colaborador (create → 3 posiciones accepted → dedupe → ACL) | ✔ PASÓ | collaborator-e2e-evidence.md; duplicate sin fila extra |
| PT-603 | Dispositivo ONLINE en panel tras posición móvil | ✔ PASÓ | maria-001 status=online, lastUpdate actualizado (fix H-1) |

## Provisión desde dashboard (1.0.5)

| ID | Prueba | Estado | Evidencia |
|---|---|---|---|
| PT-604 | `/api/mobile/provision` admin crea dispositivo + usuario MQTT | ✔ PASÓ | `panel-maria` creado (deviceId 35), preferencias guardadas, password no en attributes |
| PT-605 | App simulada con usuario/contraseña panel-maria | ✔ PASÓ | MQTT auth, ACL propia, ACK `accepted`, posición persistida |
| PT-606 | Dashboard muestra ONLINE tras posición móvil | ✔ PASÓ | `panel-maria`: status=online, lastUpdate actualizado |

## Corrección panel/app 1.0.6

| ID | Prueba | Estado | Evidencia |
|---|---|---|---|
| PT-607 | App muestra estado MQTT real | ✔ PASÓ compilación | `MqttStatus`: Conectando/Conectado/Sin conexión; APK 1.0.6 compilado |
| PT-608 | Bloque Obligatorio retirado del formulario | ✔ PASÓ | Build dashboard OK; alta concentrada en Acceso del colaborador |

| PT-609 | Reloj adelantado (teléfono) aceptado | ✔ PASÓ | sentAt +10 min → ACK accepted |
| PT-610 | messageId único por posición | ✔ PASÓ | patrón 01JAND+time+seq, 26 chars |

| PT-611 | App valida credenciales con feedback real | ✔ PASÓ compilación | Diálogos Conectado/incorrectos/denegado/servidor |
| PT-612 | Provisión idempotente (actualiza password si existe) | ✔ PASÓ | POST repetido → HTTP 200 |
| PT-613 | E2E santiago desde IP pública | ✔ PASÓ | CONECTA → ACK accepted → ONLINE |

## Diagnóstico Santiago 2026-08-15

| ID | Prueba | Resultado |
|---|---|---|
| PT-701 | BD: gaps de Santiago | 4 gaps >10 min; máximo 12h55; serverTime-fixTime alto, DB insert 0.006s |
| PT-702 | Server/broker | MQTT y consumer activos; posiciones accepted; transacción/leases sanos |
| PT-703 | Corrección app dispatcher | Build release OK; dispatcher persistente, ACK/lastAck y wake tras insert |
| PT-704 | Corrección replay dashboard | Build/lint OK; gaps >5 min ya no se dibujan como línea continua |

Causa: ráfagas atrasadas antes de persistencia, consistente con app/OS dispatcher/reposo;
no lentitud de PostgreSQL ni pérdida del server. La app 1.0.13 requiere prueba física.

| PT-705 | Heartbeat presence mantiene ONLINE sin posición | ✔ PASÓ | ACK accepted; status online; lastUpdate actualizado; 0 filas tc_positions nuevas |

## Compatibilidad fabricantes / telemetría / fallback (1.0.15)

| ID | Prueba | Estado | Evidencia |
|---|---|---|---|
| PT-706 | Telemetría presence → atributos del dispositivo | ✔ PASÓ | mobile.pending=7, battery=83, network=wifi, vendor=Xiaomi, model, appVersion, gps |
| PT-707 | Fallback HTTP batch | ✔ PASÓ | POST /api/mobile/v1/positions con X-Api-Key → accepted |
| PT-708 | targetSdk 35 build | ✔ PASÓ | assembleRelease OK con compileSdk/targetSdk 35 |
| PT-709 | Dashboard indicador sin señal/pendientes/batería | ✔ PASÓ | DeviceRow actualizado; lint+build OK |

| PT-710 | Replay: decimación por zoom y slider sin marks | ✔ PASÓ | benchmark 3000 pts: z10→24 pts, z14→109, z16→3000 (cap flechas 1500); cálculo <7 ms; lint+build OK |

| PT-711 | Anti-bucle MQTT (clientId único + guardas) | ✔ PASÓ | 2 clientes mismo usuario coexisten sin expulsión; build OK |
| PT-712 | Alertas conexión con límite 5 min | ✔ PASÓ compilación | onMqttStateChanged con throttle |

| PT-713 | /latest.json servido y descarga del release funciona | ✔ PASÓ | GET 200 con version/url; asset descarga 200 |
| PT-714 | App 1.0.17 (diagnóstico/resumen/update) | ✔ PASÓ | assembleRelease OK; versionCode 18 |

| PT-715 | Batería en cada posición | ✔ PASÓ compilación | buildPosition incluye battery/network; applyTelemetry los persiste |
| PT-716 | Auto-inicio robusto (permiso bg + guardas) | ✔ PASÓ compilación | ACCESS_BACKGROUND_LOCATION en APK; BootReceiver try/catch + USER_UNLOCKED |
| PT-717 | Datos limpios sin saltos de prueba | ✔ PASÓ | 2 posiciones/4 mensajes de prueba eliminados; 0 saltos >100 m de prueba |

| PT-718 | Fin de jornada inmediato | ✔ PASÓ | presence journeyEnded → estado offline al instante |
| PT-719 | Inicio de jornada inmediato | ✔ PASÓ | presence journeyStarted → online al instante |
| PT-720 | Batería + historial en panel | ✔ PASÓ | atributo mobile.battery/mobile.batteryHistory; sparkline render |
