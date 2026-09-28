-- ============================================================================
-- 02_cargar_nucleo.sql - Transformacion y carga nucleo (FASE 3 parte 3)
-- ============================================================================
-- Se ejecuta EN LA BASE NUEVA con el staging ya cargado por
-- 02_cargar_nucleo.sh. Orden: roles y usuarios, dispositivos, asignaciones,
-- eventos, jornadas derivadas, bateria, FCM, claves de firma, alertas de
-- recuperacion y system.dmt_migracion_mapa.
--
-- Idempotencia: claves naturales/legadas con ON CONFLICT DO NOTHING o
-- WHERE NOT EXISTS. Re-ejecutar no inserta filas nuevas.
--
-- Produccion (traccar/traccar_qa) nunca se escribe: el staging se llena por
-- COPY (SELECT) desde el legado.
-- ============================================================================

\set ON_ERROR_STOP on
BEGIN;

-- ---------------------------------------------------------------------------
-- 0) Utilitario JSON defensivo (mismo que usa el ETL de posiciones)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION system.dmt_jsonb_seguro(p_texto text)
RETURNS jsonb
LANGUAGE plpgsql IMMUTABLE
AS $fn$
BEGIN
    IF p_texto IS NULL OR btrim(p_texto) = '' THEN
        RETURN '{}'::jsonb;
    END IF;
    RETURN p_texto::jsonb;
EXCEPTION WHEN others THEN
    RETURN jsonb_build_object('crudo', p_texto);
END;
$fn$;

-- ---------------------------------------------------------------------------
-- 1) Catalogo de roles y usuarios (id legado preservado)
-- ---------------------------------------------------------------------------
INSERT INTO iam.dmt_rol (codigo, nombre, descripcion) VALUES
    ('administrador', 'Administrador', 'Acceso total a la plataforma'),
    ('operador',      'Operador',      'Operacion sobre dispositivos y usuarios asignados'),
    ('solo_lectura',  'Solo lectura',  'Consulta sin cambios')
ON CONFLICT (codigo) DO NOTHING;

INSERT INTO iam.dmt_usuario (
    id, id_legado, nombre_usuario, nombre, correo, telefono,
    hash_clave, sal, habilitado, administrador, solo_lectura,
    clave_totp, expira_en, atributos
)
SELECT
    s.id,
    s.id,
    nullif(btrim(s.login_legado), ''),
    s.nombre,
    nullif(btrim(s.correo), ''),
    nullif(btrim(s.telefono), ''),
    s.hash_clave,
    s.sal,
    NOT coalesce(s.deshabilitado, false),
    coalesce(s.administrador, false),
    coalesce(s.solo_lectura, false),
    s.clave_totp,
    s.expira_en AT TIME ZONE 'America/Guayaquil',
    system.dmt_jsonb_seguro(s.atributos_texto)
      || jsonb_strip_nulls(jsonb_build_object(
             'map', s.mapa,
             'latitude', s.latitud,
             'longitude', s.longitud,
             'zoom', s.zoom,
             'coordinateformat', s.formato_coord,
             'poilayer', s.poi_layer,
             'devicelimit', s.limite_dispositivos,
             'userlimit', s.limite_usuarios,
             'devicereadonly', s.dispositivos_solo_lectura,
             'limitcommands', s.limitar_comandos,
             'disablereports', s.deshabilitar_reportes,
             'fixedemail', s.correo_fijo,
             'temporary', s.temporal))
      || jsonb_build_object('migracion',
             jsonb_build_object('fuente', 'traccar.tc_users', 'id_legado', s.id))
FROM system.dmt_staging_usuario s
ON CONFLICT (id) DO NOTHING;

INSERT INTO iam.dmt_usuario_rol (usuario_id, rol_id)
SELECT u.id, r.id
FROM iam.dmt_usuario u
JOIN iam.dmt_rol r ON r.codigo = CASE
        WHEN u.administrador THEN 'administrador'
        WHEN u.solo_lectura  THEN 'solo_lectura'
        ELSE 'operador'
    END
