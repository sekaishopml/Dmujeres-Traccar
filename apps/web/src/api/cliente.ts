// Cliente único de la API /api/v1. Sin dependencias: fetch + manejo de errores.
// La sesión vive en una cookie HttpOnly; el navegador nunca ve credenciales
// internas. Un 401 redirige al login.

export interface ErrorApi {
  codigo: string;
  mensaje: string;
}

export class ApiError extends Error {
  codigo: string;
  estado: number;

  constructor(estado: number, codigo: string, mensaje: string) {
    super(mensaje);
    this.estado = estado;
    this.codigo = codigo;
  }
}

async function pedir<T>(metodo: string, ruta: string, cuerpo?: unknown): Promise<T> {
  const res = await fetch(ruta, {
    method: metodo,
    credentials: 'same-origin',
    headers: cuerpo ? { 'Content-Type': 'application/json' } : undefined,
    body: cuerpo ? JSON.stringify(cuerpo) : undefined,
  });
  if (res.status === 204) {
    return undefined as T;
  }
  const texto = await res.text();
  const datos = texto ? JSON.parse(texto) : null;
  if (!res.ok) {
    const error: ErrorApi = datos?.error ?? { codigo: 'ERROR_INTERNO', mensaje: `Error ${res.status}` };
    if (res.status === 401 && !ruta.endsWith('/auth/login') && !ruta.endsWith('/auth/me')) {
      window.location.href = '/login';
    }
    throw new ApiError(res.status, error.codigo, error.mensaje);
  }
  return datos as T;
}

export const api = {
  get: <T>(ruta: string) => pedir<T>('GET', ruta),
  post: <T>(ruta: string, cuerpo?: unknown) => pedir<T>('POST', ruta, cuerpo),
  put: <T>(ruta: string, cuerpo?: unknown) => pedir<T>('PUT', ruta, cuerpo),
  borrar: <T>(ruta: string) => pedir<T>('DELETE', ruta),
};

// Construye querystrings solo con parámetros definidos.
export function consulta(params: Record<string, string | number | undefined | null>): string {
  const q = new URLSearchParams();
  for (const [clave, valor] of Object.entries(params)) {
    if (valor !== undefined && valor !== null && valor !== '') {
      q.set(clave, String(valor));
    }
  }
  const texto = q.toString();
  return texto ? `?${texto}` : '';
}
