import { memo, useEffect, useRef, useState } from 'react';
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

type IdCapa = (typeof CAPAS_MAPA)[number]['id'];
const CAPA_INICIAL: IdCapa = 'google-mapa';

const ESTILO: NonNullable<MapOptions['style']> = {
  version: 8,
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
    layout: { visibility: capa.id === CAPA_INICIAL ? 'visible' : 'none' },
  })),
};

const CENTRO_INICIAL: [number, number] = [-78.4678, -0.1807];

interface Props {
  clase?: string;
  centro?: [number, number];
  zoom?: number;
  // El callback recibe null al desmontar el mapa para que quien guarde la
  // instancia (marcadores, capas) sepa que ya no sirve.
  alListo?: (mapa: MapaMaplibre | null) => void;
}

// memo: las páginas de mapa se repintan con cada sondeo (5/10 s) y sus props
// son estables (clase literal y setState), así que el contenedor del mapa no
// vuelve a renderizar; maplibre sigue gobernado por sus efectos y estado.
export default memo(function MapaRaster({ clase = 'mapa', centro = CENTRO_INICIAL, zoom = 6, alListo }: Props) {
  const contenedor = useRef<HTMLDivElement>(null);
  const instancia = useRef<MapaMaplibre | null>(null);
  const avisoListo = useRef(alListo);
  const [capaActiva, setCapaActiva] = useState<IdCapa>(CAPA_INICIAL);

  useEffect(() => {
    avisoListo.current = alListo;
  }, [alListo]);

  useEffect(() => {
    const nodo = contenedor.current;
    if (!nodo) return;
    const mapa = new MapaMaplibre({
      container: nodo,
      style: ESTILO,
      center: centro,
      zoom,
      attributionControl: { compact: true },
    });
    instancia.current = mapa;
    // Rueda más lenta que el valor por defecto: en un panel de flota el zoom
    // brusco desorienta y hace perder el encuadre de la unidad.
    mapa.scrollZoom.setZoomRate(1 / 450);
    mapa.scrollZoom.setWheelZoomRate(1 / 450);
    mapa.addControl(new NavigationControl({ showCompass: false }), 'top-right');
    mapa.addControl(new ScaleControl({ unit: 'metric' }), 'bottom-left');
    // El mapa se entrega recién con el estilo cargado: maplibre lanza
    // "Style is not done loading" si se agregan fuentes o capas antes.
    let montado = true;
    mapa.once('load', () => {
      if (montado) avisoListo.current?.(mapa);
    });
    return () => {
      montado = false;
      avisoListo.current?.(null);
      instancia.current = null;
      mapa.remove();
    };
    // centro y zoom son el encuadre inicial; si cambiaran, el mapa se recrea.
    // Las páginas los dejan por defecto y reencuadran al llegar los datos.
  }, [centro, zoom]);

  function cambiarCapa(id: IdCapa) {
    setCapaActiva(id);
    const mapa = instancia.current;
    if (!mapa) return;
    for (const capa of CAPAS_MAPA) {
      mapa.setLayoutProperty(capa.id, 'visibility', capa.id === id ? 'visible' : 'none');
    }
  }

  return (
    <div className={`${clase} mapa-envoltura`}>
      <div ref={contenedor} style={{ position: 'absolute', inset: 0 }} />
      <div className="mapa-selector" role="group" aria-label="Capa del mapa">
        {CAPAS_MAPA.map((capa) => (
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
