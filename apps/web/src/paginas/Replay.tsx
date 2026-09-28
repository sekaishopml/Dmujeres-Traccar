import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { LngLatBounds, Marker } from 'maplibre-gl';
import type { GeoJSONSource, Map as TipoMapa } from 'maplibre-gl';
import Icono from '../componentes/Icono';
import MapaRaster from './operacion/MapaRaster';
import ReproductorReplay, { LineaTiempoReplay, ListaParadas, PanelPuntoSeleccionado } from './operacion/ReproductorReplay';
import FiltroReplay from './operacion/FiltroReplay';
import { traerFlota, traerJornadas, traerParadas, traerReplay } from './operacion/datos';
import { esNoEncontrado, mensajeError } from './operacion/errores';
import {
  aColeccion,
  detencionesDeRecorrido,
  fechaAyerLocal,
  flechasPorZoom,
  horaCorta,
  indiceCercaDeInstante,
  milisegundos,
  puntosDeRecorrido,
  segmentosDeRecorrido,
} from './operacion/replay';
import type { Parada } from './operacion/replay';
import { fechaHoyLocal, finDeDia, inicioDeDia } from './operacion/rango';
import './operacion.css';

// Nombre de archivo sin caracteres problemáticos para el sistema de archivos.
// Historial tiene su propio helper y este cambio no lo toca; se replica el
// criterio para que ambas descargas generen nombres equivalentes.
function nombreArchivo(id: string): string {
  return id.replace(/[^\w.-]+/g, '_');
}

const LADO_CHEVRON = 24;
// Paleta de velocidad suavizada (teal, verde, ámbar, naranja y rojo apagados).
// El orden coincide con la banda 0..4 que calcula replay.ts a partir de la
// velocidad y con los ids de imagen que referencia la capa symbol. Los tonos
// saturados anteriores teñían el mapa y competían con las etiquetas.
const COLORES_BANDA = ['#2a9d8f', '#5a9367', '#d9a441', '#d97b41', '#c65b5b'];
const IDS_CHEVRON = COLORES_BANDA.map((_, banda) => `chev-${banda}`);

