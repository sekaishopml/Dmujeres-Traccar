# DATA/OUTBOX AUDIT — R3 (Agente C)

Repo: `DMujeres-Tracking` · Fecha: 2026-09-17 · Modo: solo lectura + validación READ-ONLY contra `dmj-db` (PostgreSQL).
Alcance: `mobile/.../data/`, `mobile/.../outbox/`, `core/DispatchLock`, schemas Room F0, contratos `MobileProtocol`, e ingesta server (`org.traccar.mobile`, `tc_mobile_messages`, `tc_positions`, `tc_device_health`).

---

## 1. Inventario verificado (file:line)

### 1.1 Capa `data/` — `mobile/app/src/main/java/com/dmujeres/traccar/data/`

| Archivo | Rol | Evidencia |
|---|---|---|
| `AppDatabase.kt:10` | `@Database` v9, 4 entidades, `exportSchema = true` | entidades: PendingPosition, SequenceState, DeadLetter, HealthSnapshot |
| `AppDatabase.kt:13-15` | DAOs expuestos | `positionDao()`, `healthSnapshotDao()` |
| `AppDatabase.kt:27` | Registro de migraciones | `MIGRATION_1_2 … MIGRATION_8_9` (8 migraciones) |
| `AppDatabase.kt:30-149` | Migraciones 1→9 | ALTER TABLE aditivas + CREATE TABLE/INDEX; ninguna reescribe datos |
| `PendingPosition.kt:12-19` | Entidad `pending_positions` | PK `messageId`; índices `sequence`, `retryAt`, `isControl` |
| `PositionDao.kt:11` | DAO abstracto Room | 25+ queries; 3 `@Transaction` (líneas 38, 106, 139) |
| `SequenceState.kt:7` | Entidad `sequence_state` | PK `id`; contador durable |
| `DeadLetter.kt:18-25` | Entidad `dead_letters` | PK `messageId`; índices `sequence`, `reason`, `quarantinedAt` |
| `HealthSnapshot.kt:20-26` | Entidad `health_snapshots` | PK auto; UNIQUE `(sessionId, wallBucket)`; índice `wallMs` |
| `HealthSnapshotDao.kt:14` | DAO salud | insert IGNORE idempotente (:17), `unsent` (:37), `markUploaded` (:41), `deleteBefore` (:33) |
| `BufferDrainPolicy.kt:19` | Tope drenaje reconexión | 200 lotes × 50 = 10 000/evento |
| `PositionBufferPolicy.kt:4,21` | `discardCount` + `StopDrainPolicy` | deadline de cierre 240 s |
| `OutboxRetentionPolicy.kt:39,42` | Retención dura | 100 000 filas / 7 días; `effectiveMax` sanea default antiguo 5 000 |
| `RemoteConfig.kt:23` | Config remota (GET `/api/mobile/v1/config`) | fail-soft (mantiene defaults) |

### 1.2 Capa `outbox/` — `mobile/app/src/main/java/com/dmujeres/traccar/outbox/`

| Archivo | Rol | Evidencia |
|---|---|---|
| `PositionOutboxDispatcher.kt:56` | Propietario único del dispatch HTTP de posiciones | `flushOnce` (:152) bajo `DispatchLock.mutex` (:160) |
| `PositionOutboxDispatcher.kt:246-279` | Semántica por status | accepted/duplicate→delete; rejected/invalid/expired→cuarentena; resto→backoff |
| `PositionOutboxDispatcher.kt:199-238` | Clasificación HTTP no-2xx global | TERMINAL→cuarentena de lote; THROTTLED→backoff; TRANSIENT→retry |
| `PositionOutboxDispatcher.kt:326-411` | Wake microbatch | canal CONFLATED, debounce 1 200 ms, MICROBATCH_MAX=5 |
| `OutboxCoordinator.kt:73-130` | Drenaje por lotes | single-flight (`drainInProgress`), debounce 10 s (:156), auto-encadenado 10,5 s (:157) |
| `OutboxCoordinator.kt:137-146` | Drenaje de cierre | deadline `StopDrainPolicy.TIMEOUT_MS`; nunca borra la cola |
| `RoomControlQueueStore.kt:15` | Adaptador puerto `ControlQueueStore` | mapeo 1:1 con `transport` sin importar Room |
| `HttpFlushPolicy.kt:32` | Disparo plan B HTTP | cola>0 ∧ (sin MQTT ∨ sin ACK>2 min ∨ backlog>10 min) |
| `PendingAlertPolicy.kt:42` | Umbral de alerta | >30 pendientes ∨ sin ACK>2 min ∨ edad>10 min |

