// Reconstrucción por calles para los tramos que quedan "a saltos" en el
// Replay: el servicio local (GraphHopper, grafo de Ecuador) devuelve el camino
// que siguen las vías entre fixes. Cubre tres casos:
//  - huecos formales (más de 10 min sin señal), con tope de 4 h;
//  - tramos sueltos (45 s a 10 min entre fixes) que dejan la traza cortando
//    esquinas, como la tarde de Pilay con cadencia de parado mientras conducía;
//  - ventanas densas (cadencia fina de 10 s): se ajustan a vía por /match para
//    que el jitter del GPS no se dibuje crudo punto a punto.
// Semántica honesta (ADR-007/008): MATCHED si hay observaciones suficientes y
// el /match las ajusta a vía (hueco con >=2 intermedios o ventana densa);
// ESTIMATED si se resuelve por /route A→B sin observaciones. Sin respuesta →
// sin tramo (la ventana queda cruda, nunca se inventa). Las posiciones
// registradas no cambian.
// Cache en memoria (aciertos 7 días, fallos 1 min, tope 2000) y presupuesto
// por carga: si el ruteo no responde, el tramo queda como siempre. Ninguna
// función lanza.

const URL_RUTEO = process.env.DMJ_RUTEO_URL ?? 'http://127.0.0.1:8992';
const TIEMPO_LIMITE_MS = 2500;
const TTL_ACIERTO_MS = 7 * 24 * 60 * 60 * 1000;
const TTL_FALLO_MS = 60 * 1000;
const MAX_ENTRADAS = 2000;

// Solo se reconstruyen tramos con desplazamiento real: menos de 150 m es el
// vehículo parado con jitter y el ruteo devolvería vueltas absurdas.
export const MIN_DISTANCIA_RUTEO_M = 150;
// Desde 45 s entre fixes el trazo recto ya corta esquinas; la cadencia fina
// (10 s) se ajusta por ventanas densas en vez de tramo a tramo.
export const MIN_SEPARACION_RUTEO_SEGUNDOS = 45;
// Más de 4 h suele ser el vehículo apagado: una ruta inventada ahí no aporta.
export const MAX_TRAMO_RUTEO_SEGUNDOS = 4 * 60 * 60;
// Topes por carga del Replay: más allá, los tramos sobrantes quedan rectos.
const MAX_TRAMOS_ESTIMADOS = 200;
const TANDA = 8;
const PRESUPUESTO_MS = 3000;
// Ventanas densas para ajuste a vía (/match) en cadencia fina. Hasta 100
// puntos o 5 min por ventana: el matcher filtra observaciones cercanas y cada
// ventana responde en <100 ms en el grafo local. El interior de paradas
// nunca entra a ventanas (se aparta el jitter de parado) y los outliers de
// teleport se apartan del ajuste; un hueco de cobertura >=45 s corta, salvo
// cuando lo cubre una parada corta (<=120 s): ahí la ventana continúa y el
// recorrido urbano con semáforos queda ajustado a vía en vez de cortarse en
// decenas de rectas. Las ventanas con desplazamiento <25 m se descartan.
const MAX_PUNTOS_VENTANA_DENSA = 100;
const MAX_DURACION_VENTANA_DENSA_MS = 5 * 60 * 1000;
const MIN_PUNTOS_VENTANA_DENSA = 3;
const MIN_DESPLAZAMIENTO_VENTANA_M = 25;
const VELOCIDAD_PARADA_KMH = 2;
const DURACION_PARADA_CORTE_MS = 60 * 1000;
const MAX_VENTANAS_DENSAS = 200;
const TANDA_DENSAS = 4;
// Puente de hueco corto: un hueco entre fixes conservados se puentea si dura
// <=150 s y no separa un desplazamiento real (<150 m); las paradas largas
// superan ese tope y el corte natural las separa.
const PUENTE_HUECO_MAX_SEGUNDOS = 150;
// Rechazo de teleports antes de /match (el crudo se conserva intacto, solo
// se aparta el fix del ajuste): velocidad implícita contra el anterior
// válido mayor a 120 km/h, o pico aislado de precisión >25 m con ambos
// vecinos <13 m. Si al quitar outliers quedan <3 fixes, se descarta.
const VELOCIDAD_MAX_TELEPORT_KMH = 120;
const PRECISION_PICO_M = 25;
const PRECISION_VECINA_FIABLE_M = 13;

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

// Instante en ms del fix, o NaN si el campo no trae fecha válida.
function instanteMs(posicion) {
  const instante = new Date(posicion?.registradoEn).getTime();
  return Number.isFinite(instante) ? instante : NaN;
}

