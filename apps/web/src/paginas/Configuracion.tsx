import { useCallback, useMemo, useState } from 'react';
import type { FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { Pagina } from '@contratos';
import { api, consulta } from '../api/cliente';
import { useSesion } from '../store/sesion';
import Icono from '../componentes/Icono';
import { ChipEstado, MensajeError } from './admin/comunes';
import { Toast } from './admin/Toast';
import type { ClaveConfiguracionEquipo, DispositivoGestion, ValorConfiguracionEquipo } from './admin/tipos';
import './admin.css';
import '../estilos/paginas.css';

interface OpcionCampo {
  valor: string;
  etiqueta: string;
}

type CampoConfig =
  | { clave: ClaveConfiguracionEquipo; tipo: 'numero' }
  | { clave: ClaveConfiguracionEquipo; tipo: 'booleano' }
  | { clave: ClaveConfiguracionEquipo; tipo: 'seleccion'; opciones: OpcionCampo[] };

// Whitelist de PUT /api/v1/fleet/{id}. La etiqueta visible es la clave exacta
// del contrato para no confundir parámetros.
const CAMPOS_CONFIG: CampoConfig[] = [
  { clave: 'mobile.intervalSeconds', tipo: 'numero' },
  { clave: 'mobile.minIntervalSeconds', tipo: 'numero' },
  { clave: 'mobile.distanceMeters', tipo: 'numero' },
  { clave: 'mobile.angleDegrees', tipo: 'numero' },
  {
    clave: 'mobile.accuracy',
    tipo: 'seleccion',
    opciones: [
      { valor: 'high', etiqueta: 'high' },
      { valor: 'medium', etiqueta: 'medium' },
      { valor: 'low', etiqueta: 'low' },
    ],
  },
  { clave: 'mobile.bufferEnabled', tipo: 'booleano' },
  { clave: 'mobile.bufferMax', tipo: 'numero' },
  {
    clave: 'mobile.bufferPolicy',
    tipo: 'seleccion',
    opciones: [
      { valor: 'drop_oldest', etiqueta: 'drop_oldest' },
      { valor: 'drop_newest', etiqueta: 'drop_newest' },
      { valor: 'stop', etiqueta: 'stop' },
    ],
  },
  { clave: 'mobile.ackTimeoutSeconds', tipo: 'numero' },
  { clave: 'mobile.maxRetries', tipo: 'numero' },
];

type ValoresBorrador = Record<ClaveConfiguracionEquipo, string>;

interface BorradorEquipo {
  nombre: string;
  valores: ValoresBorrador;
}

interface CuerpoEquipo {
  nombre?: string;
  configuracion?: Partial<Record<ClaveConfiguracionEquipo, ValorConfiguracionEquipo>>;
}

type ResultadoCampo = { ok: true; valor: ValorConfiguracionEquipo } | { ok: false; error: string };

type Analisis = { ok: true; cuerpo: CuerpoEquipo } | { ok: false; error: string };

const VALORES_VACIOS: ValoresBorrador = {
  'mobile.intervalSeconds': '',
  'mobile.minIntervalSeconds': '',
  'mobile.distanceMeters': '',
  'mobile.angleDegrees': '',
  'mobile.accuracy': '',
  'mobile.bufferEnabled': '',
  'mobile.bufferMax': '',
  'mobile.bufferPolicy': '',
  'mobile.ackTimeoutSeconds': '',
  'mobile.maxRetries': '',
};

// Un valor ausente o con un tipo inesperado se trata como "sin definir", nunca
// se completa con un valor inventado.
function valorDesdeConfiguracion(valor: unknown): ValorConfiguracionEquipo {
  if (typeof valor === 'number' && Number.isFinite(valor)) return valor;
  if (typeof valor === 'boolean' || typeof valor === 'string') return valor;
  return null;
}

function textoDesde(valor: ValorConfiguracionEquipo): string {
  return valor === null ? '' : String(valor);
}

function borradorDesde(dispositivo: DispositivoGestion): BorradorEquipo {
  const valores: ValoresBorrador = { ...VALORES_VACIOS };
  for (const campo of CAMPOS_CONFIG) {
    valores[campo.clave] = textoDesde(valorDesdeConfiguracion(dispositivo.configuracion?.[campo.clave]));
  }
  return { nombre: dispositivo.nombre, valores };
}

// Un campo vacío solo es válido cuando el parámetro no existía: el PUT hace
// merge y rechaza null, así que un valor definido no se puede quitar.
function parsearCampo(campo: CampoConfig, texto: string, original: ValorConfiguracionEquipo): ResultadoCampo {
  const limpio = texto.trim();
  if (limpio === '') {
    if (original !== null) {
      return { ok: false, error: `${campo.clave} ya está definido y la API no permite eliminarlo; escribe un valor nuevo.` };
    }
    return { ok: true, valor: null };
  }
  if (campo.tipo === 'numero') {
    const numero = Number(limpio);
    if (!Number.isFinite(numero)) return { ok: false, error: `${campo.clave} debe ser un número válido.` };
    return { ok: true, valor: numero };
  }
  if (campo.tipo === 'booleano') {
    if (limpio === 'true') return { ok: true, valor: true };
    if (limpio === 'false') return { ok: true, valor: false };
    return { ok: false, error: `${campo.clave} tiene un valor inesperado.` };
  }
  return { ok: true, valor: limpio };
}

// Devuelve únicamente los campos distintos al valor original.
function calcularCambios(dispositivo: DispositivoGestion, borrador: BorradorEquipo): Analisis {
  const nombre = borrador.nombre.trim();
  if (!nombre) return { ok: false, error: 'El nombre del equipo no puede quedar vacío.' };
  const cuerpo: CuerpoEquipo = {};
  if (nombre !== dispositivo.nombre) cuerpo.nombre = nombre;
  const configuracion: Partial<Record<ClaveConfiguracionEquipo, ValorConfiguracionEquipo>> = {};
  let hayConfiguracion = false;
  for (const campo of CAMPOS_CONFIG) {
    const original = valorDesdeConfiguracion(dispositivo.configuracion?.[campo.clave]);
    const resultado = parsearCampo(campo, borrador.valores[campo.clave], original);
    if (!resultado.ok) return resultado;
    if (resultado.valor !== original) {
      configuracion[campo.clave] = resultado.valor;
      hayConfiguracion = true;
    }
  }
  if (hayConfiguracion) cuerpo.configuracion = configuracion;
  return { ok: true, cuerpo };
}

// Aplica localmente lo aceptado por la API cuando la respuesta no trae la
// configuración completa (por ejemplo, para cuentas sin acceso a ella).
function aplicarCambios(base: BorradorEquipo, cuerpo: CuerpoEquipo): BorradorEquipo {
  const siguiente: BorradorEquipo = { nombre: cuerpo.nombre ?? base.nombre, valores: { ...base.valores } };
  for (const campo of CAMPOS_CONFIG) {
    const valor = cuerpo.configuracion?.[campo.clave];
    if (valor !== undefined) siguiente.valores[campo.clave] = textoDesde(valor);
  }
  return siguiente;
}

export default function Configuracion() {
  const administrador = useSesion((estado) => estado.usuario?.administrador === true);
  const cliente = useQueryClient();
  const [busqueda, setBusqueda] = useState('');
  const [seleccionId, setSeleccionId] = useState('');
  const [seleccionado, setSeleccionado] = useState<DispositivoGestion | null>(null);
  const [original, setOriginal] = useState<BorradorEquipo | null>(null);
  const [borrador, setBorrador] = useState<BorradorEquipo | null>(null);
  const [exito, setExito] = useState('');
  const cerrarExito = useCallback(() => setExito(''), []);

  const equipos = useQuery({
    queryKey: ['flota', 'todos'],
    queryFn: () => api.get<Pagina<DispositivoGestion>>(`/api/v1/fleet${consulta({ tamano: 200 })}`),
  });

  const guardar = useMutation({
    mutationFn: ({ id, cuerpo }: { id: string; cuerpo: CuerpoEquipo }) =>
      api.put<{ dispositivo: DispositivoGestion }>(`/api/v1/fleet/${encodeURIComponent(id)}`, cuerpo),
    onSuccess: (datos, variables) => {
      cliente.invalidateQueries({ queryKey: ['flota'] });
      setExito('Cambios guardados.');
      const respuesta = datos.dispositivo;
      if (respuesta && respuesta.configuracion !== undefined && respuesta.configuracion !== null) {
        const base = borradorDesde(respuesta);
        setOriginal(base);
        setBorrador(base);
        setSeleccionado(respuesta);
        setSeleccionId(respuesta.idPublico);
      } else if (original) {
        const base = aplicarCambios(original, variables.cuerpo);
        setOriginal(base);
        setBorrador(base);
        if (respuesta) {
          setSeleccionado(respuesta);
          setSeleccionId(respuesta.idPublico);
        }
      }
    },
  });

  const filtro = busqueda.trim().toLowerCase();
  const flota = useMemo(() => {
    const datos = equipos.data?.datos ?? [];
    if (!filtro) return datos;
    return datos.filter(
      (dispositivo) => dispositivo.nombre.toLowerCase().includes(filtro) || dispositivo.identificadorUnico.toLowerCase().includes(filtro),
    );
  }, [equipos.data, filtro]);

  const analisis = seleccionado && borrador ? calcularCambios(seleccionado, borrador) : null;
  const hayCambios = analisis?.ok === true && Object.keys(analisis.cuerpo).length > 0;
  const errorCampos = analisis?.ok === false ? analisis.error : '';

  function seleccionar(dispositivo: DispositivoGestion) {
    const base = borradorDesde(dispositivo);
    setSeleccionId(dispositivo.idPublico);
    setSeleccionado(dispositivo);
    setOriginal(base);
    setBorrador(base);
    guardar.reset();
  }

  function cambiarValor(clave: ClaveConfiguracionEquipo, valor: string) {
    setBorrador((actual) => (actual ? { ...actual, valores: { ...actual.valores, [clave]: valor } } : actual));
  }

  function enviar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault();
    if (!administrador || !seleccionado || !analisis || !analisis.ok || guardar.isPending) return;
    if (Object.keys(analisis.cuerpo).length === 0) return;
    guardar.reset();
    guardar.mutate({ id: seleccionado.idPublico, cuerpo: analisis.cuerpo });
  }

  return (
    <section>
      <header className="cabecera-pagina">
        <div>
          <h1>Configuración</h1>
          <p className="sub">Parámetros de la aplicación móvil por unidad. Solo administradores.</p>
        </div>
      </header>

      {!administrador && (
        <p className="aviso">Necesitas permisos de administrador para cambiar la configuración; el formulario está deshabilitado.</p>
      )}

      <section className="seccion">
        <div className="bloque">
          <header className="cabecera-seccion">
            <h2>Unidades</h2>
            <span className="cuenta">
              {flota.length} de {equipos.data?.total ?? 0}
            </span>
          </header>
          <label className="campo">
            <span>Buscar unidad</span>
            <span className="busqueda">
              <Icono nombre="buscar" />
              <input
                type="search"
                value={busqueda}
                onChange={(evento) => setBusqueda(evento.target.value)}
                placeholder="Nombre o identificador"
              />
            </span>
          </label>
          {equipos.isPending && <p className="vacio">Cargando unidades…</p>}
          {equipos.error && <MensajeError error={equipos.error} />}
          {equipos.data && flota.length === 0 && (
            <p className="vacio">No hay unidades que coincidan con la búsqueda.</p>
          )}
          {flota.length > 0 && (
            <div className="tabla-envoltura">
              <table className="tabla">
                <thead>
                  <tr>
                    <th>Selección</th>
                    <th>Unidad</th>
                    <th>Identificador</th>
                    <th>Estado</th>
                  </tr>
                </thead>
                <tbody>
                  {flota.map((dispositivo) => (
                    <tr
                      key={dispositivo.idPublico}
                      className={dispositivo.idPublico === seleccionId ? 'seleccionada' : ''}
                    >
                      <td>
                        <input
                          type="radio"
                          name="equipo"
                          checked={dispositivo.idPublico === seleccionId}
                          onChange={() => seleccionar(dispositivo)}
                          aria-label={`Seleccionar ${dispositivo.nombre}`}
                        />
                      </td>
                      <td>{dispositivo.nombre}</td>
                      <td className="mono">{dispositivo.identificadorUnico}</td>
                      <td>
                        <ChipEstado estado={dispositivo.estado} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </section>

      <section className="seccion">
        <div className="bloque">
          <header className="cabecera-seccion">
            <h2>Configuración del equipo</h2>
            <span className="cuenta">{seleccionado ? seleccionado.nombre : 'Sin unidad seleccionada'}</span>
          </header>
          {!seleccionado && <p className="vacio">Selecciona una unidad para ver y editar su configuración.</p>}
          {seleccionado && borrador && (
            <form onSubmit={enviar}>
              <p className="ayuda-campo">
                Solo se envían los campos modificados. Vacío significa que el parámetro no está definido.
              </p>
              <label className="campo">
                <span>Nombre del equipo</span>
                <input
                  value={borrador.nombre}
                  onChange={(evento) => setBorrador({ ...borrador, nombre: evento.target.value })}
                  disabled={!administrador}
                />
              </label>
              <div className="fila-form">
                {CAMPOS_CONFIG.map((campo) => (
                  <label className="campo" key={campo.clave}>
                    <span className="mono">{campo.clave}</span>
                    {campo.tipo === 'numero' ? (
                      <input
                        type="number"
                        step="any"
                        min="0"
                        value={borrador.valores[campo.clave]}
                        onChange={(evento) => cambiarValor(campo.clave, evento.target.value)}
                        disabled={!administrador}
                      />
                    ) : (
                      <select
                        value={borrador.valores[campo.clave]}
                        onChange={(evento) => cambiarValor(campo.clave, evento.target.value)}
                        disabled={!administrador}
                      >
                        <option value="">Sin definir</option>
                        {campo.tipo === 'booleano' ? (
                          <>
                            <option value="true">Activado</option>
                            <option value="false">Desactivado</option>
                          </>
                        ) : (
                          campo.opciones.map((opcion) => (
                            <option key={opcion.valor} value={opcion.valor}>
                              {opcion.etiqueta}
                            </option>
                          ))
                        )}
                      </select>
                    )}
                  </label>
                ))}
              </div>
              {errorCampos !== '' && (
                <p className="error" role="alert">
                  {errorCampos}
                </p>
              )}
              {guardar.error !== null && <MensajeError error={guardar.error} />}
              {administrador && (
                <div className="fila-botones">
                  <button type="submit" className="principal" disabled={guardar.isPending || !hayCambios || errorCampos !== ''}>
                    {guardar.isPending ? 'Guardando…' : 'Guardar cambios'}
                  </button>
                  {!hayCambios && errorCampos === '' && <span className="apagado">Sin cambios por guardar.</span>}
                </div>
              )}
            </form>
          )}
        </div>
      </section>

      {exito !== '' && <Toast mensaje={exito} onCerrar={cerrarExito} />}
    </section>
  );
}