### 1.3 Soporte y contratos

- `core/DispatchLock.kt:17` — `Mutex` global único; usado en `MqttManager.kt:477` (dispatch MQTT) y `PositionOutboxDispatcher.kt:160` (HTTP). Sin segundo lock ⇒ sin orden de locks ⇒ sin deadlock.
- `core/MobileProtocol.kt:31-32,36` — `PATH_POSITIONS=/api/mobile/v1/positions`, `PATH_HEALTH=/api/mobile/v1/health`, `PATH_CONFIG=/api/mobile/v1/config`, `WEB_PORT=999`.
- `transport/DispatchPolicy.kt:97,127,149` — backoff 5 s·2^n ±25% jitter, techo 5 min; `shouldDiscardUnacked()=false` SIEMPRE; clasificación 429/408=THROTTLED, 401/403/404/405/413/414/422=TERMINAL, resto=TRANSIENT.
- `transport/Envelope.kt:195` — `messageId = dmj-<hash8>-<seq12>-<uuid>` (aleatorio + secuencia durable).
- Schemas F0: `mobile/app/schemas/com.dmujeres.traccar.data.AppDatabase/9.json` (v9, hash `6b76fe96…`); histórico `…/com.dmujeres.traccar.db.AppDatabase/{8,9}.json`.
- Config Gradle: `mobile/app/build.gradle.kts:92-94` (room.schemaLocation), `:127` (room-testing), `:192-196` (schemas como assets de androidTest).
- Tests JVM outbox (6 archivos): `DispatchRobustnessTest`, `HttpFlushPolicyTest` (7 @Test), `OutboxCoordinatorTest` (7), `PendingAlertPolicyTest` (7), `PositionOutboxDispatcherTest` (12), `PositionOutboxMicroBatchTest` (7). Tests data: `BufferDrainPolicyTest`, `OutboxRetentionPolicyTest`, `PositionBufferPolicyTest`, `StopDrainPolicyTest`, `OfflineRouteOrderTest`, `DeviceHealthSchemaTest`.

### 1.4 Server (Java) — `server/src/main/java/org/traccar/`

| Archivo | Rol | Evidencia |
|---|---|---|
| `mobile/MobileIngestionService.java:46` | `AckStatus` = ACCEPTED/DUPLICATE/REJECTED/INVALID/EXPIRED/PENDING | pipeline común MQTT+HTTP (`process` :121) |
| `mobile/MobileMessageStore.java:49` | Reserva idempotente | INSERT `processing`; dedupe por `messageId` y fallback `(deviceId, sequence)` (:86-96); hash de payload (:98-103) |
| `mobile/MobileAtomicPersistence.java:85-130` | Transacción atómica | INSERT `tc_positions` + UPDATE `tc_mobile_messages`→`accepted` en 1 tx con lease condicional; rollback seguro |
| `mobile/MobileMessageStore.java:179` | Finalización condicional | UPDATE solo si `status='processing' AND leasetoken=?` (evita huérfanas) |
| `mobile/MobileSilenceMonitor.java:166-183` | Sweep de huérfanas | DELETE `processing` con lease vencido >600 s |
| `schedule/TaskMobileMessageCleanup.java:31` | Retención `tc_mobile_messages` | 7 días, nunca borra `processing` |
| `mobile/MobileHealthService.java:47,233-247` | Retención `tc_device_health` | 90 días, poda horaria (`pruneIfDue`) |
| `mobile/JdbcDeviceHealthStore.java:109` | Poda | `DELETE … WHERE ts < ?` |
| `api/resource/MobileHttpResource.java:45-155` | Endpoint HTTP batch | API key actual+previa (:78); MAX_BATCH 200 (:57); semáforo 20 → 503 Retry-After (:106); 429→`throttled` (:110); error por ítem → 503 con acks (:146) |
| `mobile/MobileEnvelopeValidator.java:27` | `MAX_AGE_DAYS=7` | `observedAt` >7 d ⇒ `expired` — alineado con `OutboxRetentionPolicy.MAX_AGE_MS` |