function coordenadasValidas(posicion) {
  return Number.isFinite(posicion?.latitud) && Number.isFinite(posicion?.longitud);
}

// Velocidad para detectar paradas: la reportada manda; si falta se estima
// contra el fix anterior (distancia/tiempo). Un duplicado por retransmisión
// (mismo instante, misma coordenada) cuenta como parado.
function velocidadParaParada(actual, anterior) {
  const reportada = actual?.velocidadKmh;
  if (typeof reportada === 'number' && Number.isFinite(reportada)) return reportada;
  if (!anterior) return null;
  const desde = instanteMs(anterior);
  const hasta = instanteMs(actual);
  const horas = (hasta - desde) / 3600000;
  if (!(horas > 0)) {
    try {
      return distanciaM(anterior, actual) <= 1 ? 0 : null;
    } catch {
      return null;
    }
  }
  try {
    return distanciaM(anterior, actual) / 1000 / horas;
  } catch {
    return null;
  }
}

// Rachas de velocidad <2 km/h que duran >=60 s (mismo umbral de detención que
// la web). Ningún índice cubierto por estas rachas puede pertenecer a una
// ventana matchable: el jitter parado no se manda al matcher, que devolvería
// vueltas absurdas sobre la misma manzana. Un fix aislado en movimiento no
// rompe la racha (suele ser glitch de velocidad, como el 0 de las 21:47:48
// en plena marcha o el 2,1 entre ceros al detenerse): hacen falta 2 seguidos
// para cerrarla.
function detectarParadasProlongadas(posiciones) {
  const paradas = [];
  let inicioRacha = -1;
  let seguidosEnMovimiento = 0;
  const cerrarRacha = (fin) => {
    if (inicioRacha >= 0 && fin >= inicioRacha) {
      const desde = instanteMs(posiciones[inicioRacha]);
      const hasta = instanteMs(posiciones[fin]);
      if (Number.isFinite(desde) && Number.isFinite(hasta) && hasta - desde >= DURACION_PARADA_CORTE_MS) {
        paradas.push({ inicio: inicioRacha, fin });
      }
    }
    inicioRacha = -1;
    seguidosEnMovimiento = 0;
  };
  for (let i = 0; i < posiciones.length; i += 1) {
    const anterior = i > 0 ? posiciones[i - 1] : null;
    let velocidad = null;
    try {
      velocidad = velocidadParaParada(posiciones[i], anterior);
    } catch {
      velocidad = null;
    }
    const detenida =
      typeof velocidad === 'number' && Number.isFinite(velocidad) && velocidad < VELOCIDAD_PARADA_KMH;
    if (detenida) {
      if (inicioRacha < 0) inicioRacha = i;
      seguidosEnMovimiento = 0;
      continue;
    }
    if (inicioRacha < 0) continue;
    seguidosEnMovimiento += 1;
    if (seguidosEnMovimiento >= 2) cerrarRacha(i - 2);
  }
  if (inicioRacha >= 0) cerrarRacha(posiciones.length - 1 - seguidosEnMovimiento);
  return paradas;
}

// Precisión del fix en metros, o null si no hay dato válido. Acepta el DTO
// (precisionM) y la fila cruda (precision_m) sin convertir unidades.
function leerPrecisionM(posicion) {
  try {
    const valor = posicion?.precisionM ?? posicion?.precision_m;
    return typeof valor === 'number' && Number.isFinite(valor) ? valor : null;
  } catch {
    return null;
  }
}

// Velocidad implícita en km/h entre dos fixes (distancia/tiempo), o null si
// no se puede calcular (tiempo inválido o no positivo). No lanza.
function velocidadImplicitaKmh(anterior, actual) {
  try {
    const desde = instanteMs(anterior);
    const hasta = instanteMs(actual);
    const segundos = (hasta - desde) / 1000;
    if (!(Number.isFinite(segundos) && segundos > 0)) return null;
    const metros = distanciaM(anterior, actual);
    if (!Number.isFinite(metros)) return null;
    return (metros / segundos) * 3.6;
  } catch {
    return null;
  }
}

