// Depuración del recorrido antes de dibujarlo y reproducirlo.
//
// El GPS de un teléfono detenido "camina": 20-80 m de deriva, fixes de red a
// cientos de metros que vuelven al instante. Dibujado tal cual, una parada de
// dos horas era una maraña de líneas y la ruta se desviaba con picos. Aquí:
//  1. Se quitan los picos: un fix que se aleja y el siguiente vuelve cerca del
//     anterior, con una velocidad implícita imposible para ese ir y volver.
//  2. Se agrupan las estancias: fixes que permanecen dentro de un radio durante
//     al menos DURACION_ESTANCIA_MS forman una parada; todos se llevan al centro
//     (mediana) de la estancia, así la línea entra al círculo de parada y sale
//     de él cuando la persona se mueve fuera del radio.
// No se inventan puntos: solo se descartan picos y se reubican fixes de una
// estancia sobre su centro. Hora, batería y velocidad de cada fix se conservan.
import type { Posicion } from '@contratos';

const RADIO_ESTANCIA_M = 45;
const DURACION_ESTANCIA_MS = 2 * 60_000;
// Precisión a partir de la cual un fix no puede sacar a nadie de una estancia.
const PRECISION_DUDOSA_M = 80;
const PICO_MIN_M = 60;
const PICO_VELOCIDAD_KMH = 90;

export interface Estancia {
  inicio: string;
  fin: string;
  latitud: number;
  longitud: number;
  // Índices [desde, hasta] en el recorrido depurado.
  desde: number;
  hasta: number;
}

function metros(a: { latitud: number; longitud: number }, b: { latitud: number; longitud: number }): number {
  const dLat = (b.latitud - a.latitud) * 111320;
  const dLon = (b.longitud - a.longitud) * 111320 * Math.cos(((a.latitud + b.latitud) / 2) * (Math.PI / 180));
  return Math.hypot(dLat, dLon);
}

const ms = (iso: string) => new Date(iso).getTime();

function mediana(valores: number[]): number {
  const orden = [...valores].sort((x, y) => x - y);
  const medio = Math.floor(orden.length / 2);
  return orden.length % 2 ? orden[medio] : (orden[medio - 1] + orden[medio]) / 2;
}

function quitarPicos(posiciones: Posicion[]): Posicion[] {
  if (posiciones.length < 3) return posiciones;
  const salida: Posicion[] = [posiciones[0]];
  for (let i = 1; i < posiciones.length - 1; i += 1) {
    const anterior = salida[salida.length - 1];
    const actual = posiciones[i];
    const siguiente = posiciones[i + 1];
    const ida = metros(anterior, actual);
    const vuelta = metros(actual, siguiente);
    const directo = metros(anterior, siguiente);
    const horas = Math.max(ms(siguiente.registradoEn) - ms(anterior.registradoEn), 1000) / 3_600_000;
    const esPico =
      ida > PICO_MIN_M && vuelta > PICO_MIN_M && directo < 0.4 * Math.min(ida, vuelta) && (ida + vuelta) / 1000 / horas > PICO_VELOCIDAD_KMH;
    const dudoso = (actual.precisionM ?? 0) > PRECISION_DUDOSA_M * 2 && directo < ida;
    if (!esPico && !dudoso) salida.push(actual);
  }
  salida.push(posiciones[posiciones.length - 1]);
  return salida;
}

export function depurarRecorrido(originales: Posicion[]): { posiciones: Posicion[]; estancias: Estancia[] } {
  const posiciones = quitarPicos(originales);
  const salida = posiciones.map((p) => ({ ...p }));
  const estancias: Estancia[] = [];
  let i = 0;
  while (i < salida.length) {
    // Crece la estancia mientras los fixes sigan dentro del radio del centro
    // acumulado; un fix de precisión dudosa no la corta por sí solo.
    let sumaLat = salida[i].latitud;
    let sumaLon = salida[i].longitud;
    let n = 1;
    let j = i + 1;
    while (j < salida.length) {
      const centro = { latitud: sumaLat / n, longitud: sumaLon / n };
      const d = metros(centro, salida[j]);
      const radio = Math.max(RADIO_ESTANCIA_M, Math.min(salida[j].precisionM ?? 0, PRECISION_DUDOSA_M));
      if (d > radio) break;
      sumaLat += salida[j].latitud;
      sumaLon += salida[j].longitud;
      n += 1;
      j += 1;
    }
    const hasta = j - 1;
    if (hasta > i && ms(salida[hasta].registradoEn) - ms(salida[i].registradoEn) >= DURACION_ESTANCIA_MS) {
      const grupo = salida.slice(i, hasta + 1);
      const latitud = mediana(grupo.map((p) => p.latitud));
      const longitud = mediana(grupo.map((p) => p.longitud));
      for (let k = i; k <= hasta; k += 1) {
        salida[k] = { ...salida[k], latitud, longitud, velocidadKmh: 0 };
      }
      estancias.push({ inicio: salida[i].registradoEn, fin: salida[hasta].registradoEn, latitud, longitud, desde: i, hasta });
      i = hasta + 1;
    } else {
      i += 1;
    }
  }
  return { posiciones: salida, estancias };
}
