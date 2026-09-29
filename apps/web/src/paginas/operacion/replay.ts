import type { Feature, FeatureCollection, LineString, Point } from 'geojson';
import type { Hueco, MetodoReconstruccion, Posicion, TramoReconstruido } from '@contratos';
import { GUION } from '../../util/formato';

export type { MetodoReconstruccion, TramoReconstruido } from '@contratos';

export interface SegmentoRecorrido {
  tipo: 'ruta' | 'hueco' | 'matched' | 'estimated';
  coordenadas: [number, number][];
  // Solo en los tramos de ruta: 0..4 según la velocidad del fix que cierra el
  // par. La capa de línea lo usa para colorear tramo a tramo, como Traccar.
  // Los tramos reconstruidos no llevan banda: cada método tiene su estilo
  // propio (ADR-007) y nunca se pintan como GPS registrado.
  banda?: number;
  // Modo de desplazamiento del par (solo ruta): el corredor corporativo dibuja
  // vehículo con casing ancho, caminata con línea fina del mismo idioma y
  // quieto sin línea (la dispersión se muestra como halo de puntos).
  modo?: ModoReal;
}

// Modo de desplazamiento de un fix o par GPS registrado. No toca el contrato
// del API: se deriva solo de la velocidad efectiva local.
export type ModoReal = 'vehiculo' | 'caminata' | 'quieto';

export const ETIQUETA_METODO_TRAMO: Record<MetodoReconstruccion, string> = {
  MATCHED: 'Ajustado a vía',
  ESTIMATED: 'Tramo estimado',
};

// Tramo heredado del contrato anterior (`estimados`, sin método): solo une
// dos fixes con un trazado por calles.
interface EstimadoHeredado {
  desde: string;
  hasta: string;
  trazado: [number, number][];
}

// Tramo tal como puede llegar del API. El contrato vigente es TramoReconstruido
// (desde, hasta, metodo, mapaVersion, trazado), pero la extensión a tramos
// densos puede añadir campos de ventana (índices o instantes) que la web no
// necesita: se toleran y se ignoran sin romper.
type TramoCrudo = Record<string, unknown>;

function esParCoordenada(valor: unknown): valor is [number, number] {
  return (
    Array.isArray(valor) &&
    typeof valor[0] === 'number' &&
    typeof valor[1] === 'number' &&
    Number.isFinite(valor[0]) &&
    Number.isFinite(valor[1])
  );
}

// Sanea un tramo del API a la forma que dibujan las capas. Devuelve null si no
// se puede ubicar (sin desde/hasta) o dibujar (trazado con menos de dos puntos
// válidos). Un método ausente o desconocido cae a ESTIMATED (punteado gris, sin
// chevrones): un tramo reconstruido nunca se dibuja como GPS registrado.
function sanearTramo(tramo: unknown): TramoReconstruido | null {
  if (!tramo || typeof tramo !== 'object') return null;
  const crudo = tramo as TramoCrudo;
  if (typeof crudo.desde !== 'string' || typeof crudo.hasta !== 'string') return null;
  if (!Array.isArray(crudo.trazado)) return null;
  const trazado = (crudo.trazado as unknown[])
    .filter(esParCoordenada)
    .map(([lon, lat]) => [lon, lat] as [number, number]);
  if (trazado.length < 2) return null;
  const metodo: MetodoReconstruccion = crudo.metodo === 'MATCHED' ? 'MATCHED' : 'ESTIMATED';
  const mapaVersion = typeof crudo.mapaVersion === 'string' ? crudo.mapaVersion : null;
  return { desde: crudo.desde, hasta: crudo.hasta, metodo, mapaVersion, trazado };
}

// Normaliza la respuesta del servidor a la lista de tramos reconstruidos. El
// contrato vigente devuelve `reconstruidos` con método y versión de mapa; los
// `estimados` heredados no traen método (son rutas A→B sin observaciones) y
// se tratan como ESTIMATED como compatibilidad temporal mientras el servidor
// aún los devuelva. Los campos extra de ventana de los tramos densos se
// toleran: el saneado solo exige desde/hasta/trazado y conserva el método.
export function normalizarReconstruidos(respuesta: {
  reconstruidos?: unknown;
  estimados?: EstimadoHeredado[] | null;
} | null | undefined): TramoReconstruido[] {
  if (!respuesta) return [];
  if (Array.isArray(respuesta.reconstruidos)) {
    const saneados: TramoReconstruido[] = [];
    for (const tramo of respuesta.reconstruidos) {
      const saneado = sanearTramo(tramo);
      if (saneado) saneados.push(saneado);
    }
    return saneados;
  }
  const heredados = Array.isArray(respuesta.estimados) ? respuesta.estimados : [];
  const saneados: TramoReconstruido[] = [];
  for (const tramo of heredados) {
    const saneado = sanearTramo({ ...tramo, metodo: 'ESTIMATED', mapaVersion: null });
    if (saneado) saneados.push(saneado);
  }
  return saneados;
}

// Método del tramo reconstruido que une dos instantes, si existe.
export function metodoDeTramo(
  reconstruidos: TramoReconstruido[],
  desde: string,
  hasta: string,
): TramoReconstruido | null {
  return reconstruidos.find((tramo) => tramo.desde === desde && tramo.hasta === hasta) ?? null;
}

// Tramo reconstruido que toca el fix indicado (par anterior o siguiente). El
// clic sobre un trazado de hueco selecciona el fix más cercano, que es un
// extremo del tramo, así que basta con mirar los dos pares vecinos. Los tramos
// densos ajustados a vía cubren una ventana de varios fixes: el fix interior
// no es extremo de ningún par, así que además se busca la ventana que contiene
// su instante (con varias, manda MATCHED) para rotular el método en la ficha.
export function tramoDeIndice(
  posiciones: Posicion[],
  reconstruidos: TramoReconstruido[],
  indice: number,
): TramoReconstruido | null {
  const actual = posiciones[indice];
  if (!actual) return null;
  const anterior = indice > 0 ? posiciones[indice - 1] : null;
  const siguiente = indice < posiciones.length - 1 ? posiciones[indice + 1] : null;
  if (anterior) {
    const tramo = metodoDeTramo(reconstruidos, anterior.registradoEn, actual.registradoEn);
    if (tramo) return tramo;
  }
  if (siguiente) {
    const tramo = metodoDeTramo(reconstruidos, actual.registradoEn, siguiente.registradoEn);
    if (tramo) return tramo;
  }
  const instante = milisegundos(actual.registradoEn);
  if (Number.isFinite(instante)) {
    let estimado: TramoReconstruido | null = null;
    for (const tramo of reconstruidos) {
      const desde = milisegundos(tramo.desde);
      const hasta = milisegundos(tramo.hasta);
      if (!Number.isFinite(desde) || !Number.isFinite(hasta)) continue;
      const inicio = Math.min(desde, hasta);
      const fin = Math.max(desde, hasta);
      if (instante < inicio || instante > fin) continue;
      if (tramo.metodo === 'MATCHED') return tramo;
      estimado ??= tramo;
    }
    if (estimado) return estimado;
  }
  return null;
}

// Una parada del recorrido. En Replay manda la lista del servidor
// (/reports/stops), con dirección resuelta cuando el geocodificador la tiene;
// el helper local genera la misma forma con direccion null para que la
// interfaz no tenga que distinguir el origen.
export interface Parada {
  inicio: string;
  fin: string;
  duracionMin: number;
  latitud: number;
  longitud: number;
  direccion: string | null;
}

// Estado operativo del fix actual, para el marcador del reproductor.
export type EstadoUnidad = 'movimiento' | 'detencion' | 'sinSenal';

// Umbrales acordados con operación: por debajo de 2 km/h la lectura no
// distingue avance real, y 3 minutos descartan las paradas de semáforo.
export const VELOCIDAD_DETENCION_KMH = 2;
export const DURACION_DETENCION_MIN = 3;
// Caminata sostenida por debajo de 8 km/h: separa el tramo a pie (2-8 km/h)
// del tramo en moto (10-17 km/h) de la jornada de referencia. La deriva con el
// equipo parado (±25 m) queda bajo el umbral de detención y se trata como
// quieto, nunca como caminata.
export const UMBRAL_CAMINATA_KMH = 8;
// Radio de parada para el halo de dispersión: cubre la deriva parada (±25 m)
// con margen para lecturas con precisión pobre.
export const RADIO_PARADA_M = 60;
// El servidor corta los huecos a los 10 min; para el marcador una lectura que
// lleva más de 5 min sin el fix siguiente ya se considera sin señal, incluso
// aunque no llegue a ser un hueco formal.
export const ANTIGUEDAD_SIN_SENAL_MS = 5 * 60 * 1000;

