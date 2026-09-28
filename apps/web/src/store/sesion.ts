import { create } from 'zustand';
import { api } from '../api/cliente';
import type { Usuario } from '@contratos';

interface EstadoSesion {
  usuario: Usuario | null;
  cargando: boolean;
  cargar: () => Promise<Usuario | null>;
  entrar: (correo: string, clave: string) => Promise<void>;
  salir: () => Promise<void>;
}

// La sesión real es la cookie dmj_sesion; aquí solo se cachea el usuario para
// pintar la interfaz. Si la cookie expira, la API responde 401 y el cliente
// redirige al login.
export const useSesion = create<EstadoSesion>((set) => ({
  usuario: null,
  cargando: true,
  cargar: async () => {
    set({ cargando: true });
    try {
      // GET /api/v1/auth/me devuelve el Usuario directo (contrato API-V1).
      const usuario = await api.get<Usuario>('/api/v1/auth/me');
      set({ usuario, cargando: false });
      return usuario;
    } catch {
      set({ usuario: null, cargando: false });
      return null;
    }
  },
  entrar: async (correo, clave) => {
    // El contrato /api/v1/auth/login usa los campos {usuario, clave}.
    const datos = await api.post<{ usuario: Usuario }>('/api/v1/auth/login', {
      usuario: correo.trim(),
      clave,
    });
    set({ usuario: datos.usuario, cargando: false });
  },
  salir: async () => {
    try {
      await api.post('/api/v1/auth/logout');
    } finally {
      set({ usuario: null });
      window.location.href = '/login';
    }
  },
}));
