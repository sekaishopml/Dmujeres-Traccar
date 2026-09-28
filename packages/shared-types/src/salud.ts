// Salud de la flota (GET /api/v1/salud). Derivada en el servidor de
// posiciones/eventos/atributos; los campos que la app aún no reporta viajan
// como null (nunca se inventan).

export type EstadoSalud =
  | "HEALTHY"
  | "DEGRADED"
  | "OFFLINE"
  | "RECOVERING"
  | "MISCONFIGURED";

export interface PermisosSalud {
  fondo: boolean | null;
  fina: boolean | null;
}

export interface SaludDispositivo {
  dispositivoId: number;
  estado: EstadoSalud;
  /** Causa legible en español (nunca un OFFLINE mudo). */
  causa: string;
  lastFixAgeS: number | null;
  uploadLagS: number | null;
  captureGapS: number | null;
  bufferDepth: number | null;
  bateriaPct: number | null;
  cargando: boolean | null;
  gps: string | null;
  permisos: PermisosSalud | null;
  bateriaExenta: boolean | null;
  fgs: string | null;
  jornada: string | null;
  red: string | null;
  bootId: string | null;
  recoveryCount: number | null;
  appVersion: string | null;
  android: string | null;
  fabricante: string | null;
  modelo: string | null;
}

export interface RespuestaSalud {
  datos: SaludDispositivo[];
}
