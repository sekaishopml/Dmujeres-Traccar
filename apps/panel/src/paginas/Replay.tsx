import { useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { LngLatBounds, Marker } from 'maplibre-gl';
import type { GeoJSONSource, Map as TipoMapa } from 'maplibre-gl';
import Icono from '@/componentes/replay/Icono';
import MapaRaster, { CAPAS_REPLAY } from '@/componentes/mapa/MapaBase';
import ReproductorReplay, {
  InsigniasParadas,
  LineaTiempoReplay,
  ListaParadas,
  PanelPuntoSeleccionado,
} from '@/componentes/replay/ReproductorReplay';
import FiltroReplay from '@/componentes/replay/FiltroReplay';
import { traerFlota, traerJornadas, traerParadas, traerReplay, CACHE_AUDITORIA_MS, CLAVE_FLOTA, equiposHabilitados } from '@/dominio/datos';
import { esNoEncontrado, mensajeError } from '@/dominio/errores';
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
  viajeDeInstante,
  viajesEntreParadas,
} from '@/dominio/replay';
import type { Parada, TramoReconstruido, Viaje } from '@/dominio/replay';
import { finDeDia, inicioDeDia } from '@/dominio/rango';
import '@/componentes/replay/replay.css';
import type { Hueco, ReplayCalidad } from '@contratos';

// Lectura de auditoría del recorrido: cuánto es observado y qué se apartó.
// El trazado sólido es GPS registrado; lo ajustado a vía sigue calles solo
// entre observaciones; lo estimado se limita a saltos cortos coherentes y el
// resto queda punteado como "sin observación". Aquí se dice en una línea.
function IntegridadRecorrido({
  totalFixes,
  huecos,
  reconstruidos,
  calidad,
}: {
  totalFixes: number;
  huecos: Hueco[];
  reconstruidos: TramoReconstruido[];
  calidad?: ReplayCalidad;
}) {
  const minutosSinSenal = Math.round(huecos.reduce((suma, hueco) => suma + hueco.duracionSegundos, 0) / 60);
  const estimados = reconstruidos.filter((tramo) => tramo.metodo === 'ESTIMATED').length;
  const apartados = (calidad?.descartadasFueraDeZona ?? 0) + (calidad?.descartadasSalto ?? 0);
  const sinSenal =
    huecos.length === 0
      ? 'sin cortes de señal'
      : `${huecos.length} ${huecos.length === 1 ? 'corte' : 'cortes'} de señal (${formatoMinutos(minutosSinSenal)})`;
  return (
    <section className="replay-integridad" aria-label="Integridad del recorrido">
      <p>
        <strong>{totalFixes.toLocaleString('es-EC')}</strong> puntos GPS · {sinSenal}
        {estimados > 0 && ` · ${estimados} ${estimados === 1 ? 'salto estimado' : 'saltos estimados'} por calle`}
      </p>
      {apartados > 0 && (
        <p className="replay-nota">
          {apartados} {apartados === 1 ? 'punto imposible apartado' : 'puntos imposibles apartados'} del trazado
          {calidad?.descartadasFueraDeZona ? ` (${calidad.descartadasFueraDeZona} fuera de zona)` : ''}.
        </p>
      )}
      {calidad?.posibleOrigenMultiple && (
        <p className="replay-alerta">
          La posición alterna entre sitios a varios kilómetros: probablemente hay otra sesión abierta con esta cuenta
          en un segundo teléfono.
        </p>
      )}
    </section>
  );
}

