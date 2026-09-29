import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { LngLatBounds, Marker } from 'maplibre-gl';
import type { GeoJSONSource, Map as TipoMapa } from 'maplibre-gl';
import Icono from '../componentes/Icono';
import MapaRaster from './operacion/MapaRaster';
import ReproductorReplay, {
  InsigniasParadas,
  LineaTiempoReplay,
  ListaParadas,
  PanelPuntoSeleccionado,
} from './operacion/ReproductorReplay';
import FiltroReplay from './operacion/FiltroReplay';
import { traerFlota, traerJornadas, traerParadas, traerReplay, CACHE_AUDITORIA_MS, CLAVE_FLOTA, equiposHabilitados } from './operacion/datos';
import { esNoEncontrado, mensajeError } from './operacion/errores';
import {
  aColeccion,
  aColeccionHalos,
  detencionesDeRecorrido,
  fechaAyerLocal,
  flechasEspaciadas,
  flechasPorZoom,
  halosDeParadas,
  horaCorta,
  indiceCercaDeInstante,
  milisegundos,
  normalizarReconstruidos,
  puntosQuietos,
  segmentosDeRecorrido,
} from './operacion/replay';
import type { Parada, TramoReconstruido } from './operacion/replay';
import { fechaHoyLocal, finDeDia, inicioDeDia } from './operacion/rango';
import './operacion.css';

// Nombre de archivo sin caracteres problemáticos para el sistema de archivos.
// Historial tiene su propio helper y este cambio no lo toca; se replica el
// criterio para que ambas descargas generen nombres equivalentes.
function nombreArchivo(id: string): string {
  return id.replace(/[^\w.-]+/g, '_');
}

// Lienzo nativo de la flecha: 64 px registrados con pixelRatio 2 (32 px
// lógicos). Se dibuja a resolución nativa y se muestra reducido por icon-size,
// así que la punta conserva el filo en pantallas densas.
const LADO_FLECHA = 64;
const PIXEL_RATIO_FLECHA = 2;
// Núcleo claro con filo del color del tramo: el blanco hace legible la marca
// sobre teselas claras y satélite; el filo mantiene la identidad de la capa.
const NUCLEO_FLECHA = '#ffffff';
// Navy profundo del tramo ajustado a vía: nunca comparte la paleta de velocidad
// del GPS registrado (ADR-007) ni el gris punteado del estimado. El casing es
// el mismo navy un paso más claro y translúcido, para asentar la línea sobre
// teselas claras sin perderla en la imagen de satélite.
const COLOR_MATCHED = '#0b2545';
const COLOR_MATCHED_CASING = '#123a5e';
const ID_FLECHA_MATCHED = 'dir-matched';
// Paleta de velocidad sobria (verdes bosque, ocre, teja y rojo apagados). El
// orden coincide con la banda 0..4 que calcula replay.ts a partir de la
// velocidad y con los ids de imagen que referencia la capa symbol.
const COLORES_BANDA = ['#2f7d5f', '#5f8f66', '#a8893a', '#a86a35', '#9c4238'];
const IDS_FLECHA = COLORES_BANDA.map((_, banda) => `dir-${banda}`);
// Separación de las marcas de dirección en ciudad: 120 m dan una lectura de
// rumbo por cuadra sin saturar la traza (replay.ts la recibe por parámetro; su
// valor por defecto de 150 m queda intacto para otros consumidores).
const SEPARACION_FLECHAS_M = 120;
// Descarte de marcas ajustadas sobre una parada: el trazado reconstruido puede
// cruzar el punto donde el equipo estuvo detenido y una flecha encima de la
// insignia fingiría movimiento en la parada. Los huecos ya no generan marcas.
const RADIO_PARADA_FLECHA_M = 45;

// Distancia plana en metros, suficiente para el descarte local junto a una
// parada (decenas de metros): a esta escala el error frente a la esfera es
// despreciable.
function distanciaAproxM(latA: number, lonA: number, latB: number, lonB: number): number {
  const dLat = (latB - latA) * 111320;
  const dLon = (lonB - lonA) * 111320 * Math.cos(((latA + latB) / 2) * (Math.PI / 180));
  return Math.hypot(dLat, dLon);
}

