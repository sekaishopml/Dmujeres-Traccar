// Ruteo por calles para los tramos que quedan "a saltos" en el Replay: el
// servicio local (GraphHopper, grafo de Ecuador) devuelve el camino que siguen
// las vias entre dos fixes consecutivos. Cubre dos casos:
//  - huecos formales (mas de 10 min sin senal), con tope de 4 h;
//  - tramos sueltos (45 s a 10 min entre fixes) que dejan la traza cortando
//    esquinas, como la tarde de Pilay con cadencia de parado mientras conducia.
// Es una ESTIMACION para el dibujo; las posiciones registradas no cambian.
// Cache en memoria (aciertos 7 dias, fallos 1 min, tope 2000 entradas) y
// presupuesto de tiempo por carga: si el ruteo no responde, el tramo se dibuja
// como siempre. Ninguna funcion lanza.

const URL_RUTEO = process.env.DMJ_RUTEO_URL ?? 'http://127.0.0.1:8992';
const TIEMPO_LIMITE_MS = 2500;
const TTL_ACIERTO_MS = 7 * 24 * 60 * 60 * 1000;
const TTL_FALLO_MS = 60 * 1000;
const MAX_ENTRADAS = 2000;

// Solo se estiman tramos con desplazamiento real: menos de 150 m es el vehiculo
// parado con jitter y el ruteo devolveria vueltas absurdas a la manzana.
export const MIN_DISTANCIA_RUTEO_M = 150;
// Desde 45 s entre fixes el trazo recto ya corta esquinas; con cadencia de
// movimiento (10 s) no se toca nada.
export const MIN_SEPARACION_RUTEO_SEGUNDOS = 45;
// Mas de 4 h suele ser el vehiculo apagado: una ruta inventada ahi no aporta.
export const MAX_TRAMO_RUTEO_SEGUNDOS = 4 * 60 * 60;
// Topes por carga del Replay: mas alla, los tramos sobrantes quedan rectos.
const MAX_TRAMOS_ESTIMADOS = 200;
const TANDA = 8;
const PRESUPUESTO_MS = 3000;

const cache = new Map();

function claveDe(a, b) {
  const punto = (p) => `${p.latitud.toFixed(5)},${p.longitud.toFixed(5)}`;
  return `${punto(a)}>${punto(b)}`;
}

// Distancia aproximada en metros entre dos fixes (haversine).
function distanciaM(a, b) {
  const radioTierra = 6371000;
  const dLat = ((b.latitud - a.latitud) * Math.PI) / 180;
  const dLon = ((b.longitud - a.longitud) * Math.PI) / 180;
  const lat1 = (a.latitud * Math.PI) / 180;
  const lat2 = (b.latitud * Math.PI) / 180;
  const h =
    Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
  return 2 * radioTierra * Math.asin(Math.sqrt(h));
}

function leerCache(clave) {
  const entrada = cache.get(clave);
  if (!entrada) return undefined;
  const ttl = entrada.trazado ? TTL_ACIERTO_MS : TTL_FALLO_MS;
  if (Date.now() - entrada.guardadoEn > ttl) {
    cache.delete(clave);
    return undefined;
  }
  return entrada.trazado;
}

function guardarCache(clave, trazado) {
  cache.delete(clave);
  cache.set(clave, { trazado, guardadoEn: Date.now() });
  while (cache.size > MAX_ENTRADAS) {
    const masAntigua = cache.keys().next().value;
    if (masAntigua === undefined) break;
    cache.delete(masAntigua);
  }
}

// Camino [lon,lat] entre dos fixes, o null si el ruteo no responde o no hay
// camino. Los extremos ya vienen de posiciones reales.
async function trazar(desde, hasta, signal) {
  const clave = claveDe(desde, hasta);
  const enCache = leerCache(clave);
  if (enCache !== undefined) return enCache;
  const controlador = new AbortController();
  const temporizador = setTimeout(() => controlador.abort(), TIEMPO_LIMITE_MS);
  const abortar = () => controlador.abort();
  signal?.addEventListener('abort', abortar, { once: true });
  try {
    const respuesta = await fetch(`${URL_RUTEO}/route`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        from: [desde.longitud, desde.latitud],
        to: [hasta.longitud, hasta.latitud],
      }),
      signal: controlador.signal,
    });
    if (!respuesta.ok) {
      guardarCache(clave, null);
      return null;
    }
    const datos = await respuesta.json();
    const trazado =
      Array.isArray(datos.points) && datos.points.length >= 2 ? datos.points : null;
    guardarCache(clave, trazado);
    return trazado;
  } catch {
    guardarCache(clave, null);
    return null;
  } finally {
    clearTimeout(temporizador);
    signal?.removeEventListener('abort', abortar);
  }
}

// Devuelve los tramos a estimar con su trazado por calles: [{desde, hasta,
// trazado}] con los instantes ISO de los fixes que los cierran. El llamador
// (replay.js) los manda tal cual a la web, que los dibuja como un tramo mas.
export async function estimarTramos(posiciones, signal) {
  const candidatos = [];
  for (let i = 1; i < posiciones.length && candidatos.length < MAX_TRAMOS_ESTIMADOS; i += 1) {
    const anterior = posiciones[i - 1];
    const actual = posiciones[i];
    const segundos = (new Date(actual.registradoEn).getTime() - new Date(anterior.registradoEn).getTime()) / 1000;
    if (!(segundos >= MIN_SEPARACION_RUTEO_SEGUNDOS && segundos <= MAX_TRAMO_RUTEO_SEGUNDOS)) continue;
    if (distanciaM(anterior, actual) < MIN_DISTANCIA_RUTEO_M) continue;
    candidatos.push([anterior, actual]);
  }
  if (candidatos.length === 0) return [];
  const inicio = Date.now();
  const estimados = [];
  for (let i = 0; i < candidatos.length; i += TANDA) {
    if (Date.now() - inicio > PRESUPUESTO_MS) break;
    const tanda = candidatos.slice(i, i + TANDA);
    const trazados = await Promise.all(tanda.map(([a, b]) => trazar(a, b, signal)));
    tanda.forEach(([a, b], j) => {
      if (trazados[j]) {
        estimados.push({ desde: a.registradoEn, hasta: b.registradoEn, trazado: trazados[j] });
      }
    });
  }
  return estimados;
}
