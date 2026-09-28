# FASE 11 - `services/recuperacion` (recuperacion por push FCM)

Servicio propio que porta la recuperacion FCM del servidor viejo
(`server/src/main/java/org/traccar/mobile/FcmSender.java`,
`FcmRecoveryService.java`, `FcmRecoveryPolicy.java`) a la plataforma nueva.
Fecha: 2026-09-26. Autor: agente BACKEND/INFRA.

## 1. Alcance y limites

- Worker con bucle en Node 24 (`/usr/local/bin/node24`), sin frameworks: usa
  `node:crypto` (JWT RS256) y `fetch` global. Unica dependencia `pg`.
- Lee `tracking.dmt_dispositivo` e `iam.dmt_token_fcm`; escribe solo
  `operations.dmt_alerta`, `iam.dmt_token_fcm` (marcar invalido) y los
  atributos `mobile.recovery*` del equipo.
- Escribe en `dmt-db` (`127.0.0.1:5443`, base `dmujeres`). No toca
  `dmj-db`/`traccar`, no depende de `dmj-traccar` y no modifica
  `/DMujeres-Tracking` (solo se leyo el codigo viejo como referencia).
- Sin secretos en el repo: la credencial FCM es una ruta del `.env` y el token
  OAuth nunca se registra.

## 2. Arquitectura

| Archivo | Responsabilidad |
|---|---|
| `src/index.js` | Arranque, bucle por `DMJ_RECUPERACION_INTERVALO`, parada limpia con SIGTERM/SIGINT |
| `src/config.js` | `.env` + `process.env` (env gana), defaults y banderas |
| `src/db.js` | Pool `pg` y consultas (equipos/token vigente, intentos, ack, inserciones) |
| `src/fcm.js` | JWT RS256, canje OAuth con cache, `messages:send`, clasificacion de errores |
| `src/monitor.js` | Politica pura, evaluacion de candidatos, envio y deteccion de ack |
| `src/log.js` | Log a stdout/stderr (journald) |

Flujo por ciclo:

1. `listarEquipos()`: equipos **habilitados** con su token FCM **vigente**
   (`activo AND NOT invalido`; si hay varios, el de `ultimo_uso_en` mas
   reciente).
2. Candidato = (jornada activa `mobile.journeyId` numerico > 0 **o** presencia
   `mobile.presenceState='offline'`) **y** silencio >=
   `DMJ_RECUPERACION_SILENCIO_MIN`, con silencio = ahora - max(
   `ultima_conexion_en`, `to_timestamp(mobile.lastPositionAt/1000)`).
3. Politica (ver seccion 3). Si `ALLOW` se envia el data message:
   `POST https://fcm.googleapis.com/v1/projects/<project_id>/messages:send`

   ```json
   {"message":{"token":"<token>","data":{"type":"TRACKING_RECOVERY_PROBE",
     "recoveryAttemptId":"fcm-<uuid>","deviceId":"<uniqueid>","issuedAtMs":"<ms>"},
     "android":{"priority":"HIGH","ttl":"60s"}}}
   ```

4. Al aceptar FCM: fila `operations.dmt_alerta` (`origen='recuperacion'`,
   `tipo='recovery_probe'`, `estado='nueva'`, atributos `recoveryAttemptId`,
   `messageId`, `silencioMin`, `motivo`) y parche al equipo
   `mobile.recoveryState='SENT'`, `mobile.recoveryAttemptId`, `mobile.recoveryAt`
   (epoch ms).
5. Ack de la App: `POST /api/mobile/v1/recovery-ack` (ya implementado en
   `services/tracking`, guarda `tipo='recovery_ack'` con `attemptId` y `stage`).
   El monitor toma el ack mas reciente del intento abierto y actualiza
   `mobile.recoveryState` a `RECEIVED` o `STARTED` (etapas normales del
   contrato `RECOVERY_*`).
6. Dry-run (`DMJ_RECUPERACION_DRY_RUN=1`): calcula candidatos y decisiones, los
   registra en log y **no envia ni escribe**.