export function milisegundos(iso: string): number {
  return new Date(iso).getTime();
}

// Hora en 24 h para la lectura del reproductor y las etiquetas del mapa:
// util/formato.hora añade "a. m./p. m." y alarga la franja compacta y los
// rótulos. horaCorta se queda solo con la hora y fechaHoraCorta (abajo) con el
// día, para la ficha del punto.
const HORA_CORTA = new Intl.DateTimeFormat('es-EC', { hour: '2-digit', minute: '2-digit', hour12: false });

export function horaCorta(valor?: string | null): string {
  return valor ? HORA_CORTA.format(new Date(valor)) : GUION;
}

// Fecha y hora en 24 h para la ficha del punto: util/formato.fechaHora usa el
// formato de es-EC con "a. m./p. m.", así que el Replay no comparte ese
// formateador. Componentes explícitos en vez de dateStyle/timeStyle para fijar
// el orden y los dos dígitos; el año a dos dígitos mantiene la fila compacta.
const FECHA_HORA_CORTA = new Intl.DateTimeFormat('es-EC', {
  day: '2-digit',
  month: '2-digit',
  year: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
});

export function fechaHoraCorta(valor?: string | null): string {
  return valor ? FECHA_HORA_CORTA.format(new Date(valor)) : GUION;
}

// Fecha local de ayer (YYYY-MM-DD): alimenta el rango por defecto de Replay y
// los botones rápidos del filtro. rango.ts solo expone hoy; se retrocede un día
// con los componentes locales (Date normaliza el desborde de mes o año) y se
// compensa la zona para leer la fecha con toISOString, el mismo criterio local
// que usa fechaHoyLocal.
export function fechaAyerLocal(): string {
  const ahora = new Date();
  const ayer = new Date(ahora.getFullYear(), ahora.getMonth(), ahora.getDate() - 1);
  const local = new Date(ayer.getTime() - ayer.getTimezoneOffset() * 60000);
  return local.toISOString().slice(0, 10);
}

const RADIO_TIERRA_KM = 6371;

// Punto geográfico mínimo para medir distancias; Posicion lo cumple sin
// conversión, y así los helpers de selección pueden medir contra el clic.
interface Coordenada {
  latitud: number;
  longitud: number;
}

// Distancia Haversine, igual que el API: se necesita local para estimar la
// velocidad implícita cuando el equipo no reporta velocidad.
function distanciaKm(a: Coordenada, b: Coordenada): number {
  const dLat = ((b.latitud - a.latitud) * Math.PI) / 180;
  const dLon = ((b.longitud - a.longitud) * Math.PI) / 180;
  const suma =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((a.latitud * Math.PI) / 180) * Math.cos((b.latitud * Math.PI) / 180) * Math.sin(dLon / 2) ** 2;
  return RADIO_TIERRA_KM * 2 * Math.asin(Math.sqrt(suma));
}

// Velocidad de un fix: la reportada manda; si viene nula se estima con el fix
// anterior (distancia/tiempo). Los equipos que no reportan velocidad quedaban
// fuera de las detenciones aunque estuvieran parados, porque "null < 2" se
// evaluaba como falso. Sin anterior, o con tiempos iguales, no hay evidencia.
function velocidadEfectivaKmh(posicion: Posicion, anterior: Posicion | null): number | null {
  if (posicion.velocidadKmh != null && Number.isFinite(posicion.velocidadKmh)) return posicion.velocidadKmh;
  if (!anterior) return null;
  const horas = (milisegundos(posicion.registradoEn) - milisegundos(anterior.registradoEn)) / 3600000;
  if (!(horas > 0)) return null;
  return distanciaKm(anterior, posicion) / horas;
}

// Bandas de color de Traccar: teal <10, verde <25, amarillo <45, naranja <70
// y rojo >=70. La velocidad desconocida cae en la banda lenta, que es la
// lectura conservadora: no se pinta de rojo sin dato.
function bandaVelocidad(velocidadKmh: number | null): number {
  if (velocidadKmh == null || !Number.isFinite(velocidadKmh) || velocidadKmh < 10) return 0;
  if (velocidadKmh < 25) return 1;
  if (velocidadKmh < 45) return 2;
  if (velocidadKmh < 70) return 3;
  return 4;
}

// Serie de velocidades efectivas alineada con las posiciones: la reportada
// manda y, si falta, se estima contra el fix anterior. Null sin evidencia.
function velocidadesEfectivas(posiciones: Posicion[]): (number | null)[] {
  return posiciones.map((posicion, i) => velocidadEfectivaKmh(posicion, i > 0 ? posiciones[i - 1] : null));
}

// Índices de fixes detenidos en rachas de al menos 3 seguidos bajo el umbral.
// Exigir racha evita que un único fix lento de caminata (2-8 km/h con un valle
// bajo 2) se lea como parada y rompa la línea a pie con un hueco de halo.
function indicesQuietos(posiciones: Posicion[]): Set<number> {
  const velocidades = velocidadesEfectivas(posiciones);
  const detenido = velocidades.map((v) => v != null && Number.isFinite(v) && v < VELOCIDAD_DETENCION_KMH);
  const quietos = new Set<number>();
  let inicio = -1;
  const cerrar = (fin: number) => {
    if (inicio >= 0 && fin - inicio + 1 >= 3) {
      for (let i = inicio; i <= fin; i += 1) quietos.add(i);
    }
    inicio = -1;
  };
  for (let i = 0; i < detenido.length; i += 1) {
    if (detenido[i]) {
      if (inicio < 0) inicio = i;
    } else {
      if (inicio >= 0) cerrar(i - 1);
    }
  }
  if (inicio >= 0) cerrar(detenido.length - 1);
  return quietos;
}

// Modo de un fix GPS registrado: quieto si pertenece a una racha detenida,
// caminata si su velocidad y la de un vecino están bajo 8 km/h (sostenida),
// vehículo en el resto (incluido sin dato: no se inventa caminata).
export function modoDePunto(posiciones: Posicion[], indice: number): ModoReal {
  const velocidades = velocidadesEfectivas(posiciones);
  const quietos = indicesQuietos(posiciones);
  if (quietos.has(indice)) return 'quieto';
  const actual = velocidades[indice];
  if (actual == null || !Number.isFinite(actual) || actual >= UMBRAL_CAMINATA_KMH) return 'vehiculo';
  const anterior = indice > 0 ? velocidades[indice - 1] : null;
  const siguiente = indice < velocidades.length - 1 ? velocidades[indice + 1] : null;
  const vecinoBajo =
    (anterior != null && Number.isFinite(anterior) && anterior < UMBRAL_CAMINATA_KMH) ||
    (siguiente != null && Number.isFinite(siguiente) && siguiente < UMBRAL_CAMINATA_KMH);
  return vecinoBajo ? 'caminata' : 'vehiculo';
}

// Modo del par entre dos fixes (el que cierra el par manda, con el vecino
// como confirmación de caminata sostenida). El par es quieto solo si sus dos
// extremos pertenecen a la misma racha detenida: fuera de ella, dos fixes
// lentos aislados siguen siendo caminata o vehículo, no dispersión parada.
function modoDePar(quietos: Set<number>, velocidades: (number | null)[], i: number): ModoReal {
  if (quietos.has(i - 1) && quietos.has(i)) return 'quieto';
  const actual = velocidades[i];
  if (actual == null || !Number.isFinite(actual) || actual >= UMBRAL_CAMINATA_KMH) return 'vehiculo';
  const anterior = i > 0 ? velocidades[i - 1] : null;
  const siguiente = i < velocidades.length - 1 ? velocidades[i + 1] : null;
  const vecinoBajo =
    (anterior != null && Number.isFinite(anterior) && anterior < UMBRAL_CAMINATA_KMH) ||
    (siguiente != null && Number.isFinite(siguiente) && siguiente < UMBRAL_CAMINATA_KMH);
  return vecinoBajo ? 'caminata' : 'vehiculo';
}

