import type { Hueco, Posicion } from '@contratos';
import { milisegundos, segmentosDeRecorrido } from '@/dominio/replay';
import type { Parada, SegmentoRecorrido, TramoReconstruido } from '@/dominio/replay';

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

// Tramos ajustados a calles que empiezan o terminan dentro de una parada: el
// matcher pegaba a la calle los minutos en que la persona seguía quieta y la
// línea arrancaba con una colita detrás de la primera flecha (Sanchez Pilay
// 30/09: tramo 17:15:29-17:20 con la parada hasta 17:16:30). Se corta en la
// proyección del primer fix al salir (o del último al llegar).
function proyeccionEnTrazado(coords: [number, number][], punto: [number, number]): { k: number; p: [number, number] } {
  let mejor = { k: 0, p: coords[0], d: Infinity };
  const escala = Math.cos(punto[1] * (Math.PI / 180));
  for (let k = 0; k < coords.length - 1; k += 1) {
    const a = coords[k];
    const b = coords[k + 1];
    const dx = (b[0] - a[0]) * escala;
    const dy = b[1] - a[1];
    const largo2 = dx * dx + dy * dy;
    const f = largo2 > 0 ? Math.min(Math.max((((punto[0] - a[0]) * escala) * dx + (punto[1] - a[1]) * dy) / largo2, 0), 1) : 0;
    const p: [number, number] = [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f];
    const d = metros(p, punto);
    if (d < mejor.d) mejor = { k, p, d };
  }
  return { k: mejor.k, p: mejor.p };
}

export function cortarTramosEnParadas(
  segmentos: SegmentoRecorrido[],
  paradas: Parada[],
  posiciones: Posicion[],
  finDe: (segmento: SegmentoRecorrido) => number,
): SegmentoRecorrido[] {
  const tiempos = posiciones.map((p) => milisegundos(p.registradoEn));
  return segmentos.map((segmento) => {
    if (segmento.tipo !== 'matched' || segmento.coordenadas.length < 2) return segmento;
    let coords = segmento.coordenadas;
    const inicio = segmento.instante ?? 0;
    const fin = finDe(segmento);
    for (const parada of paradas) {
      const pi = milisegundos(parada.inicio);
      const pf = milisegundos(parada.fin);
      if (inicio < pf && fin > pf && inicio >= pi - 1000) {
        // Sale de la parada dentro del tramo: se corta antes del primer fix al salir.
        const k = tiempos.findIndex((t) => t > pf);
        if (k >= 0) {
          const { k: tramo, p } = proyeccionEnTrazado(coords, [posiciones[k].longitud, posiciones[k].latitud]);
          coords = [p, ...coords.slice(tramo + 1)];
        }
      }
      if (inicio < pi && fin > pi && fin <= pf + 1000) {
        // Llega a la parada dentro del tramo: se corta después del último fix al llegar.
        let k = -1;
        for (let j = tiempos.length - 1; j >= 0; j -= 1) {
          if (tiempos[j] < pi) {
            k = j;
            break;
          }
        }
        if (k >= 0) {
          const { k: tramo, p } = proyeccionEnTrazado(coords, [posiciones[k].longitud, posiciones[k].latitud]);
          coords = [...coords.slice(0, tramo + 1), p];
        }
      }
    }
    return coords === segmento.coordenadas ? segmento : { ...segmento, coordenadas: coords };
  });
}

// Une cada parada con su recorrido: del centro de la parada (donde estuvo
// detenida, bajo la insignia) al punto donde empieza la línea al salir, y del
// punto donde termina la línea al llegar hasta el centro. Así la línea nace en
// la parada y no queda un trozo suelto a unos metros. Solo si ese extremo está
// cerca en el tiempo y en distancia: más lejos es un hueco, no una llegada.
const CONEXION_MAX_M = 400;
const CONEXION_MAX_MS = 10 * 60_000;

