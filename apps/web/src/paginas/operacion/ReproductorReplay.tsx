import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode, RefObject } from 'react';
import { Marker } from 'maplibre-gl';
import type { GeoJSONSource, Map as TipoMapa, MapMouseEvent } from 'maplibre-gl';
import { useQuery } from '@tanstack/react-query';
import { CategoryScale, Chart as ChartJS, Filler, LineElement, LinearScale, PointElement } from 'chart.js';
import type { ChartData, ChartOptions } from 'chart.js';
import { Line } from 'react-chartjs-2';
import type { FeatureCollection, Point } from 'geojson';
import type { Dispositivo, Hueco, Posicion } from '@contratos';
import { bateria, duracion, fechaHora, GUION, velocidad } from '../../util/formato';
import Icono from '../../componentes/Icono';
import { traerDireccion } from './datos';
import {
  estadoDePunto,
  horaCorta,
  indiceBateriaConocida,
  indiceMasCercano,
  indicePorInstante,
  milisegundos,
  puntoEnInstante,
  serieBateria,
} from './replay';
import type { EstadoUnidad, Parada } from './replay';

// Chart.js exige registrar las piezas que se dibujan. El gráfico del
// reproductor es una línea con relleno, sin ejes, sin leyenda y sin tooltip:
// solo se registran escala, línea, punto y relleno.
ChartJS.register(CategoryScale, LinearScale, LineElement, PointElement, Filler);

const VELOCIDADES = [0.5, 1, 2, 4, 8, 16];

// El marcador se mueve en cada frame (rAF), pero la interfaz (gráfico, slider,
// lectura) se refresca 5 veces por segundo: repintar Chart.js a 60 Hz sobre
// miles de fixes castiga el navegador sin mejorar la lectura, y el reloj sigue
// avanzando igual porque vive en instanteRef. A 5 Hz la hora, la velocidad, la
// batería y el punto del gráfico se sienten continuos; a 10 Hz cada tick
// duplicaba renders de React y del gráfico.
const INTERVALO_PINTADO_MS = 200;

// Factor base adaptativo: con una ruta de 14 h el reloj a 1× avanzaba casi en
// tiempo real y el play parecía roto. La ruta completa se recorre en ~60 s a
// 1×, con piso 1× para no acelerar rutas cortas y techo 300× para que una ruta
// de varios días no sea un parpadeo. Sobre rutas de más de 5 h el techo hace
// que 1× tarde más de 60 s (14 h ≈ 2.8 min), todavía visible.
const FACTOR_MINIMO = 1;
const FACTOR_MAXIMO = 300;
const SEGUNDOS_OBJETIVO = 60;

function factorBase(posiciones: Posicion[]): number {
  if (posiciones.length < 2) return FACTOR_MINIMO;
  const duracionMs =
    milisegundos(posiciones[posiciones.length - 1].registradoEn) - milisegundos(posiciones[0].registradoEn);
  if (!(duracionMs > 0)) return FACTOR_MINIMO;
  const factor = duracionMs / (SEGUNDOS_OBJETIVO * 1000);
  return Math.min(Math.max(factor, FACTOR_MINIMO), FACTOR_MAXIMO);
}

// Color del marcador actual por estado del fix. global.css define el navy como
// base del marcador; estas clases lo reemplazan en cuanto hay posiciones.
const CLASE_ESTADO: Record<EstadoUnidad, string> = {
  movimiento: 'estado-movimiento',
  detencion: 'estado-detencion',
  sinSenal: 'estado-sin-senal',
};

// Etiqueta legible del estado del fix para la ficha del punto seleccionado.
const ETIQUETA_ESTADO_PUNTO: Record<EstadoUnidad, string> = {
  movimiento: 'En movimiento',
  detencion: 'Detenido',
  sinSenal: 'Sin señal',
};

// Línea fina, sin puntos, relleno tenue y ejes ocultos: la batería se lee como
// contexto del recorrido, no como un gráfico de análisis. El eje Y fijo en
// 0-100 mantiene la escala estable entre equipos.
const OPCIONES_BATERIA: ChartOptions<'line'> = {
  responsive: true,
  maintainAspectRatio: false,
  animation: false,
  plugins: {
    legend: { display: false },
    tooltip: { enabled: false },
  },
  scales: {
    x: { display: false },
    y: { display: false, min: 0, max: 100 },
  },
  elements: {
    line: { borderWidth: 1.5 },
    point: { radius: 0 },
  },
};