// Aparta outliers de teleport de la entrada a /match sin mutar el crudo:
// devuelve una lista nueva sin los fixes saltados. Dos reglas:
// - velocidad implícita contra el anterior válido >120 km/h (teleport);
// - pico aislado de precisión >30 m con ambos vecinos <15 m.
// La comparación de velocidad usa el anterior conservado (no el outlier) para
// no marcar como outlier el fix bueno que vuelve del salto. No lanza: ante
// cualquier dato inválido devuelve lo que sí pudo filtrar.
function filtrarOutliersTeleport(ventana) {
  try {
    if (!Array.isArray(ventana) || ventana.length === 0) return [];
    // Paso 1: velocidad implícita excesiva.
    const sinSaltos = [];
    let anteriorValido = null;
    for (const fix of ventana) {
      try {
        if (!coordenadasValidas(fix)) {
          continue;
        }
        if (!anteriorValido) {
          sinSaltos.push(fix);
          anteriorValido = fix;
          continue;
        }
        const velocidad = velocidadImplicitaKmh(anteriorValido, fix);
        if (
          typeof velocidad === 'number' &&
          Number.isFinite(velocidad) &&
          velocidad > VELOCIDAD_MAX_TELEPORT_KMH
        ) {
          continue;
        }
        sinSaltos.push(fix);
        anteriorValido = fix;
      } catch {
        continue;
      }
    }
    // Paso 2: pico aislado de precisión (solo interiores con ambos vecinos).
    if (sinSaltos.length < 3) return sinSaltos;
    const sinPicos = [sinSaltos[0]];
    for (let i = 1; i < sinSaltos.length - 1; i += 1) {
      try {
        const previa = leerPrecisionM(sinSaltos[i - 1]);
        const actual = leerPrecisionM(sinSaltos[i]);
        const siguiente = leerPrecisionM(sinSaltos[i + 1]);
        if (
          actual !== null &&
          previa !== null &&
          siguiente !== null &&
          actual > PRECISION_PICO_M &&
          previa < PRECISION_VECINA_FIABLE_M &&
          siguiente < PRECISION_VECINA_FIABLE_M
        ) {
          continue;
        }
        sinPicos.push(sinSaltos[i]);
      } catch {
        sinPicos.push(sinSaltos[i]);
      }
    }
    sinPicos.push(sinSaltos[sinSaltos.length - 1]);
    return sinPicos;
  } catch {
    try {
      return Array.isArray(ventana) ? ventana.slice() : [];
    } catch {
      return [];
    }
  }
}

// Desplazamiento máximo desde el primer fix de la ventana, o NaN si no se
// puede calcular. Se usa el máximo (no extremo a extremo) para no descartar
// recorridos en bucle que vuelven cerca del origen. No lanza.
function desplazamientoMaximoM(ventana) {
  try {
    let maximo = 0;
    for (let i = 1; i < ventana.length; i += 1) {
      const desplazamiento = distanciaM(ventana[0], ventana[i]);
      if (desplazamiento > maximo) maximo = desplazamiento;
    }
    return maximo;
  } catch {
    return NaN;
  }
}

// Un hueco entre fixes conservados se puentea si dura <=150 s y no separa un
// desplazamiento real (<150 m): así el tráfico lento, las paradas cortas y
// los semáforos quedan dentro de una misma ventana y el recorrido urbano se
// ajusta a vía en vez de cortarse en decenas de rectas. Los huecos de ruta
// real (>=150 m) y las paradas largas (>150 s) cortan: el hueco de ruta lo
// trata el ruteo de huecos (ESTIMATED) como siempre.
function puenteHuecoCorto(posiciones, prevIdx, curIdx) {
  try {
    const prevMs = instanteMs(posiciones[prevIdx]);
    const curMs = instanteMs(posiciones[curIdx]);
    if (!Number.isFinite(prevMs) || !Number.isFinite(curMs)) return false;
    if ((curMs - prevMs) / 1000 > PUENTE_HUECO_MAX_SEGUNDOS) return false;
    return distanciaM(posiciones[prevIdx], posiciones[curIdx]) < MIN_DISTANCIA_RUTEO_M;
  } catch {
    return false;
  }
}