// Flecha de navegación plana: punta viva y base escotada, nunca un rombo.
// Núcleo blanco y filo del color del tramo, dibujada en canvas y registrada
// como imagen del mapa. Apunta hacia arriba porque MapLibre parte de esa
// dirección al rotar por rumbo, y se centra para que el ancla (centro) caiga
// en la línea. El filo se traza antes del relleno: la mitad interior del trazo
// queda cubierta y solo asoma el contorno.
function imagenDireccion(borde: string): ImageData | null {
  const lienzo = document.createElement('canvas');
  lienzo.width = LADO_FLECHA;
  lienzo.height = LADO_FLECHA;
  const contexto = lienzo.getContext('2d');
  // Sin contexto 2D no hay imagen; la capa de dirección se omite y queda el
  // corredor coloreado por velocidad.
  if (!contexto) return null;
  const escala = LADO_FLECHA / 64;
  contexto.beginPath();
  contexto.moveTo(32 * escala, 5 * escala);
  contexto.lineTo(56 * escala, 56 * escala);
  contexto.lineTo(32 * escala, 45 * escala);
  contexto.lineTo(8 * escala, 56 * escala);
  contexto.closePath();
  // Miter: la punta superior queda en ángulo vivo, sin redondear.
  contexto.lineJoin = 'miter';
  contexto.lineWidth = 7 * escala;
  contexto.strokeStyle = borde;
  contexto.stroke();
  contexto.fillStyle = NUCLEO_FLECHA;
  contexto.fill();
  return contexto.getImageData(0, 0, LADO_FLECHA, LADO_FLECHA);
}

// Marcador de extremo con etiqueta flotante ("Inicio 08:12"): el punto queda
// anclado a la coordenada y la etiqueta cuelga a la derecha sin desplazar el
// ancla, que maplibre calcula sobre el elemento completo.
function marcadorExtremo(mapa: TipoMapa, clase: string, texto: string, latitud: number, longitud: number): Marker {
  const elemento = document.createElement('div');
  elemento.className = `marcador-extremo ${clase}`;
  const punto = document.createElement('span');
  punto.className = 'extremo-punto';
  const etiqueta = document.createElement('span');
  etiqueta.className = 'extremo-etiqueta';
  etiqueta.textContent = texto;
  elemento.append(punto, etiqueta);
  return new Marker({ element: elemento, anchor: 'center' }).setLngLat([longitud, latitud]).addTo(mapa);
}

