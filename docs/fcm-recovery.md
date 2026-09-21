# FCM Recovery (F2)

Recuperación de tracking ante **silencio anormal** detectado por el servidor.
FCM **NO es heartbeat**, no transporta posiciones, no reemplaza MQTT/HTTP.

## Arquitectura

```
MobileSilenceMonitor (silencio anormal, stalled confirmado)
  → FcmRecoveryService.onSilenceDetected(device)
      → FcmRecoveryPolicy.decide (feature flag / token / intento activo / cooldown / rate limit)
      → tc_recovery_event RECOVERY_ATTEMPT
      → FcmSender (Admin SDK, data message HIGH, TTL 120 s, payload mínimo)
        → Android FcmRecoveryMessagingService.onMessageReceived
            → FcmRecoveryPolicy.validate (type/attemptId/deviceId/issuedAt) + dedupe
            → prioridad (HIGH/NORMAL; NORMAL → FCM_PRIORITY_DEGRADED)
            → TrackingService.start (mecanismo existente)
            → ACK etapas: RECEIVED → STARTED → FGS_ACTIVE → TRACKING_ACTIVE → GPS_CONFIRMED
        → posición real llega al servidor (ingesta existente)
            → FcmRecoveryService.onPositionAccepted → SERVER_ACK → SUCCESS (solo con TODAS las etapas)
  Timeout sin evidencia → RECOVERY_TIMEOUT con causa (TIMEOUT_NO_FGS/TRACKING/GPS/SERVER_ACK)
```

## Payload (DATA message)

```json
{ "type": "TRACKING_RECOVERY_PROBE", "recoveryAttemptId": "fcm-<uuid>",
  "deviceId": "<id>", "issuedAt": "<epoch ms>" }
```
Sin ubicación, credenciales ni datos personales.

## Estados/etapas

ATTEMPT → RECEIVED → STARTED → FGS_ACTIVE → TRACKING_ACTIVE → GPS_CONFIRMED → SERVER_ACK.
SUCCESS solo con **todas** las etapas (el servidor lo evalúa; Android nunca declara éxito).
Bloqueos: `BLOCKED_INVALID_PAYLOAD`, `BLOCKED_DEVICE_MISMATCH`, `BLOCKED_DUPLICATE`,
`BLOCKED_LOW_PRIORITY`, `BLOCKED_BACKGROUND_RESTRICTION`, `BLOCKED_PERMISSION`,
`BLOCKED_SERVICE_START`, `BLOCKED_INVALID_TOKEN`, `SKIP_DISABLED/COOLDOWN/RATE_LIMIT/NO_TOKEN/ACTIVE_ATTEMPT`.

## Endpoints (mismos headers del canal HTTP: `X-Api-Key` + `X-Device-Id`)

- `POST /api/mobile/v1/fcm-token` — `{fcmToken, appVersion, platform, manufacturer, model, androidVersion}`
- `POST /api/mobile/v1/recovery-ack` — `{recoveryAttemptId, stage, priority, reason}`

## Base de datos (Liquibase 6.14.5)

- `tc_fcm_tokens` (deviceid, token UNIQUE, previous_token, active, invalid, createdat, updatedat, lastusedat)
- `tc_recovery_event` extendida: `fcmmsgid`, `fcmpriority`, `source`

## Feature flag y configuración (server)

```xml
<entry key="fcm.recovery.enabled">false</entry>              <!-- default OFF -->
<entry key="fcm.recovery.cooldownSeconds">900</entry>
<entry key="fcm.recovery.maxPerHour">3</entry>
<entry key="fcm.recovery.evidenceTimeoutSeconds">300</entry>
```

## Credenciales (NUNCA en el repo ni en el APK)

1. `notificator.firebase.serviceAccount` (mecanismo existente del proyecto), o
2. **Application Default Credentials**:
```bash
export GOOGLE_APPLICATION_CREDENTIALS=/secure/firebase/dmujeres-tracking-service-account.json
```
En systemd:
```ini
Environment=GOOGLE_APPLICATION_CREDENTIALS=/secure/firebase/dmujeres-tracking-service-account.json
```
Sin credenciales el server loguea `FCM recovery SIN credenciales utilizables` y
los probes quedan `RECOVERY_BLOCKED` (nunca crash silencioso).

## Android

- `firebase-messaging:24.1.0` SOLO (sin analytics/crashlytics/etc.).
- `google-services.json` en `mobile/app/google-services.json` (fuera de git).
  Sin el archivo el build NO falla y la app degrada: `fcmConfigured=false`.
- Token: `FcmTokenRegistrar` (inicio + `onNewToken`); en logs/diagnóstico solo
  hay prefijo hash (`fcmTokenPrefix`), nunca el token completo.

## Diagnóstico

Prefs `fcm_configured`, `fcm_token_prefix`, `fcm_token_registered`,
`fcm_last_attempt_id`, `fcm_last_priority`, `fcm_last_result`, `fcm_last_at`.
Breadcrumbs Sentry: `FCM_TOKEN_REGISTERED`, `FCM_RECOVERY_RECEIVED`,
`FCM_RECOVERY_PRIORITY_HIGH/NORMAL`, `FCM_RECOVERY_BLOCKED`, `FCM_RECOVERY_TIMEOUT`,
`FCM_RECOVERY_GPS_CONFIRMED`.

## Limitaciones OEM

ZTE/MyOS cfreezer puede congelar el proceso con pantalla apagada incluso con
FCM HIGH: si el proceso está congelado, el mensaje no ejecuta código. Se
registra `TIMEOUT_NO_*` / `FCM_DELIVERY_FAILED` según evidencia; NO se
falsifica éxito. WakeLock/root no forman parte de F2.

## Troubleshooting

| Síntoma | Causa probable |
|---|---|
| `fcmConfigured=false` | falta `google-services.json` en el APK |
| `RECOVERY_SKIPPED_DISABLED` | `fcm.recovery.enabled=false` (default) |
| `RECOVERY_BLOCKED / missing_credentials` | falta `GOOGLE_APPLICATION_CREDENTIALS` en el server |
| `BLOCKED_DUPLICATE` | el mismo attemptId llegó 2 veces (idempotencia OK) |
| `TIMEOUT_NO_FGS` | el FGS no pudo arrancar (OEM/backgroun restriction) |
| `TIMEOUT_NO_SERVER_ACK` | hubo recovery pero la posición no llegó al server |
