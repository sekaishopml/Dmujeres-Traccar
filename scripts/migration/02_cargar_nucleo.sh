#!/usr/bin/env bash
# ============================================================================
# 02_cargar_nucleo.sh - Carga nucleo del legado a la base nueva (FASE 3 p3)
# ============================================================================
# Flujo:
#   1. Crea/vacia el staging system.dmt_staging_* en la base nueva.
#   2. Copia los datos de traccar (SOLO LECTURA: COPY TO STDOUT) y los
#      dispositivos historicos de traccar_qa.tc_devices a la base nueva con
#      tuberias docker exec -> docker exec. Los valores sensibles (hashes,
#      salts, tokens, claves) viajan por la tuberia: nunca se imprimen ni se
#      escriben en disco.
#   3. Ejecuta 02_cargar_nucleo.sql (transformaciones, jornadas, bateria y
#      system.dmt_migracion_mapa).
#
# Requisitos: docker con dmj-db (legado, solo lectura) y dmt-db (nuevo).
# Variables: DMT_LEGACY_CT (dmj-db), DMT_NEW_CT (dmt-db), DMT_LEGACY_DB
# (traccar), DMT_LEGACY_QA_DB (traccar_qa), DMT_NEW_DB (dmujeres),
# DMT_DUMPS_DIR_HOST (/var/backups/dmj) para los dispositivos solo-dump.
# Idempotente: re-ejecutar no inserta filas nuevas.
# ============================================================================
set -euo pipefail

LEGACY_CT="${DMT_LEGACY_CT:-dmj-db}"
NEW_CT="${DMT_NEW_CT:-dmt-db}"
LEGACY_DB="${DMT_LEGACY_DB:-traccar}"
LEGACY_QA_DB="${DMT_LEGACY_QA_DB:-traccar_qa}"
NEW_DB="${DMT_NEW_DB:-dmujeres}"
SQL_DIR="$(cd "$(dirname "$0")" && pwd)"

legacy()    { docker exec -i "$LEGACY_CT" psql -U traccar -d "$LEGACY_DB" -v ON_ERROR_STOP=1 "$@"; }
legacy_qa() { docker exec -i "$LEGACY_CT" psql -U traccar -d "$LEGACY_QA_DB" -v ON_ERROR_STOP=1 "$@"; }
nueva()     { docker exec -i "$NEW_CT" psql -U dmt -d "$NEW_DB" -v ON_ERROR_STOP=1 "$@"; }

log() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }

pipe_prod() { # pipe_prod <select-legado> <tabla-staging> <columnas>
    legacy -q -c "COPY ($1) TO STDOUT WITH (FORMAT csv)" \
        | nueva -q -c "COPY $2 ($3) FROM STDIN WITH (FORMAT csv)"
}
pipe_qa() {
    legacy_qa -q -c "COPY ($1) TO STDOUT WITH (FORMAT csv)" \
        | nueva -q -c "COPY $2 ($3) FROM STDIN WITH (FORMAT csv)"
}

log "Staging: creando/vaciando system.dmt_staging_*"
nueva -q -f - < "$SQL_DIR/02_staging_nucleo.sql"

log "Usuarios (traccar.tc_users)"
pipe_prod "SELECT id, name, email, hashedpassword, salt, readonly, administrator, disabled, expirationtime, totpkey, phone, login, attributes, map, latitude, longitude, zoom, coordinateformat, poilayer, devicelimit, userlimit, devicereadonly, limitcommands, disablereports, fixedemail, temporary FROM public.tc_users" \
    system.dmt_staging_usuario \
    "id,nombre,correo,hash_clave,sal,solo_lectura,administrador,deshabilitado,expira_en,clave_totp,telefono,login_legado,atributos_texto,mapa,latitud,longitud,zoom,formato_coord,poi_layer,limite_dispositivos,limite_usuarios,dispositivos_solo_lectura,limitar_comandos,deshabilitar_reportes,correo_fijo,temporal"

log "Dispositivos vivos (traccar.tc_devices)"
pipe_prod "SELECT id, name, uniqueid, lastupdate, positionid, groupid, attributes, phone, model, contact, category, disabled, status, expirationtime, 'traccar'::text FROM public.tc_devices" \
    system.dmt_staging_dispositivo \
    "id,nombre,unique_id,lastupdate,positionid,groupid,atributos_texto,telefono,modelo,contacto,categoria,deshabilitado,estado,expira_en,fuente"

log "Dispositivos historicos (traccar_qa.tc_devices)"
pipe_qa "SELECT id, name, uniqueid, lastupdate, positionid, groupid, attributes, phone, model, contact, category, disabled, status, expirationtime, 'traccar_qa'::text FROM public.tc_devices" \
    system.dmt_staging_dispositivo \
    "id,nombre,unique_id,lastupdate,positionid,groupid,atributos_texto,telefono,modelo,contacto,categoria,deshabilitado,estado,expira_en,fuente"