// Viajes del día entre paradas. Elegir uno lo resalta en el mapa; elegirlo de
// nuevo (o "Ver el día completo") vuelve a mostrar todo.
function ListaViajes({
  viajes,
  foco,
  alElegir,
}: {
  viajes: Viaje[];
  foco: number | null;
  alElegir: (indice: number | null) => void;
}) {
  if (viajes.length === 0) return null;
  return (
    <section className="replay-viajes">
      <header>
        <h3>Viajes ({viajes.length})</h3>
        {foco != null && (
          <button type="button" className="enlace" onClick={() => alElegir(null)}>
            Ver el día completo
          </button>
        )}
      </header>
      <ol>
        {viajes.map((viaje) => {
          const minutos = Math.max(1, Math.round((milisegundos(viaje.fin) - milisegundos(viaje.inicio)) / 60000));
          return (
            <li key={viaje.inicio}>
              <button
                type="button"
                className={viaje.indice === foco ? 'activo' : ''}
                aria-pressed={viaje.indice === foco}
                onClick={() => alElegir(viaje.indice === foco ? null : viaje.indice)}
              >
                <span className="numero">{viaje.indice + 1}</span>
                <span className="horas">
                  {horaCorta(viaje.inicio)} – {horaCorta(viaje.fin)}
                </span>
                <span className="datos">
                  {viaje.distanciaKm.toLocaleString('es-EC', { maximumFractionDigits: 1 })} km · {formatoMinutos(minutos)}
                </span>
              </button>
            </li>
          );
        })}
      </ol>
    </section>
  );
}

function formatoMinutos(minutos: number): string {
  if (minutos < 60) return `${minutos} min`;
  const horas = Math.floor(minutos / 60);
  const resto = minutos % 60;
  return resto === 0 ? `${horas} h` : `${horas} h ${resto} min`;
}

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
// Núcleo claro de la flecha: se lee sobre la línea azul y sobre satélite.
const NUCLEO_FLECHA = '#ffffff';
// Grosor del filo en px nativos (3 px lógicos a escala 1).
const FILO_FLECHA = 6;
// Trazado: una sola línea azul marino con borde blanco, que se lee igual sobre
// calles y satélite. El viaje elegido pasa a magenta DMujeres y el resto del
// día se atenúa; así un día de idas y vueltas por las mismas calles se lee de
// a un viaje. Lo estimado va punteado en el mismo azul y la falta de señal en
// gris punteado: nunca se confunden con GPS registrado.
const COLOR_RUTA = '#17365d';
const COLOR_FOCO = '#eb0045';
const COLOR_BORDE = '#ffffff';
const COLOR_SIN_SENAL = '#8a94a3';
const OPACIDAD_ATENUADA = 0.2;
const ID_FLECHA = 'dir-ruta';
const ID_FLECHA_FOCO = 'dir-foco';
// Una marca de dirección por cuadra (≈120 m) en ciudad.
const SEPARACION_FLECHAS_M = 120;
// Descarte de marcas ajustadas sobre una parada: el trazado reconstruido puede
// cruzar el punto donde el equipo estuvo detenido y una flecha encima de la
// insignia fingiría movimiento en la parada. Los huecos ya no generan marcas.
const RADIO_PARADA_FLECHA_M = 45;
// Descarte de marcas junto a un tramo sin GPS: un fix real puede caer a pocos
// metros de la ruta estimada o del corte de señal (mismo sitio, otro instante)
// y la flecha encima del punteado gris pasaría por movimiento sobre el hueco.
// Diez metros cubren el ancho de la cinta sin borrar marcas legítimas vecinas.
const RADIO_SIN_GPS_FLECHA_M = 10;

// Distancia plana en metros, suficiente para el descarte local junto a una
// parada (decenas de metros): a esta escala el error frente a la esfera es
// despreciable.
function distanciaAproxM(latA: number, lonA: number, latB: number, lonB: number): number {
  const dLat = (latB - latA) * 111320;
  const dLon = (lonB - lonA) * 111320 * Math.cos(((latA + latB) / 2) * (Math.PI / 180));
  return Math.hypot(dLat, dLon);
}

// Distancia mínima de un punto al segmento A→B con la misma proyección plana
// del descarte junto a parada: el tramo se recorre por si el punto cae frente
// al segmento (el pie se recorta a los extremos) y devuelve metros.
function distanciaASegmentoM(
  latitud: number,
  longitud: number,
  latA: number,
  lonA: number,
  latB: number,
  lonB: number,
): number {
  const escala = Math.cos(((latA + latB) / 2) * (Math.PI / 180));
  const ax = lonA * 111320 * escala;
  const ay = latA * 111320;
  const bx = lonB * 111320 * escala;
  const by = latB * 111320;
  const px = longitud * 111320 * escala;
  const py = latitud * 111320;
  const abx = bx - ax;
  const aby = by - ay;
  const largo2 = abx * abx + aby * aby;
  const t = largo2 > 0 ? Math.max(0, Math.min(1, ((px - ax) * abx + (py - ay) * aby) / largo2)) : 0;
  return Math.hypot(px - (ax + t * abx), py - (ay + t * aby));
}