ON CONFLICT (usuario_id, rol_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2) Dispositivos: 9 vivos de traccar.tc_devices + insertados historicos
--    (8 de traccar_qa.tc_devices y los que solo existen en el dump pre-purga)
--    necesarios para la FK del historico de posiciones que arranca en
--    2026-08; esos ids ya no existen en produccion.
--    Los historicos llevan identificador propio para no chocar con los
--    uniqueid vivos (joseph/kevin/david existen en varias fuentes), quedan
--    deshabilitados y con estado 'historico'.
-- ---------------------------------------------------------------------------
INSERT INTO tracking.dmt_dispositivo (
    id, id_legado, nombre, identificador, habilitado, estado,
    ultima_conexion_en, ultima_posicion_id, grupo_id, telefono, modelo,
    contacto, categoria, atributos, expira_en
)
SELECT
    s.id,
    s.id,
    s.nombre,
    CASE WHEN s.fuente = 'traccar' THEN s.unique_id
         ELSE 'historico-' || replace(s.fuente, 'traccar_', '') || '-' || s.id || '-' || s.unique_id
    END,
    CASE WHEN s.fuente = 'traccar' THEN NOT coalesce(s.deshabilitado, false)
         ELSE false
    END,
    CASE WHEN s.fuente = 'traccar' THEN coalesce(nullif(btrim(s.estado), ''), 'DESCONOCIDO')
         ELSE 'historico'
    END,
    s.lastupdate AT TIME ZONE 'America/Guayaquil',
    s.positionid,
    s.groupid,
    nullif(btrim(s.telefono), ''),
    nullif(btrim(s.modelo), ''),
    nullif(btrim(s.contacto), ''),
    nullif(btrim(s.categoria), ''),
    system.dmt_jsonb_seguro(s.atributos_texto)
      || jsonb_build_object('migracion', jsonb_strip_nulls(jsonb_build_object(
             'fuente', CASE s.fuente
                           WHEN 'traccar' THEN 'traccar.tc_devices'
                           WHEN 'traccar_qa' THEN 'traccar_qa.tc_devices'
                           ELSE 'dump.traccar-pre-purga-recorridos.tc_devices' END,
             'id_legado', s.id,
             'identificador_legado', s.unique_id,
             'historico', CASE WHEN s.fuente <> 'traccar' THEN true END))),
    s.expira_en AT TIME ZONE 'America/Guayaquil'
FROM system.dmt_staging_dispositivo s
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3) Asignaciones usuario-dispositivo
-- ---------------------------------------------------------------------------
INSERT INTO operations.dmt_asignacion (usuario_id, dispositivo_id, desde_en, activa, atributos)
SELECT
    u.id,
    d.id,
    now(),
    true,
    jsonb_build_object('migracion', jsonb_build_object(
        'fuente', 'traccar.tc_user_device',
        'userid_legado', s.userid,
        'deviceid_legado', s.deviceid))
FROM system.dmt_staging_asignacion s
JOIN iam.dmt_usuario u          ON u.id_legado = s.userid
JOIN tracking.dmt_dispositivo d ON d.id_legado = s.deviceid
WHERE NOT EXISTS (
    SELECT 1 FROM operations.dmt_asignacion a
    WHERE a.usuario_id = u.id AND a.dispositivo_id = d.id
);

-- ---------------------------------------------------------------------------
-- 4) Eventos (id preservado)
-- ---------------------------------------------------------------------------
INSERT INTO tracking.dmt_evento (
    id, dispositivo_id, posicion_id, geocerca_id, mantenimiento_id,
    tipo, ocurrido_en, atributos
)
SELECT
    s.id,
    d.id,
    s.positionid,
    s.geofenceid,
    s.maintenanceid,
    s.tipo,
    s.eventtime AT TIME ZONE 'America/Guayaquil',
    system.dmt_jsonb_seguro(s.atributos_texto)
