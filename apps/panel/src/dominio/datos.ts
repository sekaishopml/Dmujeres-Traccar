import type { QueryClient } from '@tanstack/react-query';
import { api, consulta } from '@/lib/api';
import type { OpcionesPeticion } from '@/lib/api';
import type { Bateria, Dispositivo, Pagina, Posicion, PosicionesVivas, Replay, ReporteParada } from '@contratos';
import type { RespuestaJornadasFlota, RespuestaSalud } from '@contratos';
import type {
  EntradaEsquemaAjustes,
  GrupoPlataforma,
  RolPlataforma,
  UsuarioPlataforma,
} from '@contratos';

// La flota la consumen Inicio, En vivo, Historial, Replay y Detalle: una sola
// clave de caché mantiene los datos coherentes entre páginas.
export const CLAVE_FLOTA = ['flota'] as const;

// Consultas de auditoría (replay, paradas, jornadas, expedientes, reportes):
// traen ventanas completas de datos y son estables dentro de la misma consulta.
// 30 s evita releerlas al ir y volver entre Historial, Replay y Reportes sin
// retrasar la vista del día en curso más que un sondeo de Inicio. Los sondeos
// (refetchInterval) no dependen del staleTime: dejan intactas las cadencias de
// Inicio, En vivo, Detalle y Sistema.
export const CACHE_AUDITORIA_MS = 30_000;

// Defensa en profundidad: el servidor ya omite los equipos dados de baja
// (habilitado=false) en /fleet, pero la caché puede conservar una respuesta
// anterior al dar de baja una cuenta y los endpoints vivos podrían devolver un
// equipo recién deshabilitado. Todo listado, selector o marcador que parta de
// la flota pasa por aquí para que un equipo deshabilitado nunca se muestre.
export function equiposHabilitados(dispositivos: Dispositivo[]): Dispositivo[] {
  return dispositivos.filter((equipo) => equipo.habilitado !== false);
}

// Invalida la caché compartida de la flota (CLAVE_FLOTA). Se usa tras crear
// una cuenta con equipo: /fleet se vuelve a leer cuando el panel lo consulte y
// el equipo nuevo aparece en En vivo, Replay e Inicio sin esperar al sondeo.
export function invalidarFlota(cliente: QueryClient): Promise<void> {
  return cliente.invalidateQueries({ queryKey: CLAVE_FLOTA });
}

export function traerFlota(opciones?: OpcionesPeticion): Promise<Pagina<Dispositivo>> {
  return api.get<Pagina<Dispositivo>>(`/api/v1/fleet${consulta({ tamano: 200 })}`, opciones);
}

export function traerPosicionesVivas(opciones?: OpcionesPeticion): Promise<PosicionesVivas> {
  return api.get<PosicionesVivas>('/api/v1/positions/live', opciones);
}

export function traerDispositivo(id: string, opciones?: OpcionesPeticion): Promise<Dispositivo> {
  return api.get<Dispositivo>(`/api/v1/fleet/${encodeURIComponent(id)}`, opciones);
}

export function traerUltimaPosicion(id: string, opciones?: OpcionesPeticion): Promise<Posicion> {
  return api.get<Posicion>(`/api/v1/fleet/${encodeURIComponent(id)}/position`, opciones);
}

// Respuesta de GET /api/v1/replay/{id}: `reconstruidos` es el contrato
// vigente (ADR-007, con método y versión de mapa, definido en el paquete
// compartido por el equipo servidor); `estimados` se conserva mientras el
// servidor aún lo devuelva, como compatibilidad temporal.
export interface RespuestaReplay extends Replay {
  estimados?: { desde: string; hasta: string; trazado: [number, number][] }[];
}

export function traerReplay(id: string, desde: string, hasta: string): Promise<RespuestaReplay> {
  return api.get<RespuestaReplay>(`/api/v1/replay/${encodeURIComponent(id)}${consulta({ desde, hasta })}`);
}

