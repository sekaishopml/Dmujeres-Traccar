# services/tracking

Receptor de tracking de la plataforma nueva (`/home/DMujeres-Tracking`). Habla
el contrato **congelado** de la App actual
(`docs/api/COMPATIBILIDAD-APP.md`) y escribe exclusivamente en la base nueva
`dmt-db` (`dmujeres`, esquemas `tracking`, `operations`, `telemetry`).

No toca la producción: no lee ni escribe `dmj-db`/`traccar`, no usa el servicio
`dmj-traccar` y no modifica `/DMujeres-Tracking` (solo lee el manifiesto OTA y
el `.env` legacy durante el diseño de la fase).

## Requisitos

- Node 24 (`/usr/local/bin/node24`) y npm 24 (`npm24`).
- Dependencia única: `pg`.
- `.env` en la raíz del proyecto con `POSTGRES_*`/`DMJ_*` (permisos 600).

## Instalación y arranque

```bash
cd /home/DMujeres-Tracking/services/tracking
npm24 install
node24 src/servidor.js
```

El servicio escucha en `DMJ_TRACKING_HOST`:`DMJ_TRACKING_PORT`
(`127.0.0.1:5056` por defecto). **Nota de este host:** `dmj-traccar` ocupa el
rango 5000-5100, así que el `.env` de desarrollo usa `DMJ_TRACKING_PORT=6066`;
tras el corte se vuelve a 5056.

## Rutas

| Método | Ruta | Función |
|---|---|---|
| GET/POST | `/` | Protocolo OsmAnd (params en query o form-urlencoded) |
| GET | `/api/mobile/v1/config` | Configuración remota de la App |
| POST | `/api/mobile/v1/journey` | Inicio/fin de jornada |
| POST | `/api/mobile/v1/diagnostics` | Diagnóstico del equipo + muestra de batería |
| GET | `/api/mobile/v1/ota` | Manifiesto OTA (lectura de `DMJ_OTA_DIR`) |
| POST | `/api/mobile/v1/fcm-token` | Prefijo hash del token FCM |
| POST | `/api/mobile/v1/recovery-ack` | ACK de recuperación (log) |

Autenticación del canal móvil: `X-Api-Key` (`DMJ_CLAVE_MOVIL` y, en rotación,
`DMJ_CLAVE_MOVIL_ANTERIOR`) + `X-Device-Id`.

## Prueba de humo

```bash
node24 src/smoke.mjs   # PASS/FAIL; deja la base como estaba
```

## Variables principales

`DMJ_TRACKING_HOST`, `DMJ_TRACKING_PORT`, `DMJ_CANAL_MOVIL`,
`DMJ_CLAVE_MOVIL`, `DMJ_CLAVE_MOVIL_ANTERIOR`, `DMJ_OTA_DIR`, `DMJ_ENV_FILE`,
`POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`, `DMJ_DB_HOST`,
`DMJ_DB_PORT` (o `DMJ_DB_URL`).

Detalle operativo y pendientes: `docs/operations/FASE4-TRACKING.md`.