export const ETIQUETA_MODO_REAL: Record<ModoReal, string> = {
  vehiculo: 'En vehículo',
  caminata: 'A pie',
  quieto: 'Detenido',
};

// Halo de dispersión de una parada: centro de la parada, radio observado
// (máxima distancia de sus fixes al centro, acotada a 15-80 m) y ventana
// temporal. El halo se dibuja como círculo sutil y los fixes quietos como
// nube de puntos: se muestra la dispersión real sin unirla con líneas.
export interface HaloParada {
  indice: number;
  latitud: number;
  longitud: number;
  radioM: number;
  inicio: string;
  fin: string;
  duracionMin: number;
}

export function halosDeParadas(posiciones: Posicion[], paradas: Parada[]): HaloParada[] {
  return paradas
    .map((parada, indice) => {
      if (!Number.isFinite(parada.latitud) || !Number.isFinite(parada.longitud)) return null;
      const inicio = milisegundos(parada.inicio);
      const fin = milisegundos(parada.fin);
      if (!Number.isFinite(inicio) || !Number.isFinite(fin)) return null;
      const desde = Math.min(inicio, fin);
      const hasta = Math.max(inicio, fin);
      let maxDistM = 0;
      let fixes = 0;
      for (const posicion of posiciones) {
        if (!Number.isFinite(posicion.latitud) || !Number.isFinite(posicion.longitud)) continue;
        const instante = milisegundos(posicion.registradoEn);
        if (!Number.isFinite(instante) || instante < desde || instante > hasta) continue;
        fixes += 1;
        maxDistM = Math.max(
          maxDistM,
          distanciaKm({ latitud: parada.latitud, longitud: parada.longitud }, posicion) * 1000,
        );
      }
      // Sin fixes en la ventana (parada del servidor fuera del rango cargado)
      // se conserva un halo de referencia de 25 m: la insignia sigue anclada a
      // la parada sin inventar dispersión.
      const radioM = fixes === 0 ? 25 : Math.min(Math.max(maxDistM, 15), 80);
      return { indice, latitud: parada.latitud, longitud: parada.longitud, radioM, inicio: parada.inicio, fin: parada.fin, duracionMin: parada.duracionMin };
    })
    .filter((halo): halo is HaloParada => halo != null);
}

// Rumbo inicial (0 = norte) entre dos pares lat/lon; lo usan los fixes y los
// chevrones del tramo estimado, que no tienen Posicion.
function rumboEntrePuntos(latA: number, lonA: number, latB: number, lonB: number): number {
  const lat1 = (latA * Math.PI) / 180;
  const lat2 = (latB * Math.PI) / 180;
  const dLon = ((lonB - lonA) * Math.PI) / 180;
  const y = Math.sin(dLon) * Math.cos(lat2);
  const x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
  return (((Math.atan2(y, x) * 180) / Math.PI) + 360) % 360;
}

function rumboEntre(a: Posicion, b: Posicion): number {
  return rumboEntrePuntos(a.latitud, a.longitud, b.latitud, b.longitud);
}

// El API define cada hueco con los dos fixes que lo rodean, así que basta con
// marcar exactamente esos pares para partir la línea. Ahora cada par de fixes
// es una Feature propia: el coloreado por velocidad necesita que cada tramo
// lleve su banda, y una fuente GeoJSON estática de miles de líneas de dos
// puntos se publica de una sola vez al cargar el recorrido.
// Los tramos reconstruidos entran con su método (MATCHED o ESTIMATED) y su
// estilo propio; los pares que cubren ya no dibujan su recta. Los tramos
// densos ajustados a vía cubren una ventana de varios fixes (sus extremos no
// son adyacentes): su trazado reemplaza la recta cruda interior, así que la
// misma ruta no queda dibujada dos veces. Solo siguen crudos el par que entra
// a la ventana (termina en desde) y el que sale (empieza en hasta): son las
// anclas limpias de la transición cruda→matched→cruda.

// Ventana temporal de un tramo MATCHED: un tramo denso ajustado a vía cubre
// varios fixes consecutivos entre desde y hasta, y su trazado por calles
// reemplaza la recta cruda interior. Los extremos de la ventana quedan fuera
// de `ventanasMatched` (el test es estricto) para conservarlos como anclas de
// la transición.
interface VentanaMatched {
  inicio: number;
  fin: number;
}

function ventanasMatched(reconstruidos: TramoReconstruido[]): VentanaMatched[] {
  const ventanas: VentanaMatched[] = [];
  for (const tramo of reconstruidos) {
    if (tramo.metodo !== 'MATCHED') continue;
    const desde = milisegundos(tramo.desde);
    const hasta = milisegundos(tramo.hasta);
    if (!Number.isFinite(desde) || !Number.isFinite(hasta)) continue;
    ventanas.push({ inicio: Math.min(desde, hasta), fin: Math.max(desde, hasta) });
  }
  return ventanas;
}

// Verdadero cuando el instante cae estrictamente dentro de una ventana MATCHED:
// el trazado ajustado ya cubre ese tramo y la recta cruda sobra.
function enVentanaMatched(ventanas: VentanaMatched[], instante: number): boolean {
  for (const ventana of ventanas) {
    if (instante > ventana.inicio && instante < ventana.fin) return true;
  }
  return false;
}

// Redondeo de presentación del trazado reconstruido (MATCHED y ESTIMATED):
// las esquinas del ajuste a vía se ven toscas a zoom urbano y un chaflán de
// una pasada se lee como corte recto, no como giro. Cada vértice interior B se
// sustituye por un arco (fillet) tangente a los dos lados AB y BC:
//   φ   = quiebre real en B (0° = sigue recto; 180° = horquilla)
//   R   = min(fracción·min(|AB|,|BC|), 10 m)        radio base por lado
//   R  ≤ desplazamientoMax / (sec(φ/2) − 1)         desviación ≤ 10 m
//   R  ≤ 2.5 m                                      horquilla (φ > 150°)
//   R  ≤ 0.45·min(|AB|,|BC|) / tan(φ/2)             tangencia dentro del lado
//   t   = R·tan(φ/2)                                distancia a cada tangencia
// Las tangencias caen sobre el trazado original y el arco se reparte en 2-5
// puntos según φ (a más cerrado, más puntos). Garantías:
//  - extremos intactos; quiebre < 2°, lados nulos o entradas no finitas se
//    conservan tal cual;
//  - todo punto generado dista ≤ s = R·(sec(φ/2)−1) del vértice B (que es del
//    trazado original), con s ≤ desplazamientoMax y nunca más de 10 m;
//  - t ≤ 0.45·lado en ambos extremos de cada lado: dos vértices vecinos no
//    pueden cruzar sus tangencias ni sus arcos;
//  - regla dura de no autointersección: cada cuerda nueva se contrasta contra
//    los segmentos originales (menos sus dos lados) y contra las cuerdas ya
//    aceptadas, con una rejilla espacial. Si algo la cruza, el vértice se
//    conserva tal cual: en horquillas y rotondas nunca aparece un bucle; los
//    espolones de ida y vuelta (coordenada repetida) también se conservan;
//  - la longitud solo puede encoger: 2t = 2R·tan(φ/2) > R·φ (arco) y los
//    extremos no se mueven.
// Es solo presentación: el GPS registrado y los huecos rectos no pasan por
// aquí y el dato crudo queda intacto (ADR-007: nada se presenta como GPS si
// no lo es).
export const SUAVIZADO_FRACCION = 0.35;
export const SUAVIZADO_DESPLAZAMIENTO_MAX_M = 10;
export const SUAVIZADO_RADIO_MAX_M = 10;
// Por debajo de 2° de quiebre el arco no redondea nada visible y solo duplica
// puntos: el vértice se conserva tal cual.
export const SUAVIZADO_ANGULO_MIN_GRADOS = 2;
// Horquilla: por encima de 150° de quiebre el arco se acota a un radio pequeño
// para no meterse entre los brazos del pliegue; si aun así no cabe en los
// lados, el vértice se conserva.
export const SUAVIZADO_ANGULO_HORQUILLA_GRADOS = 150;
export const SUAVIZADO_RADIO_HORQUILLA_M = 2.5;
// Radio mínimo visible: un arco más pequeño que esto no aporta y solo duplica
// puntos (pliegues casi de 180°), así que el vértice se conserva.
const SUAVIZADO_RADIO_MIN_M = 0.1;
// Tangencia mínima: por debajo el arco no se ve y solo duplica puntos.
const SUAVIZADO_TANGENTE_MIN_M = 0.15;
// La tangencia nunca pasa de esta fracción del lado: dos vértices vecinos
// pueden cortar el mismo lado (0.45 + 0.45 < 1) sin cruzarse entre sí.
const SUAVIZADO_FRACCION_LADO = 0.45;
// Metros por grado de latitud con la misma esfera que distanciaKm: el plano
// local del arco comparte escala con las longitudes en metros.
const SUAVIZADO_METROS_POR_GRADO = (2 * Math.PI * 6371000) / 360;
// Rejilla espacial de la regla anti-cruce: celdas de 32 m (el arco se aparta
// ≤ 10 m del vértice, así que la consulta por bbox ve todo lo cercano). Un
// segmento que cubre demasiadas celdas pasa a una lista corta que se revisa
// siempre; si el plano se vuelve patológico, el suavizado se rinde y devuelve
// el trazado crudo.
const SUAVIZADO_CELDA_M = 32;
const SUAVIZADO_CELDAS_POR_SEGMENTO = 256;
const SUAVIZADO_CANDIDATOS_MAX = 600;
const SUAVIZADO_LARGOS_MAX = 256;

