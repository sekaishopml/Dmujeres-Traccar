# FCM_RECOVERY.md — F2 FCM Recovery (referencia consolidada)

Documentos detallados existentes:
- `docs/fcm-recovery.md` — diseño y flujo.
- `docs/F2_TEST_MATRIX.md` — matriz de pruebas F2.

## 1. Flujo (implementado)

```
SILENCE_CONFIRMED → RECOVERY_ATTEMPT → FCM (probe HIGH) → RECEIVED →
START_ATTEMPT → FGS_ACTIVE → GPS_ACTIVE → POSITION_GENERATED →
SERVER_ACK (servidor) → RECOVERY_SUCCESS (servidor, con posición real)
```

Estados: `SUCCESS FAILED TIMEOUT BLOCKED NO_TOKEN NO_CREDENTIALS RATE_LIMIT
COOLDOWN`. Garantías conservadas: idempotencia, `attemptId`, dedupe, prioridad,
ACK por etapas, timeout, rate limit, cooldown, validación server-side.

## 2. Seguridad

- Credenciales SOLO server-side: `GOOGLE_APPLICATION_CREDENTIALS`
  (`~/.config/dmujeres/secrets/firebase-adminsdk.json`, 0600, gitignored).
- El token FCM del móvil se registra con `X-Api-Key` + `X-Device-Id` y se
  guarda hasheado-prefijo para diagnóstico (`FcmTokenStore.tokenPrefix`).
- Nunca viaja la clave privada al APK/git/logs/DB.

## 3. Prohibiciones vigentes (§30)

FCM **no** transporta tracking: no hay heartbeat, ni posiciones, ni uso
periódico. Solo control/recovery. El tracking sigue por MQTT (presencia) y
HTTP (posiciones).

## 4. Evidencia real disponible (2026-09)

- SEND real: confirmado con `messageId` desde el servidor dev.
- RECEIVE real: confirmado en ZTE qa-f0 (`FcmRecoveryMessagingService`).
- Fixes P1 (payload `deviceId=uniqueId`) y P2 (estado DEGRADED) desplegados y
  testeados (`FcmRecoveryPolicyTest`, `FcmRecoveryServiceTest`,
  `MobileRecoveryResource`).
- Eventos reales en `tc_recovery_event`: 5× `RECOVERY_SUCCESS` (con posición
  posterior), 74× `RECOVERY_TIMEOUT`, 561× `RECOVERY_SKIPPED_NO_TOKEN`.

## 5. Pendiente honesto

`PENDING_VALIDATION — REQUIRES REAL-WORLD WINDOW`: repetir la ventana completa
end-to-end (forzar silencio real con screen-off + probe + posición nueva) y
adjuntar el `tc_recovery_event` de `RECOVERY_SUCCESS` con el `positionid`.
No declarar FCM E2E como PASS sin esa evidencia.
