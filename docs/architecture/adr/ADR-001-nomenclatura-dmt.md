# ADR-001 — Nomenclatura propia `dmt_*` en la base nueva

- Fecha: 2026-09-25
- Estado: aceptada
- Ámbito: base de datos y contratos internos de la nueva plataforma

## Contexto

La instalación actual usa la nomenclatura de Traccar (`tc_users`, `tc_devices`,
`tc_positions`, …). El plan maestro pide dejar de depender de Traccar y que el
producto tenga identidad propia. Renombrar en caliente la base de producción
rompería el servidor actual y a la App en operación, así que la sustitución de
nombres se aplica **solo a la base nueva**.

## Decisión

1. La base nueva se crea con los esquemas del plan (`iam`, `tracking`,
   `telemetry`, `operations`, `audit`, `system`) y **todas las tablas llevan
   prefijo `dmt_` con nombre en español singular**: `dmt_usuario`,
   `dmt_dispositivo`, `dmt_posicion`, `dmt_posicion_actual`, `dmt_evento`,
   `dmt_bateria`, `dmt_jornada`, `dmt_migracion_mapa`, etc.
2. Columnas en español, `snake_case`. La PK es `id`; la referencia pública
   ordenable es `id_publico UUID` (`uuidv7()` en PostgreSQL 18).
3. Índices y restricciones con prefijo propio:
   `idx_dmt_posicion_dispositivo_tiempo`, `brin_dmt_posicion_tiempo`,
   `uq_dmt_dispositivo_identificador`, `fk_dmt_posicion_dispositivo`.
4. La migración conserva los identificadores legados en columnas `id_legado`
   (usuarios y dispositivos) y deja el mapa completo en
   `system.dmt_migracion_mapa` (`tabla_origen`, `id_origen`, `id_destino`).
5. **No se renombra nada en producción**: `/DMujeres-Tracking` y su base
   `traccar` siguen con `tc_*` hasta el cutover, y aun después el legado se
   conserva como respaldo de solo lectura.
6. **El contrato con la App no cambia**: protocolo OsmAnd (`:5055`), atributos
   `mobile.*`, canal `/api/mobile/v1/*`, claves de dispositivo y FCM mantienen
   sus nombres. La compatibilidad manda sobre la estética.
7. La API propia `/api/v1` mantiene las rutas del plan y usa campos de DTO en
   español (`documento`/`tipo` propios), documentados en `packages/contracts`.

## Sustituye

Los nombres ilustrativos en inglés del plan (`iam.user_account`,
`tracking.device`, `tracking.position`, …) quedan reemplazados por sus
equivalentes `dmt_*` en español. La estructura, particionado, índices y
requisitos del plan no cambian.

## Consecuencias

- El diccionario legado→nuevo se documenta en `docs/database/MAPA-LEGADO-DMT.md`
  y se materializa en `system.dmt_migracion_mapa` durante la migración.
- Cualquier script nuevo que mencione `tc_` para la plataforma nueva es un
  error; `tc_` solo aparece en el mapa de migración y en el legado.
- El retiro gradual de Traccar no obliga a tocar los nombres históricos: el
  histórico se copia a las tablas `dmt_*` conservando fechas e identificadores.
