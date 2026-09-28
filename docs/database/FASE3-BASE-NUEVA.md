# FASE 3 (parte 1) - Base de datos nueva PostgreSQL 18 + TimescaleDB

Fecha: 2026-09-25
Alcance: levantar la base nueva de la plataforma, aplicar el esquema `dmt_*` y verificar.
Producción: **no modificada** (contenedor `dmj-db`, DB `traccar`, `traccar_qa`, servicios intactos).
Sin commits.

---

## 1. Resumen

| Elemento | Valor verificado |
|---|---|
| Contenedor | `dmt-db` (healthy) |
| Imagen | `timescale/timescaledb:2.30.1-pg18` |
| Puerto host | `127.0.0.1:5443` -> `5432` |
| Base / usuario | `dmujeres` / `dmt` |
| Motor | PostgreSQL 18.6 (Alpine, x86_64) |
| Extensiones | `timescaledb` 2.30.1, `pg_stat_statements` 1.12, `plpgsql` 1.0 |
| Tablas lógicas | 22 (+4 particiones de `tracking`) |
| Índices | 19 `idx_dmt_*`, 1 `brin_dmt_*`, 38 `uq_dmt_*` |
| Claves foráneas | 23 objetos físicos (19 lógicas; las de tablas particionadas se copian) |
| Hypertables | 0 (el esquema usa particionado declarativo nativo, ver `ESQUEMA-NUEVO.md`) |
| Volumen | `dmt_db_datos` (local, persistente) |
| Datos de humo | insertados, verificados y eliminados; base vacía de datos de prueba |

---

## 2. Archivos creados y modificados

Creados:

- `infrastructure/development/docker-compose.yml` - servicio `dmt-db`.
- `.env` (raíz, permisos `600`) - credenciales de la base nueva. Clave generada con
  `openssl rand -hex 24`. **No se versiona, no se copia a documentos ni a `.env.example`.**
- `.gitignore` (raíz) - ignora `.env`, `*.env`, `node_modules/`, `dist/`, `build/`,
  `*.log`, `*.dump`.
- `docs/database/FASE3-BASE-NUEVA.md` - este informe.

Modificados: ninguno. Los 9 archivos de `database/schema/` se aplicaron sin cambios;
no hubo errores que corregir.

Producción: sin cambios. No se reinició, recreó ni modificó `dmj-db` ni ningún otro
contenedor existente; no se usó `docker system prune`.

---

## 3. Decisiones operativas

1. **Imagen fijada por etiqueta** `2.30.1-pg18` (no `latest`), conforme a la decisión
   tomada: PostgreSQL 18 + TimescaleDB 2.30.1.
2. **PGDATA explícito.** La imagen oficial de PostgreSQL 18 cambió el directorio de datos
   a `/var/lib/postgresql/18/docker` y el `VOLUME` a `/var/lib/postgresql`. Para que el
   volumen nombrado pedido (`dmt_db_datos:/var/lib/postgresql/data`) sea el directorio de
   datos real, el servicio fija `PGDATA=/var/lib/postgresql/data`. Verificado:
   `show data_directory;` -> `/var/lib/postgresql/data`.
3. **Volumen con nombre explícito** (`name: dmt_db_datos`) para que no herede el prefijo
   del proyecto Compose.
4. **`shared_preload_libraries=timescaledb,pg_stat_statements`** y `max_connections=200`.
   Con el preload activo, `pg_stat_statements` recolecta desde el arranque
   (consulta de control con 167 filas al momento de la verificación).
5. **Healthcheck** `pg_isready -h 127.0.0.1 -U dmt -d dmujeres` cada 10 s, 6 reintentos,
   `start_period` 30 s.
6. El `env_file` apunta a `/home/DMujeres-Tracking/.env` (absoluto) y el healthcheck usa
   `${POSTGRES_USER:-dmt}` / `${POSTGRES_DB:-dmujeres}` con valores por defecto para que
   `docker compose config` valide sin avisos aun cuando el archivo de variables vive fuera
   del directorio del compose.

---

