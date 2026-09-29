import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { SearchX, Settings2 } from 'lucide-react';
import { toast } from 'sonner';
import type { Pagina } from '@contratos';
import { api, consulta } from '@/lib/api';
import { useSesion } from '@/lib/sesion';
import { mensajeError } from '@/dominio/errores';
import { CLAVE_FLOTA } from '@/dominio/datos';
import type { ClaveConfiguracionEquipo, DispositivoGestion, ValorConfiguracionEquipo } from '@/dominio/admin';
import { AccionesPagina } from '@/componentes/marco/Marco';
import { Avatar } from '@/componentes/ui/Avatar';
import { Boton } from '@/componentes/ui/Boton';
import { Campo, Entrada, Selector } from '@/componentes/ui/Campo';
import { ChipEstado } from '@/componentes/ui/ChipEstado';
import { Cargando, ErrorCarga, Vacio } from '@/componentes/ui/Estados';
import { CabeceraTarjeta, Tarjeta } from '@/componentes/ui/Tarjeta';
import { Fila, Tabla, Td, Th } from '@/componentes/ui/Tabla';
import { AvisoError, Buscador, CACHE_FLOTA_CONSULTA_MS } from '@/componentes/admin/comunes';

interface OpcionCampo {
  valor: string;
  etiqueta: string;
}

type CampoConfig = {
  clave: ClaveConfiguracionEquipo;
  etiqueta: string;
  ayuda: string;
} & ({ tipo: 'numero' } | { tipo: 'booleano' } | { tipo: 'seleccion'; opciones: OpcionCampo[] });

interface SeccionConfig {
  titulo: string;
  detalle: string;
  campos: CampoConfig[];
}

// Whitelist de PUT /api/v1/fleet/{id}. Se muestra también la clave exacta del
// contrato para no confundir parámetros con la aplicación móvil.
const SECCIONES: SeccionConfig[] = [
  {
    titulo: 'Frecuencia de reporte',
    detalle: 'Cada cuánto y con qué precisión el teléfono manda su ubicación.',
    campos: [
      {
        clave: 'mobile.intervalSeconds',
        tipo: 'numero',
        etiqueta: 'Intervalo de envío (segundos)',
        ayuda: 'Tiempo entre un reporte y el siguiente.',
      },
      {
        clave: 'mobile.minIntervalSeconds',
        tipo: 'numero',
        etiqueta: 'Intervalo mínimo (segundos)',
        ayuda: 'Nunca se reporta más seguido que esto.',
      },
      {
        clave: 'mobile.distanceMeters',
        tipo: 'numero',
        etiqueta: 'Distancia mínima (metros)',
        ayuda: 'Recorrido necesario para generar un reporte nuevo.',
      },
      {
        clave: 'mobile.angleDegrees',
        tipo: 'numero',
        etiqueta: 'Cambio de rumbo (grados)',
        ayuda: 'Giro que dispara un reporte aunque no se haya avanzado la distancia.',
      },
      {
        clave: 'mobile.accuracy',
        tipo: 'seleccion',
        etiqueta: 'Precisión del GPS',
        ayuda: 'Más precisión consume más batería.',
        opciones: [
          { valor: 'high', etiqueta: 'Alta (high)' },
          { valor: 'medium', etiqueta: 'Media (medium)' },
          { valor: 'low', etiqueta: 'Baja (low)' },
        ],
      },
    ],
  },
  {
    titulo: 'Reportes sin conexión',
    detalle: 'Qué hace el teléfono con los puntos cuando no hay señal.',
    campos: [
      {
        clave: 'mobile.bufferEnabled',
        tipo: 'booleano',
        etiqueta: 'Guardar puntos sin señal',
        ayuda: 'Si está activado, los puntos se guardan y se envían al volver la conexión.',
      },
      {
        clave: 'mobile.bufferMax',
        tipo: 'numero',
        etiqueta: 'Capacidad de la cola (puntos)',
        ayuda: 'Máximo de puntos guardados a la espera de conexión.',
      },
      {
        clave: 'mobile.bufferPolicy',
        tipo: 'seleccion',
        etiqueta: 'Cuando la cola se llena',
        ayuda: 'Qué puntos se descartan al llegar al máximo.',
        opciones: [
          { valor: 'drop_oldest', etiqueta: 'Descartar los más antiguos (drop_oldest)' },
          { valor: 'drop_newest', etiqueta: 'Descartar los más nuevos (drop_newest)' },
          { valor: 'stop', etiqueta: 'Dejar de guardar (stop)' },
        ],
      },
    ],
  },
  {
    titulo: 'Confirmación y reintentos',
    detalle: 'Cómo se asegura el teléfono de que el servidor recibió cada reporte.',
    campos: [
      {
        clave: 'mobile.ackTimeoutSeconds',
        tipo: 'numero',
        etiqueta: 'Espera de confirmación (segundos)',
        ayuda: 'Tiempo que espera la respuesta del servidor antes de reintentar.',
      },
      {
        clave: 'mobile.maxRetries',
        tipo: 'numero',
        etiqueta: 'Reintentos máximos',
        ayuda: 'Cuántas veces se vuelve a enviar un reporte que no se confirmó.',
      },
    ],
  },
];

