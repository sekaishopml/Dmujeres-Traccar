// Tipos de la plataforma (usuarios, grupos, roles y esquema de ajustes).
// Espejan el contrato del servidor tal cual: GET /api/v1/usuarios, /grupos,
// /roles y /configuracion/esquema. Los campos marcados como opcionales son
// los que el contrato marca con "?" o los que el servidor puede no devolver
// todavía mientras el otro frente los implementa; la web los trata como
// ausentes sin romper la pantalla.

export interface GrupoResumen {
  id: number | string;
  nombre: string;
}

export interface RolPlataforma {
  id: number | string;
  nombre: string;
}

export type ConfigApp = Record<string, number | boolean | string | null>;

export interface UsuarioPlataforma {
  id: number | string;
  idPublico: string;
  /** Nombre con el que la persona entra al panel. */
  usuario: string;
  /** Nombre completo para mostrar. */
  nombre: string;
  correo?: string | null;
  telefono?: string | null;
  cargo?: string | null;
  habilitado: boolean;
  administrador: boolean;
  grupos: GrupoResumen[];
  configApp: ConfigApp;
  // Equipos que la cuenta puede ver. El servidor puede no devolverlo todavía
  // mientras el otro frente implementa GET /api/v1/usuarios/:id/equipos; la
  // web lo trata como ausente y pide ese endpoint como respaldo.
  dispositivoIds?: (number | string)[];
  // El contrato de lectura no devuelve los roles, pero si el servidor los
  // incluye en el futuro se muestran sin cambiar la pantalla.
  roles?: RolPlataforma[];
  rolIds?: (number | string)[];
}

export interface CreacionUsuarioPlataforma {
  usuario: string;
  clave: string;
  nombre: string;
  telefono?: string;
  cargo?: string;
  grupoIds?: (number | string)[];
  rolIds?: (number | string)[];
  // Las cuentas de administración nacen con este indicador en fijo; las
  // personas de campo no lo mandan.
  administrador?: boolean;
  configApp?: ConfigApp;
  // Pide al servidor que cree el equipo de rastreo junto con la cuenta:
  // dispositivo con identificador = usuario en minúsculas + asignación.
  // El servidor responde `equipo` o null; si aún no lo soporta, la cuenta
  // se crea igual y la web avisa.
  crearEquipo?: boolean;
}

// Equipo creado junto con la cuenta (POST /api/v1/usuarios con
// `crearEquipo:true`). `identificador` es el nombre que se configura en la
// app como ID de equipo y el que se ve en Replay y En vivo.
export interface EquipoCreadoConCuenta {
  id: number | string;
  idPublico?: string;
  nombre: string;
  identificador: string;
}

export interface RespuestaCreacionUsuarioPlataforma {
  usuario: UsuarioPlataforma;
  equipo?: EquipoCreadoConCuenta | null;
}

export interface ActualizacionUsuarioPlataforma {
  usuario?: string;
  clave?: string;
  nombre?: string;
  telefono?: string | null;
  cargo?: string | null;
  correo?: string | null;
  habilitado?: boolean;
  administrador?: boolean;
  grupoIds?: (number | string)[];
  rolIds?: (number | string)[];
  configApp?: ConfigApp;
}

export interface GrupoPlataforma {
  id: number | string;
  idPublico?: string;
  nombre: string;
  descripcion?: string | null;
  // El servidor puede devolver los miembros de formas distintas según la
  // fase; todos se tratan como opcionales.
  usuarioIds?: (number | string)[];
  miembros?: { id?: number | string; idPublico?: string; usuario?: string; nombre?: string }[];
  totalMiembros?: number;
}

export interface CreacionGrupo {
  nombre: string;
  descripcion?: string;
}

// Equipo visible para una cuenta (GET /api/v1/usuarios/:id/equipos).
// El servidor responde {datos:[...]}; la web también acepta un arreglo simple
// durante la transición, igual que con usuarios y grupos.
export interface EquipoVisibleUsuario {
  id: number | string;
  idPublico?: string;
  nombre: string;
}

// Reemplazo de la lista visible (PUT /api/v1/usuarios/:id/equipos). Se manda
// el id interno cuando se conoce; el servidor también acepta el idPublico.
export interface EquiposVisiblesUsuario {
  dispositivoIds: (number | string)[];
}

export interface MiembrosGrupo {
  usuarioIds: (number | string)[];
}

export type TipoAjuste = 'numero' | 'booleano' | 'texto';

export interface EntradaEsquemaAjustes {
  clave: string;
  etiqueta: string;
  descripcion: string;
  tipo: TipoAjuste;
  min?: number;
  max?: number;
}