## 4. Puesta en marcha

```sh
docker compose -f /home/DMujeres-Tracking/infrastructure/development/docker-compose.yml up -d
```

```text
 Volume "dmt_db_datos"  Created
 Container dmt-db  Creating
 Container dmt-db  Created
 Container dmt-db  Starting
 Container dmt-db  Started
```

Healthcheck:

```text
intento 1: starting
intento 2: healthy
```

---

## 5. Aplicación del esquema (orden estricto)

```sh
for f in 00_compat.sql 01_esquemas.sql 02_iam.sql 03_tracking.sql 04_telemetry.sql \
         05_operations.sql 06_audit.sql 07_system.sql 08_indices.sql; do
  docker exec -i dmt-db psql -U dmt -d dmujeres -v ON_ERROR_STOP=1 \
    < "/home/DMujeres-Tracking/database/schema/$f"
done
```

Los 9 archivos terminaron en `COMMIT` sin errores (`ON_ERROR_STOP=1`). En PostgreSQL 18,
`system.uuidv7()` toma la rama nativa:

```text
    funcion    |           prosrc
---------------+----------------------------
 system.uuidv7 | SELECT pg_catalog.uuidv7()
```

Registro de versiones aplicadas (`system.dmt_version_esquema`): 9 filas, versiones 00 a 08
con su archivo y fecha.

---

## 6. Verificación del esquema

### 6.1 Versión y extensiones

```sql
select version();
select extname, extversion from pg_extension where extname in ('timescaledb','pg_stat_statements') order by 1;
```

```text
 PostgreSQL 18.6 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit

      extname       | extversion
--------------------+------------
 pg_stat_statements | 1.12
 timescaledb        | 2.30.1
```

### 6.2 Tablas por esquema

```sql
SELECT table_schema, string_agg(table_name, ', ' ORDER BY table_name)
FROM information_schema.tables
WHERE table_schema IN ('iam','tracking','telemetry','operations','audit','system')
  AND table_type = 'BASE TABLE'
GROUP BY 1 ORDER BY 1;
```

```text
  esquema   |                                                                       tablas
------------+----------------------------------------------------------------------------------------------------------------------------------------------------
 audit      | dmt_auditoria
 iam        | dmt_clave_firma, dmt_rol, dmt_sesion, dmt_token_fcm, dmt_token_revocado, dmt_usuario, dmt_usuario_rol
 operations | dmt_alerta, dmt_asignacion, dmt_jornada, dmt_jornada_tramo
 system     | dmt_configuracion, dmt_migracion_mapa, dmt_version_esquema
 telemetry  | dmt_bateria, dmt_salud_dispositivo, dmt_senal
 tracking   | dmt_dispositivo, dmt_evento, dmt_evento_2026_09, dmt_evento_2026_10, dmt_posicion, dmt_posicion_2026_09, dmt_posicion_2026_10, dmt_posicion_actual
```

22 tablas lógicas (los conteos de `tracking` incluyen las 4 particiones).

### 6.3 Particiones (`pg_inherits`)

16 objetos: 4 particiones de tabla (`dmt_posicion_2026_09/10`,
`dmt_evento_2026_09/10`) y 12 índices propagados por PostgreSQL a cada partición
(PK, `idx_dmt_*` y `brin_dmt_*`). Salida completa en el registro de la sesión.
La partición `dmt_posicion_2026_08` necesaria para el histórico legado
(2026-08-17) queda **pendiente de crear antes de la carga** (plantilla comentada en
`03_tracking.sql`), igual que `2026_11` antes de noviembre.

### 6.4 Hypertables

```sql
select * from timescaledb_information.hypertables;
```

```text
(0 rows)
```

Es lo esperado: la base nueva usa `PARTITION BY RANGE` nativo, no hypertables
(decisión documentada en `docs/database/ESQUEMA-NUEVO.md`, punto 4). TimescaleDB queda
disponible en la imagen y preload por si una fase posterior lo requiere.

### 6.5 Índices y claves foráneas