// Punta de navegación compacta: triángulo ancho con dos muescas laterales
// suaves y una escotadura corta en la base. Es una silueta llena (no el dardo
// hueco tipo chevrón que se probó antes): a 11-14 px se lee como una punta
// maciza orientada y a ~25 px muestran las muescas, que le dan el aire de
// cursor de navegación sin recurrir a decoración. Núcleo blanco y filo del
// color del tramo, dibujada en canvas y registrada como imagen del mapa.
// Apunta hacia arriba porque MapLibre parte de esa dirección al rotar por
// rumbo, y se centra para que el ancla (centro) caiga en la línea. Las uniones
// son redondas: la silueta queda compacta, sin las púas de miter del dardo.
// El filo se traza antes del relleno: la mitad interior del trazo queda
// cubierta y solo asoma el contorno.
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
  // Flanco derecho, muesca lateral y base ancha; la base repite la muesca en
  // espejo para que el rumbo se lea de un vistazo.
  contexto.lineTo(45 * escala, 30 * escala);
  contexto.lineTo(40.5 * escala, 34.5 * escala);
  contexto.lineTo(55 * escala, 50.5 * escala);
  contexto.lineTo(32 * escala, 44 * escala);
  contexto.lineTo(9 * escala, 50.5 * escala);
  contexto.lineTo(23.5 * escala, 34.5 * escala);
  contexto.lineTo(19 * escala, 30 * escala);
  contexto.closePath();
  contexto.lineJoin = 'round';
  contexto.lineWidth = FILO_FLECHA * escala;
  contexto.strokeStyle = borde;
  contexto.stroke();
  contexto.fillStyle = NUCLEO_FLECHA;
  contexto.fill();
  return contexto.getImageData(0, 0, LADO_FLECHA, LADO_FLECHA);
}

// Ranura de la etiqueta respecto del pin. Los extremos que caen en la misma
// zona (ida y vuelta al domicilio, jornada que arranca y cierra en el mismo
// lugar, arranques con deriva GPS) comparten pantalla y sus etiquetas se
// encimaban: cada tipo prueba primero su lado natural y, si está ocupado,
// avanza por una cascada de desplazamientos verticales (o laterales, en las
// ranuras de arriba/abajo) hasta encontrar un hueco libre. Nunca hay dos
// textos superpuestos.
type UbicacionEtiqueta = 'derecha' | 'izquierda' | 'arriba' | 'abajo';

// Orden de lados por tipo: el extremo del recorrido cuelga a la derecha y el
// de la jornada a la izquierda, de modo que un pin compartido quede flanqueado.
const ORDEN_UBICACIONES: Record<string, UbicacionEtiqueta[]> = {
  inicio: ['derecha', 'arriba', 'abajo', 'izquierda'],
  fin: ['derecha', 'abajo', 'arriba', 'izquierda'],
  'jornada-inicio': ['izquierda', 'arriba', 'abajo', 'derecha'],
  'jornada-fin': ['izquierda', 'abajo', 'arriba', 'derecha'],
};

// Cascada de separación: la primera pasada cuelga a la altura del pin; las
// siguientes apilan la píldora una fila arriba/abajo (34 px = píldora + aire).
// El inicio prefiere subir y el fin bajar, como en una lista.
const DESPLAZAMIENTOS_INICIO = [0, -34, 34, -68, 68];
const DESPLAZAMIENTOS_FIN = [0, 34, -34, 68, -68];

// La colisión se decide en píxeles de pantalla, no en metros: a zoom de ciudad
// dos pines pueden estar a más de un kilómetro y sus píldoras solaparse igual.
// Los offsets replican operacion.css: 14 px al lado de un pin de 18 px dejan la
// píldora a 5 px del centro, y 15 px arriba/abajo a 6 px. El alto es 24 px
// medidos y el ancho se sobreestima un poco para no decidir de menos.
const ALTO_ETIQUETA = 26;
const FUERA_HORIZONTAL = 5;
const FUERA_VERTICAL = 6;

function anchoEtiqueta(texto: string): number {
  return 12 + texto.length * 6.4;
}

