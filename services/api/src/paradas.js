// Paradas por permanencia (desde la 1.4 del panel).
//
// Antes una parada era "fixes con velocidad < 5 km/h". Con datos reales eso
// falla de tres formas:
//  - Teléfonos que reportan velocidad 0 siempre (mantilla, 29/09): toda la
//    caminata era "detenida" y la parada real de 21:00-21:03 quedaba diluida.
//  - Muestreo ralo (Fernando, app 2.1.80, un fix cada ~2 min): un fix suelto
//    en movimiento entre dos quietos se descartaba como ruido y aparecían
//    paradas de 48 min mientras recorría kilómetros.
//  - Ubicación aproximada por antenas (precisión 100 m, misma coordenada
//    repetida durante horas): se leía como una parada de 5 h.
//
// Regla nueva: una parada es el tiempo que la persona PERMANECE dentro de un
// radio, sin importar la velocidad que diga el teléfono. Se agrupan fixes
// consecutivos mientras sigan a <= RADIO_M del centro (mediana) del grupo; si
// el grupo dura >= MIN_PARADA_S es parada. Los fixes de precisión mala no
// abren ni sostienen una parada por sí solos (no se sabe dónde está), pero no
// la cortan si caen cerca. Un hueco sin fixes de más de MAX_HUECO_S corta el
// grupo aunque vuelva al mismo sitio: ese tiempo no se observó.

export const RADIO_M = 60;
export const MIN_PARADA_S = 120;
export const PRECISION_BUENA_M = 80;
export const MAX_HUECO_S = 30 * 60;
// Núcleo de la parada: los extremos más lejos que esto del centro se recortan.
export const RADIO_NUCLEO_M = 30;
export const MIN_FIXES_BORDE = 3;

function metros(a, b) {
  const dLat = (b.latitud - a.latitud) * 111320;
  const dLon = (b.longitud - a.longitud) * 111320 * Math.cos(((a.latitud + b.latitud) / 2) * (Math.PI / 180));
  return Math.hypot(dLat, dLon);
}

function mediana(valores) {
  const orden = [...valores].sort((x, y) => x - y);
  const medio = Math.floor(orden.length / 2);
  return orden.length % 2 ? orden[medio] : (orden[medio - 1] + orden[medio]) / 2;
}

const bueno = (p) => p.precisionM == null || p.precisionM <= PRECISION_BUENA_M;
const ms = (p) => (p.registradoEn instanceof Date ? p.registradoEn.getTime() : new Date(p.registradoEn).getTime());

// puntos: [{ registradoEn, latitud, longitud, precisionM }] de UN equipo,
// ordenados por hora. Devuelve [{ inicio, fin, segundos, latitud, longitud,
// precisionM, fixes }] con inicio/fin como Date.
export function detectarParadas(puntos) {
  const paradas = [];
  let i = 0;
  while (i < puntos.length) {
    if (!bueno(puntos[i])) {
      i += 1;
      continue;
    }
    const buenos = [puntos[i]];
    let centro = { latitud: puntos[i].latitud, longitud: puntos[i].longitud };
    let ultimo = i;
    let j = i + 1;
    while (j < puntos.length) {
      const p = puntos[j];
      if ((ms(p) - ms(puntos[ultimo])) / 1000 > MAX_HUECO_S) break;
      const d = metros(centro, p);
      if (bueno(p)) {
        if (d > RADIO_M) break;
        buenos.push(p);
        centro = { latitud: mediana(buenos.map((x) => x.latitud)), longitud: mediana(buenos.map((x) => x.longitud)) };
        ultimo = j;
      } else if (d > RADIO_M + Math.min(p.precisionM ?? 0, 150)) {
        // Un fix aproximado lejísimos sí indica que se fue.
        break;
      }
      j += 1;
    }
    // Bordes: llegar o salir caminando despacio queda dentro del radio de 60 m
    // y se contaba como parada (Manzaba 30/09 20:41: 100 m a pie entre dos
    // paradas desaparecían). Los fixes de los extremos a más de RADIO_NUCLEO_M
    // del centro son la llegada o la salida, no la estancia.
    // Solo se recorta un borde con al menos MIN_FIXES_BORDE fixes fuera del
    // núcleo: uno suelto es deriva o muestreo ralo (mantilla 29/09 21:03).
    const fuera = (k) => !bueno(puntos[k]) || metros(centro, puntos[k]) > RADIO_NUCLEO_M;
    let primero = i;
    let k = i;
    while (k < ultimo && fuera(k)) k += 1;
    if (puntos.slice(i, k).filter(bueno).length >= MIN_FIXES_BORDE) primero = k;
    k = ultimo;
    while (k > primero && fuera(k)) k -= 1;
    if (puntos.slice(k + 1, ultimo + 1).filter(bueno).length >= MIN_FIXES_BORDE) ultimo = k;
    const nucleo = puntos.slice(primero, ultimo + 1).filter(bueno);
    const segundos = (ms(puntos[ultimo]) - ms(puntos[primero])) / 1000;
    if (segundos >= MIN_PARADA_S && nucleo.length >= 2) {
      paradas.push({
        inicio: new Date(ms(puntos[primero])),
        fin: new Date(ms(puntos[ultimo])),
        segundos,
        latitud: centro.latitud,
        longitud: centro.longitud,
        precisionM: mediana(nucleo.map((x) => x.precisionM ?? 0)),
        fixes: nucleo.length,
      });
      i = ultimo + 1;
    } else {
      i += 1;
    }
  }
  return fusionar(paradas, puntos);
}

