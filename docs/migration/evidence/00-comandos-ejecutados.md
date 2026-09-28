# Comandos principales de FASE 3 parte 2

Todo el trabajo de producción fue de solo lectura (`SELECT`/`COPY TO`) y las
bases scratch (`dmt_hist_test`, `dmt_etl_scratch`, `dmt_new_test`) se crearon
y eliminaron dentro del contenedor `dmj-db`.

## Inventario y verificación de dumps

```sh
ls -la /var/backups/dmj/
sha256sum /var/backups/dmj/*.dump
docker exec dmj-db pg_restore -l /tmp/dmt_dumps/<dump>.dump   # verificación TOC
```

## Conteos de producción (antes/después)

```sh
docker exec dmj-db psql -U traccar -d traccar -tAc "select count(*) from tc_positions;"
docker exec dmj-db psql -U traccar -d traccar -tAc "select count(*) from tc_positions_bak_20260903;"
docker exec dmj-db psql -U traccar -d traccar_qa -tAc "select count(*) from tc_positions;"
```

## Restauración de prueba y análisis (scratch)

```sh
docker exec dmj-db createdb -U traccar dmt_hist_test
docker exec dmj-db bash /tmp/dmt_dumps/restore_positions.sh      # chunks _hyper_1_* + bak
docker exec dmj-db psql -U traccar -d dmt_hist_test -f /tmp/dmt_dumps/union_analisis.sql
```

## ETL (prueba en scratch con esquema nuevo)

```sh
docker exec -e PGUSER=traccar \
  -e DMT_NEW_PSQL="psql -d dmt_new_test" \
  -e DMT_DUMPS_DIR=/tmp/dmt_dumps \
  -e DMT_SCRATCH_DB=dmt_etl_scratch \
  -e DMT_WORK_DIR=/tmp/dmt_dumps/etl \
  dmj-db bash /tmp/dmt_etl_src/01_cargar_posiciones.sh

# idempotencia (segunda pasada del cargador)
docker exec -e PGUSER=traccar dmj-db bash -c \
  'PGOPTIONS="-c dmt.batch_size=5000" psql -d dmt_new_test -f /tmp/dmt_etl_src/01_cargar_posiciones.sql'
```

## Limpieza

```sh
docker exec dmj-db psql -U traccar -d postgres -c \
  "DROP DATABASE IF EXISTS dmt_hist_test WITH (FORCE);
   DROP DATABASE IF EXISTS dmt_etl_scratch WITH (FORCE);
   DROP DATABASE IF EXISTS dmt_new_test WITH (FORCE);"
```