const CAMPOS_CONFIG: CampoConfig[] = SECCIONES.flatMap((seccion) => seccion.campos);

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
      return { ok: false, error: `${campo.etiqueta} ya tiene valor y no se puede quitar; escribe uno nuevo.` };
    }
    return { ok: true, valor: null };
  }
  if (campo.tipo === 'numero') {
    const numero = Number(limpio);
    if (!Number.isFinite(numero)) return { ok: false, error: `${campo.etiqueta} debe ser un número.` };
    return { ok: true, valor: numero };
  }
  if (campo.tipo === 'booleano') {
    if (limpio === 'true') return { ok: true, valor: true };
    if (limpio === 'false') return { ok: true, valor: false };
    return { ok: false, error: `${campo.etiqueta}: usa activado o desactivado.` };
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
// configuración completa.
function aplicarCambios(base: BorradorEquipo, cuerpo: CuerpoEquipo): BorradorEquipo {
  const siguiente: BorradorEquipo = { nombre: cuerpo.nombre ?? base.nombre, valores: { ...base.valores } };
  for (const campo of CAMPOS_CONFIG) {
    const valor = cuerpo.configuracion?.[campo.clave];
    if (valor !== undefined) siguiente.valores[campo.clave] = textoDesde(valor);
  }
  return siguiente;
}

function ControlCampo({
  campo,
  valor,
  deshabilitado,
  alCambiar,
}: {
  campo: CampoConfig;
  valor: string;
  deshabilitado: boolean;
  alCambiar: (valor: string) => void;
}) {
  return (
    <Campo
      etiqueta={campo.etiqueta}
      ayuda={
        <>
          {campo.ayuda} <code className="font-mono text-[11px]">{campo.clave}</code>
        </>
      }
    >
      {campo.tipo === 'numero' ? (
        <Entrada
          type="number"
          step="any"
          min="0"
          value={valor}
          onChange={(evento) => alCambiar(evento.target.value)}
          disabled={deshabilitado}
        />
      ) : (
        <Selector value={valor} onChange={(evento) => alCambiar(evento.target.value)} disabled={deshabilitado}>
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
        </Selector>
      )}
    </Campo>
  );
}

export default function Configuracion() {
  const administrador = useSesion((estado) => estado.usuario?.administrador === true);
  const cliente = useQueryClient();
  const [busqueda, setBusqueda] = useState('');
  const [seleccionId, setSeleccionId] = useState('');
  const [seleccionado, setSeleccionado] = useState<DispositivoGestion | null>(null);
  const [original, setOriginal] = useState<BorradorEquipo | null>(null);
  const [borrador, setBorrador] = useState<BorradorEquipo | null>(null);

  // Misma clave que el resto de páginas: comparte la caché de la flota.
  const equipos = useQuery({
    queryKey: CLAVE_FLOTA,
    queryFn: () => api.get<Pagina<DispositivoGestion>>(`/api/v1/fleet${consulta({ tamano: 200 })}`),
    staleTime: CACHE_FLOTA_CONSULTA_MS,
  });

  const guardar = useMutation({
    mutationFn: ({ id, cuerpo }: { id: string; cuerpo: CuerpoEquipo }) =>
      api.put<{ dispositivo: DispositivoGestion }>(`/api/v1/fleet/${encodeURIComponent(id)}`, cuerpo),
    onSuccess: (datos, variables) => {
      void cliente.invalidateQueries({ queryKey: ['flota'] });
      toast.success('Cambios guardados.');
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
    onError: (error) => toast.error(mensajeError(error)),
  });

  const filtro = busqueda.trim().toLowerCase();
  const flota = useMemo(() => {
    const datos = equipos.data?.datos ?? [];
    if (!filtro) return datos;
    return datos.filter(
      (dispositivo) =>
        dispositivo.nombre.toLowerCase().includes(filtro) || dispositivo.identificadorUnico.toLowerCase().includes(filtro),
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

  function descartar() {
    if (original) setBorrador(original);
    guardar.reset();
  }

  function enviar() {
    if (!administrador || !seleccionado || !analisis || !analisis.ok || guardar.isPending) return;
    if (Object.keys(analisis.cuerpo).length === 0) return;
    guardar.reset();
    guardar.mutate({ id: seleccionado.idPublico, cuerpo: analisis.cuerpo });
  }

  return (
    <div className="space-y-5">
      <AccionesPagina>
        <Buscador valor={busqueda} alCambiar={setBusqueda} placeholder="Buscar equipo" />
      </AccionesPagina>

      {!administrador && (
        <p className="rounded-tarjeta border border-sin-senal/25 bg-sin-senal-suave px-4 py-3 text-[13px] font-medium text-sin-senal">
          Necesitas permisos de administrador para cambiar la configuración; el formulario está en solo lectura.
        </p>
      )}

      <Tarjeta>
        <CabeceraTarjeta titulo="Equipos" detalle={`${flota.length} de ${equipos.data?.total ?? 0}`} />
        {equipos.isPending && <Cargando texto="Cargando equipos…" />}
        {equipos.error && (
          <div className="px-5 pb-5">
            <ErrorCarga mensaje={mensajeError(equipos.error)} alReintentar={() => void equipos.refetch()} />
          </div>
        )}
        {equipos.data && flota.length === 0 && (
          <Vacio icono={SearchX} titulo="Sin equipos">
            No hay equipos que coincidan con la búsqueda.
          </Vacio>
        )}
        {flota.length > 0 && (
          <div className="max-h-80 overflow-y-auto">
            <Tabla>
              <thead>
                <tr>
                  <Th className="w-12">
                    <span className="sr-only">Selección</span>
                  </Th>
                  <Th>Equipo</Th>
                  <Th>Identificador</Th>
                  <Th>Estado</Th>
                </tr>
              </thead>
              <tbody>
                {flota.map((dispositivo) => {
                  const activo = dispositivo.idPublico === seleccionId;
                  return (
                    <Fila key={dispositivo.idPublico} seleccionada={activo} onClick={() => seleccionar(dispositivo)}>
                      <Td>
                        <input
                          type="radio"
                          name="equipo"
                          className="size-4 cursor-pointer accent-marca"
                          checked={activo}
                          onChange={() => seleccionar(dispositivo)}
                          onClick={(evento) => evento.stopPropagation()}
                          aria-label={`Seleccionar ${dispositivo.nombre}`}
                        />
                      </Td>
                      <Td>
                        <div className="flex items-center gap-3">
                          <Avatar nombre={dispositivo.nombre} tamano="sm" />
                          <span className="font-semibold text-marino-900">{dispositivo.nombre}</span>
                        </div>
                      </Td>
                      <Td className="font-mono text-[12px]">{dispositivo.identificadorUnico}</Td>
                      <Td>
                        <ChipEstado equipo={dispositivo} />
                      </Td>
                    </Fila>
                  );
                })}
              </tbody>
            </Tabla>
          </div>
        )}
      </Tarjeta>

      {!seleccionado || !borrador ? (
        <Tarjeta>
          <Vacio icono={Settings2} titulo="Sin equipo seleccionado">
            Selecciona un equipo para ver y editar su configuración.
          </Vacio>
        </Tarjeta>
      ) : (
        <form
          onSubmit={(evento) => {
            evento.preventDefault();
            enviar();
          }}
          noValidate
          className="space-y-5"
        >
          <Tarjeta>
            <CabeceraTarjeta titulo="Identificación" detalle={seleccionado.identificadorUnico} />
            <div className="px-5 pb-5">
              <Campo etiqueta="Nombre del equipo" ayuda="Así se ve el equipo en En vivo, Replay y reportes.">
                <Entrada
                  value={borrador.nombre}
                  onChange={(evento) => setBorrador({ ...borrador, nombre: evento.target.value })}
                  disabled={!administrador}
                  className="sm:max-w-md"
                />
              </Campo>
            </div>
          </Tarjeta>

          <p className="px-1 text-[12.5px] text-texto-2">
            Solo se guardan los campos que cambies; un campo vacío queda sin definir. Un valor ya definido no se
            puede quitar, solo cambiar.
          </p>

          {SECCIONES.map((seccion) => (
            <Tarjeta key={seccion.titulo}>
              <CabeceraTarjeta titulo={seccion.titulo} />
              <p className="-mt-2 px-5 pb-3 text-[12.5px] text-texto-2">{seccion.detalle}</p>
              <div className="grid gap-x-5 gap-y-4 px-5 pb-5 sm:grid-cols-2">
                {seccion.campos.map((campo) => (
                  <ControlCampo
                    key={campo.clave}
                    campo={campo}
                    valor={borrador.valores[campo.clave]}
                    deshabilitado={!administrador}
                    alCambiar={(valor) => cambiarValor(campo.clave, valor)}
                  />
                ))}
              </div>
            </Tarjeta>
          ))}

          {errorCampos !== '' && <AvisoError>{errorCampos}</AvisoError>}
          {guardar.error != null && <AvisoError>{mensajeError(guardar.error)}</AvisoError>}

          {administrador && hayCambios && (
            <div className="sticky bottom-4 z-10 flex flex-wrap items-center gap-3 rounded-tarjeta border border-borde bg-superficie px-5 py-3 shadow-flotante">
              <p className="mr-auto text-[13px] font-medium text-marino-900">Hay cambios sin guardar.</p>
              <Boton onClick={descartar} disabled={guardar.isPending}>
                Descartar
              </Boton>
              <Boton variante="principal" type="submit" disabled={guardar.isPending || errorCampos !== ''}>
                {guardar.isPending ? 'Guardando…' : 'Guardar cambios'}
              </Boton>
            </div>
          )}
        </form>
      )}
    </div>
  );
}
