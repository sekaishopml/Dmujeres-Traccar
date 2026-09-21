# F2_TEST_MATRIX — FCM Recovery (evidencia real)

Fecha: 2026-09-16 (America/Guayaquil). Server dev localhost:999, PostgreSQL `dmj-db`, ZTE Z2450 (Blade A55, Android 14, MyOS14.0.8_Z2450_LA2, versionCode 110/1.1.0).
Regla: ninguna fila se marca PASS sin evidencia (log/DB/ADB). Fixtures jamás cuentan como envío real.

| Test | Resultado | Evidencia |
|------|-----------|-----------|
| Firebase init (server) | BLOCKED | `tracker-server.log:27` 00:05:23 `FCM recovery SIN credenciales utilizables ... Your default credentials were not found` (ADC ausente) |
| Token registration | PASS | curl `X-Device-Id: qa-f0` 00:14:49 → HTTP 200 + `tc_fcm_tokens id=10 deviceid=47 active=t`; log `FCM token registrado para device 47 (prefix=596a5cd855f3)` |
| Token refresh / rotation | PASS | Audit DB: V1 inactive con `previous_token=V1`; V2 active; re-registro V2 idempotente (1 fila); token de device 43 reasignado a 48 eliminando fila previa |
| FCM send (real) | BLOCKED | `RECOVERY_ATTEMPT` real device 43 (attempt `fcm-01f12884-e12e-4f…`) → send failed `missing_credentials`; sin messageId (no hay Firebase Admin) |
| FCM receive (real) | BLOCKED | Requiere send real; sin `google-services.json` + service account. No simulado |
| HIGH priority | BLOCKED (código auditado) | `AndroidConfig.priority=HIGH`, `ttl=120s` en FcmSender; recepción no ejercitable sin FCM real |
| Degraded priority (original HIGH → entregada NORMAL) | PENDIENTE DE FCM REAL (fix implementado) | `FcmRecoveryPolicy.classifyDelivery()` + breadcrumb `FCM_PRIORITY_DEGRADED` (tests JVM PASS); falta `RemoteMessage` real |
| Duplicate recovery | PASS | `SKIP_ACTIVE_ATTEMPT`/cooldown: sweep 00:03 sin duplicados; `RECOVERY_SKIPPED_RATE_LIMIT` device 48 00:12:52 (maxPerHour=5) |
| ACK duplicado | PASS | POST `recovery-ack` duplicado → `rejected` + evento `RECOVERY_ACK_REJECTED / INVALID_TRANSITION_RECEIVED` |
| ACK transición inválida | PASS | `STARTED` sin `RECEIVED` → rejected; stage desconocido → `UNKNOWN_STAGE_*`; spoof `SERVER_ACK` cliente → rejected (fix: `isValidTransition(SERVER_ACK)=false`) |
| FGS | PENDIENTE E2E FCM REAL | Sin credenciales no hay probe que arranque FGS por FCM; FGS por jornada normal: `dumpsys activity services` `isForeground=true types=8` (00:0x) |
| Tracking | PASS (screen ON) | Posiciones qa-f0 cada ~2 min hasta 00:14:47 (tc_positions) |
| GPS | PASS | Fixes reales válidos (`valid=t`) en tc_positions; GPS_CONFIRMED ACK aceptado en cadena C2 |
| Server ACK | PASS | `onPositionAccepted` → `RECOVERY_SERVER_ACK` real tras posición HTTP (idempotente, 1 fila) |
| SUCCESS | PASS (cadena completa con posición real) | `SERVER_ACK` + `RECOVERY_SUCCESS` solo con las 6 etapas + posición aceptada; imposible fabricar `SERVER_ACK` desde cliente (fix D2) |
| Screen ON | PASS | Tracking continuo con pantalla encendida (ver arriba) |
| Screen OFF | PASS (silencio real) / tracking con app viva no medido | KEYCODE_SLEEP 00:14:57 + force-stop 00:15:12 → stall 00:29:55 (15 min, stationary) → RECOVERY_ATTEMPT `fcm-3545004b` → BLOCKED_missing_credentials. App restaurada (pid 10836). Honestidad: no se midió tracking continuo con pantalla apagada sin force-stop |
| Doze | PENDIENTE OEM | Whitelist batería activa (`user,com.dmujeres.traccar,10525`); deviceidle sin restricción para la app |
| ZTE | OEM LIMITATION documentada | cfreezer (`CpuFreezerManagerServiceV2`) congela procesos cacheados; con FGS+whitelist la app no aparece frozen; si el OEM congela el proceso, no hay callback ni ACK → solo `TIMEOUT_NO_*` server-side. Sin hacks |
| Security (secretos) | PASS en F2 / FAIL preexistente | 0 private keys en git/APK/logs; token solo como prefijo SHA-256. Preexistentes: S1 X-Api-Key fallback estática, S2 transporte HTTP cleartext del token, S3 firma release con debug keystore |

