// Depuración de fixes imposibles antes de dibujar el Replay. El crudo en la
// base no se toca: solo se aparta del trazado, de las distancias y de la
// reconstrucción, y la respuesta dice cuántos se apartaron y por qué.
//
// Dos casos vistos en producción:
//  - FUERA_DE_ZONA: fixes fuera del área operativa (37.422,-122.084 es la
//    ubicación por defecto del emulador/mock; un solo fix así sumaba 6.400 km
//    y cruzaba el mapa con una línea).
//  - SALTO: pico aislado imposible (ir y volver a >180 km/h). Es el patrón de
//    dos teléfonos reportando con la misma cuenta: los fixes alternan entre
//    dos sitios a kilómetros. Se conserva la secuencia coherente y, si los
//    saltos largos se repiten, se avisa de posible origen múltiple.

import { LAT_MAX_OP, LAT_MIN_OP, LON_MAX_OP, LON_MIN_OP } from './segmentos.js';
import { VELOCIDAD_IMPOSIBLE_KMH, distanciaKm } from './geo.js';

// Saltos de más de 2 km repetidos 3 o más veces en la ventana: no es ruido de
// GPS (que ronda decenas o cientos de metros), son dos equipos alternando.
const SALTO_ORIGEN_MULTIPLE_KM = 2;
const MIN_SALTOS_ORIGEN_MULTIPLE = 3;

function enZona(p) {
  return (
    Number.isFinite(p.latitud) && Number.isFinite(p.longitud)
    && p.latitud >= LAT_MIN_OP && p.latitud <= LAT_MAX_OP
    && p.longitud >= LON_MIN_OP && p.longitud <= LON_MAX_OP
  );
}

function velocidadKmh(a, b) {
  const km = distanciaKm(a.latitud, a.longitud, b.latitud, b.longitud);
  const horas = (new Date(b.registradoEn).getTime() - new Date(a.registradoEn).getTime()) / 3600000;
  if (!(horas > 0)) return km > 0.05 ? Infinity : 0;
  return km / horas;
}

export function depurarPosiciones(posiciones) {
  const enArea = [];
  let fueraDeZona = 0;
  for (const p of posiciones) {
    if (enZona(p)) enArea.push(p);
    else fueraDeZona += 1;
  }
  const conservadas = [];
  let saltos = 0;
  let saltosLargos = 0;
  for (let i = 0; i < enArea.length; i += 1) {
    const actual = enArea[i];
    const anterior = conservadas[conservadas.length - 1];
    const siguiente = enArea[i + 1];
    if (anterior && siguiente) {
      const ida = velocidadKmh(anterior, actual);
      const vuelta = velocidadKmh(actual, siguiente);
      const directo = velocidadKmh(anterior, siguiente);
      if (ida > VELOCIDAD_IMPOSIBLE_KMH && vuelta > VELOCIDAD_IMPOSIBLE_KMH && directo <= VELOCIDAD_IMPOSIBLE_KMH) {
        saltos += 1;
        if (distanciaKm(anterior.latitud, anterior.longitud, actual.latitud, actual.longitud) > SALTO_ORIGEN_MULTIPLE_KM) {
          saltosLargos += 1;
        }
        continue;
      }
    }
    conservadas.push(actual);
  }
  return {
    conservadas,
    calidad: {
      descartadasFueraDeZona: fueraDeZona,
      descartadasSalto: saltos,
      posibleOrigenMultiple: saltosLargos >= MIN_SALTOS_ORIGEN_MULTIPLE,
    },
  };
}
