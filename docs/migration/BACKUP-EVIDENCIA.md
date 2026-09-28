# FASE 1 — Backup de PostgreSQL y restauración de prueba verificada

Evidencia completa de la fase. Fecha de ejecución: 2026-09-25 (hora local -05).
Directorio de trabajo: `/home/DMujeres-backups/migration/20260925-205714/`
(`TS="$(cat /home/DMujeres-backups/migration/.ultima)"`).

Producción **no** fue detenida, modificada ni borrada. No se hizo `DROP` ni
cambio alguno sobre la base `traccar`. No se tocaron archivos de
`/DMujeres-Tracking`.

## 1. Entorno confirmado (preflight)

| Dato | Valor real |
|---|---|
| Contenedor | `dmj-db` (`timescale/timescaledb:latest-pg17`, healthy, 6 días) |
| Puerto | `127.0.0.1:5433 -> 5432` |
| PostgreSQL | 17.10 (Alpine, x86_64) |
| TimescaleDB | 2.29.1 (extensión activa) |
| Base de datos | `traccar` |
| Usuario | `traccar` (superusuario, creador de DBs) |
| Tamaño de la base | 61 MB |
| Otras bases en el contenedor | `postgres`, `traccar_qa` (NO tocadas) |
| Disco host | 78 GB, 73 GB usados, **4.5 GB libres (95 %)** |

Hypertables detectadas (`timescaledb_information.hypertables`):

| Hypertable | Chunks | Dimensiones | Intervalo de tiempo | Compresión |
|---|---|---|---|---|
| `tc_actions` | 7 | 2 (tiempo + `deviceid`) | 7 días | no |
| `tc_events` | 29 | 2 (tiempo + `deviceid`) | 7 días | no |
| `tc_positions` | 9 | 2 (tiempo + `deviceid`) | 7 días | no |

Conteos en el preflight (20:58:33): `tc_users`=5, `tc_devices`=9,
`tc_positions`=26 416, `tc_events`=5 072, `tc_user_device`=20.

Evidencia: `evidence/preflight.txt`.

## 2. Backup lógico (comandos ejecutados)

```bash
TS="$(cat /home/DMujeres-backups/migration/.ultima)"
D="/home/DMujeres-backups/migration/$TS/db"

# 2.1 Dump lógico en formato custom (completo, consistente por MVCC)
docker exec dmj-db pg_dump -U traccar -d traccar -Fc > "$D/traccar_pre_cutover.dump"

# 2.2 Roles y configuración global
docker exec dmj-db pg_dumpall -U traccar --globals-only > "$D/postgres_globals.sql"

# 2.3 Hashes SHA-256 de todos los archivos del respaldo
cd "$D" && sha256sum traccar_pre_cutover.dump postgres_globals.sql \
  pg_dump.stderr pg_dumpall.stderr > SHA256SUMS
```

Resultado: `pg_dump` rc=0 y `pg_dumpall` rc=0. El dump tardó ~1 segundo.
Advertencia no bloqueante de `pg_dump`: FK circulares en la tabla interna
`continuous_agg` de TimescaleDB (`_timescaledb_catalog`); no afectó la
restauración (el usuario `traccar` es superusuario). No hubo pérdida ni error.

### Archivos, tamaños y hashes

| Archivo | Bytes | SHA-256 |
|---|---|---|
| `traccar_pre_cutover.dump` | 3 892 173 | `774940cd009a0afb595a23dfa2bbc2813d4f81f7f70a3a2e9f6be43bc26b785e` |
| `postgres_globals.sql` | 944 | `c6e13e9fe7f89e277a037f1964f8923c73d864843f1a4dcd97b1af832b6f8234` |
| `pg_dump.stderr` | 334 | `095023068a9a31435072b848f15b34d66536eec00b12341110324dccf0887474` |
| `pg_dumpall.stderr` | 0 | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `SHA256SUMS` | 343 | (índice, no se incluye a sí mismo) |

