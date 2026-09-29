import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { LngLatBounds, Map as MapaMaplibre, Marker, Popup } from 'maplibre-gl';
import type { Dispositivo, Posicion } from '@contratos';
import { hace } from '../util/formato';
import Icono from '../componentes/Icono';
import MapaRaster from './operacion/MapaRaster';
import ChipEstado from './operacion/ChipEstado';
import BarraBateria from './operacion/BarraBateria';
import { claveEstado, colorEstado } from './operacion/estado';
import { contenidoPopup } from './operacion/popup';
import { traerFlota, traerPosicionesVivas, CLAVE_FLOTA, equiposHabilitados } from './operacion/datos';
import { mensajeError } from './operacion/errores';
import '../estilos/paginas.css';

const REFRESCO_MS = 5000;

// Zoom "urbano" al que viaja el mapa al elegir una unidad y duración del
// vuelo: alcanza para ubicar la calle sin desorientar a la operadora.
const ZOOM_UNIDAD = 15.5;
const VUELO_MS = 900;
const CURVA_VUELO = 1.42;

function movimientoReducido(): boolean {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

// Vuelo a la posición viva de una unidad. Solo lo dispara una orden explícita
// (clic en la lista o en el marcador), nunca el sondeo de 5 s. Con movimiento
// reducido se centra de inmediato, sin animación.
function volarA(mapa: MapaMaplibre, centro: [number, number]) {
  if (movimientoReducido()) {
    mapa.jumpTo({ center: centro, zoom: ZOOM_UNIDAD });
    return;
  }
  mapa.flyTo({ center: centro, zoom: ZOOM_UNIDAD, duration: VUELO_MS, curve: CURVA_VUELO });
}

// Filtros de operación: "sin señal" agrupa SIN_SENAL, SEÑAL_DÉBIL y
// DESCONOCIDO porque los tres exigen revisar el equipo igual. "Fuera de
// jornada" solo agrupa equipos habilitados con jornada cerrada:
// un equipo dado de baja (habilitado=false) se filtra antes de llegar aquí y no
// aparece en ningún filtro, ni en la lista, ni en el mapa.
type FiltroEstado = 'todas' | 'enLinea' | 'detenido' | 'sinSenal' | 'deshabilitado';

const FILTROS: { valor: FiltroEstado; etiqueta: string }[] = [
  { valor: 'todas', etiqueta: 'Todos' },
  { valor: 'enLinea', etiqueta: 'En línea' },
  { valor: 'detenido', etiqueta: 'Detenido' },
  { valor: 'sinSenal', etiqueta: 'Sin señal / débil' },
  { valor: 'deshabilitado', etiqueta: 'Fuera de jornada' },
];

function coincideFiltro(clave: string, filtro: FiltroEstado): boolean {
  if (filtro === 'todas') return true;
  if (filtro === 'sinSenal') return clave === 'sinSenal' || clave === 'senalDebil' || clave === 'desconocido';
  return clave === filtro;
}

export default function EnVivo() {
  const [mapa, setMapa] = useState<MapaMaplibre | null>(null);
  const [busqueda, setBusqueda] = useState('');
  const [filtroEstado, setFiltroEstado] = useState<FiltroEstado>('todas');
  // Unidad elegida en la lista o en el marcador: mantiene el resaltado y la
  // ficha abierta aunque llegue un sondeo nuevo.
  const [seleccionado, setSeleccionado] = useState<number | null>(null);
  const marcadores = useRef(new Map<number, Marker>());
  // Los eventos del popup (open) necesitan los datos más recientes sin volver a
  // crear el marcador; este ref se sincroniza en el efecto que los actualiza.
  const datosRef = useRef<{ dispositivos: Dispositivo[]; posiciones: Map<number, Posicion> }>({
    dispositivos: [],
    posiciones: new Map(),
  });
  // Los manejadores de clic de los marcadores se registran una sola vez, al
  // crearlos: este ref les entrega siempre la versión vigente de seleccionar.
  const seleccionarRef = useRef<(id: number) => void>(() => {});

  // Sondeo de fondo cada 5 s: un 401 aquí no redirige (redirigir401: false),
  // solo deja el estado de error; la comprobación de sesión decide.
  const flota = useQuery({
    queryKey: CLAVE_FLOTA,
    queryFn: () => traerFlota({ redirigir401: false }),
    refetchInterval: () => (document.hidden ? false : REFRESCO_MS),
  });
  const vivas = useQuery({
    queryKey: ['posiciones-vivas'],
    queryFn: () => traerPosicionesVivas({ redirigir401: false }),
    refetchInterval: () => (document.hidden ? false : REFRESCO_MS),
  });

  // La flota habilitada es la única fuente de verdad de En vivo: aunque el
  // endpoint vivo devuelva posiciones de un equipo recién dado de baja, no se
  // pinta marcador ni estado porque el equipo no entra en dispositivos.
  const dispositivos = useMemo(() => equiposHabilitados(flota.data?.datos ?? []), [flota.data]);
  const idsHabilitados = useMemo(() => new Set(dispositivos.map((equipo) => equipo.id)), [dispositivos]);
  const posiciones = useMemo(
    () =>
      new Map(
        (vivas.data?.datos ?? [])
          .filter((posicion) => idsHabilitados.has(posicion.dispositivoId))
          .map((posicion) => [posicion.dispositivoId, posicion] as const),
      ),
    [vivas.data, idsHabilitados],
  );

  // Si la unidad elegida sale de la flota habilitada, la selección deja de
  // tener sentido: se ignora en el render en vez de dejar un resaltado huérfano.
  const seleccionActual = seleccionado != null && idsHabilitados.has(seleccionado) ? seleccionado : null;

  const reconsultarFlota = flota.refetch;
  const reconsultarPosiciones = vivas.refetch;
  useEffect(() => {
    // Al volver a la pestaña la consulta se reactiva de inmediato: el
    // refetchInterval en false detiene el sondeo mientras está oculta.
    const alVolver = () => {
      if (!document.hidden) {
        void reconsultarFlota();
        void reconsultarPosiciones();
      }
    };
    document.addEventListener('visibilitychange', alVolver);
    return () => document.removeEventListener('visibilitychange', alVolver);
  }, [reconsultarFlota, reconsultarPosiciones]);

  useEffect(() => {
    if (!mapa) {
      // El mapa se desmontó (o React lo recreó en modo estricto): los marcadores
      // anteriores quedaron huérfanos y se descartan.
      for (const marcador of marcadores.current.values()) marcador.remove();
      marcadores.current.clear();
      return;
    }
    datosRef.current = { dispositivos, posiciones };
    const vigentes = new Set<number>();
    for (const dispositivo of dispositivos) {
      const posicion = posiciones.get(dispositivo.id);
      if (!posicion) continue;
      vigentes.add(dispositivo.id);
      let marcador = marcadores.current.get(dispositivo.id);
      if (!marcador) {
        const elemento = document.createElement('div');
        elemento.className = 'marcador-equipo';
        elemento.style.background = colorEstado(dispositivo);
        const popup = new Popup({ offset: 16, maxWidth: '280px' });
        popup.on('open', () => {
          const actual = datosRef.current;
          const equipoActual = actual.dispositivos.find((uno) => uno.id === dispositivo.id) ?? dispositivo;
          const posicionActual = actual.posiciones.get(dispositivo.id) ?? posicion;
          popup.setDOMContent(contenidoPopup(equipoActual, posicionActual));
        });
        marcador = new Marker({ element: elemento, anchor: 'center' })
          .setLngLat([posicion.longitud, posicion.latitud])
          .setPopup(popup)
          .addTo(mapa);
        // Clic en el marcador: elige la unidad igual que la lista lateral. Se
        // corta la propagación para que el mapa no lea el clic como "vacío"
        // (quitaría la selección y alternaría el popup recién abierto).
        marcador.on('click', (evento) => {
          evento.originalEvent.stopPropagation();
          seleccionarRef.current(dispositivo.id);
        });
        marcadores.current.set(dispositivo.id, marcador);
      } else {
        // Actualización en sitio: recrear los marcadores en cada refresco de 5 s
        // haría parpadear el mapa y cerraría el popup abierto.
        marcador.setLngLat([posicion.longitud, posicion.latitud]);
        marcador.getElement().style.background = colorEstado(dispositivo);
        const popup = marcador.getPopup();
        if (popup?.isOpen()) popup.setDOMContent(contenidoPopup(dispositivo, posicion));
      }
      // La selección se refleja en el marcador y sobrevive a cada sondeo.
      marcador.getElement().classList.toggle('seleccionado', dispositivo.id === seleccionActual);
    }
    for (const [id, marcador] of marcadores.current) {
      if (!vigentes.has(id)) {
        marcador.remove();
        marcadores.current.delete(id);
      }
    }
  }, [mapa, dispositivos, posiciones, seleccionActual]);

  // El manejador de clic de cada marcador vive más que un render: este efecto
  // mantiene el ref apuntando a la selección vigente del componente.
  useEffect(() => {
    seleccionarRef.current = seleccionarPorId;
  });

  // Clic en el mapa vacío: quita la selección y cierra la ficha. Los clics del
  // marcador no llegan aquí (detienen la propagación) y el popup no pasa por el
  // lienzo, así que cualquier clic recibido es sobre mapa vacío.
  useEffect(() => {
    if (!mapa) return;
    const alClic = () => {
      if (seleccionActual != null) {
        const popup = marcadores.current.get(seleccionActual)?.getPopup();
        if (popup?.isOpen()) popup.remove();
      }
      setSeleccionado(null);
    };
    mapa.on('click', alClic);
    return () => {
      mapa.off('click', alClic);
    };
  }, [mapa, seleccionActual]);

  const conteos = useMemo(() => {
    const acumulado = { enLinea: 0, detenido: 0, sinSenal: 0, deshabilitado: 0 };
    for (const equipo of dispositivos) {
      const clave = claveEstado(equipo);
      if (clave === 'enLinea') acumulado.enLinea += 1;
      else if (clave === 'detenido') acumulado.detenido += 1;
      else if (clave === 'deshabilitado') acumulado.deshabilitado += 1;
      else acumulado.sinSenal += 1;
    }
    return acumulado;
  }, [dispositivos]);

  function cuentaDe(filtro: FiltroEstado): number {
    return filtro === 'todas' ? dispositivos.length : conteos[filtro];
  }

  const filtro = busqueda.trim().toLowerCase();
  const listado = useMemo(() => {
    return dispositivos.filter((equipo) => {
      if (!coincideFiltro(claveEstado(equipo), filtroEstado)) return false;
      if (!filtro) return true;
      return `${equipo.nombre} ${equipo.identificadorUnico} ${equipo.idPublico}`.toLowerCase().includes(filtro);
    });
  }, [dispositivos, filtro, filtroEstado]);

  function centrarFlota() {
    if (!mapa) return;
    const puntos = [...posiciones.values()].map((posicion) => [posicion.longitud, posicion.latitud] as [number, number]);
    if (puntos.length === 0) return;
    const limites = puntos.reduce((caja, punto) => caja.extend(punto), new LngLatBounds(puntos[0], puntos[0]));
    mapa.fitBounds(limites, { padding: 48, maxZoom: 15 });
  }

  function quitarSeleccion() {
    if (seleccionActual != null) {
      const popup = marcadores.current.get(seleccionActual)?.getPopup();
      if (popup?.isOpen()) popup.remove();
    }
    setSeleccionado(null);
  }

  // Vuela a la posición viva de la unidad y abre su ficha. El sondeo de 5 s
  // sigue moviendo el marcador y refrescando el popup, pero no vuelve a volar:
  // el encuadre solo cambia con una nueva orden de la operadora.
  function seleccionarPorId(id: number) {
    if (seleccionActual != null && seleccionActual !== id) {
      const previo = marcadores.current.get(seleccionActual)?.getPopup();
      if (previo?.isOpen()) previo.remove();
    }
    setSeleccionado(id);
    const posicion = posiciones.get(id);
    if (!mapa || !posicion) return;
    volarA(mapa, [posicion.longitud, posicion.latitud]);
    const marcador = marcadores.current.get(id);
    const popup = marcador?.getPopup();
    if (marcador && popup && !popup.isOpen()) marcador.togglePopup();
  }

  function verTodaLaFlota() {
    quitarSeleccion();
    centrarFlota();
  }

  const error = flota.error ?? vivas.error;
  const actualizado = vivas.dataUpdatedAt ? hace(new Date(vivas.dataUpdatedAt).toISOString()) : null;

  return (
    <section className="pagina-en-vivo">
      <header className="cabecera-pagina">
        <div>
          <h1>En vivo</h1>
          <p className="sub">
            {flota.data?.total ?? 0} equipos
            {actualizado ? ` · actualizado ${actualizado}` : ''}
            {vivas.isFetching || flota.isFetching ? ' · actualizando…' : ''}
          </p>
        </div>
        <span className="empuja" />
        {seleccionActual != null && (
          <button type="button" className="suave con-icono" onClick={verTodaLaFlota}>
            <Icono nombre="capas" />
            Ver toda la flota
          </button>
        )}
        <button type="button" className="suave con-icono" onClick={centrarFlota} disabled={!mapa || posiciones.size === 0}>
          <Icono nombre="enVivo" />
          Centrar flota
        </button>
      </header>

      {error && (
        <p className="vacio">
          <Icono nombre="sistema" />
          {mensajeError(error)}
        </p>
      )}

      <div className="en-vivo">
        <MapaRaster clase="mapa mapa-caja" alListo={setMapa} />
        <aside className="bloque panel-equipos">
          <header className="cabecera-seccion">
            <h2>Equipos</h2>
            <span className="cuenta">
              {listado.length} de {dispositivos.length}
            </span>
          </header>
          <label className="busqueda">
            <Icono nombre="buscar" />
            <input
              type="search"
              placeholder="Buscar por nombre o identificador…"
              aria-label="Buscar equipo"
              value={busqueda}
              onChange={(evento) => setBusqueda(evento.target.value)}
            />
          </label>
          <div className="filtro-estados" role="group" aria-label="Filtrar equipos por estado">
            {FILTROS.map((opcion) => (
              <button
                key={opcion.valor}
                type="button"
                className={filtroEstado === opcion.valor ? 'activa' : ''}
                aria-pressed={filtroEstado === opcion.valor}
                onClick={() => setFiltroEstado(opcion.valor)}
              >
                {opcion.etiqueta}
                <span className="cuenta">{cuentaDe(opcion.valor)}</span>
              </button>
            ))}
          </div>
          {flota.isPending && <p className="vacio pulso">Cargando…</p>}
          {!flota.isPending && (
            <ul className="equipos-lista">
              {listado.map((equipo) => {
                const posicion = posiciones.get(equipo.id);
                return (
                  <li key={equipo.id}>
                    <button
                      type="button"
                      className={`equipo${seleccionActual === equipo.id ? ' seleccionado' : ''}`}
                      title={`Centrar ${equipo.nombre}`}
                      aria-pressed={seleccionActual === equipo.id}
                      onClick={() => seleccionarPorId(equipo.id)}
                    >
                      <span className="equipo-titulo">
                        <strong>{equipo.nombre}</strong>
                        <ChipEstado dispositivo={equipo} />
                      </span>
                      <span className="equipo-meta">
                        <span className="mono">{equipo.identificadorUnico}</span>
                        <BarraBateria porcentaje={equipo.bateriaPct} cargando={equipo.cargando} />
                      </span>
                      <span className="equipo-meta">
                        <span>{posicion ? `Última posición ${hace(posicion.registradoEn)}` : 'Sin posición conocida'}</span>
                        <span className={equipo.jornadaActiva ? '' : 'apagado'}>
                          {equipo.jornadaActiva ? 'Jornada activa' : 'Jornada cerrada'}
                        </span>
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
          {!flota.isPending && listado.length === 0 && (
            <p className="vacio">Ningún equipo coincide con el filtro o la búsqueda.</p>
          )}
        </aside>
      </div>
    </section>
  );
}
