# services/recuperacion

Recuperacion por push FCM de la plataforma nueva (`/home/DMujeres-Tracking`).
Worker con bucle (sin frameworks, sin HTTP) que detecta equipos silenciados y
les envia un data message `TRACKING_RECOVERY_PROBE` con la cuenta de servicio
Firebase, auditando todo en `operations.dmt_alerta`.

Es el porte de `FcmSender` + `FcmRecoveryService` + `FcmRecoveryPolicy` del
servidor viejo (`/DMujeres-Tracking`, solo lectura), sin el SDK Admin: JWT
RS256 propio con `node:crypto` y `fetch` global contra FCM HTTP v1.

## Requisitos

- Node 24 (`/usr/local/bin/node24`) y npm 24 (`npm24`).
- Dependencia unica: `pg`.
- `.env` en la raiz del proyecto con `POSTGRES_*`/`DMJ_*` (permisos 600).
- Credencial de cuenta de servicio (`DMJ_FCM_CREDENCIAL`, por defecto
  `/home/opencode/.config/dmujeres/secrets/firebase-adminsdk.json`, 600).

## Instalacion y arranque

```bash
cd /home/DMujeres-Tracking/services/recuperacion
npm24 install
node24 src/index.js        # worker
node24 src/smoke.mjs       # prueba de humo (PASS/FAIL)
```

Unidad systemd: `infrastructure/production/systemd/dmj-recuperacion.service`
(instalada por `infrastructure/production/install.sh`).

## Flujo

1. Cada `DMJ_RECUPERACION_INTERVALO` (60 s) lista los equipos **habilitados**
   con token FCM vigente (`iam.dmt_token_fcm`, `activo` y no `invalido`).
2. Es candidato el equipo con jornada activa (`mobile.journeyId` numerico > 0)
   o presencia `offline`, y silencio >= `DMJ_RECUPERACION_SILENCIO_MIN` segun
   `ultima_conexion_en` y `mobile.lastPositionAt`.
3. La politica decide: sin token, intento en curso, cooldown desde el ultimo
   intento y maximo por hora (mismo orden que el servidor viejo).
4. Al enviar: `POST /v1/projects/<project_id>/messages:send` con prioridad
   HIGH y TTL 60 s; se inserta `operations.dmt_alerta` (`tipo=recovery_probe`,
   `estado=nueva`) y el equipo queda `mobile.recoveryState='SENT'` con
   `mobile.recoveryAttemptId` y `mobile.recoveryAt`.
5. Los ack de la App (`POST /api/mobile/v1/recovery-ack`, que guarda
   `services/tracking` como `recovery_ack`) actualizan el estado a `RECEIVED`
   o `STARTED`; un intento sin cerrar bloquea nuevos probes hasta la vigencia.

Detalle completo en `docs/operations/FASE11-RECUPERACION.md`.