// Cruce propio (estricto) de dos segmentos: los extremos compartidos y las
// tangencias no cuentan, solo el atravesarse. Con NaN devuelve falso.
function segmentosCruzan(
  a: [number, number],
  b: [number, number],
  c: [number, number],
  d: [number, number],
): boolean {
  const lado = (p: [number, number], q: [number, number], r: [number, number]): number =>
    (q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0]);
  const d1 = lado(c, d, a);
  const d2 = lado(c, d, b);
  const d3 = lado(a, b, c);
  const d4 = lado(a, b, d);
  return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
}

export function suavizarTrazado(
  coordenadas: [number, number][],
  desplazamientoMaxM = SUAVIZADO_DESPLAZAMIENTO_MAX_M,
  fraccion = SUAVIZADO_FRACCION,
): [number, number][] {
  const desvioMaxM = Math.min(desplazamientoMaxM, SUAVIZADO_DESPLAZAMIENTO_MAX_M);
  if (coordenadas.length < 3 || !(desvioMaxM > 0) || !(fraccion > 0)) return coordenadas;
  // Una sola proyección equirectangular para toda la traza: arcos y rejilla
  // comparten plano y escala; a ≤ 10 m de desvío el error es despreciable.
  let refLon = 0;
  let refLat = 0;
  let hayReferencia = false;
  for (const [lon, lat] of coordenadas) {
    if (Number.isFinite(lon) && Number.isFinite(lat)) {
      refLon = lon;
      refLat = lat;
      hayReferencia = true;
      break;
    }
  }
  if (!hayReferencia) return coordenadas;
  const cosRef = Math.cos((refLat * Math.PI) / 180);
  if (!(cosRef > 1e-6)) return coordenadas;
  const xy: [number, number][] = coordenadas.map(([lon, lat]) =>
    Number.isFinite(lon) && Number.isFinite(lat)
      ? [(lon - refLon) * SUAVIZADO_METROS_POR_GRADO * cosRef, (lat - refLat) * SUAVIZADO_METROS_POR_GRADO]
      : [NaN, NaN],
  );
  const rejillaOriginal = new Map<string, number[]>();
  const originalesLargos: number[] = [];
  const cuerdasGeneradas: [[number, number], [number, number]][] = [];
  const rejillaGenerada = new Map<string, number[]>();
  const generadosLargos: number[] = [];
  // Celdas que cubre la caja del segmento; null si son demasiadas (segmento
  // largo). La inserción por caja es un superconjunto: cualquier cruce cae en
  // una celda de la caja del otro segmento y por tanto se encuentra.
  const celdasDe = (a: [number, number], b: [number, number]): string[] | null => {
    const ix0 = Math.floor(Math.min(a[0], b[0]) / SUAVIZADO_CELDA_M);
    const ix1 = Math.floor(Math.max(a[0], b[0]) / SUAVIZADO_CELDA_M);
    const iy0 = Math.floor(Math.min(a[1], b[1]) / SUAVIZADO_CELDA_M);
    const iy1 = Math.floor(Math.max(a[1], b[1]) / SUAVIZADO_CELDA_M);
    const celdas: string[] = [];
    for (let ix = ix0; ix <= ix1; ix += 1) {
      for (let iy = iy0; iy <= iy1; iy += 1) {
        if (celdas.length >= SUAVIZADO_CELDAS_POR_SEGMENTO) return null;
        celdas.push(`${ix},${iy}`);
      }
    }
    return celdas;
  };
  const insertar = (
    rejilla: Map<string, number[]>,
    largos: number[],
    a: [number, number],
    b: [number, number],
    indice: number,
  ): void => {
    if (!Number.isFinite(a[0]) || !Number.isFinite(b[0])) return;
    const celdas = celdasDe(a, b);
    if (!celdas) {
      largos.push(indice);
      return;
    }
    for (const celda of celdas) {
      const lista = rejilla.get(celda);
      if (lista) lista.push(indice);
      else rejilla.set(celda, [indice]);
    }
  };
  // Índices de segmentos/cuerdas en las celdas de la caja de p–q, ampliada una
  // celda para no perder los que rozan el borde. null si hay demasiados: en
  // ese caso el suavizado del vértice se descarta por precaución.
  const candidatosCerca = (
    rejilla: Map<string, number[]>,
    largos: number[],
    p: [number, number],
    q: [number, number],
  ): number[] | null => {
    const oeste = Math.floor(Math.min(p[0], q[0]) / SUAVIZADO_CELDA_M) - 1;
    const este = Math.floor(Math.max(p[0], q[0]) / SUAVIZADO_CELDA_M) + 1;
    const sur = Math.floor(Math.min(p[1], q[1]) / SUAVIZADO_CELDA_M) - 1;
    const norte = Math.floor(Math.max(p[1], q[1]) / SUAVIZADO_CELDA_M) + 1;
    if ((este - oeste + 1) * (norte - sur + 1) > 64) return null;
    const lista: number[] = [...largos];
    const vistos = new Set<number>(largos);
    for (let ix = oeste; ix <= este; ix += 1) {
      for (let iy = sur; iy <= norte; iy += 1) {
        const vecinos = rejilla.get(`${ix},${iy}`);
        if (!vecinos) continue;
        for (const indice of vecinos) {
          if (vistos.has(indice)) continue;
          vistos.add(indice);
          lista.push(indice);
          if (lista.length > SUAVIZADO_CANDIDATOS_MAX) return null;
        }
      }
    }
    return lista;
  };
  for (let i = 1; i < xy.length; i += 1) {
    insertar(rejillaOriginal, originalesLargos, xy[i - 1], xy[i], i - 1);
    if (originalesLargos.length > SUAVIZADO_LARGOS_MAX) return coordenadas;
  }
  // Espolones de ida y vuelta sobre la misma vía: si un vértice repite la
  // coordenada de otro punto del trazado (mismo punto a 10 cm), el arco
  // cruzaría la copia de la vía; se conserva el vértice y queda el pliegue.
  const cuantizada = (p: [number, number]): string =>
    `${Math.round(p[0] * 10)},${Math.round(p[1] * 10)}`;
  const vecesPunto = new Map<string, number>();
  for (const punto of xy) {
    if (!Number.isFinite(punto[0]) || !Number.isFinite(punto[1])) continue;
    const clave = cuantizada(punto);
    vecesPunto.set(clave, (vecesPunto.get(clave) ?? 0) + 1);
  }
  const salida: [number, number][] = [coordenadas[0]];
  for (let i = 1; i < coordenadas.length - 1; i += 1) {
    const [lonB, latB] = coordenadas[i];
    const [bx, by] = xy[i];
    if (![xy[i - 1][0], xy[i - 1][1], bx, by, xy[i + 1][0], xy[i + 1][1]].every((valor) => Number.isFinite(valor))) {
      salida.push([lonB, latB]);
      continue;
    }
    if ((vecesPunto.get(cuantizada(xy[i])) ?? 0) > 1) {
      salida.push([lonB, latB]);
      continue;
    }
    // Vectores locales alrededor de B: u = A→B, v = B→C.
    const ux = bx - xy[i - 1][0];
    const uy = by - xy[i - 1][1];
    const vx = xy[i + 1][0] - bx;
    const vy = xy[i + 1][1] - by;
    const ladoAM = Math.hypot(ux, uy);
    const ladoBM = Math.hypot(vx, vy);
    if (!(ladoAM > 0) || !(ladoBM > 0)) {
      salida.push([lonB, latB]);
      continue;
    }
    const seno = Math.abs(ux * vy - uy * vx) / (ladoAM * ladoBM);
    const coseno = Math.min(Math.max((ux * vx + uy * vy) / (ladoAM * ladoBM), -1), 1);
    const anguloRad = Math.atan2(seno, coseno);
    const anguloGrados = (anguloRad * 180) / Math.PI;
    if (anguloGrados < SUAVIZADO_ANGULO_MIN_GRADOS) {
      salida.push([lonB, latB]);
      continue;
    }
    const minLadoM = Math.min(ladoAM, ladoBM);
    const tanMitad = Math.tan(anguloRad / 2);
    const secMitad = 1 / Math.cos(anguloRad / 2);
    // Radio adaptativo: fracción del lado más corto, acotado por desviación
    // (s = R·(sec(φ/2)−1) ≤ desvioMax), por horquilla y por tangencia.
    let radioM = Math.min(fraccion * minLadoM, SUAVIZADO_RADIO_MAX_M);
    const factorDesvio = secMitad - 1;
    if (factorDesvio > 0) radioM = Math.min(radioM, desvioMaxM / factorDesvio);
    if (anguloGrados > SUAVIZADO_ANGULO_HORQUILLA_GRADOS) {
      radioM = Math.min(radioM, SUAVIZADO_RADIO_HORQUILLA_M);
    }
    if (tanMitad > 0) radioM = Math.min(radioM, (SUAVIZADO_FRACCION_LADO * minLadoM) / tanMitad);
    const tangenteM = radioM * tanMitad;
    if (!(radioM >= SUAVIZADO_RADIO_MIN_M) || !(tangenteM >= SUAVIZADO_TANGENTE_MIN_M)) {
      salida.push([lonB, latB]);
      continue;
    }
    // Direcciones unitarias B→A y B→C y bisectriz interior (centro del arco).
    const ax = -ux / ladoAM;
    const ay = -uy / ladoAM;
    const cx = vx / ladoBM;
    const cy = vy / ladoBM;
    let wx = ax + cx;
    let wy = ay + cy;
    const normaW = Math.hypot(wx, wy);
    if (!(normaW > 1e-9)) {
      // φ = 180° exacto: la bisectriz no está definida y no hay arco posible.
      salida.push([lonB, latB]);
      continue;
    }
    wx /= normaW;
    wy /= normaW;
    const centroM = radioM * secMitad;
    const ox = wx * centroM;
    const oy = wy * centroM;
    const t1x = ax * tangenteM;
    const t1y = ay * tangenteM;
    const t2x = cx * tangenteM;
    const t2y = cy * tangenteM;
    // Barrido con signo desde la tangencia T1 hasta T2 (= φ, con el sentido
    // del giro): interpolar el vector radial por ese ángulo da el arco.
    const r1x = t1x - ox;
    const r1y = t1y - oy;
    const r2x = t2x - ox;
    const r2y = t2y - oy;
    const barrido = Math.atan2(r1x * r2y - r1y * r2x, r1x * r2x + r1y * r2y);
    const puntos = Math.min(5, Math.max(2, Math.floor(anguloGrados / 30) + 2));
    const generados: [number, number][] = [];
    let finito = true;
    for (let k = 0; k < puntos; k += 1) {
      const avance = barrido * (k / (puntos - 1));
      const cosAvance = Math.cos(avance);
      const senAvance = Math.sin(avance);
      const gx = bx + ox + r1x * cosAvance - r1y * senAvance;
      const gy = by + oy + r1x * senAvance + r1y * cosAvance;
      if (!Number.isFinite(gx) || !Number.isFinite(gy)) {
        finito = false;
        break;
      }
      generados.push([gx, gy]);
    }
    // Regla dura: ninguna cuerda nueva puede cruzar el trazado original (sus
    // lados no cuentan: el arco nace tangente a ellos) ni las cuerdas ya
    // aceptadas. Si cruza, el vértice se conserva.
    let cruza = !finito || generados.length < 2;
    for (let k = 1; k < generados.length && !cruza; k += 1) {
      const p = generados[k - 1];
      const q = generados[k];
      const cercanos = candidatosCerca(rejillaOriginal, originalesLargos, p, q);
      if (!cercanos) {
        cruza = true;
        break;
      }
      for (const indice of cercanos) {
        if (indice === i - 1 || indice === i) continue;
        if (segmentosCruzan(p, q, xy[indice], xy[indice + 1])) {
          cruza = true;
          break;
        }
      }
      if (cruza) break;
      const cercanosGenerados = candidatosCerca(rejillaGenerada, generadosLargos, p, q);
      if (!cercanosGenerados) {
        cruza = true;
        break;
      }
      for (const indice of cercanosGenerados) {
        if (segmentosCruzan(p, q, cuerdasGeneradas[indice][0], cuerdasGeneradas[indice][1])) {
          cruza = true;
          break;
        }
      }
    }
    if (cruza) {
      salida.push([lonB, latB]);
      continue;
    }
    for (let k = 1; k < generados.length; k += 1) {
      const cuerdas = cuerdasGeneradas.length;
      cuerdasGeneradas.push([generados[k - 1], generados[k]]);
      insertar(rejillaGenerada, generadosLargos, generados[k - 1], generados[k], cuerdas);
    }
    for (const [gx, gy] of generados) {
      salida.push([
        refLon + gx / (SUAVIZADO_METROS_POR_GRADO * cosRef),
        refLat + gy / SUAVIZADO_METROS_POR_GRADO,
      ]);
    }
  }
  salida.push(coordenadas[coordenadas.length - 1]);
  return salida;
}

