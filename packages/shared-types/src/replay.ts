import type { Dispositivo } from "./fleet";
import type { Posicion } from "./positions";

export type MotivoHueco = "SIN_SENAL" | "SIN_DATOS" | "PAUSA" | "APAGADO";

export interface Hueco {
  desde: string;
  hasta: string;
  duracionSegundos: number;
  motivo: MotivoHueco;
}

/** Cómo se obtuvo el tramo reconstruido: ajustado a vía o estimado A→B. */
export type MetodoReconstruccion = "MATCHED" | "ESTIMATED";

/**
 * Tramo reconstruido por calles entre dos fixes muy separados. MATCHED trae
 * fixes intermedios ajustados a vía (/match); ESTIMATED es ruta A→B (/route).
 * mapaVersion es el SHA-256 del PBF de GraphHopper (null si no se conoce).
 * Nunca se presenta como GPS registrado: la web lo dibuja punteado/rotulado.
 */
export interface TramoReconstruido {
  desde: string;
  hasta: string;
  metodo: MetodoReconstruccion;
  mapaVersion: string | null;
  trazado: [number, number][];
}

export interface ReplayResumen {
  inicio: string;
  fin: string;
  totalPosiciones: number;
  totalHuecos: number;
  distanciaKm: number;
  duracionMin: number;
  velocidadPromedioKmh: number | null;
  velocidadMaximaKmh: number | null;
  bateriaInicialPct: number | null;
  bateriaFinalPct: number | null;
}

export interface Replay {
  dispositivo: Dispositivo;
  desde: string;
  hasta: string;
  posiciones: Posicion[];
  huecos: Hueco[];
  reconstruidos: TramoReconstruido[];
  resumen: ReplayResumen;
  generadoEn: string;
}

export interface ReplayDisponible {
  dispositivoId: number;
  idPublico: string;
  nombre: string;
  desde: string;
  hasta: string;
  totalPosiciones: number;
}
