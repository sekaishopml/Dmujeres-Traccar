// Piezas compartidas por las páginas de administración: consultas comunes,
// controles de equipo/paginación, chips y estados de carga/error. Centralizar
// esto evita que cada página invente sus propios mensajes o formatos.
import { useQuery } from '@tanstack/react-query';
import type { Dispositivo, EstadoDispositivo, Pagina, Usuario } from '@contratos';
import { ApiError, api, consulta } from '../../api/cliente';
import { GUION, bateria as formatearBateria } from '../../util/formato';

// --- Errores ---

export function mensajeDeError(error: unknown): string {
  // ApiError pasa el "mensaje" de la API a Error.message en el cliente.
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return 'No se pudo cargar la información.';
}

export function esErrorDeEstado(error: unknown, estado: number): boolean {
  return error instanceof ApiError && error.estado === estado;
}

export function MensajeError({ error }: { error: unknown }) {
  return (
    <p className="error" role="alert">
      {mensajeDeError(error)}
    </p>
  );
}

// --- Flota ---

// La flota se pide una sola vez con el tamaño máximo del contrato (200) y
// react-query comparte la caché entre Batería, Reportes y Configuración.
export function useEquipos() {
  return useQuery({
    queryKey: ['flota', 'todos'],
    queryFn: () => api.get<Pagina<Dispositivo>>(`/api/v1/fleet${consulta({ tamano: 200 })}`),
  });
}

// --- Estados del dispositivo ---

export const ETIQUETA_ESTADO_DISPOSITIVO: Record<EstadoDispositivo, string> = {
  EN_LINEA: 'En línea',
  DETENIDO: 'Detenido',
  SENAL_DEBIL: 'Señal débil',
  SIN_SENAL: 'Sin señal',
  DESHABILITADO: 'Deshabilitado',
  DESCONOCIDO: 'Desconocido',
};

// Reutiliza las clases .chip del tema; DESCONOCIDO se pinta en gris.
export const CLASE_ESTADO_DISPOSITIVO: Record<EstadoDispositivo, string> = {
  EN_LINEA: 'enLinea',
  DETENIDO: 'detenido',
  SENAL_DEBIL: 'senalDebil',
  SIN_SENAL: 'sinSenal',
  DESHABILITADO: 'deshabilitado',
  DESCONOCIDO: 'deshabilitado',
};

export function ChipEstado({ estado }: { estado: EstadoDispositivo }) {
  return <span className={`chip ${CLASE_ESTADO_DISPOSITIVO[estado]}`}>{ETIQUETA_ESTADO_DISPOSITIVO[estado]}</span>;
}

export function ChipHabilitado({ habilitado }: { habilitado: boolean }) {
  return (
    <span className={`chip ${habilitado ? 'enLinea' : 'deshabilitado'}`}>
      {habilitado ? 'Habilitado' : 'Deshabilitado'}
    </span>
  );
}

// El rol no tiene endpoint propio: se deriva de los dos flags del DTO. La
// prioridad admin > solo lectura > operador evita que un administrador con
// soloLectura se muestre como operador.
export function rolDeUsuario(usuario: Usuario): string {
  if (usuario.administrador) return 'Administrador';
  if (usuario.soloLectura) return 'Solo lectura';
  return 'Operador';
}

// --- Batería ---

export function BarraBateria({ pct }: { pct: number | null }) {
  const clase = pct == null ? '' : pct <= 20 ? 'bajo' : pct <= 50 ? 'medio' : '';
  const ancho = pct == null ? 0 : Math.max(0, Math.min(100, pct));
  return (
    <span className="barra-bateria">
      <span className="tubo">
        <span className={`relleno ${clase}`} style={{ width: `${ancho}%` }} />
      </span>
      <b>{formatearBateria(pct)}</b>
    </span>
  );
}

// El estado de carga del DTO es nullable: null significa "sin dato", no "no".
export function TextoCarga({ cargando }: { cargando: boolean | null }) {
  if (cargando == null) return <>{GUION}</>;
  return <span className={cargando ? 'si' : 'no'}>{cargando ? 'Sí' : 'No'}</span>;
}

// --- Controles ---

interface PropsSelectorEquipo {
  equipos: Dispositivo[];
  valor: string;
  onCambio: (valor: string) => void;
  etiqueta?: string;
  incluirTodos?: boolean;
}

export function SelectorEquipo({ equipos, valor, onCambio, etiqueta = 'Equipo', incluirTodos = false }: PropsSelectorEquipo) {
  return (
    <label className="campo">
      <span>{etiqueta}</span>
      <select value={valor} onChange={(e) => onCambio(e.target.value)}>
        {incluirTodos && <option value="">Todos los equipos</option>}
        {equipos.map((equipo) => (
          <option key={equipo.idPublico} value={equipo.idPublico}>
            {equipo.nombre}
          </option>
        ))}
      </select>
    </label>
  );
}

interface PropsPaginacion {
  pagina: number;
  tamano: number;
  total: number;
  onPagina: (pagina: number) => void;
}

export function Paginacion({ pagina, tamano, total, onPagina }: PropsPaginacion) {
  const totalPaginas = Math.max(1, Math.ceil(total / tamano));
  return (
    <div className="paginacion">
      <span>
        Página {pagina} de {totalPaginas} · {total} registros
      </span>
      <button type="button" className="suave" disabled={pagina <= 1} onClick={() => onPagina(pagina - 1)}>
        Anterior
      </button>
      <button type="button" className="suave" disabled={pagina >= totalPaginas} onClick={() => onPagina(pagina + 1)}>
        Siguiente
      </button>
    </div>
  );
}

