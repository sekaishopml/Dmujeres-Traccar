// Cronograma de actividades para el panel: lo que cada persona declaró en la
// app (operations.dmt_actividad) junto con lo que dice su recorrido a esa
// hora, para auditar lo declarado contra lo registrado:
//  - `enHora`: el fix más cercano a la hora declarada (±20 min) y, si a esa
//    hora estaba detenida, la parada (por permanencia, paradas.js).
//  - `registro`: cuándo se cargó y, solo si fue con jornada iniciada, dónde.
// Las direcciones salen de la caché del geocodificador; si falta se precalienta
// y el panel las pide por /geocode/reverse.

import { consultar } from './db.js';
import { datosInvalidos, noEncontrado } from './errores.js';
import { leerCuerpoJson, respuestaJson } from './http.js';
import { PREDICADO_PERMISO, permisoDe } from './flota.js';
import { detectarParadas } from './paradas.js';
import { precalentar, resolucionEnCache } from './geocodigo.js';

const FECHA_RE = /^\d{4}-\d{2}-\d{2}$/;
const MAX_DIAS = 62;
const VENTANA_HORA_MS = 20 * 60_000;
const MARGEN_PARADA_MS = 5 * 60_000;
// Ecuador continental: UTC-5 todo el año.
const DESFASE = '-05:00';

function instanteDeclarado(fecha, hora) {
  return new Date(`${fecha}T${hora}:00${DESFASE}`).getTime();
}

function direccion(lat, lon, precision) {
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  const r = resolucionEnCache(lat, lon, precision);
  if (!r) precalentar(lat, lon, precision);
  return r?.direccion ?? null;
}

export async function listarCronograma(ctx) {
  const desde = ctx.url.searchParams.get('desde');
  const hasta = ctx.url.searchParams.get('hasta');
  if (!FECHA_RE.test(desde ?? '') || !FECHA_RE.test(hasta ?? '')) {
    throw datosInvalidos('desde y hasta deben ser fechas YYYY-MM-DD.');
  }
  const dias = (Date.parse(hasta) - Date.parse(desde)) / 86_400_000;
  if (!(dias >= 0) || dias > MAX_DIAS) throw datosInvalidos(`El rango debe ser de 0 a ${MAX_DIAS} días.`);
  const equipo = ctx.url.searchParams.get('dispositivoId');

  const { rows } = await consultar(
    ctx.pool,
    `SELECT a.id_publico, a.dispositivo_id, d.id_publico AS dispositivo_publico, d.nombre,
            to_char(a.fecha, 'YYYY-MM-DD') AS fecha, a.hora, a.hora_fin, a.tipo, a.lugar, a.nota,
            a.registrado_en, a.con_jornada, a.latitud, a.longitud, a.precision_m
       FROM operations.dmt_actividad a
       JOIN tracking.dmt_dispositivo d ON d.id = a.dispositivo_id
      WHERE NOT a.eliminada
        AND a.fecha BETWEEN $2::date AND $3::date
        AND ${PREDICADO_PERMISO}
        AND ($4::text IS NULL OR d.id_publico::text = $4 OR d.id::text = $4)
      ORDER BY a.fecha, d.nombre, a.hora, a.registrado_en`,
    [permisoDe(ctx.usuario), desde, hasta, equipo],
    { signal: ctx.signal, timeoutMs: 20000 },
  );

  // Recorrido de los equipos con actividades, una sola consulta para el rango.
  const equipos = [...new Set(rows.map((f) => Number(f.dispositivo_id)))];
  const porEquipo = new Map();
  if (equipos.length > 0) {
    const pos = await consultar(
      ctx.pool,
      `SELECT dispositivo_id, registrado_en, latitud, longitud, precision_m
         FROM tracking.dmt_posicion
        WHERE dispositivo_id = ANY($1::bigint[])
          AND registrado_en >= ($2::date::timestamp AT TIME ZONE 'America/Guayaquil') - interval '1 hour'
          AND registrado_en < (($3::date + 1)::timestamp AT TIME ZONE 'America/Guayaquil') + interval '1 hour'
        ORDER BY dispositivo_id, registrado_en`,
      [equipos, desde, hasta],
      { signal: ctx.signal, timeoutMs: 20000 },
    );
    for (const p of pos.rows) {
      const id = Number(p.dispositivo_id);
      if (!porEquipo.has(id)) porEquipo.set(id, []);
      porEquipo.get(id).push({
        registradoEn: p.registrado_en,
        latitud: Number(p.latitud),
        longitud: Number(p.longitud),
        precisionM: p.precision_m === null ? null : Number(p.precision_m),
      });
    }
  }
  const paradasPorEquipo = new Map();
  for (const [id, puntos] of porEquipo) paradasPorEquipo.set(id, detectarParadas(puntos));

  const datos = rows.map((f) => {
    const id = Number(f.dispositivo_id);
    const t = instanteDeclarado(f.fecha, f.hora);
    const puntos = porEquipo.get(id) ?? [];
    let cercano = null;
    let mejor = Infinity;
    for (const p of puntos) {
      const delta = Math.abs(new Date(p.registradoEn).getTime() - t);
      if (delta < mejor) {
        mejor = delta;
        cercano = p;
      }
    }
    // La hora declarada va redondeada al minuto: se acepta la parada que toque
    // la ventana de ±5 min (la más cercana a la hora).
    const cerca = (s) => Math.max(0, s.inicio.getTime() - t, t - s.fin.getTime());
    const parada = (paradasPorEquipo.get(id) ?? [])
      .filter((s) => cerca(s) <= MARGEN_PARADA_MS)
      .sort((a, b) => cerca(a) - cerca(b))[0] ?? null;
    const enHora = cercano && mejor <= VENTANA_HORA_MS
      ? {
          latitud: parada ? parada.latitud : cercano.latitud,
          longitud: parada ? parada.longitud : cercano.longitud,
          desfaseMin: Math.round(mejor / 60_000),
          detenida: parada !== null,
          paradaDesde: parada ? parada.inicio.toISOString() : null,
          paradaHasta: parada ? parada.fin.toISOString() : null,
          direccion: parada
            ? direccion(parada.latitud, parada.longitud, parada.precisionM)
            : direccion(cercano.latitud, cercano.longitud, cercano.precisionM),
        }
      : null;
    const lat = f.latitud === null ? null : Number(f.latitud);
    const lon = f.longitud === null ? null : Number(f.longitud);
    return {
      id: f.id_publico,
      dispositivoId: f.dispositivo_publico,
      nombre: f.nombre,
      fecha: f.fecha,
      hora: f.hora,
      horaFin: f.hora_fin ?? null,
      tipo: f.tipo,
      lugar: f.lugar,
      nota: f.nota,
      registro: {
        en: f.registrado_en instanceof Date ? f.registrado_en.toISOString() : f.registrado_en,
        conJornada: f.con_jornada,
        latitud: lat,
        longitud: lon,
        direccion: lat !== null ? direccion(lat, lon, f.precision_m === null ? null : Number(f.precision_m)) : null,
      },
      enHora,
    };
  });
  respuestaJson(ctx.res, 200, { desde, hasta, datos });
}

