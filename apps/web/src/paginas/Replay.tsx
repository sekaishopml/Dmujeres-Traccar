import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { LngLatBounds, Marker } from 'maplibre-gl';
import type { GeoJSONSource, Map as TipoMapa } from 'maplibre-gl';
import Icono from '../componentes/Icono';
import MapaRaster from './operacion/MapaRaster';
import ReproductorReplay, { LineaTiempoReplay, ListaParadas, PanelPuntoSeleccionado } from './operacion/ReproductorReplay';
import FiltroReplay from './operacion/FiltroReplay';
import { traerFlota, traerJornadas, traerParadas, traerReplay, CLAVE_FLOTA } from './operacion/datos';
import { esNoEncontrado, mensajeError } from './operacion/errores';
import {
  aColeccion,
  aColeccionHalos,
  detencionesDeRecorrido,
  fechaAyerLocal,
  flechasPorZoom,
  halosDeParadas,
  horaCorta,
  indiceCercaDeInstante,
  milisegundos,
  normalizarReconstruidos,
  puntosDeRecorrido,
  puntosQuietos,
  segmentosDeRecorrido,
} from './operacion/replay';
import type { Parada, TramoReconstruido } from './operacion/replay';
import { duracion } from '../util/formato';
import { fechaHoyLocal, finDeDia, inicioDeDia } from './operacion/rango';
import './operacion.css';

// Nombre de archivo sin caracteres problemáticos para el sistema de archivos.
// Historial tiene su propio helper y este cambio no lo toca; se replica el
// criterio para que ambas descargas generen nombres equivalentes.
function nombreArchivo(id: string): string {
  return id.replace(/[^\w.-]+/g, '_');
}

const LADO_CHEVRON = 28;
// Azul corporativo del tramo ajustado a vía: nunca comparte la paleta de
// velocidad del GPS registrado (ADR-007).
const COLOR_MATCHED = '#4a6fa5';
const ID_CHEVRON_MATCHED = 'chev-matched';
// Paleta de velocidad suavizada (teal, verde, ámbar, naranja y rojo apagados).
// El orden coincide con la banda 0..4 que calcula replay.ts a partir de la
// velocidad y con los ids de imagen que referencia la capa symbol.
const COLORES_BANDA = ['#2a9d8f', '#5a9367', '#d9a441', '#d97b41', '#c65b5b'];
const IDS_CHEVRON = COLORES_BANDA.map((_, banda) => `chev-${banda}`);