// Un fix de deriva suelto fuera del radio partía una estancia en dos
// (18:05-18:35 y 18:36-18:52 en el mismo patio): paradas seguidas en el mismo
// sitio con menos de MAX_PAUSA_FUSION_S entre ellas son una sola.
export const MAX_PAUSA_FUSION_S = 180;

// Estancia larga dentro de un edificio (Manzaba 30/09, 10:09-20:41): la
// deriva del GPS bajo techo saca fixes a 70-100 m y la estancia quedaba en 5
// paradas con una telaraña de líneas entre ellas. Dos paradas seguidas son la
// misma estancia si:
//  - las separa como mucho MAX_PAUSA_ESTANCIA_S,
//  - sus centros están a <= RADIO_FUSION_M, y
//  - en la pausa no se alejó más de EXCURSION_MAX_M del centro durante
//    MIN_PARADA_S seguidos (un salto suelto del GPS no es una salida).
// Una visita real a otro sitio no se pierde: si la persona se quedó >= 2 min a
// más de RADIO_FUSION_M, eso es otra parada y corta la cadena.
export const MAX_PAUSA_ESTANCIA_S = 15 * 60;
export const RADIO_FUSION_M = 100;
export const EXCURSION_MAX_M = 200;

// ¿Hubo una salida real en la pausa? Solo si los fixes buenos se mantienen
// a más de EXCURSION_MAX_M del centro durante MIN_PARADA_S seguidos: bajo
// techo el GPS salta 200 m y vuelve en segundos (Manzaba 10:36, 22 → 244 →
// 22 m en 33 s) y eso no es una salida.
function salidaSostenida(puntos, centro, desdeMs, hastaMs) {
  let fueraDesde = null;
  for (const p of puntos) {
    const t = ms(p);
    if (t <= desdeMs || !bueno(p)) continue;
    if (t >= hastaMs) break;
    if (metros(centro, p) > EXCURSION_MAX_M) {
      if (fueraDesde === null) fueraDesde = t;
      if ((t - fueraDesde) / 1000 >= MIN_PARADA_S) return true;
    } else {
      fueraDesde = null;
    }
  }
  return false;
}

function mismaEstancia(previa, parada, puntos) {
  const pausaS = (parada.inicio.getTime() - previa.fin.getTime()) / 1000;
  const distancia = metros(previa, parada);
  if (pausaS <= MAX_PAUSA_FUSION_S && distancia <= RADIO_M) return true;
  if (pausaS > MAX_PAUSA_ESTANCIA_S || distancia > RADIO_FUSION_M) return false;
  return !salidaSostenida(puntos ?? [], previa, previa.fin.getTime(), parada.inicio.getTime());
}

function fusionar(paradas, puntos) {
  const salida = [];
  for (const parada of paradas) {
    const previa = salida[salida.length - 1];
    if (previa && mismaEstancia(previa, parada, puntos)) {
      const fixes = previa.fixes + parada.fixes;
      previa.latitud = (previa.latitud * previa.fixes + parada.latitud * parada.fixes) / fixes;
      previa.longitud = (previa.longitud * previa.fixes + parada.longitud * parada.fixes) / fixes;
      previa.fin = parada.fin;
      previa.segundos = (previa.fin.getTime() - previa.inicio.getTime()) / 1000;
      previa.fixes = fixes;
    } else {
      salida.push({ ...parada });
    }
  }
  return salida;
}
