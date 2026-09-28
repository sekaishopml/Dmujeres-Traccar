import type { PuntoGeo, Rango } from "./common";

export interface ReporteViaje {
  id: number;
  dispositivoId: number;
  idPublico: string;
  inicio: string;
  fin: string;
  duracionMin: number;
  distanciaKm: number;
  velocidadPromedioKmh: number | null;
  velocidadMaximaKmh: number | null;
  origen: PuntoGeo;
  destino: PuntoGeo;
  paradas: number;
}

export interface ReporteParada {
  id: number;
  dispositivoId: number;
  idPublico: string;
  inicio: string;
  fin: string;
  duracionMin: number;
  latitud: number;
  longitud: number;
  direccion: string | null;
}

export interface ResumenDispositivo {
  dispositivoId: number;
  idPublico: string;
  nombre: string;
  distanciaKm: number;
  duracionMin: number;
  viajes: number;
  paradas: number;
  ultimaPosicionEn: string | null;
}

export interface ResumenReporte {
  desde: string;
  hasta: string;
  dispositivos: number;
  posiciones: number;
  distanciaTotalKm: number;
  duracionTotalMin: number;
  viajes: number;
  paradas: number;
  porDispositivo: ResumenDispositivo[];
}

export interface FiltroReportes extends Rango {
  dispositivoId?: string;
  pagina?: number;
  tamano?: number;
  orden?: string;
}
