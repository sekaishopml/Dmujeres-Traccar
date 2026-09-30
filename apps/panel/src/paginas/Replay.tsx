import { useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { LngLatBounds, Marker } from 'maplibre-gl';
import type { GeoJSONSource, Map as TipoMapa } from 'maplibre-gl';
import Icono from '@/componentes/replay/Icono';
import MapaRaster, { CAPAS_REPLAY, CAPA_INICIAL_REPLAY } from '@/componentes/mapa/MapaBase';
import ReproductorReplay, {
  InsigniasParadas,
  LineaTiempoReplay,
  ListaParadas,
  PanelPuntoSeleccionado,
} from '@/componentes/replay/ReproductorReplay';
import type { FeatureCollection, LineString } from 'geojson';
import FiltroReplay from '@/componentes/replay/FiltroReplay';
import { depurarRecorrido } from '@/dominio/depuracion';
import { traerFlota, traerJornadas, traerParadas, traerReplay, CACHE_AUDITORIA_MS, CLAVE_FLOTA, equiposHabilitados } from '@/dominio/datos';
import { esNoEncontrado, mensajeError } from '@/dominio/errores';
import {
  aColeccion,
  aColeccionHalos,
  detencionesDeRecorrido,
  halosDeParadas,
  horaCorta,
  indiceCercaDeInstante,
  microparadasDeRecorrido,
  milisegundos,
  normalizarReconstruidos,
  puntosQuietos,
  segmentosDeRecorrido,
} from '@/dominio/replay';
import type { Parada, TramoReconstruido } from '@/dominio/replay';
import { fechaHoyLocal, finDeDia, inicioDeDia } from '@/dominio/rango';
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
  enVivo = false,
}: {
  enVivo?: boolean;
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
      {enVivo && (
        <p className="replay-vivo">
          <span className="replay-vivo-punto" aria-hidden="true" /> En vivo · se actualiza cada 15 s
        </p>
      )}
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
const FILO_FLECHA = 10;
// Trazado: una sola línea azul marino con borde blanco, que se lee igual sobre
// calles y satélite. Lo estimado va punteado en el mismo azul y la falta de señal en
// gris punteado: nunca se confunden con GPS registrado.
const COLOR_RUTA = '#17365d';
const COLOR_BORDE = '#ffffff';
const COLOR_SIN_SENAL = '#8a94a3';
const ID_FLECHA = 'dir-ruta';
const MIN_PARADA_MS = 3 * 60_000;
const REFRESCO_VIVO_MS = 15_000;
const REFRESCO_VIVO_PARADAS_MS = 60_000;
// Distancia plana en metros; a escala de cuadras el error frente a la esfera
// es despreciable.
function distanciaAproxM(latA: number, lonA: number, latB: number, lonB: number): number {
  const dLat = (latB - latA) * 111320;
  const dLon = (lonB - lonA) * 111320 * Math.cos(((latA + latB) / 2) * (Math.PI / 180));
  return Math.hypot(dLat, dLon);
}

// Chevrón de sentido registrado como imagen del mapa (ver capa replay-flechas).
function imagenDireccion(borde: string): ImageData | null {
  const lienzo = document.createElement('canvas');
  lienzo.width = LADO_FLECHA;
  lienzo.height = LADO_FLECHA;
  const contexto = lienzo.getContext('2d');
  // Sin contexto 2D no hay imagen; la capa de dirección se omite y queda el
  // corredor coloreado por velocidad.
  if (!contexto) return null;
  // Chevrón ">" blanco con contorno del azul de la ruta: se lee sobre la
  // línea, sobre calles y sobre satélite. Apunta hacia donde avanza el
  // recorrido (la capa lo alinea con la línea).
  const escala = LADO_FLECHA / 64;
  const trazar = () => {
    contexto.beginPath();
    contexto.moveTo(20 * escala, 12 * escala);
    contexto.lineTo(44 * escala, 32 * escala);
    contexto.lineTo(20 * escala, 52 * escala);
  };
  contexto.lineCap = 'round';
  contexto.lineJoin = 'round';
  trazar();
  contexto.lineWidth = (FILO_FLECHA + 8) * escala;
  contexto.strokeStyle = COLOR_RUTA;
  contexto.stroke();
  trazar();
  contexto.lineWidth = FILO_FLECHA * escala;
  contexto.strokeStyle = borde;
  contexto.stroke();
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
  const [desde, setDesde] = useState(parametros.get('desde') ?? fechaHoyLocal());
  const [hasta, setHasta] = useState(parametros.get('hasta') ?? fechaHoyLocal());
  const [mapa, setMapa] = useState<TipoMapa | null>(null);
  const [panelRecogido, setPanelRecogido] = useState(false);
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

  // En vivo: si el rango llega a hoy, el recorrido se vuelve a pedir cada
  // 15 s y la ruta se va trazando sola. El resto de rangos es histórico y no
  // se refresca.
  const enVivo = rangoValido && hasta === fechaHoyLocal();
  const replay = useQuery({
    queryKey: ['replay', seleccionado, desde, hasta],
    queryFn: () => traerReplay(seleccionado, inicioDeDia(desde), finDeDia(hasta)),
    enabled: seleccionado !== '' && rangoValido,
    staleTime: enVivo ? 0 : CACHE_AUDITORIA_MS,
    refetchInterval: enVivo ? REFRESCO_VIVO_MS : false,
    refetchIntervalInBackground: false,
  });

  // Paradas del servidor en paralelo al recorrido. Si la consulta falla, el
  // panel cae al helper local y lo advierte; mientras carga se muestra el
  // respaldo sin aviso para no parpadear.
  const paradasConsulta = useQuery({
    queryKey: ['paradas', seleccionado, desde, hasta],
    queryFn: () => traerParadas(seleccionado, inicioDeDia(desde), finDeDia(hasta)),
    enabled: seleccionado !== '' && rangoValido,
    staleTime: enVivo ? 0 : CACHE_AUDITORIA_MS,
    refetchInterval: enVivo ? REFRESCO_VIVO_PARADAS_MS : false,
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
    staleTime: enVivo ? 0 : CACHE_AUDITORIA_MS,
    refetchInterval: enVivo ? REFRESCO_VIVO_PARADAS_MS : false,
  });

  const { posiciones, estancias } = useMemo(() => {
    const lista = [...(replay.data?.posiciones ?? [])];
    // La API ya ordena por hora del fix; se reordena como defensa para que la
    // línea y la reproducción nunca retrocedan si el orden cambia.
    lista.sort((a, b) => milisegundos(a.registradoEn) - milisegundos(b.registradoEn));
    // Sin picos de ruido y con cada estancia llevada a su centro: la línea, el
    // marcador, las flechas y el slider trabajan sobre el mismo recorrido.
    return depurarRecorrido(lista);
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
        latitudRepresentativa: parada.latitudRepresentativa,
        longitudRepresentativa: parada.longitudRepresentativa,
        precisionM: parada.precisionM,
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
  // Paradas = las del servidor más las estancias de 3 min o más que el
  // servidor no detectó (su regla es por velocidad y la deriva del GPS la
  // engaña). Cada parada se ubica en el centro de su estancia, que es por
  // donde pasa la línea.
  const paradas = useMemo(() => {
    const base = paradasServidor ?? paradasLocales;
    const solapa = (a: { inicio: string; fin: string }, b: { inicio: string; fin: string }) =>
      milisegundos(a.inicio) <= milisegundos(b.fin) && milisegundos(b.inicio) <= milisegundos(a.fin);
    const ubicadas = base.map((parada) => {
      const estancia = estancias.find((e) => solapa(e, parada));
      return estancia ? { ...parada, latitud: estancia.latitud, longitud: estancia.longitud } : parada;
    });
    const nuevas: Parada[] = estancias
      .filter((e) => milisegundos(e.fin) - milisegundos(e.inicio) >= MIN_PARADA_MS)
      .filter((e) => !base.some((parada) => solapa(e, parada)))
      .map((e) => ({
        inicio: e.inicio,
        fin: e.fin,
        duracionMin: Math.round((milisegundos(e.fin) - milisegundos(e.inicio)) / 6000) / 10,
        latitud: e.latitud,
        longitud: e.longitud,
        direccion: null,
      }));
    return [...ubicadas, ...nuevas].sort((a, b) => milisegundos(a.inicio) - milisegundos(b.inicio));
  }, [paradasServidor, paradasLocales, estancias]);
  const paradasLocalesEnUso = paradasServidor == null && paradasConsulta.isError;
  // Detenciones de 40 s a 3 min que la regla de parada no cuenta (semáforo
  // largo, entrega rápida, espera en la vía): se marcan aparte.
  const microparadas = useMemo(() => microparadasDeRecorrido(posiciones, paradas), [posiciones, paradas]);

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
  // acierto. Las marcas que caen sobre una parada se descartan en cualquier
  // origen (ajustadas, GPS real y la marca centrada de un tramo corto): una
  // flecha encima de la insignia fingiría movimiento en el punto detenido. Con
  // el mismo criterio se descartan las que caen junto a un tramo sin GPS
  // (estimado o hueco): el fix es real, pero encima del punteado gris la marca
  // fingiría un movimiento que la traza no respalda.
  // Líneas continuas del recorrido con movimiento (GPS o ajustado a calles):
  // la capa de flechas coloca chevrones a lo largo de ellas con separación en
  // píxeles de pantalla, así hay flecha en cada tramo y la densidad se adapta
  // al zoom sola. Se unen los pares consecutivos porque un tramo de dos puntos
  // es más corto que la separación y no recibiría flecha.
  const direccion = useMemo<FeatureCollection<LineString>>(() => {
    const lineas: [number, number][][] = [];
    for (const segmento of segmentos) {
      const conFlecha =
        (segmento.tipo === 'ruta' && segmento.modo !== 'quieto') || segmento.tipo === 'matched';
      if (!conFlecha || segmento.coordenadas.length < 2) continue;
      const ultima = lineas[lineas.length - 1];
      const fin = ultima?.[ultima.length - 1];
      const [primero, ...resto] = segmento.coordenadas;
      // Unión con tolerancia de 25 m: el trazado ajustado a calles no empieza
      // exactamente en el fix crudo y sin unir quedaban trocitos sin flecha.
      if (fin && distanciaAproxM(fin[1], fin[0], primero[1], primero[0]) <= 25) ultima.push(...resto);
      else lineas.push([...segmento.coordenadas]);
    }
    return {
      type: 'FeatureCollection',
      features: lineas
        .filter((linea) => linea.length >= 2)
        .map((coordinates) => ({ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates } })),
    };
  }, [segmentos]);
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
          'circle-opacity': 0.12,
          'circle-stroke-color': COLOR_RUTA,
          'circle-stroke-opacity': 0.55,
          'circle-stroke-width': 1.5,
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
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 5.5, 14, 8, 17, 11],
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
          'line-width': ['interpolate', ['linear'], ['zoom'], 10, 3, 14, 5, 17, 7.5],
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
    if (mapa.hasImage(ID_FLECHA)) mapa.removeImage(ID_FLECHA);
    const imagenFlecha = imagenDireccion(NUCLEO_FLECHA);
    if (imagenFlecha) mapa.addImage(ID_FLECHA, imagenFlecha, { pixelRatio: PIXEL_RATIO_FLECHA });
    // Capas de flechas de versiones anteriores (puntos sueltos) se reemplazan.
    if (mapa.getLayer('replay-flechas') && mapa.getLayoutProperty('replay-flechas', 'symbol-placement') !== 'line') {
      mapa.removeLayer('replay-flechas');
    }
    if (mapa.hasImage(ID_FLECHA) && !mapa.getLayer('replay-flechas')) {
      mapa.addLayer({
        id: 'replay-flechas',
        type: 'symbol',
        source: 'replay-flechas',
        minzoom: 11,
        layout: {
          'symbol-placement': 'line',
          'symbol-spacing': ['interpolate', ['linear'], ['zoom'], 11, 40, 14, 56, 17, 72],
          'icon-image': ID_FLECHA,
          'icon-rotation-alignment': 'map',
          'icon-keep-upright': false,
          'icon-size': ['interpolate', ['linear'], ['zoom'], 11, 0.5, 14, 0.62, 17, 0.8],
          'icon-allow-overlap': true,
          'icon-ignore-placement': true,
          'icon-padding': 0,
        },
        paint: { 'icon-opacity': ['interpolate', ['linear'], ['zoom'], 11, 0.75, 13, 1] },
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

  useEffect(() => {
    if (!mapa) return;
    mapa.getSource<GeoJSONSource>('replay-flechas')?.setData(direccion);
  }, [mapa, direccion]);

  // Extremos del recorrido con su hora en la etiqueta y encuadre inicial: el
  // padding 64 y maxZoom 14 evitan que una ruta corta quede a un zoom agresivo.
  // El efecto depende de las posiciones cargadas, no del índice de
  // reproducción, así que reproducir nunca reencuadra el mapa. Los marcadores
  // se recrean al cambiar de recorrido para refrescar sus etiquetas.
  // El encuadre solo se hace al abrir un recorrido (persona o fechas): en vivo
  // llegan puntos cada 15 s y reencuadrar movería el mapa bajo el cursor.
  const recorridoEncuadrado = useRef('');
  useEffect(() => {
    if (!mapa || posiciones.length === 0) return;
    const primera = posiciones[0];
    const ultima = posiciones[posiciones.length - 1];
    const claveRecorrido = `${seleccionado}|${desde}|${hasta}`;
    const encuadrar = recorridoEncuadrado.current !== claveRecorrido;
    recorridoEncuadrado.current = claveRecorrido;
    // El encuadre se fija antes de colocar los pines: las ranuras de etiqueta
    // se deciden con la proyección de pantalla definitiva. cameraForBounds +
    // jumpTo aplica la cámara en el acto (fitBounds la agenda al siguiente
    // cuadro y la proyección quedaría en el encuadre anterior).
    const limites = posiciones.reduce(
      (caja, posicion) => caja.extend([posicion.longitud, posicion.latitud] as [number, number]),
      new LngLatBounds([primera.longitud, primera.latitud], [primera.longitud, primera.latitud]),
    );
    if (encuadrar) {
      const camara = mapa.cameraForBounds(limites, { padding: 64, maxZoom: 14 });
      if (camara) mapa.jumpTo(camara);
    }
    const textoInicio = `Inicio ${horaCorta(primera.registradoEn)}`;
    const textoFin = `${enVivo ? 'Último' : 'Fin'} ${horaCorta(ultima.registradoEn)}`;
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
  }, [mapa, posiciones, seleccionado, desde, hasta, enVivo]);

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
          enVivo={enVivo}
          totalFixes={posiciones.length}
          huecos={huecos}
          reconstruidos={reconstruidos}
          calidad={replay.data.calidad}
        />
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
      finRango={finDeDia(hasta)}
      paradas={paradas}
      microparadas={microparadas}
    >
      <section className="replay-pantalla">
        {/* El mapa ocupa la pantalla completa; panel y franja flotan encima
            con las clases que definen global.css y operacion.css. Replay pide
            el set de capas sin "Mapa" (Satélite inicial) y el zoom abajo a la
            derecha, con el selector pegado al top bar. */}
        <MapaRaster clase="mapa" alListo={setMapa} capas={CAPAS_REPLAY} capaInicial={CAPA_INICIAL_REPLAY} zoomAbajoDerecha selectorPegado />
        {/* Insignias de parada sobre el mapa, dentro del proveedor del
            reproductor: comparten selección con la lista y llevan el mapa a la
            parada con un vuelo suave al pulsarlas. No pintan nada en el DOM. */}
        <InsigniasParadas mapa={mapa} paradas={paradas} />
        <aside className={`replay-panel${panelRecogido ? ' colapsado' : ''}`}>
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
              acciones={
                <span className="replay-acciones">
                  <button
                    type="button"
                    className="suave replay-csv"
                    onClick={exportarCsv}
                    disabled={!hayRecorrido}
                    title="Descargar el recorrido (CSV)"
                    aria-label="Descargar el recorrido (CSV)"
                  >
                    CSV
                  </button>
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
              }
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
