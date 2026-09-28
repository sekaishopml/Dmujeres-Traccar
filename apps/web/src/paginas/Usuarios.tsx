import { useCallback, useState } from 'react';
import type { FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { Dispositivo, Pagina } from '@contratos';
import { api, consulta } from '../api/cliente';
import { useSesion } from '../store/sesion';
import { GUION } from '../util/formato';
import Icono from '../componentes/Icono';
import {
  ChipHabilitado,
  MensajeError,
  Paginacion,
  esErrorDeEstado,
  mensajeDeError,
  rolDeUsuario,
  useEquipos,
} from './admin/comunes';
import { Dialogo } from './admin/Dialogo';
import { Toast } from './admin/Toast';
import type { UsuarioGestion } from './admin/tipos';
import './admin.css';
import '../estilos/paginas.css';

const TAMANO = 25;
const CORREO_VALIDO = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const CLAVE_MINIMA = 8;

interface CuerpoUsuario {
  nombre: string;
  correo: string;
  clave: string;
  administrador: boolean;
  soloLectura: boolean;
  dispositivoIds: string[];
}

type Modal =
  | { modo: 'crear' }
  | { modo: 'editar'; usuario: UsuarioGestion }
  | { modo: 'eliminar'; usuario: UsuarioGestion };

interface PropsFormulario {
  usuario: UsuarioGestion | null;
  equipos: Dispositivo[];
  guardando: boolean;
  error: Error | null;
  onGuardar: (cuerpo: CuerpoUsuario) => void;
  onCancelar: () => void;
}

// En edición la clave vacía significa "no cambiar"; en creación es obligatoria.
function FormularioUsuario({ usuario, equipos, guardando, error, onGuardar, onCancelar }: PropsFormulario) {
  const [nombre, setNombre] = useState(usuario?.nombre ?? '');
  const [correo, setCorreo] = useState(usuario?.correo ?? '');
  const [clave, setClave] = useState('');
  const [verClave, setVerClave] = useState(false);
  const [administrador, setAdministrador] = useState(usuario?.administrador ?? false);
  const [soloLectura, setSoloLectura] = useState(usuario?.soloLectura ?? false);
  const [dispositivoIds, setDispositivoIds] = useState<string[]>(usuario ? [...usuario.dispositivoIds] : []);
  const [validacion, setValidacion] = useState('');

  function alternarEquipo(id: string) {
    setDispositivoIds((actuales) => (actuales.includes(id) ? actuales.filter((otro) => otro !== id) : [...actuales, id]));
  }

  function enviar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault();
    if (guardando) return;
    if (!nombre.trim()) {
      setValidacion('El nombre es obligatorio.');
      return;
    }
    if (!correo.trim()) {
      setValidacion('El correo es obligatorio.');
      return;
    }
    if (!CORREO_VALIDO.test(correo.trim())) {
      setValidacion('El correo no tiene un formato válido.');
      return;
    }
    if (!usuario && !clave) {
      setValidacion('La clave es obligatoria al crear el usuario.');
      return;
    }
    if (clave && clave.length < CLAVE_MINIMA) {
      setValidacion(`La clave debe tener al menos ${CLAVE_MINIMA} caracteres.`);
      return;
    }
    setValidacion('');
    onGuardar({
      nombre: nombre.trim(),
      correo: correo.trim(),
      clave,
      administrador,
      soloLectura,
      dispositivoIds,
    });
  }

  return (
    <form onSubmit={enviar} noValidate>
      <label className="campo">
        <span>Nombre</span>
        <input value={nombre} onChange={(evento) => setNombre(evento.target.value)} autoFocus />
      </label>
      <label className="campo">
        <span>Correo</span>
        <input type="email" value={correo} onChange={(evento) => setCorreo(evento.target.value)} />
      </label>
      <label className="campo">
        <span>{usuario ? 'Clave (vacía para no cambiarla)' : 'Clave'}</span>
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
            aria-label={verClave ? 'Ocultar la clave' : 'Mostrar la clave'}
          >
            {verClave ? 'Ocultar' : 'Mostrar'}
          </button>
        </span>
      </label>
      <div className="fila-form">
        <label className="recordar-admin">
          <input type="checkbox" checked={administrador} onChange={(evento) => setAdministrador(evento.target.checked)} />
          <span>Administrador</span>
        </label>
        <label className="recordar-admin">
          <input type="checkbox" checked={soloLectura} onChange={(evento) => setSoloLectura(evento.target.checked)} />
          <span>Solo lectura</span>
        </label>
      </div>
      <fieldset className="grupo-equipos">
        <legend>Equipos asignados</legend>
        {equipos.length === 0 && <p className="apagado">No hay equipos disponibles.</p>}
        <div className="equipos-asignados">
          {equipos.map((equipo) => (
            <label key={equipo.idPublico}>
              <input
                type="checkbox"
                checked={dispositivoIds.includes(equipo.idPublico)}
                onChange={() => alternarEquipo(equipo.idPublico)}
              />
              <span>{equipo.nombre}</span>
            </label>
          ))}
        </div>
      </fieldset>
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

export default function Usuarios() {
  const administrador = useSesion((estado) => estado.usuario?.administrador === true);
  const cliente = useQueryClient();
  const [pagina, setPagina] = useState(1);
  const [modal, setModal] = useState<Modal | null>(null);
  const [exito, setExito] = useState('');
  const cerrarExito = useCallback(() => setExito(''), []);

  const usuarios = useQuery({
    queryKey: ['usuarios', pagina],
    enabled: administrador,
    queryFn: () => api.get<Pagina<UsuarioGestion>>(`/api/v1/users${consulta({ pagina, tamano: TAMANO, orden: 'nombre' })}`),
  });
  const equipos = useEquipos();
  const flota = equipos.data?.datos ?? [];

  const crear = useMutation({
    mutationFn: (cuerpo: CuerpoUsuario) => api.post<{ usuario: UsuarioGestion }>('/api/v1/users', cuerpo),
    onSuccess: () => {
      cliente.invalidateQueries({ queryKey: ['usuarios'] });
      setExito('Usuario creado.');
      setModal(null);
    },
  });

  const actualizar = useMutation({
    mutationFn: ({ id, cuerpo }: { id: string; cuerpo: CuerpoUsuario }) =>
      api.put<{ usuario: UsuarioGestion }>(`/api/v1/users/${encodeURIComponent(id)}`, cuerpo),
    onSuccess: () => {
      cliente.invalidateQueries({ queryKey: ['usuarios'] });
      setExito('Usuario actualizado.');
      setModal(null);
    },
  });

  const eliminar = useMutation({
    mutationFn: (id: string) => api.borrar<void>(`/api/v1/users/${encodeURIComponent(id)}`),
    onSuccess: () => {
      cliente.invalidateQueries({ queryKey: ['usuarios'] });
      setExito('Usuario eliminado.');
      setModal(null);
    },
  });

  const cerrarModal = useCallback(() => setModal(null), []);

  function abrirCrear() {
    crear.reset();
    actualizar.reset();
    eliminar.reset();
    setModal({ modo: 'crear' });
  }

  function abrirEditar(usuario: UsuarioGestion) {
    actualizar.reset();
    setModal({ modo: 'editar', usuario });
  }

  function abrirEliminar(usuario: UsuarioGestion) {
    eliminar.reset();
    setModal({ modo: 'eliminar', usuario });
  }

  function guardarUsuario(cuerpo: CuerpoUsuario) {
    if (!modal) return;
    if (modal.modo === 'crear') crear.mutate(cuerpo);
    else if (modal.modo === 'editar') actualizar.mutate({ id: modal.usuario.idPublico, cuerpo });
  }

  function confirmarEliminar() {
    if (modal?.modo === 'eliminar') eliminar.mutate(modal.usuario.idPublico);
  }

  const nombresEquipos = new Map(flota.map((equipo) => [equipo.idPublico, equipo.nombre]));
  function equiposAsignados(usuario: UsuarioGestion): string {
    if (usuario.dispositivoIds.length === 0) return GUION;
    return usuario.dispositivoIds.map((id) => nombresEquipos.get(id) ?? id).join(', ');
  }

  if (!administrador || esErrorDeEstado(usuarios.error, 403)) {
    return (
      <section>
        <header className="cabecera-pagina">
          <div>
            <h1>Usuarios</h1>
          </div>
        </header>
        <div className="bloque">
          <p className="aviso">Necesitas permisos de administrador para gestionar usuarios.</p>
        </div>
      </section>
    );
  }

  return (
    <section>
      <header className="cabecera-pagina">
        <div>
          <h1>Usuarios</h1>
          <p className="sub">Alta, edición y equipos asignados. Solo administradores.</p>
        </div>
        <div className="empuja" />
        <button type="button" className="principal con-icono" onClick={abrirCrear}>
          <Icono nombre="mas" />
          Agregar usuario
        </button>
      </header>

      <section className="seccion">
        <div className="bloque">
          <header className="cabecera-seccion">
            <h2>Cuentas de acceso</h2>
            <span className="cuenta">
              {usuarios.data ? `${usuarios.data.total} registrados` : 'Consultando…'}
            </span>
          </header>
          {usuarios.isPending && <p className="vacio">Cargando usuarios…</p>}
          {usuarios.error && <MensajeError error={usuarios.error} />}
          {usuarios.data && usuarios.data.datos.length === 0 && (
            <p className="vacio">No hay usuarios registrados.</p>
          )}
          {usuarios.data && usuarios.data.datos.length > 0 && (
            <>
              <div className="tabla-envoltura">
                <table className="tabla">
                  <thead>
                    <tr>
                      <th>Nombre</th>
                      <th>Correo</th>
                      <th>Rol</th>
                      <th>Equipos asignados</th>
                      <th>Estado</th>
                      <th>Acciones</th>
                    </tr>
                  </thead>
                  <tbody>
                    {usuarios.data.datos.map((usuario) => (
                      <tr key={usuario.idPublico}>
                        <td>{usuario.nombre}</td>
                        <td>{usuario.correo ?? GUION}</td>
                        <td>{rolDeUsuario(usuario)}</td>
                        <td>{equiposAsignados(usuario)}</td>
                        <td>
                          <ChipHabilitado habilitado={usuario.habilitado} />
                        </td>
                        <td>
                          <div className="fila-botones">
                            <button
                              type="button"
                              className="accion-icono"
                              title="Editar usuario"
                              aria-label={`Editar ${usuario.nombre}`}
                              onClick={() => abrirEditar(usuario)}
                            >
                              <Icono nombre="editar" />
                            </button>
                            <button
                              type="button"
                              className="accion-icono peligro"
                              title="Eliminar usuario"
                              aria-label={`Eliminar ${usuario.nombre}`}
                              onClick={() => abrirEliminar(usuario)}
                            >
                              <Icono nombre="basura" />
                            </button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Paginacion
                pagina={usuarios.data.pagina}
                tamano={usuarios.data.tamano}
                total={usuarios.data.total}
                onPagina={setPagina}
              />
            </>
          )}
        </div>
      </section>

      {modal?.modo === 'crear' && (
        <Dialogo titulo="Agregar usuario" onCerrar={cerrarModal}>
          <FormularioUsuario
            usuario={null}
            equipos={flota}
            guardando={crear.isPending}
            error={crear.error}
            onGuardar={guardarUsuario}
            onCancelar={cerrarModal}
          />
        </Dialogo>
      )}

      {modal?.modo === 'editar' && (
        <Dialogo titulo="Editar usuario" onCerrar={cerrarModal}>
          <FormularioUsuario
            usuario={modal.usuario}
            equipos={flota}
            guardando={actualizar.isPending}
            error={actualizar.error}
            onGuardar={guardarUsuario}
            onCancelar={cerrarModal}
          />
        </Dialogo>
      )}

      {modal?.modo === 'eliminar' && (
        <Dialogo titulo="Eliminar usuario" onCerrar={cerrarModal}>
          <p>
            ¿Eliminar el usuario <b>{modal.usuario.nombre}</b>? Esta acción no se puede deshacer.
          </p>
          {eliminar.error !== null && <MensajeError error={eliminar.error} />}
          <div className="dialogo-pie">
            <button type="button" className="suave" onClick={cerrarModal} disabled={eliminar.isPending}>
              Cancelar
            </button>
            <button type="button" className="peligro" onClick={confirmarEliminar} disabled={eliminar.isPending}>
              {eliminar.isPending ? 'Eliminando…' : 'Eliminar'}
            </button>
          </div>
        </Dialogo>
      )}

      {exito !== '' && <Toast mensaje={exito} onCerrar={cerrarExito} />}
    </section>
  );
}
