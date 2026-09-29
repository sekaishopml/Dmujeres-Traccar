import { useCallback, useState } from 'react';
import type { FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { CreacionUsuarioPlataforma, RolPlataforma, UsuarioPlataforma } from '@contratos';
import { api } from '../api/cliente';
import { useSesion } from '../store/sesion';
import { GUION, fechaHora, hace } from '../util/formato';
import Icono from '../componentes/Icono';
import EncabezadoPagina from '../componentes/EncabezadoPagina';
import CabeceraSeccion from '../componentes/CabeceraSeccion';
import EstadoVacio from '../componentes/EstadoVacio';
import { CACHE_PLATAFORMA_MS, ChipHabilitado, MensajeError, esErrorDeEstado, mensajeDeError } from './admin/comunes';
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
    ok: 'El servicio responde: el panel puede cargar y guardar datos.',
    error: 'El servicio no responde: el panel no puede cargar ni guardar datos.',
    desconocido: 'Comprobando si el servicio responde…',
  },
  baseDatos: {
    ok: 'La base de datos responde: flota e historial disponibles.',
    error: 'La base de datos no responde: no hay datos de flota ni historial.',
    desconocido: 'Sin confirmación de la base de datos.',
  },
  tracking: {
    ok: 'El motor de seguimiento está conectado: las posiciones llegan.',
    error: 'El motor de seguimiento no responde: los equipos dejarán de actualizarse.',
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
        {estado === 'ok' ? 'Funciona' : estado === 'error' ? 'Con falla' : 'Sin respuesta'}
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
      <p className="apagado">La cuenta se crea con permiso de administración. No hay que elegir nada.</p>
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
  if (baseDatos === 'error') fallidas.push('la base de datos');
  if (tracking === 'error') fallidas.push('el motor de seguimiento');

  const estadoProceso = estadoDe(salud.data?.estado === 'ok' ? 'ok' : undefined, salud.isPending, salud.isError);
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
    staleTime: CACHE_PLATAFORMA_MS,
  });
  const roles = useQuery({
    queryKey: ['roles'],
    enabled: administrador,
    queryFn: () => traerRoles(),
    staleTime: CACHE_PLATAFORMA_MS,
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
      setExito('Cuenta dada de baja. Puedes reactivarla cuando la necesites.');
    },
  });

  const altaAdmin = useMutation({
    mutationFn: (usuario: UsuarioPlataforma) =>
      api.patch<{ usuario: UsuarioPlataforma }>(`/api/v1/usuarios/${idEnUrl(usuario)}`, {
        habilitado: true,
      }),
    onSuccess: () => {
      cliente.invalidateQueries({ queryKey: ['usuarios-plataforma'] });
      setExito('Cuenta reactivada.');
    },
  });

  return (
    <section className="pagina-sistema">
      <EncabezadoPagina
        contexto="Administración"
        titulo="Sistema"
      />

      {listoFalla && (
        <section className="seccion">
          <div className="bloque fallo">
            <CabeceraSeccion titulo="Servicio con problemas" />
            {listo.error && (
              <p role="alert">No se pudo comprobar el estado del servicio: {mensajeDeError(listo.error)}.</p>
            )}
            {!listo.error && listo.data && (
              <p role="alert">
                {fallidas.length > 0
                  ? `La revisión falla en ${fallidas.join(' y ')}.`
                  : 'La revisión del servicio quedó en estado con problemas.'}
              </p>
            )}
            <p className="apagado">
              Los datos del panel pueden estar incompletos. Avisa a quien administra el sistema antes de
              operar con la flota.
            </p>
          </div>
        </section>
      )}

      <section className="seccion">
        <div className="tira-datos">
          <div className="dato">
            <div className="valor">{version.data?.version ?? GUION}</div>
            <div className="etiqueta">Versión del panel</div>
          </div>
          <div className="dato">
            <div className="valor">{version.data?.versionApi ?? GUION}</div>
            <div className="etiqueta">Versión del servicio</div>
          </div>
          <div className="dato">
            <div className="valor">{version.data?.versionEsquema ?? GUION}</div>
            <div className="etiqueta">Versión de la base de datos</div>
          </div>
          <div className="dato">
            <div className="valor mono">{version.data?.commit ?? GUION}</div>
            <div className="etiqueta">Código publicado</div>
          </div>
          <div className="dato">
            <div className="valor">{fechaHora(listo.data?.comprobadoEn)}</div>
            <div className="etiqueta">Última comprobación</div>
          </div>
        </div>
      </section>

      <section className="seccion">
        <CabeceraSeccion
          titulo="Dependencias"
          cuenta={`${disponibles} de 3 disponibles`}
          acciones={
            <span className="cuenta">
              {salud.dataUpdatedAt > 0 ? `Consultado ${hace(new Date(salud.dataUpdatedAt).toISOString())}` : ''}
            </span>
          }
        />
        <FilaDependencia
          nombre="Servicio"
          estado={estadoProceso}
          significado={SIGNIFICADO.proceso[estadoProceso]}
        />
        <FilaDependencia
          nombre="Base de datos"
          estado={estadoBaseDatos}
          significado={SIGNIFICADO.baseDatos[estadoBaseDatos]}
        />
        <FilaDependencia
          nombre="Motor de seguimiento"
          estado={estadoTracking}
          significado={SIGNIFICADO.tracking[estadoTracking]}
        />
        {salud.error && <MensajeError error={salud.error} />}
        {listo.error && <MensajeError error={listo.error} />}
      </section>

      <section className="seccion">
        <CabeceraSeccion
          titulo="Cuentas de administración"
          cuenta={cuentas.data ? `${admins.length} con permiso de administración` : 'Consultando…'}
          acciones={
            administrador ? (
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
            ) : undefined
          }
        />
        {sinPermiso && (
          <p className="aviso">Necesitas permisos de administrador para ver y cambiar estas cuentas.</p>
        )}
        {!sinPermiso && cuentas.isPending && <p className="vacio pulso">Cargando cuentas…</p>}
        {!sinPermiso && cuentas.error && <MensajeError error={cuentas.error} />}
        {!sinPermiso && cuentas.data && admins.length === 0 && (
          <EstadoVacio icono="usuarios">Todavía no hay cuentas con permiso de administración.</EstadoVacio>
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
                            {altaAdmin.isPending ? 'Reactivando…' : 'Reactivar'}
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
          <p className="apagado">No se pudieron cargar los permisos; la lista se armó con lo disponible.</p>
        )}
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