export function segmentosDeRecorrido(
  posiciones: Posicion[],
  huecos: Hueco[],
  reconstruidos: TramoReconstruido[],
): SegmentoRecorrido[] {
  const paresReconstruidos = new Map(reconstruidos.map((tramo) => [`${tramo.desde}|${tramo.hasta}`, tramo]));
  const paresHueco = new Set(
    huecos
      .filter((hueco) => !paresReconstruidos.has(`${hueco.desde}|${hueco.hasta}`))
      .map((hueco) => `${hueco.desde}|${hueco.hasta}`),
  );
  const segmentos: SegmentoRecorrido[] = [];
  // Ventanas ajustadas a vía que cubren varios fixes: su recta cruda interior
  // no se dibuja. La clasificación vehículo/caminata/quieto por velocidad
  // efectiva se calcula una vez para todo el recorrido para que el modo del
  // par confirme la caminata con el vecino (sostenida) y la racha detenida
  // marque el quieto.
  const ventanas = ventanasMatched(reconstruidos);
  const velocidades = velocidadesEfectivas(posiciones);
  const quietos = indicesQuietos(posiciones);
  for (const tramo of reconstruidos) {
    // El trazado ya viene saneado, pero se filtran pares no finitos por
    // defensa: un punto malo no debe tumbar el tramo denso completo.
    // Un método desconocido cae a estimado: punteado gris, nunca como GPS.
    const trazado = Array.isArray(tramo.trazado)
      ? tramo.trazado.filter(
          (par): par is [number, number] =>
            Array.isArray(par) && Number.isFinite(par[0]) && Number.isFinite(par[1]),
        )
      : [];
    if (trazado.length >= 2) {
      segmentos.push({
        tipo: tramo.metodo === 'MATCHED' ? 'matched' : 'estimated',
        // Solo presentación: el trazado ajustado a vía y el estimado se
        // redondean; el GPS registrado y los huecos rectos quedan crudos.
        coordenadas: suavizarTrazado(trazado.map(([lon, lat]) => [lon, lat] as [number, number])),
      });
    }
  }
  for (let i = 1; i < posiciones.length; i += 1) {
    const anterior = posiciones[i - 1];
    const actual = posiciones[i];
    // El tramo reconstruido ya dibuja este par: la recta quedaría encima del
    // trazado con otro estilo y se vería doble.
    if (paresReconstruidos.has(`${anterior.registradoEn}|${actual.registradoEn}`)) continue;
    // Ventana densa MATCHED: el trazado ajustado es la única línea de esa
    // parte y los fixes interiores no dibujan recta. Los pares que entran y
    // salen de la ventana sí se dibujan (sus extremos no quedan dentro): la
    // cruda conecta con el inicio y el fin del trazado ajustado.
    if (
      enVentanaMatched(ventanas, milisegundos(anterior.registradoEn)) ||
      enVentanaMatched(ventanas, milisegundos(actual.registradoEn))
    ) {
      continue;
    }
    const coordenadas: [number, number][] = [
      [anterior.longitud, anterior.latitud],
      [actual.longitud, actual.latitud],
    ];
    if (paresHueco.has(`${anterior.registradoEn}|${actual.registradoEn}`)) {
      segmentos.push({ tipo: 'hueco', coordenadas });
      continue;
    }
    segmentos.push({
      tipo: 'ruta',
      banda: bandaVelocidad(velocidadEfectivaKmh(actual, anterior)),
      modo: modoDePar(quietos, velocidades, i),
      coordenadas,
    });
  }
  return segmentos;
}

