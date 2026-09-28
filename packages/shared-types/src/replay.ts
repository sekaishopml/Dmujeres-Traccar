import type { Dispositivo } from "./fleet";
import type { Posicion } from "./positions";

export type MotivoHueco = "SIN_SENAL" | "SIN_DATOS" | "PAUSA" | "APAGADO";

export interface Hueco {
  desde: string;
  hasta: string;
  duracionSegundos: number;
  motivo: MotivoHueco;
}

/**
 * Tramo dibujado con el camino estimado por calles: une dos fixes consecutivos
 * que quedaron muy separados (huecos o cadencia lenta). Es una estimación para
 * el dibujo; las posiciones registradas no cambian.
 */
export interface SegmentoEstimado {
  desde: string;
  hasta: string;
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
  estimados: SegmentoEstimado[];
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
