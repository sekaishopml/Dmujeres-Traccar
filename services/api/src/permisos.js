// Permisos de la plataforma: quién puede cambiar qué.
// Reglas:
//   * Solo lectura (flag solo_lectura o rol solo_lectura) bloquea toda escritura.
//   * Operador: todo menos gestión de usuarios, roles y grupos.
//   * Administrador (flag o rol): todo.
// La gestión de usuarios/roles/grupos exige administrador y escritura.

import { sinPermiso } from './errores.js';

export async function codigosRol(ejecutor, usuarioId) {
  const { rows } = await ejecutor.query(
    `SELECT r.codigo
       FROM iam.dmt_usuario_rol ur
       JOIN iam.dmt_rol r ON r.id = ur.rol_id
      WHERE ur.usuario_id = $1`,
    [Number(usuarioId)],
  );
  return rows.map((fila) => fila.codigo);
}

function marcaSoloLectura(usuario) {
  return usuario?.soloLectura === true || usuario?.solo_lectura === true;
}

function marcaAdministradora(usuario) {
  return usuario?.administrador === true;
}

export async function esSoloLectura(poolOCiente, usuario) {
  if (!usuario) return false;
  if (marcaSoloLectura(usuario)) return true;
  try {
    const codigos = await codigosRol(poolOCiente, usuario.id);
    return codigos.includes('solo_lectura');
  } catch {
    // Sin permiso de lectura de roles se respeta solo el flag.
    return false;
  }
}

export async function esAdministradora(poolOCiente, usuario) {
  if (!usuario) return false;
  if (marcaAdministradora(usuario)) return true;
  try {
    const codigos = await codigosRol(poolOCiente, usuario.id);
    return codigos.includes('administrador');
  } catch {
    return false;
  }
}

// Toda escritura (usuarios, grupos, flota, etc.) pasa por aquí.
export async function exigirEscritura(ctx) {
  if (await esSoloLectura(ctx.pool, ctx.usuario)) {
    throw sinPermiso('La cuenta de solo lectura no puede hacer cambios.');
  }
}

// Gestión de personas y permisos: solo administradores (y nunca solo lectura).
export async function exigirAdministracion(ctx) {
  await exigirEscritura(ctx);
  if (!(await esAdministradora(ctx.pool, ctx.usuario))) {
    throw sinPermiso('Se requiere una cuenta administradora.');
  }
}
