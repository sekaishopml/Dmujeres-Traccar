// Dominio reportes: viajes, paradas y resumen operativo del rango.
// Deriva los tramos del historico con src/segmentos.js (dmt_jornada_tramo aun
// no se calcula en la plataforma nueva).

import { consultar } from './db.js';
import { iso } from './dto.js';
import { direccionEnCache } from './geocodigo.js';
import { leerOrden, leerPaginacion, leerRango, respuestaJson } from './http.js';
import { permisoDe } from './flota.js';
import { ORDEN_PARADAS, ORDEN_VIAJES, sqlSegmentos } from './segmentos.js';

const DURACION_MAXIMA_DIAS = 92;

function redondear(valor, decimales) {
  if (valor === null || valor === undefined || !Number.isFinite(Number(valor))) return null;
  const factor = 10 ** decimales;
  return Math.round(Number(valor) * factor) / factor;
}

function aViaje(fila) {
  const segundos = Number(fila.segundos);
  const km = Number(fila.km);
  return {
    id: Number(fila.id),
    dispositivoId: Number(fila.dispositivo_id),
    idPublico: fila.id_publico,
    inicio: iso(fila.inicio),
    fin: iso(fila.fin),
    duracionMin: redondear(segundos / 60, 1),
    distanciaKm: redondear(km, 3),
    velocidadPromedioKmh: segundos > 0 ? redondear((km / segundos) * 3600, 1) : null,
    velocidadMaximaKmh: Number(fila.velocidad_maxima) > 0 ? redondear(fila.velocidad_maxima, 1) : null,
    origen: { latitud: Number(fila.lat_inicio), longitud: Number(fila.lon_inicio) },
    destino: { latitud: Number(fila.lat_fin), longitud: Number(fila.lon_fin) },
    paradas: Number(fila.paradas),
  };
}

function aParada(fila) {
  const latitud = Number(fila.lat_inicio);
  const longitud = Number(fila.lon_inicio);
  return {
    id: Number(fila.id),
    dispositivoId: Number(fila.dispositivo_id),
    idPublico: fila.id_publico,
    inicio: iso(fila.inicio),
    fin: iso(fila.fin),
    duracionMin: redondear(Number(fila.segundos) / 60, 1),
    latitud,
    longitud,
    direccion: direccionEnCache(latitud, longitud),
  };
}

// El parámetro dispositivoId del query puede venir como idPublico (UUID), id
// legado o id interno; el SQL de segmentos filtra por el id numérico. Sin
// coincidencia se devuelve -1 para que la consulta no devuelva filas.
async function resolverDispositivoId(ctx) {
  const valor = ctx.url.searchParams.get('dispositivoId');
  if (!valor) return null;
  const { rows } = await consultar(
    ctx.pool,
    `SELECT d.id FROM tracking.dmt_dispositivo d
     WHERE d.id_publico::text = $1 OR d.id_legado::text = $1 OR d.id::text = $1
     LIMIT 1`,
    [String(valor)],
    { signal: ctx.signal },
  );
  return rows[0] ? Number(rows[0].id) : -1;
}

function parametrosSegmentos(ctx, { pagina, tamano, desplazamiento, dispositivoId }) {
  const rango = leerRango(ctx.url, {
    porDefecto: 'hoy',
    maxDias: DURACION_MAXIMA_DIAS,
    zonaHoraria: ctx.entorno.zonaHoraria,
  });
  const valores = [permisoDe(ctx.usuario), rango.desde, rango.hasta, dispositivoId];
  if (pagina !== undefined) valores.push(tamano, desplazamiento);
  return { valores, rango };
}

export async function listarViajes(ctx) {
  const { pagina, tamano, desplazamiento } = leerPaginacion(ctx.url);
  const orden = leerOrden(ctx.url, ORDEN_VIAJES, 'v.inicio DESC');
  const { valores } = parametrosSegmentos(ctx, {
    pagina,
    tamano,
    desplazamiento,
    dispositivoId: await resolverDispositivoId(ctx),
  });
  const { rows } = await consultar(
    ctx.pool,
    `WITH tramos AS (${sqlSegmentos()}),
     viajes AS (SELECT * FROM tramos WHERE tipo = 'viaje'),
     paradas AS (SELECT * FROM tramos WHERE tipo = 'parada')
     SELECT v.*,
            (SELECT count(*) FROM paradas s
             WHERE s.dispositivo_id = v.dispositivo_id
               AND s.inicio > v.inicio AND s.fin < v.fin) AS paradas,
            count(*) OVER() AS total_filas
     FROM viajes v
     ORDER BY ${orden.sql}, v.dispositivo_id
     LIMIT $5 OFFSET $6`,
    valores,
    { signal: ctx.signal, timeoutMs: 20000 },
  );
  respuestaJson(ctx.res, 200, {
    datos: rows.map(aViaje),
    total: rows.length > 0 ? Number(rows[0].total_filas) : 0,
    pagina,
    tamano,
  });
}

