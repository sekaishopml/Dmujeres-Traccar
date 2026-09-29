import { useCallback, useMemo, useState } from 'react';
import type { FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type {
  ActualizacionUsuarioPlataforma,
  CreacionUsuarioPlataforma,
  Dispositivo,
  EntradaEsquemaAjustes,
  GrupoPlataforma,
  RespuestaCreacionUsuarioPlataforma,
  UsuarioPlataforma,
} from '@contratos';
import { api } from '../api/cliente';
import { useSesion } from '../store/sesion';
import { GUION } from '../util/formato';
import Icono from '../componentes/Icono';
import EncabezadoPagina from '../componentes/EncabezadoPagina';
import CabeceraSeccion from '../componentes/CabeceraSeccion';
import EstadoVacio from '../componentes/EstadoVacio';
import {
  CACHE_FLOTA_CONSULTA_MS,
  CACHE_PLATAFORMA_MS,
  ChipHabilitado,
  MensajeError,
  Paginacion,
  esErrorDeEstado,
  mensajeDeError,
} from './admin/comunes';
import { Dialogo } from './admin/Dialogo';
import { Toast } from './admin/Toast';
import {
  CLAVE_FLOTA,
  invalidarFlota,
  traerEsquemaAjustes,
  traerFlota,
  traerGrupos,
  traerUsuariosPlataforma,
} from './operacion/datos';
import './admin.css';
import '../estilos/paginas.css';

const TAMANO = 25;
const CLAVE_MINIMA = 8;

function idEnUrl(usuario: UsuarioPlataforma): string {
  return encodeURIComponent(usuario.idPublico ?? String(usuario.id));
}

function idTextoGrupo(grupo: GrupoPlataforma): string {
  return String(grupo.idPublico ?? grupo.id);
}

function idOriginalGrupo(grupo: GrupoPlataforma): number | string {
  return grupo.idPublico ?? grupo.id;
}

function textoGrupos(usuario: UsuarioPlataforma): string {
  const grupos = usuario.grupos ?? [];
  if (grupos.length === 0) return GUION;
  return grupos.map((grupo) => grupo.nombre).join(', ');
}

// Equipos vinculados a la persona: se resuelven los nombres con la flota.
// Si la cuenta aún no trae dispositivoIds o no tiene ninguno, se muestra "—".
// Si hay asignaciones pero los nombres no están en la flota visible (flota aún
// cargando o equipo recién creado), se muestra el conteo para no esconderlas.
function textoEquipos(usuario: UsuarioPlataforma, flota: Dispositivo[]): string {
  const ids = usuario.dispositivoIds ?? [];
  if (ids.length === 0) return GUION;
  if (flota.length === 0) {
    return ids.length === 1 ? '1 equipo' : `${ids.length} equipos`;
  }
  const nombresPorId = new Map<string, string>();
  for (const equipo of flota) {
    nombresPorId.set(String(equipo.id), equipo.nombre);
    nombresPorId.set(equipo.idPublico, equipo.nombre);
  }
  const nombres = ids
    .map((id) => nombresPorId.get(String(id)))
    .filter((nombre): nombre is string => typeof nombre === 'string' && nombre !== '');
  if (nombres.length > 0) return nombres.join(', ');
  return ids.length === 1 ? '1 equipo' : `${ids.length} equipos`;
}

type Modal =
  | { modo: 'crear' }
  | { modo: 'editar'; usuario: UsuarioPlataforma }
  | { modo: 'baja'; usuario: UsuarioPlataforma }
  | { modo: 'ajustes'; usuario: UsuarioPlataforma };

interface PropsFormulario {
  usuario: UsuarioPlataforma | null;
  grupos: GrupoPlataforma[];
  gruposError: unknown;
  guardando: boolean;
  error: Error | null;
  onGuardar: (cuerpo: CreacionUsuarioPlataforma | ActualizacionUsuarioPlataforma) => void;
  onCancelar: () => void;
}

// En edición la clave vacía significa "no cambiar"; en creación es obligatoria.
// Las cuentas son personas que hacen ruta y el guardado nunca manda roles.
// Al crear la cuenta, el servidor siempre crea su equipo de rastreo.
function FormularioCuenta({
  usuario,
  grupos,
  gruposError,
  guardando,
  error,
  onGuardar,
  onCancelar,
}: PropsFormulario) {
  const [cuenta, setCuenta] = useState(usuario?.usuario ?? '');
  const [clave, setClave] = useState('');
  const [verClave, setVerClave] = useState(false);
  const [nombre, setNombre] = useState(usuario?.nombre ?? '');
  const [telefono, setTelefono] = useState(usuario?.telefono ?? '');
  const [cargo, setCargo] = useState(usuario?.cargo ?? '');
  const [grupoIds, setGrupoIds] = useState<string[]>(() =>
    (usuario?.grupos ?? []).map((grupo) => String(grupo.id)),
  );
  const [validacion, setValidacion] = useState('');

  function alternarGrupo(id: string) {
    setGrupoIds((actuales) =>
      actuales.includes(id) ? actuales.filter((otro) => otro !== id) : [...actuales, id],
    );
  }

  function enviar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault();
    if (guardando) return;
    if (!usuario && !cuenta.trim()) {
      setValidacion('Escribe el nombre con el que la persona va a entrar.');
      return;
    }
    if (!nombre.trim()) {
      setValidacion('Escribe el nombre completo de la persona.');
      return;
    }
    if (!usuario && !clave) {
      setValidacion('Escribe una contraseña para la cuenta nueva.');
      return;
    }
    if (clave && clave.length < CLAVE_MINIMA) {
      setValidacion(`La contraseña debe tener al menos ${CLAVE_MINIMA} caracteres.`);
      return;
    }
    setValidacion('');
    const gruposElegidos = grupos
      .filter((grupo) => grupoIds.includes(idTextoGrupo(grupo)))
      .map((grupo) => idOriginalGrupo(grupo));
    if (!usuario) {
      const cuerpo: CreacionUsuarioPlataforma = {
        usuario: cuenta.trim(),
        clave,
        nombre: nombre.trim(),
        // El equipo de rastreo se crea siempre junto con la cuenta (misma
        // transacción en el servidor): no hay caso en que no se quiera.
        crearEquipo: true,
      };
      const telefonoLimpio = telefono.trim();
      const cargoLimpio = cargo.trim();
      if (telefonoLimpio !== '') cuerpo.telefono = telefonoLimpio;
      if (cargoLimpio !== '') cuerpo.cargo = cargoLimpio;
      if (gruposElegidos.length > 0) cuerpo.grupoIds = gruposElegidos;
      onGuardar(cuerpo);
      return;
    }
    const cuerpo: ActualizacionUsuarioPlataforma = {
      nombre: nombre.trim(),
      telefono: telefono.trim() === '' ? null : telefono.trim(),
      cargo: cargo.trim() === '' ? null : cargo.trim(),
      grupoIds: gruposElegidos,
    };
    if (clave !== '') cuerpo.clave = clave;
    onGuardar(cuerpo);
  }

  return (
    <form onSubmit={enviar} noValidate>
      {usuario ? (
        <p className="apagado">
          Cuenta: <b>{usuario.usuario}</b>
        </p>
      ) : (
        <label className="campo">
          <span>Nombre para entrar</span>
          <input
            value={cuenta}
            onChange={(evento) => setCuenta(evento.target.value)}
            autoFocus
            autoComplete="off"
            placeholder="Por ejemplo: maria.p"
          />
        </label>
      )}
      <label className="campo">
        <span>{usuario ? 'Contraseña nueva (déjala vacía para no cambiarla)' : 'Contraseña'}</span>
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
        <span>Nombre de la persona</span>
        <input value={nombre} onChange={(evento) => setNombre(evento.target.value)} />
      </label>
      <div className="fila-form">
        <label className="campo">
          <span>Teléfono (opcional)</span>
          <input value={telefono} onChange={(evento) => setTelefono(evento.target.value)} autoComplete="off" />
        </label>
        <label className="campo">
          <span>Puesto (opcional)</span>
          <input
            value={cargo}
            onChange={(evento) => setCargo(evento.target.value)}
            placeholder="Por ejemplo: Zona norte"
          />
        </label>
      </div>
      <fieldset className="grupo-equipos">
        <legend>Grupos</legend>
        {gruposError !== null && gruposError !== undefined && (
          <p className="apagado">No se pudieron cargar los grupos; puedes guardar sin cambiarlos.</p>
        )}
        {grupos.length === 0 && gruposError == null && (
          <p className="apagado">Todavía no hay grupos creados.</p>
        )}
        <div className="equipos-asignados">
          {grupos.map((grupo) => (
            <label key={idTextoGrupo(grupo)}>
              <input
                type="checkbox"
                checked={grupoIds.includes(idTextoGrupo(grupo))}
                onChange={() => alternarGrupo(idTextoGrupo(grupo))}
              />
              <span>{grupo.nombre}</span>
            </label>
          ))}
        </div>
      </fieldset>
      {!usuario && (
        <p className="apagado">
          La cuenta se crea con su equipo y ya aparece en En vivo y Replay.
        </p>
      )}
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

interface PropsAjustes {
  usuario: UsuarioPlataforma;
  esquema: EntradaEsquemaAjustes[];
  cargandoEsquema: boolean;
  errorEsquema: unknown;
  guardando: boolean;
  error: Error | null;
  onGuardar: (ajustes: Record<string, number | boolean | string | null>) => void;
  onCancelar: () => void;
}

function textoDesdeAjuste(valor: unknown): string {
  if (typeof valor === 'number' && Number.isFinite(valor)) return String(valor);
  if (typeof valor === 'boolean') return valor ? 'true' : 'false';
  if (typeof valor === 'string') return valor;
  return '';
}

function EditorAjustes({
  usuario,
  esquema,
  cargandoEsquema,
  errorEsquema,
  guardando,
  error,
  onGuardar,
  onCancelar,
}: PropsAjustes) {
  const [valores, setValores] = useState<Record<string, string>>(() => {
    const iniciales: Record<string, string> = {};
    for (const entrada of esquema) {
      iniciales[entrada.clave] = textoDesdeAjuste(usuario.configApp?.[entrada.clave]);
    }
    return iniciales;
  });
  const [validacion, setValidacion] = useState('');

  function cambiar(clave: string, valor: string) {
    setValores((actuales) => ({ ...actuales, [clave]: valor }));
  }

  function enviar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault();
    if (guardando) return;
    const ajustes: Record<string, number | boolean | string | null> = {};
    for (const entrada of esquema) {
      const texto = (valores[entrada.clave] ?? '').trim();
      if (texto === '') {
        ajustes[entrada.clave] = null;
        continue;
      }
      if (entrada.tipo === 'numero') {
        const numero = Number(texto);
        if (!Number.isFinite(numero)) {
          setValidacion(`“${entrada.etiqueta}” debe ser un número.`);
          return;
        }
        if (entrada.min !== undefined && numero < entrada.min) {
          setValidacion(`“${entrada.etiqueta}” no puede ser menor de ${entrada.min}.`);
          return;
        }
        if (entrada.max !== undefined && numero > entrada.max) {
          setValidacion(`“${entrada.etiqueta}” no puede ser mayor de ${entrada.max}.`);
          return;
        }
        ajustes[entrada.clave] = numero;
        continue;
      }
      if (entrada.tipo === 'booleano') {
        if (texto !== 'true' && texto !== 'false') {
          setValidacion(`“${entrada.etiqueta}” tiene un valor que no se reconoce.`);
          return;
        }
        ajustes[entrada.clave] = texto === 'true';
        continue;
      }
      ajustes[entrada.clave] = texto;
    }
    setValidacion('');
    onGuardar(ajustes);
  }

  if (cargandoEsquema) {
    return (
      <div>
        <p className="vacio pulso">Cargando los ajustes disponibles…</p>
        <div className="dialogo-pie">
          <button type="button" className="suave" onClick={onCancelar}>
            Cerrar
          </button>
        </div>
      </div>
    );
  }

  if (errorEsquema) {
    return (
      <div>
        <p>No se pudieron cargar los ajustes de esta cuenta. Inténtalo más tarde.</p>
        <MensajeError error={errorEsquema} />
        <div className="dialogo-pie">
          <button type="button" className="suave" onClick={onCancelar}>
            Cerrar
          </button>
        </div>
      </div>
    );
  }

  if (esquema.length === 0) {
    return (
      <div>
        <p>Esta cuenta aún no tiene ajustes para cambiar.</p>
        <div className="dialogo-pie">
          <button type="button" className="suave" onClick={onCancelar}>
            Cerrar
          </button>
        </div>
      </div>
    );
  }

  return (
    <form onSubmit={enviar} noValidate>
      <p className="ayuda-campo">
        Solo se guarda lo que cambies. Un campo vacío deja ese ajuste sin definir.
      </p>
      {esquema.map((entrada) => (
        <label className="campo" key={entrada.clave}>
          <span>{entrada.etiqueta}</span>
          {entrada.tipo === 'numero' ? (
            <input
              type="number"
              step="any"
              min={entrada.min}
              max={entrada.max}
              value={valores[entrada.clave] ?? ''}
              onChange={(evento) => cambiar(entrada.clave, evento.target.value)}
            />
          ) : entrada.tipo === 'booleano' ? (
            <select
              value={valores[entrada.clave] ?? ''}
              onChange={(evento) => cambiar(entrada.clave, evento.target.value)}
            >
              <option value="">Sin definir</option>
              <option value="true">Activado</option>
              <option value="false">Desactivado</option>
            </select>
          ) : (
            <input
              type="text"
              value={valores[entrada.clave] ?? ''}
              onChange={(evento) => cambiar(entrada.clave, evento.target.value)}
            />
          )}
          {entrada.descripcion !== '' && <span className="apagado">{entrada.descripcion}</span>}
        </label>
      ))}
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
          {guardando ? 'Guardando…' : 'Guardar ajustes'}
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
    queryKey: ['usuarios-plataforma'],
    enabled: administrador,
    queryFn: () => traerUsuariosPlataforma(),
    staleTime: CACHE_PLATAFORMA_MS,
  });
  const grupos = useQuery({
    queryKey: ['grupos'],
    enabled: administrador,
    queryFn: () => traerGrupos(),
    staleTime: CACHE_PLATAFORMA_MS,
  });
  const esquema = useQuery({
    queryKey: ['esquema-ajustes'],
    enabled: administrador,
    queryFn: () => traerEsquemaAjustes(),
    staleTime: CACHE_PLATAFORMA_MS,
  });
  // La flota resuelve los nombres de la columna "Equipo(s)": la caché se
  // comparte con el resto de páginas mediante la clave común.
  const flota = useQuery({
    queryKey: CLAVE_FLOTA,
    enabled: administrador,
    queryFn: () => traerFlota(),
    staleTime: CACHE_FLOTA_CONSULTA_MS,
  });

  // Orden del plantel: primero las cuentas habilitadas y al final las dadas de
  // baja, cada bloque en orden alfabético por nombre (la cuenta desempata).
  const lista = useMemo(() => {
    return [...(usuarios.data ?? [])].sort((a, b) => {
      if (a.habilitado !== b.habilitado) return a.habilitado ? -1 : 1;
      const nombreA = (a.nombre.trim() !== '' ? a.nombre : a.usuario).trim();
      const nombreB = (b.nombre.trim() !== '' ? b.nombre : b.usuario).trim();
      const porNombre = nombreA.localeCompare(nombreB, 'es', { sensitivity: 'base', numeric: true });
      if (porNombre !== 0) return porNombre;
      return a.usuario.localeCompare(b.usuario, 'es', { sensitivity: 'base', numeric: true });
    });
  }, [usuarios.data]);
  const total = lista.length;
  const totalPaginas = Math.max(1, Math.ceil(total / TAMANO));
  const paginaSegura = Math.min(pagina, totalPaginas);
  const visibles = useMemo(
    () => lista.slice((paginaSegura - 1) * TAMANO, paginaSegura * TAMANO),
    [lista, paginaSegura],
  );
  const listaGrupos = useMemo(() => grupos.data ?? [], [grupos.data]);
  const listaEquipos = useMemo(() => flota.data?.datos ?? [], [flota.data]);

  function invalidar() {
    cliente.invalidateQueries({ queryKey: ['usuarios-plataforma'] });
  }

  const crear = useMutation({
    // El servidor crea la cuenta y su equipo de rastreo en la misma operación
    // (crearEquipo:true va siempre) y responde el equipo creado.
    mutationFn: (cuerpo: CreacionUsuarioPlataforma) =>
      api.post<RespuestaCreacionUsuarioPlataforma>('/api/v1/usuarios', cuerpo),
    onSuccess: ({ equipo }) => {
      invalidar();
      // El equipo nuevo aparece de inmediato en la caché compartida de flota
      // (En vivo, Replay, Inicio) sin esperar al siguiente sondeo.
      invalidarFlota(cliente);
      setExito(
        equipo
          ? `Cuenta y equipo creados. “${equipo.nombre}” ya aparece en En vivo y Replay.`
          : 'Cuenta creada.',
      );
      setModal(null);
    },
  });

  const actualizar = useMutation({
    mutationFn: ({ usuario, cuerpo }: { usuario: UsuarioPlataforma; cuerpo: ActualizacionUsuarioPlataforma }) =>
      api.patch<{ usuario: UsuarioPlataforma }>(`/api/v1/usuarios/${idEnUrl(usuario)}`, cuerpo),
    onSuccess: () => {
      invalidar();
      setExito('Cambios guardados.');
      setModal(null);
    },
  });

  const darDeBaja = useMutation({
    mutationFn: (usuario: UsuarioPlataforma) => api.borrar<void>(`/api/v1/usuarios/${idEnUrl(usuario)}`),
    onSuccess: () => {
      invalidar();
      setExito('Cuenta dada de baja. Puedes reactivarla cuando la necesites.');
      setModal(null);
    },
  });

  const reactivar = useMutation({
    mutationFn: (usuario: UsuarioPlataforma) =>
      api.patch<{ usuario: UsuarioPlataforma }>(`/api/v1/usuarios/${idEnUrl(usuario)}`, {
        habilitado: true,
      }),
    onSuccess: () => {
      invalidar();
      setExito('Cuenta reactivada.');
    },
  });

  const guardarAjustes = useMutation({
    mutationFn: ({
      usuario,
      ajustes,
    }: {
      usuario: UsuarioPlataforma;
      ajustes: Record<string, number | boolean | string | null>;
    }) =>
      api.patch<{ usuario: UsuarioPlataforma }>(`/api/v1/usuarios/${idEnUrl(usuario)}`, {
        configApp: ajustes,
      }),
    onSuccess: () => {
      invalidar();
      setExito('Ajustes guardados.');
      setModal(null);
    },
  });

  const cerrarModal = useCallback(() => setModal(null), []);

  function abrirCrear() {
    crear.reset();
    setModal({ modo: 'crear' });
  }

  function abrirEditar(usuario: UsuarioPlataforma) {
    actualizar.reset();
    setModal({ modo: 'editar', usuario });
  }

  function abrirBaja(usuario: UsuarioPlataforma) {
    darDeBaja.reset();
    setModal({ modo: 'baja', usuario });
  }

  function abrirAjustes(usuario: UsuarioPlataforma) {
    guardarAjustes.reset();
    setModal({ modo: 'ajustes', usuario });
  }

  function guardarCuenta(cuerpo: CreacionUsuarioPlataforma | ActualizacionUsuarioPlataforma) {
    if (!modal) return;
    if (modal.modo === 'crear') crear.mutate(cuerpo as CreacionUsuarioPlataforma);
    else if (modal.modo === 'editar') actualizar.mutate({ usuario: modal.usuario, cuerpo });
  }

  function confirmarBaja() {
    if (modal?.modo === 'baja') darDeBaja.mutate(modal.usuario);
  }

  function guardarAjustesDe(usuario: UsuarioPlataforma, ajustes: Record<string, number | boolean | string | null>) {
    guardarAjustes.mutate({ usuario, ajustes });
  }

  if (!administrador || esErrorDeEstado(usuarios.error, 403)) {
    return (
      <section>
        <EncabezadoPagina contexto="Administración" titulo="Usuarios" />
        <p className="aviso">Necesitas permisos de administrador para gestionar cuentas.</p>
      </section>
    );
  }

  return (
    <section>
      <EncabezadoPagina
        contexto="Administración"
        titulo="Usuarios"
        sub="Cuentas de acceso, personas y grupos. Solo administradores."
        acciones={
          <button type="button" className="principal con-icono" onClick={abrirCrear}>
            <Icono nombre="mas" />
            Agregar cuenta
          </button>
        }
      />

      <section className="seccion">
        <CabeceraSeccion
          titulo="Cuentas de acceso"
          cuenta={usuarios.data ? `${total} registradas` : 'Consultando…'}
        />
        {usuarios.isPending && <p className="vacio pulso">Cargando cuentas…</p>}
        {usuarios.error && <MensajeError error={usuarios.error} />}
        {usuarios.data && total === 0 && <EstadoVacio icono="usuarios">Todavía no hay cuentas.</EstadoVacio>}
        {visibles.length > 0 && (
          <>
            <div className="tabla-envoltura">
              <table className="tabla">
                <thead>
                  <tr>
                    <th>Cuenta</th>
                    <th>Persona</th>
                    <th>Teléfono</th>
                    <th>Puesto</th>
                    <th>Grupos</th>
                    <th>Equipos</th>
                    <th>Estado</th>
                    <th>Acciones</th>
                  </tr>
                </thead>
                <tbody>
                  {visibles.map((usuario) => (
                    <tr key={usuario.idPublico}>
                      <td>{usuario.usuario}</td>
                      <td>{usuario.nombre}</td>
                      <td>{usuario.telefono ?? GUION}</td>
                      <td>{usuario.cargo ?? GUION}</td>
                      <td>{textoGrupos(usuario)}</td>
                      <td>{textoEquipos(usuario, listaEquipos)}</td>
                      <td>
                        <ChipHabilitado habilitado={usuario.habilitado} />
                      </td>
                      <td>
                        <div className="fila-botones">
                          <button
                            type="button"
                            className="accion-icono"
                            title="Cambiar los datos de la cuenta"
                            aria-label={`Cambiar los datos de ${usuario.nombre}`}
                            onClick={() => abrirEditar(usuario)}
                          >
                            <Icono nombre="editar" />
                          </button>
                          <button
                            type="button"
                            className="accion-icono"
                            title="Cambiar los ajustes de la aplicación móvil"
                            aria-label={`Cambiar los ajustes de ${usuario.nombre}`}
                            onClick={() => abrirAjustes(usuario)}
                          >
                            <Icono nombre="configuracion" />
                          </button>
                          {usuario.habilitado ? (
                            <button
                              type="button"
                              className="accion-icono peligro"
                              title="Dar de baja la cuenta"
                              aria-label={`Dar de baja a ${usuario.nombre}`}
                              onClick={() => abrirBaja(usuario)}
                            >
                              <Icono nombre="basura" />
                            </button>
                          ) : (
                            <button
                              type="button"
                              className="suave"
                              title="Reactivar la cuenta"
                              aria-label={`Reactivar la cuenta de ${usuario.nombre}`}
                              onClick={() => reactivar.mutate(usuario)}
                              disabled={reactivar.isPending}
                            >
                              {reactivar.isPending ? 'Reactivando…' : 'Reactivar'}
                            </button>
                          )}
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
        {reactivar.error && <MensajeError error={reactivar.error} />}
      </section>

      {modal?.modo === 'crear' && (
        <Dialogo titulo="Agregar cuenta" onCerrar={cerrarModal}>
          <FormularioCuenta
            usuario={null}
            grupos={listaGrupos}
            gruposError={grupos.error}
            guardando={crear.isPending}
            error={crear.error}
            onGuardar={guardarCuenta}
            onCancelar={cerrarModal}
          />
        </Dialogo>
      )}

      {modal?.modo === 'editar' && (
        <Dialogo titulo="Cambiar los datos de la cuenta" onCerrar={cerrarModal}>
          <FormularioCuenta
            usuario={modal.usuario}
            grupos={listaGrupos}
            gruposError={grupos.error}
            guardando={actualizar.isPending}
            error={actualizar.error}
            onGuardar={guardarCuenta}
            onCancelar={cerrarModal}
          />
        </Dialogo>
      )}

      {modal?.modo === 'baja' && (
        <Dialogo titulo="Dar de baja la cuenta" onCerrar={cerrarModal}>
          <p>
            ¿Dar de baja a <b>{modal.usuario.nombre}</b> ({modal.usuario.usuario})? La persona dejará de
            poder entrar; sus datos y su equipo se conservan, y puedes reactivar la cuenta cuando la
            necesites.
          </p>
          {darDeBaja.error !== null && <MensajeError error={darDeBaja.error} />}
          <div className="dialogo-pie">
            <button type="button" className="suave" onClick={cerrarModal} disabled={darDeBaja.isPending}>
              Cancelar
            </button>
            <button type="button" className="peligro" onClick={confirmarBaja} disabled={darDeBaja.isPending}>
              {darDeBaja.isPending ? 'Dando de baja…' : 'Dar de baja'}
            </button>
          </div>
        </Dialogo>
      )}

      {modal?.modo === 'ajustes' && (
        <Dialogo titulo={`Ajustes de ${modal.usuario.nombre}`} onCerrar={cerrarModal}>
          <EditorAjustes
            usuario={modal.usuario}
            esquema={esquema.data ?? []}
            cargandoEsquema={esquema.isPending}
            errorEsquema={esquema.error}
            guardando={guardarAjustes.isPending}
            error={guardarAjustes.error}
            onGuardar={(ajustes) => guardarAjustesDe(modal.usuario, ajustes)}
            onCancelar={cerrarModal}
          />
        </Dialogo>
      )}

      {exito !== '' && <Toast mensaje={exito} onCerrar={cerrarExito} />}
    </section>
  );
}
