import { useMemo } from 'react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { FileText, Map as MapaIcono, Route } from 'lucide-react';
import type { Dispositivo, ResumenReporte } from '@contratos';
import { api, consulta } from '@/lib/api';
import { cn } from '@/lib/cn';
import { Avatar } from '@/componentes/ui/Avatar';
import { Tarjeta } from '@/componentes/ui/Tarjeta';
import { Cargando, ErrorCarga } from '@/componentes/ui/Estados';
import { claseBoton } from '@/componentes/ui/Boton';
import { AccionesPagina } from '@/componentes/marco/Marco';
import { bateria, duracion, hace, hora, GUION } from '@/dominio/formatoBase';
import { traerFlota, traerJornadasFlota, traerSalud, CLAVE_FLOTA, equiposHabilitados } from '@/dominio/datos';
import { mensajeError } from '@/dominio/errores';
import { etiquetaEstado, claveEstado } from '@/dominio/estado';
import { finDeDia, fechaHoyLocal, inicioDeDia, sumarDias } from '@/dominio/rango';
import { TIPOS, traerCronograma } from '@/dominio/cronograma';
import { CLAVE_NOVEDADES_CRONOGRAMA, REFRESCO_NOVEDADES_MS, traerNovedadesCronograma } from '@/componentes/reportes/novedades';

// Inicio: tablero de operación del día, compacto y con datos que sirven para
// actuar. Arriba, el estado de la flota ahora y las cifras del día; al centro,
// una fila por persona con todo lo de hoy (estado, jornada, recorrido,
// cronograma, batería y app); a la derecha, lo que hay que revisar y las
// últimas actividades cargadas.

const REFRESCO_MS = 15_000;
const REFRESCO_DIA_MS = 60_000;
const MINUTOS_SIN_SENAL = 5;
const BATERIA_BAJA_PCT = 15;
const JORNADA_LARGA_H = 16;

const PUNTO_ESTADO: Record<string, string> = {
  enLinea: 'bg-movimiento',
  detenido: 'bg-detenido',
  sinSenal: 'bg-sin-senal',
  desconocido: 'bg-sin-senal',
  deshabilitado: 'bg-deshabilitado',
};

function minutosDesde(valor: string | null): number | null {
  if (!valor) return null;
  const diferencia = Date.now() - new Date(valor).getTime();
  return Number.isFinite(diferencia) ? diferencia / 60_000 : null;
}

// "2.4.0" > "2.1.73": compara por partes numéricas.
function compararVersion(a: string, b: string): number {
  const pa = a.split('.').map((x) => Number.parseInt(x, 10) || 0);
  const pb = b.split('.').map((x) => Number.parseInt(x, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i += 1) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d !== 0) return d;
  }
  return 0;
}

// Inicio de jornada: solo la hora si fue hoy; "Ayer 15:03" o "28/09 08:10" si
// empezó antes y sigue abierta.
function inicioJornada(inicioEn: string, hoy: string): string {
  const dia = fechaLocal(inicioEn);
  if (dia === hoy) return hora(inicioEn);
  if (dia === sumarDias(hoy, -1)) return `Ayer ${hora(inicioEn)}`;
  return `${dia.slice(8, 10)}/${dia.slice(5, 7)} ${hora(inicioEn)}`;
}

function fechaLocal(iso: string): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Guayaquil' }).format(new Date(iso));
}

function km(valor: number | null | undefined): string {
  if (valor == null || !Number.isFinite(valor)) return GUION;
  return `${valor.toLocaleString('es-EC', { maximumFractionDigits: valor < 10 ? 1 : 0 })} km`;
}

interface Revision {
  equipo: Dispositivo;
  motivos: string[];
  grave: boolean;
}