interface Props {
  mapa: TipoMapa | null;
  posiciones: Posicion[];
  huecos: Hueco[];
  dispositivo: Dispositivo | null;
  children: ReactNode;
}

interface Reproductor {
  posiciones: Posicion[];
  huecos: Hueco[];
  dispositivo: Dispositivo | null;
  indice: number;
  punto: Posicion | null;
  reproduciendo: boolean;
  velocidad: number;
  seguir: boolean;
  seleccionado: number | null;
  // El slider del tiempo es no controlado: el reproductor escribe su valor y
  // su relleno de progreso por frame durante la reproducción (60 fps), así el
  // avance se ve continuo y no a saltos del repintado de React (5 Hz).
  sliderRef: RefObject<HTMLInputElement | null>;
  alternar: () => void;
  alternarSeguir: () => void;
  saltarHueco: (direccion: 1 | -1) => void;
  cambiarVelocidad: (valor: number) => void;
  mover: (indice: number) => void;
  pausar: () => void;
  // Salto a un instante de la línea de tiempo pedido desde fuera (paradas).
  irA: (instante: number) => void;
  // Salto a una parada: pausa, ubica el reloj, selecciona el fix y centra el
  // mapa en ella, igual que elegir un colaborador en En vivo.
  irAPunto: (latitud: number, longitud: number, instante: number) => void;
  // Selección de un fix al pulsar la ruta: pausa y ubica el reproductor.
  seleccionar: (indice: number) => void;
  quitarSeleccion: () => void;
}

const ContextoReproductor = createContext<Reproductor | null>(null);

