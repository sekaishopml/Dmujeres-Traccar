# Nomenclatura de la base DMujeres Tracking

Decisión: [ADR-001](../architecture/adr/ADR-001-nomenclatura-dmt.md). El
diccionario autoritativo legado→nuevo se completa en
`MAPA-LEGADO-DMT.md`; este documento fija las reglas.

## Reglas

- Esquemas: `iam`, `tracking`, `telemetry`, `operations`, `audit`, `system`.
- Tablas: `dmt_` + sustantivo en español singular (`dmt_usuario`,
  `dmt_dispositivo`, `dmt_posicion`).
- Columnas: español `snake_case` (`registrado_en`, `velocidad_kmh`,
  `precision_m`, `bateria_pct`).
- Claves: `id BIGINT` (compatibilidad) e `id_publico UUID` para referencias
  públicas nuevas.
- Índices: `idx_dmt_<tabla>_<columnas>`; BRIN `brin_dmt_<tabla>_<columna>`;
  único `uq_dmt_...`; foránea `fk_dmt_...`.
- Nada de `tc_` en la plataforma nueva. `tc_` solo vive en el legado y en el
  mapa de migración.

## Esquema objetivo

| Esquema | Tablas principales (dmt_) |
|---|---|
| `iam` | `dmt_usuario`, `dmt_rol`, `dmt_usuario_rol`, `dmt_sesion` |
| `tracking` | `dmt_dispositivo`, `dmt_posicion` (particionada), `dmt_posicion_actual`, `dmt_evento` |
| `telemetry` | `dmt_bateria`, `dmt_senal`, `dmt_salud_dispositivo` |
| `operations` | `dmt_jornada`, `dmt_jornada_tramo`, `dmt_asignacion`, `dmt_alerta` |
| `audit` | `dmt_auditoria` |
| `system` | `dmt_configuracion`, `dmt_migracion_mapa`, `dmt_version_esquema` |

## Equivalencias núcleo (se amplía en el mapa)

| Legado (`tc_*`) | Nuevo |
|---|---|
| `tc_users` | `iam.dmt_usuario` |
| `tc_user_user` / roles | `iam.dmt_rol`, `iam.dmt_usuario_rol` |
| `tc_devices` | `tracking.dmt_dispositivo` |
| `tc_positions` | `tracking.dmt_posicion` |
| `tc_positions_bak_20260903` | histórico a recuperar en `tracking.dmt_posicion` |
| `tc_events` | `tracking.dmt_evento` |
| `tc_device_health` | `telemetry.dmt_salud_dispositivo` |
| `tc_device_attribute` / `tc_attributes` | `telemetry.dmt_senal` / columnas propias |
| `tc_user_device` | `operations.dmt_asignacion` |
| `tc_mobile_messages`, `tc_recovery_event` | `operations.dmt_alerta` (o tabla propia de recuperación) |
| `tc_fcm_tokens` | `iam.dmt_sesion`/tabla propia de tokens |
| `tc_statistics` | `system`/`telemetry` según contenido |
| `tc_keystore`, `tc_revoked_tokens` | `iam.dmt_sesion` |
| `databasechangelog*` | no se migra (metadatos del motor anterior) |

## Migración

1. Copiar con identificadores y fechas originales; nada de regenerar ids de
   usuarios o dispositivos (la App y las credenciales dependen de ellos).
2. Registrar cada fila migrada en `system.dmt_migracion_mapa`.
3. Validar conteos e integridad antes de levantar los servicios nuevos.
4. El histórico fragmentado (`tc_positions_bak_20260903`, `traccar_qa`, dump
   pre-purga) se restaura en la misma tabla particionada `tracking.dmt_posicion`.

## No se renombra

- Producción actual (`/DMujeres-Tracking`, base `traccar`): intacta.
- Contrato de la App: OsmAnd `:5055`, atributos `mobile.*`, canal
  `/api/mobile/v1/*`, claves de dispositivo y FCM.
