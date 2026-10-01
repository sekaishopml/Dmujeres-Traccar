import type { Posicion } from '@contratos';
import { milisegundos } from '@/dominio/replay';
import type { Parada, SegmentoRecorrido } from '@/dominio/replay';

// Reglas de dibujo del trazo (solo presentación: las posiciones registradas,
// la ficha del punto y los reportes no cambian).

// Color por hora: claro al empezar el día, oscuro al terminar. Cuando la
// persona pasa dos veces por la misma calle, los dos trazos y sus flechas se
// distinguen por tono (Manzaba 30/09, C. 44 S-E de ida y de vuelta).
export const COLOR_HORA_TEMPRANO = '#5b8fd0';
export const COLOR_HORA_MEDIO = '#2c5b99';
export const COLOR_HORA_TARDE = '#0b2545';
export const COLOR_POR_HORA = [
  'interpolate',
  ['linear'],
  ['coalesce', ['get', 'f'], 1],
  0,
  COLOR_HORA_TEMPRANO,
  0.5,
  COLOR_HORA_MEDIO,
  1,
  COLOR_HORA_TARDE,
] as const;

export function fraccionDelDia(t: number, desde: number, hasta: number): number {
  if (!(hasta > desde)) return 1;
  return Math.min(Math.max((t - desde) / (hasta - desde), 0), 1);
}

function metros(a: [number, number], b: [number, number]): number {
  const dLat = (b[1] - a[1]) * 111320;
  const dLon = (b[0] - a[0]) * 111320 * Math.cos(((a[1] + b[1]) / 2) * (Math.PI / 180));
  return Math.hypot(dLat, dLon);
}

// Caminata sin "fideo": a pie el GPS oscila 5-15 m a los lados y la línea se
// veía como un fideo. Para dibujar, cada lectura a pie se promedia con sus dos
// vecinas (si están a menos de 30 s y 40 m). Fuera de paradas y nunca en
// vehículo: ahí el GPS ya es estable y las esquinas no se deben redondear.
const VELOCIDAD_A_PIE_KMH = 8;
const VECINO_MAX_MS = 30_000;
const VECINO_MAX_M = 40;

export function suavizarCaminata(posiciones: Posicion[], paradas: Parada[]): Posicion[] {
  const ventanas = paradas.map((p) => [milisegundos(p.inicio), milisegundos(p.fin)] as const);
  const enParada = (t: number) => ventanas.some(([a, b]) => t >= a && t <= b);
  return posiciones.map((p, i) => {
    const antes = posiciones[i - 1];
    const despues = posiciones[i + 1];
    if (!antes || !despues) return p;
    const t = milisegundos(p.registradoEn);
    if (enParada(t) || (p.velocidadKmh ?? 0) >= VELOCIDAD_A_PIE_KMH) return p;
    const aqui: [number, number] = [p.longitud, p.latitud];
    const a: [number, number] = [antes.longitud, antes.latitud];
    const b: [number, number] = [despues.longitud, despues.latitud];
    if (
      t - milisegundos(antes.registradoEn) > VECINO_MAX_MS ||
      milisegundos(despues.registradoEn) - t > VECINO_MAX_MS ||
      metros(aqui, a) > VECINO_MAX_M ||
      metros(aqui, b) > VECINO_MAX_M
    ) {
      return p;
    }
    return { ...p, longitud: (a[0] + 2 * aqui[0] + b[0]) / 4, latitud: (a[1] + 2 * aqui[1] + b[1]) / 4 };
  });
}

// La línea llega al borde de la parada y no la cruza: el ajuste a calles
// terminaba en el callejón de atrás del edificio y dibujaba una línea vertical
// por encima del círculo (Manzaba 30/09, parada de 10:10 a 20:40). Se recorta
// lo que queda a menos de RADIO_PARADA_M de una parada cercana en el tiempo.
const RADIO_PARADA_M = 25;
const MARGEN_PARADA_MS = 5 * 60_000;

function cortarCirculo(dentro: [number, number], fuera: [number, number], centro: [number, number]): [number, number] {
  // Búsqueda binaria del punto del tramo que cae en el borde del círculo.
  let a = dentro;
  let b = fuera;
  for (let k = 0; k < 16; k += 1) {
    const medio: [number, number] = [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];
    if (metros(medio, centro) < RADIO_PARADA_M) a = medio;
    else b = medio;
  }
  return b;
}

function recortarCoordenadas(coords: [number, number][], centros: [number, number][]): [number, number][][] {
  if (centros.length === 0) return [coords];
  const centroDe = (p: [number, number]) => centros.find((c) => metros(p, c) < RADIO_PARADA_M) ?? null;
  const piezas: [number, number][][] = [];
  let actual: [number, number][] = [];
  for (let k = 0; k < coords.length; k += 1) {
    const p = coords[k];
    const centro = centroDe(p);
    const previo = coords[k - 1];
    if (!centro) {
      if (previo && actual.length === 0) {
        const centroPrevio = centroDe(previo);
        if (centroPrevio) actual.push(cortarCirculo(previo, p, centroPrevio));
      }
      actual.push(p);
    } else if (actual.length > 0) {
      actual.push(cortarCirculo(p, actual[actual.length - 1], centro));
      piezas.push(actual);
      actual = [];
    }
  }
  if (actual.length > 0) piezas.push(actual);
  return piezas.filter((pieza) => pieza.length >= 2 && metros(pieza[0], pieza[pieza.length - 1]) > 1);
}

export function recortarEnParadas(
  segmentos: SegmentoRecorrido[],
  paradas: Parada[],
  finDe: (segmento: SegmentoRecorrido) => number,
): SegmentoRecorrido[] {
  const lista = paradas.map((p) => ({
    desde: milisegundos(p.inicio) - MARGEN_PARADA_MS,
    hasta: milisegundos(p.fin) + MARGEN_PARADA_MS,
    centro: [p.longitud, p.latitud] as [number, number],
  }));
  return segmentos.flatMap((segmento) => {
    if (segmento.tipo === 'hueco' || segmento.coordenadas.length < 2) return [segmento];
    const inicio = segmento.instante ?? 0;
    const fin = finDe(segmento);
    const centros = lista.filter((p) => inicio <= p.hasta && fin >= p.desde).map((p) => p.centro);
    return recortarCoordenadas(segmento.coordenadas, centros).map((coordenadas) => ({ ...segmento, coordenadas }));
  });
}