FROM system.dmt_staging_evento s
LEFT JOIN tracking.dmt_dispositivo d ON d.id_legado = s.deviceid
ON CONFLICT (id, ocurrido_en) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 5) Jornadas derivadas de mobileJourneyStarted / mobileJourneyEnded
--    (attributes.journeyId, bigint). Una jornada por (dispositivo, journeyId).
--    Los mobileJourneyEnded sin mobileJourneyStarted previo (3 en el legado)
--    no se pueden abrir sin hora de inicio: se dejan fuera y se reportan.
--    id_legado = journeyId (no existe id simple de jornada en el legado).
-- ---------------------------------------------------------------------------
WITH ev AS (
    SELECT
        e.dispositivo_id,
        (e.atributos ->> 'journeyId')::bigint AS journey_id,
        e.tipo,
        e.ocurrido_en,
        e.id
    FROM tracking.dmt_evento e
    WHERE e.tipo IN ('mobileJourneyStarted', 'mobileJourneyEnded')
      AND (e.atributos ->> 'journeyId') ~ '^[0-9]+$'
      AND e.dispositivo_id IS NOT NULL
), inicio AS (
    SELECT dispositivo_id, journey_id,
           min(ocurrido_en) AS inicio_en,
           min(id)          AS evento_inicio_id
    FROM ev
    WHERE tipo = 'mobileJourneyStarted'
    GROUP BY dispositivo_id, journey_id
), fin AS (
    SELECT dispositivo_id, journey_id,
           min(ocurrido_en) AS fin_en,
           min(id)          AS evento_fin_id
    FROM ev
    WHERE tipo = 'mobileJourneyEnded'
    GROUP BY dispositivo_id, journey_id
)
INSERT INTO operations.dmt_jornada (
    id_legado, dispositivo_id, inicio_en, fin_en, estado, atributos
)
SELECT
    i.journey_id,
    i.dispositivo_id,
    i.inicio_en,
    f.fin_en,
    CASE WHEN f.fin_en IS NULL THEN 'abierta' ELSE 'cerrada' END,
    jsonb_strip_nulls(jsonb_build_object(
        'origen', 'tc_events.mobileJourney',
        'journey_id', i.journey_id,
        'evento_inicio_id', i.evento_inicio_id,
        'evento_fin_id', f.evento_fin_id))
FROM inicio i
LEFT JOIN fin f USING (dispositivo_id, journey_id)
WHERE NOT EXISTS (
    SELECT 1 FROM operations.dmt_jornada j
    WHERE j.dispositivo_id = i.dispositivo_id
      AND j.id_legado = i.journey_id
);

-- ---------------------------------------------------------------------------
-- 6) Bateria
-- ---------------------------------------------------------------------------
-- 6a) mobile.batteryHistory de los atributos vivos de tc_devices (epoch en
--     segundos), solo ultimos 7 dias. El valor es una cadena JSON dentro del
--     JSON de atributos.
WITH hist AS (
    SELECT
        s.id AS id_legado,
        (entrada ->> 0)::bigint AS epoch_s,
        (entrada ->> 1)::real   AS pct
    FROM system.dmt_staging_dispositivo s
    CROSS JOIN LATERAL jsonb_array_elements(
        CASE
            WHEN (system.dmt_jsonb_seguro(s.atributos_texto) ->> 'mobile.batteryHistory') ~ '^\s*\['
            THEN (system.dmt_jsonb_seguro(s.atributos_texto) ->> 'mobile.batteryHistory')::jsonb
            ELSE '[]'::jsonb
        END) AS h(entrada)
    WHERE s.fuente = 'traccar'
), hist_dedup AS (
    SELECT DISTINCT ON (id_legado, epoch_s) id_legado, epoch_s, pct
    FROM hist
    WHERE epoch_s IS NOT NULL
      AND pct IS NOT NULL
      AND pct BETWEEN 0 AND 100
      AND to_timestamp(epoch_s) BETWEEN now() - interval '7 days' AND now()
    ORDER BY id_legado, epoch_s, pct
)
INSERT INTO telemetry.dmt_bateria (
    id_legado, dispositivo_id, porcentaje, cargando, registrado_en, atributos
)
SELECT
    NULL,
    d.id,
    h.pct,
    NULL,
    to_timestamp(h.epoch_s),
    jsonb_build_object('origen', 'tc_devices.mobile.batteryHistory')
FROM hist_dedup h
JOIN tracking.dmt_dispositivo d ON d.id_legado = h.id_legado
WHERE NOT EXISTS (
    SELECT 1 FROM telemetry.dmt_bateria b
    WHERE b.dispositivo_id = d.id
      AND b.registrado_en = to_timestamp(h.epoch_s)
);

-- 6b) attributes.batteryLevel de las posiciones de los ultimos 7 dias.
INSERT INTO telemetry.dmt_bateria (
    id_legado, dispositivo_id, porcentaje, cargando, registrado_en, atributos
)
SELECT
    s.id,
    d.id,
    CASE WHEN (a.atr ->> 'batteryLevel') ~ '^-?[0-9]+(\.[0-9]+)?$'
         THEN (a.atr ->> 'batteryLevel')::real END,
    CASE WHEN (a.atr ->> 'charge') IN ('true', 'false')
         THEN (a.atr ->> 'charge')::boolean END,
    s.fixtime AT TIME ZONE 'America/Guayaquil',
    jsonb_build_object('origen', 'tc_positions.batteryLevel')