export default function Inicio() {
  const hoy = fechaHoyLocal();

  const flota = useQuery({
    queryKey: CLAVE_FLOTA,
    queryFn: () => traerFlota({ redirigir401: false }),
    refetchInterval: REFRESCO_MS,
  });
  // Últimos 7 días: una jornada que sigue abierta (aunque haya empezado antes)
  // es la de hoy, y se muestra desde cuándo está abierta.
  const desdeJornadas = sumarDias(hoy, -7);
  const jornadas = useQuery({
    queryKey: ['inicio', 'jornadas', hoy],
    queryFn: () => traerJornadasFlota(inicioDeDia(desdeJornadas), finDeDia(hoy), undefined, { redirigir401: false }),
    refetchInterval: () => (document.hidden ? false : REFRESCO_DIA_MS),
  });
  const resumen = useQuery({
    queryKey: ['inicio', 'resumen', hoy],
    queryFn: () =>
      api.get<ResumenReporte>(`/api/v1/reports/summary${consulta({ desde: inicioDeDia(hoy), hasta: finDeDia(hoy) })}`, {
        redirigir401: false,
      }),
    refetchInterval: () => (document.hidden ? false : REFRESCO_DIA_MS),
    retry: false,
  });
  const actividades = useQuery({
    queryKey: ['inicio', 'cronograma', hoy],
    queryFn: () => traerCronograma(hoy, hoy),
    refetchInterval: () => (document.hidden ? false : REFRESCO_DIA_MS),
    retry: false,
  });
  const novedades = useQuery({
    queryKey: CLAVE_NOVEDADES_CRONOGRAMA,
    queryFn: traerNovedadesCronograma,
    refetchInterval: REFRESCO_NOVEDADES_MS,
    retry: false,
  });
  const salud = useQuery({
    queryKey: ['salud'],
    queryFn: traerSalud,
    refetchInterval: () => (document.hidden ? false : REFRESCO_DIA_MS),
    retry: false,
  });

  const equipos = useMemo(() => equiposHabilitados(flota.data?.datos ?? []), [flota.data]);

  // Versión de app más nueva vista en la flota: quien tenga una menor no
  // recibió la actualización.
  const versionMasNueva = useMemo(
    () =>
      equipos
        .map((e) => e.versionApp)
        .filter((v): v is string => Boolean(v))
        .sort(compararVersion)
        .at(-1) ?? null,
    [equipos],
  );

  const estados = useMemo(() => {
    const cuenta = { enLinea: 0, detenido: 0, sinSenal: 0, deshabilitado: 0 };
    for (const e of equipos) {
      const clave = claveEstado(e);
      if (clave === 'enLinea') cuenta.enLinea += 1;
      else if (clave === 'detenido') cuenta.detenido += 1;
      else if (clave === 'deshabilitado') cuenta.deshabilitado += 1;
      else cuenta.sinSenal += 1;
    }
    return cuenta;
  }, [equipos]);

  const jornadaPorPersona = useMemo(() => {
    const mapa = new Map<string, { inicioEn: string; finEn: string | null; duracionMin: number | null }>();
    const lista = [...(jornadas.data?.datos ?? [])].sort((a, b) => b.inicioEn.localeCompare(a.inicioEn));
    // La más reciente de cada persona, si sigue abierta o se cerró hoy.
    const inicioHoy = new Date(inicioDeDia(hoy)).getTime();
    for (const j of lista) {
      if (mapa.has(j.idPublico)) continue;
      if (j.finEn == null || new Date(j.finEn).getTime() >= inicioHoy) mapa.set(j.idPublico, j);
    }
    return mapa;
  }, [jornadas.data, hoy]);
  const recorridoPorPersona = useMemo(
    () => new Map((resumen.data?.porDispositivo ?? []).map((r) => [r.idPublico, r])),
    [resumen.data],
  );
  const actividadesPorPersona = useMemo(() => {
    const mapa = new Map<string, number>();
    for (const a of actividades.data?.datos ?? []) mapa.set(a.dispositivoId, (mapa.get(a.dispositivoId) ?? 0) + 1);
    return mapa;
  }, [actividades.data]);
  const nuevasPorPersona = useMemo(
    () => new Map((novedades.data?.personas ?? []).map((p) => [p.dispositivoId, p.nuevas])),
    [novedades.data],
  );

  const revisar = useMemo<Revision[]>(() => {
    const causaSalud = new Map(
      (salud.data?.datos ?? []).filter((s) => s.estado !== 'HEALTHY' && s.causa).map((s) => [s.dispositivoId, s.causa]),
    );
    const lista: Revision[] = [];
    for (const e of equipos) {
      const motivos: string[] = [];
      let grave = false;
      const clave = claveEstado(e);
      const minutos = minutosDesde(e.ultimaConexion);
      if ((clave === 'sinSenal' || clave === 'desconocido') && (minutos == null || minutos > MINUTOS_SIN_SENAL)) {
        motivos.push(e.ultimaConexion ? `Sin señal desde las ${hora(e.ultimaConexion)}` : 'Nunca ha reportado');
        grave = grave || e.jornadaActiva;
      }
      if (e.bateriaPct != null && e.bateriaPct <= BATERIA_BAJA_PCT && !e.cargando) {
        motivos.push(`Batería ${bateria(e.bateriaPct)}`);
        grave = true;
      }
      if ((e.pendientes ?? 0) > 0) motivos.push(`${e.pendientes} puntos sin enviar`);
      // Jornada abierta de más de JORNADA_LARGA_H: casi siempre olvidó finalizar.
      const jornada = jornadaPorPersona.get(e.idPublico);
      if (jornada && !jornada.finEn) {
        const horas = (Date.now() - new Date(jornada.inicioEn).getTime()) / 3_600_000;
        if (horas > JORNADA_LARGA_H) motivos.push(`Jornada abierta hace ${Math.floor(horas)} h`);
      }
      if (versionMasNueva && e.versionApp && compararVersion(e.versionApp, versionMasNueva) < 0) {
        motivos.push(`App ${e.versionApp} (hay ${versionMasNueva})`);
      }
      const causa = causaSalud.get(e.id)?.replace(/^Último GPS hace [^.]*\.?\s*/, '').trim();
      if (causa && motivos.length === 0) motivos.push(causa);
      if (motivos.length > 0) lista.push({ equipo: e, motivos, grave });
    }
    return lista.sort((a, b) => Number(b.grave) - Number(a.grave) || a.equipo.nombre.localeCompare(b.equipo.nombre, 'es'));
  }, [equipos, salud.data, versionMasNueva, jornadaPorPersona]);
  const enRevision = useMemo(() => new Set(revisar.map((r) => r.equipo.id)), [revisar]);

  // Personas: primero quien está en jornada, después quien requiere revisión,
  // y por nombre.
  const filas = useMemo(
    () =>
      [...equipos].sort(
        (a, b) =>
          Number(b.jornadaActiva) - Number(a.jornadaActiva) ||
          Number(enRevision.has(b.id)) - Number(enRevision.has(a.id)) ||
          a.nombre.localeCompare(b.nombre, 'es'),
      ),
    [equipos, enRevision],
  );

  const ultimasActividades = useMemo(
    () => [...(actividades.data?.datos ?? [])].sort((a, b) => b.registro.en.localeCompare(a.registro.en)).slice(0, 6),
    [actividades.data],
  );

  const enJornada = equipos.filter((e) => e.jornadaActiva).length;
  const totalNuevas = novedades.data?.total ?? 0;
  const actualizado = flota.dataUpdatedAt ? hace(new Date(flota.dataUpdatedAt).toISOString()) : null;

  return (
    <div className="space-y-4">
      <AccionesPagina>
        <span className="hidden text-[12px] text-texto-3 sm:inline">
          {actualizado ? `Actualizado ${actualizado}` : 'Sin datos todavía'}
          {flota.isFetching ? ' · actualizando…' : ''}
        </span>
        <Link to="/en-vivo" className={claseBoton('secundario')}>
          <MapaIcono className="size-4" />
          Mapa en vivo
        </Link>
      </AccionesPagina>

      {flota.isPending && <Cargando />}
      {flota.error && <ErrorCarga mensaje={mensajeError(flota.error)} alReintentar={() => void flota.refetch()} />}

      {flota.data && (
        <>
          {/* Hoy, de un vistazo: cifras en línea y la barra de estados de la flota. */}
          <Tarjeta className="overflow-hidden">
            <div className="grid grid-cols-2 divide-borde md:grid-cols-4 md:divide-x">
              <Cifra etiqueta="En jornada ahora" valor={`${enJornada}`} detalle={`de ${equipos.length} personas`} />
              <Cifra
                etiqueta="Recorrido de hoy"
                valor={resumen.data ? km(resumen.data.distanciaTotalKm) : GUION}
                detalle={resumen.data ? `${resumen.data.viajes} trayectos · ${resumen.data.paradas} paradas` : 'Calculando…'}
              />
              <Cifra
                etiqueta="Actividades de hoy"
                valor={actividades.data ? `${actividades.data.datos.length}` : GUION}
                detalle={
                  totalNuevas > 0 ? (
                    <Link to="/reportes" className="font-semibold text-marca hover:underline">
                      {totalNuevas} nuevas sin revisar
                    </Link>
                  ) : (
                    'Todo revisado'
                  )
                }
              />
              <Cifra
                etiqueta="Para revisar"
                valor={`${revisar.length}`}
                detalle={(() => {
                  const urgentes = revisar.filter((r) => r.grave).length;
                  return urgentes === 0 ? 'Sin urgencias' : `${urgentes} ${urgentes === 1 ? 'urgente' : 'urgentes'}`;
                })()}
                alerta={revisar.some((r) => r.grave)}
              />
            </div>
            <BarraEstados estados={estados} total={equipos.length} />
          </Tarjeta>

          <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_340px]">
            <Tarjeta className="min-w-0 overflow-hidden">
              <div className="flex items-baseline justify-between px-4 pt-3.5 pb-2.5">
                <h2 className="text-[15px] font-semibold">Personas · hoy</h2>
                <span className="text-[12px] text-texto-3">{equipos.length} activas</span>
              </div>
              <div className="overflow-x-auto">
                <table className="w-full min-w-[820px] text-[13px]">
                  <thead>
                    <tr className="border-y border-borde bg-marino-50/60 text-left text-[10.5px] font-semibold tracking-[0.06em] text-texto-3 uppercase">
                      <th className="px-4 py-2">Persona</th>
                      <th className="px-3 py-2">Estado</th>
                      <th className="px-3 py-2">Jornada</th>
                      <th className="px-3 py-2 text-right">Recorrido</th>
                      <th className="px-3 py-2 text-right">Actividades</th>
                      <th className="px-3 py-2">Batería</th>
                      <th className="px-3 py-2">App</th>
                      <th className="w-0 px-3 py-2" aria-label="Acciones" />
                    </tr>
                  </thead>
                  <tbody>
                    {filas.map((e) => (
                      <FilaPersona
                        key={e.id}
                        equipo={e}
                        hoy={hoy}
                        jornada={jornadaPorPersona.get(e.idPublico) ?? null}
                        recorrido={recorridoPorPersona.get(e.idPublico) ?? null}
                        actividades={actividadesPorPersona.get(e.idPublico) ?? 0}
                        nuevas={nuevasPorPersona.get(e.idPublico) ?? 0}
                        versionMasNueva={versionMasNueva}
                      />
                    ))}
                  </tbody>
                </table>
              </div>
            </Tarjeta>

            <div className="min-w-0 space-y-4">
              <Tarjeta>
                <div className="flex items-baseline justify-between px-4 pt-3.5 pb-2">
                  <h2 className="text-[15px] font-semibold">Para revisar</h2>
                  <span className="text-[12px] text-texto-3">{revisar.length}</span>
                </div>
                {revisar.length === 0 ? (
                  <p className="px-4 pb-4 text-[12.5px] text-texto-2">Nada pendiente: todos reportan y con batería.</p>
                ) : (
                  <ul className="divide-y divide-borde/70 pb-1">
                    {revisar.map(({ equipo, motivos, grave }) => (
                      <li key={equipo.id}>
                        <Link
                          to={`/unidad/${equipo.idPublico}`}
                          className="flex items-start gap-2.5 px-4 py-2 transition-colors hover:bg-fondo"
                        >
                          <span className={cn('mt-1.5 size-2 flex-none rounded-full', grave ? 'bg-peligro' : 'bg-sin-senal')} />
                          <span className="min-w-0 flex-1">
                            <span className="block truncate text-[13px] font-semibold text-marino-900">{equipo.nombre}</span>
                            <span className="block text-[11.5px] leading-snug text-texto-2">{motivos.join(' · ')}</span>
                          </span>
                        </Link>
                      </li>
                    ))}
                  </ul>
                )}
              </Tarjeta>

              <Tarjeta>
                <div className="flex items-baseline justify-between px-4 pt-3.5 pb-2">
                  <h2 className="text-[15px] font-semibold">Últimas actividades</h2>
                  <Link to="/reportes" className="text-[12px] font-medium text-texto-2 hover:text-marca">
                    Ver cronograma
                  </Link>
                </div>
                {ultimasActividades.length === 0 ? (
                  <p className="px-4 pb-4 text-[12.5px] text-texto-2">Nadie cargó actividades hoy.</p>
                ) : (
                  <ul className="divide-y divide-borde/70 pb-1">
                    {ultimasActividades.map((a) => (
                      <li key={a.id} className="flex items-start gap-3 px-4 py-2">
                        <span className="w-10 flex-none pt-px text-[12px] font-semibold text-marino-900 cifras">{a.hora}</span>
                        <span className="min-w-0 flex-1">
                          <span className="block truncate text-[13px] text-marino-900">
                            <b className="font-semibold">{a.nombre}</b> · {TIPOS[a.tipo]?.etiqueta ?? a.tipo}
                          </span>
                          <span className="block truncate text-[11.5px] text-texto-3">
                            {[a.lugar || a.nota, `cargada ${hora(a.registro.en)}`].filter(Boolean).join(' · ')}
                            {a.registro.conJornada ? '' : ' sin jornada'}
                          </span>
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </Tarjeta>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function Cifra({
  etiqueta,
  valor,
  detalle,
  alerta = false,
}: {
  etiqueta: string;
  valor: string;
  detalle: ReactNode;
  alerta?: boolean;
}) {
  return (
    <div className="px-4 py-3">
      <p className="text-[11px] font-medium tracking-[0.04em] text-texto-3 uppercase">{etiqueta}</p>
      <p className={cn('mt-0.5 font-display text-[22px] leading-tight font-semibold cifras', alerta ? 'text-peligro' : 'text-marino-900')}>
        {valor}
      </p>
      <p className="mt-0.5 truncate text-[12px] text-texto-2">{detalle}</p>
    </div>
  );
}

// Barra proporcional de la flota por estado, con la cuenta escrita al lado.
function BarraEstados({
  estados,
  total,
}: {
  estados: { enLinea: number; detenido: number; sinSenal: number; deshabilitado: number };
  total: number;
}) {
  const partes = [
    { clave: 'enLinea', n: estados.enLinea, texto: 'en movimiento' },
    { clave: 'detenido', n: estados.detenido, texto: 'detenidas' },
    { clave: 'sinSenal', n: estados.sinSenal, texto: 'sin señal' },
    { clave: 'deshabilitado', n: estados.deshabilitado, texto: 'fuera de jornada' },
  ];
  return (
    <div className="border-t border-borde px-4 py-2.5">
      <div className="flex h-1.5 overflow-hidden rounded-full bg-fondo" aria-hidden="true">
        {total > 0 &&
          partes.map((p) => (p.n > 0 ? <span key={p.clave} className={PUNTO_ESTADO[p.clave]} style={{ width: `${(p.n / total) * 100}%` }} /> : null))}
      </div>
      <p className="mt-1.5 flex flex-wrap gap-x-4 gap-y-1 text-[12px] text-texto-2">
        {partes.map((p) => (
          <span key={p.clave} className="inline-flex items-center gap-1.5">
            <span className={cn('size-2 rounded-full', PUNTO_ESTADO[p.clave])} />
            <b className="font-semibold text-marino-900 cifras">{p.n}</b> {p.texto}
          </span>
        ))}
      </p>
    </div>
  );
}

function FilaPersona({
  equipo,
  hoy,
  jornada,
  recorrido,
  actividades,
  nuevas,
  versionMasNueva,
}: {
  equipo: Dispositivo;
  hoy: string;
  jornada: { inicioEn: string; finEn: string | null; duracionMin: number | null } | null;
  recorrido: { distanciaKm: number; paradas: number } | null;
  actividades: number;
  nuevas: number;
  versionMasNueva: string | null;
}) {
  const clave = claveEstado(equipo);
  const bajaBateria = equipo.bateriaPct != null && equipo.bateriaPct <= BATERIA_BAJA_PCT;
  const appVieja = Boolean(versionMasNueva && equipo.versionApp && compararVersion(equipo.versionApp, versionMasNueva) < 0);
  return (
    <tr className="border-b border-borde/70 last:border-b-0 hover:bg-fondo/60">
      <td className="px-4 py-2">
        <div className="flex items-center gap-2.5">
          <Avatar nombre={equipo.nombre} estado={clave} tamano="sm" />
          <div className="min-w-0 leading-tight">
            <Link to={`/unidad/${equipo.idPublico}`} className="block truncate font-semibold text-marino-900 hover:text-marca">
              {equipo.nombre}
            </Link>
            <span className="block truncate text-[11px] text-texto-3">{equipo.identificadorUnico}</span>
          </div>
        </div>
      </td>
      <td className="px-3 py-2 whitespace-nowrap">
        <span className="inline-flex items-center gap-1.5 text-marino-900">
          <span className={cn('size-2 rounded-full', PUNTO_ESTADO[clave] ?? 'bg-deshabilitado')} />
          {etiquetaEstado(equipo)}
        </span>
        <span className="block pl-3.5 text-[11px] text-texto-3">{equipo.ultimaConexion ? hace(equipo.ultimaConexion) : 'Sin reportes'}</span>
      </td>
      <td className="px-3 py-2 whitespace-nowrap cifras">
        {jornada ? (
          <>
            <span className="text-marino-900">
              {inicioJornada(jornada.inicioEn, hoy)} –{' '}
              {jornada.finEn ? hora(jornada.finEn) : 'ahora'}
            </span>
            <span className="block text-[11px] text-texto-3">
              {jornada.duracionMin != null ? duracion(jornada.duracionMin * 60) : 'En curso'}
            </span>
          </>
        ) : (
          <span className="text-texto-3">Sin jornada</span>
        )}
      </td>
      <td className="px-3 py-2 text-right whitespace-nowrap cifras">
        {recorrido && recorrido.distanciaKm > 0 ? (
          <>
            <span className="text-marino-900">{km(recorrido.distanciaKm)}</span>
            <span className="block text-[11px] text-texto-3">{recorrido.paradas} paradas</span>
          </>
        ) : (
          <span className="text-texto-3">{GUION}</span>
        )}
      </td>
      <td className="px-3 py-2 text-right whitespace-nowrap cifras">
        <span className={actividades > 0 ? 'text-marino-900' : 'text-texto-3'}>{actividades}</span>
        {nuevas > 0 && (
          <span
            className="ml-1.5 inline-grid h-4 min-w-4 place-items-center rounded-full bg-marca px-1 align-[1px] text-[10px] font-bold text-white"
            title={`${nuevas} nuevas sin revisar`}
          >
            {nuevas}
          </span>
        )}
      </td>
      <td className="px-3 py-2 whitespace-nowrap">
        <span className="inline-flex items-center gap-2">
          <span className="h-1.5 w-10 overflow-hidden rounded-full bg-fondo" aria-hidden="true">
            <span
              className={cn('block h-full rounded-full', bajaBateria ? 'bg-peligro' : 'bg-marino-300')}
              style={{ width: `${Math.max(0, Math.min(100, equipo.bateriaPct ?? 0))}%` }}
            />
          </span>
          <span className={cn('cifras', bajaBateria ? 'font-semibold text-peligro' : 'text-texto-2')}>{bateria(equipo.bateriaPct)}</span>
        </span>
        {equipo.cargando && <span className="block text-[11px] text-movimiento">Cargando</span>}
      </td>
      <td className="px-3 py-2 whitespace-nowrap">
        <span
          className={cn('cifras', appVieja ? 'font-semibold text-sin-senal' : 'text-texto-2')}
          title={appVieja ? `Desactualizada: la más reciente en la flota es ${versionMasNueva}` : undefined}
        >
          {equipo.versionApp ?? GUION}
        </span>
      </td>
      <td className="px-3 py-2">
        <div className="flex items-center justify-end gap-0.5">
          <Link
            to={`/replay${consulta({ dispositivo: equipo.idPublico, desde: hoy, hasta: hoy })}`}
            title="Repetición de ruta de hoy"
            aria-label={`Repetición de ruta de hoy de ${equipo.nombre}`}
            className="grid size-7 place-items-center rounded-control text-texto-2 transition-colors hover:bg-fondo hover:text-marca"
          >
            <Route className="size-4" />
          </Link>
          <Link
            to={`/unidad/${equipo.idPublico}`}
            title="Expediente"
            aria-label={`Expediente de ${equipo.nombre}`}
            className="grid size-7 place-items-center rounded-control text-texto-2 transition-colors hover:bg-fondo hover:text-marca"
          >
            <FileText className="size-4" />
          </Link>
        </div>
      </td>
    </tr>
  );
}
