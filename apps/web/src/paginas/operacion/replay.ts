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
}

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
// El servidor corta los huecos a los 10 min; para el marcador una lectura que
// lleva más de 5 min sin el fix siguiente ya se considera sin señal, incluso
// aunque no llegue a ser un hueco formal.
export const ANTIGUEDAD_SIN_SENAL_MS = 5 * 60 * 1000;

export function milisegundos(iso: string): number {
  return new Date(iso).getTime();
}

// Hora en 24 h para la lectura del reproductor y las etiquetas del mapa:
// util/formato.hora añade "a. m./p. m." y alarga la franja compacta y los
// rótulos. La ficha del punto conserva fechaHora, que sí necesita el día.
const HORA_CORTA = new Intl.DateTimeFormat('es-EC', { hour: '2-digit', minute: '2-digit', hour12: false });

export function horaCorta(valor?: string | null): string {
  return valor ? HORA_CORTA.format(new Date(valor)) : GUION;
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

function rumboDePosicion(posicion: Posicion, anterior: Posicion | null): number {
  const rumbo = posicion.rumboGrados;
  if (rumbo != null && Number.isFinite(rumbo) && rumbo > 0) return rumbo;
  if (!anterior) return 0;
  return rumboEntre(anterior, posicion);
}

// El API define cada hueco con los dos fixes que lo rodean, así que basta con
// marcar exactamente esos pares para partir la línea. Ahora cada par de fixes
// es una Feature propia: el coloreado por velocidad necesita que cada tramo
// lleve su banda, y una fuente GeoJSON estática de miles de líneas de dos
// puntos se publica de una sola vez al cargar el recorrido.
// Los tramos reconstruidos entran con su método (MATCHED o ESTIMATED) y su
// estilo propio; los pares que cubren ya no dibujan su recta. Los tramos
// densos ajustados a vía cubren una ventana de varios fixes (sus extremos no
// son adyacentes): la cruda interior se conserva debajo y el trazado ajustado
// se dibuja encima en su capa propia, así que ambas quedan visibles.
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
        coordenadas: trazado.map(([lon, lat]) => [lon, lat] as [number, number]),
      });
    }
  }
  for (let i = 1; i < posiciones.length; i += 1) {
    const anterior = posiciones[i - 1];
    const actual = posiciones[i];
    // El tramo reconstruido ya dibuja este par: la recta quedaría encima del
    // trazado con otro estilo y se vería doble.
    if (paresReconstruidos.has(`${anterior.registradoEn}|${actual.registradoEn}`)) continue;
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
      coordenadas,
    });
  }
  return segmentos;
}