# Dispositivos que solo existen en el dump pre-purga (p. ej. qa-f1/qa-f2, con
# posiciones del 2026-09-15). Se restauran SOLO en una scratch del cluster
# legado y se agregan como historicos si su id no esta ya en el staging.
DMT_DUMPS_DIR_HOST="${DMT_DUMPS_DIR_HOST:-/var/backups/dmj}"
DMT_DEVICES_DUMP="${DMT_DEVICES_DUMP:-traccar-pre-purga-recorridos.dump}"
DMT_DEVICES_SCRATCH_DB="${DMT_DEVICES_SCRATCH_DB:-dmt_devices_scratch}"
if [ -f "$DMT_DUMPS_DIR_HOST/$DMT_DEVICES_DUMP" ]; then
    log "Dispositivos solo-dump ($DMT_DEVICES_DUMP)"
    docker cp "$DMT_DUMPS_DIR_HOST/$DMT_DEVICES_DUMP" "$LEGACY_CT:/tmp/dmt_devices_dump.dump" >/dev/null
    legacy -q -c "DROP DATABASE IF EXISTS $DMT_DEVICES_SCRATCH_DB WITH (FORCE)"
    legacy -q -c "CREATE DATABASE $DMT_DEVICES_SCRATCH_DB"
    docker exec -i "$LEGACY_CT" psql -U traccar -d "$DMT_DEVICES_SCRATCH_DB" -q -v ON_ERROR_STOP=1 -c "
        CREATE TABLE public.tc_devices (
            id integer, name varchar(128), uniqueid varchar(128), lastupdate timestamp,
            positionid integer, groupid integer, attributes varchar(4000), phone varchar(128),
            model varchar(128), contact varchar(512), category varchar(128), disabled boolean,
            status character(8), expirationtime timestamp, motionstate boolean, motiontime timestamp,
            motiondistance double precision, overspeedstate boolean, overspeedtime timestamp,
            overspeedgeofenceid integer, motionstreak boolean, calendarid integer,
            motionpositionid integer, motionlatitude double precision, motionlongitude double precision);"
    docker exec -i "$LEGACY_CT" pg_restore -U traccar -a -t tc_devices --no-owner --no-privileges \
        -d "$DMT_DEVICES_SCRATCH_DB" /tmp/dmt_devices_dump.dump
    ids_presentes=$(nueva -tAc "SELECT coalesce(string_agg(DISTINCT id::text, ','), '0') FROM system.dmt_staging_dispositivo")
    docker exec -i "$LEGACY_CT" psql -U traccar -d "$DMT_DEVICES_SCRATCH_DB" -q -v ON_ERROR_STOP=1 -c "
        COPY (
            SELECT id, name, uniqueid, lastupdate, positionid, groupid, attributes, phone,
                   model, contact, category, disabled, status, expirationtime,
                   'traccar_dump_pre_purga'::text
            FROM public.tc_devices
            WHERE id NOT IN ($ids_presentes)
        ) TO STDOUT WITH (FORMAT csv)" \
        | nueva -q -c "COPY system.dmt_staging_dispositivo (id,nombre,unique_id,lastupdate,positionid,groupid,atributos_texto,telefono,modelo,contacto,categoria,deshabilitado,estado,expira_en,fuente) FROM STDIN WITH (FORMAT csv)"
    legacy -q -c "DROP DATABASE IF EXISTS $DMT_DEVICES_SCRATCH_DB WITH (FORCE)"
    docker exec -i "$LEGACY_CT" rm -f /tmp/dmt_devices_dump.dump
else
    log "AVISO: no se encontro $DMT_DUMPS_DIR_HOST/$DMT_DEVICES_DUMP; no se agregan dispositivos solo-dump"
fi

log "Asignaciones (traccar.tc_user_device)"
pipe_prod "SELECT userid, deviceid FROM public.tc_user_device" \
    system.dmt_staging_asignacion "userid,deviceid"

log "Eventos (traccar.tc_events)"
pipe_prod "SELECT id, type, eventtime, deviceid, positionid, geofenceid, maintenanceid, attributes FROM public.tc_events" \
    system.dmt_staging_evento \
    "id,tipo,eventtime,deviceid,positionid,geofenceid,maintenanceid,atributos_texto"

log "Bateria desde posiciones (traccar.tc_positions, ultimos 7 dias)"
pipe_prod "SELECT id, deviceid, fixtime, attributes FROM public.tc_positions WHERE fixtime >= now() - interval '7 days' AND attributes LIKE '%batteryLevel%'" \
    system.dmt_staging_bateria_pos "id,deviceid,fixtime,atributos_texto"

log "Tokens FCM (traccar.tc_fcm_tokens)"
pipe_prod "SELECT id, deviceid, token, previous_token, active, invalid, createdat, updatedat, lastusedat FROM public.tc_fcm_tokens" \
    system.dmt_staging_fcm \
    "id,deviceid,token,token_previo,activo,invalido,creado_en,actualizado_en,ultimo_uso_en"

log "Claves de firma (traccar.tc_keystore)"
pipe_prod "SELECT id, publickey, privatekey FROM public.tc_keystore" \
    system.dmt_staging_keystore "id,clave_publica,clave_privada"

log "Recuperacion (traccar.tc_recovery_event)"
pipe_prod "SELECT id, deviceid, ts, eventtype, reason, attemptid, status, sessionid, fcmmsgid, fcmpriority, source FROM public.tc_recovery_event" \
    system.dmt_staging_recuperacion \
    "id,deviceid,ts,eventtype,reason,attemptid,status,sessionid,fcmmsgid,fcmpriority,source"

log "Conteos de tablas pendientes/evaluar"
legacy -tAF $'\t' -c "
SELECT 'traccar.public.tc_mobile_messages', count(*) FROM public.tc_mobile_messages
UNION ALL SELECT 'traccar.public.tc_statistics', count(*) FROM public.tc_statistics
UNION ALL SELECT 'traccar.public.tc_actions', count(*) FROM public.tc_actions
UNION ALL SELECT 'traccar.public.tc_device_health', count(*) FROM public.tc_device_health
UNION ALL SELECT 'traccar.public.tc_groups', count(*) FROM public.tc_groups
ORDER BY 1" \
    | nueva -q -c "COPY system.dmt_staging_origen_pendiente FROM STDIN WITH (FORMAT text)"

log "Ejecutando 02_cargar_nucleo.sql"
nueva -f - < "$SQL_DIR/02_cargar_nucleo.sql"

log "Fin de la carga nucleo"