// Marcas de dirección espaciadas por distancia, no una por fix: la capa de
// dirección solo necesita leer el sentido de marcha cada ~150 m. Solo entra
// movimiento real (pares en vehículo o caminata) con rumbo geométrico fiable:
// el par que cierra la marca debe medir al menos 12 m, porque con menos el
// ruido del GPS inventa rumbos. El quieto no lleva marca (parado no hay rumbo
// y el fix ya se lee en el halo) y los bordes de hueco tampoco: el salto tras
// una pérdida de señal no es una dirección observada. Los tramos ajustados a
// vía (MATCHED) reparten sus marcas sobre el trazado con el rumbo entre puntos
// consecutivos y su color propio, nunca la banda de velocidad: el tramo
// reconstruido nunca se colorea como GPS registrado. Los tramos estimados
// (ESTIMATED) no llevan marcas: la línea punteada gris ya los distingue y las
// flechas los harían pasar por ruta normal.
//
// Un tramo real corto (menos de 2·separación) produce como mucho una marca por
// acumulación, y a menudo ninguna (salir del domicilio en dos fixes). Para que
// esos arranques también tengan lectura de rumbo sin acercar la densidad de un
// tramo largo, la marca suelta se reemplaza por una sola marca centrada en el
// punto medio del tramo, sobre el par fiable más próximo al centro. Solo se
// centra si el tramo supera el umbral de movimiento (la deriva del GPS junto a
// una parada tiene desplazamiento neto casi nulo). La marca centrada nunca
// invade un hueco (el tramo se parte en cada corte) y el descarte junto a una
// parada lo aplica Replay al filtrar la colección.
export const DISTANCIA_FLECHAS_M = 150;
export const RUMBO_FIABLE_MIN_M = 12;

// Par válido de un tramo real en curso: índices y extremos para poder medir el
// centro del tramo y emitir la marca centrada más adelante.
interface ParReal {
  indice: number;
  anterior: Posicion;
  actual: Posicion;
  metros: number;
}

export function flechasEspaciadas(
  posiciones: Posicion[],
  huecos: Hueco[],
  reconstruidos: TramoReconstruido[],
  cadaMetros = DISTANCIA_FLECHAS_M,
): FeatureCollection<Point> {
  const features: Feature<Point>[] = [];
  const paresHueco = new Set(huecos.map((hueco) => `${hueco.desde}|${hueco.hasta}`));
  const paresReconstruidos = new Set(reconstruidos.map((tramo) => `${tramo.desde}|${tramo.hasta}`));
  // Las marcas crudas dentro de una ventana MATCHED se omiten: la recta cruda
  // no está dibujada ahí y las marcas del trazado ajustado ya ocupan esa parte.
  const ventanas = ventanasMatched(reconstruidos);
  const velocidades = velocidadesEfectivas(posiciones);
  const quietos = indicesQuietos(posiciones);
  const marcaReal = (par: ParReal, longitud: number, latitud: number): Feature<Point> => ({
    type: 'Feature',
    properties: {
      bearing: rumboEntre(par.anterior, par.actual),
      banda: bandaVelocidad(velocidadEfectivaKmh(par.actual, par.anterior)),
      origen: 'real',
      indice: par.indice,
    },
    geometry: { type: 'Point', coordinates: [longitud, latitud] },
  });
  // Cierra el tramo real en curso: reparte las marcas normales por distancia;
  // si el tramo es corto (< 2·separación) y el par fiable más cercano al centro
  // existe, lo reemplaza por una única marca en el punto medio del par.
  const cerrarTramo = (pares: ParReal[], metrosTramo: number) => {
    if (pares.length === 0) return;
    const normales: Feature<Point>[] = [];
    let acumuladoM = 0;
    for (const par of pares) {
      acumuladoM += par.metros;
      if (acumuladoM >= cadaMetros && par.metros >= RUMBO_FIABLE_MIN_M) {
        acumuladoM = 0;
        normales.push(marcaReal(par, par.actual.longitud, par.actual.latitud));
      }
    }
    if (metrosTramo >= 2 * cadaMetros) {
      features.push(...normales);
      return;
    }
    // Tramo corto y lento: la deriva del GPS junto a una parada larga junta
    // fixes que superan el umbral por pares pero no desplazan el equipo. La
    // velocidad efectiva del tramo (desplazamiento neto entre extremos sobre
    // el tiempo observado) los descarta con el mismo umbral de detención; las
    // marcas normales se conservan para no cambiar la densidad previa.
    const primera = pares[0].anterior;
    const ultima = pares[pares.length - 1].actual;
    const duracionHoras =
      (milisegundos(ultima.registradoEn) - milisegundos(primera.registradoEn)) / 3600000;
    const velocidadTramoKmh = duracionHoras > 0 ? distanciaKm(primera, ultima) / duracionHoras : 0;
    if (velocidadTramoKmh < VELOCIDAD_DETENCION_KMH) {
      features.push(...normales);
      return;
    }
    // Par fiable cuyo centro cae más cerca del centro del tramo.
    const centroTramoM = metrosTramo / 2;
    let inicioParM = 0;
    let elegido: ParReal | null = null;
    let distanciaCentro = Infinity;
    for (const par of pares) {
      if (par.metros >= RUMBO_FIABLE_MIN_M) {
        const centroParM = inicioParM + par.metros / 2;
        const distancia = Math.abs(centroParM - centroTramoM);
        if (distancia < distanciaCentro) {
          distanciaCentro = distancia;
          elegido = par;
        }
      }
      inicioParM += par.metros;
    }
    if (!elegido) {
      features.push(...normales);
      return;
    }
    const longitud = (elegido.anterior.longitud + elegido.actual.longitud) / 2;
    const latitud = (elegido.anterior.latitud + elegido.actual.latitud) / 2;
    const marca = marcaReal(elegido, longitud, latitud);
    features.push({ ...marca, properties: { ...marca.properties, puntoMedio: true } });
  };
  let paresTramo: ParReal[] = [];
  let metrosTramo = 0;
  for (let i = 1; i < posiciones.length; i += 1) {
    const anterior = posiciones[i - 1];
    const actual = posiciones[i];
    if (
      !Number.isFinite(anterior.latitud) ||
      !Number.isFinite(anterior.longitud) ||
      !Number.isFinite(actual.latitud) ||
      !Number.isFinite(actual.longitud)
    ) {
      cerrarTramo(paresTramo, metrosTramo);
      paresTramo = [];
      metrosTramo = 0;
      continue;
    }
    const clave = `${anterior.registradoEn}|${actual.registradoEn}`;
    if (paresHueco.has(clave) || paresReconstruidos.has(clave)) {
      cerrarTramo(paresTramo, metrosTramo);
      paresTramo = [];
      metrosTramo = 0;
      continue;
    }
    if (
      enVentanaMatched(ventanas, milisegundos(anterior.registradoEn)) ||
      enVentanaMatched(ventanas, milisegundos(actual.registradoEn))
    ) {
      cerrarTramo(paresTramo, metrosTramo);
      paresTramo = [];
      metrosTramo = 0;
      continue;
    }
    if (modoDePar(quietos, velocidades, i) === 'quieto') {
      cerrarTramo(paresTramo, metrosTramo);
      paresTramo = [];
      metrosTramo = 0;
      continue;
    }
    const tramoM = distanciaKm(anterior, actual) * 1000;
    if (!(tramoM > 0)) continue;
    paresTramo.push({ indice: i, anterior, actual, metros: tramoM });
    metrosTramo += tramoM;
  }
  cerrarTramo(paresTramo, metrosTramo);
  let indiceTrazado = posiciones.length;
  for (const tramo of reconstruidos) {
    if (tramo.metodo !== 'MATCHED') continue;
    const trazado = Array.isArray(tramo.trazado)
      ? tramo.trazado.filter(
          (par): par is [number, number] =>
            Array.isArray(par) && Number.isFinite(par[0]) && Number.isFinite(par[1]),
        )
      : [];
    if (trazado.length < 2) continue;
    let acumuladoTrazadoM = 0;
    for (let i = 1; i < trazado.length; i += 1) {
      const [lonA, latA] = trazado[i - 1];
      const [lonB, latB] = trazado[i];
      const tramoM =
        distanciaKm({ latitud: latA, longitud: lonA }, { latitud: latB, longitud: lonB }) * 1000;
      if (!(tramoM > 0)) continue;
      acumuladoTrazadoM += tramoM;
      if (acumuladoTrazadoM >= cadaMetros && tramoM >= RUMBO_FIABLE_MIN_M) {
        acumuladoTrazadoM = 0;
        features.push({
          type: 'Feature',
          properties: {
            bearing: rumboEntrePuntos(latA, lonA, latB, lonB),
            banda: 0,
            origen: 'matched',
            indice: indiceTrazado,
          },
          geometry: { type: 'Point', coordinates: [lonB, latB] },
        });
        indiceTrazado += 1;
      }
    }
  }
  return { type: 'FeatureCollection', features };
}