export interface Jornada {
  inicioEn: string;
  // El fin es nulo mientras la jornada sigue abierta; duracionMin también
  // puede faltar en ese caso.
  finEn: string | null;
  duracionMin: number | null;
  abierta: boolean;
}

export interface RespuestaJornadas {
  jornadas: Jornada[];
  total: number;
}

// Jornadas (encendido/apagado) de un equipo en la ventana pedida. El endpoint
// es nuevo y puede no existir todavía: Replay trata 404 o error como "sin
// dato" y oculta el resultado, sin tumbar el resto de la pantalla.
export function traerJornadas(idPublico: string, desde: string, hasta: string): Promise<RespuestaJornadas> {
  return api.get<RespuestaJornadas>(
    `/api/v1/fleet/${encodeURIComponent(idPublico)}/journeys${consulta({ desde, hasta })}`,
  );
}

// Jornadas de todos los equipos visibles en la ventana: alimenta la auditoría
// (día completo y expediente por unidad). Ventana cerrada con total 0 no es
// error; significa "sin jornadas ese día".
export function traerJornadasFlota(
  desde: string,
  hasta: string,
  dispositivoId?: string,
  opciones?: OpcionesPeticion,
): Promise<RespuestaJornadasFlota> {
  return traerTodasLasJornadas(desde, hasta, dispositivoId, opciones);
}

// La API pagina de a 200 como máximo: se piden todas las páginas para que las
// cifras de Historial (jornadas, tiempo total) no se corten sin aviso.
const TAMANO_PAGINA_JORNADAS = 200;
const MAX_PAGINAS_JORNADAS = 50;

async function traerTodasLasJornadas(
  desde: string,
  hasta: string,
  dispositivoId: string | undefined,
  opciones: OpcionesPeticion | undefined,
): Promise<RespuestaJornadasFlota> {
  const pedir = (pagina: number) =>
    api.get<RespuestaJornadasFlota>(
      `/api/v1/journeys${consulta({ desde, hasta, dispositivoId, tamano: TAMANO_PAGINA_JORNADAS, pagina })}`,
      opciones,
    );
  const primera = await pedir(1);
  const datos = [...primera.datos];
  const total = primera.total ?? datos.length;
  for (let pagina = 2; datos.length < total && pagina <= MAX_PAGINAS_JORNADAS; pagina++) {
    const siguiente = await pedir(pagina);
    if (siguiente.datos.length === 0) break;
    datos.push(...siguiente.datos);
  }
  return { ...primera, datos };
}

// Paradas del servidor: segmentación precisa de la plataforma (>= 3 min y por
// debajo de 5 km/h), con dirección resuelta cuando existe. Replay la usa como
// fuente principal y solo cae al helper local si esta consulta falla.
export function traerParadas(idPublico: string, desde: string, hasta: string): Promise<Pagina<ReporteParada>> {
  return api.get<Pagina<ReporteParada>>(
    `/api/v1/reports/stops${consulta({ dispositivoId: idPublico, desde, hasta, tamano: 200 })}`,
  );
}

// Salud por equipo (GET /api/v1/salud, ADR-009): estado derivado en el
// servidor con su causa (tipos en el paquete compartido). Si el endpoint aún
// no está desplegado, la vista trata el 404 como "sin dato" con estados de
// carga/error/vacío correctos.

// Sondeo de fondo: un 401 aquí no redirige, solo deja el estado de error para
// que la comprobación de sesión decida.
export function traerSalud(): Promise<RespuestaSalud> {
  return api.get<RespuestaSalud>('/api/v1/salud', { redirigir401: false });
}

