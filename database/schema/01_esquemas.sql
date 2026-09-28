-- ============================================================================
-- 01_esquemas.sql - Esquemas de la base nueva
-- Proyecto: DMujeres Tracking - nomenclatura dmt_
-- ============================================================================
-- Separacion por dominio segun el plan maestro (seccion 7 y 8):
--   iam        identidad, roles, sesiones y credenciales
--   tracking   dispositivos, posiciones y eventos
--   telemetry  muestras de alta frecuencia y salud del dispositivo
--   operations jornadas, tramos, asignaciones y alertas
--   audit      trazabilidad administrativa
--   system     configuracion, mapa de migracion y version del esquema
--
-- El esquema system se crea tambien en 00_compat.sql (lo necesita la
-- funcion system.uuidv7); aqui se declara de forma explicita para que el
-- orden de aplicacion sea claro.
-- ============================================================================

BEGIN;

CREATE SCHEMA IF NOT EXISTS iam;
CREATE SCHEMA IF NOT EXISTS tracking;
CREATE SCHEMA IF NOT EXISTS telemetry;
CREATE SCHEMA IF NOT EXISTS operations;
CREATE SCHEMA IF NOT EXISTS audit;
CREATE SCHEMA IF NOT EXISTS system;

-- Metricas de consultas: requiere shared_preload_libraries='pg_stat_statements'
-- y reinicio para recolectar. La extension puede crearse sin preload, pero la
-- vista pg_stat_statements fallara hasta que el parametro este activo.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- PostGIS es opcional en el plan (geocercas / consultas espaciales).
-- Descomentar cuando se habilite esa fase y el paquete este instalado:
-- CREATE EXTENSION IF NOT EXISTS postgis;

COMMIT;