### 1.5 Validación contra PostgreSQL real (read-only, `dmj-db`)

- `tc_mobile_messages`: UNIQUE `messageid`, UNIQUE `(deviceid, sequence)`, índices `updated` (cleanup), `(status, leaseuntil)` (sweep), `status`. ✔ coincide con el código.
- `tc_device_health`: UNIQUE `(deviceid, ts)` + índice `(deviceid, ts DESC)`. ✔ coincide con idempotencia del uploader.
- Columnas de `tc_mobile_messages` verificadas: `status`, `positionid`, `payloadhash`, `leaseuntil`, `leasetoken`, `attempts` — todas usadas por `MobileMessageStore`/`MobileAtomicPersistence`.

---

## 2. Hallazgos (con severidad)

| # | Severidad | Hallazgo | Evidencia |
|---|---|---|---|
| H1 | ✅ OK | **Integridad de paquete**: 0 referencias a `com.dmujeres.traccar.db.*` en todo `mobile/` (código, tests, manifest, proguard). La migración `db/`→`data/` fue un move puro: diff de los 9 archivos de HEAD solo difiere en la línea `package`; `DispatchLock` íntegro en `core/`; `RemoteConfig` vino de `util/`; `HealthSnapshot(+Dao)` son archivos nuevos (FASE 7), nunca existieron en `db/`. Nada que recuperar del historial. | grep global; `git show HEAD:…db/` vs `data/` |
| H2 | ✅ OK | **8→9 preparado**: `MIGRATION_8_9` registrada (:27), ALTER TABLE aditivo con defaults honestos (`uploadedAt=0`, `healthState='UNKNOWN'`), `exportSchema=true`, `9.json` exportado, androidTest cubre 7→8 y 8→9 con datos que sobreviven (`HealthSnapshotMigrationTest.kt:186-217`). Sin `fallbackToDestructiveMigration` en todo el proyecto. | AppDatabase.kt:140; build.gradle.kts:92 |
| H3 | BAJA | **Transacciones no atómicas por ítem en `flushLocked`**: `updateAttempts` y `updateRetryAt` son 2 UPDATEs independientes (PositionOutboxDispatcher.kt:226-227, 274-275). Si el proceso muere entre ambos: attempts sube sin backoff ⇒ reintento inmediato (no pierde datos, solo golpea más). Igual en la rama THROTTLED. | PositionOutboxDispatcher.kt:222-233, 269-278 |
| H4 | BAJA | **`deleteOldestNonControl` inspecciona el payload con `NOT LIKE '%journeyStarted%'`** (PositionDao.kt:67). Frágil ante cambio de formato JSON; mitigado porque filtra `isControl = 0` y los controles llevan `isControl=1`. Deuda estética, no de datos. | PositionDao.kt:67-68 |
| H5 | BAJA | **`dead_letters` sin poda** (intencional, documentado en DeadLetter.kt:15): solo entra por NACK terminal (`rejected/invalid/expired/http_4xx`), flujo naturalmente acotado; pero un bug de contrato masivo crecería sin tope. No hay export/upload de la evidencia. | PositionDao.kt:93-97 |
| H6 | MEDIA | **Snapshots de salud pendientes se purgan a las 24 h aunque no se hayan subido**: `TrackingHealthMonitor.RETENTION_MS = 24 h` y `deleteBefore(now-24h)` borra sin mirar `uploadedAt` (TrackingHealthMonitor.kt:77, 86). Un dispositivo offline >24 h pierde telemetría de salud sin subir (no afecta posiciones). Contradice el espíritu "sin pérdida silenciosa" del outbox. | TrackingHealthMonitor.kt:77 |
| H7 | BAJA | **Índices**: `dueControls` (`isControl=1 AND (retryAt<=? OR retryAt=0) ORDER BY sequence`) usa el índice de `isControl` + filtro/orden; un compuesto `(isControl, sequence)` sería óptimo. `minFutureRetryAt` y `allDue` sí aprovechan `retryAt`/`sequence` existentes. Volumen típico (≈1-2 controles) lo hace cosmético. | PositionDao.kt:61-65; PendingPosition.kt:14-18 |
| H8 | BAJA | **Schemas legacy duplicados**: `schemas/com.dmujeres.traccar.db.AppDatabase/9.json` (idéntico al de `data`) queda huérfano tras el renombre de paquete; dirs de test vacíos `src/test/java/com/dmujeres/traccar/db/` y `…/util/`. Ruido de arqueología, riesgo cero de build. | árbol de schemas/tests |
| H9 | INFO | **Dedupe fallback del server con hash que incluye `messageId`**: `canonicalHash` incluye messageId (MobileIngestionService.java:511); si un dispositivo regenerara messageId con la MISMA sequence (reinstalación/restore de prefs sin Room), `classify` ⇒ REJECTED en vez de DUPLICATE. Teórico: `nextSequence` es durable y nunca reutiliza sequence (`PositionDao.kt:38-44`), y REJECTED es seguro (cuarentena local). | MobileMessageStore.java:98-103 |
| H10 | ✅ OK | **Server transaccional**: INSERT posición + UPDATE mensaje en 1 tx con lease (`MobileAtomicPersistence.persist`); finalización condicional por `leasetoken` evita el clásico `completeWithoutPosition` tardío que desvinculaba posiciones; sweep de huérfanas `processing` >600 s. | MobileAtomicPersistence.java:85-130; MobileMessageStore.java:179-195; MobileSilenceMonitor.java:166 |
| H11 | ✅ OK | **Retenciones alineadas**: cliente 7 d (`OutboxRetentionPolicy.MAX_AGE_MS`) ↔ server `MAX_AGE_DAYS=7` (expired terminal); `tc_mobile_messages` 7 d terminales; `tc_device_health` 90 d con poda horaria; ventana de dedupe del server (7 d) ≥ retención del cliente (los mensajes viejos ya se purgan antes de poder reenviarse). | MobileEnvelopeValidator.java:27; TaskMobileMessageCleanup.java:31; MobileHealthService.java:47 |