## Cadena real observada (server-side, sin credenciales)

```
00:03:22 Stalled event qa-f2/santiago/admin/joseph/miguel (30 min sin coordenadas)
00:03:22 RECOVERY_SKIPPED_NO_TOKEN ×5 (devices 49,2,37,39,41) — hook FCM conectado
00:05:22 Stalled event pedro (30 min, stationary)
00:05:23 RECOVERY_ATTEMPT fcm-01f12884… (source=FCM) → FCM recovery SIN credenciales utilizables
00:05:23 RECOVERY_BLOCKED BLOCKED_missing_credentials (FAILED) — sin messageId, sin delivery
00:12:52 RECOVERY_SKIPPED_RATE_LIMIT device 48 — rate limit operativo
00:14:49 FCM token registrado device 47 (qa-f0)
00:14:57 pantalla OFF (KEYCODE_SLEEP) + 00:15:12 force-stop (silencio real del teléfono)
00:29:55 Stalled event device qa-f0 (15 min sin coordenadas, modo=stationary)
00:29:55 RECOVERY_ATTEMPT fcm-3545004b-9fb7-4bc5 (source=FCM, reason=stalled:15min)
00:29:55 FCM recovery SIN credenciales utilizables → RECOVERY_BLOCKED BLOCKED_missing_credentials (FAILED, sin messageId)
00:32 app restaurada en ZTE (pid 10836, journey se auto-restaura por worker STARTUP)
```

`SEND_ACCEPTED ≠ DELIVERED`: sin credenciales no hay ni SEND_ACCEPTED; nada se presenta como entregado.

## Ronda 2 — credenciales reales (2026-09-16 ~01:00)
| Test | Resultado | Evidencia |
|------|-----------|-----------|
| ADC credentials | PASS | `tracker-server.log` 00:56:31 `credenciales desde Application Default Credentials`; SA fuera del repo (600), `.env` ignorado |
| FCM send real (smoke) | PASS | 01:00:48 device 47, messageId `0:1789538449547706%0a9a548cf9fd7ecd` (FcmSenderSmokeTest opt-in) |
| Invalid token | PASS | `INVALID_ARGUMENT`, messageId=null, sin éxito |
| Invalid credentials | PASS | `MISSING_CREDENTIALS`, sin crash, `.env` intacto |
| Token real app→server | PASS | `tc_fcm_tokens` id=13 deviceid=47 active prefix `doITS6Hy…` 01:04:46; rotación delete→re-registro OK |
| FCM receive real | PASS | `onMessageReceived` 01:00:49 en ZTE, priority HIGH (ACK priority=HIGH, DB fcmpriority=HIGH) |
| P1 DEVICE_MISMATCH | CORREGIDO (pendiente re-probar) | Probe rechazado `BLOCKED_DEVICE_MISMATCH` (id numérico vs uniqueid); fix: payload envía uniqueId + `priorityLabel` conserva DEGRADED; tests 805/0 |
| E2E SCREEN ON/OFF recovery real | PENDING | Agente E2E cancelado antes de ejecutar; requiere re-probar cadena completa post-fix P1 |
| Suite server | 805/0/0 | `./gradlew test` |
| Suite mobile | 507/0/0 | `./gradlew :app:testDebugUnitTest` |
| KEY_ROTATION_REQUIRED | SÍ | Credencial compartida por canal inseguro; rotar service account + API key cliente |
