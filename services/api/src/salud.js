// Salud, disponibilidad y version (rutas publicas, sin sesion).

import { consultar } from './db.js';
import { respuestaJson } from './http.js';
import { servicioNoDisponible } from './errores.js';

export function salud(ctx) {
  respuestaJson(ctx.res, 200, { estado: 'ok' });
}

export async function disponibilidad(ctx) {
  let baseDatos = 'ok';
  let tracking = 'ok';
  try {
    await consultar(ctx.pool, 'SELECT 1', [], { timeoutMs: 2500 });
  } catch (error) {
    ctx.log.aviso('ready_db_error', { detalle: error.message });
    baseDatos = 'error';
  }
  if (baseDatos === 'ok') {
    try {
      await consultar(ctx.pool, 'SELECT 1 FROM tracking.dmt_posicion_actual LIMIT 1', [], { timeoutMs: 2500 });
    } catch (error) {
      ctx.log.aviso('ready_tracking_error', { detalle: error.message });
      tracking = 'error';
    }
  } else {
    tracking = 'error';
  }
  if (ctx.entorno.trackingUrl) {
    tracking = (await servicioTrackingDisponible(ctx)) ? 'ok' : 'error';
  }
  if (baseDatos === 'error') {
    throw servicioNoDisponible('La base de datos no responde.');
  }
  respuestaJson(ctx.res, 200, {
    estado: tracking === 'ok' ? 'listo' : 'degradado',
    dependencias: { baseDatos, tracking },
    comprobadoEn: new Date().toISOString(),
  });
}

async function servicioTrackingDisponible(ctx) {
  const controlador = new AbortController();
  const temporizador = setTimeout(() => controlador.abort(), 2000);
  try {
    const respuesta = await fetch(ctx.entorno.trackingUrl, { signal: controlador.signal });
    return respuesta.ok;
  } catch (error) {
    ctx.log.aviso('ready_tracking_sin_respuesta', { detalle: error.message });
    return false;
  } finally {
    clearTimeout(temporizador);
  }
}

export async function version(ctx) {
  let versionEsquema = 'desconocida';
  try {
    const { rows } = await consultar(
      ctx.pool,
      'SELECT max(version) AS version FROM system.dmt_version_esquema',
      [],
      { signal: ctx.signal, timeoutMs: 2500 },
    );
    if (rows.length > 0 && rows[0].version) versionEsquema = String(rows[0].version);
  } catch (error) {
    ctx.log.aviso('version_esquema_no_disponible', { detalle: error.message });
  }
  respuestaJson(ctx.res, 200, {
    version: ctx.entorno.version,
    versionApi: 'v1',
    versionEsquema,
    commit: ctx.entorno.commit,
    construidoEn: ctx.entorno.construidoEn,
  });
}
