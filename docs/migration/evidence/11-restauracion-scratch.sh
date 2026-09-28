#!/bin/bash
# Restaura SOLO los datos de posiciones de dumps custom de traccar en la
# scratch dmt_hist_test del contenedor dmj-db (nunca en traccar/traccar_qa).
# - hypertable tc_positions = _hyper_1_*  -> tabla public.pos_<tag>
# - tabla public.tc_positions_bak_20260903 -> public.posbak_<tag>
# Uso: bash restore_positions.sh
set -u

DB=dmt_hist_test
DUMPDIR=/tmp/dmt_dumps
LOGDIR=/tmp/dmt_dumps/logs
mkdir -p "$LOGDIR"

declare -A TAGS=(
 [traccar-20260919-125422.dump]=d0919
 [traccar-20260922-003135.dump]=d0922a
 [traccar-20260922-030001.dump]=d0922b
 [traccar-20260923-030001.dump]=d0923
 [traccar-20260924-030001.dump]=d0924
 [traccar-20260925-030001.dump]=d0925
 [traccar-pre-cambio-usuarios.dump]=dprecambio
 [traccar-pre-purga-recorridos.dump]=dprepurga
)

psql -U traccar -d "$DB" -q -c "CREATE SCHEMA IF NOT EXISTS _timescaledb_internal;"
psql -U traccar -d "$DB" -q -c "CREATE TABLE IF NOT EXISTS public.tc_positions (
  id integer, protocol varchar(128), deviceid integer,
  servertime timestamp, devicetime timestamp, fixtime timestamp,
  valid boolean, latitude double precision, longitude double precision,
  altitude double precision, speed double precision, course double precision,
  address varchar(512), attributes varchar(4000), accuracy double precision,
  network varchar(4000), geofenceids varchar(128));"
psql -U traccar -d "$DB" -q -c "CREATE TABLE IF NOT EXISTS public.tc_positions_bak_20260903 (LIKE public.tc_positions);"

for dump in "${!TAGS[@]}"; do
  tag=${TAGS[$dump]}
  echo "=== $dump -> pos_$tag / posbak_$tag ==="
  grep -E "TABLE DATA _timescaledb_internal _hyper_1_[0-9]+_chunk" "$DUMPDIR/toc-$dump.txt" \
    > "$LOGDIR/list-$tag.txt"
  echo "chunks hyper_1: $(wc -l < "$LOGDIR/list-$tag.txt")"

  psql -U traccar -d "$DB" -q -c "DROP TABLE IF EXISTS public.pos_$tag; CREATE TABLE public.pos_$tag (LIKE public.tc_positions);"
  psql -U traccar -d "$DB" -q -c "DROP TABLE IF EXISTS public.posbak_$tag; CREATE TABLE public.posbak_$tag (LIKE public.tc_positions);"

  while read -r line; do
    chunk=$(echo "$line" | awk '{print $7}')
    psql -U traccar -d "$DB" -q -c "DROP TABLE IF EXISTS _timescaledb_internal.$chunk; CREATE TABLE _timescaledb_internal.$chunk (LIKE public.tc_positions);"
  done < "$LOGDIR/list-$tag.txt"

  pg_restore -U traccar -a -L "$LOGDIR/list-$tag.txt" --no-owner --no-privileges \
    -d "$DB" "$DUMPDIR/$dump" > "$LOGDIR/restore-$tag.log" 2>&1 \
    && echo "pg_restore chunks OK" || echo "pg_restore chunks rc=$? (ver $LOGDIR/restore-$tag.log)"

  while read -r line; do
    chunk=$(echo "$line" | awk '{print $7}')
    psql -U traccar -d "$DB" -q -c "INSERT INTO public.pos_$tag SELECT * FROM _timescaledb_internal.$chunk;"
  done < "$LOGDIR/list-$tag.txt"

  pg_restore -U traccar -a -t tc_positions --no-owner --no-privileges -d "$DB" "$DUMPDIR/$dump" \
    > "$LOGDIR/restorepar-$tag.log" 2>&1 \
    && echo "pg_restore tabla padre OK" || echo "pg_restore tabla padre rc=$? (ver $LOGDIR/restorepar-$tag.log)"

  pg_restore -U traccar -a -t tc_positions_bak_20260903 --no-owner --no-privileges -d "$DB" "$DUMPDIR/$dump" \
    > "$LOGDIR/restorebak-$tag.log" 2>&1 \
    && echo "pg_restore bak OK" || echo "pg_restore bak rc=$? (ver $LOGDIR/restorebak-$tag.log)"

  psql -U traccar -d "$DB" -c "SELECT '$tag' AS tag,
       (SELECT count(*) FROM public.pos_$tag) AS filas_chunks,
       (SELECT count(*) FROM public.posbak_$tag) AS filas_bak,
       (SELECT min(fixtime) FROM public.pos_$tag) AS min_fx,
       (SELECT max(fixtime) FROM public.pos_$tag) AS max_fx,
       (SELECT count(DISTINCT deviceid) FROM public.pos_$tag) AS dispositivos;"

  while read -r line; do
    chunk=$(echo "$line" | awk '{print $7}')
    psql -U traccar -d "$DB" -q -c "DROP TABLE IF EXISTS _timescaledb_internal.$chunk;"
  done < "$LOGDIR/list-$tag.txt"
done
echo "FIN"
