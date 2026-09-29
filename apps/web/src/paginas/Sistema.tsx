import { useCallback, useState } from 'react';
import type { FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { CreacionUsuarioPlataforma, RolPlataforma, UsuarioPlataforma } from '@contratos';
import { api } from '../api/cliente';
import { useSesion } from '../store/sesion';
import { GUION, fechaHora, hace } from '../util/formato';
import Icono from '../componentes/Icono';
import { ChipHabilitado, MensajeError, esErrorDeEstado, mensajeDeError } from './admin/comunes';
import { Dialogo } from './admin/Dialogo';
import { Toast } from './admin/Toast';
import { traerRoles, traerUsuariosPlataforma } from './operacion/datos';
import type { Disponibilidad, Salud, Version } from './admin/tipos';
import './admin.css';
import '../estilos/paginas.css';

// El contrato pide sondeo de salud; 15 s es suficiente para detectar caídas
// sin castigar al servidor.
const INTERVALO_MS = 15_000;

const CLAVE_MINIMA = 8;

type EstadoDependencia = 'ok' | 'error' | 'desconocido';

// Cada dependencia se explica en una línea con lo que implica su caída, no con
// un "Ok" suelto: el personal de oficina necesita saber qué deja de funcionar.
const SIGNIFICADO: Record<'proceso' | 'baseDatos' | 'tracking', Record<EstadoDependencia, string>> = {
  proceso: {
    ok: 'El proceso de la API responde a las peticiones del panel.',
    error: 'La API no responde: el panel no carga ni guarda datos.',
    desconocido: 'Comprobando si la API responde…',
  },
  baseDatos: {
    ok: 'PostgreSQL acepta consultas: flota e histórico disponibles.',
    error: 'PostgreSQL no responde: sin datos de flota ni histórico.',
    desconocido: 'Sin confirmación de PostgreSQL.',
  },
  tracking: {
    ok: 'El motor de seguimiento está conectado: las posiciones llegan.',
    error: 'El motor de seguimiento no responde: las unidades dejarán de actualizarse.',
    desconocido: 'Sin confirmación del motor de seguimiento.',
  },
};

function estadoDe(valor: 'ok' | 'error' | undefined, cargando: boolean, fallo: boolean): EstadoDependencia {
  if (valor === 'ok') return 'ok';
  if (cargando) return 'desconocido';
  if (valor === 'error' || fallo) return 'error';
  return 'desconocido';
}

function FilaDependencia({
  nombre,
  estado,
  significado,
}: {
  nombre: string;
  estado: EstadoDependencia;
  significado: string;
}) {
  const clasePunto = estado === 'desconocido' ? 'sin-datos' : estado;
  const claseResultado = estado === 'ok' ? 'si' : estado === 'error' ? 'no' : 'apagado';
  return (
    <div className="fila-dependencia">
      <span className={`punto-estado ${clasePunto}`} aria-hidden="true" />
      <strong>{nombre}</strong>
      <span className="detalle">{significado}</span>
      <span className={`resultado ${claseResultado}`}>
        {estado === 'ok' ? 'Ok' : estado === 'error' ? 'Error' : 'Sin respuesta'}
      </span>
    </div>
  );
}

function idEnUrl(usuario: UsuarioPlataforma): string {
  return encodeURIComponent(usuario.idPublico ?? String(usuario.id));
}

function esAdmin(usuario: UsuarioPlataforma, roles: RolPlataforma[]): boolean {
  if (usuario.administrador) return true;
  if (usuario.roles?.some((rol) => rol.nombre.trim().toLowerCase() === 'administrador')) return true;
  if (usuario.rolIds && usuario.rolIds.length > 0) {
    return usuario.rolIds.some((id) => {
      const rol = roles.find((otro) => String(otro.id) === String(id));
      return rol?.nombre.trim().toLowerCase() === 'administrador';
    });
  }
  return false;
}

function FormularioAdmin({
  roles,
  guardando,
  error,
  onGuardar,
  onCancelar,
}: {
  roles: RolPlataforma[];
  guardando: boolean;
  error: Error | null;
  onGuardar: (cuerpo: CreacionUsuarioPlataforma) => void;
  onCancelar: () => void;
}) {
  const [cuenta, setCuenta] = useState('');
  const [clave, setClave] = useState('');
  const [verClave, setVerClave] = useState(false);
  const [nombre, setNombre] = useState('');
  const [validacion, setValidacion] = useState('');

  function enviar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault();
    if (guardando) return;
    if (!cuenta.trim()) {
      setValidacion('Escribe el nombre con el que la persona va a entrar.');
      return;
    }
    if (!nombre.trim()) {
      setValidacion('Escribe el nombre completo de la persona.');
      return;
    }
    if (!clave) {
      setValidacion('Escribe una contraseña para la cuenta nueva.');
      return;
    }
    if (clave.length < CLAVE_MINIMA) {
      setValidacion(`La contraseña debe tener al menos ${CLAVE_MINIMA} caracteres.`);
      return;
    }
    setValidacion('');
    // La cuenta nace administradora sin elegir nada: el permiso va fijo en el
    // cuerpo aunque el servidor aún devuelva roles.
    const cuerpo: CreacionUsuarioPlataforma = {
      usuario: cuenta.trim(),
      clave,
      nombre: nombre.trim(),
      administrador: true,
    };
    const rolAdmin = roles.find((rol) => rol.nombre.trim().toLowerCase() === 'administrador');
    if (rolAdmin) cuerpo.rolIds = [rolAdmin.id];
    onGuardar(cuerpo);
  }

  return (
    <form onSubmit={enviar} noValidate>
      <label className="campo">
        <span>Nombre para entrar</span>
        <input
          value={cuenta}
          onChange={(evento) => setCuenta(evento.target.value)}
          autoFocus
          autoComplete="off"
          placeholder="Por ejemplo: jefa.turno"
        />
      </label>
      <label className="campo">
        <span>Contraseña</span>
        <span className="clave-caja">
          <input
            type={verClave ? 'text' : 'password'}
            value={clave}
            onChange={(evento) => setClave(evento.target.value)}
            autoComplete="new-password"
          />
          <button
            type="button"
            className="clave-ver"
            onClick={() => setVerClave((visible) => !visible)}
            aria-label={verClave ? 'Ocultar la contraseña' : 'Mostrar la contraseña'}
          >
            {verClave ? 'Ocultar' : 'Mostrar'}
          </button>
        </span>
      </label>
      <label className="campo">
        <span>Nombre completo</span>
        <input value={nombre} onChange={(evento) => setNombre(evento.target.value)} />
      </label>
      <p className="apagado">Esta cuenta nace con permiso de administración, sin elegir nada.</p>
      {validacion !== '' && (
        <p className="error" role="alert">
          {validacion}
        </p>
      )}
      {validacion === '' && error !== null && <p className="error" role="alert">{mensajeDeError(error)}</p>}
      <div className="dialogo-pie">
        <button type="button" className="suave" onClick={onCancelar} disabled={guardando}>
          Cancelar
        </button>
        <button type="submit" className="principal" disabled={guardando}>
          {guardando ? 'Guardando…' : 'Guardar'}
        </button>
      </div>
    </form>
  );
}