---

## 3. Riesgos de migraciones / datos

1. **Upgrade v8 (APK anterior, paquete `db`) → v9 (paquete `data`)**: seguro. Room migra por `user_version` (8<9 ⇒ ejecuta `MIGRATION_8_9`), valida el esquema resultante contra `9.json` del NUEVO paquete y reescribe el identity hash. La estructura de `9.json` es idéntica en ambos dirs (verificado byte a byte); el paquete no participa en la validación de esquema. Riesgo: **ninguno**.
2. **Cadena larga (<7 → 9)**: todas las migraciones 1_2…8_9 están registradas; solo ALTER/CREATE con defaults ⇒ sin reescrituras ni locks largos. Riesgo: bajo (fallaría solo si un dispositivo estuviera en una versión pre-histórica no cubierta).
3. **Fixture de test de migración**: `HealthSnapshotMigrationTest` reconstruye v7/v8 con SQL a mano (no `MigrationTestHelper`): si la entidad cambia sin tocar el fixture, el test puede pasar con un esquema que ya no refleja la realidad. Es el principal hueco de red de seguridad migratoria (los schemas y assets ya están cableados en Gradle para usar el helper).
4. **H6 (salud)**: única pérdida de datos silenciosa identificada (telemetría, no ruta). El ACK de salud (`uploadedAt`) existe; la poda local simplemente no lo consulta.
5. **`sequence_state`**: fila única id=1; `nextSequence` es transaccional (INSERT IGNORE + raise + increment) — crash entre reserva e insert deja hueco (válido por diseño: "los huecos son válidos; la reutilización no", SequenceState.kt:6). Sin riesgo de reenvío duplicado con distinto messageId.

---

## 4. Refactors propuestos (SIN ejecutar) + tests de caracterización