Verificación estructural del dump: `pg_restore -l` lista 652 objetos
(`TOC Entries: 652`) e incluye `EXTENSION timescaledb`, tablas, chunks de
hypertable, datos y ACLs. Verificación criptográfica:
`sha256sum -c SHA256SUMS` → **OK** en los 4 archivos (repetida al final).

## 3. Restauración de prueba

Base scratch creada en el **mismo contenedor** (nunca en producción):
`dmj_restore_20260925-205714`.

```bash
docker exec dmj-db createdb -U traccar dmj_restore_20260925-205714        # rc=0
docker exec -i dmj-db pg_restore -U traccar \
  -d dmj_restore_20260925-205714 --no-owner \
  < "$D/traccar_pre_cutover.dump"                                          # rc=0
```

Resultado: **exit code 0, sin un solo error ni advertencia**. Al ser mismo
motor y misma versión de TimescaleDB (17.10 / 2.29.1) en la misma imagen, no
hizo falta `timescaledb_pre_restore()` / `timescaledb_post_restore()` ni
restaurar sin las llamadas de hypertable. Las 3 hypertables y sus chunks
quedaron idénticas (7/29/9). Procedimiento alternativo documentado en
`evidence/restore-test.txt` por si en FASE 3 se restaura contra otra versión
de TimescaleDB.

Evidencia: `evidence/restore-test.txt`.

## 4. Validación OLD vs NEW

Producción está viva: entre el dump (20:58:50) y la validación (21:00:21)
entraron 12 posiciones y 1 evento nuevos. Por eso la validación exacta se hace
contra la **foto consistente del dump** con corte `id <= max(id)` de la
restauración, y el delta en vivo se mide y explica aparte.

### 4.1 Conteos e igualdad de contenido contra la foto del dump

| Tabla | OLD (foto) | NEW | Conteo igual | md5 contenido igual |
|---|---|---|---|---|
| `tc_users` | 5 | 5 | SI | SI (`621b58a7…`) |
| `tc_positions` | 26 417 | 26 417 | SI | SI (`3eeedd91…`) |
| `tc_events` | 5 076 | 5 076 | SI | SI (`ff0129f4…`) |
| `tc_user_device` | 20 | 20 | SI | SI (`88f5d564…`) |
| `tc_devices` | 9 | 9 | SI | Solo columnas volátiles (ver 4.2) |

El hash md5 es sobre `string_agg` ordenado de todas las filas (contenido
completo de la tabla), no solo el conteo.

### 4.2 Diferencia explicada en `tc_devices`

Los dispositivos 52, 56 y 59 difieren entre OLD (ahora) y NEW (foto) **solo**
en `status`, `lastupdate` y `positionid`. Prueba de que son actualizaciones
posteriores al dump: en producción sus `positionid` apuntan a 88 256 / 88 257 /
88 255, todos mayores que `max(id)=88243` del dump, es decir, posiciones
creadas después del corte por el tracker en vivo. El hash excluyendo las tres
columnas volátiles es idéntico
(`1df9b3305d9fde4e751044377109182f`). No es pérdida de datos del backup.

### 4.3 Resto de comprobaciones

| Comprobación | OLD | NEW | Resultado |
|---|---|---|---|
| Duplicados de `tc_devices.uniqueid` | 0 | 0 | OK |
| Última posición por dispositivo | 7 dispositivos | idénticas | OK (foto vs restauración) |
| Usuarios habilitados (`disabled=false`) | 5 | 5 | OK |
| Administradores (`administrator=true`) | 1 | 1 | OK |
| Rango `fixtime` | 2026-09-22 02:58:01 → 2026-09-25 20:58:34 | idéntico | OK |
| Rango `servertime` | 2026-09-22 02:58:04.276 → 2026-09-25 22:03:27.289673 | idéntico | OK |
| Hypertables/chunks (`tc_actions`/`tc_events`/`tc_positions`) | 7 / 29 / 9 | 7 / 29 / 9 | OK |

