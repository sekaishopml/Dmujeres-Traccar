import { useCallback, useMemo, useState } from 'react';
import type { FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type {
  ActualizacionUsuarioPlataforma,
  CreacionUsuarioPlataforma,
  Dispositivo,
  EntradaEsquemaAjustes,
  EquipoCreadoConCuenta,
  GrupoPlataforma,
  RespuestaCreacionUsuarioPlataforma,
  UsuarioPlataforma,
} from '@contratos';
import { api } from '../api/cliente';
import { useSesion } from '../store/sesion';
import { GUION } from '../util/formato';
import Icono from '../componentes/Icono';
import {
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
  guardarEquiposDeUsuario,
  traerEquiposDeUsuario,
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

function claveEquipo(equipo: { id: number | string; idPublico?: string }): string {
  return String(equipo.idPublico ?? equipo.id);
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
  equipos: Dispositivo[];
  cargandoEquipos: boolean;
  avisoEquipos: string | null;
  equiposIniciales: string[];
  // Asignaciones que no están en la flota visible: no se muestran, pero se
  // conservan al guardar para no borrarlas con el reemplazo.
  equiposExtras: (number | string)[];
  guardando: boolean;
  error: Error | null;
  onGuardar: (
    cuerpo: CreacionUsuarioPlataforma | ActualizacionUsuarioPlataforma,
    dispositivoIds: (number | string)[],
  ) => void;
  onCancelar: () => void;
}

// En edición la clave vacía significa "no cambiar"; en creación es obligatoria.
// Las cuentas son personas que hacen ruta y el guardado nunca manda roles.
function FormularioCuenta({
  usuario,
  grupos,
  gruposError,
  equipos,
  cargandoEquipos,
  avisoEquipos,
  equiposIniciales,
  equiposExtras,
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
  const [equipoIds, setEquipoIds] = useState<string[]>(() => equiposIniciales);
  const [crearEquipo, setCrearEquipo] = useState(true);
  const [validacion, setValidacion] = useState('');

  function alternarGrupo(id: string) {
    setGrupoIds((actuales) =>
      actuales.includes(id) ? actuales.filter((otro) => otro !== id) : [...actuales, id],
    );
  }

  function alternarEquipo(id: string) {
    setEquipoIds((actuales) =>
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
    // El reemplazo manda el id interno cuando se conoce; lo que no está en la
    // flota visible se conserva tal cual para no borrarlo sin querer.
    const porClave = new Map<string, Dispositivo>();
    for (const equipo of equipos) {
      porClave.set(claveEquipo(equipo), equipo);
      porClave.set(String(equipo.id), equipo);
    }
    const dispositivoIds: (number | string)[] = [
      ...equiposExtras,
      ...equipoIds.map((clave) => porClave.get(clave)?.id ?? clave),
    ];
    if (!usuario) {
      const cuerpo: CreacionUsuarioPlataforma = {
        usuario: cuenta.trim(),
        clave,
        nombre: nombre.trim(),
      };
      const telefonoLimpio = telefono.trim();
      const cargoLimpio = cargo.trim();
      if (telefonoLimpio !== '') cuerpo.telefono = telefonoLimpio;
      if (cargoLimpio !== '') cuerpo.cargo = cargoLimpio;
      if (gruposElegidos.length > 0) cuerpo.grupoIds = gruposElegidos;
      // El contrato pide crearEquipo:true para que el servidor cree el equipo
      // de rastreo junto con la cuenta. Si la casilla está apagada, no se manda.
      if (crearEquipo) cuerpo.crearEquipo = true;
      onGuardar(cuerpo, dispositivoIds);
      return;
    }
    const cuerpo: ActualizacionUsuarioPlataforma = {
      nombre: nombre.trim(),
      telefono: telefono.trim() === '' ? null : telefono.trim(),
      cargo: cargo.trim() === '' ? null : cargo.trim(),
      grupoIds: gruposElegidos,
    };
    if (clave !== '') cuerpo.clave = clave;
    onGuardar(cuerpo, dispositivoIds);
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
        <span>{usuario ? 'Contraseña nueva (vacía para no cambiarla)' : 'Contraseña'}</span>
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
            placeholder="Por ejemplo: Ruta norte"
          />
        </label>
      </div>
      <fieldset className="grupo-equipos">
        <legend>Grupos</legend>
        {gruposError !== null && gruposError !== undefined && (
          <p className="apagado">No se pudieron traer los grupos; puedes guardar sin cambiarlos.</p>
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
        <div className="campo">
          <label className="equipo-auto">
            <input
              type="checkbox"
              checked={crearEquipo}
              onChange={(evento) => setCrearEquipo(evento.target.checked)}
            />
            <span>Crear equipo de rastreo</span>
          </label>
          <p className="apagado">
            Se crea con el nombre de la cuenta en minúsculas. Así aparece en Replay y En vivo, y en la
            app se configura ese mismo nombre como ID de equipo.
          </p>
        </div>
      )}
      <fieldset className="grupo-equipos">
        <legend>Equipos que puede ver</legend>
        {avisoEquipos !== null && <p className="apagado">{avisoEquipos}</p>}
        {cargandoEquipos && <p className="apagado">Trayendo los equipos de la flota…</p>}
        {!cargandoEquipos && equipos.length === 0 && avisoEquipos === null && (
          <p className="apagado">Todavía no hay equipos en la flota.</p>
        )}
        <div className="equipos-asignados">
          {equipos.map((equipo) => (
            <label key={claveEquipo(equipo)}>
              <input
                type="checkbox"
                checked={equipoIds.includes(claveEquipo(equipo))}
                onChange={() => alternarEquipo(claveEquipo(equipo))}
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
        <p className="vacio">Trayendo los ajustes disponibles…</p>
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
        <p>Todavía no se pudieron traer los ajustes de esta cuenta. Inténtalo más tarde.</p>
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
        Cambia solo lo necesario. Vacío significa que ese ajuste queda sin definir.
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
  });
  const grupos = useQuery({
    queryKey: ['grupos'],
    enabled: administrador,
    queryFn: () => traerGrupos(),
  });
  const esquema = useQuery({
    queryKey: ['esquema-ajustes'],
    enabled: administrador,
    queryFn: () => traerEsquemaAjustes(),
  });
  // La flota alimenta el selector "Equipos que puede ver": la caché se comparte
  // con el resto de páginas mediante la clave común.
  const flota = useQuery({
    queryKey: CLAVE_FLOTA,
    enabled: administrador,
    queryFn: () => traerFlota(),
  });

  const lista = useMemo(() => usuarios.data ?? [], [usuarios.data]);
  const total = lista.length;
  const totalPaginas = Math.max(1, Math.ceil(total / TAMANO));
  const paginaSegura = Math.min(pagina, totalPaginas);
  const visibles = useMemo(
    () => lista.slice((paginaSegura - 1) * TAMANO, paginaSegura * TAMANO),
    [lista, paginaSegura],
  );
  const listaGrupos = useMemo(() => grupos.data ?? [], [grupos.data]);
  const listaEquipos = useMemo(() => flota.data?.datos ?? [], [flota.data]);

  // Equipos asignados a la cuenta en edición. Si GET /api/v1/usuarios ya trae
  // dispositivoIds se usa eso y no se pide nada; si no, se consulta el endpoint
  // de equipos (otro frente lo implementa; puede no existir todavía).
  const usuarioEnEdicion = modal?.modo === 'editar' ? modal.usuario : null;
  const necesitaEquipos = usuarioEnEdicion != null && usuarioEnEdicion.dispositivoIds == null;
  const equiposDeUsuario = useQuery({
    queryKey: ['usuario-equipos', usuarioEnEdicion ? idEnUrl(usuarioEnEdicion) : 'ninguno'],
    queryFn: () => traerEquiposDeUsuario(idEnUrl(usuarioEnEdicion as UsuarioPlataforma)),
    enabled: administrador && necesitaEquipos,
    retry: false,
  });

  // Fuente de asignaciones: lo que ya trae la cuenta o lo que devolvió el
  // endpoint de equipos. Null mientras se espera la consulta.
  const fuenteEquipos = useMemo<(number | string)[] | null>(() => {
    if (!usuarioEnEdicion) return [];
    if (usuarioEnEdicion.dispositivoIds != null) return usuarioEnEdicion.dispositivoIds;
    if (equiposDeUsuario.data) {
      return equiposDeUsuario.data.map((equipo) => equipo.idPublico ?? equipo.id);
    }
    if (equiposDeUsuario.isPending) return null;
    return [];
  }, [usuarioEnEdicion, equiposDeUsuario.data, equiposDeUsuario.isPending]);

  // Las claves se normalizan contra la flota para que el marcado funcione
  // venga el id interno o el público; lo que no está en la flota visible se
  // conserva aparte para no borrarlo al guardar.
  const { equiposIniciales, equiposExtras } = useMemo(() => {
    if (fuenteEquipos == null) return { equiposIniciales: [] as string[], equiposExtras: [] as (number | string)[] };
    const porId = new Map<string, Dispositivo>();
    for (const equipo of listaEquipos) {
      porId.set(String(equipo.id), equipo);
      if (equipo.idPublico) porId.set(equipo.idPublico, equipo);
    }
    const claves: string[] = [];
    const extras: (number | string)[] = [];
    for (const id of fuenteEquipos) {
      const equipo = porId.get(String(id));
      if (equipo) {
        const clave = claveEquipo(equipo);
        if (!claves.includes(clave)) claves.push(clave);
      } else if (!extras.some((otro) => String(otro) === String(id))) {
        extras.push(id);
      }
    }
    return { equiposIniciales: claves, equiposExtras: extras };
  }, [fuenteEquipos, listaEquipos]);

  const avisoEquipos = useMemo<string | null>(() => {
    if (flota.error) return 'No se pudieron traer los equipos de la flota; puedes guardar sin cambiarlos.';
    if (equiposDeUsuario.error) {
      if (esErrorDeEstado(equiposDeUsuario.error, 404)) {
        return 'El servidor aún no guarda equipos por cuenta: lo que marques se guardará cuando esté listo.';
      }
      return 'No se pudieron traer los equipos asignados; revisa la lista completa antes de guardar.';
    }
    return null;
  }, [flota.error, equiposDeUsuario.error]);

  function invalidar() {
    cliente.invalidateQueries({ queryKey: ['usuarios-plataforma'] });
  }

  const crear = useMutation({
    mutationFn: async ({
      cuerpo,
      equipoIds,
    }: {
      cuerpo: CreacionUsuarioPlataforma;
      equipoIds: (number | string)[];
    }) => {
      // Contrato con el otro frente: con crearEquipo:true el servidor crea la
      // cuenta + el dispositivo (identificador = usuario en minúsculas) +
      // asignación, y responde equipo:{id,idPublico,nombre,identificador} o
      // null. Si el servidor aún no lo soporta, la cuenta se crea igual.
      const quiereEquipo = cuerpo.crearEquipo === true;
      let respuesta: RespuestaCreacionUsuarioPlataforma;
      let equipoAutoPendiente = false;
      try {
        respuesta = await api.post<RespuestaCreacionUsuarioPlataforma>('/api/v1/usuarios', cuerpo);
      } catch (error) {
        // Servidor anterior que rechaza el campo desconocido: se reintenta sin
        // él para que la cuenta se cree igual y se avisa al final.
        if (quiereEquipo && esErrorDeEstado(error, 400)) {
          const { crearEquipo: _omitido, ...cuerpoSinEquipo } = cuerpo;
          void _omitido;
          respuesta = await api.post<RespuestaCreacionUsuarioPlataforma>(
            '/api/v1/usuarios',
            cuerpoSinEquipo,
          );
          equipoAutoPendiente = true;
        } else {
          throw error;
        }
      }
      const equipo: EquipoCreadoConCuenta | null = respuesta.equipo ?? null;
      if (quiereEquipo && !equipo && !equipoAutoPendiente) {
        equipoAutoPendiente = true;
      }
      // Si el servidor ya asignó el equipo nuevo, no se pisa con el reemplazo:
      // se suma su id a la selección manual (si la hay). Sin selección manual
      // no hace falta el PUT porque la asignación ya quedó hecha.
      let idsParaGuardar = equipoIds;
      if (equipo) {
        const idAuto = equipo.idPublico ?? equipo.id;
        const yaIncluido = equipoIds.some(
          (id) => String(id) === String(equipo.id) || String(id) === String(idAuto),
        );
        if (!yaIncluido && equipoIds.length > 0) idsParaGuardar = [...equipoIds, idAuto];
      }
      let equiposPendientes = false;
      if (idsParaGuardar.length > 0 && !(equipo && equipoIds.length === 0)) {
        try {
          await guardarEquiposDeUsuario(idEnUrl(respuesta.usuario), idsParaGuardar);
        } catch (error) {
          if (!esErrorDeEstado(error, 404)) throw error;
          equiposPendientes = true;
        }
      }
      return { usuario: respuesta.usuario, equipo, equipoAutoPendiente, equiposPendientes };
    },
    onSuccess: ({ equipo, equipoAutoPendiente, equiposPendientes }) => {
      invalidar();
      cliente.invalidateQueries({ queryKey: CLAVE_FLOTA });
      if (equipo) {
        setExito(`Cuenta y equipo creados. “${equipo.nombre}” ya aparece en Replay y En vivo.`);
      } else if (equipoAutoPendiente) {
        setExito(
          'Cuenta creada, pero el servidor aún no crea el equipo automático. Se puede vincular uno en “Cambiar los datos” cuando esté listo.',
        );
      } else if (equiposPendientes) {
        setExito('Cuenta creada, pero el servidor aún no guarda los equipos por cuenta.');
      } else {
        setExito('Cuenta creada.');
      }
      setModal(null);
    },
  });

  const actualizar = useMutation({
    mutationFn: async ({
      usuario,
      cuerpo,
      equipoIds,
    }: {
      usuario: UsuarioPlataforma;
      cuerpo: ActualizacionUsuarioPlataforma;
      equipoIds: (number | string)[];
    }) => {
      const respuesta = await api.patch<{ usuario: UsuarioPlataforma }>(
        `/api/v1/usuarios/${idEnUrl(usuario)}`,
        cuerpo,
      );
      let equiposPendientes = false;
      try {
        await guardarEquiposDeUsuario(idEnUrl(usuario), equipoIds);
      } catch (error) {
        if (!esErrorDeEstado(error, 404)) throw error;
        equiposPendientes = true;
      }
      return { usuario: respuesta.usuario, equiposPendientes };
    },
    onSuccess: ({ equiposPendientes }) => {
      invalidar();
      setExito(
        equiposPendientes
          ? 'Cambios guardados, pero el servidor aún no guarda los equipos por cuenta.'
          : 'Cambios guardados.',
      );
      setModal(null);
    },
  });

  const darDeBaja = useMutation({
    mutationFn: (usuario: UsuarioPlataforma) => api.borrar<void>(`/api/v1/usuarios/${idEnUrl(usuario)}`),
    onSuccess: () => {
      invalidar();
      setExito('Cuenta dada de baja. Se puede volver a dar de alta cuando se necesite.');
      setModal(null);
    },
  });

  const darDeAlta = useMutation({
    mutationFn: (usuario: UsuarioPlataforma) =>
      api.patch<{ usuario: UsuarioPlataforma }>(`/api/v1/usuarios/${idEnUrl(usuario)}`, {
        habilitado: true,
      }),
    onSuccess: () => {
      invalidar();
      setExito('Cuenta dada de alta de nuevo.');
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

  function guardarCuenta(
    cuerpo: CreacionUsuarioPlataforma | ActualizacionUsuarioPlataforma,
    equipoIds: (number | string)[],
  ) {
    if (!modal) return;
    if (modal.modo === 'crear') crear.mutate({ cuerpo: cuerpo as CreacionUsuarioPlataforma, equipoIds });
    else if (modal.modo === 'editar') actualizar.mutate({ usuario: modal.usuario, cuerpo, equipoIds });
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
        <header className="cabecera-pagina">
          <div>
            <h1>Usuarios</h1>
          </div>
        </header>
        <div className="bloque">
          <p className="aviso">Necesitas permisos de administrador para gestionar cuentas.</p>
        </div>
      </section>
    );
  }

  return (
    <section>
      <header className="cabecera-pagina">
        <div>
          <h1>Usuarios</h1>
          <p className="sub">Cuentas de acceso, grupos y ajustes. Solo administradores.</p>
        </div>
        <div className="empuja" />
        <button type="button" className="principal con-icono" onClick={abrirCrear}>
          <Icono nombre="mas" />
          Agregar cuenta
        </button>
      </header>

      <section className="seccion">
        <div className="bloque">
          <header className="cabecera-seccion">
            <h2>Cuentas de acceso</h2>
            <span className="cuenta">
              {usuarios.data ? `${total} registradas` : 'Consultando…'}
            </span>
          </header>
          {usuarios.isPending && <p className="vacio">Cargando cuentas…</p>}
          {usuarios.error && <MensajeError error={usuarios.error} />}
          {usuarios.data && total === 0 && <p className="vacio">No hay cuentas registradas.</p>}
          {visibles.length > 0 && (
            <>
              <div className="tabla-envoltura">
                <table className="tabla">
                  <thead>
                    <tr>
                      <th>Cuenta</th>
                      <th>Nombre completo</th>
                      <th>Teléfono</th>
                      <th>Puesto</th>
                      <th>Grupo(s)</th>
                      <th>Equipo(s)</th>
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
                              title="Cambiar los ajustes de la aplicación"
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
                                title="Volver a dar de alta la cuenta"
                                aria-label={`Volver a dar de alta a ${usuario.nombre}`}
                                onClick={() => darDeAlta.mutate(usuario)}
                                disabled={darDeAlta.isPending}
                              >
                                {darDeAlta.isPending ? 'Dando de alta…' : 'Dar de alta'}
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
          {darDeAlta.error && <MensajeError error={darDeAlta.error} />}
        </div>
      </section>

      {modal?.modo === 'crear' && (
        <Dialogo titulo="Agregar cuenta" onCerrar={cerrarModal}>
          <FormularioCuenta
            usuario={null}
            grupos={listaGrupos}
            gruposError={grupos.error}
            equipos={listaEquipos}
            cargandoEquipos={flota.isPending}
            avisoEquipos={avisoEquipos}
            equiposIniciales={[]}
            equiposExtras={[]}
            guardando={crear.isPending}
            error={crear.error}
            onGuardar={guardarCuenta}
            onCancelar={cerrarModal}
          />
        </Dialogo>
      )}

      {modal?.modo === 'editar' && (
        <Dialogo titulo="Cambiar los datos de la cuenta" onCerrar={cerrarModal}>
          {fuenteEquipos == null ? (
            <div>
              <p className="vacio">Trayendo los equipos que puede ver…</p>
              <div className="dialogo-pie">
                <button type="button" className="suave" onClick={cerrarModal}>
                  Cerrar
                </button>
              </div>
            </div>
          ) : (
            <FormularioCuenta
              usuario={modal.usuario}
              grupos={listaGrupos}
              gruposError={grupos.error}
              equipos={listaEquipos}
              cargandoEquipos={flota.isPending}
              avisoEquipos={avisoEquipos}
              equiposIniciales={equiposIniciales}
              equiposExtras={equiposExtras}
              guardando={actualizar.isPending}
              error={actualizar.error}
              onGuardar={guardarCuenta}
              onCancelar={cerrarModal}
            />
          )}
        </Dialogo>
      )}

      {modal?.modo === 'baja' && (
        <Dialogo titulo="Dar de baja la cuenta" onCerrar={cerrarModal}>
          <p>
            ¿Dar de baja a <b>{modal.usuario.nombre}</b> ({modal.usuario.usuario})? La persona dejará de
            poder entrar, pero sus datos se conservan y se puede volver a dar de alta.
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