export function conectarParadas(
  segmentos: SegmentoRecorrido[],
  paradas: Parada[],
  finDe: (segmento: SegmentoRecorrido) => number,
): SegmentoRecorrido[] {
  const dibujados = segmentos.filter(
    (s) => (s.tipo === 'matched' || (s.tipo === 'ruta' && s.modo !== 'quieto')) && s.coordenadas.length >= 2,
  );
  const conexiones: SegmentoRecorrido[] = [];
  for (const parada of paradas) {
    const pi = milisegundos(parada.inicio);
    const pf = milisegundos(parada.fin);
    const centro: [number, number] = [parada.longitud, parada.latitud];
    // Al salir: el primer trazo que termina después de la parada y empezó en
    // ella o después (un tramo ajustado puede empezar dentro y ya viene
    // cortado en el primer fix al salir).
    const salida = dibujados
      .filter((s) => finDe(s) > pf && (s.instante ?? 0) >= pi - 1000 && (s.instante ?? 0) - pf <= CONEXION_MAX_MS)
      .sort((a, b) => (a.instante ?? 0) - (b.instante ?? 0))[0];
    const llegada = dibujados
      .filter((s) => (s.instante ?? 0) < pi && finDe(s) <= pf + 1000 && pi - finDe(s) <= CONEXION_MAX_MS)
      .sort((a, b) => finDe(b) - finDe(a))[0];
    if (salida) {
      const punto = salida.coordenadas[0];
      const d = metros(centro, punto);
      if (d > 1 && d <= CONEXION_MAX_M) {
        // Un segundo antes del trazo de salida: así se une con él en una
        // sola línea y la primera flecha queda al nacer de la parada.
        conexiones.push({
          tipo: 'ruta',
          modo: salida.modo === 'caminata' ? 'caminata' : 'vehiculo',
          coordenadas: [centro, punto],
          instante: (salida.instante ?? pf) - 1000,
        });
      }
    }
    if (llegada) {
      const punto = llegada.coordenadas[llegada.coordenadas.length - 1];
      const d = metros(centro, punto);
      if (d > 1 && d <= CONEXION_MAX_M) {
        conexiones.push({
          tipo: 'ruta',
          modo: llegada.modo === 'caminata' ? 'caminata' : 'vehiculo',
          coordenadas: [punto, centro],
          instante: finDe(llegada),
        });
      }
    }
  }
  return conexiones;
}

// Trazo que se dibuja en Repetición de ruta, a partir del recorrido depurado:
// paradas sin telaraña, caminata suavizada, ajustes a calles cortados en las
// paradas, línea recortada en su borde y unida a su centro.
export function segmentosParaDibujar(
  posiciones: Posicion[],
  huecos: Hueco[],
  reconstruidos: TramoReconstruido[],
  paradas: Parada[],
): SegmentoRecorrido[] {
  const ventanasParada = paradas.map((p) => [milisegundos(p.inicio), milisegundos(p.fin)] as const);
  const finTramo = new Map(reconstruidos.map((tramo) => [milisegundos(tramo.desde), milisegundos(tramo.hasta)]));
  // Un par GPS que empieza antes del último fix de la parada termina dentro
  // de ella; el par que sale de la parada (empieza en su último fix) sí se
  // dibuja.
  const dentroDeParada = (desde: number, hasta: number) =>
    ventanasParada.some(([inicio, fin]) => desde >= inicio && hasta <= fin && desde < fin);
  // Solo para dibujar: la caminata suavizada (sin "fideo") y, después, la
  // línea recortada en el borde de cada parada.
  const trazables = suavizarCaminata(posiciones, paradas);
  const crudos = segmentosDeRecorrido(trazables, huecos, reconstruidos).flatMap((segmento) => {
    const t = segmento.instante ?? 0;
    // Dentro de una parada todo trazo es deriva del GPS (bajo techo salta
    // 100-200 m): no se dibuja línea ni flechas, la parada se ve con su
    // halo y la nube de fixes. Antes tejía una telaraña con flechas.
    if (segmento.tipo === 'matched' && dentroDeParada(t, finTramo.get(t) ?? Infinity)) return [];
    if (segmento.tipo === 'ruta' && dentroDeParada(t, t)) return [{ ...segmento, modo: 'quieto' as const }];
    // Los fixes de antena llegan con velocidad 0 aunque la persona avance, y
    // el par quedaba "quieto" (sin línea): la ruta se veía cortada. Un par
    // quieto que se desplazó 40 m o más fuera de una parada es movimiento.
    if (segmento.tipo !== 'ruta' || segmento.modo !== 'quieto' || segmento.coordenadas.length < 2) return [segmento];
    const [a, b] = [segmento.coordenadas[0], segmento.coordenadas[segmento.coordenadas.length - 1]];
    const dLat = (b[1] - a[1]) * 111320;
    const dLon = (b[0] - a[0]) * 111320 * Math.cos(((a[1] + b[1]) / 2) * (Math.PI / 180));
    return [Math.hypot(dLat, dLon) >= 40 ? { ...segmento, modo: 'vehiculo' as const } : segmento];
  });
  const siguiente = new Map(trazables.slice(0, -1).map((p, i) => [milisegundos(p.registradoEn), milisegundos(trazables[i + 1].registradoEn)]));
  const finDe = (segmento: SegmentoRecorrido) => {
    const t = segmento.instante ?? 0;
    return (segmento.tipo === 'ruta' ? siguiente.get(t) : finTramo.get(t)) ?? t;
  };
  const cortados = cortarTramosEnParadas(crudos, paradas, trazables, finDe);
  const recortados = recortarEnParadas(cortados, paradas, finDe);
  // Cada parada se une, desde su centro, con el punto donde empieza la
  // línea al salir y donde termina al llegar.
  return [...recortados, ...conectarParadas(recortados, paradas, finDe)];
}
