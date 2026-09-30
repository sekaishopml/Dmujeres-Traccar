import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ChevronLeft, ChevronRight, Download, MapPin, Navigation, TriangleAlert } from 'lucide-react';
import { AccionesPagina } from '@/componentes/marco/Marco';
import { Selector } from '@/componentes/ui/Campo';
import { Segmentado } from '@/componentes/ui/Segmentado';
import { Tarjeta } from '@/componentes/ui/Tarjeta';
import { Avatar } from '@/componentes/ui/Avatar';
import { Cargando, ErrorCarga, Vacio } from '@/componentes/ui/Estados';
import { claseBoton } from '@/componentes/ui/Boton';
import { CLAVE_FLOTA, equiposHabilitados, traerDireccion, traerFlota } from '@/dominio/datos';
import { mensajeError } from '@/dominio/errores';
import { hora } from '@/dominio/formatoBase';
import { diaDe, fechaHoyLocal, sumarDias } from '@/dominio/rango';
import {
  TIPOS,
  diasDeSemana,
  etiquetaDia,
  etiquetaDiaLargo,
  etiquetaMes,
  lunesDe,
  primeroDeMes,
  sumarMeses,
  traerCronograma,
  ultimoDeMes,
  type Actividad,
} from '@/dominio/cronograma';
import { cn } from '@/lib/cn';

type Vista = 'semana' | 'mes';
const VISTAS = [
  { valor: 'semana', etiqueta: 'Semana' },
  { valor: 'mes', etiqueta: 'Mes' },
] as const;

// Cronograma de actividades: lo que cada persona declaró en la app, en
// semana (columnas por día, como su Excel) o en mes (calendario), con la
// auditoría contra su recorrido a la hora declarada.
export default function Cronograma() {
  const hoy = fechaHoyLocal();
  const [vista, setVista] = useState<Vista>('semana');
  const [ancla, setAncla] = useState(hoy);
  const [persona, setPersona] = useState('');

  const flota = useQuery({ queryKey: CLAVE_FLOTA, queryFn: () => traerFlota() });
  const equipos = useMemo(() => equiposHabilitados(flota.data?.datos ?? []), [flota.data]);

  const lunes = lunesDe(ancla);
  const desde = vista === 'semana' ? lunes : primeroDeMes(ancla);
  const hasta = vista === 'semana' ? sumarDias(lunes, 6) : ultimoDeMes(ancla);

  const cronograma = useQuery({
    queryKey: ['cronograma', desde, hasta, persona],
    queryFn: () => traerCronograma(desde, hasta, persona),
    refetchInterval: 60_000,
  });
  const datos = useMemo(() => cronograma.data?.datos ?? [], [cronograma.data]);

  const mover = (paso: number) =>
    setAncla(vista === 'semana' ? sumarDias(ancla, paso * 7) : sumarMeses(ancla, paso));
  const titulo = vista === 'semana' ? rangoSemana(desde, hasta) : etiquetaMes(desde);

  const personas = new Set(datos.map((a) => a.dispositivoId)).size;
  const novedades = datos.filter((a) => a.tipo === 'novedad').length;
  const sinJornada = datos.filter((a) => !a.registro.conJornada).length;

  return (
    <div className="space-y-4">
      <AccionesPagina>
        <Selector aria-label="Persona" className="w-48" value={persona} onChange={(e) => setPersona(e.target.value)}>
          <option value="">Todas las personas</option>
          {equipos.map((e) => (
            <option key={e.idPublico} value={e.idPublico}>
              {e.nombre}
            </option>
          ))}
        </Selector>
        <Segmentado opciones={VISTAS} valor={vista} alCambiar={setVista} />
        <div className="flex items-center gap-1">
          <button type="button" onClick={() => mover(-1)} className={claseBoton('secundario', 'sm')} aria-label="Anterior">
            <ChevronLeft className="size-4" />
          </button>
          <button type="button" onClick={() => setAncla(hoy)} className={claseBoton('secundario', 'sm')}>
            Hoy
          </button>
          <button type="button" onClick={() => mover(1)} className={claseBoton('secundario', 'sm')} aria-label="Siguiente">
            <ChevronRight className="size-4" />
          </button>
        </div>
        <button
          type="button"
          onClick={() => exportarCsv(datos, desde, hasta)}
          disabled={datos.length === 0}
          className={claseBoton('secundario', 'sm')}
        >
          <Download className="size-4" /> CSV
        </button>
      </AccionesPagina>

      <Tarjeta className="flex flex-wrap items-center gap-x-6 gap-y-2 px-5 py-3">
        <p className="font-display text-[15px] font-semibold text-marino-900 first-letter:uppercase">{titulo}</p>
        <Dato valor={datos.length} etiqueta="actividades" />
        <Dato valor={personas} etiqueta="personas" />
        <Dato valor={novedades} etiqueta="novedades" tono={novedades > 0 ? 'text-marca' : undefined} />
        <Dato
          valor={sinJornada}
          etiqueta="cargadas sin jornada"
          tono={sinJornada > 0 ? 'text-sin-senal' : undefined}
          titulo="Se cargaron con la jornada cerrada: no llevan ubicación de registro."
        />
      </Tarjeta>

      {cronograma.error ? (
        <ErrorCarga mensaje={mensajeError(cronograma.error)} alReintentar={() => void cronograma.refetch()} />
      ) : cronograma.isPending ? (
        <Cargando texto="Cargando cronograma…" />
      ) : vista === 'mes' ? (
        <Mes
          desde={desde}
          hasta={hasta}
          hoy={hoy}
          datos={datos}
          alElegirDia={(dia) => {
            setAncla(dia);
            setVista('semana');
          }}
        />
      ) : persona ? (
        <SemanaPersona dias={diasDeSemana(lunes)} hoy={hoy} datos={datos} />
      ) : (
        <SemanaEquipo datos={datos} hoy={hoy} />
      )}
    </div>
  );
}

