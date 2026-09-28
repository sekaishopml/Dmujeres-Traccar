# DMujeres Tracking — Agente DB

Estado de la base de datos de producción y plan de trabajo del agente DB.
Fuente: `docs/Plan-Maestro.pdf`. La instalación en `/DMujeres-Tracking` es la
fuente de datos y **no se modifica** hasta el cutover autorizado.

## 1. Rol y reglas

- Mantener el dato disponible y verificable en toda la transición.
- Cada cambio peligroso: backup + evidencia + rollback + validación OLD vs NEW.
- No detener producción, no hacer `DROP` sobre `traccar`, no borrar
  `/DMujeres-Tracking`.
- Ningún corte de producción: eso es del agente RELEASE (FASE 10).

## 2. Estado actual de la base (verificado 2026-09-25)

| Dato | Valor |
|---|---|
| Motor | PostgreSQL 17.10 (Alpine) + TimescaleDB 2.29.1 |
| Contenedor | `dmj-db` (`timescale/timescaledb:latest-pg17`), puerto `127.0.0.1:5433` |
| Base | `traccar` (61 MB) |
| Usuario actual | `traccar` (superusuario, debe reducirse en la base nueva) |
| Otras bases en el contenedor | `postgres`, `traccar_qa` (no tocadas) |
| Esquema | `public` con tablas Traccar + Liquibase (`databasechangelog`) |
| Disco host | 4.5 GB libres (95 % usado) — vigilar |

Conteos de producción al cierre de FASE 1 (sistema vivo):
`tc_users`=5, `tc_devices`=9, `tc_positions`≈26.4k, `tc_events`≈5.1k,
`tc_user_device`=20. Secuencias: `tc_users`=9, `tc_devices`=59,
`tc_positions`≈88.26k, `tc_events`≈32.17k, `tc_mobile_messages`≈73.6k.

### 2.1 Tablas propias además del esquema Traccar estándar

| Tabla | Filas aprox. | Tamaño | Nota para migración |
|---|---|---|---|
| `tc_mobile_messages` | 4 160 | 17 MB | Datos de la app móvil; decidir migrar/archivar |
| `tc_recovery_event` | 9 631 | 4.6 MB | Eventos de recuperación; decidir destino |
| `tc_device_health` | 1 002 | 1.5 MB | Encaja en `telemetry.device_health` |
| `tc_fcm_tokens` | 9 | 64 kB | Push; revisar en FASE 7 (App) |
| `tc_statistics` | 17 | 32 kB | Métricas operativas |
| `tc_positions_bak_20260903` | 19 602 | 6.2 MB | Copia interna; NO migrar automáticamente |
| `_bak_macias_mock_positions_20260925` | 8 | 16 kB | Copia interna; NO migrar automáticamente |

Las dos tablas `*_bak_*` son respaldos internos hechos dentro de la propia
base; deben tratarse como archivo histórico, no como datos operativos.

### 2.2 TimescaleDB y particionado actual

| Hypertable | Chunks | Dimensiones | Intervalo | Compresión | Políticas |
|---|---|---|---|---|---|
| `tc_positions` | 9 | tiempo (`fixtime`, 7 d) + `deviceid` | 7 días | no | ninguna de retención/compresión |
| `tc_events` | 29 | tiempo (`eventtime`, 7 d) + `deviceid` | 7 días | no | ninguna |
| `tc_actions` | 7 | tiempo (`actiontime`, 7 d) + `deviceid` | 7 días | no | ninguna |

Jobs Timescale activos: `policy_telemetry` (reporte de telemetría) y
`policy_job_stat_history_retention`. No hay políticas de retención de datos ni
compresión configuradas en las hypertables.

## 3. FASE 1 completada: backup verificado

Backup lógico + restauración de prueba **válidos**. Detalle completo en
`docs/migration/BACKUP-EVIDENCIA.md`.

- Dump custom: `/home/DMujeres-backups/migration/20260925-205714/db/traccar_pre_cutover.dump` (3 892 173 bytes, sha256 `774940cd…`).
- Roles: `postgres_globals.sql`; hashes en `SHA256SUMS` (verificado OK).
- Restauración exitosa en `dmj_restore_20260925-205714` (exit 0, sin errores),
  conteos y contenido idénticos contra la foto del dump; base scratch eliminada
  tras guardar evidencia.
- Pendiente de esta fase no queda nada; el delta en vivo posterior al dump se
  recapturará en el cutover con un dump nuevo o CDC (FASE 2/3/10).

## 4. Qué falta para FASE 3 (PostgreSQL y restauración)

### 4.1 Decisión de plataforma destino

- El plan fija PostgreSQL 18.6 y `uuidv7()` para `public_id`; producción corre
  17.10. Falta decidir/provisionar el PostgreSQL nuevo (contenedor o servicio),
  puerto, volumen, credenciales y política de backups del entorno nuevo.
- El plan pide particionado nativo por rango (`tracking.position`), no
  hypertables. Decidir si se conserva TimescaleDB solo para telemetría de alta
  frecuencia o se abandona por completo. PostGIS queda opcional.
- Activar `pg_stat_statements` en la instancia nueva.

### 4.2 Esquemas y tablas a crear

