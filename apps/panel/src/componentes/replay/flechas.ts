import type { Feature, FeatureCollection, Point } from 'geojson';
import type { Posicion } from '@contratos';
import { milisegundos } from '@/dominio/replay';
import type { Parada, SegmentoRecorrido, TramoReconstruido } from '@/dominio/replay';

// Flechas de sentido sobre la línea dibujada (GPS o ajustada a calles), nunca
// sobre el fix crudo: con precisión de decenas de metros el fix cae fuera de
// la calle y su rumbo entre vecinos gira en cualquier sentido. Aquí la flecha
// va sobre el trazo y apunta a lo largo de él, en el sentido del tiempo.
//
// Hay una flecha cada PASO_M metros de recorrido. Cada una lleva:
//   t: instante (ms) en que la persona pasó por ahí, para ubicar su fix,
//   r: rumbo en grados (0 = norte),
//   n: zoom desde el que se dibuja.
// Los niveles son anidados: a zoom 12 hay una cada 1,28 km; cada nivel de zoom
// duplica la cantidad (640 m, 320 m, … 20 m) y a zoom 19 hay una cada 10 m.
// Lo que se ve a un zoom sigue viéndose al acercar.

const PASO_M = 10;
const NIVEL_MIN = 12;
const NIVEL_MAX = 19;
// Media base del rumbo, a lo largo del trazo: suaviza los quiebres cortos.
const MEDIA_BASE_M = 15;
// Tolerancia para unir tramos consecutivos en una sola línea: el trazado
// ajustado a calles no empieza exactamente en el fix crudo.
const UNION_M = 25;

interface Vertice {
  lon: number;
  lat: number;
  t: number;
}

function distanciaM(a: { lat: number; lon: number }, b: { lat: number; lon: number }): number {
  const dLat = (b.lat - a.lat) * 111320;
  const dLon = (b.lon - a.lon) * 111320 * Math.cos(((a.lat + b.lat) / 2) * (Math.PI / 180));
  return Math.hypot(dLat, dLon);
}

function rumbo(a: { lat: number; lon: number }, b: { lat: number; lon: number }): number {
  const rad = Math.PI / 180;
  const y = Math.sin((b.lon - a.lon) * rad) * Math.cos(b.lat * rad);
  const x =
    Math.cos(a.lat * rad) * Math.sin(b.lat * rad) - Math.sin(a.lat * rad) * Math.cos(b.lat * rad) * Math.cos((b.lon - a.lon) * rad);
  return (Math.atan2(y, x) / rad + 360) % 360;
}

// Nivel de la flecha número m (a m·PASO_M metros del inicio de su línea).
function nivelDe(m: number): number {
  let z = NIVEL_MAX;
  let paso = 2;
  while (z > NIVEL_MIN && m % paso === 0) {
    z -= 1;
    paso *= 2;
  }
  return z;
}

// Vértices con hora: el tramo GPS une dos fixes (sus horas se conocen); el
// ajustado a calles reparte su hora de inicio a fin según la distancia.
function verticesDe(segmento: SegmentoRecorrido, finDe: (inicio: number) => number | null): Vertice[] | null {
  const inicio = segmento.instante;
  if (inicio == null) return null;
  const fin = finDe(inicio);
  if (fin == null || fin < inicio) return null;
  const coords = segmento.coordenadas;
  const acumulada = [0];
  for (let k = 1; k < coords.length; k += 1) {
    acumulada.push(
      acumulada[k - 1] + distanciaM({ lon: coords[k - 1][0], lat: coords[k - 1][1] }, { lon: coords[k][0], lat: coords[k][1] }),
    );
  }
  const total = acumulada[acumulada.length - 1];
  return coords.map(([lon, lat], k) => ({ lon, lat, t: total > 0 ? inicio + ((fin - inicio) * acumulada[k]) / total : inicio }));
}

export function flechasDeRecorrido(
  posiciones: Posicion[],
  segmentos: SegmentoRecorrido[],
  reconstruidos: TramoReconstruido[],
  paradas: Parada[],
): FeatureCollection<Point> {
  // Hora de fin de cada tramo: la del fix siguiente (GPS) o la del tramo
  // reconstruido que empieza en ese instante.
  const siguienteFix = new Map<number, number>();
  for (let i = 0; i + 1 < posiciones.length; i += 1) {
    siguienteFix.set(milisegundos(posiciones[i].registradoEn), milisegundos(posiciones[i + 1].registradoEn));
  }
  const finReconstruido = new Map(reconstruidos.map((tramo) => [milisegundos(tramo.desde), milisegundos(tramo.hasta)]));

  const conFlecha = segmentos
    .filter((s) => ((s.tipo === 'ruta' && s.modo !== 'quieto') || s.tipo === 'matched') && s.coordenadas.length >= 2)
    .sort((a, b) => (a.instante ?? 0) - (b.instante ?? 0));

  const lineas: Vertice[][] = [];
  for (const segmento of conFlecha) {
    const vertices = verticesDe(segmento, (inicio) =>
      segmento.tipo === 'matched' ? finReconstruido.get(inicio) ?? null : siguienteFix.get(inicio) ?? null,
    );
    if (!vertices) continue;
    const ultima = lineas[lineas.length - 1];
    const fin = ultima?.[ultima.length - 1];
    if (fin && distanciaM(fin, vertices[0]) <= UNION_M && vertices[0].t >= fin.t) ultima.push(...vertices.slice(1));
    else lineas.push(vertices);
  }

  const ventanasParada = paradas.map((p) => [milisegundos(p.inicio), milisegundos(p.fin)] as const);
  const enParada = (t: number) => ventanasParada.some(([a, b]) => t >= a && t <= b);

  const features: Feature<Point>[] = [];
  for (const linea of lineas) {
    const acumulada = [0];
    for (let k = 1; k < linea.length; k += 1) acumulada.push(acumulada[k - 1] + distanciaM(linea[k - 1], linea[k]));
    const total = acumulada[acumulada.length - 1];
    // Punto a s metros del inicio de la línea (interpolado entre vértices).
    let tramo = 0;
    const puntoEn = (s: number, desde = 0): Vertice & { k: number } => {
      let k = desde;
      while (k < linea.length - 2 && acumulada[k + 1] < s) k += 1;
      const largo = acumulada[k + 1] - acumulada[k];
      const f = largo > 0 ? Math.min(Math.max((s - acumulada[k]) / largo, 0), 1) : 0;
      const a = linea[k];
      const b = linea[k + 1];
      return { lon: a.lon + (b.lon - a.lon) * f, lat: a.lat + (b.lat - a.lat) * f, t: a.t + (b.t - a.t) * f, k };
    };
    for (let m = 1; m * PASO_M < total - PASO_M / 2; m += 1) {
      const s = m * PASO_M;
      const aqui = puntoEn(s, tramo);
      tramo = aqui.k;
      if (enParada(aqui.t)) continue;
      const atras = puntoEn(Math.max(s - MEDIA_BASE_M, 0));
      const adelante = puntoEn(Math.min(s + MEDIA_BASE_M, total), tramo);
      if (distanciaM(atras, adelante) < 1) continue;
      features.push({
        type: 'Feature',
        properties: { t: Math.round(aqui.t), r: Math.round(rumbo(atras, adelante)), n: nivelDe(m) },
        geometry: { type: 'Point', coordinates: [aqui.lon, aqui.lat] },
      });
    }
  }
  return { type: 'FeatureCollection', features };
}