FROM system.dmt_staging_bateria_pos s
CROSS JOIN LATERAL (SELECT system.dmt_jsonb_seguro(s.atributos_texto) AS atr) a
JOIN tracking.dmt_dispositivo d ON d.id_legado = s.deviceid
WHERE (a.atr ->> 'batteryLevel') ~ '^-?[0-9]+(\.[0-9]+)?$'
  AND (a.atr ->> 'batteryLevel')::real BETWEEN 0 AND 100
  AND NOT EXISTS (
      SELECT 1 FROM telemetry.dmt_bateria b
      WHERE b.dispositivo_id = d.id
        AND b.registrado_en = (s.fixtime AT TIME ZONE 'America/Guayaquil')
  );

-- ---------------------------------------------------------------------------
-- 7) Tokens FCM (continuidad de notificaciones de la App)
-- ---------------------------------------------------------------------------
INSERT INTO iam.dmt_token_fcm (
    id_legado, dispositivo_id, token, token_previo, activo, invalido,
    ultimo_uso_en, creado_en, actualizado_en
)
SELECT
    s.id, d.id, s.token, s.token_previo, s.activo, s.invalido,
    s.ultimo_uso_en  AT TIME ZONE 'America/Guayaquil',
    s.creado_en      AT TIME ZONE 'America/Guayaquil',
    s.actualizado_en AT TIME ZONE 'America/Guayaquil'
FROM system.dmt_staging_fcm s
JOIN tracking.dmt_dispositivo d ON d.id_legado = s.deviceid
ON CONFLICT (token) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 8) Claves de firma (compatibilidad de JWT de la App). Material secreto:
--    no se registra en ningun log ni documento.
-- ---------------------------------------------------------------------------
INSERT INTO iam.dmt_clave_firma (id_legado, clave_publica, clave_privada, activa)
SELECT s.id, s.clave_publica, s.clave_privada, true
FROM system.dmt_staging_keystore s
ON CONFLICT (id_legado) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 9) Alertas de recuperacion (tc_recovery_event -> dmt_alerta origen
--    'recuperacion'). Mapeo de estado: OK -> resuelta, FAILED -> descartada,
--    resto -> nueva. El estado legado original queda en atributos.
-- ---------------------------------------------------------------------------
INSERT INTO operations.dmt_alerta (
    id_legado, origen, dispositivo_id, tipo, subtipo, severidad, estado,
    mensaje, ocurrido_en, atributos
)
SELECT
    s.id,
    'recuperacion',
    d.id,
    s.eventtype,
    s.reason,
    'media',
    CASE s.status
        WHEN 'OK'     THEN 'resuelta'
        WHEN 'FAILED' THEN 'descartada'
        ELSE 'nueva'
    END,
    s.eventtype || coalesce(' / ' || s.reason, ''),
    s.ts AT TIME ZONE 'America/Guayaquil',
    jsonb_strip_nulls(jsonb_build_object(
        'attemptid', s.attemptid,
        'sessionid', s.sessionid,
        'fcmmsgid', s.fcmmsgid,
        'fcmpriority', s.fcmpriority,
        'source', s.source,
        'status_legado', s.status))
FROM system.dmt_staging_recuperacion s
JOIN tracking.dmt_dispositivo d ON d.id_legado = s.deviceid
ON CONFLICT (origen, id_legado) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 10) Ajuste de secuencias de ids sembrados (usuarios, dispositivos, eventos)
-- ---------------------------------------------------------------------------
SELECT setval(pg_get_serial_sequence('iam.dmt_usuario', 'id'),
              (SELECT coalesce(max(id), 1) FROM iam.dmt_usuario),
              (SELECT max(id) IS NOT NULL FROM iam.dmt_usuario));
SELECT setval(pg_get_serial_sequence('tracking.dmt_dispositivo', 'id'),
              (SELECT coalesce(max(id), 1) FROM tracking.dmt_dispositivo),
              (SELECT max(id) IS NOT NULL FROM tracking.dmt_dispositivo));
