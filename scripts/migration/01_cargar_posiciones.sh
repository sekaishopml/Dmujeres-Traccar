#!/usr/bin/env bash
# ============================================================================
# 01_cargar_posiciones.sh - Extrae el historico unificado y lo carga
# Proyecto: DMujeres Tracking - FASE 3 parte 2
# ============================================================================
# Flujo:
#   1. Restaura los dumps de $DMT_DUMPS_DIR en una base scratch propia (nunca
#      en traccar ni traccar_qa) verificando cada dump con pg_restore -l.
#   2. Copia (solo SELECT / COPY TO) tc_positions de la base viva y de QA.
#   3. Deduplica por (deviceid, fixtime) con prioridad:
#        traccar.tc_positions > tc_positions_bak_20260903 > traccar_qa >
#        dumps (a igualdad de contenido, gana el dump mas reciente).
#   4. Exporta el conjunto unificado a CSV.
#   5. Carga el CSV en system.dmt_staging_posicion de la base nueva.
#   6. Ejecuta 01_cargar_posiciones.sql (lotes + system.dmt_migracion_mapa).
#   7. Elimina la scratch (salvo DMT_KEEP_SCRATCH=1).
#
# Requisitos: psql, pg_restore y permiso CREATE DATABASE en el cluster legado.
# pg_restore usa las variables libpq estandar (PGUSER, PGHOST, PGPORT,
# PGPASSWORD); definir PGUSER si el usuario del sistema no es el de la base.
# Si el host no tiene pg_restore, ejecutar dentro del contenedor de la base
# legada (docker exec -i <contenedor> bash -s < 01_cargar_posiciones.sh).
#
# Variables (default entre parentesis):
#   DMT_LEGACY_DB     (traccar)       base viva; SOLO LECTURA
#   DMT_LEGACY_QA_DB  (traccar_qa)    base QA; SOLO LECTURA
#   DMT_DUMPS_DIR     (/var/backups/dmj)
#   DMT_SCRATCH_DB    (dmt_etl_scratch)
#   DMT_WORK_DIR      (/tmp/dmt_etl)
#   DMT_CSV           ($DMT_WORK_DIR/posiciones_unificadas.csv)
#   DMT_BATCH_SIZE    (5000)
#   DMT_LEGACY_PSQL   ("psql -d $DMT_LEGACY_DB")
#   DMT_LEGACY_QA_PSQL("psql -d $DMT_LEGACY_QA_DB")
#   DMT_NEW_PSQL      ("psql" - debe incluir la conexion a la base nueva,
#                      p.ej. "psql -U traccar -d tracking")
#   DMT_KEEP_SCRATCH  (0)
# ============================================================================
set -euo pipefail

DMT_LEGACY_DB="${DMT_LEGACY_DB:-traccar}"
DMT_LEGACY_QA_DB="${DMT_LEGACY_QA_DB:-traccar_qa}"
DMT_DUMPS_DIR="${DMT_DUMPS_DIR:-/var/backups/dmj}"
DMT_SCRATCH_DB="${DMT_SCRATCH_DB:-dmt_etl_scratch}"
DMT_WORK_DIR="${DMT_WORK_DIR:-/tmp/dmt_etl}"
DMT_CSV="${DMT_CSV:-$DMT_WORK_DIR/posiciones_unificadas.csv}"
DMT_BATCH_SIZE="${DMT_BATCH_SIZE:-5000}"
DMT_LEGACY_PSQL="${DMT_LEGACY_PSQL:-psql -d $DMT_LEGACY_DB}"
DMT_LEGACY_QA_PSQL="${DMT_LEGACY_QA_PSQL:-psql -d $DMT_LEGACY_QA_DB}"
DMT_NEW_PSQL="${DMT_NEW_PSQL:-psql}"
DMT_KEEP_SCRATCH="${DMT_KEEP_SCRATCH:-0}"
SQL_DIR="$(cd "$(dirname "$0")" && pwd)"
LOGDIR="$DMT_WORK_DIR/logs"

mkdir -p "$DMT_WORK_DIR" "$LOGDIR"

COLS="id, protocol, deviceid, servertime, devicetime, fixtime, valid, latitude, longitude, altitude, speed, course, address, attributes, accuracy, network, geofenceids"

log() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }

legacy() { $DMT_LEGACY_PSQL -v ON_ERROR_STOP=1 "$@"; }
legacy_qa() { $DMT_LEGACY_QA_PSQL -v ON_ERROR_STOP=1 "$@"; }
scratch() { $DMT_LEGACY_PSQL -v ON_ERROR_STOP=1 -d "$DMT_SCRATCH_DB" "$@"; }
nueva() { $DMT_NEW_PSQL -v ON_ERROR_STOP=1 "$@"; }