interface RanuraEtiqueta {
  ubicacion: UbicacionEtiqueta;
  desplazamiento: number;
}

interface CajaEtiqueta {
  izquierda: number;
  arriba: number;
  derecha: number;
  abajo: number;
}

function cajaRanura(ranura: RanuraEtiqueta, x: number, y: number, ancho: number): CajaEtiqueta {
  const { ubicacion, desplazamiento } = ranura;
  switch (ubicacion) {
    case 'izquierda':
      return {
        izquierda: x - FUERA_HORIZONTAL - ancho,
        arriba: y - ALTO_ETIQUETA / 2 + desplazamiento,
        derecha: x - FUERA_HORIZONTAL,
        abajo: y + ALTO_ETIQUETA / 2 + desplazamiento,
      };
    case 'arriba':
      return {
        izquierda: x - ancho / 2 + desplazamiento,
        arriba: y - FUERA_VERTICAL - ALTO_ETIQUETA,
        derecha: x + ancho / 2 + desplazamiento,
        abajo: y - FUERA_VERTICAL,
      };
    case 'abajo':
      return {
        izquierda: x - ancho / 2 + desplazamiento,
        arriba: y + FUERA_VERTICAL,
        derecha: x + ancho / 2 + desplazamiento,
        abajo: y + FUERA_VERTICAL + ALTO_ETIQUETA,
      };
    default:
      return {
        izquierda: x + FUERA_HORIZONTAL,
        arriba: y - ALTO_ETIQUETA / 2 + desplazamiento,
        derecha: x + FUERA_HORIZONTAL + ancho,
        abajo: y + ALTO_ETIQUETA / 2 + desplazamiento,
      };
  }
}

function cajasCruzan(a: CajaEtiqueta, b: CajaEtiqueta): boolean {
  return a.izquierda < b.derecha && b.izquierda < a.derecha && a.arriba < b.abajo && b.arriba < a.abajo;
}

interface EtiquetaExtremo {
  clave: string;
  caja: CajaEtiqueta;
}

// Prueba la cascada del tipo (cada lado por cada desplazamiento) y devuelve la
// primera ranura cuya caja proyectada no cruce ninguna ya colocada; la
// registra y reemplaza la anterior del mismo extremo. Con todas ocupadas (no
// ocurre con cuatro extremos) usa la natural como último recurso.
function elegirRanura(
  registro: { current: EtiquetaExtremo[] },
  mapa: TipoMapa,
  clase: string,
  texto: string,
  latitud: number,
  longitud: number,
): RanuraEtiqueta {
  const lados = ORDEN_UBICACIONES[clase] ?? ORDEN_UBICACIONES.inicio;
  const desplazamientos = clase.endsWith('fin') ? DESPLAZAMIENTOS_FIN : DESPLAZAMIENTOS_INICIO;
  const candidatas: RanuraEtiqueta[] = lados.flatMap((ubicacion) =>
    desplazamientos.map((desplazamiento) => ({ ubicacion, desplazamiento })),
  );
  const punto = mapa.project([longitud, latitud]);
  const ancho = anchoEtiqueta(texto);
  const elegida =
    candidatas.find((ranura) =>
      registro.current.every(
        (etiqueta) => !cajasCruzan(cajaRanura(ranura, punto.x, punto.y, ancho), etiqueta.caja),
      ),
    ) ?? candidatas[0];
  registro.current = registro.current.filter((etiqueta) => etiqueta.clave !== clase);
  registro.current.push({ clave: clase, caja: cajaRanura(elegida, punto.x, punto.y, ancho) });
  return elegida;
}

// Libera las ranuras de los extremos retirados (cambio de recorrido o de
// mapa) para que el siguiente encuadre reparta desde cero.
function liberarUbicaciones(registro: { current: EtiquetaExtremo[] }, claves: string[]): void {
  registro.current = registro.current.filter((etiqueta) => !claves.includes(etiqueta.clave));
}

