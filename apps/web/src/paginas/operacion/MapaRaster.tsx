import { memo, useEffect, useMemo, useRef, useState } from 'react';
import { Map as MapaMaplibre, NavigationControl, ScaleControl, setWorkerUrl } from 'maplibre-gl';
import type { MapOptions } from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
// Maplibre resuelve su worker con new URL(...) relativo a su propio bundle, y
// ni Vite dev ni el build copian maplibre-gl-worker.mjs junto a él. Con
// ?worker&url Vite empaqueta el worker (y su shared) y aquí se le da la URL
// correcta; sin esto el mapa no procesa fuentes GeoJSON.
import urlTrabajador from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';

setWorkerUrl(urlTrabajador);

// Capas raster por URL directa, sin clave de API: Google (mapa/satélite/híbrido)
// como usaba el panel anterior y OpenStreetMap como alternativa libre. Los
// cuatro orígenes viven en el mismo estilo y solo cambia la visibilidad, así
// que cambiar de capa no recrea el mapa ni pierde el encuadre.
const SUFIJOS_GOOGLE = ['mt0', 'mt1', 'mt2', 'mt3'];
const tilesGoogle = (lyrs: string) =>
  SUFIJOS_GOOGLE.map((host) => `https://${host}.google.com/vt/lyrs=${lyrs}&x={x}&y={y}&z={z}`);

export const CAPAS_MAPA = [
  { id: 'google-mapa', nombre: 'Mapa', tiles: tilesGoogle('m'), attribution: '© Google' },
  { id: 'google-satelite', nombre: 'Satélite', tiles: tilesGoogle('s'), attribution: '© Google' },
  { id: 'google-hibrido', nombre: 'Híbrido', tiles: tilesGoogle('y'), attribution: '© Google' },
  {
    id: 'osm',
    nombre: 'OpenStreetMap',
    tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
    attribution: '© OpenStreetMap',
  },
] as const;

export type IdCapa = (typeof CAPAS_MAPA)[number]['id'];
// Set que pide Replay: sin la capa "Mapa" (no se entiende frente a Satélite u
// OpenStreetMap) y con Satélite como primera opción, es decir, la inicial.
export const CAPAS_REPLAY = ['google-satelite', 'google-hibrido', 'osm'] as const satisfies readonly IdCapa[];
// Orden completo del panel; constante para que la prop por defecto no cambie
// de identidad en cada render y memo() siga evitando repintados.
const IDS_CAPAS: readonly IdCapa[] = CAPAS_MAPA.map((capa) => capa.id);

// Duración del fundido entre capas base, leída del token de movimiento
// (--dmj-mov-media) para no duplicar el valor; con movimiento reducido el
// cambio de capa es instantáneo.
function duracionFundidoMs(): number {
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return 0;
  const valor = getComputedStyle(document.documentElement).getPropertyValue('--dmj-mov-media').trim();
  const numero = Number.parseFloat(valor);
  if (!Number.isFinite(numero)) return 180;
  return valor.endsWith('ms') ? numero : numero * 1000;
}

// El estilo se arma por instancia porque la transición global de pintura usa
// la duración del token: con ella, cambiar la opacidad de una capa con
// setPaintProperty funde el cambio sin agregar ni quitar capas.
function estiloMapa(duracionFundido: number, capaInicial: IdCapa): NonNullable<MapOptions['style']> {
  return {
    version: 8,
    transition: { duration: duracionFundido, delay: 0 },
    sources: Object.fromEntries(
      CAPAS_MAPA.map((capa) => [
        capa.id,
        { type: 'raster', tiles: [...capa.tiles], tileSize: 256, maxzoom: 20, attribution: capa.attribution },
      ]),
    ),
    layers: CAPAS_MAPA.map((capa) => ({
      id: capa.id,
      type: 'raster',
      source: capa.id,
      layout: { visibility: capa.id === capaInicial ? 'visible' : 'none' },
    })),
  };
}