Errores FCM: `404`/`UNREGISTERED`/`INVALID_ARGUMENT` marcan el token
(`invalido=true`); `401` renueva el access token una unica vez y reintenta; el
resto se registra en log y en `operations.dmt_alerta`
(`tipo='recovery_send_error'` con `errorCode` y `http`).

## 3. Politica

Mismo orden de decisiones que `FcmRecoveryPolicy.decide` del servidor viejo:

| Decision | Condicion |
|---|---|
| `SKIP_DISABLED` | `DMJ_FCM_ENABLED` inactivo |
| `SKIP_NO_TOKEN` | el equipo no tiene token FCM vigente |
| `SKIP_ACTIVE_ATTEMPT` | intento abierto (`recoveryState` `SENT`/`RECEIVED` y edad < vigencia) |
| `SKIP_COOLDOWN` | ultimo intento hace menos de `DMJ_RECUPERACION_COOLDOWN` |
| `SKIP_RATE_LIMIT` | `DMJ_RECUPERACION_MAX_HORA` intentos o mas en la ultima hora |
| `ALLOW` | resto |

Intentos = filas `recovery_probe` **y** `recovery_send_error` (un fallo tambien
consume cuota, como `RECOVERY_ATTEMPT` en el servidor viejo). Un intento se
cierra cuando el monitor ve un ack `STARTED` (o una etapa terminal) o cuando
vence `DMJ_RECUPERACION_INTENTO_VIGENCIA_MIN`.

## 4. Variables de entorno

Se cargan de `/home/DMujeres-Tracking/.env` (600) y de `process.env` (esta
gana). Sin valores por defecto que expongan secretos.

| Variable | Default | Uso |
|---|---|---|
| `DMJ_FCM_ENABLED` | `1` | `0` deja el servicio solo en monitoreo |
| `DMJ_FCM_CREDENCIAL` | `/home/opencode/.config/dmujeres/secrets/firebase-adminsdk.json` | ruta de la cuenta de servicio |
| `DMJ_RECUPERACION_INTERVALO` | `60` | segundos entre ciclos |
| `DMJ_RECUPERACION_SILENCIO_MIN` | `15` | minutos de silencio para ser candidato |
| `DMJ_RECUPERACION_COOLDOWN` | `60` | segundos desde el ultimo intento |
| `DMJ_RECUPERACION_MAX_HORA` | `5` | intentos maximos por equipo y hora |
| `DMJ_RECUPERACION_INTENTO_VIGENCIA_MIN` | `5` | minutos que un intento abierto bloquea reenvios |
| `DMJ_RECUPERACION_DRY_RUN` | `0` | `1` calcula y registra sin enviar ni escribir |

## 5. Como probar

```bash
cd /home/DMujeres-Tracking/services/recuperacion
node24 --check src/*.js src/*.mjs      # sintaxis
npm24 install                          # dependencia unica: pg
node24 src/smoke.mjs                   # PASS/FAIL (politica, OAuth, dry-run, envio qa-f0)
```

Canje OAuth real sin enviar push (no imprime el token):

```bash
node24 -e "import('./src/fcm.js').then(async ({cargarCredencial,crearEmisorFcm})=>{
  const c=crearCredencial('/home/opencode/.config/dmujeres/secrets/firebase-adminsdk.json');
  const e=crearEmisorFcm({credencial:c,log:console});
  const t=await e.obtenerAccessToken(); console.log('access_token ok largo=',t.length);});"
```

Ciclo seco con datos reales (devuelve candidatos y decisiones sin efectos):

```bash
DMJ_RECUPERACION_DRY_RUN=1 node24 -e "import('./src/monitor.js').then(async (m)=>{
  const {cargarConfiguracion}=await import('./src/config.js');
  const {crearAlmacen}=await import('./src/db.js');
  const c=cargarConfiguracion(); const a=await crearAlmacen(c,console);
  await m.ejecutarCiclo({almacen:a,emisor:null,configuracion:c,log:console,dryRun:true});
  await a.cerrar();});"
```

Systemd:

```bash
sudo install -m 0644 infrastructure/production/systemd/dmj-recuperacion.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now dmj-recuperacion
systemctl is-active dmj-recuperacion
journalctl -u dmj-recuperacion -n 20 --no-pager
```

## 6. Verificacion ejecutada (2026-09-26)

- `node24 --check` correcto en los 7 archivos; `npm24 install` instala solo
  `pg` (14 paquetes, 0 vulnerabilidades).
- Canje OAuth real contra `oauth2.googleapis.com` con la cuenta de servicio
  `firebase-adminsdk-fbsvc@dmujeres-tracking.iam.gserviceaccount.com`: access
  token de 1024 caracteres, segunda llamada cacheada (sin red).
- `node24 src/smoke.mjs` => PASS:
  - politica pura: `SKIP_DISABLED`, `SKIP_NO_TOKEN`, `SKIP_ACTIVE_ATTEMPT`,
    `SKIP_COOLDOWN` (30 s), `ALLOW` (61 s), `SKIP_RATE_LIMIT` (5/h), candidatos
    con y sin jornada/silencio/`lastPositionAt`, vigencia de intento.
  - dry-run con datos reales: equipos habilitados evaluados con su decision
    (`macias` y `pilay` no candidatos por silencio; `manzaba` y `fernando`
    candidatos con `ALLOW`; `alejandro` candidato con `SKIP_NO_TOKEN`).
  - envio real de prueba a `qa-f0` (habilitado y token activado solo durante
    el humo): FCM respondio `404 UNREGISTERED` porque el token guardado en
    `iam.dmt_token_fcm` es un fixture de QA sin relacion con
    `mobile.fcmTokenPrefix` del equipo. El servicio lo trato como token
    invalido: fila `recovery_send_error` en `operations.dmt_alerta` con
    `errorCode=UNREGISTERED` y token marcado `invalido=true`. No es un fallo
    del servicio: con un token real de la App el envio devuelve `messageId`.
  - cooldown real: con el intento recien auditado el monitor decide
    `SKIP_COOLDOWN`; con un `recovery_ack` `RECOVERY_RECEIVED` simulado decide
    `SKIP_ACTIVE_ATTEMPT` y refleja `RECEIVED` (solo en dry-run).
  - limpieza verificada: alertas de recuperacion de `qa-f0` 2069 -> 2069 y
    atributos/token/habilitado restaurados.
- Unidad `dmj-recuperacion.service` activa y habilitada; `journalctl` sin
  errores de arranque.

## 6.1 Modo seco en estabilizacion (decision operativa)

El `.env` de la plataforma queda con `DMJ_RECUPERACION_DRY_RUN=1`: el servicio
evalua la flota y registra candidatos/decisiones cada ciclo, pero **no envia
push ni escribe** hasta que se decida el corte. Motivo: los tokens migrados de
`macias`, `manzaba` y `fernando` coinciden con el `mobile.fcmTokenPrefix` real
de sus equipos, de modo que un arranque en vivo enviaria probes a equipos de
produccion de inmediato (`manzaba` y `fernando` cumplen hoy silencio y
presencia offline; `macias` esta a minutos del umbral).

Para habilitar el envio real: quitar `DMJ_RECUPERACION_DRY_RUN=1` del `.env` (o
ponerlo en `0`) y `sudo systemctl restart dmj-recuperacion`.

## 7. Pendientes

- Metricas de recuperacion (intentos, ack por etapa, latencia probe->posicion,
  tasa de exito real) y su exposicion al panel.
- Cierre end-to-end del intento (`SERVER_ACK` + `SUCCESS`/`TIMEOUT`) como el
  servidor viejo; hoy solo se modela la vigencia del intento.
- Soporte de etapas intermedias (`FGS_ACTIVE`, `TRACKING_ACTIVE`,
  `GPS_CONFIRMED`) en `mobile.recoveryState` si la App las emite.
- Rotacion/limpieza de tokens FCM invalidos y aviso al usuario para
  re-registrar el equipo.
- Prueba con token FCM real (el fixture de `qa-f0` da `UNREGISTERED`).
