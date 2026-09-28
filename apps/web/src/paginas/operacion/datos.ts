import { api, consulta } from '../../api/cliente';
import type { OpcionesPeticion } from '../../api/cliente';
import type { Dispositivo, Pagina, Posicion, PosicionesVivas, Replay, ReporteParada } from '@contratos';
import type { RespuestaJornadasFlota, RespuestaSalud } from '@contratos';

// La flota la consumen Inicio, En vivo, Historial, Replay y Detalle: una sola
// clave de caché mantiene los datos coherentes entre páginas.
export const CLAVE_FLOTA = ['flota'] as const;

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
  return api.get<RespuestaJornadasFlota>(
    `/api/v1/journeys${consulta({ desde, hasta, dispositivoId, tamano: 200 })}`,
    opciones,
  );
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

export interface RespuestaDireccion {
  direccion: string | null;
}

// Caché de direcciones por coordenada redondeada a 5 decimales (~1 m). El
// geocodificador inverso es un servicio externo y una misma parada se consulta
// al abrir la lista y al seleccionarla: el caché evita repetir la llamada.
const direccionesPorCoordenada = new Map<string, string | null>();

export async function traerDireccion(lat: number, lon: number): Promise<RespuestaDireccion> {
  const clave = `${lat.toFixed(5)},${lon.toFixed(5)}`;
  const cacheada = direccionesPorCoordenada.get(clave);
  if (cacheada !== undefined) return { direccion: cacheada };
  const respuesta = await api.get<RespuestaDireccion>(`/api/v1/geocode/reverse${consulta({ lat, lon })}`);
  const direccion = respuesta?.direccion ?? null;
  // Solo se guarda la respuesta recibida, aunque venga null: es una respuesta
  // válida del servicio. Un fallo de red no entra al caché para poder
  // reintentarlo al volver a seleccionar el punto.
  direccionesPorCoordenada.set(clave, direccion);
  return { direccion };
}
