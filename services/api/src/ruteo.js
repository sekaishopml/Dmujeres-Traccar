// Reconstrucción por calles para los tramos que quedan "a saltos" en el
// Replay: el servicio local (GraphHopper, grafo de Ecuador) devuelve el camino
// que siguen las vías entre fixes. Cubre dos casos:
//  - huecos formales (más de 10 min sin señal), con tope de 4 h;
//  - tramos sueltos (45 s a 10 min entre fixes) que dejan la traza cortando
//    esquinas, como la tarde de Pilay con cadencia de parado mientras conducía.
// Semántica honesta (ADR-007/008): MATCHED si el hueco trae >=2 fixes
// intermedios y el /match los ajusta a vía; ESTIMATED si se resuelve por
// /route A→B sin observaciones. Sin respuesta → sin tramo (recta punteada en
// la web, nunca se inventa). Las posiciones registradas no cambian.
// Cache en memoria (aciertos 7 días, fallos 1 min, tope 2000) y presupuesto
// por carga: si el ruteo no responde, el tramo queda recto. Ninguna función
// lanza.

const URL_RUTEO = process.env.DMJ_RUTEO_URL ?? 'http://127.0.0.1:8992';
const TIEMPO_LIMITE_MS = 2500;
const TTL_ACIERTO_MS = 7 * 24 * 60 * 60 * 1000;
const TTL_FALLO_MS = 60 * 1000;
const MAX_ENTRADAS = 2000;

// Solo se reconstruyen tramos con desplazamiento real: menos de 150 m es el
// vehículo parado con jitter y el ruteo devolvería vueltas absurdas.
export const MIN_DISTANCIA_RUTEO_M = 150;
// Desde 45 s entre fixes el trazo recto ya corta esquinas; con cadencia de
// movimiento (10 s) no se toca nada.
export const MIN_SEPARACION_RUTEO_SEGUNDOS = 45;
// Más de 4 h suele ser el vehículo apagado: una ruta inventada ahí no aporta.
export const MAX_TRAMO_RUTEO_SEGUNDOS = 4 * 60 * 60;
// Topes por carga del Replay: más allá, los tramos sobrantes quedan rectos.
const MAX_TRAMOS_ESTIMADOS = 200;
const TANDA = 8;
const PRESUPUESTO_MS = 3000;

const cache = new Map();

function claveRuta(a, b) {
  const punto = (p) => `${p.latitud.toFixed(5)},${p.longitud.toFixed(5)}`;
  return `R:${punto(a)}>${punto(b)}`;
}

function claveMatch(puntos) {
  return `M:${puntos.map((p) => `${p.longitud.toFixed(5)},${p.latitud.toFixed(5)}`).join('|')}`;
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
  return entrada;
}

function guardarCache(clave, valor) {
  cache.delete(clave);
  cache.set(clave, { ...valor, guardadoEn: Date.now() });
  while (cache.size > MAX_ENTRADAS) {
    const masAntigua = cache.keys().next().value;
    if (masAntigua === undefined) break;
    cache.delete(masAntigua);
  }
}

function peticionConLimite(signal) {
  const controlador = new AbortController();
  const temporizador = setTimeout(() => controlador.abort(), TIEMPO_LIMITE_MS);
  const abortar = () => controlador.abort();
  signal?.addEventListener('abort', abortar, { once: true });
  return {
    signal: controlador.signal,
    liberar: () => {
      clearTimeout(temporizador);
      signal?.removeEventListener('abort', abortar);
    },
  };
}

// Camino [lon,lat] por /route entre dos fixes + versión del mapa, o null si
// el ruteo no responde o no hay camino. Los extremos son posiciones reales.
async function trazarPorRuta(desde, hasta, signal) {
  const clave = claveRuta(desde, hasta);
  const enCache = leerCache(clave);
  if (enCache !== undefined) return enCache.trazado ? enCache : null;
  const { signal: senal, liberar } = peticionConLimite(signal);
  try {
    const respuesta = await fetch(`${URL_RUTEO}/route`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        from: [desde.longitud, desde.latitud],
        to: [hasta.longitud, hasta.latitud],
      }),
      signal: senal,
    });
    if (!respuesta.ok) {
      guardarCache(clave, { trazado: null, mapaVersion: null, metodo: 'ESTIMATED' });
      return null;
    }
    const datos = await respuesta.json();
    const trazado =
      Array.isArray(datos.points) && datos.points.length >= 2 ? datos.points : null;
    const resultado = {
      trazado,
      mapaVersion: typeof datos.mapaVersion === 'string' ? datos.mapaVersion : null,
      metodo: 'ESTIMATED',
    };
    guardarCache(clave, resultado);
    return trazado ? resultado : null;
  } catch {
    guardarCache(clave, { trazado: null, mapaVersion: null, metodo: 'ESTIMATED' });
    return null;
  } finally {
    liberar();
  }
}