SELECT setval(pg_get_serial_sequence('tracking.dmt_evento', 'id'),
              (SELECT coalesce(max(id), 1) FROM tracking.dmt_evento),
              (SELECT max(id) IS NOT NULL FROM tracking.dmt_evento));

-- ---------------------------------------------------------------------------
-- 11) system.dmt_migracion_mapa
-- ---------------------------------------------------------------------------
-- 11a) Mapa por fila: usuarios, dispositivos y asignaciones.
INSERT INTO system.dmt_migracion_mapa (
    tabla_origen, id_origen, tabla_destino, id_destino, esquema_destino, nota
)
SELECT 'traccar.public.tc_users', s.id::text, 'iam.dmt_usuario', u.id, 'iam',
       'usuario migrado con id legado preservado'
FROM system.dmt_staging_usuario s
JOIN iam.dmt_usuario u ON u.id_legado = s.id
ON CONFLICT (tabla_origen, id_origen) DO UPDATE
    SET id_destino = EXCLUDED.id_destino,
        nota = EXCLUDED.nota,
        migrado_en = now();

INSERT INTO system.dmt_migracion_mapa (
    tabla_origen, id_origen, tabla_destino, id_destino, esquema_destino, nota
)
SELECT
    CASE s.fuente
        WHEN 'traccar'    THEN 'traccar.public.tc_devices'
        WHEN 'traccar_qa' THEN 'traccar_qa.public.tc_devices'
        ELSE 'dump.traccar-pre-purga-recorridos.tc_devices'
    END,
    s.id::text,
    'tracking.dmt_dispositivo',
    d.id,
    'tracking',
    CASE WHEN s.fuente <> 'traccar'
         THEN 'dispositivo historico (id ya inexistente en produccion); identificador desambiguado y habilitado=false'
         ELSE 'dispositivo vivo; identificador (uniqueid) intacto' END
FROM system.dmt_staging_dispositivo s
JOIN tracking.dmt_dispositivo d ON d.id_legado = s.id
ON CONFLICT (tabla_origen, id_origen) DO UPDATE
    SET id_destino = EXCLUDED.id_destino,
        nota = EXCLUDED.nota,
        migrado_en = now();

INSERT INTO system.dmt_migracion_mapa (
    tabla_origen, id_origen, tabla_destino, id_destino, esquema_destino, nota
)
SELECT 'traccar.public.tc_user_device',
       s.userid || ':' || s.deviceid,
       'operations.dmt_asignacion',
       a.id,
       'operations',
       'asignacion usuario-dispositivo'
FROM system.dmt_staging_asignacion s
JOIN iam.dmt_usuario u          ON u.id_legado = s.userid
JOIN tracking.dmt_dispositivo d ON d.id_legado = s.deviceid
JOIN operations.dmt_asignacion a
  ON a.usuario_id = u.id AND a.dispositivo_id = d.id
ON CONFLICT (tabla_origen, id_origen) DO UPDATE
    SET id_destino = EXCLUDED.id_destino,
        nota = EXCLUDED.nota,
        migrado_en = now();

