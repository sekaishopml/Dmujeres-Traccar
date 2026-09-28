// Segmentacion de recorridos a partir de tracking.dmt_posicion.
// Mientras operations.dmt_jornada_tramo no tenga datos (el servicio de tracking
// aun no los calcula), la API deriva viajes y paradas del historico con las
// mismas reglas para reportes y replay. Los umbrales son constantes del codigo.

export const UMBRAL_MOVIMIENTO_KMH = 5;
export const MIN_PARADA_SEGUNDOS = 180;
export const MIN_VIAJE_SEGUNDOS = 60;
export const MAX_SALTO_SEGUNDOS = 900;
export const MIN_DISTANCIA_VIAJE_KM = 0.05;
// Un tramo "en movimiento" que no llega a 300 m es jitter del GPS (el equipo
// parado con la velocidad reportada oscilando entre 0 y picos altos): sus
// puntos vuelven a contar como detenidos para no partir una parada en decenas.
// Con los datos reales de Pilay del 24/09 (velocidad reportada con picos de
// 98 km/h en el mismo patio) este ruido generaba 28 paradas donde hay 5.
export const MAX_RUIDO_MOVIMIENTO_KM = 0.3;

export const ORDEN_VIAJES = {
  inicio: 'v.inicio',
  fin: 'v.fin',
  duracionMin: 'v.segundos',
  distanciaKm: 'v.km',
  velocidadMaximaKmh: 'v.velocidad_maxima',
  paradas: 'v.paradas',
  dispositivoId: 'v.dispositivo_id',
};

export const ORDEN_PARADAS = {
  inicio: 's.inicio',
  fin: 's.fin',
  duracionMin: 's.segundos',
  dispositivoId: 's.dispositivo_id',
};