// Ajuste a vía por /match con los puntos del hueco (extremos + intermedios).
// Devuelve {trazado, mapaVersion} o null si no hay ajuste útil.
async function trazarPorMatch(puntos, signal) {
  const clave = claveMatch(puntos);
  const enCache = leerCache(clave);
  if (enCache !== undefined) return enCache.trazado ? enCache : null;
  const { signal: senal, liberar } = peticionConLimite(signal);
  try {
    const respuesta = await fetch(`${URL_RUTEO}/match`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        points: puntos.map((p) => [p.longitud, p.latitud]),
      }),
      signal: senal,
    });
    if (!respuesta.ok) {
      guardarCache(clave, { trazado: null, mapaVersion: null, metodo: 'MATCHED' });
      return null;
    }
    const datos = await respuesta.json();
    const trazado =
      Array.isArray(datos.matched) && datos.matched.length >= 2 ? datos.matched : null;
    const resultado = {
      trazado,
      mapaVersion: typeof datos.mapaVersion === 'string' ? datos.mapaVersion : null,
      metodo: 'MATCHED',
    };
    guardarCache(clave, resultado);
    return trazado ? resultado : null;
  } catch {
    guardarCache(clave, { trazado: null, mapaVersion: null, metodo: 'MATCHED' });
    return null;
  } finally {
    liberar();
  }
}

// Devuelve los tramos reconstruidos con su trazado por calles:
// [{desde, hasta, metodo:'MATCHED'|'ESTIMATED', mapaVersion, trazado}] con los
// instantes ISO de los fixes que los cierran. El llamador (replay.js) los
// manda a la web, que dibuja MATCHED continuo fino y ESTIMATED punteado gris.
// Por ahora casi todo es ESTIMATED: el /match solo se usa cuando el hueco
// trae >=2 fixes intermedios (poco probable con captura consecutiva).
export async function estimarTramos(posiciones, signal) {
  return reconstruirTramos(posiciones, signal);
}

export async function reconstruirTramos(posiciones, signal) {
  const candidatos = [];
  for (let i = 1; i < posiciones.length && candidatos.length < MAX_TRAMOS_ESTIMADOS; i += 1) {
    const anterior = posiciones[i - 1];
    const actual = posiciones[i];
    const segundos = (new Date(actual.registradoEn).getTime() - new Date(anterior.registradoEn).getTime()) / 1000;
    if (!(segundos >= MIN_SEPARACION_RUTEO_SEGUNDOS && segundos <= MAX_TRAMO_RUTEO_SEGUNDOS)) continue;
    if (distanciaM(anterior, actual) < MIN_DISTANCIA_RUTEO_M) continue;
    // Fixes con tiempo estrictamente interior al tramo: con lista consecutiva
    // suelen ser cero, por eso el método queda en ESTIMATED por ahora.
    const desdeMs = new Date(anterior.registradoEn).getTime();
    const hastaMs = new Date(actual.registradoEn).getTime();
    const intermedios = posiciones.filter((p) => {
      const instante = new Date(p.registradoEn).getTime();
      return instante > desdeMs && instante < hastaMs;
    });
    candidatos.push({ anterior, actual, intermedios });
  }
  if (candidatos.length === 0) return [];
  const inicio = Date.now();
  const reconstruidos = [];
  for (let i = 0; i < candidatos.length; i += TANDA) {
    if (Date.now() - inicio > PRESUPUESTO_MS) break;
    const tanda = candidatos.slice(i, i + TANDA);
    const resultados = await Promise.all(tanda.map(async ({ anterior, actual, intermedios }) => {
      if (intermedios.length >= 2) {
        const puntos = [anterior, ...intermedios, actual];
        const porMatch = await trazarPorMatch(puntos, signal);
        if (porMatch) return { anterior, actual, ...porMatch };
      }
      const porRuta = await trazarPorRuta(anterior, actual, signal);
      if (porRuta) return { anterior, actual, ...porRuta };
      return null;
    }));
    for (const resultado of resultados) {
      if (resultado?.trazado) {
        reconstruidos.push({
          desde: resultado.anterior.registradoEn,
          hasta: resultado.actual.registradoEn,
          metodo: resultado.metodo,
          mapaVersion: resultado.mapaVersion,
          trazado: resultado.trazado,
        });
      }
    }
  }
  return reconstruidos;
}