// Chevron corporativo integrado: "V" abierta blanca con borde del color del
// tramo, dibujada en canvas y registrada como imagen del mapa. Apunta hacia
// arriba porque MapLibre parte de esa dirección al rotar por rumbo. El núcleo
// blanco se lee sobre el corredor de color y el borde tiñe cada chevron con su
// banda (o con el azul de ajustado a vía): la dirección va integrada al trazo,
// no como marca suelta. Se registra sin pixelRatio para que icon-size mande
// sobre el tamaño; el trazo se centra para que el ancla (centro) caiga en la
// línea.
function imagenChevron(colorBorde: string): ImageData | null {
  const lienzo = document.createElement('canvas');
  lienzo.width = LADO_CHEVRON;
  lienzo.height = LADO_CHEVRON;
  const contexto = lienzo.getContext('2d');
  // Sin contexto 2D no hay imagen; la capa de chevrones se omite y queda el
  // corredor coloreado por velocidad.
  if (!contexto) return null;
  contexto.lineCap = 'round';
  contexto.lineJoin = 'round';
  contexto.beginPath();
  contexto.moveTo(7, 18);
  contexto.lineTo(14, 9);
  contexto.lineTo(21, 18);
  // Borde del color del tramo y núcleo blanco encima: chevron blanco con filo
  // de color, integrado al corredor.
  contexto.strokeStyle = colorBorde;
  contexto.lineWidth = 8;
  contexto.stroke();
  contexto.strokeStyle = '#ffffff';
  contexto.lineWidth = 4.5;
  contexto.stroke();
  return contexto.getImageData(0, 0, LADO_CHEVRON, LADO_CHEVRON);
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
  const equipos = flota.data?.datos ?? [];
  const seleccionado = dispositivoId || equipos[0]?.idPublico || '';
  const rangoValido = desde !== '' && hasta !== '' && desde <= hasta;

  const replay = useQuery({
    queryKey: ['replay', seleccionado, desde, hasta],
    queryFn: () => traerReplay(seleccionado, inicioDeDia(desde), finDeDia(hasta)),
    enabled: seleccionado !== '' && rangoValido,
  });

  // Paradas del servidor en paralelo al recorrido. Si la consulta falla, el
  // panel cae al helper local y lo advierte; mientras carga se muestra el
  // respaldo sin aviso para no parpadear.
  const paradasConsulta = useQuery({
    queryKey: ['paradas', seleccionado, desde, hasta],
    queryFn: () => traerParadas(seleccionado, inicioDeDia(desde), finDeDia(hasta)),
    enabled: seleccionado !== '' && rangoValido,
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
  // Los puntos alimentan solo la capa de chevrones; la selección del mapa ya
  // no depende de ellos, se resuelve por cercanía sobre la línea de acierto.
  const puntos = useMemo(
    () => puntosDeRecorrido(posiciones, huecos, reconstruidos),
    [posiciones, huecos, reconstruidos],
  );
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
    // REAL vehículo (casing translúcido + núcleo por banda), REAL a pie
    // (mismo idioma, más fino), MATCHED (azul propio), ESTIMATED (gris
    // punteado) y hueco sin datos (gris claro punteado fino). El quieto no
    // tiene capa de línea: su dispersión se muestra como halo + nube.
    const COLOR_BANDA: unknown = [
      'match',
      ['get', 'banda'],
      0, '#2a9d8f',
      1, '#5a9367',
      2, '#d9a441',
      3, '#d97b41',
      4, '#c65b5b',
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
    // Casings translúcidos del corredor (debajo de los núcleos).
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
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 9, 16, 14],
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
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 5, 16, 8],
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
          'line-color': COLOR_MATCHED,
          'line-opacity': 0.2,
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 7, 16, 11],
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
          'line-opacity': 0.14,
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 7, 16, 10],
        },
      });
    }
    // Núcleos nítidos del corredor.
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
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 3.5, 16, 6],
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
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 1.8, 16, 3],
        },
      });
    }
    // MATCHED: hueco con observaciones ajustado a vía. Núcleo continuo fino en
    // el azul del método, sin coloreado por velocidad.
    if (!mapa.getLayer('replay-matched')) {
      mapa.addLayer({
        id: 'replay-matched',
        type: 'line',
        source: 'replay-recorrido',
        filter: ['==', ['get', 'tipo'], 'matched'],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': COLOR_MATCHED,
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 2, 16, 3.5],
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
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 2.5, 16, 4],
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
    // Nube de dispersión parada: puntos quietos como halo sutil, sin unirlos.
    if (!mapa.getLayer('replay-quieto')) {
      mapa.addLayer({
        id: 'replay-quieto',
        type: 'circle',
        source: 'replay-quieto',
        paint: {
          'circle-color': '#0b2545',
          'circle-opacity': 0.28,
          'circle-stroke-color': '#ffffff',
          'circle-stroke-opacity': 0.6,
          'circle-stroke-width': 0.5,
          'circle-radius': ['interpolate', ['linear'], ['zoom'], 10, 2, 16, 4],
        },
      });
    }
    // Las imágenes corporativas (núcleo blanco, borde del tramo) deben existir
    // antes de crear la capa symbol. Se regeneran si ya existían con el estilo
    // anterior para que el chevron integrado quede aplicado.
    for (let banda = 0; banda < IDS_CHEVRON.length; banda += 1) {
      const id = IDS_CHEVRON[banda];
      if (mapa.hasImage(id)) mapa.removeImage(id);
      const imagen = imagenChevron(COLORES_BANDA[banda]);
      if (imagen) mapa.addImage(id, imagen);
    }
    if (mapa.hasImage(ID_CHEVRON_MATCHED)) mapa.removeImage(ID_CHEVRON_MATCHED);
    {
      const imagen = imagenChevron(COLOR_MATCHED);
      if (imagen) mapa.addImage(ID_CHEVRON_MATCHED, imagen);
    }
    const imagenesListas = IDS_CHEVRON.every((id) => mapa.hasImage(id)) && mapa.hasImage(ID_CHEVRON_MATCHED);
    if (imagenesListas && !mapa.getLayer('replay-flechas')) {
      mapa.addLayer({
        id: 'replay-flechas',
        type: 'symbol',
        source: 'replay-flechas',
        // Parado no lleva chevron: sin desplazamiento no hay rumbo y el fix ya
        // se lee en la nube de dispersión.
        filter: ['!=', ['get', 'modo'], 'quieto'],
        layout: {
          // Un punto por fix, o uno de cada N según el zoom: la densidad la
          // decide la fuente (flechasPorZoom en cada zoomend) y el tamaño lo
          // fija esta expresión. allow-overlap los deja pegados a la ruta,
          // aunque se solapen en curvas cerradas. La rotación es en coordenadas
          // del mapa para que el icono apunte al rumbo real. Los ajustados a
          // vía usan su imagen propia, no la banda de velocidad.
          'icon-image': [
            'match',
            ['get', 'origen'],
            'matched',
            ID_CHEVRON_MATCHED,
            ['match', ['get', 'banda'], 0, 'chev-0', 1, 'chev-1', 2, 'chev-2', 3, 'chev-3', 'chev-4'],
          ],
          'icon-rotate': ['get', 'bearing'],
          'icon-rotation-alignment': 'map',
          'icon-keep-upright': false,
          'icon-size': ['interpolate', ['linear'], ['zoom'], 9, 0.4, 11, 0.5, 13, 0.62, 15, 0.74, 16, 0.85],
          'icon-allow-overlap': true,
          'icon-ignore-placement': true,
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

  // Flechas adaptativas al zoom: en cada zoomend se recalcula la densidad y se
  // reemplaza solo la fuente de flechas, sin tocar la ruta ni el encuadre. No
  // hace falta moveend: la densidad depende del zoom, no del centro; el tamaño
  // del icono lo resuelve la expresión icon-size de la capa por su cuenta.
  useEffect(() => {
    if (!mapa) return;
    const fuente = mapa.getSource<GeoJSONSource>('replay-flechas');
    if (!fuente) return;
    const actualizar = () => fuente.setData(flechasPorZoom(puntos, mapa.getZoom()));
    actualizar();
    mapa.on('zoomend', actualizar);
    return () => {
      mapa.off('zoomend', actualizar);
    };
  }, [mapa, puntos]);

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

  // Paradas como insignias circulares numeradas con duración: el número sigue
  // el orden del recorrido y la píldora muestra la duración observada. El halo
  // de dispersión y la nube de puntos quietos ya están en sus capas; la
  // insignia solo rotula. No tocan el encuadre y se retiran al cambiar de
  // consulta.
  useEffect(() => {
    if (!mapa) return;
    const marcadores = paradas.map((parada, orden) => {
      const elemento = document.createElement('div');
      elemento.className = 'marcador-parada';
      elemento.title = `Parada ${orden + 1}: ${horaCorta(parada.inicio)} a ${horaCorta(parada.fin)} (${duracion(parada.duracionMin * 60)})`;
      const insignia = document.createElement('span');
      insignia.className = 'parada-insignia';
      insignia.textContent = String(orden + 1);
      const etiqueta = document.createElement('span');
      etiqueta.className = 'parada-duracion';
      etiqueta.textContent = duracion(parada.duracionMin * 60);
      elemento.append(insignia, etiqueta);
      return new Marker({ element: elemento, anchor: 'center' })
        .setLngLat([parada.longitud, parada.latitud])
        .addTo(mapa);
    });
    return () => {
      for (const marcador of marcadores) marcador.remove();
    };
  }, [mapa, paradas]);

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
    if (seleccionado === '') return <p className="vacio">No hay equipos visibles para esta cuenta.</p>;
    if (!rangoValido) return <p className="vacio">El rango de fechas no es válido.</p>;
    if (replay.isPending) return <p className="vacio">Cargando…</p>;
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
        {/* Leyenda del sistema visual: estado del marcador, corredor GPS por
            modo (vehículo y a pie), dispersión parada con su insignia, capas
            reconstruidas con identidad propia (ADR-007) y hueco sin datos. */}
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
            <span className="muestra corredor-vehiculo" /> GPS en vehículo
          </span>
          <span>
            <span className="muestra corredor-caminata" /> GPS a pie (&lt;8 km/h)
          </span>
          <span>
            <span className="muestra halo" /> Parada (dispersión GPS)
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
                title="Exportar CSV"
                aria-label="Exportar CSV"
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