-- 11b) Resumen por tabla origen para el resto de objetos.
INSERT INTO system.dmt_migracion_mapa (tabla_origen, id_origen, tabla_destino, esquema_destino, nota)
VALUES
    ('traccar.public.tc_events', 'carga:resumen', 'tracking.dmt_evento', 'tracking',
     format('eventos migrados: %s', (SELECT count(*) FROM tracking.dmt_evento))),
    ('traccar.public.tc_events', 'carga:jornadas', 'operations.dmt_jornada', 'operations',
     format('jornadas derivadas de mobileJourneyStarted/Ended: %s', (SELECT count(*) FROM operations.dmt_jornada))),
    ('traccar.public.tc_positions', 'carga:bateria', 'telemetry.dmt_bateria', 'telemetry',
     format('muestras batteryLevel (7 dias): %s', (SELECT count(*) FROM telemetry.dmt_bateria WHERE atributos->>'origen' = 'tc_positions.batteryLevel'))),
    ('traccar.public.tc_devices', 'carga:bateria.history', 'telemetry.dmt_bateria', 'telemetry',
     format('muestras mobile.batteryHistory (7 dias): %s', (SELECT count(*) FROM telemetry.dmt_bateria WHERE atributos->>'origen' = 'tc_devices.mobile.batteryHistory'))),
    ('traccar.public.tc_fcm_tokens', 'carga:resumen', 'iam.dmt_token_fcm', 'iam',
     format('tokens FCM migrados: %s', (SELECT count(*) FROM iam.dmt_token_fcm))),
    ('traccar.public.tc_keystore', 'carga:resumen', 'iam.dmt_clave_firma', 'iam',
     format('claves de firma migradas: %s', (SELECT count(*) FROM iam.dmt_clave_firma))),
    ('traccar.public.tc_recovery_event', 'carga:resumen', 'operations.dmt_alerta', 'operations',
     format('alertas de recuperacion migradas: %s', (SELECT count(*) FROM operations.dmt_alerta WHERE origen = 'recuperacion'))),
    ('traccar.public.tc_mobile_messages', 'carga:evaluar', NULL, NULL,
     format('evaluar: %s filas en origen (cola/lease movil); no migrado en esta fase',
            (SELECT filas FROM system.dmt_staging_origen_pendiente
              WHERE tabla_origen = 'traccar.public.tc_mobile_messages'))),
    ('traccar.public.tc_revoked_tokens', 'carga:evaluar', 'iam.dmt_token_revocado', 'iam',
     'evaluar: 0 filas; el legado solo conserva id, no el token; la tabla nueva exige token_hash'),
    ('traccar.public.tc_statistics', 'carga:no_migrar', NULL, NULL,
     format('no migrar: %s filas de metricas internas del motor legado',
            (SELECT filas FROM system.dmt_staging_origen_pendiente
              WHERE tabla_origen = 'traccar.public.tc_statistics'))),
    ('traccar.public.tc_actions', 'carga:pendiente', 'audit.dmt_auditoria', 'audit',
     format('pendiente fuera del alcance de esta parte: %s filas (tabla equivalente audit.dmt_auditoria)',
            (SELECT filas FROM system.dmt_staging_origen_pendiente
              WHERE tabla_origen = 'traccar.public.tc_actions'))),
    ('traccar.public.tc_device_health', 'carga:pendiente', 'telemetry.dmt_salud_dispositivo', 'telemetry',
     format('pendiente fuera del alcance de esta parte: %s filas (tabla equivalente telemetry.dmt_salud_dispositivo)',
            (SELECT filas FROM system.dmt_staging_origen_pendiente
              WHERE tabla_origen = 'traccar.public.tc_device_health'))),
    ('traccar.public.tc_groups', 'carga:evaluar', NULL, NULL,
     format('evaluar: %s grupos; la tabla destino tracking.dmt_grupo no existe en el esquema de esta fase',
            (SELECT filas FROM system.dmt_staging_origen_pendiente
              WHERE tabla_origen = 'traccar.public.tc_groups')))
ON CONFLICT (tabla_origen, id_origen) DO UPDATE
    SET nota = EXCLUDED.nota,
        migrado_en = now();

COMMIT;

-- ---------------------------------------------------------------------------
-- 12) Verificacion inmediata
-- ---------------------------------------------------------------------------
SELECT 'usuarios' AS objeto, count(*) AS filas FROM iam.dmt_usuario
UNION ALL SELECT 'usuarios_habilitados', count(*) FROM iam.dmt_usuario WHERE habilitado
UNION ALL SELECT 'usuarios_administradores', count(*) FROM iam.dmt_usuario WHERE administrador
UNION ALL SELECT 'roles', count(*) FROM iam.dmt_rol
UNION ALL SELECT 'usuario_rol', count(*) FROM iam.dmt_usuario_rol
UNION ALL SELECT 'dispositivos', count(*) FROM tracking.dmt_dispositivo
UNION ALL SELECT 'asignaciones', count(*) FROM operations.dmt_asignacion
UNION ALL SELECT 'eventos', count(*) FROM tracking.dmt_evento
UNION ALL SELECT 'jornadas', count(*) FROM operations.dmt_jornada
UNION ALL SELECT 'bateria', count(*) FROM telemetry.dmt_bateria
UNION ALL SELECT 'fcm', count(*) FROM iam.dmt_token_fcm
UNION ALL SELECT 'claves_firma', count(*) FROM iam.dmt_clave_firma
UNION ALL SELECT 'alertas_recuperacion', count(*) FROM operations.dmt_alerta WHERE origen = 'recuperacion'
UNION ALL SELECT 'mapa', count(*) FROM system.dmt_migracion_mapa
ORDER BY 1;