const CENTRO_INICIAL: [number, number] = [-78.4678, -0.1807];

interface Props {
  clase?: string;
  centro?: [number, number];
  zoom?: number;
  // El callback recibe null al desmontar el mapa para que quien guarde la
  // instancia (marcadores, capas) sepa que ya no sirve.
  alListo?: (mapa: MapaMaplibre | null) => void;
  // Ids visibles en el selector y primera capa (la inicial). Por defecto, las
  // cuatro del panel con Mapa al frente; Replay pasa CAPAS_REPLAY.
  capas?: readonly IdCapa[];
  // Replay pide el zoom abajo a la derecha: la esquina superior queda libre
  // para el selector de capas pegado al top bar.
  zoomAbajoDerecha?: boolean;
}

// memo: las páginas de mapa se repintan con cada sondeo (5/10 s) y sus props
// son estables (clase literal y setState), así que el contenedor del mapa no
// vuelve a renderizar; maplibre sigue gobernado por sus efectos y estado.
export default memo(function MapaRaster({
  clase = 'mapa',
  centro = CENTRO_INICIAL,
  zoom = 6,
  alListo,
  capas = IDS_CAPAS,
  zoomAbajoDerecha = false,
}: Props) {
  const contenedor = useRef<HTMLDivElement>(null);
  const instancia = useRef<MapaMaplibre | null>(null);
  const avisoListo = useRef(alListo);
  const [capaActiva, setCapaActiva] = useState<IdCapa>(() => capas[0] ?? 'google-mapa');
  // Estado del fundido: duración vigente, capa pedida mientras el estilo aún
  // carga y temporizador que retira las capas salientes al terminar.
  const duracionFundido = useRef(180);
  const cargado = useRef(false);
  const capaDestino = useRef<IdCapa>(capas[0] ?? 'google-mapa');
  const temporizadorCapa = useRef<number | null>(null);
  // Sin WebGL2 (PC viejo, aceleración apagada) maplibre lanza al construir y
  // tumbaba la página entera: se muestra un aviso en el recuadro del mapa y
  // el resto del panel (lista, ficha, reportes) sigue funcionando.
  const [sinMapa, setSinMapa] = useState(false);

  useEffect(() => {
    avisoListo.current = alListo;
  }, [alListo]);

  useEffect(() => {
    const nodo = contenedor.current;
    if (!nodo) return;
    duracionFundido.current = duracionFundidoMs();
    cargado.current = false;
    let mapa: MapaMaplibre;
    try {
      mapa = new MapaMaplibre({
        container: nodo,
        style: estiloMapa(duracionFundido.current, capaDestino.current),
        center: centro,
        zoom,
        attributionControl: { compact: true },
      });
    } catch {
      setSinMapa(true);
      return;
    }
    instancia.current = mapa;
    // Rueda más lenta que el valor por defecto: en un panel de flota el zoom
    // brusco desorienta y hace perder el encuadre de la unidad.
    mapa.scrollZoom.setZoomRate(1 / 450);
    mapa.scrollZoom.setWheelZoomRate(1 / 450);
    // Replay deja el zoom abajo a la derecha para que el selector de capas,
    // pegado al top bar, no lo tape.
    mapa.addControl(new NavigationControl({ showCompass: false }), zoomAbajoDerecha ? 'bottom-right' : 'top-right');
    mapa.addControl(new ScaleControl({ unit: 'metric' }), 'bottom-left');
    // El mapa se entrega recién con el estilo cargado: maplibre lanza
    // "Style is not done loading" si se agregan fuentes o capas antes.
    let montado = true;
    mapa.once('load', () => {
      if (!montado) return;
      cargado.current = true;
      // Una capa pedida antes de cargar el estilo se aplica sin fundido.
      aplicarCapa(capaDestino.current, false);
      avisoListo.current?.(mapa);
    });
    return () => {
      montado = false;
      if (temporizadorCapa.current != null) {
        window.clearTimeout(temporizadorCapa.current);
        temporizadorCapa.current = null;
      }
      cargado.current = false;
      avisoListo.current?.(null);
      instancia.current = null;
      mapa.remove();
    };
    // centro y zoom son el encuadre inicial y el lado del control de zoom es de
    // creación: si cambiaran, el mapa se recrea. Las páginas los dejan por
    // defecto y reencuadran al llegar los datos.
  }, [centro, zoom, zoomAbajoDerecha]);

  // Cambia la capa base fundiendo la entrante desde opacidad 0 y apagando las
  // salientes; al terminar, las salientes salen del render. No agrega ni quita
  // capas: solo ajusta opacidad y visibilidad con setPaintProperty.
  function aplicarCapa(id: IdCapa, fundir: boolean) {
    const mapa = instancia.current;
    if (!mapa || !cargado.current) return;
    const visibles = CAPAS_MAPA.filter((capa) => mapa.getLayoutProperty(capa.id, 'visibility') !== 'none');
    // Misma capa ya visible (o clic repetido): no hay nada que fundir.
    if (visibles.length === 1 && visibles[0].id === id) return;
    if (temporizadorCapa.current != null) {
      window.clearTimeout(temporizadorCapa.current);
      temporizadorCapa.current = null;
    }
    const salientes = visibles.filter((capa) => capa.id !== id);
    if (!fundir) {
      for (const capa of salientes) {
        mapa.setLayoutProperty(capa.id, 'visibility', 'none');
        mapa.setPaintProperty(capa.id, 'raster-opacity', 1);
      }
      mapa.setLayoutProperty(id, 'visibility', 'visible');
      mapa.setPaintProperty(id, 'raster-opacity', 1);
      return;
    }
    // La entrante arranca en 0 y sube a 1 con la transición global del estilo;
    // las salientes bajan a 0 y recién al terminar salen del render.
    mapa.setPaintProperty(id, 'raster-opacity', 0);
    mapa.setLayoutProperty(id, 'visibility', 'visible');
    mapa.setPaintProperty(id, 'raster-opacity', 1);
    for (const capa of salientes) mapa.setPaintProperty(capa.id, 'raster-opacity', 0);
    const retirar = () => {
      for (const capa of salientes) {
        mapa.setLayoutProperty(capa.id, 'visibility', 'none');
        mapa.setPaintProperty(capa.id, 'raster-opacity', 1);
      }
    };
    if (duracionFundido.current <= 0) {
      retirar();
      return;
    }
    temporizadorCapa.current = window.setTimeout(() => {
      temporizadorCapa.current = null;
      retirar();
    }, duracionFundido.current + 40);
  }

  function cambiarCapa(id: IdCapa) {
    setCapaActiva(id);
    capaDestino.current = id;
    aplicarCapa(id, true);
  }

  // Selector en el orden del panel, restringido al set vigente: Replay no
  // muestra "Mapa". El estilo sigue definiendo las cuatro capas por si el
  // mapa se reutiliza en otra página, pero aquí solo se ofrecen las pedidas.
  const definiciones = useMemo(() => CAPAS_MAPA.filter((capa) => capas.includes(capa.id)), [capas]);

  return (
    <div className={`${clase} mapa-envoltura`}>
      <div ref={contenedor} style={{ position: 'absolute', inset: 0 }} />
      {sinMapa && (
        <p className="mapa-sin-webgl">
          Este navegador no puede dibujar el mapa (necesita aceleración gráfica WebGL2). Activa la aceleración por
          hardware en la configuración del navegador o abre el panel en Chrome o Edge actualizados.
        </p>
      )}
      <div className="mapa-selector" role="group" aria-label="Capa del mapa">
        {definiciones.map((capa) => (
          <button
            key={capa.id}
            type="button"
            className={capa.id === capaActiva ? 'activo' : ''}
            onClick={() => cambiarCapa(capa.id)}
          >
            {capa.nombre}
          </button>
        ))}
      </div>
    </div>
  );
});
