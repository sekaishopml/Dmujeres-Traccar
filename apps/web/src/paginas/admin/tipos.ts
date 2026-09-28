// DTOs de sistema que todavía no viven en @contratos (packages/shared-types no
// tiene config.ts). Se espejan tal cual del openapi.json para no usar `any` y
// mantener los mismos nombres de campo que la API.
import type { Dispositivo, Entorno, PuntoGeo, Usuario } from '@contratos';

export interface MapaConfig {
  estiloUrl: string;
  centroInicial: PuntoGeo;
  zoomInicial: number;
}

export interface CapacidadesConfig {
  replay: boolean;
  reportes: boolean;
  sse: boolean;
  websocket: boolean;
  exportacion: boolean;
}

export interface Configuracion {
  versionApi: string;
  entorno: Entorno;
  zonaHoraria: string;
  intervaloRefrescoSegundos: number;
  mapa: MapaConfig;
  capacidades: CapacidadesConfig;
}

export interface Salud {
  estado: 'ok';
}

export interface DependenciasSalud {
  baseDatos: 'ok' | 'error';
  tracking: 'ok' | 'error';
}

export interface Disponibilidad {
  estado: 'listo' | 'degradado';
  dependencias: DependenciasSalud;
  comprobadoEn: string;
}

export interface Version {
  version: string;
  versionApi: string;
  versionEsquema: string;
  commit: string;
  construidoEn: string;
}

// --- Gestión (FASE 4b): campos de escritura del contrato de usuarios y flota ---

export type ClaveConfiguracionEquipo =
  | 'mobile.intervalSeconds'
  | 'mobile.minIntervalSeconds'
  | 'mobile.distanceMeters'
  | 'mobile.angleDegrees'
  | 'mobile.accuracy'
  | 'mobile.bufferEnabled'
  | 'mobile.bufferMax'
  | 'mobile.bufferPolicy'
  | 'mobile.ackTimeoutSeconds'
  | 'mobile.maxRetries';

export type ValorConfiguracionEquipo = number | boolean | string | null;

// Whitelist de `PUT /fleet/{id}`: los campos ausentes no se inventan.
export interface ConfiguracionEquipo {
  'mobile.intervalSeconds'?: number;
  'mobile.minIntervalSeconds'?: number;
  'mobile.distanceMeters'?: number;
  'mobile.angleDegrees'?: number;
  'mobile.accuracy'?: string;
  'mobile.bufferEnabled'?: boolean;
  'mobile.bufferMax'?: number;
  'mobile.bufferPolicy'?: string;
  'mobile.ackTimeoutSeconds'?: number;
  'mobile.maxRetries'?: number;
}

// El Omit evita choques si @contratos incorpora estos campos en paralelo: los
// DTO de escritura se tipan aquí sin tocar packages/shared-types.
export interface UsuarioGestion extends Omit<Usuario, 'dispositivoIds'> {
  dispositivoIds: string[];
}

export interface DispositivoGestion extends Omit<Dispositivo, 'configuracion'> {
  configuracion?: ConfiguracionEquipo | null;
}