// Parte el track en ventanas matchables: hasta 100 puntos o 5 min, cortando
// en huecos >=45 s salvo que el hueco lo cubra una parada corta (<=120 s):
// así el recorrido urbano con semáforos queda en pocas ventanas y el trazado
// se ajusta a vía. El interior de paradas nunca pertenece a una ventana (se
// aparta el jitter de parado). Después se apartan outliers de teleport de la
// entrada a /match (el crudo queda intacto). Devuelve listas de fixes ya
// limpios; el llamador los manda a /match por ventana. No lanza: ante dato
// inválido devuelve las ventanas que sí pudo partir.
export function partirVentanasDensas(posiciones) {
  const ventanas = [];
  try {
    if (!Array.isArray(posiciones) || posiciones.length < MIN_PUNTOS_VENTANA_DENSA) return ventanas;
    let paradas = [];
    try {
      paradas = detectarParadasProlongadas(posiciones);
    } catch {
      paradas = [];
    }
    // Conjunto con todos los índices cubiertos por paradas prolongadas: el
    // interior parado jamás va a /match (antes solo se cortaba en bordes y
    // la deriva + el teleport siguiente quedaban pegados en una ventana).
    const enParada = new Set();
    try {
      for (const parada of paradas) {
        if (!parada || !Number.isInteger(parada.inicio) || !Number.isInteger(parada.fin)) continue;
        for (let i = parada.inicio; i <= parada.fin; i += 1) enParada.add(i);
      }
    } catch {
      // Sin conjunto tampoco se rompe: se sigue con partición por huecos.
    }
    // Índices conservados (sin interior de parada) y cortes por hueco: un
    // hueco >=45 s corta el tramo salvo que lo cubra una parada corta
    // (puente). Las paradas largas nunca se puentean.
    const tramosLibres = [];
    try {
      const conservados = [];
      for (let i = 0; i < posiciones.length; i += 1) {
        if (!enParada.has(i)) conservados.push(i);
      }
      let actual = [];
      for (let k = 0; k < conservados.length; k += 1) {
        const idx = conservados[k];
        if (actual.length > 0) {
          const prevIdx = conservados[k - 1];
          const prevMs = instanteMs(posiciones[prevIdx]);
          const actualMs = instanteMs(posiciones[idx]);
          if (Number.isFinite(prevMs) && Number.isFinite(actualMs)) {
            const segundos = (actualMs - prevMs) / 1000;
            if (
              segundos >= MIN_SEPARACION_RUTEO_SEGUNDOS
              && !puenteHuecoCorto(posiciones, prevIdx, idx)
            ) {
              tramosLibres.push(actual);
              actual = [];
            }
          }
        }
        actual.push(posiciones[idx]);
      }
      if (actual.length > 0) tramosLibres.push(actual);
    } catch {
      return ventanas;
    }
    for (const tramo of tramosLibres) {
      let inicio = 0;
      const cerrar = (fin) => {
        if (fin >= inicio) ventanas.push(tramo.slice(inicio, fin + 1));
        inicio = fin + 1;
      };
      for (let i = 1; i < tramo.length; i += 1) {
        if (i - inicio + 1 > MAX_PUNTOS_VENTANA_DENSA) {
          cerrar(i - 1);
          continue;
        }
        const inicioMs = instanteMs(tramo[inicio]);
        const actualMs = instanteMs(tramo[i]);
        if (
          Number.isFinite(inicioMs) &&
          Number.isFinite(actualMs) &&
          actualMs - inicioMs > MAX_DURACION_VENTANA_DENSA_MS
        ) {
          cerrar(i - 1);
        }
      }
      cerrar(tramo.length - 1);
    }
  } catch {
    return ventanas;
  }
  // Solo ventanas con movimiento real: al menos 3 fixes, coordenadas finitas
  // y tiempo creciente, con algún fix a >=25 m del primero. Tras apartar
  // outliers se reexige >=3 fixes y >=25 m: una ventana que solo pasaba el
  // gate gracias al salto (deriva parada + teleport) queda descartada.
  const utiles = [];
  try {
    for (const ventana of ventanas) {
      if (ventana.length < MIN_PUNTOS_VENTANA_DENSA) continue;
      if (!ventana.every(coordenadasValidas)) continue;
      const desde = instanteMs(ventana[0]);
      const hasta = instanteMs(ventana[ventana.length - 1]);
      if (!(Number.isFinite(desde) && Number.isFinite(hasta) && hasta > desde)) continue;
      const maxDesplazamiento = desplazamientoMaximoM(ventana);
      if (!(maxDesplazamiento >= MIN_DESPLAZAMIENTO_VENTANA_M)) continue;
      let limpia = ventana;
      try {
        limpia = filtrarOutliersTeleport(ventana);
      } catch {
        limpia = ventana;
      }
      if (limpia.length < MIN_PUNTOS_VENTANA_DENSA) continue;
      if (!limpia.every(coordenadasValidas)) continue;
      const desdeLimpio = instanteMs(limpia[0]);
      const hastaLimpio = instanteMs(limpia[limpia.length - 1]);
      if (!(Number.isFinite(desdeLimpio) && Number.isFinite(hastaLimpio) && hastaLimpio > desdeLimpio)) {
        continue;
      }
      const maxLimpio = desplazamientoMaximoM(limpia);
      if (!(maxLimpio >= MIN_DESPLAZAMIENTO_VENTANA_M)) continue;
      utiles.push(limpia);
      if (utiles.length >= MAX_VENTANAS_DENSAS) break;
    }
  } catch {
    return utiles;
  }
  return utiles;
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

// Precisión mediana (m) de una ventana: viaja como `accuracy` al /match para
// que el snap sea más exigente con buena señal. Sin datos válidos: null.
function precisionMedianaM(puntos) {
  try {
    const valores = (Array.isArray(puntos) ? puntos : [])
      .map((p) => Number(p?.precisionM))
      .filter((v) => Number.isFinite(v) && v > 0)
      .sort((a, b) => a - b);
    if (valores.length === 0) return null;
    const medio = Math.floor(valores.length / 2);
    const mediana = valores.length % 2 === 0
      ? (valores[medio - 1] + valores[medio]) / 2
      : valores[medio];
    return Number.isFinite(mediana) ? mediana : null;
  } catch {
    return null;
  }
}

// Ajuste a vía por /match con los puntos del hueco (extremos + intermedios).
// La precisión mediana de la ventana viaja como `accuracy`: el ruteo ajusta
// cuánto puede confiar en cada snap (efecto en cañones urbanos). Devuelve
// {trazado, mapaVersion} o null si no hay ajuste útil.
async function trazarPorMatch(puntos, signal) {
  const accuracy = precisionMedianaM(puntos);
  const clave = `${claveMatch(puntos)}|a${accuracy === null ? 'na' : Math.round(accuracy)}`;
  const enCache = leerCache(clave);
  if (enCache !== undefined) return enCache.trazado ? enCache : null;
  const { signal: senal, liberar } = peticionConLimite(signal);
  try {
    const respuesta = await fetch(`${URL_RUTEO}/match`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        points: puntos.map((p) => [p.longitud, p.latitud]),
        ...(accuracy === null ? {} : { accuracy }),
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
// instantes ISO de los fixes que los cierran. En huecos, desde/hasta son el
// par que cierra el salto; en ventanas densas son los extremos de la ventana.
// El llamador (replay.js) los manda a la web, que dibuja MATCHED continuo fino
// ("ajustado a vía") y ESTIMATED punteado gris. Las posiciones registradas no
// cambian. Ninguna función lanza: sin respuesta la ventana queda cruda.
export async function estimarTramos(posiciones, signal) {
  return reconstruirTramos(posiciones, signal);
}

export async function reconstruirTramos(posiciones, signal) {
  if (!Array.isArray(posiciones) || posiciones.length === 0) return [];
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
  // Ajuste a vía de tramos densos: cada ventana matchable se resuelve por
  // /match con la caché y el presupuesto ya existentes. Si el ruteo no
  // responde o devuelve menos de 2 puntos, la ventana queda cruda.
  try {
    let ventanas = [];
    try {
      ventanas = partirVentanasDensas(posiciones);
    } catch {
      ventanas = [];
    }
    const clavesCandidatas = new Set(
      candidatos.map(({ anterior, actual }) => `${anterior.registradoEn}|${actual.registradoEn}`),
    );
    ventanas = ventanas.filter(
      (ventana) =>
        !clavesCandidatas.has(`${ventana[0].registradoEn}|${ventana[ventana.length - 1].registradoEn}`),
    );
    for (let i = 0; i < ventanas.length; i += TANDA_DENSAS) {
      if (Date.now() - inicio > PRESUPUESTO_MS) break;
      const tanda = ventanas.slice(i, i + TANDA_DENSAS);
      const resultados = await Promise.all(
        tanda.map(async (puntos) => {
          try {
            const porMatch = await trazarPorMatch(puntos, signal);
            if (!porMatch?.trazado || porMatch.trazado.length < 2) return null;
            return { puntos, ...porMatch };
          } catch {
            return null;
          }
        }),
      );
      for (const resultado of resultados) {
        if (resultado?.trazado) {
          reconstruidos.push({
            desde: resultado.puntos[0].registradoEn,
            hasta: resultado.puntos[resultado.puntos.length - 1].registradoEn,
            metodo: resultado.metodo,
            mapaVersion: resultado.mapaVersion,
            trazado: resultado.trazado,
          });
        }
      }
    }
  } catch {
    // Las ventanas densas son oportunistas: ante cualquier fallo se conserva
    // lo ya reconstruido por huecos y el resto queda crudo.
  }
  try {
    reconstruidos.sort((a, b) => new Date(a.desde).getTime() - new Date(b.desde).getTime());
  } catch {
    // Sin orden tampoco se rompe la web: solo pierde el orden cronológico.
  }
  return reconstruidos;
}
