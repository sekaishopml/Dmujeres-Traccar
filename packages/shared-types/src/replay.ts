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

/**
 * Fixes apartados del trazado por imposibles (el crudo en base no cambia):
 * fuera del área operativa (mock/emulador) o picos de ida y vuelta a más de
 * 180 km/h. `posibleOrigenMultiple` avisa de saltos largos repetidos: dos
 * teléfonos reportando con la misma cuenta.
 */
export interface ReplayCalidad {
  descartadasFueraDeZona: number;
  descartadasSalto: number;
  posibleOrigenMultiple: boolean;
}

export interface Replay {
  dispositivo: Dispositivo;
  desde: string;
  hasta: string;
  posiciones: Posicion[];
  huecos: Hueco[];
  reconstruidos: TramoReconstruido[];
  resumen: ReplayResumen;
  calidad?: ReplayCalidad;
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