// Un punto por fix para la capa de chevrones y la capa de puntos. Se omiten
// coordenadas no finitas y, sobre recorridos largos (>3000 fixes), se toma uno
// de cada dos: un punto por segundo no aporta nada visual y evita miles de
// símbolos solapados. El último fix siempre se conserva.
export function puntosDeRecorrido(
  posiciones: Posicion[],
  huecos: Hueco[],
  reconstruidos: TramoReconstruido[],
): FeatureCollection<Point> {
  const paresHueco = new Set(huecos.map((hueco) => `${hueco.desde}|${hueco.hasta}`));
  const features: Feature<Point>[] = [];
  const paso = posiciones.length > 3000 ? 2 : 1;
  const aPunto = (indice: number): Feature<Point> | null => {
    const posicion = posiciones[indice];
    if (!posicion || !Number.isFinite(posicion.latitud) || !Number.isFinite(posicion.longitud)) return null;
    const anterior = indice > 0 ? posiciones[indice - 1] : null;
    const siguiente = indice < posiciones.length - 1 ? posiciones[indice + 1] : null;
    // `indice` viaja en propiedades para que la capa de puntos sea clicable
    // aunque el muestreo reduzca los puntos dibujados. `hueco` pinta en gris
    // los fixes que bordean una pérdida de señal: el último antes del corte y
    // el primero al recuperarla.
    const enHueco =
      (anterior != null && paresHueco.has(`${anterior.registradoEn}|${posicion.registradoEn}`)) ||
      (siguiente != null && paresHueco.has(`${posicion.registradoEn}|${siguiente.registradoEn}`));
    return {
      type: 'Feature',
      properties: {
        bearing: rumboDePosicion(posicion, anterior),
        banda: bandaVelocidad(velocidadEfectivaKmh(posicion, anterior)),
        // Origen del punto para la capa de chevrones: los reales se colorean
        // por banda de velocidad; los ajustados a vía usan su imagen propia.
        origen: 'real',
        indice,
        hueco: enHueco,
      },
      geometry: { type: 'Point', coordinates: [posicion.longitud, posicion.latitud] },
    };
  };
  let ultimo = -1;
  for (let i = 0; i < posiciones.length; i += paso) {
    const punto = aPunto(i);
    if (!punto) continue;
    features.push(punto);
    ultimo = i;
  }
  if (ultimo !== posiciones.length - 1) {
    const punto = aPunto(posiciones.length - 1);
    if (punto) features.push(punto);
  }
  // Chevrones de los tramos ajustados a vía (MATCHED): se reparten a lo largo
  // del trazado con el rumbo entre puntos consecutivos, como si fueran fixes.
  // Se acotan a ~24 por tramo y usan la imagen propia del método, no la banda
  // de velocidad: el tramo reconstruido nunca se colorea como GPS registrado.
  // Los tramos densos usan el mismo reparto; su trazado corto añade pocos
  // puntos. Los tramos estimados (ESTIMATED) no llevan chevrones: la línea
  // punteada gris ya los distingue y las flechas los harían pasar por normal.
  let indiceTrazado = posiciones.length;
  for (const tramo of reconstruidos) {
    if (tramo.metodo !== 'MATCHED') continue;
    const trazado = tramo.trazado;
    if (!trazado || trazado.length < 2) continue;
    const salto = Math.max(1, Math.ceil(trazado.length / 24));
    for (let i = 0; i < trazado.length; i += salto) {
      const punto = trazado[i];
      const puntoSiguiente = trazado[Math.min(i + 1, trazado.length - 1)];
      if (!Array.isArray(punto) || !Array.isArray(puntoSiguiente)) continue;
      const [lon, lat] = punto;
      const [lonSiguiente, latSiguiente] = puntoSiguiente;
      if (![lon, lat, lonSiguiente, latSiguiente].every(Number.isFinite)) continue;
      features.push({
        type: 'Feature',
        properties: {
          bearing: rumboEntrePuntos(lat, lon, latSiguiente, lonSiguiente),
          banda: 0,
          origen: 'matched',
          indice: indiceTrazado,
          hueco: false,
        },
        geometry: { type: 'Point', coordinates: [lon, lat] },
      });
      indiceTrazado += 1;
    }
  }
  return { type: 'FeatureCollection', features };
}

// Paso de decimación de chevrones según el zoom: alejado, cientos de flechas
// pegadas ensucian la traza y no se distinguen; acercado, hacen falta para leer
// el sentido de cada tramo. 0 deja la capa sin flechas. Los escalones son finos
// para que la densidad crezca de a poco al acercar (el tamaño del icono también
// sube con el zoom) y el cambio de nivel no dé un salto brusco.
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
// la evidencia de los extremos del recorrido.
export function flechasPorZoom(puntos: FeatureCollection<Point>, zoom: number): FeatureCollection<Point> {
  const paso = pasoFlechas(zoom);
  if (paso === 0) return { type: 'FeatureCollection', features: [] };
  if (paso === 1) return puntos;
  const features = puntos.features.filter((punto) => {
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
      properties: segmento.banda == null ? { tipo: segmento.tipo } : { tipo: segmento.tipo, banda: segmento.banda },
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
