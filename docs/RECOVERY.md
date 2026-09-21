# RECOVERY.md — Escalera de recuperación (FASE 6)

## 1. Niveles implementados

| Nivel | Mecanismo | Cuándo | Evidencia |
|---|---|---|---|
| 0 | Tracking normal (FGS + FLP) | jornada activa | `mobile.sessionId` en posiciones |
| 1 | Self-recovery in-process: re-registro FLP (2 min), re-init cliente (10 min sin callbacks, máx 1/15 min), polling one-shot con backoff, GNSS forzado + fallback GPS_PROVIDER | sin fix fresco | logs `SENSOR_FUSION`, `POSITION_*` |
| 2 | `TrackingRecoveryWorker` (WorkManager, auxiliar) | proceso muerto/Doze; stop colgado | trabajo + drenaje outbox |
| 3 | `SessionKeeper` (alarma ELAPSED_REALTIME_WAKEUP, `setAndAllowWhileIdle`) | OEM mata el proceso; corre en proceso nuevo al disparar | `RECOVERY_ATTEMPT/SUCCESS/BLOCKED` en `RecoveryJournal` |
| 4 | `BootReceiver` (BOOT_COMPLETED / MY_PACKAGE_REPLACED / QUICKBOOT) | reboot / update | arranque del servicio |
| 5 | **Acción de usuario** | Android bloquea el arranque en segundo plano | notificación "Toca para reanudar" (`Notifications.resumeRequired`) |

Nunca se asume que una capa vence a la siguiente (§28).

## 2. Estados de recovery (honestos)

`SUCCESS`, `FAILED`, `TIMEOUT`, `BLOCKED`, `NO_TOKEN`, `NO_CREDENTIALS`,
`RATE_LIMIT`, `COOLDOWN`. El cliente **no fabrica** `SERVER_ACK` ni
`RECOVERY_SUCCESS` (F2: el servidor solo los declara con posición real).

## 3. Fallback de acción de usuario ("tap to resume")

- `SessionKeeperReceiver`: si `TrackingService.start()` devuelve false
  (bloqueo de FGS en segundo plano) → `RECOVERY_BLOCKED` + notificación de
  reanudación (PendingIntent `getForegroundService` con ACTION_START; el toque
  del usuario concede la exención de primer plano).
- `TrackingRecoveryWorker`: mismo comportamiento si no puede reactivar.
- Al volver a correr, `startTracking()` retira el aviso
  (`Notifications.clearResume`).
- El aviso NO promete reinicio automático: pide acción (§33).

## 4. Permisos críticos post-OTA y en caliente

`PermissionHealth` audita NOTIFICATIONS, BATTERY_EXEMPT, FINE,
BACKGROUND_LOCATION y FSL tras una actualización (MainActivity). Además el
watchdog vigila **ACCESS_BACKGROUND_LOCATION durante la jornada**: si falta,
marca `PERMISSION_MISSING` y avisa (throttle 1 h) — sin "Permitir siempre" el
FGS pierde ubicación con pantalla apagada (Android 10+).

## 5. Force-stop (§33)

`USER FORCE-STOP` es una condición especial: Android cancela alarmas y prohíbe
el reinicio hasta que el usuario abra la app. La app lo detecta (arranque
anormal + sin evidencia) y lo documenta; NO promete recuperación automática.

## 6. FCM recovery (F2)

Ver `docs/fcm-recovery.md` y `docs/F2_TEST_MATRIX.md`. Estado real:
SEND confirmado (messageId), RECEIVE confirmado en ZTE, ACK por etapas
validado en servidor. La ventana E2E completa (silencio → probe → posición
nueva → SUCCESS con evidencia) quedó `PENDING_VALIDATION` con dispositivo real.
