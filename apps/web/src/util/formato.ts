// Formato de presentación: nada de datos inventados, campo ausente = "—".
const FECHA_HORA = new Intl.DateTimeFormat('es-EC', { dateStyle: 'short', timeStyle: 'short' });
const HORA = new Intl.DateTimeFormat('es-EC', { hour: '2-digit', minute: '2-digit' });
const FECHA = new Intl.DateTimeFormat('es-EC', { dateStyle: 'medium' });

export const GUION = '—';

export function fechaHora(valor?: string | null): string {
  return valor ? FECHA_HORA.format(new Date(valor)) : GUION;
}

export function hora(valor?: string | null): string {
  return valor ? HORA.format(new Date(valor)) : GUION;
}

export function fecha(valor?: string | null): string {
  return valor ? FECHA.format(new Date(valor)) : GUION;
}

export function hace(valor?: string | null): string {
  if (!valor) return GUION;
  const minutos = Math.round((Date.now() - new Date(valor).getTime()) / 60000);
  if (!Number.isFinite(minutos)) return GUION;
  if (minutos < 1) return 'ahora';
  if (minutos < 60) return `hace ${minutos} min`;
  const horas = Math.floor(minutos / 60);
  if (horas < 24) return `hace ${horas} h`;
  return `hace ${Math.floor(horas / 24)} d`;
}

export function duracion(segundos?: number | null): string {
  if (segundos == null || !Number.isFinite(segundos)) return GUION;
  const total = Math.max(0, Math.round(segundos));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  return h > 0 ? `${h} h ${m} min` : `${m} min`;
}

export function kilometros(metros?: number | null): string {
  if (metros == null || !Number.isFinite(metros)) return GUION;
  const km = metros / 1000;
  return km >= 100 ? `${Math.round(km)} km` : `${km.toFixed(1)} km`;
}

export function velocidad(kmh?: number | null): string {
  return kmh == null || !Number.isFinite(kmh) ? GUION : `${kmh.toFixed(1)} km/h`;
}

export function bateria(pct?: number | null): string {
  return pct == null || !Number.isFinite(pct) ? GUION : `${Math.round(pct)}%`;
}

// Estados operativos: mismos nombres que la app y el panel anterior.
export const ETIQUETA_ESTADO: Record<string, string> = {
  deshabilitado: 'DESHABILITADO',
  sinSenal: 'SIN CONEXIÓN',
  senalDebil: 'SEÑAL DÉBIL',
  detenido: 'DETENIDO',
  enLinea: 'EN LÍNEA',
};

export const COLOR_ESTADO: Record<string, string> = {
  deshabilitado: '#8A8A8A',
  sinSenal: '#ED6C02',
  senalDebil: '#ED6C02',
  detenido: '#0288D1',
  enLinea: '#2E7D32',
};