// Nube de dispersión parada: un punto por fix quieto (racha detenida) para
// dibujar la deriva real como halo sutil en vez de unirla con líneas. Los
// fixes en movimiento no entran: su evidencia ya es la línea del corredor.
export function puntosQuietos(posiciones: Posicion[]): FeatureCollection<Point> {
  const quietos = indicesQuietos(posiciones);
  const features: Feature<Point>[] = [];
  for (const indice of quietos) {
    const posicion = posiciones[indice];
    if (!posicion || !Number.isFinite(posicion.latitud) || !Number.isFinite(posicion.longitud)) continue;
    features.push({
      type: 'Feature',
      properties: { indice },
      geometry: { type: 'Point', coordinates: [posicion.longitud, posicion.latitud] },
    });
  }
  return { type: 'FeatureCollection', features };
}

export function aColeccionHalos(halos: HaloParada[]): FeatureCollection<Point> {
  return {
    type: 'FeatureCollection',
    features: halos.map((halo) => ({
      type: 'Feature',
      properties: { indice: halo.indice, radioM: halo.radioM },
      geometry: { type: 'Point', coordinates: [halo.longitud, halo.latitud] },
    })),
  };
}

// Paso de decimación de marcas de dirección según el zoom: la base ya viene
// espaciada por distancia, esto solo adelgaza al alejar para que la traza no se
// sature. 0 deja la capa sin marcas. Los escalones son finos para que la
// densidad crezca de a poco al acercar (el tamaño del icono también sube con el
// zoom) y el cambio de nivel no dé un salto brusco.
export function pasoFlechas(zoom: number): number {
  if (zoom < 9) return 0;
  if (zoom < 10) return 32;
  if (zoom < 11) return 20;
  if (zoom < 12) return 12;
  if (zoom < 13) return 8;
  if (zoom < 14) return 5;
  if (zoom < 15) return 3;
  if (zoom < 16) return 2;
  return 1;
}

// Decima la colección de puntos de la capa de flechas con el paso que toca
// para el zoom. `indice` (el número de fix original) es la referencia estable
// para decimar: los puntos ya pueden venir muestreados de dos en dos en
// recorridos largos. El primer y el último fix se conservan siempre porque son
// la evidencia de los extremos del recorrido. La marca centrada de un tramo
// corto es la única de su tramo: no se adelgaza, porque decimar por `indice`
// la borraría en la mitad de los zooms y el arranque del recorrido quedaría sin
// sentido de marcha.
export function flechasPorZoom(puntos: FeatureCollection<Point>, zoom: number): FeatureCollection<Point> {
  const paso = pasoFlechas(zoom);
  if (paso === 0) return { type: 'FeatureCollection', features: [] };
  if (paso === 1) return puntos;
  const features = puntos.features.filter((punto) => {
    if (punto.properties?.puntoMedio === true) return true;
    const indice = punto.properties?.indice;
    return typeof indice !== 'number' || indice % paso === 0;
  });
  const ultimo = puntos.features[puntos.features.length - 1];
  if (ultimo && !features.includes(ultimo)) features.push(ultimo);
  return { type: 'FeatureCollection', features };
}

// Deriva las paradas del recorrido ya cargado, sin consultar al API. Es el
// respaldo de Replay cuando /reports/stops falla. Una racha es una seguidilla
// de fixes con velocidad efectiva bajo el umbral. Dos reglas nacidas de datos
// reales:
//  - Un fix sin velocidad usa la velocidad implícita contra el anterior, para
//    no perder paradas de equipos que no la reportan.
//  - Una racha que contenga un hueco se descarta completa. En la ventana
//    ayer→hoy un equipo con fixes lentos separados por huecos de 10-21 min
//    producía una "detención" de 1 h 54 min cuando era pérdida de señal: con
//    el hueco dentro no se puede atribuir ese tiempo a la parada, ni siquiera
//    a los tramos que la rodean, porque el movimiento durante el hueco es
//    desconocido. La duración se mide entre el primer y el último fix lento:
//    es tiempo observado, nunca extrapolado.
export function detencionesDeRecorrido(posiciones: Posicion[], huecos: Hueco[]): Parada[] {
  const paresHueco = new Set(huecos.map((h) => `${h.desde}|${h.hasta}`));
  const detenciones: Parada[] = [];
  let primera: Posicion | null = null;
  let ultima: Posicion | null = null;
  let contaminada = false;

  const cerrarRacha = () => {
    if (primera && ultima && !contaminada) {
      const duracionMin = (milisegundos(ultima.registradoEn) - milisegundos(primera.registradoEn)) / 60000;
      if (duracionMin >= DURACION_DETENCION_MIN) {
        detenciones.push({
          inicio: primera.registradoEn,
          fin: ultima.registradoEn,
          duracionMin,
          // El marcador se ancla al primer fix detenido: es el punto donde el
          // equipo se detuvo. El promedio de la racha podría caer en otra calle
          // si las lecturas derivan durante la parada.
          latitud: primera.latitud,
          longitud: primera.longitud,
          direccion: null,
        });
      }
    }
    primera = null;
    ultima = null;
    contaminada = false;
  };

  for (let i = 0; i < posiciones.length; i += 1) {
    const posicion = posiciones[i];
    const anterior = i > 0 ? posiciones[i - 1] : null;
    // El hueco contamina la racha en curso, no la que arranca después: una
    // parada nueva tras la pérdida de señal sigue siendo observable. Si el par
    // con hueco no pertenece a ninguna racha lenta, no hay nada que descartar.
    if (anterior && primera && paresHueco.has(`${anterior.registradoEn}|${posicion.registradoEn}`)) contaminada = true;
    const velocidad = velocidadEfectivaKmh(posicion, anterior);
    const detenido = velocidad != null && Number.isFinite(velocidad) && velocidad < VELOCIDAD_DETENCION_KMH;
    if (!detenido) {
      cerrarRacha();
      continue;
    }
    if (!primera) primera = posicion;
    ultima = posicion;
  }
  cerrarRacha();
  return detenciones;
}

// Velocidad derivada del punto seleccionado: distancia/tiempo entre el fix
// anterior y el siguiente. Con ambos extremos el cálculo queda centrado en el
// punto y filtra mejor el ruido del GPS; en los extremos del recorrido solo
// existe un segmento posible.
export function velocidadDerivadaKmh(posiciones: Posicion[], indice: number): number | null {
  const actual = posiciones[indice];
  if (!actual) return null;
  const anterior = indice > 0 ? posiciones[indice - 1] : null;
  const siguiente = indice < posiciones.length - 1 ? posiciones[indice + 1] : null;
  const desde = anterior ?? actual;
  const hasta = siguiente ?? actual;
  if (desde === hasta) return null;
  const horas = (milisegundos(hasta.registradoEn) - milisegundos(desde.registradoEn)) / 3600000;
  if (!(horas > 0)) return null;
  return distanciaKm(desde, hasta) / horas;
}

