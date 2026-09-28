# Implementation Backlog — IA-2

Orden de ejecución estricto. Cada tarea es verificable sin dispositivos
(salvo las marcadas TESTING). Referencias: `docs/FINAL-ARCHITECTURE.md`,
`docs/ADR/`, `docs/audit/BUG-REGISTER.md`.

## Sprint 1 — Servidor

### P1-SERVER-001 — Idempotencia
- Objetivo: no duplicar posiciones por retransmisión.
- Archivos: `database/migrations/002_idempotencia.sql` (nuevo),
  `services/tracking/src/db.js`, `services/tracking/src/movil.js`.
- Cambios: columnas `boot_id text`, `local_sequence bigint`; índice único
  parcial `(dispositivo_id, boot_id, local_sequence) WHERE boot_id IS NOT NULL`;
  `INSERT ... ON CONFLICT DO NOTHING RETURNING id`; devolver `duplicado`.
- Criterio: insertar dos veces el mismo `(device,boot,seq)` → 1 fila.
- Verificación: `node24 smoke.mjs` (tracking) + SQL manual.

### P1-SERVER-002 — Posición viva no retrocede
- Archivos: `services/tracking/src/db.js:191-236`.
- Cambios: guardas por `registrado_en` en el upsert y `GREATEST` en el update
  del dispositivo.
- Criterio: insertar fix nuevo y luego uno viejo → la viva conserva el nuevo.
- Verificación: SQL manual reproducible en el smoke.

### P1-SERVER-003 — Validación de fechas
- Archivos: `services/tracking/src/osmand.js` (validación) y `movil.js` (lote).
- Cambios: rango [ahora−30 d, ahora+24 h]; fuera → `invalid` (200 para drenar
  buffer, log). Purga del fix de 2037 en la migración 002.
- Verificación: smoke con timestamp absurdo → no se guarda.

### P1-SERVER-004 — Lote `/api/mobile/v1/positions`
- Archivos: `services/tracking/src/servidor.js`, `movil.js`.
- Cambios: POST JSON ≤500 eventos, idempotente, respuesta por evento.
- Criterio: lote con 1 duplicado, 1 inválido y 3 válidos → 3 accepted.
- Verificación: `node24 src/smoke.mjs` + `e2e.sh`.

### P2-SERVER-005 — Particiones y mantenimiento
- Archivos: `services/tracking/src/servidor.js` (arranque) + timer systemd.
- Cambios: precrear mes+2; job diario de reporte de tamaños; `ANALYZE` semanal.
- Verificación: arrancar y ver particiones del mes siguiente creadas.

## Sprint 2 — App (worktree de `origin/main:fallback/`)

### P1-APP-001 — MovementStateMachine
- Archivos: `movement/MovementStateMachine.kt` (nuevo),
  `TrackingController.kt`, `MotionMonitor.kt`, `MotionSignal.kt`.
- Cambios: ADR-006 (multi-fuente, UNKNOWN→ACTIVE, STATIONARY con evidencia).
- Criterio: test JVM con casos (parado real, marcha constante sin IMU,
  sin fix, recreación con historial).
- Verificación: `./gradlew testRegularDebugUnitTest` (offline).

### P1-APP-002 — LocalPositionStore v5
- Archivos: `DatabaseHelper.kt`, `Position.kt`.
- Cambios: migración aditiva (sin DROP) con `boot_id`, `local_sequence`,
  `journey_id`, `provider`, `movement_state`, `status`, `attempts`, `meta`.
- Criterio: actualizar desde v4 conserva pendientes; la secuencia sobrevive
  process recreation y reboot.
- Verificación: test instrumentado no necesario; revisión + unit test de
  serialización.

### P1-APP-003 — UploadQueue
- Archivos: `RequestManager.kt`, `TrackingController.kt`.
- Cambios: clasificar 2xx/4xx/401/retry con `HttpURLConnection`
  (`responseCode`), backoff exp+jitter, lotes de ≤50 al endpoint nuevo.
- Criterio: 404 marca DEAD y continúa con el siguiente (no bloquea).
- Verificación: unit test del clasificador (puro).

### P1-APP-004 — RecoveryEngine
- Archivos: `recovery/DozeAlarmReceiver.kt` (nuevo), `TrackingService.kt`,
  `AutostartReceiver.kt`, `AndroidManifest.xml`.
- Cambios: alarma 9 min rearmada; al despertar fix fresco + flush; contador.
- Verificación: `adb shell dumpsys alarm | grep <pkg>` (TESTING) + unit test
  del armado.

### P1-APP-005 — JourneyManager
- Archivos: `journey/JourneyManager.kt` (nuevo), `DmujeresApi.kt`,
  `TrackingController.kt`; servidor `movil.js` (`GET /journey`).
- Cambios: persistir jornada en `meta`; reconciliar al arrancar.
- Verificación: smoke del endpoint + unit test de reconciliación.

## Sprint 3 — Replay y panel

### P1-WEB-001 — REAL/MATCHED/ESTIMATED
- Archivos: `services/api/src/ruteo.js` (`reconstruidos` con `metodo`),
  `apps/web/src/paginas/operacion/replay.ts`, `ReproductorReplay.tsx`,
  `apps/web/src/paginas/Replay.tsx`, `packages/shared-types/src/replay.ts`,
  `packages/contracts/openapi.json`.
- Cambios: ADR-007 (estilos y leyenda).
- Criterio: captura de pantalla con un hueco: punteado gris + etiqueta.
- Verificación: `npm run build` + revisión manual.

### P1-ROUTING-001 — `/match` y versionado
- Archivos: `services/routing/RouteService.java`, `build.sh`.
- Cambios: map matching reutilizando `MatchService.java` del respaldo;
  `mapaVersion` (hash del pbf) en respuestas.
- Verificación: `curl /match` con 5 fixes conocidos.

### P2-WEB-002 — Salud con causa
- Archivos: `services/api/src/salud.js`/`flota.js`, dashboard `Inicio.tsx`.
- Criterio: un equipo silenciado muestra DEGRADED + causa concreta.

### P2-WEB-003 — Auditoría dashboard
- Archivos: `apps/web/src/api/cliente.ts` (401 con navegación SPA),
  `MapaRaster.tsx` (destroy al desmontar), queries duplicadas.
- Criterio: sin redirects duros en queries de fondo; un mapa por montaje.

## Sprint 4 — Release y seguridad

### P1-REL-001 — 2.1.74 (284)
- Archivos: `fallback/app/build.gradle`, `ota/latest.json`, `ota/*.apk`.
- Cambios: bump, build `assembleGoogleRelease` firmado, OTA con sha256.
- Verificación: E2E OTA + instalación (TESTING en teléfono).

### P2-SEC-001 — Sin credenciales + TLS
- Archivos: `scripts/validation/e2e.sh`, docs, nginx example.
- Cambios: `DMJ_TEST_PASSWORD` obligatorio; TLS/HSTS/cookies seguras como gate.
- Verificación: `grep -rn cctv2026` → 0 resultados.

## Pruebas deliberadamente pospuestas (TESTING, IA-2 con dispositivos)

Los 16 TESTS del encargo + matriz Android 10-16 × OEM (Pixel/Samsung/Xiaomi/
Motorola/Huawei/OPPO/Vivo/Infinix) con ADB (doze, kill, reboot, modo avión
30/120 min). Procedimiento y plantilla de evidencia en `docs/AI-HANDOFF.md` §4.