// Sin marca previa (cuenta nueva o primera vez), cuenta como nuevo lo de la
// última semana: avisa lo reciente sin inundar con todo el historial.
const VENTANA_SIN_MARCA = "interval '7 days'";

// GET /api/v1/cronograma/novedades -> {total, personas:[{dispositivoId,
// nombre, nuevas, ultimaEn}]}: actividades cargadas o editadas en la app
// después de la última vez que esta cuenta abrió el cronograma de cada
// persona. `total` es la suma (número del menú).
export async function novedadesCronograma(ctx) {
  const { rows } = await consultar(
    ctx.pool,
    `SELECT d.id_publico AS dispositivo_publico, d.nombre,
            count(*)::int AS nuevas, max(a.actualizado_en) AS ultima_en
       FROM operations.dmt_actividad a
       JOIN tracking.dmt_dispositivo d ON d.id = a.dispositivo_id
       LEFT JOIN operations.dmt_cronograma_visto v
              ON v.dispositivo_id = a.dispositivo_id AND v.usuario_id = $2
      WHERE NOT a.eliminada
        AND ${PREDICADO_PERMISO}
        AND a.actualizado_en > COALESCE(v.visto_en, now() - ${VENTANA_SIN_MARCA})
      GROUP BY d.id_publico, d.nombre
      ORDER BY max(a.actualizado_en) DESC`,
    [permisoDe(ctx.usuario), ctx.usuario.id],
    { signal: ctx.signal },
  );
  const personas = rows.map((fila) => ({
    dispositivoId: fila.dispositivo_publico,
    nombre: fila.nombre,
    nuevas: Number(fila.nuevas),
    ultimaEn: fila.ultima_en instanceof Date ? fila.ultima_en.toISOString() : fila.ultima_en,
  }));
  respuestaJson(ctx.res, 200, { total: personas.reduce((suma, p) => suma + p.nuevas, 0), personas });
}

// POST /api/v1/cronograma/visto {dispositivoId}: esta cuenta ya vio el
// cronograma de esa persona hasta ahora (se borra su aviso).
export async function marcarCronogramaVisto(ctx) {
  const cuerpo = await leerCuerpoJson(ctx.req, 2048);
  const equipo = typeof cuerpo?.dispositivoId === 'string' ? cuerpo.dispositivoId.trim() : '';
  if (equipo === '') throw datosInvalidos('dispositivoId es obligatorio.');
  const { rowCount } = await consultar(
    ctx.pool,
    `INSERT INTO operations.dmt_cronograma_visto (usuario_id, dispositivo_id, visto_en)
     SELECT $2, d.id, now()
       FROM tracking.dmt_dispositivo d
      WHERE (d.id_publico::text = $3 OR d.id::text = $3)
        AND ${PREDICADO_PERMISO}
     ON CONFLICT (usuario_id, dispositivo_id) DO UPDATE SET visto_en = now()`,
    [permisoDe(ctx.usuario), ctx.usuario.id, equipo],
    { signal: ctx.signal },
  );
  if (rowCount === 0) throw noEncontrado('La persona no existe o no está a tu cargo.');
  respuestaJson(ctx.res, 200, { ok: true });
}