// Marcador de extremo con etiqueta flotante ("Inicio 08:12"): el punto queda
// anclado a la coordenada y la etiqueta cuelga de la ranura asignada sin
// desplazar el ancla, que maplibre calcula sobre el elemento completo. El
// desplazamiento de la cascada se aplica como margen para no pelear con el
// transform que centra cada ranura.
function marcadorExtremo(
  mapa: TipoMapa,
  clase: string,
  texto: string,
  latitud: number,
  longitud: number,
  ranura: RanuraEtiqueta,
): Marker {
  const elemento = document.createElement('div');
  elemento.className = `marcador-extremo ${clase} etiqueta-${ranura.ubicacion}`;
  const punto = document.createElement('span');
  punto.className = 'extremo-punto';
  const etiqueta = document.createElement('span');
  etiqueta.className = 'extremo-etiqueta';
  etiqueta.textContent = texto;
  if (ranura.desplazamiento !== 0) {
    if (ranura.ubicacion === 'derecha' || ranura.ubicacion === 'izquierda') {
      etiqueta.style.marginTop = `${ranura.desplazamiento}px`;
    } else {
      etiqueta.style.marginLeft = `${ranura.desplazamiento}px`;
    }
  }
  elemento.append(punto, etiqueta);
  return new Marker({ element: elemento, anchor: 'center' }).setLngLat([longitud, latitud]).addTo(mapa);
}