// Placeholders: $1 = usuario (NULL = administrador), $2 = desde, $3 = hasta,
// $4 = dispositivo (NULL = todos). Devuelve una fila por tramo con `tipo`.
export function sqlSegmentos() {
  return `
    WITH pts AS (
      SELECT p.id, p.id_publico, p.dispositivo_id, p.registrado_en, p.latitud, p.longitud,
             p.direccion,
             coalesce(p.velocidad_kmh, 0) AS velocidad,
             (coalesce(p.velocidad_kmh, 0) > ${UMBRAL_MOVIMIENTO_KMH}) AS en_movimiento
      FROM tracking.dmt_posicion p
      JOIN tracking.dmt_dispositivo d ON d.id = p.dispositivo_id
      WHERE d.habilitado
        AND p.registrado_en >= $2 AND p.registrado_en < $3
        AND ($1::bigint IS NULL OR EXISTS (
              SELECT 1 FROM operations.dmt_asignacion a
              WHERE a.dispositivo_id = d.id AND a.usuario_id = $1 AND a.activa
                AND a.desde_en <= now() AND (a.hasta_en IS NULL OR a.hasta_en > now())))
        AND ($4::bigint IS NULL OR p.dispositivo_id = $4)
    ),
    saltos AS (
      SELECT pts.*,
             CASE WHEN lag(registrado_en) OVER w IS NULL
                    OR registrado_en - lag(registrado_en) OVER w > interval '${MAX_SALTO_SEGUNDOS} seconds'
                  THEN 1 ELSE 0 END AS salto
      FROM pts
      WINDOW w AS (PARTITION BY dispositivo_id ORDER BY registrado_en)
    ),
    islas AS (
      SELECT saltos.*, sum(salto) OVER (PARTITION BY dispositivo_id ORDER BY registrado_en) AS isla
      FROM saltos
    ),
    cambios AS (
      SELECT islas.*,
             CASE WHEN en_movimiento IS DISTINCT FROM
                       lag(en_movimiento) OVER (PARTITION BY dispositivo_id, isla ORDER BY registrado_en)
                  THEN 1 ELSE 0 END AS cambio
      FROM islas
    ),
    grupos AS (
      SELECT cambios.*, sum(cambio) OVER (PARTITION BY dispositivo_id, isla ORDER BY registrado_en) AS grupo
      FROM cambios
    ),
    pares AS (
      SELECT grupos.*,
             lag(latitud) OVER (PARTITION BY dispositivo_id, isla, grupo ORDER BY registrado_en) AS lat_previa,
             lag(longitud) OVER (PARTITION BY dispositivo_id, isla, grupo ORDER BY registrado_en) AS lon_previa
      FROM grupos
    ),
    distancias AS (
      SELECT dispositivo_id, isla, grupo,
             sum(6371 * 2 * asin(sqrt(
               power(sin(radians(latitud - lat_previa) / 2), 2)
               + cos(radians(lat_previa)) * cos(radians(latitud))
                 * power(sin(radians(longitud - lon_previa) / 2), 2)
             ))) AS km
      FROM pares
      WHERE lat_previa IS NOT NULL
      GROUP BY dispositivo_id, isla, grupo
    ),
    crudos AS (
      SELECT g.dispositivo_id, g.isla, g.grupo, bool_and(g.en_movimiento) AS movimiento,
             coalesce(d.km, 0) AS km
      FROM grupos g
      LEFT JOIN distancias d
        ON d.dispositivo_id = g.dispositivo_id AND d.isla = g.isla AND d.grupo = g.grupo
      GROUP BY g.dispositivo_id, g.isla, g.grupo, d.km
    ),
    ruido AS (
      SELECT dispositivo_id, isla, grupo
      FROM crudos
      WHERE movimiento AND km < ${MAX_RUIDO_MOVIMIENTO_KM}
    ),
    puntos2 AS (
      SELECT g.*,
             (g.en_movimiento AND NOT EXISTS (
               SELECT 1 FROM ruido r
               WHERE r.dispositivo_id = g.dispositivo_id AND r.isla = g.isla AND r.grupo = g.grupo
             )) AS mov2
      FROM grupos g
    ),
    cambios2 AS (
      SELECT puntos2.*,
             CASE WHEN mov2 IS DISTINCT FROM
                       lag(mov2) OVER (PARTITION BY dispositivo_id, isla ORDER BY registrado_en)
                  THEN 1 ELSE 0 END AS cambio2
      FROM puntos2
    ),
    grupos2 AS (
      SELECT cambios2.*, sum(cambio2) OVER (PARTITION BY dispositivo_id, isla ORDER BY registrado_en) AS grupo2
      FROM cambios2
    ),
    pares2 AS (
      SELECT grupos2.*,
             lag(latitud) OVER (PARTITION BY dispositivo_id, isla, grupo2 ORDER BY registrado_en) AS lat_previa,
             lag(longitud) OVER (PARTITION BY dispositivo_id, isla, grupo2 ORDER BY registrado_en) AS lon_previa
      FROM grupos2
    ),
    distancias2 AS (
      SELECT dispositivo_id, isla, grupo2,
             sum(6371 * 2 * asin(sqrt(
               power(sin(radians(latitud - lat_previa) / 2), 2)
               + cos(radians(lat_previa)) * cos(radians(latitud))
                 * power(sin(radians(longitud - lon_previa) / 2), 2)
             ))) AS km
      FROM pares2
      WHERE lat_previa IS NOT NULL
      GROUP BY dispositivo_id, isla, grupo2
    ),
    tramos AS (
      SELECT g.dispositivo_id, bool_and(g.mov2) AS movimiento,
             min(g.registrado_en) AS inicio, max(g.registrado_en) AS fin,
             extract(epoch FROM (max(g.registrado_en) - min(g.registrado_en))) AS segundos,
             count(*) AS puntos,
             (array_agg(g.id ORDER BY g.registrado_en))[1] AS id,
             (array_agg(g.id_publico ORDER BY g.registrado_en))[1] AS id_publico,
             (array_agg(g.latitud ORDER BY g.registrado_en))[1] AS lat_inicio,
             (array_agg(g.longitud ORDER BY g.registrado_en))[1] AS lon_inicio,
             (array_agg(g.latitud ORDER BY g.registrado_en DESC))[1] AS lat_fin,
             (array_agg(g.longitud ORDER BY g.registrado_en DESC))[1] AS lon_fin,
             (array_agg(g.direccion ORDER BY g.registrado_en))[1] AS direccion,
             max(g.velocidad) AS velocidad_maxima,
             coalesce(d.km, 0) AS km
      FROM grupos2 g
      LEFT JOIN distancias2 d
        ON d.dispositivo_id = g.dispositivo_id AND d.isla = g.isla AND d.grupo2 = g.grupo2
      GROUP BY g.dispositivo_id, g.isla, g.grupo2, d.km
    )
    SELECT 'viaje' AS tipo, t.*
    FROM tramos t
    WHERE t.movimiento
      AND t.segundos >= ${MIN_VIAJE_SEGUNDOS}
      AND t.puntos >= 2
      AND t.km >= ${MIN_DISTANCIA_VIAJE_KM}
    UNION ALL
    SELECT 'parada' AS tipo, t.*
    FROM tramos t
    WHERE NOT t.movimiento
      AND t.segundos >= ${MIN_PARADA_SEGUNDOS}
  `;
}