// Contenedor de la reproducción: conserva todo el estado (reloj simulado,
// índice, marcador actual, selección, saltos de hueco) y lo comparte por
// contexto con los bloques de salida. Replay coloca los bloques en el panel
// flotante y la franja inferior, de modo que comparten estado sin duplicar la
// lógica. La superficie de selección es la capa de acierto de línea que agrega
// Replay sobre la ruta; el reproductor se engancha a ella y resuelve el fix más
// cercano con posiciones, que ya tiene en memoria.
export default function ReproductorReplay({ mapa, posiciones, huecos, dispositivo, children }: Props) {
  const [indice, setIndice] = useState(0);
  const [reproduciendo, setReproduciendo] = useState(false);
  const [velocidadReproduccion, setVelocidadReproduccion] = useState(1);
  // Seguir apagado por defecto: el encuadre inicial del recorrido manda hasta
  // que el usuario pida acompañar el marcador.
  const [seguir, setSeguir] = useState(false);
  const [seleccionado, setSeleccionado] = useState<number | null>(null);
  const marcadorActual = useRef<Marker | null>(null);
  // El reloj y el índice viven en refs para que el bucle de animación no se
  // reinicie en cada avance; el estado solo provoca el repintado de la interfaz.
  // `indiceRef` es la verdad para pausar o saltos; `indicePintadoRef` guarda el
  // último índice enviado al estado para espaciar los repintados.
  const indiceRef = useRef(0);
  const indicePintadoRef = useRef(0);
  const instanteRef = useRef(posiciones.length > 0 ? milisegundos(posiciones[0].registradoEn) : 0);
  // El slider vive en la franja inferior, pero lo gobierna este contenedor:
  // durante la reproducción se escribe por frame y React no lo controla.
  const sliderRef = useRef<HTMLInputElement | null>(null);

  // Escribe en el slider el instante del reloj (valor y relleno de progreso).
  // Se usa al moverse a mano, al cambiar de recorrido y en cada frame de la
  // reproducción; con la entrada no controlada no hay pelea con el repintado.
  const sincronizarSlider = useCallback(
    (instante: number) => {
      const nodo = sliderRef.current;
      const primera = posiciones[0];
      const ultima = posiciones[posiciones.length - 1];
      if (!nodo || !primera || !ultima) return;
      const inicio = milisegundos(primera.registradoEn);
      const total = Math.max(0, milisegundos(ultima.registradoEn) - inicio);
      const posicion = Math.min(Math.max(instante - inicio, 0), total);
      nodo.value = String(posicion);
      nodo.style.setProperty('--progreso', total > 0 ? `${(posicion / total) * 100}%` : '0%');
    },
    [posiciones],
  );

  // Cada recorrido llega con un array nuevo (Replay lo memoiza por respuesta de
  // la API): al cambiar de equipo o de rango se reinicia el índice, la
  // reproducción y la selección en el mismo render, como recomienda React para
  // estado que depende de una prop, y el reloj en un efecto de layout que corre
  // antes de los efectos del marcador y del bucle.
  const [recorrido, setRecorrido] = useState(posiciones);
  if (recorrido !== posiciones) {
    setRecorrido(posiciones);
    setIndice(0);
    setReproduciendo(false);
    setSeleccionado(null);
  }

  useLayoutEffect(() => {
    indiceRef.current = 0;
    indicePintadoRef.current = 0;
    instanteRef.current = posiciones.length > 0 ? milisegundos(posiciones[0].registradoEn) : 0;
    sincronizarSlider(instanteRef.current);
  }, [posiciones, sincronizarSlider]);

  const indiceAcotado = posiciones.length === 0 ? 0 : Math.min(indice, posiciones.length - 1);
  const punto = posiciones[indiceAcotado] ?? null;

  // Estado del fix actual: alimenta el color del marcador. La antigüedad se
  // evalúa contra Date.now(), pero solo manda en el último fix del recorrido;
  // en los intermedios la señal la decide el salto al fix siguiente.
  const estadoUnidad = useMemo(
    () => estadoDePunto(posiciones, huecos, indiceAcotado),
    [posiciones, huecos, indiceAcotado],
  );

  useEffect(() => {
    if (!mapa) {
      marcadorActual.current?.remove();
      marcadorActual.current = null;
      return;
    }
    const primero = posiciones[0];
    if (!primero) return;
    if (!marcadorActual.current) {
      const elemento = document.createElement('div');
      elemento.className = `marcador-actual ${CLASE_ESTADO[estadoUnidad]}`;
      // maplibre v6 exige posición antes de addTo; si no, addTo revienta.
      marcadorActual.current = new Marker({ element: elemento, anchor: 'center' })
        .setLngLat([primero.longitud, primero.latitud])
        .addTo(mapa);
    }
  }, [mapa, posiciones, estadoUnidad]);

  useEffect(() => {
    const elemento = marcadorActual.current?.getElement();
    if (elemento) elemento.className = `marcador-actual ${CLASE_ESTADO[estadoUnidad]}`;
  }, [estadoUnidad, mapa, posiciones]);

  useEffect(() => {
    // Durante la reproducción el marcador lo gobierna el bucle rAF, que lo
    // interpola entre fixes; este efecto solo atiende los movimientos manuales
    // (slider, saltos, selección) para no devolverlo a la posición discreta.
    if (reproduciendo) return;
    const actual = posiciones[Math.min(indice, posiciones.length - 1)];
    if (!actual) return;
    marcadorActual.current?.setLngLat([actual.longitud, actual.latitud]);
    // setCenter sin animación: el acompañamiento es un salto sólido al punto;
    // una transición por frame pelearía con el siguiente.
    if (seguir && mapa) mapa.setCenter([actual.longitud, actual.latitud], { duration: 0 });
  }, [indice, posiciones, mapa, seguir, reproduciendo]);

  // Con la reproducción detenida el slider se sincroniza con el fix actual:
  // cubre el arrastre, los saltos, la selección y el fin del recorrido.
  useEffect(() => {
    if (reproduciendo) return;
    sincronizarSlider(punto ? milisegundos(punto.registradoEn) : 0);
  }, [reproduciendo, punto, sincronizarSlider]);

  // Factor base del reloj simulado: cambia con el recorrido cargado, así que se
  // memoiza y el bucle lo captura en sus dependencias.
  const factor = useMemo(() => factorBase(posiciones), [posiciones]);

  useEffect(() => {
    if (!reproduciendo || posiciones.length < 2) return;
    let cuadro = 0;
    let anteriorFrame = performance.now();
    let ultimoPintado = 0;
    const avanzar = (ahora: number) => {
      // El reloj avanza por el tiempo real transcurrido entre frames: a 4× el
      // recorrido dura la cuarta parte que a 1×. Al pausar o cambiar de
      // velocidad el reloj vive en instanteRef, así que la posición no se
      // pierde; el nuevo bucle solo recalibra el frame anterior.
      instanteRef.current += (ahora - anteriorFrame) * velocidadReproduccion * factor;
      anteriorFrame = ahora;
      // El slider (valor y relleno) se escribe en cada frame: la barra de
      // progreso avanza continua aunque React repinte a 5 Hz.
      sincronizarSlider(instanteRef.current);
      const siguiente = indicePorInstante(posiciones, instanteRef.current);
      if (siguiente >= posiciones.length - 1) {
        indiceRef.current = posiciones.length - 1;
        indicePintadoRef.current = posiciones.length - 1;
        sincronizarSlider(milisegundos(posiciones[posiciones.length - 1].registradoEn));
        setIndice(posiciones.length - 1);
        setReproduciendo(false);
        return;
      }
      indiceRef.current = siguiente;
      if (siguiente !== indicePintadoRef.current && ahora - ultimoPintado >= INTERVALO_PINTADO_MS) {
        indicePintadoRef.current = siguiente;
        ultimoPintado = ahora;
        setIndice(siguiente);
      }
      const interpolado = puntoEnInstante(posiciones, huecos, instanteRef.current);
      if (interpolado) {
        marcadorActual.current?.setLngLat([interpolado.longitud, interpolado.latitud]);
        if (seguir && mapa) mapa.setCenter([interpolado.longitud, interpolado.latitud], { duration: 0 });
      }
      cuadro = window.requestAnimationFrame(avanzar);
    };
    cuadro = window.requestAnimationFrame(avanzar);
    return () => window.cancelAnimationFrame(cuadro);
  }, [reproduciendo, velocidadReproduccion, posiciones, huecos, factor, seguir, mapa, sincronizarSlider]);

  // Resaltado del punto seleccionado. La superficie de selección es la capa de
  // acierto de línea que agrega Replay junto a la ruta; aquí solo vive el aro
  // del fix elegido. La capa se agrega una sola vez por mapa y Replay la sube
  // sobre la ruta al terminar de construir sus propias capas.
  useEffect(() => {
    if (!mapa) return;
    if (!mapa.getSource('replay-punto-sel')) {
      mapa.addSource('replay-punto-sel', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
    }
    if (!mapa.getLayer('replay-punto-activo')) {
      mapa.addLayer({
        id: 'replay-punto-activo',
        type: 'circle',
        source: 'replay-punto-sel',
        paint: {
          'circle-radius': 7,
          'circle-color': '#ffffff',
          'circle-stroke-color': '#eb0045',
          'circle-stroke-width': 2.5,
        },
      });
    }
  }, [mapa]);

  const coleccionSeleccion = useMemo<FeatureCollection<Point>>(() => {
    const fijado = seleccionado != null ? posiciones[seleccionado] ?? null : null;
    if (!fijado) return { type: 'FeatureCollection', features: [] };
    return {
      type: 'FeatureCollection',
      features: [
        {
          type: 'Feature',
          properties: {},
          geometry: { type: 'Point', coordinates: [fijado.longitud, fijado.latitud] },
        },
      ],
    };
  }, [posiciones, seleccionado]);

  useEffect(() => {
    if (!mapa) return;
    mapa.getSource<GeoJSONSource>('replay-punto-sel')?.setData(coleccionSeleccion);
  }, [mapa, coleccionSeleccion]);

  // Selección de un fix concreto: pausa, ubica el reloj y deja el punto
  // resaltado. Va en useCallback porque el manejador de clic del mapa se
  // suscribe una vez por instancia de mapa.
  const seleccionar = useCallback(
    (nuevoIndice: number) => {
      if (posiciones.length === 0) return;
      const acotado = Math.min(Math.max(nuevoIndice, 0), posiciones.length - 1);
      const fix = posiciones[acotado];
      indiceRef.current = acotado;
      indicePintadoRef.current = acotado;
      if (fix) instanteRef.current = milisegundos(fix.registradoEn);
      sincronizarSlider(instanteRef.current);
      setReproduciendo(false);
      setIndice(acotado);
      setSeleccionado(acotado);
    },
    [posiciones, sincronizarSlider],
  );

  const quitarSeleccion = useCallback(() => setSeleccionado(null), []);

  useEffect(() => {
    if (!mapa) return;
    // El clic se engancha a la capa de acierto de línea ('replay-linea-hit'),
    // una copia ancha y casi transparente de la ruta que Replay agrega con sus
    // capas. El enlace puede registrarse antes de que la capa exista (los
    // efectos del hijo corren antes que los del padre), pero maplibre filtra
    // las capas inexistentes en el momento del evento, así que queda válido en
    // cuanto Replay la crea. Al no depender de acertar un fix, la selección
    // funciona en cualquier punto del trazo y sobre los chevrones.
    const alPulsar = (evento: MapMouseEvent) => {
      const indice = indiceMasCercano(posiciones, evento.lngLat.lng, evento.lngLat.lat);
      if (indice == null) return;
      seleccionar(indice);
    };
    const alEntrar = () => {
      mapa.getCanvas().style.cursor = 'pointer';
    };
    const alSalir = () => {
      mapa.getCanvas().style.cursor = '';
    };
    mapa.on('click', 'replay-linea-hit', alPulsar);
    mapa.on('mouseenter', 'replay-linea-hit', alEntrar);
    mapa.on('mouseleave', 'replay-linea-hit', alSalir);
    return () => {
      mapa.off('click', 'replay-linea-hit', alPulsar);
      mapa.off('mouseenter', 'replay-linea-hit', alEntrar);
      mapa.off('mouseleave', 'replay-linea-hit', alSalir);
    };
  }, [mapa, posiciones, seleccionar]);

  function moverA(nuevoIndice: number) {
    if (posiciones.length === 0) return;
    const acotado = Math.min(Math.max(nuevoIndice, 0), posiciones.length - 1);
    indiceRef.current = acotado;
    indicePintadoRef.current = acotado;
    setIndice(acotado);
    const fix = posiciones[acotado];
    if (fix) instanteRef.current = milisegundos(fix.registradoEn);
    sincronizarSlider(instanteRef.current);
  }

  function alternarReproduccion() {
    if (posiciones.length < 2) return;
    if (reproduciendo) {
      pausar();
      return;
    }
    // Si ya terminó, volver a reproducir arranca desde el principio.
    if (indiceRef.current >= posiciones.length - 1) moverA(0);
    setReproduciendo(true);
  }

  // Pausa sincronizando el índice de la interfaz con el reloj: el estado puede
  // ir hasta INTERVALO_PINTADO_MS por detrás del bucle y, sin esta corrección,
  // el marcador retrocedería al pausar. No toca instanteRef para conservar la
  // fracción de tramo y reanudar exactamente donde se pausó.
  function pausar() {
    setReproduciendo(false);
    indicePintadoRef.current = indiceRef.current;
    setIndice(indiceRef.current);
  }

  function saltarHueco(direccion: 1 | -1) {
    if (!punto) return;
    const instante = milisegundos(punto.registradoEn);
    const candidatos =
      direccion === 1
        ? huecos.filter((hueco) => milisegundos(hueco.desde) > instante)
        : huecos.filter((hueco) => milisegundos(hueco.desde) < instante);
    const elegido = direccion === 1 ? candidatos[0] : candidatos[candidatos.length - 1];
    if (elegido) moverA(indicePorInstante(posiciones, milisegundos(elegido.desde)));
  }

  // Salto desde la lista de paradas: se pausa, igual que al arrastrar la barra,
  // porque el objetivo es leer la parada y no seguir corriendo desde ella.
  // indicePorInstante ubica el último fix anterior al instante pedido.
  function irAInstante(instante: number) {
    if (posiciones.length === 0) return;
    pausar();
    moverA(indicePorInstante(posiciones, instante));
  }

  // Salto a una parada concreta: pausa, ubica el reloj, deja seleccionado el
  // fix (la ficha muestra su detalle) y centra el mapa en la parada. Es el
  // mismo gesto que elegir un colaborador en En vivo: el mapa te lleva al
  // punto sin tener que buscarlo.
  function irAParada(latitud: number, longitud: number, instante: number) {
    if (posiciones.length === 0) return;
    irAInstante(instante);
    setSeleccionado(indiceRef.current);
    if (mapa) mapa.easeTo({ center: [longitud, latitud], duration: 350 });
  }

  const estado: Reproductor = {
    posiciones,
    huecos,
    dispositivo,
    indice: indiceAcotado,
    punto,
    reproduciendo,
    velocidad: velocidadReproduccion,
    seguir,
    seleccionado,
    sliderRef,
    alternar: alternarReproduccion,
    alternarSeguir: () => setSeguir((activo) => !activo),
    saltarHueco,
    cambiarVelocidad: (valor) => setVelocidadReproduccion(valor),
    mover: moverA,
    pausar,
    irA: irAInstante,
    irAPunto: irAParada,
    seleccionar,
    quitarSeleccion,
  };

  return <ContextoReproductor.Provider value={estado}>{children}</ContextoReproductor.Provider>;
}

function useReproductor(): Reproductor {
  const reproductor = useContext(ContextoReproductor);
  if (!reproductor) {
    throw new Error('Los bloques de Replay van dentro de ReproductorReplay.');
  }
  return reproductor;
}

// Dirección bajo demanda por coordenada redondeada: react-query deduplica la
// consulta por clave y el caché de datos.ts evita llamar dos veces al geocoder
// cuando la misma parada vuelve a pedirse. Sin habilitar, no hay consulta.
function useDireccion(lat: number | null, lon: number | null, habilitada: boolean): string | null {
  const consulta = useQuery({
    queryKey: ['geocode', lat == null ? null : lat.toFixed(5), lon == null ? null : lon.toFixed(5)],
    queryFn: async () => {
      if (lat == null || lon == null) return { direccion: null };
      return traerDireccion(lat, lon);
    },
    enabled: habilitada && lat != null && lon != null,
    retry: false,
    staleTime: Infinity,
  });
  return consulta.data?.direccion ?? null;
}

// Gráfico de batería de la franja inferior: la serie completa en línea fina y
// un segundo dataset con un único punto no nulo en la posición actual, que se
// mueve con la reproducción. Sin porcentajes en las posiciones no se dibuja.
function GraficoBateria() {
  const { posiciones, indice, pausar, mover } = useReproductor();
  const serie = useMemo(() => serieBateria(posiciones), [posiciones]);
  const hayBateria = useMemo(() => serie.some((valor) => valor != null), [serie]);
  const datos = useMemo<ChartData<'line'>>(() => {
    const indicePunto = indiceBateriaConocida(serie, indice);
    // Navy apagado en vez del azul anterior: el gráfico es contexto del
    // recorrido y no debe leerse como una barra azul junto al slider ni
    // competir con los colores de estado del marcador y la leyenda.
    return {
      labels: serie.map((_, posicion) => posicion),
      datasets: [
        {
          data: serie,
          borderColor: '#0b2545',
          backgroundColor: 'rgba(10, 37, 64, .1)',
          borderWidth: 1.5,
          pointRadius: 0,
          fill: true,
          spanGaps: true,
        },
        {
          data: serie.map((valor, posicion) => (posicion === indicePunto ? valor : null)),
          borderColor: '#0b2545',
          backgroundColor: '#0b2545',
          pointRadius: 3,
          pointHoverRadius: 3,
          showLine: false,
        },
      ],
    };
  }, [serie, indice]);
  // Clic en el gráfico = saltar a ese instante, igual que arrastrar el slider:
  // pausa y mueve el reproductor. El cursor cambia solo sobre la serie.
  const opciones = useMemo<ChartOptions<'line'>>(
    () => ({
      ...OPCIONES_BATERIA,
      onClick: (_evento, elementos) => {
        const posicion = elementos[0]?.index;
        if (posicion == null) return;
        pausar();
        mover(posicion);
      },
      onHover: (evento, elementos) => {
        const destino = evento.native?.target as HTMLElement | null;
        if (destino) destino.style.cursor = elementos.length > 0 ? 'pointer' : '';
      },
    }),
    [mover, pausar],
  );
  if (!hayBateria) return null;
  return (
    <div className="replay-bateria">
      <Line data={datos} options={opciones} />
    </div>
  );
}

// Detalle del fix seleccionado en el mapa: solo datos sencillos (hora,
// dirección, velocidad y batería), el estado del punto (movimiento, detención
// o sin señal) y si el equipo está habilitado, como el panel clásico. Las
// coordenadas, la precisión y la recepción en el servidor eran datos técnicos
// y se retiraron. Incluye acciones para volver al punto o soltar la selección.
export function PanelPuntoSeleccionado() {
  const { posiciones, huecos, dispositivo, seleccionado, mover, pausar, quitarSeleccion } = useReproductor();
  const punto = seleccionado != null ? posiciones[seleccionado] ?? null : null;
  const estado = useMemo(
    () => (seleccionado == null ? null : estadoDePunto(posiciones, huecos, seleccionado)),
    [posiciones, huecos, seleccionado],
  );
  const direccion = useDireccion(punto?.latitud ?? null, punto?.longitud ?? null, punto != null);
  if (!punto || seleccionado == null) {
    return (
      <section className="replay-punto">
        <h3>Detalles de ruta</h3>
        <p className="replay-nota">Pulsa un punto del recorrido o una parada para ver su detalle.</p>
      </section>
    );
  }
  return (
    <section className="replay-punto">
      <h3>Detalles de ruta</h3>
      <dl className="replay-ficha">
        <dt>Hora</dt>
        <dd>{fechaHora(punto.registradoEn)}</dd>
        <dt>Dirección</dt>
        <dd>{direccion ?? GUION}</dd>
        <dt>Velocidad</dt>
        <dd>{velocidad(punto.velocidadKmh)}</dd>
        <dt>Batería</dt>
        <dd>{bateria(punto.bateriaPct)}</dd>
        <dt>Estado</dt>
        <dd>{estado ? ETIQUETA_ESTADO_PUNTO[estado] : GUION}</dd>
        <dt>Equipo</dt>
        <dd>{dispositivo ? (dispositivo.habilitado ? 'Habilitado' : 'Deshabilitado') : GUION}</dd>
      </dl>
      <div className="replay-punto-acciones">
        <button
          type="button"
          className="suave"
          onClick={() => {
            pausar();
            mover(seleccionado);
          }}
        >
          Ir a este punto
        </button>
        <button type="button" className="suave" onClick={quitarSeleccion}>
          Quitar selección
        </button>
      </div>
    </section>
  );
}

// Fila de parada: inicio, duración, extremo del recorrido y dirección. El
// geocode se pide solo para la primera fila (referencia de la zona) y para la
// seleccionada; el resto muestra la dirección que ya trajo el servidor.
function FilaParada({
  parada,
  primera,
  ultima,
  activa,
  alPulsar,
}: {
  parada: Parada;
  primera: boolean;
  ultima: boolean;
  activa: boolean;
  alPulsar: () => void;
}) {
  const { irAPunto } = useReproductor();
  const pideDireccion = (primera || activa) && !parada.direccion;
  const geocodificada = useDireccion(
    pideDireccion ? parada.latitud : null,
    pideDireccion ? parada.longitud : null,
    pideDireccion,
  );
  const direccion = parada.direccion ?? geocodificada;
  const clases = [primera ? 'primera' : '', ultima ? 'ultima' : '', activa ? 'activa' : ''].filter(Boolean).join(' ');
  return (
    <li className={clases}>
      <button
        type="button"
        onClick={() => {
          alPulsar();
          irAPunto(parada.latitud, parada.longitud, milisegundos(parada.inicio));
        }}
        title={`${horaCorta(parada.inicio)} a ${horaCorta(parada.fin)}`}
      >
        <span className="parada-cabecera">
          <span className="parada-hora">{horaCorta(parada.inicio)}</span>
          {(primera || ultima) && <span className="parada-extremo">{primera ? 'Primera' : 'Última'}</span>}
          <span className="parada-tiempo">{duracion(parada.duracionMin * 60)}</span>
        </span>
        {direccion && <span className="parada-direccion">{direccion}</span>}
      </button>
    </li>
  );
}

// Lista de paradas plegable. `origen` distingue la segmentación del servidor
// del respaldo local para avisar de la caída sin ruido cuando todo va bien.
// Se listan todas las paradas cargadas: el cuerpo del panel tiene scroll
// propio, así que la lista ya no se recorta ni resume el resto en una línea.
export function ListaParadas({
  paradas,
  origen,
  total,
}: {
  paradas: Parada[];
  origen: 'servidor' | 'local';
  total?: number;
}) {
  const [abiertas, setAbiertas] = useState(false);
  const [activa, setActiva] = useState<number | null>(null);
  if (paradas.length === 0) return null;
  // El total del servidor puede superar las filas cargadas (tope de la
  // consulta): "y N más" cuenta lo que quedó fuera de la carga.
  const restantes = Math.max(total ?? paradas.length, paradas.length) - paradas.length;
  return (
    <section className="replay-paradas">
      <button
        type="button"
        className="replay-plegar-paradas"
        onClick={() => setAbiertas((valor) => !valor)}
        aria-expanded={abiertas}
      >
        Paradas ({paradas.length})
        <Icono nombre="flecha" />
      </button>
      {origen === 'local' && <p className="replay-nota">Calculadas localmente: la consulta de paradas no respondió.</p>}
      {abiertas && (
        <>
          <ul>
            {paradas.map((parada, posicion) => (
              <FilaParada
                key={`${parada.inicio}-${posicion}`}
                parada={parada}
                primera={posicion === 0}
                ultima={posicion === paradas.length - 1}
                activa={activa === posicion}
                alPulsar={() => setActiva(posicion)}
              />
            ))}
          </ul>
          {restantes > 0 && <p className="replay-nota">y {restantes} más</p>}
        </>
      )}
    </section>
  );
}

// Mandos del reproductor: play/pausa, seguir, saltos de hueco y velocidades.
// Van en la franja inferior, entre la lectura del punto y el slider, como
// estaban antes de moverlos al panel.
function ControlesReplay() {
  const {
    posiciones,
    huecos,
    reproduciendo,
    velocidad: velocidadReproduccion,
    seguir,
    alternar,
    alternarSeguir,
    saltarHueco,
    cambiarVelocidad,
  } = useReproductor();
  return (
    <div className="replay-controles">
      <button
        type="button"
        className="suave icono-solo"
        onClick={alternar}
        disabled={posiciones.length < 2}
        title={reproduciendo ? 'Pausar' : 'Reproducir'}
        aria-label={reproduciendo ? 'Pausar' : 'Reproducir'}
      >
        <Icono nombre={reproduciendo ? 'pausa' : 'play'} />
      </button>
      <button
        type="button"
        className={`suave icono-solo${seguir ? ' seguir-activo' : ''}`}
        onClick={alternarSeguir}
        disabled={posiciones.length < 2}
        title={seguir ? 'Dejar de seguir el punto' : 'Seguir el punto en el mapa'}
        aria-label={seguir ? 'Dejar de seguir el punto' : 'Seguir el punto en el mapa'}
        aria-pressed={seguir}
      >
        <Icono nombre="enVivo" />
      </button>
      <button
        type="button"
        className="suave icono-solo"
        onClick={() => saltarHueco(-1)}
        disabled={huecos.length === 0}
        title="Hueco anterior"
        aria-label="Hueco anterior"
      >
        <span className="voltear">
          <Icono nombre="flecha" />
        </span>
      </button>
      <button
        type="button"
        className="suave icono-solo"
        onClick={() => saltarHueco(1)}
        disabled={huecos.length === 0}
        title="Hueco siguiente"
        aria-label="Hueco siguiente"
      >
        <Icono nombre="flecha" />
      </button>
      <div className="velocidades">
        {VELOCIDADES.map((valor) => (
          <button
            key={valor}
            type="button"
            className={`suave${velocidadReproduccion === valor ? ' velocidad-activa' : ''}`}
            onClick={() => cambiarVelocidad(valor)}
            title={`Velocidad ${valor}×`}
            aria-pressed={velocidadReproduccion === valor}
          >
            {valor}×
          </button>
        ))}
      </div>
    </div>
  );
}

// Franja inferior: gráfico de batería con la lectura del punto en curso, los
// mandos y el slider sobre la línea de tiempo del recorrido. Arrastrar el
// slider pausa la reproducción, que es la misma acción manual que los saltos.
export function LineaTiempoReplay() {
  const { posiciones, punto, sliderRef, mover, pausar } = useReproductor();
  const primera = posiciones[0] ?? null;
  const ultima = posiciones[posiciones.length - 1] ?? null;
  const inicio = primera ? milisegundos(primera.registradoEn) : 0;
  const fin = ultima ? milisegundos(ultima.registradoEn) : 0;
  const duracionTotal = Math.max(0, fin - inicio);
  // El slider trabaja en tiempo, no en índices: los fixes llegan espaciados de
  // forma irregular y así el avance concuerda con la lectura y el gráfico. Es
  // no controlado: el contenedor escribe su valor y su relleno de progreso por
  // frame durante la reproducción (ver ReproductorReplay), y aquí solo se
  // atiende el arrastre del usuario.
  return (
    <div className="replay-linea">
      <div className="replay-lectura">
        <GraficoBateria />
        <span className="replay-tiempos">
          <strong>{punto ? horaCorta(punto.registradoEn) : GUION}</strong> · {velocidad(punto?.velocidadKmh)} ·{' '}
          {bateria(punto?.bateriaPct)}
        </span>
      </div>
      <ControlesReplay />
      <input
        ref={sliderRef}
        type="range"
        className="replay-slider"
        min={0}
        max={duracionTotal}
        defaultValue={0}
        disabled={posiciones.length < 2}
        aria-label="Posición del recorrido"
        onChange={(evento) => {
          pausar();
          mover(indicePorInstante(posiciones, inicio + Number(evento.target.value)));
        }}
      />
    </div>
  );
}