export default function Replay() {
  const [parametros] = useSearchParams();
  const [dispositivoId, setDispositivoId] = useState(parametros.get('dispositivo') ?? '');
  // La ventana por defecto es el día anterior completo: la operación audita lo
  // acontecido ayer, no el día en curso. Desde y Hasta quedan en la misma fecha
  // y el chip "Ayer" del filtro aparece activo; la URL o el ajuste manual la
  // pueden cambiar.
  const [desde, setDesde] = useState(parametros.get('desde') ?? fechaAyerLocal());
  const [hasta, setHasta] = useState(parametros.get('hasta') ?? fechaAyerLocal());
  const [mapa, setMapa] = useState<TipoMapa | null>(null);
  const [panelRecogido, setPanelRecogido] = useState(false);
  // Viaje resaltado (índice en `viajes`) o null para ver el día completo.
  const [viajeFoco, setViajeFoco] = useState<number | null>(null);
  // Ranuras ocupadas por las etiquetas de los extremos (inicio/fin del
  // recorrido y de la jornada): el registro elige una libre cuando dos pines
  // caen juntos. Vive en un ref porque los efectos que crean marcadores son
  // independientes y no deben re-renderizar por él.
  const etiquetasExtremos = useRef<EtiquetaExtremo[]>([]);

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
  const viajes = useMemo(() => viajesEntreParadas(posiciones, paradas), [posiciones, paradas]);
  // Cambiar de equipo o de fechas vuelve al día completo.
  useEffect(() => {
    setViajeFoco(null);
  }, [seleccionado, desde, hasta]);
  // Cada tramo lleva el índice de su viaje (-1 dentro de una parada) para que
  // las capas resalten el elegido y atenúen el resto sin rehacer la fuente.
  const coleccion = useMemo(() => {
    const base = aColeccion(segmentos);
    return {
      ...base,
      features: base.features.map((feature) => ({
        ...feature,
        properties: {
          ...feature.properties,
          viaje: viajeDeInstante(viajes, Number(feature.properties?.instante)),
        },
      })),
    };
  }, [segmentos, viajes]);
  // Marcas de dirección espaciadas por distancia (no una por fix); la selección
  // del mapa no depende de ellas, se resuelve por cercanía sobre la línea de
  // acierto. Las marcas que caen sobre una parada se descartan en cualquier
  // origen (ajustadas, GPS real y la marca centrada de un tramo corto): una
  // flecha encima de la insignia fingiría movimiento en el punto detenido. Con
  // el mismo criterio se descartan las que caen junto a un tramo sin GPS
  // (estimado o hueco): el fix es real, pero encima del punteado gris la marca
  // fingiría un movimiento que la traza no respalda.
  const direccion = useMemo(() => {
    const coleccion = flechasEspaciadas(posiciones, huecos, reconstruidos, SEPARACION_FLECHAS_M);
    const tramosSinGps = segmentos.filter(
      (segmento) => segmento.tipo === 'estimated' || segmento.tipo === 'hueco',
    );
    const features = coleccion.features.filter((flecha) => {
      const [longitud, latitud] = flecha.geometry.coordinates;
      const libreDeParadas = paradas.every(
        (parada) =>
          distanciaAproxM(latitud, longitud, parada.latitud, parada.longitud) > RADIO_PARADA_FLECHA_M,
      );
      if (!libreDeParadas) return false;
      return tramosSinGps.every((tramo) =>
        tramo.coordenadas.every((punto, indice) => {
          if (indice === 0) return true;
          const [lonA, latA] = tramo.coordenadas[indice - 1];
          const [lonB, latB] = punto;
          return (
            distanciaASegmentoM(latitud, longitud, latA, lonA, latB, lonB) >
            RADIO_SIN_GPS_FLECHA_M
          );
        }),
      );
    });
    return {
      ...coleccion,
      features: features.map((flecha) => ({
        ...flecha,
        properties: { ...flecha.properties, viaje: viajeDeInstante(viajes, Number(flecha.properties?.instante)) },
      })),
    };
  }, [posiciones, huecos, reconstruidos, paradas, segmentos, viajes]);
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
    // Capas de versiones anteriores (paleta por velocidad, cinta plegada):
    // si el mapa se reutiliza se retiran para no duplicar la traza.
    for (const vieja of [
      'replay-linea-base', 'replay-casing-vehiculo', 'replay-casing-caminata', 'replay-casing-matched',
      'replay-casing-estimated', 'replay-linea-vehiculo', 'replay-filete-vehiculo', 'replay-matched',
    ]) {
      if (mapa.getLayer(vieja)) mapa.removeLayer(vieja);
    }
    const trazo = ['in', ['get', 'tipo'], ['literal', ['ruta', 'matched', 'estimated']]];
    const noQuieto = ['!=', ['get', 'modo'], 'quieto'];
    // Superficie de acierto: toda la traza, casi transparente.
    if (!mapa.getLayer('replay-linea-hit')) {
      mapa.addLayer({
        id: 'replay-linea-hit',
        type: 'line',
        source: 'replay-recorrido',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': '#000000', 'line-width': 18, 'line-opacity': 0.01 },
      });
    }
    if (!mapa.getLayer('replay-halo')) {
      mapa.addLayer({
        id: 'replay-halo',
        type: 'circle',
        source: 'replay-halos',
        paint: {
          'circle-color': COLOR_RUTA,
          'circle-opacity': 0.06,
          'circle-stroke-color': COLOR_RUTA,
          'circle-stroke-opacity': 0.18,
          'circle-stroke-width': 1,
          'circle-radius': ['interpolate', ['linear'], ['zoom'], 10, 14, 16, 34],
        },
      });
    }
    // Borde blanco bajo la línea: la separa del mapa sin inventar colores.
    if (!mapa.getLayer('replay-borde')) {
      mapa.addLayer({
        id: 'replay-borde',
        type: 'line',
        source: 'replay-recorrido',
        filter: ['all', trazo, noQuieto, ['!=', ['get', 'modo'], 'caminata']] as never,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': COLOR_BORDE,
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 5, 16, 9],
        },
      });
    }
    if (!mapa.getLayer('replay-linea')) {
      mapa.addLayer({
        id: 'replay-linea',
        type: 'line',
        source: 'replay-recorrido',
        filter: ['all', ['in', ['get', 'tipo'], ['literal', ['ruta', 'matched']]], noQuieto, ['!=', ['get', 'modo'], 'caminata']] as never,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': COLOR_RUTA,
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 2.6, 16, 5],
        },
      });
    }
    if (!mapa.getLayer('replay-caminata')) {
      mapa.addLayer({
        id: 'replay-caminata',
        type: 'line',
        source: 'replay-recorrido',
        filter: ['all', ['==', ['get', 'tipo'], 'ruta'], ['==', ['get', 'modo'], 'caminata']] as never,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': COLOR_RUTA,
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 1.6, 16, 3],
        },
      });
    }
    if (!mapa.getLayer('replay-estimated')) {
      mapa.addLayer({
        id: 'replay-estimated',
        type: 'line',
        source: 'replay-recorrido',
        filter: ['==', ['get', 'tipo'], 'estimated'],
        layout: { 'line-join': 'round' },
        paint: {
          'line-color': COLOR_RUTA,
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 2.2, 16, 4],
          'line-dasharray': [1.2, 1.2],
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
          'line-color': COLOR_SIN_SENAL,
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 1.5, 16, 2.5],
          'line-dasharray': [0.6, 2],
        },
      });
    }
    // Nube de fixes quietos: la deriva parada como puntos, sin líneas.
    if (!mapa.getLayer('replay-quieto')) {
      mapa.addLayer({
        id: 'replay-quieto',
        type: 'circle',
        source: 'replay-quieto',
        paint: {
          'circle-color': COLOR_RUTA,
          'circle-opacity': 0.16,
          'circle-radius': ['interpolate', ['linear'], ['zoom'], 10, 1.5, 16, 3],
        },
      });
    }
    // Flechas de sentido: punta blanca con filo del color de su línea.
    for (const [id, color] of [[ID_FLECHA, COLOR_RUTA], [ID_FLECHA_FOCO, COLOR_FOCO]] as const) {
      if (mapa.hasImage(id)) mapa.removeImage(id);
      const imagen = imagenDireccion(color);
      if (imagen) mapa.addImage(id, imagen, { pixelRatio: PIXEL_RATIO_FLECHA });
    }
    if (mapa.hasImage(ID_FLECHA) && !mapa.getLayer('replay-flechas')) {
      mapa.addLayer({
        id: 'replay-flechas',
        type: 'symbol',
        source: 'replay-flechas',
        layout: {
          'icon-image': ID_FLECHA,
          'icon-rotate': ['get', 'bearing'],
          'icon-rotation-alignment': 'map',
          'icon-keep-upright': false,
          'icon-size': ['interpolate', ['linear'], ['zoom'], 10, 0.42, 13, 0.6, 16, 0.85, 18, 0.95],
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

  // Resaltado del viaje elegido: su línea pasa a magenta y el resto del día se
  // atenúa; las flechas quedan solo en el viaje elegido. Sin foco, todo pleno.
  useEffect(() => {
    if (!mapa) return;
    const enFoco = ['==', ['get', 'viaje'], viajeFoco ?? -99];
    const opacidad = viajeFoco == null ? 1 : ['case', enFoco, 1, OPACIDAD_ATENUADA];
    const color = viajeFoco == null ? COLOR_RUTA : ['case', enFoco, COLOR_FOCO, COLOR_RUTA];
    for (const capa of ['replay-linea', 'replay-caminata', 'replay-estimated']) {
      if (!mapa.getLayer(capa)) continue;
      mapa.setPaintProperty(capa, 'line-color', color as never);
      mapa.setPaintProperty(capa, 'line-opacity', opacidad as never);
    }
    for (const capa of ['replay-borde', 'replay-hueco']) {
      if (mapa.getLayer(capa)) mapa.setPaintProperty(capa, 'line-opacity', opacidad as never);
    }
    if (mapa.getLayer('replay-flechas')) {
      mapa.setFilter('replay-flechas', viajeFoco == null ? null : (enFoco as never));
      mapa.setLayoutProperty('replay-flechas', 'icon-image', viajeFoco == null ? ID_FLECHA : ID_FLECHA_FOCO);
    }
  }, [mapa, viajeFoco, coleccion]);

  // Elegir un viaje encuadra sus puntos; volver al día completo no mueve la
  // cámara (el operador decide dónde mirar).
  function enfocarViaje(indice: number | null) {
    setViajeFoco(indice);
    if (!mapa || indice == null) return;
    const viaje = viajes[indice];
    if (!viaje) return;
    const desdeMs = milisegundos(viaje.inicio);
    const hastaMs = milisegundos(viaje.fin);
    const puntos = posiciones.filter((p) => {
      const t = milisegundos(p.registradoEn);
      return t >= desdeMs && t <= hastaMs;
    });
    if (puntos.length === 0) return;
    const caja = puntos.reduce(
      (acumulada, p) => acumulada.extend([p.longitud, p.latitud] as [number, number]),
      new LngLatBounds([puntos[0].longitud, puntos[0].latitud], [puntos[0].longitud, puntos[0].latitud]),
    );
    mapa.fitBounds(caja, { padding: { top: 70, bottom: 150, left: panelRecogido ? 70 : 460, right: 70 }, maxZoom: 16, duration: 600 });
  }

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
    // El encuadre se fija antes de colocar los pines: las ranuras de etiqueta
    // se deciden con la proyección de pantalla definitiva. cameraForBounds +
    // jumpTo aplica la cámara en el acto (fitBounds la agenda al siguiente
    // cuadro y la proyección quedaría en el encuadre anterior).
    const limites = posiciones.reduce(
      (caja, posicion) => caja.extend([posicion.longitud, posicion.latitud] as [number, number]),
      new LngLatBounds([primera.longitud, primera.latitud], [primera.longitud, primera.latitud]),
    );
    const camara = mapa.cameraForBounds(limites, { padding: 64, maxZoom: 14 });
    if (camara) mapa.jumpTo(camara);
    const textoInicio = `Inicio ${horaCorta(primera.registradoEn)}`;
    const textoFin = `Fin ${horaCorta(ultima.registradoEn)}`;
    const inicio = marcadorExtremo(
      mapa,
      'inicio',
      textoInicio,
      primera.latitud,
      primera.longitud,
      elegirRanura(etiquetasExtremos, mapa, 'inicio', textoInicio, primera.latitud, primera.longitud),
    );
    const fin = marcadorExtremo(
      mapa,
      'fin',
      textoFin,
      ultima.latitud,
      ultima.longitud,
      elegirRanura(etiquetasExtremos, mapa, 'fin', textoFin, ultima.latitud, ultima.longitud),
    );
    return () => {
      inicio.remove();
      fin.remove();
      liberarUbicaciones(etiquetasExtremos, ['inicio', 'fin']);
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
      const ranura = elegirRanura(etiquetasExtremos, mapa, clase, texto, posicion.latitud, posicion.longitud);
      marcadores.push(marcadorExtremo(mapa, clase, texto, posicion.latitud, posicion.longitud, ranura));
    };
    agregar('jornada-inicio', 'Inicio jornada', primera.inicioEn);
    if (!ultima.abierta) agregar('jornada-fin', 'Fin jornada', ultima.finEn);
    return () => {
      for (const marcador of marcadores) marcador.remove();
      liberarUbicaciones(etiquetasExtremos, ['jornada-inicio', 'jornada-fin']);
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
        <IntegridadRecorrido
          totalFixes={posiciones.length}
          huecos={huecos}
          reconstruidos={reconstruidos}
          calidad={replay.data.calidad}
        />
        <ListaViajes viajes={viajes} foco={viajeFoco} alElegir={enfocarViaje} />
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
        {/* El mapa ocupa la pantalla completa; panel y franja flotan encima
            con las clases que definen global.css y operacion.css. Replay pide
            el set de capas sin "Mapa" (Satélite inicial) y el zoom abajo a la
            derecha, con el selector pegado al top bar. */}
        <MapaRaster clase="mapa" alListo={setMapa} capas={CAPAS_REPLAY} zoomAbajoDerecha />
        {/* Insignias de parada sobre el mapa, dentro del proveedor del
            reproductor: comparten selección con la lista y llevan el mapa a la
            parada con un vuelo suave al pulsarlas. No pintan nada en el DOM. */}
        <InsigniasParadas mapa={mapa} paradas={paradas} />
        <aside className={`replay-panel${panelRecogido ? ' colapsado' : ''}`}>
          <header className="replay-cabecera">
            <h2>Replay</h2>
            <span className="replay-acciones">
              <button
                type="button"
                className="suave replay-csv"
                onClick={exportarCsv}
                disabled={!hayRecorrido}
                title="Descargar el recorrido (CSV)"
                aria-label="Descargar el recorrido (CSV)"
              >
                <Icono nombre="reportes" tamano={14} />
                CSV
              </button>
              <span className="replay-separador" aria-hidden="true" />
              <button
                type="button"
                className="suave icono-solo"
                onClick={() => setPanelRecogido((valor) => !valor)}
                title={panelRecogido ? 'Mostrar panel' : 'Ocultar panel'}
                aria-label={panelRecogido ? 'Mostrar panel' : 'Ocultar panel'}
                aria-expanded={!panelRecogido}
              >
                <Icono nombre={panelRecogido ? 'chevronDer' : 'chevronIzq'} />
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
