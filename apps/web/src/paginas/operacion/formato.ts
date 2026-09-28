import { GUION } from '../../util/formato';

// Helpers de presentación que no están en util/formato.ts. Todos devuelven
// GUION cuando el dato falta: nunca se inventan valores.

export function coordenadas(lat?: number | null, lon?: number | null): string {
  if (lat == null || lon == null || !Number.isFinite(lat) || !Number.isFinite(lon)) return GUION;
  return `${lat.toFixed(5)}, ${lon.toFixed(5)}`;
}

export function metros(valor?: number | null): string {
  return valor == null || !Number.isFinite(valor) ? GUION : `${Math.round(valor)} m`;
}

export function grados(valor?: number | null): string {
  return valor == null || !Number.isFinite(valor) ? GUION : `${Math.round(valor)}°`;
}

export function entero(valor?: number | null): string {
  return valor == null || !Number.isFinite(valor) ? GUION : String(Math.trunc(valor));
}

export function siNo(valor?: boolean | null): string {
  if (valor == null) return GUION;
  return valor ? 'Sí' : 'No';
}
