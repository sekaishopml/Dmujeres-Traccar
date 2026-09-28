import { ApiError } from '../../api/cliente';

// La API siempre manda un mensaje legible; cualquier otro fallo (red, JSON)
// se resume sin exponer detalles técnicos al operador. ApiError hereda de
// Error, así que el texto viaja en `message` (no existe `mensaje`).
export function mensajeError(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error && error.name === 'AbortError') return 'Consulta cancelada.';
  return 'No se pudo conectar con el servidor.';
}

// Un 404 en replay/posición no es un fallo: significa que no hay datos en la
// ventana pedida, y se muestra como estado vacío.
export function esNoEncontrado(error: unknown): boolean {
  return error instanceof ApiError && error.estado === 404;
}
