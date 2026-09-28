// Geocodificacion inversa con Nominatim (OpenStreetMap): direccion corta en
// español a partir de una coordenada. Cache en memoria (TTL 24 h, tope 5000
// entradas, eviccion FIFO) y ritmo maximo de 1 peticion por segundo, segun la
// politica de uso de Nominatim. Ninguna funcion lanza: si falla o no hay red
// devuelve null.

import { datosInvalidos } from './errores.js';
import { respuestaJson } from './http.js';

const URL_NOMINATIM = 'https://nominatim.openstreetmap.org/reverse';
const AGENTE = 'DMujeresTracking/0.1 (contacto interno: soporte@dmujeres.local)';
const TIEMPO_LIMITE_MS = 4000;
const INTERVALO_MINIMO_MS = 1000;
const TTL_MS = 24 * 60 * 60 * 1000;
const MAX_ENTRADAS = 5000;
const DECIMALES_CLAVE = 5;

const cache = new Map();
let cola = Promise.resolve();
let ultimaPeticion = 0;

function coordenadaValida(valor, minimo, maximo) {
  if (valor === null || valor === undefined || valor === '') return null;
  const numero = Number(valor);
  if (!Number.isFinite(numero) || numero < minimo || numero > maximo) return null;
  return numero;
}

function claveDeCache(lat, lon) {
  return `${lat.toFixed(DECIMALES_CLAVE)}/${lon.toFixed(DECIMALES_CLAVE)}`;
}

function leerCache(clave) {
  const entrada = cache.get(clave);
  if (!entrada) return undefined;
  if (Date.now() - entrada.guardadoEn > TTL_MS) {
    cache.delete(clave);
    return undefined;
  }
  return entrada.direccion;
}

function guardarCache(clave, direccion) {
  cache.delete(clave);
  cache.set(clave, { direccion, guardadoEn: Date.now() });
  while (cache.size > MAX_ENTRADAS) {
    const masAntigua = cache.keys().next().value;
    if (masAntigua === undefined) break;
    cache.delete(masAntigua);
  }
}

function esperar(ms, signal) {
  return new Promise((resolver) => {
    const completar = () => {
      clearTimeout(temporizador);
      signal?.removeEventListener('abort', completar);
      resolver();
    };
    const temporizador = setTimeout(completar, ms);
    signal?.addEventListener('abort', completar, { once: true });
    if (signal?.aborted) completar();
  });
}

// Serializa el acceso al geocodificador: cada turno espera a que se cumpla el
// segundo desde el inicio de la peticion anterior.
async function esperarTurno(signal) {
  const turno = cola.then(async () => {
    const espera = INTERVALO_MINIMO_MS - (Date.now() - ultimaPeticion);
    if (espera > 0) await esperar(espera, signal);
    ultimaPeticion = Date.now();
  });
  cola = turno.then(
    () => {},
    () => {},
  );
  return turno;
}

async function consultarNominatim(lat, lon, signal) {
  const controlador = new AbortController();
  const porTiempo = setTimeout(() => controlador.abort(), TIEMPO_LIMITE_MS);
  const porSenal = () => controlador.abort();
  signal?.addEventListener('abort', porSenal, { once: true });
  try {
    const parametros = new URLSearchParams({
      format: 'jsonv2',
      lat: String(lat),
      lon: String(lon),
      zoom: '18',
      addressdetails: '1',
      'accept-language': 'es',
    });
    const respuesta = await fetch(`${URL_NOMINATIM}?${parametros.toString()}`, {
      headers: { 'User-Agent': AGENTE, Accept: 'application/json' },
      signal: controlador.signal,
    });
    if (!respuesta.ok) return null;
    return await respuesta.json();
  } catch {
    return null;
  } finally {
    clearTimeout(porTiempo);
    signal?.removeEventListener('abort', porSenal);
  }
}

function primeroConValor(address, claves) {
  if (!address || typeof address !== 'object') return '';
  for (const clave of claves) {
    const valor = address[clave];
    if (typeof valor === 'string' && valor.trim() !== '') return valor.trim();
  }
  return '';
}

export function componerDireccion(datos) {
  const address = datos?.address;
  if (address && typeof address === 'object') {
    const partes = [
      primeroConValor(address, ['road', 'pedestrian', 'footway']),
      primeroConValor(address, ['suburb', 'neighbourhood']),
      primeroConValor(address, ['city', 'town', 'village']),
      primeroConValor(address, ['state']),
      primeroConValor(address, ['country']),
    ].filter(Boolean);
    if (partes.length > 0) return partes.join(', ');
  }
  const display = typeof datos?.display_name === 'string' ? datos.display_name.trim() : '';
  return display !== '' ? display : null;
}

// Devuelve la direccion del punto o null. Los aciertos se memorizan; los
// fallos no, para permitir reintentos cuando vuelva la red.
export async function direccionDe(lat, lon, opciones = {}) {
  try {
    const latitud = coordenadaValida(lat, -90, 90);
    const longitud = coordenadaValida(lon, -180, 180);
    if (latitud === null || longitud === null) return null;
    const clave = claveDeCache(latitud, longitud);
    const cacheada = leerCache(clave);
    if (cacheada !== undefined) return cacheada;
    const { signal } = opciones;
    if (signal?.aborted) return null;
    await esperarTurno(signal);
    if (signal?.aborted) return null;
    const direccion = componerDireccion(await consultarNominatim(latitud, longitud, signal));
    if (direccion) guardarCache(clave, direccion);
    return direccion;
  } catch {
    return null;
  }
}

// Solo cache: nunca llama a Nominatim ni espera al ritmo. Es la via que usa el
// listado de paradas para no frenar la respuesta.
export function direccionEnCache(lat, lon) {
  try {
    const latitud = coordenadaValida(lat, -90, 90);
    const longitud = coordenadaValida(lon, -180, 180);
    if (latitud === null || longitud === null) return null;
    return leerCache(claveDeCache(latitud, longitud)) ?? null;
  } catch {
    return null;
  }
}

function leerCoordenada(valor, minimo, maximo, nombre) {
  const texto = valor === null ? '' : String(valor).trim();
  if (texto === '') throw datosInvalidos(`Falta el parámetro ${nombre}.`);
  const numero = Number(texto);
  if (!Number.isFinite(numero) || numero < minimo || numero > maximo) {
    throw datosInvalidos(`El parámetro ${nombre} debe ser un número entre ${minimo} y ${maximo}.`);
  }
  return numero;
}

export async function obtenerDireccion(ctx) {
  const lat = leerCoordenada(ctx.url.searchParams.get('lat'), -90, 90, 'lat');
  const lon = leerCoordenada(ctx.url.searchParams.get('lon'), -180, 180, 'lon');
  const direccion = await direccionDe(lat, lon, { signal: ctx.signal });
  respuestaJson(ctx.res, 200, { direccion });
}