// Serie de batería alineada índice a índice con las posiciones cargadas:
// null donde el fix no trae porcentaje, para que Chart.js dibuje el hueco sin
// inventar valores. El gráfico decide con spanGaps si lo une o no.
export function serieBateria(posiciones: Posicion[]): (number | null)[] {
  return posiciones.map((posicion) =>
    posicion.bateriaPct == null || !Number.isFinite(posicion.bateriaPct) ? null : posicion.bateriaPct,
  );
}

// Índice del último valor de batería conocido hasta el índice pedido. El punto
// del reproductor se ancla ahí y no en el índice actual para que no se despegue
// de la línea cuando un fix intermedio no trae porcentaje; -1 si no hay dato.
export function indiceBateriaConocida(serie: (number | null)[], indice: number): number {
  for (let i = Math.min(indice, serie.length - 1); i >= 0; i -= 1) {
    if (serie[i] != null) return i;
  }
  return -1;
}

export function aColeccion(segmentos: SegmentoRecorrido[]): FeatureCollection<LineString> {
  return {
    type: 'FeatureCollection',
    features: segmentos.map((segmento) => ({
      type: 'Feature',
      properties: {
        tipo: segmento.tipo,
        ...(segmento.banda == null ? {} : { banda: segmento.banda }),
        ...(segmento.modo == null ? {} : { modo: segmento.modo }),
      },
      geometry: { type: 'LineString', coordinates: segmento.coordenadas },
    })),
  };
}

// Búsqueda binaria: devuelve el índice del último fix cuyo instante no supera
// al pedido. Se usa en la reproducción para ubicarse en la línea de tiempo sin
// recorrer las posiciones en cada tick.
export function indicePorInstante(posiciones: Posicion[], instante: number): number {
  let bajo = 0;
  let alto = posiciones.length - 1;
  while (bajo < alto) {
    const medio = Math.ceil((bajo + alto) / 2);
    if (milisegundos(posiciones[medio].registradoEn) <= instante) bajo = medio;
    else alto = medio - 1;
  }
  return bajo;
}

// Radio de acierto de la capa de línea: un clic más lejos del fix más próximo
// es un clic sobre el mapa, no sobre la ruta. Con 80 m el filtro era más
// estrecho que la propia capa pulsable (18 px ≈ 200 m a zoom lejano) y pulsar
// la ruta exigía puntería de pocos píxeles; 250 m cubre toda la capa sin
// seleccionar clics fuera de ella.
const RADIO_SELECCION_M = 250;

// Índice del fix más cercano al punto pulsado sobre la ruta, por distancia
// haversine. La superficie de acierto es la ruta engrosada y el clic cae entre
// dos fixes, así que se elige el más próximo; sin ningún fix a menos de 80 m se
// devuelve null para no seleccionar desde un clic fuera de la ruta.
export function indiceMasCercano(posiciones: Posicion[], lng: number, lat: number): number | null {
  let mejorIndice: number | null = null;
  let mejorDistanciaKm = Infinity;
  for (let i = 0; i < posiciones.length; i += 1) {
    const posicion = posiciones[i];
    if (!Number.isFinite(posicion.latitud) || !Number.isFinite(posicion.longitud)) continue;
    const distancia = distanciaKm({ latitud: lat, longitud: lng }, posicion);
    if (distancia < mejorDistanciaKm) {
      mejorDistanciaKm = distancia;
      mejorIndice = i;
    }
  }
  if (mejorIndice == null || mejorDistanciaKm * 1000 > RADIO_SELECCION_M) return null;
  return mejorIndice;
}

// Índice del fix más cercano en el tiempo a un instante, siempre que el
// instante caiga dentro del tramo cargado. Los marcadores de jornada llegan
// solo con hora, así que se anclan al fix más próximo; fuera de
// [primer fix, último fix] no hay coordenada fiable y se devuelve null para no
// pintar un punto que no pertenece a la ventana visible.
export function indiceCercaDeInstante(posiciones: Posicion[], instante: number): number | null {
  if (posiciones.length === 0) return null;
  const inicio = milisegundos(posiciones[0].registradoEn);
  const fin = milisegundos(posiciones[posiciones.length - 1].registradoEn);
  if (instante < inicio || instante > fin) return null;
  const indice = indicePorInstante(posiciones, instante);
  const anterior = posiciones[indice];
  const siguiente = posiciones[indice + 1];
  if (!siguiente) return indice;
  const distanciaAnterior = instante - milisegundos(anterior.registradoEn);
  const distanciaSiguiente = milisegundos(siguiente.registradoEn) - instante;
  return distanciaSiguiente < distanciaAnterior ? indice + 1 : indice;
}

// Posición del marcador para un instante cualquiera: interpola linealmente
// entre el fix anterior y el siguiente para que la reproducción se vea fluida
// aunque los fixes lleguen espaciados. En los tramos sin evidencia (hueco
// formal, salto de más de 5 min o par cubierto por un tramo reconstruido)
// devuelve el último fix conocido: una interpolación recta inventaría un
// desplazamiento entre dos lecturas.
export function puntoEnInstante(
  posiciones: Posicion[],
  huecos: Hueco[],
  instante: number,
  reconstruidos: TramoReconstruido[] = [],
): { latitud: number; longitud: number } | null {
  const indice = indicePorInstante(posiciones, instante);
  const actual = posiciones[indice];
  if (!actual) return null;
  const siguiente = posiciones[indice + 1] ?? null;
  if (!siguiente) return { latitud: actual.latitud, longitud: actual.longitud };
  const desde = milisegundos(actual.registradoEn);
  const hasta = milisegundos(siguiente.registradoEn);
  const esHueco = huecos.some((hueco) => hueco.desde === actual.registradoEn && hueco.hasta === siguiente.registradoEn);
  const esReconstruido = reconstruidos.some(
    (tramo) => tramo.desde === actual.registradoEn && tramo.hasta === siguiente.registradoEn,
  );
  if (esHueco || esReconstruido || hasta - desde > ANTIGUEDAD_SIN_SENAL_MS || !(hasta > desde)) {
    return { latitud: actual.latitud, longitud: actual.longitud };
  }
  const fraccion = Math.min(Math.max((instante - desde) / (hasta - desde), 0), 1);
  return {
    latitud: actual.latitud + (siguiente.latitud - actual.latitud) * fraccion,
    longitud: actual.longitud + (siguiente.longitud - actual.longitud) * fraccion,
  };
}

// Estado del fix actual para el color del marcador. Reglas, en orden:
//  1. El fix cae dentro de un hueco (aunque el servidor no lo derive de un par
//     exacto de posiciones): sin señal.
//  2. El salto al fix siguiente supera los 5 min: el equipo dejó de reportar
//     después de este fix, así que ya se lee como sin señal.
//  3. Es el último fix cargado y su antigüedad contra el reloj real supera los
//     5 min: el equipo no volvió a reportar.
//  4. Velocidad efectiva bajo el umbral de detención: detenido; si no, en
//     movimiento.
export function estadoDePunto(
  posiciones: Posicion[],
  huecos: Hueco[],
  indice: number,
  ahora = Date.now(),
): EstadoUnidad {
  const posicion = posiciones[indice];
  if (!posicion) return 'sinSenal';
  const instante = milisegundos(posicion.registradoEn);
  if (huecos.some((hueco) => instante > milisegundos(hueco.desde) && instante < milisegundos(hueco.hasta))) {
    return 'sinSenal';
  }
  const siguiente = posiciones[indice + 1] ?? null;
  if (siguiente) {
    if (milisegundos(siguiente.registradoEn) - instante > ANTIGUEDAD_SIN_SENAL_MS) return 'sinSenal';
  } else if (ahora - instante > ANTIGUEDAD_SIN_SENAL_MS) {
    return 'sinSenal';
  }
  const anterior = indice > 0 ? posiciones[indice - 1] : null;
  const velocidad = velocidadEfectivaKmh(posicion, anterior);
  if (velocidad != null && Number.isFinite(velocidad) && velocidad < VELOCIDAD_DETENCION_KMH) return 'detencion';
  return 'movimiento';
}