export default function Sistema() {
  // Sondeo de fondo cada 15 s: un 401 aquí no redirige, solo deja el estado
  // de error; la comprobación de sesión decide.
  const salud = useQuery({
    queryKey: ['sistema', 'salud'],
    queryFn: () => api.get<Salud>('/api/v1/health', { redirigir401: false }),
    refetchInterval: INTERVALO_MS,
  });

  const listo = useQuery({
    queryKey: ['sistema', 'listo'],
    queryFn: () => api.get<Disponibilidad>('/api/v1/ready', { redirigir401: false }),
    refetchInterval: INTERVALO_MS,
  });

  const version = useQuery({
    queryKey: ['sistema', 'version'],
    queryFn: () => api.get<Version>('/api/v1/version', { redirigir401: false }),
    refetchInterval: INTERVALO_MS,
  });

  // /ready puede fallar con 503 o responder 200 con estado "degradado": ambos
  // casos se resaltan igual porque significan que una dependencia no está bien.
  const listoFalla =
    listo.isError ||
    listo.data?.estado === 'degradado' ||
    listo.data?.dependencias.baseDatos === 'error' ||
    listo.data?.dependencias.tracking === 'error';

  const baseDatos = listo.data?.dependencias.baseDatos;
  const tracking = listo.data?.dependencias.tracking;
  const fallidas: string[] = [];
  if (baseDatos === 'error') fallidas.push('PostgreSQL');
  if (tracking === 'error') fallidas.push('el motor de seguimiento');

  const estadoProceso = estadoDe(undefined, salud.isPending, salud.isError);
  // Si /ready falla sin cuerpo no se puede culpar a una dependencia concreta:
  // las filas quedan en "Sin respuesta" y el aviso superior explica el fallo.
  const estadoBaseDatos = estadoDe(baseDatos, listo.isPending, false);
  const estadoTracking = estadoDe(tracking, listo.isPending, false);

  const estados = [estadoProceso, estadoBaseDatos, estadoTracking];
  const disponibles = estados.filter((estado) => estado === 'ok').length;

  // --- Cuentas de administración (contrato nuevo /api/v1/usuarios y /roles) ---
  const administrador = useSesion((estado) => estado.usuario?.administrador === true);
  const cliente = useQueryClient();
  const [modalAdmin, setModalAdmin] = useState(false);
  const [exito, setExito] = useState('');
  const cerrarExito = useCallback(() => setExito(''), []);

  const cuentas = useQuery({
    queryKey: ['usuarios-plataforma'],
    enabled: administrador,
    queryFn: () => traerUsuariosPlataforma(),
  });
  const roles = useQuery({
    queryKey: ['roles'],
    enabled: administrador,
    queryFn: () => traerRoles(),
  });
  const listaRoles = roles.data ?? [];
  const admins = (cuentas.data ?? []).filter((usuario) => esAdmin(usuario, listaRoles));
  const sinPermiso = !administrador || esErrorDeEstado(cuentas.error, 403);

  const crearAdmin = useMutation({
    mutationFn: (cuerpo: CreacionUsuarioPlataforma) =>
      api.post<{ usuario: UsuarioPlataforma }>('/api/v1/usuarios', cuerpo),
    onSuccess: () => {
      cliente.invalidateQueries({ queryKey: ['usuarios-plataforma'] });
      setExito('Cuenta de administración creada.');
      setModalAdmin(false);
    },
  });

  const bajaAdmin = useMutation({
    mutationFn: (usuario: UsuarioPlataforma) => api.borrar<void>(`/api/v1/usuarios/${idEnUrl(usuario)}`),
    onSuccess: () => {
      cliente.invalidateQueries({ queryKey: ['usuarios-plataforma'] });
      setExito('Cuenta dada de baja. Se puede volver a dar de alta desde Usuarios.');
    },
  });

  const altaAdmin = useMutation({
    mutationFn: (usuario: UsuarioPlataforma) =>
      api.patch<{ usuario: UsuarioPlataforma }>(`/api/v1/usuarios/${idEnUrl(usuario)}`, {
        habilitado: true,
      }),
    onSuccess: () => {
      cliente.invalidateQueries({ queryKey: ['usuarios-plataforma'] });
      setExito('Cuenta dada de alta de nuevo.');
    },
  });

  return (
    <section className="pagina-sistema">
      <header className="cabecera-pagina">
        <div>
          <h1>Sistema</h1>
          <p className="sub">Disponibilidad y versión del servicio; se refresca cada 15 s.</p>
        </div>
      </header>

      {listoFalla && (
        <section className="seccion">
          <div className="bloque fallo">
            <header className="cabecera-seccion">
              <h2>Disponibilidad degradada</h2>
            </header>
            {listo.error && (
              <p role="alert">La API no pudo comprobar sus dependencias: {mensajeDeError(listo.error)}.</p>
            )}
            {!listo.error && listo.data && (
              <p role="alert">
                {fallidas.length > 0
                  ? `El chequeo de disponibilidad falla para ${fallidas.join(' y ')}.`
                  : 'El chequeo de disponibilidad responde "degradado".'}
              </p>
            )}
            <p className="apagado">
              Los datos del panel pueden estar incompletos; conviene revisar los servicios en el servidor antes de
              operar con la flota.
            </p>
          </div>
        </section>
      )}

      <section className="seccion">
        <div className="tira-datos">
          <div className="dato">
            <div className="valor">{version.data?.version ?? GUION}</div>
            <div className="etiqueta">Versión</div>
          </div>
          <div className="dato">
            <div className="valor">{version.data?.versionApi ?? GUION}</div>
            <div className="etiqueta">Versión API</div>
          </div>
          <div className="dato">
            <div className="valor">{version.data?.versionEsquema ?? GUION}</div>
            <div className="etiqueta">Versión esquema</div>
          </div>
          <div className="dato">
            <div className="valor mono">{version.data?.commit ?? GUION}</div>
            <div className="etiqueta">Commit</div>
          </div>
          <div className="dato">
            <div className="valor">{fechaHora(listo.data?.comprobadoEn)}</div>
            <div className="etiqueta">Última comprobación</div>
          </div>
        </div>
      </section>

      <section className="seccion">
        <div className="bloque">
          <header className="cabecera-seccion">
            <h2>Dependencias</h2>
            <span className="cuenta">{disponibles} de 3 disponibles</span>
            <span className="acciones">
              <span className="cuenta">
                {salud.dataUpdatedAt > 0 ? `Consultado ${hace(new Date(salud.dataUpdatedAt).toISOString())}` : ''}
              </span>
            </span>
          </header>
          <FilaDependencia
            nombre="Proceso API"
            estado={estadoProceso}
            significado={SIGNIFICADO.proceso[estadoProceso]}
          />
          <FilaDependencia
            nombre="Base de datos"
            estado={estadoBaseDatos}
            significado={SIGNIFICADO.baseDatos[estadoBaseDatos]}
          />
          <FilaDependencia
            nombre="Tracking"
            estado={estadoTracking}
            significado={SIGNIFICADO.tracking[estadoTracking]}
          />
          {salud.error && <MensajeError error={salud.error} />}
          {listo.error && <MensajeError error={listo.error} />}
        </div>
      </section>

      <section className="seccion">
        <div className="bloque">
          <header className="cabecera-seccion">
            <h2>Cuentas de administración</h2>
            <span className="cuenta">
              {cuentas.data ? `${admins.length} con permiso alto` : 'Consultando…'}
            </span>
            <span className="acciones">
              {administrador && (
                <button
                  type="button"
                  className="principal con-icono"
                  onClick={() => {
                    crearAdmin.reset();
                    setModalAdmin(true);
                  }}
                >
                  <Icono nombre="mas" />
                  Agregar cuenta
                </button>
              )}
            </span>
          </header>
          {sinPermiso && (
            <p className="aviso">Necesitas permisos de administrador para ver y cambiar estas cuentas.</p>
          )}
          {!sinPermiso && cuentas.isPending && <p className="vacio">Cargando cuentas…</p>}
          {!sinPermiso && cuentas.error && <MensajeError error={cuentas.error} />}
          {!sinPermiso && cuentas.data && admins.length === 0 && (
            <p className="vacio">Todavía no hay cuentas con permiso alto.</p>
          )}
          {!sinPermiso && admins.length > 0 && (
            <div className="tabla-envoltura">
              <table className="tabla">
                <thead>
                  <tr>
                    <th>Cuenta</th>
                    <th>Nombre completo</th>
                    <th>Estado</th>
                    <th>Acciones</th>
                  </tr>
                </thead>
                <tbody>
                  {admins.map((usuario) => (
                    <tr key={usuario.idPublico}>
                      <td>{usuario.usuario}</td>
                      <td>{usuario.nombre}</td>
                      <td>
                        <ChipHabilitado habilitado={usuario.habilitado} />
                      </td>
                      <td>
                        <div className="fila-botones">
                          {usuario.habilitado ? (
                            <button
                              type="button"
                              className="accion-icono peligro"
                              title="Dar de baja la cuenta"
                              aria-label={`Dar de baja a ${usuario.nombre}`}
                              onClick={() => bajaAdmin.mutate(usuario)}
                              disabled={bajaAdmin.isPending}
                            >
                              <Icono nombre="basura" />
                            </button>
                          ) : (
                            <button
                              type="button"
                              className="suave"
                              onClick={() => altaAdmin.mutate(usuario)}
                              disabled={altaAdmin.isPending}
                            >
                              {altaAdmin.isPending ? 'Dando de alta…' : 'Dar de alta'}
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {!sinPermiso && bajaAdmin.error && <MensajeError error={bajaAdmin.error} />}
          {!sinPermiso && altaAdmin.error && <MensajeError error={altaAdmin.error} />}
          {!sinPermiso && roles.error && (
            <p className="apagado">No se pudieron traer los permisos; la lista se armó con lo disponible.</p>
          )}
        </div>
      </section>

      {modalAdmin && (
        <Dialogo titulo="Agregar cuenta de administración" onCerrar={() => setModalAdmin(false)}>
          <FormularioAdmin
            roles={listaRoles}
            guardando={crearAdmin.isPending}
            error={crearAdmin.error}
            onGuardar={(cuerpo) => crearAdmin.mutate(cuerpo)}
            onCancelar={() => setModalAdmin(false)}
          />
        </Dialogo>
      )}

      {exito !== '' && <Toast mensaje={exito} onCerrar={cerrarExito} />}
    </section>
  );
}