```text
 idx_dmt | brin_dmt | uq_dmt
---------+----------+--------
      19 |        1 |     38

 fk_totales
------------
         23
```

Listado `idx_dmt_*`/`brin_dmt_*`: 20 filas (19 `idx_dmt_` + `brin_dmt_posicion_tiempo`),
2 en `audit`, 2 en `iam`, 5 en `operations`, 4 en `telemetry` y 7 en `tracking`
(incluye `idx_dmt_evento_dispositivo_tiempo`, `idx_dmt_evento_tipo_tiempo`,
`idx_dmt_posicion_dispositivo_tiempo`, `brin_dmt_posicion_tiempo`,
`idx_dmt_dispositivo_ultima_conexion`). Los 23 objetos FK físicos corresponden a las
19 FK lógicas; las de tablas particionadas se copian a cada partición.

---

## 7. Prueba de humo

Se insertó una entidad completa: usuario, rol, usuario_rol, dispositivo, posición,
posición_actual, batería, jornada, evento, auditoría, configuración y mapa de migración.
Todas las inserciones devolvieron id (identidades desde 1).

Verificación cruzada (consulta con 12 `JOIN`):

```text
 nombre_usuario |   rol    | identificador | posicion | posicion_actual | bateria_pct | jornada |   evento    |  auditoria  |   config    |       tabla_origen
----------------+----------+---------------+----------+-----------------+-------------+---------+-------------+-------------+-------------+---------------------------
 humo.dmt       | humo_rol | HUMO-DMT-0001 |        1 |               1 |          87 | abierta | humo_prueba | humo_prueba | humo.prueba | traccar.public.tc_devices
```

Enrutado de particiones (posición y evento de 2026-09):

```text
    objeto    |           particion
--------------+-------------------------------
 dmt_posicion | tracking.dmt_posicion_2026_09
 dmt_evento   | tracking.dmt_evento_2026_09
```

Limpieza: `TRUNCATE` de las 22 tablas con `RESTART IDENTITY CASCADE`. Conteo final:
0 filas en todas las tablas de datos; `system.dmt_version_esquema` conserva sus 9 filas
de metadatos (no es dato de prueba).

---

## 8. UUIDv7

```sql
SELECT pg_catalog.uuidv7() AS nativo_pg18, system.uuidv7() AS envoltorio_system;
SELECT substring(u::text, 15, 1) AS version_uuid,
       substring(u::text, 20, 1) AS variante_rfc4122,
       to_timestamp(('x' || translate(substring(u::text, 1, 13), '-', ''))::bit(48)::bigint / 1000.0) AS instante_embebido
FROM (SELECT system.uuidv7() AS u) t;
```

```text
             nativo_pg18              |          envoltorio_system
--------------------------------------+--------------------------------------
 01a0dba5-70b2-7884-80e0-3485f6f1e382 | 01a0dba5-70b2-78e0-93e2-bc55cd79d898

 version_uuid | variante_rfc4122 |     instante_embebido
--------------+------------------+----------------------------
 7            | b                | 2026-09-26 02:57:27.732+00
```

Y dos llamadas separadas 5 ms: `uuid_despues > uuid_antes` -> `t` (orden temporal).
`system.uuidv7()` es envoltorio de `pg_catalog.uuidv7()` en esta instalación.

---

## 9. Persistencia del volumen

### 9.1 Reinicio del contenedor

`docker restart dmt-db` -> healthy en el intento 3. Datos de humo intactos:

```text
 usuarios | posiciones | eventos | mapa
----------+------------+---------+------
        1 |          1 |       1 |    1
```

### 9.2 Recreación (`docker compose down` + `up -d`)

```text
 Container dmt-db  Removed
 Network dmt_default  Removed
--- contenedor:
dmt-db eliminado
--- volumen:
dmt_db_datos | local
```

Tras `up -d`, healthy en el intento 3. Datos intactos:

```text
 usuarios | posiciones | eventos | mapa
----------+------------+---------+------
        1 |          1 |       1 |    1
```

