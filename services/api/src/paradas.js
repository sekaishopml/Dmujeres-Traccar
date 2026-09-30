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
    const segundos = (ms(puntos[ultimo]) - ms(puntos[i])) / 1000;
    if (segundos >= MIN_PARADA_S && buenos.length >= 2) {
      paradas.push({
        inicio: new Date(ms(puntos[i])),
        fin: new Date(ms(puntos[ultimo])),
        segundos,
        latitud: centro.latitud,
        longitud: centro.longitud,
        precisionM: mediana(buenos.map((x) => x.precisionM ?? 0)),
        fixes: buenos.length,
      });
      i = ultimo + 1;
    } else {
      i += 1;
    }
  }
  return fusionar(paradas);
}

// Un fix de deriva suelto fuera del radio partía una estancia en dos
// (18:05-18:35 y 18:36-18:52 en el mismo patio): paradas seguidas en el mismo
// sitio con menos de MAX_PAUSA_FUSION_S entre ellas son una sola.
export const MAX_PAUSA_FUSION_S = 180;

function fusionar(paradas) {
  const salida = [];
  for (const parada of paradas) {
    const previa = salida[salida.length - 1];
    if (
      previa &&
      (parada.inicio.getTime() - previa.fin.getTime()) / 1000 <= MAX_PAUSA_FUSION_S &&
      metros(previa, parada) <= RADIO_M
    ) {
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
