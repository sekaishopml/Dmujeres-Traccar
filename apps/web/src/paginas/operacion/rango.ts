// Los <input type="date"> trabajan con fecha local (YYYY-MM-DD), mientras que
// la API espera ISO-8601 con zona. Estas conversiones usan la zona del
// navegador para que el día elegido por la operadora sea el mismo que consulta.

export function fechaHoyLocal(): string {
  const ahora = new Date();
  const local = new Date(ahora.getTime() - ahora.getTimezoneOffset() * 60000);
  return local.toISOString().slice(0, 10);
}

export function inicioDeDia(fechaLocal: string): string {
  return new Date(`${fechaLocal}T00:00:00`).toISOString();
}

export function finDeDia(fechaLocal: string): string {
  return new Date(`${fechaLocal}T23:59:59.999`).toISOString();
}