```sql
CREATE SCHEMA IF NOT EXISTS iam;
CREATE SCHEMA IF NOT EXISTS tracking;
CREATE SCHEMA IF NOT EXISTS telemetry;
CREATE SCHEMA IF NOT EXISTS operations;
CREATE SCHEMA IF NOT EXISTS audit;
CREATE SCHEMA IF NOT EXISTS system;
```

Tablas objetivo (plan, secciones 8 y 12):
- `iam`: `user_account`, `role`, `user_role`, `session`.
- `tracking`: `device`, `position` (particionada por `recorded_at`),
  `current_position` (una fila por dispositivo), `tracking_event`.
- `telemetry`: `battery_sample`, `signal_sample`, `device_health`.
- `operations`: `journey`, `journey_segment`, `assignment`, `alert`.
- `audit`: `audit_log`.
- `system`: `setting`, `migration_map`, `schema_version`.

Particiones e índices obligatorios: particiones mensuales de
`tracking.position` creadas con antelación; índice B-tree
`(device_id, recorded_at DESC)`; BRIN sobre `recorded_at`; índice solo
`device_id`; `device_last_seen_idx`; `event_device_time_idx`;
`battery_device_time_idx`. JSONB solo para atributos variables.

### 4.3 Migración de datos Traccar al modelo nuevo

Mapeo previsto (a confirmar en FASE 2/3 contra los contratos y la App):

| Origen | Destino | Notas |
|---|---|---|
| `tc_users` | `iam.user_account` + `iam.role` | Conservar ids, email y `hashedpassword`+`salt`; `administrator`→rol, `disabled` invertido a `enabled`. Auditar el algoritmo de hash ANTES de tocar autenticación. |
| `tc_devices` | `tracking.device` | Conservar id y `uniqueid`→`unique_id`; conservar `attributes` como JSONB. |
| `tc_positions` | `tracking.position` | `fixtime`→`recorded_at`, `servertime`→`received_at`; `attributes` JSONB; conservar histórico y rango de fechas. |
| última posición por device | `tracking.current_position` | Derivar del histórico. |
| `tc_events` | `tracking.tracking_event` | `eventtime`→`occurred_at`. |
| `tc_user_device` | `operations.assignment` o equivalente | Asignación usuario-dispositivo; confirmar con contrato. |
| `tc_device_health` | `telemetry.device_health` | Mapear columnas. |
| `tc_mobile_messages`, `tc_recovery_event` | a definir | Decidir migrar o archivar. |
| `tc_positions_bak_*`, `_bak_*` | no migrar | Archivo histórico. |

Requisitos de la migración (plan, sección 13): conservar ids de usuarios y
dispositivos, `uniqueId`, credenciales, históricos, atributos, tokens de
recuperación y configuraciones; no cambiar contraseñas en masa; no perder
fechas históricas; verificar conteos OLD vs NEW.

### 4.4 Operación y seguridad

- Separar roles: rol de migración, rol de aplicación con mínimo privilegio
  (el nuevo backend no debe conectarse como superusuario).
- Definir backup automático + retención + verificación de restauración
  periódica en el entorno nuevo.
- Vigilar el disco durante FASE 3 (hoy 4.5 GB libres).

### 4.5 Validación de FASE 3

- Repetir la comparación OLD vs NEW del corte exacto (mismos conteos, hashes y
  última posición por dispositivo usados en FASE 1).
- Verificar particiones sin escaneos completos y planes con `EXPLAIN
  (ANALYZE, BUFFERS)` en consultas críticas.
- Probar restauración del entorno nuevo.

## 5. Dependencias

| Depende de | Para qué |
|---|---|
| FASE 2 (Arquitectura y contratos) | Definir qué tablas consume `/api/v1` y el mapeo exacto (assignments, sesiones, alertas). |
| Agente BACKEND | DSN, esquema y roles de la app. |
| Agente APP/WEB | Compatibilidad de datos de `tc_mobile_messages`, push y contratos. |
| Agente INFRA | Instancia PostgreSQL nueva (versión, recursos, puerto, volumen, backups). |
| RELEASE/ORQUESTADOR | Checkpoint de cutover y autorización para dump final. |

## 6. Riesgos abiertos

- Algoritmo de hash de contraseñas sin auditar; no modernizar sin evidencia.
- Delta de datos en vivo entre dump y cutover: requiere dump nuevo o CDC.
- Posible dependencia oculta de tablas custom (`tc_mobile_messages`,
  `tc_recovery_event`) en la App actual.
- Uso de superusuario por la aplicación actual; corregir en la base nueva.
- Presupuesto de disco reducido (4.5 GB).

## 7. Comandos útiles

```bash
# Conexión de lectura a producción (NO modificar)
docker exec dmj-db psql -U traccar -d traccar

# Conteos de control
docker exec dmj-db psql -U traccar -d traccar -tAc "select count(*) from tc_positions;"

# Hypertables y chunks
docker exec dmj-db psql -U traccar -d traccar -c \
  "select * from timescaledb_information.hypertables;"
```

## 8. Bitácora

- 2026-09-25: FASE 1 completada. Preflight registrado, dump custom + roles +
  SHA-256, restauración de prueba exitosa con validación OLD vs NEW idéntica
  contra la foto del dump, limpieza de la base scratch y evidencia en
  `docs/migration/BACKUP-EVIDENCIA.md`. Siguiente: FASE 3 depende de las
  decisiones de plataforma y contratos de FASE 2.