// Chevron "V" abierta dibujado en un canvas fuera del DOM y registrado como
// imagen del mapa. Apunta hacia arriba porque MapLibre parte de esa dirección
// al rotar por rumbo. Se pinta un trazo grueso del color de la banda y encima
// otro blanco y fino: queda un chevron blanco con borde de color, discreto
// sobre cualquier capa del mapa, en vez del trazo sólido saturado anterior.
// Se registra sin pixelRatio para que icon-size mande sobre el tamaño y el
// icono no se corra de la línea. El trazo se centra en el lienzo para que el
// ancla por defecto (centro) coincida con el fix.
function imagenChevron(color: string): ImageData | null {
  const lienzo = document.createElement('canvas');
  lienzo.width = LADO_CHEVRON;
  lienzo.height = LADO_CHEVRON;
  const contexto = lienzo.getContext('2d');
  // Sin contexto 2D no hay imagen; la capa de chevrones se omite y queda la
  // línea coloreada por velocidad.
  if (!contexto) return null;
  contexto.lineCap = 'round';
  contexto.lineJoin = 'round';
  contexto.beginPath();
  contexto.moveTo(6, 16);
  contexto.lineTo(12, 8);
  contexto.lineTo(18, 16);
  // Halo blanco fino y cuerpo del color de la banda encima: el chevrón se lee
  // por su color sobre mapas claros y el halo lo separa en mapas oscuros.
  contexto.strokeStyle = '#ffffff';
  contexto.lineWidth = 7;
  contexto.stroke();
  contexto.strokeStyle = color;
  contexto.lineWidth = 5;
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

  const flota = useQuery({ queryKey: ['flota'], queryFn: traerFlota });
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
  // Tramos resueltos por calles en el servidor (huecos y fixes muy separados).
  const estimados = useMemo(() => replay.data?.estimados ?? [], [replay.data]);
  const segmentos = useMemo(
    () => segmentosDeRecorrido(posiciones, huecos, estimados),
    [posiciones, huecos, estimados],
  );
  const coleccion = useMemo(() => aColeccion(segmentos), [segmentos]);
  // Los puntos alimentan solo la capa de chevrones; la selección del mapa ya
  // no depende de ellos, se resuelve por cercanía sobre la línea de acierto.
  const puntos = useMemo(
    () => puntosDeRecorrido(posiciones, huecos, estimados),
    [posiciones, huecos, estimados],
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
    // Dos fuentes y cinco capas: el contorno navy que separa la traza del
    // mapa, la ruta coloreada por banda de velocidad, los tramos sin señal
    // punteados, los chevrones sobre los fixes y la capa de acierto de línea.
    // Repintar reemplaza los datos de las fuentes, nunca recrea capas.
    if (!mapa.getLayer('replay-linea-hit')) {
      mapa.addLayer({
        id: 'replay-linea-hit',
        type: 'line',
        source: 'replay-recorrido',
        // Sin filtro: la copia cubre también los tramos sin señal, de modo que
        // toda la traza (incluidas sus flechas) sea pulsable. Va debajo de las
        // capas visibles; con 18 px y opacidad casi nula solo captura el clic.
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': '#000000', 'line-width': 18, 'line-opacity': 0.01 },
      });
    }
    // Contorno navy fino bajo la traza: separa la línea del mapa claro sin
    // lavarla (el casing blanco anterior la hacía invisible sobre fondo
    // blanco) y la mantiene legible en satélite. Va antes que la línea para
    // quedar por debajo.
    if (!mapa.getLayer('replay-linea-base')) {
      mapa.addLayer({
        id: 'replay-linea-base',
        type: 'line',
        source: 'replay-recorrido',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': '#0b2545',
          'line-opacity': 0.35,
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 5, 16, 8],
        },
      });
    }
    if (!mapa.getLayer('replay-linea')) {
      mapa.addLayer({
        id: 'replay-linea',
        type: 'line',
        source: 'replay-recorrido',
        // La ruta real y los tramos estimados por calles comparten capa: el
        // tramo estimado llega con la banda de crucero y se pinta como uno más.
        filter: ['in', ['get', 'tipo'], ['literal', ['ruta', 'estimado']]],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          // Misma paleta que los chevrones. El navy queda como respaldo si un
          // tramo llegara sin banda. La línea engrosa con el zoom: 3,5 px de
          // lejos y 6 px de cerca, sólida para leerse sobre mapas claros.
          'line-color': [
            'match',
            ['get', 'banda'],
            0, '#2a9d8f',
            1, '#5a9367',
            2, '#d9a441',
            3, '#d97b41',
            4, '#c65b5b',
            '#0b2545',
          ],
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 3.5, 16, 6],
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
          // Gris punteado (--gris): el hueco se lee como "sin datos" y no se
          // confunde con la ruta, porque antes compartía el rojo de la banda
          // más rápida y el tramo parecía una recta de alta velocidad.
          'line-color': '#6b7684',
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 2, 16, 3],
          'line-dasharray': [1, 2],
        },
      });
    }
    // Las cinco imágenes deben existir antes de crear la capa symbol; si el
    // canvas falla para alguna, la capa no se agrega y no queda referenciando
    // una imagen ausente.
    for (let banda = 0; banda < IDS_CHEVRON.length; banda += 1) {
      const id = IDS_CHEVRON[banda];
      if (mapa.hasImage(id)) continue;
      const imagen = imagenChevron(COLORES_BANDA[banda]);
      if (imagen) mapa.addImage(id, imagen);
    }
    const imagenesListas = IDS_CHEVRON.every((id) => mapa.hasImage(id));
    if (imagenesListas && !mapa.getLayer('replay-flechas')) {
      mapa.addLayer({
        id: 'replay-flechas',
        type: 'symbol',
        source: 'replay-flechas',
        layout: {
          // Un punto por fix, o uno de cada N según el zoom: la densidad la
          // decide la fuente (flechasPorZoom en cada zoomend) y el tamaño lo
          // fija esta expresión. allow-overlap los deja pegados a la ruta,
          // como en Traccar, aunque se solapen en curvas cerradas. La rotación
          // es en coordenadas del mapa para que el icono apunte al rumbo real.
          'icon-image': ['match', ['get', 'banda'], 0, 'chev-0', 1, 'chev-1', 2, 'chev-2', 3, 'chev-3', 'chev-4'],
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

  // Las paradas no tocan el encuadre: van dentro del recorrido y solo
  // necesitan su círculo hueco naranja. Se retiran al cambiar de consulta.
  useEffect(() => {
    if (!mapa) return;
    const marcadores = paradas.map((parada) => {
      const elemento = document.createElement('div');
      elemento.className = 'marcador-detencion';
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
    <ReproductorReplay mapa={mapa} posiciones={posiciones} huecos={huecos} dispositivo={replay.data?.dispositivo ?? null}>
      <section className="replay-pantalla">
        {/* El mapa ocupa la pantalla completa; panel, leyenda y franja flotan
            encima con las clases que definen global.css y operacion.css. */}
        <MapaRaster clase="mapa" alListo={setMapa} />
        {/* Leyenda del estado del marcador actual y del tramo sin datos,
            junto al mapa y sin adornos. */}
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