export default function Replay() {
  const [parametros] = useSearchParams();
  const [dispositivoId, setDispositivoId] = useState(parametros.get('dispositivo') ?? '');
  const [desde, setDesde] = useState(parametros.get('desde') ?? fechaAyerLocal());
  const [hasta, setHasta] = useState(parametros.get('hasta') ?? fechaHoyLocal());
  const [mapa, setMapa] = useState<TipoMapa | null>(null);
  const [panelRecogido, setPanelRecogido] = useState(false);

  const flota = useQuery({ queryKey: CLAVE_FLOTA, queryFn: () => traerFlota() });
  // Solo la flota habilitada alimenta el selector y las consultas: un equipo
  // dado de baja no aparece como opción ni llega a pedir replay, paradas o
  // jornadas, aunque la caché conserve la respuesta anterior.
  const equipos = useMemo(() => equiposHabilitados(flota.data?.datos ?? []), [flota.data]);
  // La selección es válida solo si apunta a un equipo de la flota habilitada.
  // Una URL o un estado previo hacia un equipo deshabilitado se resuelve a la
  // primera unidad disponible sin disparar consultas del equipo dado de baja.
  const seleccionado = useMemo(
    () =>
      equipos.some((equipo) => equipo.idPublico === dispositivoId)
        ? dispositivoId
        : equipos[0]?.idPublico ?? '',
    [dispositivoId, equipos],
  );
  const rangoValido = desde !== '' && hasta !== '' && desde <= hasta;

  // Sincroniza el estado con la selección efectiva cuando la flota ya cargó y
  // la selección guardada dejó de ser válida (equipo dado de baja, id
  // desconocido o lista vacía): el selector nunca queda apuntando a un equipo
  // que no está entre las opciones.
  useEffect(() => {
    if (!flota.data || dispositivoId === seleccionado) return;
    setDispositivoId(seleccionado);
  }, [flota.data, dispositivoId, seleccionado]);

  const replay = useQuery({
    queryKey: ['replay', seleccionado, desde, hasta],
    queryFn: () => traerReplay(seleccionado, inicioDeDia(desde), finDeDia(hasta)),
    enabled: seleccionado !== '' && rangoValido,
    staleTime: CACHE_AUDITORIA_MS,
  });

  // Paradas del servidor en paralelo al recorrido. Si la consulta falla, el
  // panel cae al helper local y lo advierte; mientras carga se muestra el
  // respaldo sin aviso para no parpadear.
  const paradasConsulta = useQuery({
    queryKey: ['paradas', seleccionado, desde, hasta],
    queryFn: () => traerParadas(seleccionado, inicioDeDia(desde), finDeDia(hasta)),
    enabled: seleccionado !== '' && rangoValido,
    staleTime: CACHE_AUDITORIA_MS,
  });

  // Jornadas del equipo en la ventana: alimentan los marcadores de
  // inicio/fin de jornada sobre el mapa. El endpoint es nuevo y puede no
  // existir todavía; 404 o error se leen como "sin dato" (—) sin ruido para
  // el operador, por eso no se consulta isError y se desactivan los
  // reintentos del cliente.
  const jornadasConsulta = useQuery({
    queryKey: ['jornadas', seleccionado, desde, hasta],
    queryFn: () => traerJornadas(seleccionado, inicioDeDia(desde), finDeDia(hasta)),
    enabled: seleccionado !== '' && rangoValido,
    retry: false,
    staleTime: CACHE_AUDITORIA_MS,
  });

  const posiciones = useMemo(() => {
    const lista = [...(replay.data?.posiciones ?? [])];
    // La API ya ordena por hora del fix; se reordena como defensa para que la
    // línea y la reproducción nunca retrocedan si el orden cambia.
    lista.sort((a, b) => milisegundos(a.registradoEn) - milisegundos(b.registradoEn));
    return lista;
  }, [replay.data]);

  const huecos = useMemo(() => replay.data?.huecos ?? [], [replay.data]);
  // Tramos reconstruidos por el servidor (ADR-007): el contrato vigente trae
  // `reconstruidos` con método; los `estimados` heredados se normalizan a
  // ESTIMATED en replay.ts como compatibilidad temporal.
  const reconstruidos = useMemo<TramoReconstruido[]>(
    () => normalizarReconstruidos(replay.data),
    [replay.data],
  );
  const paradasServidor = useMemo<Parada[] | null>(() => {
    const datos = paradasConsulta.data?.datos;
    if (!datos) return null;
    // Salvaguarda: el filtro por equipo se contrasta con el id numérico del
    // recorrido cargado. Las filas traen su propio idPublico (el de la parada,
    // no el del dispositivo), así que comparar contra la cadena del equipo
    // descartaría todo si el servidor no aplicara el filtro.
    const dispositivo = replay.data?.dispositivo.id ?? null;
    return datos
      .filter((parada) => dispositivo == null || parada.dispositivoId === dispositivo)
      .map((parada) => ({
        inicio: parada.inicio,
        fin: parada.fin,
        duracionMin: parada.duracionMin,
        latitud: parada.latitud,
        longitud: parada.longitud,
        direccion: parada.direccion,
      }))
      .filter((parada) => Number.isFinite(parada.latitud) && Number.isFinite(parada.longitud))
      // El API ordena por inicio descendente; la lista se lee en orden de
      // recorrido para que la primera parada sea la del inicio de la jornada.
      .sort((a, b) => milisegundos(a.inicio) - milisegundos(b.inicio));
  }, [paradasConsulta.data, replay.data]);

  // Respaldo local: las detenciones necesitan los huecos para descartar rachas
  // que cruzan una pérdida de señal; sin ellos vuelven las duraciones absurdas
  // (1 h 54 min).
  const paradasLocales = useMemo(() => detencionesDeRecorrido(posiciones, huecos), [posiciones, huecos]);
  const paradas = paradasServidor ?? paradasLocales;
  const paradasLocalesEnUso = paradasServidor == null && paradasConsulta.isError;

  // Segmentos con modo vehículo/caminata/quieto: el quieto no dibuja línea
  // (su dispersión se muestra como halo + nube de puntos) para no tejer el
  // espagueti de la deriva parada.
  const segmentos = useMemo(
    () => segmentosDeRecorrido(posiciones, huecos, reconstruidos),
    [posiciones, huecos, reconstruidos],
  );
  const coleccion = useMemo(() => aColeccion(segmentos), [segmentos]);
  // Marcas de dirección espaciadas por distancia (no una por fix); la selección
  // del mapa no depende de ellas, se resuelve por cercanía sobre la línea de
  // acierto. Las marcas ajustadas que caen sobre una parada se descartan para
  // que el punto detenido no se lea como movimiento.
  const direccion = useMemo(() => {
    const coleccion = flechasEspaciadas(posiciones, huecos, reconstruidos, SEPARACION_FLECHAS_M);
    if (paradas.length === 0) return coleccion;
    const features = coleccion.features.filter((flecha) => {
      if (flecha.properties?.origen !== 'matched') return true;
      const [longitud, latitud] = flecha.geometry.coordinates;
      return paradas.every(
        (parada) =>
          distanciaAproxM(latitud, longitud, parada.latitud, parada.longitud) > RADIO_PARADA_FLECHA_M,
      );
    });
    return features.length === coleccion.features.length ? coleccion : { ...coleccion, features };
  }, [posiciones, huecos, reconstruidos, paradas]);
  // Halos de parada (círculo sutil por insignia) y nube de fixes quietos: la
  // dispersión real sin líneas que la unan.
  const halos = useMemo(() => halosDeParadas(posiciones, paradas), [posiciones, paradas]);
  const coleccionHalos = useMemo(() => aColeccionHalos(halos), [halos]);
  const coleccionQuietos = useMemo(() => puntosQuietos(posiciones), [posiciones]);

  const jornadas = useMemo(() => {
    const lista = [...(jornadasConsulta.data?.jornadas ?? [])];
    // El contrato no garantiza orden; se ordena por inicio para que "primera" y
    // "última" sean siempre las del extremo temporal de la ventana.
    lista.sort((a, b) => milisegundos(a.inicioEn) - milisegundos(b.inicioEn));
    return lista;
  }, [jornadasConsulta.data]);

  useEffect(() => {
    if (!mapa) return;
    // Cada bloque se agrega solo si falta: si el mapa se recrea, el estilo
    // arranca vacío y las guardas por id evitan fuentes, capas e imágenes
    // duplicadas.
    if (!mapa.getSource('replay-recorrido')) {
      mapa.addSource('replay-recorrido', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
    }
    if (!mapa.getSource('replay-flechas')) {
      mapa.addSource('replay-flechas', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
    }
    if (!mapa.getSource('replay-halos')) {
      mapa.addSource('replay-halos', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
    }
    if (!mapa.getSource('replay-quieto')) {
      mapa.addSource('replay-quieto', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
    }
    // Limpieza del estilo anterior (línea única + contorno navy): el corredor
    // corporativo lo reemplaza por casing y núcleo por modo. Si el mapa se
    // reutiliza, las capas viejas se retiran para no duplicar la traza.
    if (mapa.getLayer('replay-linea-base')) mapa.removeLayer('replay-linea-base');
    if (mapa.getLayer('replay-linea')) mapa.removeLayer('replay-linea');
    // Sistema visual del corredor corporativo (4 semánticas ADR-007):
    // REAL vehículo (casing contenido + núcleo definido por banda), REAL a pie
    // (mismo idioma, más fino), MATCHED (azul propio), ESTIMATED (gris
    // punteado) y hueco sin datos (gris claro punteado fino). El quieto no
    // tiene capa de línea: su dispersión se muestra como halo + nube sutil.
    const COLOR_BANDA: unknown = [
      'match',
      ['get', 'banda'],
      0, '#2f7d5f',
      1, '#5f8f66',
      2, '#a8893a',
      3, '#a86a35',
      4, '#9c4238',
      '#0b2545',
    ];
    // Sin filtro de modo: cubre toda la traza para la selección, incluida la
    // dispersión parada y los tramos sin señal. Casi transparente, solo acierto.
    if (!mapa.getLayer('replay-linea-hit')) {
      mapa.addLayer({
        id: 'replay-linea-hit',
        type: 'line',
        source: 'replay-recorrido',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': '#000000', 'line-width': 18, 'line-opacity': 0.01 },
      });
    }
    // Halo de parada: círculo sutil bajo el corredor con la dispersión de
    // referencia. La dispersión real la dibuja la nube de puntos quietos.
    if (!mapa.getLayer('replay-halo')) {
      mapa.addLayer({
        id: 'replay-halo',
        type: 'circle',
        source: 'replay-halos',
        paint: {
          'circle-color': '#0b2545',
          'circle-opacity': 0.07,
          'circle-stroke-color': '#0b2545',
          'circle-stroke-opacity': 0.16,
          'circle-stroke-width': 1,
          'circle-radius': ['interpolate', ['linear'], ['zoom'], 10, 14, 16, 34],
        },
      });
    }
    // Casings contenidos del corredor (debajo de los núcleos): apenas un filo
    // translúcido para asentar la línea, sin el halo ancho anterior.
    if (!mapa.getLayer('replay-casing-vehiculo')) {
      mapa.addLayer({
        id: 'replay-casing-vehiculo',
        type: 'line',
        source: 'replay-recorrido',
        filter: ['all', ['==', ['get', 'tipo'], 'ruta'], ['!=', ['get', 'modo'], 'caminata'], ['!=', ['get', 'modo'], 'quieto']],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': COLOR_BANDA as string,
          'line-opacity': 0.22,
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 7.5, 16, 10.5],
        },
      });
    }
    if (!mapa.getLayer('replay-casing-caminata')) {
      mapa.addLayer({
        id: 'replay-casing-caminata',
        type: 'line',
        source: 'replay-recorrido',
        filter: ['all', ['==', ['get', 'tipo'], 'ruta'], ['==', ['get', 'modo'], 'caminata']],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': COLOR_BANDA as string,
          'line-opacity': 0.16,
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 4.5, 16, 6.5],
        },
      });
    }
    if (!mapa.getLayer('replay-casing-matched')) {
      mapa.addLayer({
        id: 'replay-casing-matched',
        type: 'line',
        source: 'replay-recorrido',
        filter: ['==', ['get', 'tipo'], 'matched'],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': COLOR_MATCHED_CASING,
          'line-opacity': 0.3,
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 7, 16, 10.5],
        },
      });
    }
    if (!mapa.getLayer('replay-casing-estimated')) {
      mapa.addLayer({
        id: 'replay-casing-estimated',
        type: 'line',
        source: 'replay-recorrido',
        filter: ['==', ['get', 'tipo'], 'estimated'],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': '#6b7684',
          'line-opacity': 0.16,
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 6, 16, 9],
        },
      });
    }
    // Núcleos definidos del corredor.
    if (!mapa.getLayer('replay-linea-vehiculo')) {
      mapa.addLayer({
        id: 'replay-linea-vehiculo',
        type: 'line',
        source: 'replay-recorrido',
        // Solo GPS registrado en vehículo (y tramos sin modo por compatibilidad):
        // los reconstruidos tienen sus capas propias (ADR-007) y el quieto no
        // dibuja línea.
        filter: ['all', ['==', ['get', 'tipo'], 'ruta'], ['!=', ['get', 'modo'], 'caminata'], ['!=', ['get', 'modo'], 'quieto']],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': COLOR_BANDA as string,
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 3, 16, 5],
        },
      });
    }
    if (!mapa.getLayer('replay-linea-caminata')) {
      mapa.addLayer({
        id: 'replay-linea-caminata',
        type: 'line',
        source: 'replay-recorrido',
        filter: ['all', ['==', ['get', 'tipo'], 'ruta'], ['==', ['get', 'modo'], 'caminata']],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': COLOR_BANDA as string,
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 1.6, 16, 2.6],
        },
      });
    }
    // MATCHED: hueco con observaciones ajustado a vía. Núcleo continuo navy
    // sobre su casing translúcido, sin coloreado por velocidad: la traza
    // ajustada se lee como una vía propia, distinta del GPS registrado.
    if (!mapa.getLayer('replay-matched')) {
      mapa.addLayer({
        id: 'replay-matched',
        type: 'line',
        source: 'replay-recorrido',
        filter: ['==', ['get', 'tipo'], 'matched'],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': COLOR_MATCHED,
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 2.4, 16, 3.8],
        },
      });
    }
    // ESTIMATED: hueco sin observaciones, ruta A→B. Núcleo punteado gris sobre
    // su casing: se lee como estimación, nunca como GPS registrado.
    if (!mapa.getLayer('replay-estimated')) {
      mapa.addLayer({
        id: 'replay-estimated',
        type: 'line',
        source: 'replay-recorrido',
        filter: ['==', ['get', 'tipo'], 'estimated'],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': '#6b7684',
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 2, 16, 3.2],
          'line-dasharray': [2, 2],
        },
      });
    }
    if (!mapa.getLayer('replay-hueco')) {
      mapa.addLayer({
        id: 'replay-hueco',
        type: 'line',
        source: 'replay-recorrido',
        filter: ['==', ['get', 'tipo'], 'hueco'],
        paint: {
          // Gris claro punteado: el hueco se lee como "sin datos" y no se
          // confunde con la ruta ni con el estimado (gris medio más grueso).
          'line-color': '#9aa3af',
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 2, 16, 3],
          'line-dasharray': [1, 2],
        },
      });
    }
    // Nube de dispersión parada: puntos quietos integrados al halo, sin filo
    // blanco ni borde decorativo, sin unirlos con líneas.
    if (!mapa.getLayer('replay-quieto')) {
      mapa.addLayer({
        id: 'replay-quieto',
        type: 'circle',
        source: 'replay-quieto',
        paint: {
          'circle-color': '#0b2545',
          'circle-opacity': 0.16,
          'circle-radius': ['interpolate', ['linear'], ['zoom'], 10, 1.5, 16, 3],
        },
      });
    }
    // Las imágenes de dirección (núcleo blanco y filo del color del tramo)
    // deben existir antes de crear la capa symbol. Se registran con pixelRatio
    // 2 para que icon-size trabaje sobre 32 px lógicos y la punta quede nítida.
    for (let banda = 0; banda < IDS_FLECHA.length; banda += 1) {
      const id = IDS_FLECHA[banda];
      if (mapa.hasImage(id)) mapa.removeImage(id);
      const imagen = imagenDireccion(COLORES_BANDA[banda]);
      if (imagen) mapa.addImage(id, imagen, { pixelRatio: PIXEL_RATIO_FLECHA });
    }
    if (mapa.hasImage(ID_FLECHA_MATCHED)) mapa.removeImage(ID_FLECHA_MATCHED);
    {
      const imagen = imagenDireccion(COLOR_MATCHED);
      if (imagen) mapa.addImage(ID_FLECHA_MATCHED, imagen, { pixelRatio: PIXEL_RATIO_FLECHA });
    }
    const imagenesListas = IDS_FLECHA.every((id) => mapa.hasImage(id)) && mapa.hasImage(ID_FLECHA_MATCHED);
    if (imagenesListas && !mapa.getLayer('replay-flechas')) {
      mapa.addLayer({
        id: 'replay-flechas',
        type: 'symbol',
        source: 'replay-flechas',
        layout: {
          // Marcas espaciadas por distancia (flechasEspaciadas), no una por
          // fix: la densidad base la trae la fuente y el zoom solo adelgaza
          // (flechasPorZoom en cada zoomend). Sin solape: en curvas cerradas el
          // mapa oculta las que choquen en vez de apilar insignias. La rotación
          // es en coordenadas del mapa para que el icono apunte al rumbo real.
          // Los ajustados a vía usan su imagen propia, no la banda de
          // velocidad.
          'icon-image': [
            'match',
            ['get', 'origen'],
            'matched',
            ID_FLECHA_MATCHED,
            ['match', ['get', 'banda'], 0, 'dir-0', 1, 'dir-1', 2, 'dir-2', 3, 'dir-3', 'dir-4'],
          ],
          'icon-rotate': ['get', 'bearing'],
          'icon-rotation-alignment': 'map',
          'icon-keep-upright': false,
          // Escala fina: ~9 px de flecha al alejar y ~21 px en z16, para que
          // ninguna se vea diminuta ni gigante. Crece con el zoom mientras
          // flechasPorZoom adelgaza la densidad.
          'icon-size': [
            'interpolate',
            ['linear'],
            ['zoom'],
            9, 0.36,
            11, 0.44,
            12, 0.5,
            13, 0.58,
            14, 0.66,
            15, 0.74,
            16, 0.82,
          ],
          'icon-allow-overlap': false,
          'icon-ignore-placement': false,
        },
      });
    }
    // ReproductorReplay agrega el aro del punto seleccionado en un efecto
    // propio que corre antes que este (los efectos del hijo van primero); se
    // sube para que quede por encima de la ruta y los chevrones.
    if (mapa.getLayer('replay-punto-activo')) mapa.moveLayer('replay-punto-activo');
  }, [mapa]);

  useEffect(() => {
    if (!mapa) return;
    mapa.getSource<GeoJSONSource>('replay-recorrido')?.setData(coleccion);
  }, [mapa, coleccion]);

  useEffect(() => {
    if (!mapa) return;
    mapa.getSource<GeoJSONSource>('replay-halos')?.setData(coleccionHalos);
  }, [mapa, coleccionHalos]);

  useEffect(() => {
    if (!mapa) return;
    mapa.getSource<GeoJSONSource>('replay-quieto')?.setData(coleccionQuietos);
  }, [mapa, coleccionQuietos]);

  // Marcas de dirección adaptativas al zoom: en cada zoomend se recalcula la
  // densidad y se reemplaza solo la fuente de dirección, sin tocar la ruta ni
  // el encuadre. No hace falta moveend: la densidad depende del zoom, no del
  // centro; el tamaño del icono lo resuelve la expresión icon-size de la capa
  // por su cuenta.
  useEffect(() => {
    if (!mapa) return;
    const fuente = mapa.getSource<GeoJSONSource>('replay-flechas');
    if (!fuente) return;
    const actualizar = () => fuente.setData(flechasPorZoom(direccion, mapa.getZoom()));
    actualizar();
    mapa.on('zoomend', actualizar);
    return () => {
      mapa.off('zoomend', actualizar);
    };
  }, [mapa, direccion]);

  // Extremos del recorrido con su hora en la etiqueta y encuadre inicial: el
  // padding 64 y maxZoom 14 evitan que una ruta corta quede a un zoom agresivo.
  // El efecto depende de las posiciones cargadas, no del índice de
  // reproducción, así que reproducir nunca reencuadra el mapa. Los marcadores
  // se recrean al cambiar de recorrido para refrescar sus etiquetas.
  useEffect(() => {
    if (!mapa || posiciones.length === 0) return;
    const primera = posiciones[0];
    const ultima = posiciones[posiciones.length - 1];
    const inicio = marcadorExtremo(mapa, 'inicio', `Inicio ${horaCorta(primera.registradoEn)}`, primera.latitud, primera.longitud);
    const fin = marcadorExtremo(mapa, 'fin', `Fin ${horaCorta(ultima.registradoEn)}`, ultima.latitud, ultima.longitud);
    const limites = posiciones.reduce(
      (caja, posicion) => caja.extend([posicion.longitud, posicion.latitud] as [number, number]),
      new LngLatBounds([primera.longitud, primera.latitud], [primera.longitud, primera.latitud]),
    );
    mapa.fitBounds(limites, { padding: 64, maxZoom: 14, duration: 0 });
    return () => {
      inicio.remove();
      fin.remove();
    };
  }, [mapa, posiciones]);

  // Inicio y fin de jornada. El endpoint solo trae horas, así que cada extremo
  // se ancla al fix más cercano en el tiempo y solo si cae dentro del tramo
  // cargado: indiceCercaDeInstante devuelve null cuando la jornada empezó antes
  // del primer fix o terminó después del último, y en ese caso no se pinta. Se
  // marcan los extremos de la ventana (primera jornada y última); si la última
  // sigue abierta no hay fin que marcar. Las etiquetas usan las clases
  // jornada-* para diferenciarse del inicio/fin del recorrido.
  useEffect(() => {
    if (!mapa || jornadas.length === 0) return;
    const marcadores: Marker[] = [];
    const primera = jornadas[0];
    const ultima = jornadas[jornadas.length - 1];
    const agregar = (clase: string, texto: string, instante: string | null) => {
      if (instante == null) return;
      const indice = indiceCercaDeInstante(posiciones, milisegundos(instante));
      if (indice == null) return;
      const posicion = posiciones[indice];
      marcadores.push(marcadorExtremo(mapa, clase, texto, posicion.latitud, posicion.longitud));
    };
    agregar('jornada-inicio', 'Inicio jornada', primera.inicioEn);
    if (!ultima.abierta) agregar('jornada-fin', 'Fin jornada', ultima.finEn);
    return () => {
      for (const marcador of marcadores) marcador.remove();
    };
  }, [mapa, jornadas, posiciones]);

  const hayRecorrido = replay.data != null && posiciones.length > 0;

  // CSV en cliente: mismo patrón que Historial (API sin descarga, conjunto ya
  // en memoria). Todas las columnas son ISO-8601 o números, así que la coma no
  // necesita entrecomillado; el BOM evita que Excel destroce los acentos.
  function exportarCsv() {
    const recorrido = replay.data;
    if (!recorrido || posiciones.length === 0) return;
    const encabezado = ['hora', 'latitud', 'longitud', 'velocidad_kmh', 'precision_m', 'bateria_pct'];
    const filas = posiciones.map((posicion) => [
      posicion.registradoEn,
      posicion.latitud.toFixed(6),
      posicion.longitud.toFixed(6),
      posicion.velocidadKmh == null ? '' : posicion.velocidadKmh.toFixed(1),
      posicion.precisionM == null ? '' : String(Math.round(posicion.precisionM)),
      posicion.bateriaPct == null ? '' : String(Math.round(posicion.bateriaPct)),
    ]);
    const contenido = [encabezado, ...filas].map((fila) => fila.join(',')).join('\n');
    const blob = new Blob([`\uFEFF${contenido}`], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const enlace = document.createElement('a');
    enlace.href = url;
    enlace.download = `replay-${nombreArchivo(recorrido.dispositivo.identificadorUnico)}-${desde}_${hasta}.csv`;
    enlace.click();
    URL.revokeObjectURL(url);
  }

  function cuerpoPanel() {
    if (seleccionado === '') return <p className="vacio">No hay equipos asignados a esta cuenta.</p>;
    if (!rangoValido) return <p className="vacio">Revisa las fechas: el inicio no puede ser posterior al fin.</p>;
    if (replay.isPending) return <p className="vacio pulso">Cargando…</p>;
    if (replay.error) {
      if (esNoEncontrado(replay.error)) return <p className="vacio">Sin recorrido en el rango seleccionado.</p>;
      return <p className="vacio">{mensajeError(replay.error)}</p>;
    }
    if (!replay.data || posiciones.length === 0) return <p className="vacio">Sin recorrido en el rango seleccionado.</p>;
    return (
      <>
        <PanelPuntoSeleccionado />
        <ListaParadas
          paradas={paradas}
          origen={paradasLocalesEnUso ? 'local' : 'servidor'}
          total={paradasServidor != null ? paradasConsulta.data?.total : undefined}
        />
      </>
    );
  }

  return (
    <ReproductorReplay
      mapa={mapa}
      posiciones={posiciones}
      huecos={huecos}
      reconstruidos={reconstruidos}
      dispositivo={replay.data?.dispositivo ?? null}
    >
      <section className="replay-pantalla">
        {/* El mapa ocupa la pantalla completa; panel, leyenda y franja flotan
            encima con las clases que definen global.css y operacion.css. */}
        <MapaRaster clase="mapa" alListo={setMapa} />
        {/* Insignias de parada sobre el mapa, dentro del proveedor del
            reproductor: comparten selección con la lista y llevan el mapa a la
            parada con un vuelo suave al pulsarlas. No pintan nada en el DOM. */}
        <InsigniasParadas mapa={mapa} paradas={paradas} />
        {/* Leyenda del sistema visual: estado del marcador, corredor GPS por
            modo (vehículo y a pie) con su sentido de marcha, dispersión parada
            con su insignia, capas reconstruidas con identidad propia (ADR-007)
            y hueco sin datos. */}
        <div className="replay-leyenda" aria-hidden="true">
          <span>
            <span className="muestra movimiento" /> En movimiento
          </span>
          <span>
            <span className="muestra detencion" /> Detenido
          </span>
          <span>
            <span className="muestra sin-senal" /> Sin señal
          </span>
          <span>
            <span className="muestra corredor-vehiculo" /> Recorrido en vehículo
          </span>
          <span>
            <span className="muestra corredor-caminata" /> Recorrido a pie (hasta 8 km/h)
          </span>
          <span>
            <span className="muestra direccion" /> Sentido de marcha
          </span>
          <span>
            <span className="muestra halo" /> Parada (puntos registrados)
          </span>
          <span>
            <span className="muestra ajustado" /> Ajustado a vía
          </span>
          <span>
            <span className="muestra estimado" /> Tramo estimado
          </span>
          <span>
            <span className="muestra sin-datos" /> Tramo sin datos
          </span>
        </div>
        <aside className={`replay-panel${panelRecogido ? ' colapsado' : ''}`}>
          <header className="replay-cabecera">
            <h2>Replay</h2>
            <span className="replay-acciones">
              <button
                type="button"
                className="suave icono-solo"
                onClick={exportarCsv}
                disabled={!hayRecorrido}
                title="Descargar el recorrido (CSV)"
                aria-label="Descargar el recorrido (CSV)"
              >
                <Icono nombre="reportes" tamano={15} />
              </button>
              <button
                type="button"
                className="plegar-panel suave icono-solo"
                onClick={() => setPanelRecogido((valor) => !valor)}
                title={panelRecogido ? 'Mostrar panel' : 'Ocultar panel'}
                aria-label={panelRecogido ? 'Mostrar panel' : 'Ocultar panel'}
                aria-expanded={!panelRecogido}
              >
                <Icono nombre={panelRecogido ? 'flecha' : 'cerrar'} />
              </button>
            </span>
          </header>
          <div className="cuerpo-panel">
            <FiltroReplay
              compacto
              equipos={equipos}
              cargandoEquipos={flota.isPending}
              dispositivoId={seleccionado}
              alCambiarDispositivo={setDispositivoId}
              desde={desde}
              hasta={hasta}
              alCambiarDesde={setDesde}
              alCambiarHasta={setHasta}
            />
            {flota.error && <p className="vacio">{mensajeError(flota.error)}</p>}
            {cuerpoPanel()}
          </div>
        </aside>
        {hayRecorrido && (
          <div className="replay-timeline">
            <LineaTiempoReplay />
          </div>
        )}
      </section>
    </ReproductorReplay>
  );
}