// --- Plataforma (usuarios, grupos, roles y esquema de ajustes) ---
//
// Contrato nuevo que otro frente implementa en paralelo: cada función pide
// exactamente su endpoint (/api/v1/usuarios, /api/v1/grupos, /api/v1/roles,
// /api/v1/configuracion/esquema). Si el endpoint aún no existe, la promesa
// se rechaza y la pantalla muestra el error sin romper el resto del panel.
//
// La lista de usuarios del contrato es un arreglo simple. Si el servidor
// responde temporalmente con una página {datos}, se acepta igual para no
// dejar la tabla vacía durante la transición.
function comoArreglo<T>(respuesta: T[] | { datos?: T[] } | null | undefined): T[] {
  if (Array.isArray(respuesta)) return respuesta;
  if (respuesta && Array.isArray((respuesta as { datos?: T[] }).datos)) {
    return (respuesta as { datos: T[] }).datos;
  }
  return [];
}

export async function traerUsuariosPlataforma(opciones?: OpcionesPeticion): Promise<UsuarioPlataforma[]> {
  const respuesta = await api.get<UsuarioPlataforma[] | { datos: UsuarioPlataforma[] }>(
    '/api/v1/usuarios',
    opciones,
  );
  return comoArreglo(respuesta);
}

export async function traerGrupos(opciones?: OpcionesPeticion): Promise<GrupoPlataforma[]> {
  const respuesta = await api.get<GrupoPlataforma[] | { datos: GrupoPlataforma[] }>(
    '/api/v1/grupos',
    opciones,
  );
  return comoArreglo(respuesta);
}

export async function traerRoles(opciones?: OpcionesPeticion): Promise<RolPlataforma[]> {
  const respuesta = await api.get<RolPlataforma[] | { datos: RolPlataforma[] }>(
    '/api/v1/roles',
    opciones,
  );
  return comoArreglo(respuesta);
}

export async function traerEsquemaAjustes(opciones?: OpcionesPeticion): Promise<EntradaEsquemaAjustes[]> {
  const respuesta = await api.get<EntradaEsquemaAjustes[] | { datos: EntradaEsquemaAjustes[] }>(
    '/api/v1/configuracion/esquema',
    opciones,
  );
  return comoArreglo(respuesta);
}

export interface RespuestaDireccion {
  direccion: string | null;
  direccionAproximada?: boolean;
}

// Caché de direcciones por coordenada redondeada a 5 decimales (~1 m). El
// geocodificador inverso es un servicio externo y una misma parada se consulta
// al abrir la lista y al seleccionarla: el caché evita repetir la llamada.
const direccionesPorCoordenada = new Map<string, string | null>();

// La precisión del fix va al servidor: con precisión mala devuelve "Cerca de …"
// en lugar de afirmar una calle. Forma parte de la clave porque la misma
// coordenada puede resolverse distinto según cuánto se fíe del punto.
export async function traerDireccion(
  lat: number,
  lon: number,
  precisionM: number | null = null,
): Promise<RespuestaDireccion> {
  const clave = `${lat.toFixed(5)},${lon.toFixed(5)},${precisionM == null ? '' : Math.round(precisionM)}`;
  const cacheada = direccionesPorCoordenada.get(clave);
  if (cacheada !== undefined) return { direccion: cacheada };
  const respuesta = await api.get<RespuestaDireccion>(
    `/api/v1/geocode/reverse${consulta({ lat, lon, precision: precisionM ?? undefined })}`,
  );
  const direccion = respuesta?.direccion ?? null;
  // Solo se guarda la respuesta recibida, aunque venga null: es una respuesta
  // válida del servicio. Un fallo de red no entra al caché para poder
  // reintentarlo al volver a seleccionar el punto.
  direccionesPorCoordenada.set(clave, direccion);
  return { direccion };
}

// Muestras de batería de un equipo en la ventana (máximo 31 días): alimentan
// la curva de batería y los eventos de carga de la bitácora.
export function traerBateriaEquipo(idPublico: string, desde: string, hasta: string): Promise<Bateria> {
  return api.get<Bateria>(`/api/v1/battery/${encodeURIComponent(idPublico)}${consulta({ desde, hasta })}`);
}