function Dato({ valor, etiqueta, tono, titulo }: { valor: number; etiqueta: string; tono?: string; titulo?: string }) {
  return (
    <p className="text-[12.5px] text-texto-2" title={titulo}>
      <span className={cn('font-display text-[16px] font-semibold text-marino-900 cifras', tono)}>{valor}</span>{' '}
      {etiqueta}
    </p>
  );
}

// Semana de una persona: una columna por día, como su planilla.
function SemanaPersona({ dias, hoy, datos }: { dias: string[]; hoy: string; datos: Actividad[] }) {
  const visibles = dias.filter((d, i) => i < 6 || datos.some((a) => a.fecha === d));
  if (datos.length === 0) {
    return (
      <Tarjeta>
        <Vacio titulo="Sin actividades esta semana">La persona no cargó actividades en la app para estas fechas.</Vacio>
      </Tarjeta>
    );
  }
  return (
    <div className="grid gap-3" style={{ gridTemplateColumns: `repeat(${visibles.length}, minmax(0, 1fr))` }}>
      {visibles.map((dia) => {
        const delDia = datos.filter((a) => a.fecha === dia);
        return (
          <div key={dia} className="min-w-0">
            <p
              className={cn(
                'mb-2 rounded-control px-2.5 py-1.5 text-[12px] font-semibold first-letter:uppercase',
                dia === hoy ? 'bg-marca text-white' : 'bg-marino-50 text-marino-800',
              )}
            >
              {etiquetaDia(dia)}
            </p>
            <div className="space-y-2">
              {delDia.length === 0 ? (
                <p className="px-1 text-[12px] text-texto-3">—</p>
              ) : (
                delDia.map((a) => <TarjetaActividad key={a.id} actividad={a} compacta />)
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}

// Semana de todo el equipo: tabla agrupada por día, fácil de recorrer.
function SemanaEquipo({ datos, hoy }: { datos: Actividad[]; hoy: string }) {
  if (datos.length === 0) {
    return (
      <Tarjeta>
        <Vacio titulo="Sin actividades esta semana">Nadie cargó actividades en la app para estas fechas.</Vacio>
      </Tarjeta>
    );
  }
  const dias = [...new Set(datos.map((a) => a.fecha))];
  return (
    <div className="space-y-4">
      {dias.map((dia) => (
        <Tarjeta key={dia} className="overflow-hidden">
          <p
            className={cn(
              'border-b border-borde px-5 py-2.5 text-[13px] font-semibold first-letter:uppercase',
              dia === hoy ? 'text-marca' : 'text-marino-900',
            )}
          >
            {etiquetaDiaLargo(dia)}
          </p>
          <div className="divide-y divide-borde">
            {datos
              .filter((a) => a.fecha === dia)
              .map((a) => (
                <div key={a.id} className="grid grid-cols-[11rem_minmax(0,1fr)] items-start gap-4 px-5 py-2.5">
                  <div className="flex min-w-0 items-center gap-2.5">
                    <Avatar nombre={a.nombre} tamano="sm" />
                    <span className="truncate text-[13px] font-semibold text-marino-900">{a.nombre}</span>
                  </div>
                  <TarjetaActividad actividad={a} plana />
                </div>
              ))}
          </div>
        </Tarjeta>
      ))}
    </div>
  );
}

// Mes: calendario con lo cargado cada día; un clic abre esa semana.
function Mes({
  desde,
  hasta,
  hoy,
  datos,
  alElegirDia,
}: {
  desde: string;
  hasta: string;
  hoy: string;
  datos: Actividad[];
  alElegirDia: (dia: string) => void;
}) {
  const inicio = lunesDe(desde);
  const celdas: string[] = [];
  for (let d = inicio; d <= hasta || celdas.length % 7 !== 0; d = sumarDias(d, 1)) celdas.push(d);
  return (
    <Tarjeta className="overflow-hidden">
      <div className="grid grid-cols-7 border-b border-borde bg-fondo text-center text-[11px] font-semibold tracking-[0.06em] text-texto-3 uppercase">
        {['Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb', 'Dom'].map((d) => (
          <span key={d} className="py-2">
            {d}
          </span>
        ))}
      </div>
      <div className="grid grid-cols-7">
        {celdas.map((dia) => {
          const delDia = datos.filter((a) => a.fecha === dia);
          const fuera = dia < desde || dia > hasta;
          const porTipo = new Map<string, number>();
          for (const a of delDia) porTipo.set(a.tipo, (porTipo.get(a.tipo) ?? 0) + 1);
          return (
            <button
              key={dia}
              type="button"
              onClick={() => alElegirDia(dia)}
              className={cn(
                'flex min-h-24 cursor-pointer flex-col items-start justify-start border-r border-b border-borde p-2 text-left transition-colors hover:bg-fondo',
                fuera && 'bg-fondo/60 opacity-50',
              )}
            >
              <span
                className={cn(
                  'inline-grid size-6 place-items-center rounded-full text-[12px] font-semibold cifras',
                  dia === hoy ? 'bg-marca text-white' : 'text-marino-900',
                )}
              >
                {Number(dia.slice(8))}
              </span>
              {delDia.length > 0 && (
                <div className="mt-1 space-y-1">
                  <p className="text-[11.5px] text-texto-2">
                    <strong className="text-marino-900">{delDia.length}</strong>{' '}
                    {delDia.length === 1 ? 'actividad' : 'actividades'} ·{' '}
                    {new Set(delDia.map((a) => a.dispositivoId)).size} pers.
                  </p>
                  <div className="flex flex-wrap gap-1">
                    {[...porTipo].map(([tipo, n]) => (
                      <span
                        key={tipo}
                        className={cn('rounded-full px-1.5 text-[10px] font-semibold', TIPOS[tipo as keyof typeof TIPOS].clase)}
                      >
                        {TIPOS[tipo as keyof typeof TIPOS].etiqueta} {n}
                      </span>
                    ))}
                  </div>
                </div>
              )}
            </button>
          );
        })}
      </div>
    </Tarjeta>
  );
}

// Una actividad: hora, tipo, lugar y nota, más la auditoría: dónde estaba la
// persona a esa hora según su recorrido y cuándo/dónde se cargó.
function TarjetaActividad({ actividad: a, compacta = false, plana = false }: { actividad: Actividad; compacta?: boolean; plana?: boolean }) {
  const tipo = TIPOS[a.tipo];
  return (
    <div className={cn(!plana && 'rounded-control border border-borde bg-superficie p-2.5', 'min-w-0')}>
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <span className="font-mono text-[12.5px] font-semibold text-marino-900">{a.hora}</span>
        <span className={cn('rounded-full px-2 py-0.5 text-[10.5px] font-semibold', tipo.clase)}>{tipo.etiqueta}</span>
        {a.lugar && <span className="min-w-0 text-[13px] font-semibold break-words text-marino-900">{a.lugar}</span>}
      </div>
      {a.nota && <p className="mt-1 text-[12px] break-words text-texto-2">{a.nota}</p>}
      <div className={cn('mt-1.5 space-y-0.5 text-[11.5px]', compacta && 'text-[11px]')}>
        <EnHora actividad={a} />
        <p className={cn('flex items-center gap-1', a.registro.conJornada ? 'text-texto-3' : 'text-sin-senal')}>
          {!a.registro.conJornada && <TriangleAlert className="size-3 flex-none" />}
          Cargada {hora(a.registro.en)}
          {diaDe(a.registro.en) !== a.fecha ? ` del ${etiquetaDia(diaDe(a.registro.en))}` : ''}
          {a.registro.conJornada ? ' · con jornada' : ' · sin jornada, sin ubicación'}
        </p>
      </div>
    </div>
  );
}

function EnHora({ actividad: a }: { actividad: Actividad }) {
  const eh = a.enHora;
  const direccion = useDireccionFaltante(eh?.latitud ?? null, eh?.longitud ?? null, eh?.direccion ?? null);
  if (!eh) {
    return <p className="text-texto-3">Sin recorrido registrado a esa hora</p>;
  }
  return (
    <p className="flex items-start gap-1 text-texto-2">
      {eh.detenida ? <MapPin className="mt-px size-3 flex-none text-detenido" /> : <Navigation className="mt-px size-3 flex-none text-movimiento" />}
      <span className="min-w-0">
        A esa hora {eh.detenida ? 'estaba detenida' : 'estaba en camino'}
        {eh.detenida && eh.paradaDesde && eh.paradaHasta ? ` (${hora(eh.paradaDesde)}–${hora(eh.paradaHasta)})` : ''}
        {direccion ? ` en ${direccion}` : ''}{' '}
        <Link
          to={`/replay?dispositivo=${a.dispositivoId}&desde=${a.fecha}&hasta=${a.fecha}`}
          className="font-semibold text-marino-700 hover:text-marca"
        >
          Ver ruta
        </Link>
      </span>
    </p>
  );
}

function useDireccionFaltante(lat: number | null, lon: number | null, conocida: string | null) {
  const consulta = useQuery({
    queryKey: ['geocode', lat?.toFixed(5), lon?.toFixed(5), null],
    queryFn: () => traerDireccion(lat as number, lon as number),
    enabled: conocida == null && lat != null && lon != null,
    staleTime: Infinity,
    retry: false,
  });
  return conocida ?? consulta.data?.direccion ?? null;
}

function exportarCsv(datos: Actividad[], desde: string, hasta: string) {
  const campo = (v: unknown) => `"${String(v ?? '').replaceAll('"', '""')}"`;
  const filas = [
    ['Fecha', 'Persona', 'Hora', 'Tipo', 'Lugar', 'Nota', 'Cargada', 'Con jornada', 'A esa hora', 'Dirección a esa hora'],
    ...datos.map((a) => [
      a.fecha,
      a.nombre,
      a.hora,
      TIPOS[a.tipo].etiqueta,
      a.lugar,
      a.nota,
      a.registro.en,
      a.registro.conJornada ? 'Sí' : 'No',
      a.enHora ? (a.enHora.detenida ? 'Detenida' : 'En camino') : 'Sin recorrido',
      a.enHora?.direccion,
    ]),
  ];
  const texto = '﻿' + filas.map((f) => f.map(campo).join(',')).join('\n');
  const url = URL.createObjectURL(new Blob([texto], { type: 'text/csv;charset=utf-8' }));
  const enlace = document.createElement('a');
  enlace.href = url;
  enlace.download = `cronograma-${desde}-a-${hasta}.csv`;
  enlace.click();
  URL.revokeObjectURL(url);
}

const DIA_MES = new Intl.DateTimeFormat('es-EC', { timeZone: 'UTC', day: 'numeric', month: 'short' });
function rangoSemana(desde: string, hasta: string): string {
  const f = (d: string) => DIA_MES.format(new Date(`${d}T12:00:00Z`)).replace('.', '');
  return `${f(desde)} – ${f(hasta)} ${hasta.slice(0, 4)}`;
}
