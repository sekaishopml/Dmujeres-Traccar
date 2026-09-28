-- ============================================================================
-- 00_compat.sql - Compatibilidad PostgreSQL 17 / 18
-- Proyecto: DMujeres Tracking - base nueva con nomenclatura dmt_
-- ============================================================================
-- La plataforma nueva expone un identificador publico UUIDv7 (RFC 9562)
-- en cada tabla. PostgreSQL 18 incorpora la funcion nativa uuidv7(); este
-- archivo define system.uuidv7() para que el DDL sea portable:
--
--   * PostgreSQL 18 o superior: envoltorio sobre pg_catalog.uuidv7().
--   * PostgreSQL 17: respaldo en plpgsql (48 bits de milisegundos Unix +
--     74 bits aleatorios, version 7, variante RFC 4122).
--
-- La verificacion de este repositorio corre sobre el contenedor dmj-db
-- (PostgreSQL 17.10), por eso se ejercita la rama de respaldo.
-- NO define reglas de negocio: solo el generador de UUID.
-- ============================================================================

BEGIN;

CREATE SCHEMA IF NOT EXISTS system;

DO $compat$
BEGIN
    IF current_setting('server_version_num')::int >= 180000 THEN
        -- PostgreSQL 18+: se usa la funcion nativa.
        CREATE OR REPLACE FUNCTION system.uuidv7() RETURNS uuid
            LANGUAGE sql VOLATILE
            AS 'SELECT pg_catalog.uuidv7()';
    ELSE
        -- PostgreSQL 17: respaldo propio.
        CREATE OR REPLACE FUNCTION system.uuidv7() RETURNS uuid
            LANGUAGE plpgsql VOLATILE
            AS $body$
DECLARE
    ts_bytes bytea;
    v_bytes  bytea;
BEGIN
    -- 48 bits altos de los milisegundos Unix, en big-endian.
    ts_bytes := substring(
        int8send((extract(epoch FROM clock_timestamp()) * 1000)::bigint)
        FROM 3
    );
    v_bytes := uuid_send(gen_random_uuid());
    v_bytes := overlay(v_bytes PLACING ts_bytes FROM 1 FOR 6);
    -- Version 7: nibble alto del byte 6 = 0111.
    v_bytes := set_byte(v_bytes, 6, (get_byte(v_bytes, 6) & 15) | 112);
    -- Variante RFC 4122: dos bits altos del byte 8 = 10.
    v_bytes := set_byte(v_bytes, 8, (get_byte(v_bytes, 8) & 63) | 128);
    RETURN encode(v_bytes, 'hex')::uuid;
END;
$body$;
    END IF;
END
$compat$;

COMMENT ON FUNCTION system.uuidv7() IS
    'UUIDv7 (RFC 9562) para id_publico. En PG18+ delega en pg_catalog.uuidv7(); en PG17 usa un respaldo plpgsql ordenable por milisegundo.';

COMMIT;