# ---------------------------------------------------------------------------
# 0) Scratch limpia
# ---------------------------------------------------------------------------
log "Creando scratch $DMT_SCRATCH_DB (nunca en $DMT_LEGACY_DB / $DMT_LEGACY_QA_DB)"
legacy -q -c "DROP DATABASE IF EXISTS $DMT_SCRATCH_DB WITH (FORCE)"
legacy -q -c "CREATE DATABASE $DMT_SCRATCH_DB"

scratch -q -c "CREATE SCHEMA IF NOT EXISTS _timescaledb_internal;"
scratch -q -c "CREATE TABLE public.tc_positions (
    id integer, protocol varchar(128), deviceid integer,
    servertime timestamp, devicetime timestamp, fixtime timestamp,
    valid boolean, latitude double precision, longitude double precision,
    altitude double precision, speed double precision, course double precision,
    address varchar(512), attributes varchar(4000), accuracy double precision,
    network varchar(4000), geofenceids varchar(128));"
scratch -q -c "CREATE TABLE public.tc_positions_bak_20260903 (LIKE public.tc_positions);"

# ---------------------------------------------------------------------------
# 1) Dumps: verificacion de TOC y restauracion de posiciones en la scratch
# ---------------------------------------------------------------------------
DUMP_OK=0
DUMP_MALOS=0
: > "$LOGDIR/dumps-invalidos.txt"
for f in $(ls -1 "$DMT_DUMPS_DIR"/*.dump 2>/dev/null | sort); do
    base=$(basename "$f")
    tag=$(echo "$base" | sed 's/^traccar-//; s/\.dump$//; s/[^A-Za-z0-9_]/_/g')
    if [ ! -s "$f" ]; then
        log "AVISO: $base tiene 0 bytes; se omite"
        echo "$base: 0 bytes" >> "$LOGDIR/dumps-invalidos.txt"
        DUMP_MALOS=$((DUMP_MALOS + 1))
        continue
    fi
    if ! pg_restore -l "$f" > "$LOGDIR/toc-$tag.txt" 2> "$LOGDIR/toc-$tag.err"; then
        log "AVISO: $base no es un dump valido; se omite ($(head -c 120 "$LOGDIR/toc-$tag.err"))"
        echo "$base: $(head -c 200 "$LOGDIR/toc-$tag.err")" >> "$LOGDIR/dumps-invalidos.txt"
        DUMP_MALOS=$((DUMP_MALOS + 1))
        continue
    fi
    grep -E "TABLE DATA _timescaledb_internal _hyper_1_[0-9]+_chunk" "$LOGDIR/toc-$tag.txt" \
        > "$LOGDIR/list-$tag.txt" || true
    nchunks=$(wc -l < "$LOGDIR/list-$tag.txt")

    scratch -q -c "DROP TABLE IF EXISTS public.pos_$tag; CREATE TABLE public.pos_$tag (LIKE public.tc_positions);"
    scratch -q -c "DROP TABLE IF EXISTS public.bak_$tag; CREATE TABLE public.bak_$tag (LIKE public.tc_positions);"
    while read -r line; do
        [ -n "$line" ] || continue
        chunk=$(echo "$line" | awk '{print $7}')
        scratch -q -c "DROP TABLE IF EXISTS _timescaledb_internal.$chunk; CREATE TABLE _timescaledb_internal.$chunk (LIKE public.tc_positions);"
    done < "$LOGDIR/list-$tag.txt"

    if ! pg_restore -a -L "$LOGDIR/list-$tag.txt" --no-owner --no-privileges \
        -d "$DMT_SCRATCH_DB" "$f" > "$LOGDIR/restore-$tag.log" 2>&1; then
        log "AVISO: fallo la restauracion de $base; se omite ($(head -c 120 "$LOGDIR/restore-$tag.log"))"
        echo "$base: $(head -c 200 "$LOGDIR/restore-$tag.log")" >> "$LOGDIR/dumps-invalidos.txt"
        scratch -q -c "DROP TABLE IF EXISTS public.pos_$tag; DROP TABLE IF EXISTS public.bak_$tag;"
        DUMP_MALOS=$((DUMP_MALOS + 1))
        continue
    fi
    while read -r line; do
        [ -n "$line" ] || continue
        chunk=$(echo "$line" | awk '{print $7}')
        scratch -q -c "INSERT INTO public.pos_$tag SELECT * FROM _timescaledb_internal.$chunk;"
        scratch -q -c "DROP TABLE _timescaledb_internal.$chunk;"
    done < "$LOGDIR/list-$tag.txt"

    # tc_positions_bak_20260903 es una tabla plana: pg_restore la carga siempre
    # en el mismo nombre, por eso se vacia antes y se mueve a bak_$tag.
    scratch -q -c "TRUNCATE public.tc_positions_bak_20260903;"
    if ! pg_restore -a -t tc_positions_bak_20260903 --no-owner --no-privileges \
        -d "$DMT_SCRATCH_DB" "$f" > "$LOGDIR/restorebak-$tag.log" 2>&1; then
        log "AVISO: fallo la restauracion de tc_positions_bak_20260903 en $base; se omite el dump"
        echo "$base (bak): $(head -c 200 "$LOGDIR/restorebak-$tag.log")" >> "$LOGDIR/dumps-invalidos.txt"
        scratch -q -c "DROP TABLE IF EXISTS public.pos_$tag; DROP TABLE IF EXISTS public.bak_$tag;"
        DUMP_MALOS=$((DUMP_MALOS + 1))
        continue
    fi
    scratch -q -c "INSERT INTO public.bak_$tag SELECT * FROM public.tc_positions_bak_20260903;"

    resumen=$(scratch -tAc "SELECT (SELECT count(*) FROM public.pos_$tag)
                                 || ' posiciones | ' ||
                                 (SELECT count(*) FROM public.bak_$tag)
                                 || ' en bak | chunks: $nchunks';")
    log "Dump $base -> $resumen"
    DUMP_OK=$((DUMP_OK + 1))
done

# ---------------------------------------------------------------------------
# 2) Fuentes vivas (solo lectura) -> scratch
# ---------------------------------------------------------------------------
log "Copiando tc_positions de $DMT_LEGACY_DB (SELECT) a la scratch"
scratch -q -c "DROP TABLE IF EXISTS public.u_prod; CREATE TABLE public.u_prod (LIKE public.tc_positions);"
legacy -c "COPY (SELECT $COLS FROM public.tc_positions) TO STDOUT" \
    | scratch -c "COPY public.u_prod ($COLS) FROM STDIN"

log "Copiando tc_positions de $DMT_LEGACY_QA_DB (SELECT) a la scratch"
scratch -q -c "DROP TABLE IF EXISTS public.u_qa; CREATE TABLE public.u_qa (LIKE public.tc_positions);"
legacy_qa -c "COPY (SELECT $COLS FROM public.tc_positions) TO STDOUT" \
    | scratch -c "COPY public.u_qa ($COLS) FROM STDIN"

# u_bak: todas las copias de tc_positions_bak_20260903 son identicas
# (verificado en FASE 3 parte 2); se usa el dump pre-purga o el primero no vacio.
scratch -q -c "DROP TABLE IF EXISTS public.u_bak; CREATE TABLE public.u_bak (LIKE public.tc_positions);"
bak_origen=""
for t in pre_purga_recorridos pre_cambio_usuarios; do
    n=$(scratch -tAc "SELECT count(*) FROM public.bak_$t;" 2>/dev/null || echo 0)
    if [ "${n:-0}" -gt 0 ]; then bak_origen="$t"; break; fi
done
if [ -z "$bak_origen" ]; then
    for d in "$LOGDIR"/list-*.txt; do
        [ -e "$d" ] || continue
        t=$(basename "$d" | sed 's/^list-//; s/\.txt$//')
        n=$(scratch -tAc "SELECT count(*) FROM public.bak_$t;" 2>/dev/null || echo 0)
        if [ "${n:-0}" -gt 0 ]; then bak_origen="$t"; break; fi
    done
fi
if [ -n "$bak_origen" ]; then
    scratch -q -c "INSERT INTO public.u_bak SELECT * FROM public.bak_$bak_origen;"
fi
log "u_bak cargada desde bak_${bak_origen:-ninguno}"

# ---------------------------------------------------------------------------
# 3) Conjunto unificado deduplicado por (deviceid, fixtime)
# ---------------------------------------------------------------------------
log "Construyendo conjunto unificado (prioridad prod > bak > qa > dumps)"
PRIORIDAD=(
    "1|traccar.tc_positions|u_prod"
    "2|traccar.tc_positions_bak_20260903|u_bak"
    "3|traccar_qa.tc_positions|u_qa"
    "4|dump traccar-20260925-030001|pos_20260925_030001"
    "5|dump traccar-20260924-030001|pos_20260924_030001"
    "6|dump traccar-20260923-030001|pos_20260923_030001"
    "7|dump traccar-pre-cambio-usuarios|pos_pre_cambio_usuarios"
    "8|dump traccar-20260922-030001|pos_20260922_030001"
    "9|dump traccar-pre-purga-recorridos|pos_pre_purga_recorridos"
    "10|dump traccar-20260922-003135|pos_20260922_003135"
    "11|dump traccar-20260919-125422|pos_20260919_125422"
)
UNION_SQL=""
for item in "${PRIORIDAD[@]}"; do
    prio=${item%%|*}
    resto=${item#*|}
    fuente=${resto%%|*}
    tabla=${resto##*|}
    existe=$(scratch -tAc "SELECT to_regclass('public.$tabla') IS NOT NULL;")
    if [ "$existe" = "t" ]; then
        [ -n "$UNION_SQL" ] && UNION_SQL="$UNION_SQL
    UNION ALL "
        UNION_SQL="$UNION_SQL    SELECT $prio AS prio, '$fuente'::text AS fuente, x.* FROM public.$tabla x"
    fi
done
if [ -z "$UNION_SQL" ]; then
    echo "ERROR: no hay ninguna fuente disponible" >&2
    exit 1
fi

scratch -q -c "DROP TABLE IF EXISTS public.u_final;
CREATE TABLE public.u_final AS
WITH todas AS (
$UNION_SQL
), ranked AS (
    SELECT *, row_number() OVER (
        PARTITION BY deviceid, fixtime
        ORDER BY prio, servertime DESC NULLS LAST, id DESC
    ) AS rn
    FROM todas
)
SELECT prio, fuente, id, protocol, deviceid, servertime, devicetime, fixtime, valid,
       latitude, longitude, altitude, speed, course, address, attributes, accuracy,
       network, geofenceids
FROM ranked WHERE rn = 1;
CREATE INDEX ON public.u_final (deviceid, fixtime);"

scratch -c "SELECT count(*) AS unificadas, count(DISTINCT deviceid) AS dispositivos,
                   min(fixtime) AS min_fx, max(fixtime) AS max_fx FROM public.u_final;"

# ---------------------------------------------------------------------------
# 4) Exportar CSV del conjunto unificado
# ---------------------------------------------------------------------------
log "Exportando CSV a $DMT_CSV"
scratch -c "COPY (
    SELECT id AS id_legado, deviceid, protocol, servertime, devicetime, fixtime,
           valid, latitude, longitude, altitude, speed, course, address,
           attributes, accuracy, network, geofenceids, fuente,
           ((deviceid = 50 AND latitude = 37.4220009 AND longitude = -122.0840607)
             OR id IN (73883,73887,73893,84216,84217,84219,77604,77605)) AS es_mock
    FROM public.u_final
    ORDER BY deviceid, fixtime
) TO STDOUT WITH (FORMAT csv, HEADER true)" > "$DMT_CSV"

# ---------------------------------------------------------------------------
# 5) Staging en la base nueva
# ---------------------------------------------------------------------------
log "Cargando staging en la base nueva"
nueva -q -c "CREATE TABLE IF NOT EXISTS system.dmt_staging_posicion (
    id_legado integer NOT NULL, deviceid integer NOT NULL,
    protocol varchar(128), servertime timestamp, devicetime timestamp,
    fixtime timestamp, valid boolean, latitude double precision,
    longitude double precision, altitude double precision, speed double precision,
    course double precision, address varchar(512), attributes varchar(4000),
    accuracy double precision, network varchar(4000), geofenceids varchar(128),
    fuente text, es_mock boolean NOT NULL DEFAULT false);"
nueva -q -c "TRUNCATE system.dmt_staging_posicion;"
nueva -c "\copy system.dmt_staging_posicion (id_legado, deviceid, protocol, servertime, devicetime, fixtime, valid, latitude, longitude, altitude, speed, course, address, attributes, accuracy, network, geofenceids, fuente, es_mock) FROM '$DMT_CSV' WITH (FORMAT csv, HEADER true)"

# ---------------------------------------------------------------------------
# 6) Carga final (lotes + mapa de migracion)
# ---------------------------------------------------------------------------
log "Ejecutando 01_cargar_posiciones.sql"
PGOPTIONS="-c dmt.batch_size=$DMT_BATCH_SIZE" \
    $DMT_NEW_PSQL -v ON_ERROR_STOP=1 -f "$SQL_DIR/01_cargar_posiciones.sql"

nueva -c "SELECT count(*) AS filas_cargadas, count(DISTINCT dispositivo_id) AS dispositivos,
                 min(registrado_en) AS min_reg, max(registrado_en) AS max_reg
          FROM tracking.dmt_posicion;"

# ---------------------------------------------------------------------------
# 7) Limpieza
# ---------------------------------------------------------------------------
if [ "$DMT_KEEP_SCRATCH" = "1" ]; then
    log "Scratch conservada por DMT_KEEP_SCRATCH=1: $DMT_SCRATCH_DB"
else
    legacy -q -c "DROP DATABASE IF EXISTS $DMT_SCRATCH_DB WITH (FORCE)"
    log "Scratch $DMT_SCRATCH_DB eliminada"
fi

log "Resumen: $DUMP_OK dumps restaurados; $DUMP_MALOS omitidos (ver $LOGDIR/dumps-invalidos.txt)"