| Propuesta | Detalle | Test de caracterización recomendado |
|---|---|---|
| R1. Atornillar attempts+retryAt en un UPDATE | Nueva query `@Query("UPDATE pending_positions SET attempts=:a, retryAt=:r WHERE messageId=:id")` y usarla en las 3 ramas de `flushLocked` (H3). Cambio mínimo, semántica idéntica. | Extender `PositionOutboxDispatcherTest`: simular fallo entre updates ya no es posible; asertar attempts==N ∧ retryAt==now+backoff tras pending/throttled/429. |
| R2. Retención de salud consciente del ACK | Cambiar poda local a `DELETE FROM health_snapshots WHERE wallMs < ? AND uploadedAt > 0` (o ventana mayor p. ej. 72 h para `uploadedAt=0`) en `HealthSnapshotDao` + `TrackingHealthMonitor` (H6). | Unit-JVM del DAO (in-memory Room) o androidTest: snapshot viejo no subido sobrevive a `deleteBefore`; subido se borra. |
| R3. Índice compuesto para controles | `Index("isControl","sequence")` en v10 (con `MIGRATION_9_10` CREATE INDEX IF NOT EXISTS) si `dueControls` volviera a ser caliente (H7). Hoy diferible. | `EXPLAIN QUERY PLAN` en androidTest antes/después; test de orden FIFO de `OfflineRouteOrderTest` reutilizado. |
| R4. Test de migración con `MigrationTestHelper` | Sustituir fixtures SQL a mano por helper (schemas ya son assets de androidTest, build.gradle.kts:192) cubriendo 7→8→9 encadenado (riesgo 3). | `MigrationTestHelper.runMigrationsAndValidate(9)` con datos sembrados en v7; mantiene el assert de defaults honestos actual. |
| R5. Cap + export de `dead_letters` | Soft-cap (p. ej. 5 000): al exceder, subir las más viejas al endpoint de diagnóstico y luego podarlas; mientras tanto solo contador+alerta (H5). Requiere contrato server nuevo. | Test puro de política (`DeadLetterPolicy.evictPlan(count, cap, oldestFirst)`) estilo `OutboxRetentionPolicy`. |
| R6. Higiene del repositorio (sin código) | Borrar `schemas/com.dmujeres.traccar.db.AppDatabase/9.json` (mantener `8.json` como histórico) y los dirs vacíos `src/test/…/db/`, `…/util/` (H8). | `git status` limpio; build `assembleDebug` + `testDebugUnitTest` verdes. |
| R7. (Opcional) Documentar payload-hash fallback | Añadir nota en `MobileMessageStore.classify` sobre H9 para futuros mantenedores; no cambiar comportamiento. | Test ya existente de dedupe hash-mismatch; agregar caso "misma sequence, messageId regenerado ⇒ REJECTED". |

---

## 5. Resumen final

1. Integridad ✓: el move `db/`→`data/` es limpio (diff = línea de package), `DispatchLock` íntegro en `core/`, 0 referencias a `traccar.db.*`, nada que rescatar del historial.
2. Room v9 sana: 4 entidades, índices correctos, 8 migraciones aditivas registradas, `exportSchema=true`, sin destructive-migration; 8→9 listo con androidTest que valida datos sobrevivientes.
3. Outbox coherente: sequence durable (gaps válidos, nunca reutilizada), FIFO por `sequence` sobre vencidos, ACK de aplicación único autorizante de borrado, backoff exponencial con jitter (techo 5 min), `shouldDiscardUnacked()≡false`.
4. Invariantes verificadas: "sin ACK no se borra" ✓ (retención 100 k/7 d es el único purge, con alerta); "NACK terminal → cuarentena" ✓ (`moveToDeadLetter` transaccional, IGNORE idempotente).
5. Locks: un solo `DispatchLock.mutex` global (MQTT+HTTP+wake), coordinador con single-flight+debounce; sin deadlock por diseño (un solo lock).
6. Server: dedupe dual (messageId UNIQUE + (device,sequence) UNIQUE, verificado en PG), transacción atómica posición+mensaje con lease condicional, sweep de huérfanas, retenciones 7 d/90 d alineadas con el cliente.
7. Riesgo medio único (H6): snapshots de salud no subidos se purgan a las 24 h — pérdida silenciosa de telemetría; R2 lo corrige con un cambio de una línea.
8. Riesgos bajos (H3-H5, H7-H9): updates no atómicos por ítem, LIKE textual en evicción, dead_letters sin tope, índice compuesto cosmético, artefactos legacy de schemas/dirs.
9. Migraciones: el único hueco de red de seguridad es que el test usa SQL a mano en vez de `MigrationTestHelper` (R4) — los schemas ya están cableados para ello.
10. Sin refactors ejecutados; propuestas R1-R7 con su test de caracterización, de menor a mayor valor: R1, R2, R6, R4, R5, R3, R7.
