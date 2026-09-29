import { useCallback, useMemo, useState } from 'react';
import type { FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { CreacionGrupo, GrupoPlataforma, UsuarioPlataforma } from '@contratos';
import { api } from '../api/cliente';
import { useSesion } from '../store/sesion';
import { GUION } from '../util/formato';
import Icono from '../componentes/Icono';
import {
  CACHE_PLATAFORMA_MS,
  MensajeError,
  Paginacion,
  esErrorDeEstado,
  mensajeDeError,
} from './admin/comunes';
import { Dialogo } from './admin/Dialogo';
import { Toast } from './admin/Toast';
import { traerGrupos, traerUsuariosPlataforma } from './operacion/datos';
import './admin.css';
import '../estilos/paginas.css';

const TAMANO = 25;

function idEnUrl(grupo: GrupoPlataforma): string {
  return encodeURIComponent(String(grupo.idPublico ?? grupo.id));
}

function claveUsuario(usuario: UsuarioPlataforma): string {
  return String(usuario.idPublico ?? usuario.id);
}

// El servidor puede devolver los miembros como ids o como fichas con nombre;
// se aceptan las dos formas para no dejar la lista vacía durante la transición.
function idsMiembros(grupo: GrupoPlataforma): string[] {
  if (grupo.usuarioIds && grupo.usuarioIds.length > 0) {
    return grupo.usuarioIds.map((id) => String(id));
  }
  if (grupo.miembros && grupo.miembros.length > 0) {
    return grupo.miembros
      .map((miembro) => String(miembro.idPublico ?? miembro.id ?? miembro.usuario ?? ''))
      .filter((id) => id !== '');
  }
  return [];
}

function nombresMiembros(grupo: GrupoPlataforma, usuarios: UsuarioPlataforma[]): string {
  if (grupo.miembros && grupo.miembros.length > 0) {
    const nombres = grupo.miembros
      .map((miembro) => {
        if (typeof (miembro as { nombre?: unknown }).nombre === 'string') {
          return (miembro as { nombre: string }).nombre;
        }
        return null;
      })
      .filter((nombre): nombre is string => typeof nombre === 'string' && nombre !== '');
    if (nombres.length > 0) return nombres.join(', ');
  }
  const ids = new Set(idsMiembros(grupo));
  if (ids.size === 0) {
    if (grupo.totalMiembros !== undefined) return `${grupo.totalMiembros} en el grupo`;
    return GUION;
  }
  const porId = new Map<string, UsuarioPlataforma>();
  for (const usuario of usuarios) {
    porId.set(String(usuario.id), usuario);
    porId.set(claveUsuario(usuario), usuario);
  }
  const nombres = [...ids]
    .map((id) => porId.get(id)?.nombre ?? porId.get(id)?.usuario)
    .filter((nombre): nombre is string => typeof nombre === 'string' && nombre !== '');
  if (nombres.length === 0) return `${ids.size} en el grupo`;
  return nombres.join(', ');
}

type Modal =
  | { modo: 'crear' }
  | { modo: 'eliminar'; grupo: GrupoPlataforma }
  | { modo: 'miembros'; grupo: GrupoPlataforma };

function FormularioGrupo({
  guardando,
  error,
  onGuardar,
  onCancelar,
}: {
  guardando: boolean;
  error: Error | null;
  onGuardar: (cuerpo: CreacionGrupo) => void;
  onCancelar: () => void;
}) {
  const [nombre, setNombre] = useState('');
  const [descripcion, setDescripcion] = useState('');
  const [validacion, setValidacion] = useState('');

  function enviar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault();
    if (guardando) return;
    if (!nombre.trim()) {
      setValidacion('Escribe el nombre del grupo.');
      return;
    }
    setValidacion('');
    const cuerpo: CreacionGrupo = { nombre: nombre.trim() };
    if (descripcion.trim() !== '') cuerpo.descripcion = descripcion.trim();
    onGuardar(cuerpo);
  }

  return (
    <form onSubmit={enviar} noValidate>
      <label className="campo">
        <span>Nombre del grupo</span>
        <input
          value={nombre}
          onChange={(evento) => setNombre(evento.target.value)}
          autoFocus
          placeholder="Por ejemplo: Turno de noche"
        />
      </label>
      <label className="campo">
        <span>Descripción (opcional)</span>
        <input
          value={descripcion}
          onChange={(evento) => setDescripcion(evento.target.value)}
          placeholder="Para qué se usa este grupo"
        />
      </label>
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

function FormularioMiembros({
  grupo,
  usuarios,
  guardando,
  error,
  onGuardar,
  onCancelar,
}: {
  grupo: GrupoPlataforma;
  usuarios: UsuarioPlataforma[];
  guardando: boolean;
  error: Error | null;
  onGuardar: (usuarioIds: (number | string)[]) => void;
  onCancelar: () => void;
}) {
  const [elegidos, setElegidos] = useState<string[]>(() => idsMiembros(grupo));

  function alternar(clave: string) {
    setElegidos((actuales) =>
      actuales.includes(clave) ? actuales.filter((otro) => otro !== clave) : [...actuales, clave],
    );
  }

  function enviar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault();
    if (guardando) return;
    const porClave = new Map(usuarios.map((usuario) => [claveUsuario(usuario), usuario]));
    const usuarioIds = elegidos
      .map((clave) => porClave.get(clave))
      .filter((usuario): usuario is UsuarioPlataforma => usuario !== undefined)
      .map((usuario) => usuario.id ?? usuario.idPublico);
    onGuardar(usuarioIds);
  }

  return (
    <form onSubmit={enviar} noValidate>
      <p className="ayuda-campo">Marca quiénes pertenecen a este grupo. Se guarda la lista completa.</p>
      {usuarios.length === 0 && <p className="vacio">No hay personas para asignar.</p>}
      {usuarios.length > 0 && (
        <fieldset className="grupo-equipos">
          <legend>Personas</legend>
          <div className="equipos-asignados">
            {usuarios.map((usuario) => (
              <label key={claveUsuario(usuario)}>
                <input
                  type="checkbox"
                  checked={elegidos.includes(claveUsuario(usuario))}
                  onChange={() => alternar(claveUsuario(usuario))}
                />
                <span>
                  {usuario.nombre} ({usuario.usuario})
                  {usuario.habilitado ? '' : ' · dada de baja'}
                </span>
              </label>
            ))}
          </div>
        </fieldset>
      )}
      {error !== null && <p className="error" role="alert">{mensajeDeError(error)}</p>}
      <div className="dialogo-pie">
        <button type="button" className="suave" onClick={onCancelar} disabled={guardando}>
          Cancelar
        </button>
        <button type="submit" className="principal" disabled={guardando}>
          {guardando ? 'Guardando…' : 'Guardar lista'}
        </button>
      </div>
    </form>
  );
}

export default function Grupos() {
  const administrador = useSesion((estado) => estado.usuario?.administrador === true);
  const cliente = useQueryClient();
  const [pagina, setPagina] = useState(1);
  const [modal, setModal] = useState<Modal | null>(null);
  const [exito, setExito] = useState('');
  const cerrarExito = useCallback(() => setExito(''), []);

  const grupos = useQuery({
    queryKey: ['grupos'],
    enabled: administrador,
    queryFn: () => traerGrupos(),
    staleTime: CACHE_PLATAFORMA_MS,
  });
  const usuarios = useQuery({
    queryKey: ['usuarios-plataforma'],
    enabled: administrador,
    queryFn: () => traerUsuariosPlataforma(),
    staleTime: CACHE_PLATAFORMA_MS,
  });

  const lista = useMemo(() => grupos.data ?? [], [grupos.data]);
  const listaUsuarios = useMemo(() => usuarios.data ?? [], [usuarios.data]);
  const total = lista.length;
  const totalPaginas = Math.max(1, Math.ceil(total / TAMANO));
  const paginaSegura = Math.min(pagina, totalPaginas);
  const visibles = useMemo(
    () => lista.slice((paginaSegura - 1) * TAMANO, paginaSegura * TAMANO),
    [lista, paginaSegura],
  );

  const crear = useMutation({
    mutationFn: (cuerpo: CreacionGrupo) => api.post<{ grupo: GrupoPlataforma }>('/api/v1/grupos', cuerpo),
    onSuccess: () => {
      cliente.invalidateQueries({ queryKey: ['grupos'] });
      setExito('Grupo creado.');
      setModal(null);
    },
  });

  const eliminar = useMutation({
    mutationFn: (grupo: GrupoPlataforma) => api.borrar<void>(`/api/v1/grupos/${idEnUrl(grupo)}`),
    onSuccess: () => {
      cliente.invalidateQueries({ queryKey: ['grupos'] });
      setExito('Grupo eliminado. Las personas no se borran.');
      setModal(null);
    },
  });

  const guardarMiembros = useMutation({
    mutationFn: ({ grupo, usuarioIds }: { grupo: GrupoPlataforma; usuarioIds: (number | string)[] }) =>
      api.put<{ grupo: GrupoPlataforma }>(`/api/v1/grupos/${idEnUrl(grupo)}/miembros`, { usuarioIds }),
    onSuccess: () => {
      cliente.invalidateQueries({ queryKey: ['grupos'] });
      setExito('Lista del grupo guardada.');
      setModal(null);
    },
  });

  const cerrarModal = useCallback(() => setModal(null), []);

  function abrirCrear() {
    crear.reset();
    setModal({ modo: 'crear' });
  }

  function abrirEliminar(grupo: GrupoPlataforma) {
    eliminar.reset();
    setModal({ modo: 'eliminar', grupo });
  }

  function abrirMiembros(grupo: GrupoPlataforma) {
    guardarMiembros.reset();
    setModal({ modo: 'miembros', grupo });
  }

  function confirmarEliminar() {
    if (modal?.modo === 'eliminar') eliminar.mutate(modal.grupo);
  }

  if (!administrador || esErrorDeEstado(grupos.error, 403)) {
    return (
      <section>
        <header className="cabecera-pagina">
          <div>
            <h1>Grupos</h1>
          </div>
        </header>
        <div className="bloque">
          <p className="aviso">Necesitas permisos de administrador para gestionar grupos.</p>
        </div>
      </section>
    );
  }

  return (
    <section>
      <header className="cabecera-pagina">
        <div>
          <h1>Grupos</h1>
          <p className="sub">Agrupan personas para organizar turnos y zonas. Solo administradores.</p>
        </div>
        <div className="empuja" />
        <button type="button" className="principal con-icono" onClick={abrirCrear}>
          <Icono nombre="mas" />
          Agregar grupo
        </button>
      </header>

      <section className="seccion">
        <div className="bloque">
          <header className="cabecera-seccion">
            <h2>Lista de grupos</h2>
            <span className="cuenta">{grupos.data ? `${total} registrados` : 'Consultando…'}</span>
          </header>
          {grupos.isPending && <p className="vacio">Cargando grupos…</p>}
          {grupos.error && <MensajeError error={grupos.error} />}
          {grupos.data && total === 0 && <p className="vacio">Todavía no hay grupos. Crea el primero.</p>}
          {visibles.length > 0 && (
            <>
              <div className="tabla-envoltura">
                <table className="tabla">
                  <thead>
                    <tr>
                      <th>Grupo</th>
                      <th>Descripción</th>
                      <th>Personas</th>
                      <th>Acciones</th>
                    </tr>
                  </thead>
                  <tbody>
                    {visibles.map((grupo) => (
                      <tr key={String(grupo.idPublico ?? grupo.id)}>
                        <td>{grupo.nombre}</td>
                        <td>{grupo.descripcion ?? GUION}</td>
                        <td>{nombresMiembros(grupo, listaUsuarios)}</td>
                        <td>
                          <div className="fila-botones">
                            <button
                              type="button"
                              className="accion-icono"
                              title="Cambiar quiénes están en el grupo"
                              aria-label={`Cambiar quiénes están en ${grupo.nombre}`}
                              onClick={() => abrirMiembros(grupo)}
                            >
                              <Icono nombre="usuarios" />
                            </button>
                            <button
                              type="button"
                              className="accion-icono peligro"
                              title="Eliminar el grupo"
                              aria-label={`Eliminar el grupo ${grupo.nombre}`}
                              onClick={() => abrirEliminar(grupo)}
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
              <Paginacion pagina={paginaSegura} tamano={TAMANO} total={total} onPagina={setPagina} />
            </>
          )}
          {usuarios.error && (
            <p className="apagado">No se pudieron cargar las personas para mostrar los nombres del grupo.</p>
          )}
        </div>
      </section>

      {modal?.modo === 'crear' && (
        <Dialogo titulo="Agregar grupo" onCerrar={cerrarModal}>
          <FormularioGrupo
            guardando={crear.isPending}
            error={crear.error}
            onGuardar={(cuerpo) => crear.mutate(cuerpo)}
            onCancelar={cerrarModal}
          />
        </Dialogo>
      )}

      {modal?.modo === 'eliminar' && (
        <Dialogo titulo="Eliminar el grupo" onCerrar={cerrarModal}>
          <p>
            ¿Eliminar el grupo <b>{modal.grupo.nombre}</b>? Las personas no se borran, solo quedan sin ese
            grupo.
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

      {modal?.modo === 'miembros' && (
        <Dialogo titulo={`Quiénes están en ${modal.grupo.nombre}`} onCerrar={cerrarModal}>
          {usuarios.isPending && <p className="vacio">Cargando personas…</p>}
          {usuarios.error && (
            <div>
              <p>No se pudieron cargar las personas. Inténtalo más tarde.</p>
              <MensajeError error={usuarios.error} />
              <div className="dialogo-pie">
                <button type="button" className="suave" onClick={cerrarModal}>
                  Cerrar
                </button>
              </div>
            </div>
          )}
          {usuarios.data && (
            <FormularioMiembros
              grupo={modal.grupo}
              usuarios={listaUsuarios}
              guardando={guardarMiembros.isPending}
              error={guardarMiembros.error}
              onGuardar={(usuarioIds) => guardarMiembros.mutate({ grupo: modal.grupo, usuarioIds })}
              onCancelar={cerrarModal}
            />
          )}
        </Dialogo>
      )}

      {exito !== '' && <Toast mensaje={exito} onCerrar={cerrarExito} />}
    </section>
  );
}