export async function listarParadas(ctx) {
  const { pagina, tamano, desplazamiento } = leerPaginacion(ctx.url);
  const orden = leerOrden(ctx.url, ORDEN_PARADAS, 's.inicio DESC');
  const { valores } = parametrosSegmentos(ctx, {
    pagina,
    tamano,
    desplazamiento,
    dispositivoId: await resolverDispositivoId(ctx),
  });
  const { rows } = await consultar(
    ctx.pool,
    `WITH tramos AS (${sqlSegmentos()})
     SELECT s.*, count(*) OVER() AS total_filas
     FROM tramos s
     WHERE s.tipo = 'parada'
     ORDER BY ${orden.sql}, s.dispositivo_id
     LIMIT $5 OFFSET $6`,
    valores,
    { signal: ctx.signal, timeoutMs: 20000 },
  );
  respuestaJson(ctx.res, 200, {
    datos: rows.map(aParada),
    total: rows.length > 0 ? Number(rows[0].total_filas) : 0,
    pagina,
    tamano,
  });
}

export async function obtenerResumen(ctx) {
  const { valores, rango } = parametrosSegmentos(ctx, {
    dispositivoId: await resolverDispositivoId(ctx),
  });
  const { rows } = await consultar(
    ctx.pool,
    `WITH tramos AS (${sqlSegmentos()}),
     viajes AS (SELECT * FROM tramos WHERE tipo = 'viaje'),
     paradas AS (SELECT * FROM tramos WHERE tipo = 'parada'),
     por_dispositivo AS (
       SELECT v.dispositivo_id,
              sum(v.km) AS distancia_km,
              sum(v.segundos) AS duracion_s,
              count(*) AS viajes,
              (SELECT count(*) FROM paradas s WHERE s.dispositivo_id = v.dispositivo_id) AS paradas
       FROM viajes v
       GROUP BY v.dispositivo_id
     ),
     posiciones AS (
       SELECT p.dispositivo_id, count(*) AS total, max(p.registrado_en) AS ultima
       FROM tracking.dmt_posicion p
       JOIN tracking.dmt_dispositivo d ON d.id = p.dispositivo_id
       WHERE d.habilitado
         AND p.registrado_en >= $2 AND p.registrado_en < $3
         AND ($1::bigint IS NULL OR EXISTS (
               SELECT 1 FROM operations.dmt_asignacion a
               WHERE a.dispositivo_id = d.id AND a.usuario_id = $1 AND a.activa
                 AND a.desde_en <= now() AND (a.hasta_en IS NULL OR a.hasta_en > now())))
         AND ($4::bigint IS NULL OR p.dispositivo_id = $4)
       GROUP BY p.dispositivo_id
     )
     SELECT d.id AS dispositivo_id, d.id_publico, d.nombre,
            coalesce(pd.distancia_km, 0) AS distancia_km,
            coalesce(pd.duracion_s, 0) AS duracion_s,
            coalesce(pd.viajes, 0) AS viajes,
            coalesce(pd.paradas, 0) AS paradas,
            pos.total AS total_posiciones, pos.ultima AS ultima_posicion
     FROM posiciones pos
     JOIN tracking.dmt_dispositivo d ON d.id = pos.dispositivo_id
     LEFT JOIN por_dispositivo pd ON pd.dispositivo_id = pos.dispositivo_id
     ORDER BY d.nombre, d.id`,
    valores,
    { signal: ctx.signal, timeoutMs: 20000 },
  );
  const porDispositivo = rows.map((fila) => ({
    dispositivoId: Number(fila.dispositivo_id),
    idPublico: fila.id_publico,
    nombre: fila.nombre,
    distanciaKm: redondear(fila.distancia_km, 3),
    duracionMin: redondear(Number(fila.duracion_s) / 60, 1),
    viajes: Number(fila.viajes),
    paradas: Number(fila.paradas),
    ultimaPosicionEn: iso(fila.ultima_posicion),
  }));
  respuestaJson(ctx.res, 200, {
    desde: rango.desde.toISOString(),
    hasta: rango.hasta.toISOString(),
    dispositivos: porDispositivo.length,
    posiciones: rows.reduce((total, fila) => total + Number(fila.total_posiciones), 0),
    distanciaTotalKm: redondear(rows.reduce((total, fila) => total + Number(fila.distancia_km), 0), 3),
    duracionTotalMin: redondear(rows.reduce((total, fila) => total + Number(fila.duracion_s), 0) / 60, 1),
    viajes: rows.reduce((total, fila) => total + Number(fila.viajes), 0),
    paradas: rows.reduce((total, fila) => total + Number(fila.paradas), 0),
    porDispositivo,
  });
}