El volumen `dmt_db_datos` sobrevive a la eliminación del contenedor. Solo se eliminó con
`RESTART IDENTITY CASCADE` el contenido de prueba, ya con la persistencia demostrada.

---

## 10. Operación

- Estado final: `docker ps` -> `dmt-db | timescale/timescaledb:2.30.1-pg18 |
  Up ... (healthy) | 127.0.0.1:5443->5432/tcp`.
- `docker compose config` valida sin errores (salida redactada: la clave nunca se
  documenta ni se imprime).
- Apagado sin pérdida de datos:

```sh
docker compose -f /home/DMujeres-Tracking/infrastructure/development/docker-compose.yml down
```

  El contenedor y la red se eliminan; el volumen `dmt_db_datos` permanece y la base
  vuelve completa al hacer `up -d`. **No usar `down -v`** salvo que se quiera borrar la
  base a propósito.
- Conexión: `docker exec -it dmt-db psql -U dmt -d dmujeres`
  (o `psql "postgresql://dmt@127.0.0.1:5443/dmujeres"` con la clave del `.env`).

---

## 11. Seguridad

- La contraseña vive únicamente en `/home/DMujeres-Tracking/.env` (permisos `600`,
  propietario `opencode`). No aparece en `.env.example`, en este documento, en el compose
  ni en el historial de comandos.
- La salida de `docker compose config` se manipuló con redacción (`<OCULTO>`) para
  evidencia, porque Compose resuelve `env_file` al imprimir la configuración.
- Producción intacta: `dmj-db` (PG17 + Timescale 2.29) sigue en `127.0.0.1:5433` con la
  DB `traccar`; no se tocaron contenedores ni volúmenes ajenos.

---

## 12. Riesgos y dudas abiertas

1. **PGDATA fijado a `/var/lib/postgresql/data`.** Es la ruta pedida y así el volumen
   nombrado es el datadir real; si en el futuro se migra de imagen con la convención
   PG18 (`/var/lib/postgresql/18/docker`), habrá que reapuntar el volumen explícitamente.
2. **Particiones.** Crear `dmt_posicion_2026_08` antes de cargar el histórico
   (arranca 2026-08-17) y `2026_11` antes de noviembre; si no, las escrituras fuera de
   rango fallan. Conviene automatizar la creación mensual (FASE 3 parte 2 / FASE 8).
3. **`max_connections=200`** es un valor razonable inicial; ajustar según la migración
   (cargas por lotes) y el pool de la API.
4. **Hypertables descartadas.** Si la migración del histórico busca el rendimiento de
   TimescaleDB, decidirlo **antes** de copiar `tc_positions`/`tc_events`/`tc_actions`;
   el esquema actual es declarativo y no usa la extensión.
5. **TimescaleDB presente sin uso.** El plan fijaba PostgreSQL 18.6; la imagen añade
   Timescale 2.30.1. No afecta al esquema, pero conviene registrar la decisión (ADR) de
   mantener la extensión disponible.
6. **Sin repositorio git todavía.** El `.gitignore` queda preparado; conviene
   `git init` y primer commit cuando el orquestador lo autorice (no se hizo aquí).

---

## 13. Reproducción

```sh
# 1) datos y contenedor
docker compose -f /home/DMujeres-Tracking/infrastructure/development/docker-compose.yml up -d

# 2) esquema
for f in 00_compat.sql 01_esquemas.sql 02_iam.sql 03_tracking.sql 04_telemetry.sql \
         05_operations.sql 06_audit.sql 07_system.sql 08_indices.sql; do
  docker exec -i dmt-db psql -U dmt -d dmujeres -v ON_ERROR_STOP=1 \
    < "/home/DMujeres-Tracking/database/schema/$f"
done

# 3) comprobación
docker exec dmt-db psql -U dmt -d dmujeres -c "select version();"
docker exec dmt-db psql -U dmt -d dmujeres -c "select extversion from pg_extension where extname='timescaledb';"
docker ps --filter name=dmt-db
```

Siguiente: FASE 3 parte 2 (restauración / carga del histórico en la base nueva).
