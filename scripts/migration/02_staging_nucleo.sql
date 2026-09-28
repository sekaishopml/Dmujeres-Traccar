-- ============================================================================
-- 02_staging_nucleo.sql - Tablas de staging de la carga nucleo (FASE 3 parte 3)
-- ============================================================================
-- Se ejecuta EN LA BASE NUEVA. 02_cargar_nucleo.sh llena estas tablas por
-- tuberia (COPY TO STDOUT desde el legado -> COPY FROM STDIN aqui), de modo
-- que los valores sensibles (hashes, salts, tokens, claves) nunca se imprimen
-- ni se escriben en disco.
-- ============================================================================

\set ON_ERROR_STOP on
BEGIN;

CREATE TABLE IF NOT EXISTS system.dmt_staging_usuario (
    id                       integer NOT NULL,
    nombre                   text,
    correo                   text,
    hash_clave               text,
    sal                      text,
    solo_lectura             boolean,
    administrador            boolean,
    deshabilitado            boolean,
    expira_en                timestamp,
    clave_totp               text,
    telefono                 text,
    login_legado             text,
    atributos_texto          text,
    mapa                     text,
    latitud                  double precision,
    longitud                 double precision,
    zoom                     integer,
    formato_coord            text,
    poi_layer                text,
    limite_dispositivos      integer,
    limite_usuarios          integer,
    dispositivos_solo_lectura boolean,
    limitar_comandos         boolean,
    deshabilitar_reportes    boolean,
    correo_fijo              boolean,
    temporal                 boolean
);

CREATE TABLE IF NOT EXISTS system.dmt_staging_dispositivo (
    id              integer NOT NULL,
    nombre          text,
    unique_id       text,
    lastupdate      timestamp,
    positionid      integer,
    groupid         integer,
    atributos_texto text,
    telefono        text,
    modelo          text,
    contacto        text,
    categoria       text,
    deshabilitado   boolean,
    estado          text,
    expira_en       timestamp,
    fuente          text
);

CREATE TABLE IF NOT EXISTS system.dmt_staging_asignacion (
    userid   integer NOT NULL,
    deviceid integer NOT NULL
);

CREATE TABLE IF NOT EXISTS system.dmt_staging_evento (
    id              integer NOT NULL,
    tipo            text,
    eventtime       timestamp,
    deviceid        integer,
    positionid      integer,
    geofenceid      integer,
    maintenanceid   integer,
    atributos_texto text
);

CREATE TABLE IF NOT EXISTS system.dmt_staging_fcm (
    id             bigint NOT NULL,
    deviceid       integer NOT NULL,
    token          text,
    token_previo   text,
    activo         boolean,
    invalido       boolean,
    creado_en      timestamp,
    actualizado_en timestamp,
    ultimo_uso_en  timestamp
);

CREATE TABLE IF NOT EXISTS system.dmt_staging_keystore (
    id            integer NOT NULL,
    clave_publica bytea,
    clave_privada bytea
);

CREATE TABLE IF NOT EXISTS system.dmt_staging_recuperacion (
    id          bigint NOT NULL,
    deviceid    integer NOT NULL,
    ts          timestamp,
    eventtype   text,
    reason      text,
    attemptid   text,
    status      text,
    sessionid   text,
    fcmmsgid    text,
    fcmpriority text,
    source      text
);

CREATE TABLE IF NOT EXISTS system.dmt_staging_bateria_pos (
    id              integer NOT NULL,
    deviceid        integer NOT NULL,
    fixtime         timestamp,
    atributos_texto text
);

-- Conteos de tablas legadas que quedan en evaluar/no migrar en esta fase.
CREATE TABLE IF NOT EXISTS system.dmt_staging_origen_pendiente (
    tabla_origen text NOT NULL,
    filas        bigint NOT NULL
);

TRUNCATE system.dmt_staging_origen_pendiente,
         system.dmt_staging_usuario,
         system.dmt_staging_dispositivo,
         system.dmt_staging_asignacion,
         system.dmt_staging_evento,
         system.dmt_staging_fcm,
         system.dmt_staging_keystore,
         system.dmt_staging_recuperacion,
         system.dmt_staging_bateria_pos;

COMMIT;