Delta en vivo posterior al dump (no es fallo del backup, se recuperará con un
nuevo backup o CDC en el cutover): +12 `tc_positions` (ids 88244–88255) y
+1 `tc_events` (id 32174).

### 4.4 Decisión

**BACKUP VÁLIDO.** La restauración de prueba pasó con conteos y contenido
idénticos contra la foto del dump; la única diferencia observada está
totalmente explicada por escrituras en vivo posteriores al corte.

Evidencia: `evidence/validation.txt` y script reproducible
`evidence/validate_old_vs_new.sh`.

## 5. Limpieza

Tras guardar la evidencia se eliminó la base scratch para no consumir disco:

```bash
docker exec dmj-db dropdb -U traccar dmj_restore_20260925-205714   # rc=0
```

Bases restantes en `dmj-db`: `postgres`, `traccar`, `traccar_qa`.
No se tocó nada más. Espacio final del host: 4.5 GB libres (95 % usado).
Evidencia: sección CLEANUP de `evidence/restore-test.txt`.

## 6. Cómo se restauraría en caso de rollback (procedimiento ensayado)

El rollback solo lo ejecuta el agente RELEASE tras el checkpoint de cutover
(FASE 10), nunca durante el desarrollo.

```bash
TS="$(cat /home/DMujeres-backups/migration/.ultima)"
D="/home/DMujeres-backups/migration/$TS/db"

# 1) Verificar integridad ANTES de restaurar
cd "$D" && sha256sum -c SHA256SUMS

# 2) Crear base destino (no reutilizar nombres en uso) y restaurar
docker exec dmj-db createdb -U traccar traccar_rollback
docker exec -i dmj-db pg_restore -U traccar -d traccar_rollback --no-owner \
  < "$D/traccar_pre_cutover.dump"

# 3) Solo si se reconstruye un clúster vacío, recrear roles globales
docker exec -i dmj-db psql -U traccar -d postgres < "$D/postgres_globals.sql"
```

Consideraciones:
- `postgres_globals.sql` recrea roles; sobre un clúster que ya los tiene puede
  dar errores de duplicado. Revisarlo antes y aplicarlo solo en un clúster
  nuevo o filtrando roles inexistentes.
- El dump ya incluye `CREATE EXTENSION timescaledb`; no crear la extensión
  antes de restaurar.
- `--no-owner` evita dependencias de propietarios; los objetos quedan del
  usuario que ejecuta la restauración (`traccar`).
- Al restaurar, repetir la validación OLD vs NEW de la sección 4 para el punto
  de corte exacto del rollback.
- El backup es lógico (no PITR). Las escrituras posteriores al dump no están
  incluidas: antes del cutover real hay que generar un dump nuevo o activar
  CDC/doble escritura (FASE 2/3/10).

## 7. Verificación final

```text
sha256sum -c SHA256SUMS
traccar_pre_cutover.dump: OK
postgres_globals.sql: OK
pg_dump.stderr: OK
pg_dumpall.stderr: OK

df -h /
Filesystem  Size  Used Avail Use% Mounted on
/dev/sda1   78G   73G  4.5G  95% /
```

## 8. Artefactos de esta fase

- `/home/DMujeres-backups/migration/20260925-205714/db/traccar_pre_cutover.dump`
- `/home/DMujeres-backups/migration/20260925-205714/db/postgres_globals.sql`
- `/home/DMujeres-backups/migration/20260925-205714/db/SHA256SUMS`
- `/home/DMujeres-backups/migration/20260925-205714/evidence/preflight.txt`
- `/home/DMujeres-backups/migration/20260925-205714/evidence/restore-test.txt`
- `/home/DMujeres-backups/migration/20260925-205714/evidence/validation.txt`
- `/home/DMujeres-backups/migration/20260925-205714/evidence/validate_old_vs_new.sh`
- Este documento: `/home/DMujeres-Tracking/docs/migration/BACKUP-EVIDENCIA.md`
