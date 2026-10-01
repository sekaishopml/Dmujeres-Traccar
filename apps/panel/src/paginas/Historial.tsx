import { useMemo, useState } from 'react';
import type { CSSProperties } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import type { JornadaFlota } from '@contratos';
import { consulta } from '@/lib/api';
import { cn } from '@/lib/cn';
import { Avatar } from '@/componentes/ui/Avatar';
import { Selector } from '@/componentes/ui/Campo';
import { Segmentado } from '@/componentes/ui/Segmentado';
import { Tarjeta } from '@/componentes/ui/Tarjeta';
import { ErrorCarga, Esqueleto, Vacio } from '@/componentes/ui/Estados';
import { AccionesPagina } from '@/componentes/marco/Marco';
import { GUION } from '@/dominio/formatoBase';
import { traerFlota, traerJornadasFlota, CACHE_AUDITORIA_MS, CLAVE_FLOTA, equiposHabilitados } from '@/dominio/datos';
import { mensajeError } from '@/dominio/errores';
import { lunesDe } from '@/dominio/cronograma';
import { fechaHoyLocal, finDeDia, inicioDeDia, sumarDias } from '@/dominio/rango';
import '@/componentes/inicio/inicio.css';

// Asistencia: una planilla persona × día con la entrada, la salida y las horas
// de cada jornada. Responde lo que pregunta la auditoría: quién trabajó, a qué
// hora entró, cuánto estuvo y qué jornadas quedaron sin cerrar. Cada celda
// abre la repetición de ruta de ese día.

const ZONA = 'America/Guayaquil';
const HORA = new Intl.DateTimeFormat('es-EC', { timeZone: ZONA, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
const DIA = new Intl.DateTimeFormat('en-CA', { timeZone: ZONA });
const NOMBRE_DIA = ['Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb', 'Dom'];
// Jornada abierta más allá de esto: casi siempre olvidó finalizar.
const JORNADA_LARGA_H = 16;

type Periodo = 'semana' | 'anterior' | 'catorce';
const PERIODOS: readonly { valor: Periodo; etiqueta: string }[] = [
  { valor: 'semana', etiqueta: 'Esta semana' },
  { valor: 'anterior', etiqueta: 'Semana pasada' },
  { valor: 'catorce', etiqueta: 'Últimos 14 días' },
];

function diasDelPeriodo(periodo: Periodo, hoy: string): string[] {
  const inicio =
    periodo === 'semana' ? lunesDe(hoy) : periodo === 'anterior' ? sumarDias(lunesDe(hoy), -7) : sumarDias(hoy, -13);
  const cantidad = periodo === 'catorce' ? 14 : 7;
  return Array.from({ length: cantidad }, (_, i) => sumarDias(inicio, i));
}

function horasTexto(minutos: number): string {
  const h = Math.floor(minutos / 60);
  const m = Math.round(minutos % 60);
  return h > 0 ? `${h} h${m > 0 ? ` ${m}` : ''}` : `${m} min`;
}

interface Celda {
  jornadas: JornadaFlota[];
  minutos: number;
  entrada: string;
  salida: string | null;
  abiertaLarga: boolean;
  cruzaDia: boolean;
}

export default function Historial() {
  const hoy = fechaHoyLocal();
  const [periodo, setPeriodo] = useState<Periodo>('semana');
  const [persona, setPersona] = useState('');
  const dias = useMemo(() => diasDelPeriodo(periodo, hoy), [periodo, hoy]);

  const flota = useQuery({ queryKey: CLAVE_FLOTA, queryFn: () => traerFlota(), staleTime: 60_000 });
  const personas = useMemo(
    () => equiposHabilitados(flota.data?.datos ?? []).sort((a, b) => a.nombre.localeCompare(b.nombre, 'es')),
    [flota.data],
  );
  const jornadas = useQuery({
    queryKey: ['asistencia', dias[0], dias[dias.length - 1]],
    queryFn: () => traerJornadasFlota(inicioDeDia(dias[0]), finDeDia(dias[dias.length - 1])),
    staleTime: CACHE_AUDITORIA_MS,
  });

  // Celdas por persona y día (día local de la entrada).
  const celdas = useMemo(() => {
    const mapa = new Map<string, Map<string, Celda>>();
    for (const j of jornadas.data?.datos ?? []) {
      const dia = DIA.format(new Date(j.inicioEn));
      const porDia = mapa.get(j.idPublico) ?? new Map<string, Celda>();
      const celda = porDia.get(dia) ?? { jornadas: [], minutos: 0, entrada: j.inicioEn, salida: null, abiertaLarga: false, cruzaDia: false };
      celda.jornadas.push(j);
      celda.minutos += j.duracionMin ?? 0;
      if (j.inicioEn < celda.entrada) celda.entrada = j.inicioEn;
      if (j.finEn && (!celda.salida || j.finEn > celda.salida)) celda.salida = j.finEn;
      if (!j.finEn && (j.duracionMin ?? 0) / 60 > JORNADA_LARGA_H) celda.abiertaLarga = true;
      if (j.finEn && DIA.format(new Date(j.finEn)) !== dia) celda.cruzaDia = true;
      porDia.set(dia, celda);
      mapa.set(j.idPublico, porDia);
    }
    return mapa;
  }, [jornadas.data]);

  const filas = personas.filter((p) => persona === '' || p.idPublico === persona);

  return (
    <div className="space-y-4">
      <AccionesPagina>
        <Selector aria-label="Persona" className="w-44" value={persona} onChange={(e) => setPersona(e.target.value)}>
          <option value="">Todo el equipo</option>
          {personas.map((p) => (
            <option key={p.idPublico} value={p.idPublico}>
              {p.nombre}
            </option>
          ))}
        </Selector>
        <Segmentado opciones={PERIODOS} valor={periodo} alCambiar={setPeriodo} />
      </AccionesPagina>

      <Tarjeta className="overflow-hidden">
        <div className="flex flex-wrap items-baseline justify-between gap-2 px-4 pt-3.5 pb-2.5">
          <h2 className="text-[15px] font-semibold">Asistencia</h2>
          <p className="flex flex-wrap gap-x-4 gap-y-1 text-[11.5px] text-texto-3">
            <span>Entrada – salida y horas de jornada por día</span>
            <span className="inline-flex items-center gap-1.5">
              <span className="size-2 rounded-full bg-sin-senal" /> Abierta más de {JORNADA_LARGA_H} h
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="size-2 rounded-full bg-movimiento" /> En curso
            </span>
          </p>
        </div>

        {jornadas.error ? (
          <div className="px-4 pb-4">
            <ErrorCarga mensaje={mensajeError(jornadas.error)} alReintentar={() => void jornadas.refetch()} />
          </div>
        ) : flota.isPending || jornadas.isPending ? (
          <PlanillaEsqueleto columnas={dias.length} />
        ) : filas.length === 0 ? (
          <Vacio titulo="Sin personas">No hay personas activas para mostrar.</Vacio>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[900px] border-collapse text-[12.5px]">
              <thead>
                <tr className="border-y border-borde bg-marino-50/60 text-[10.5px] font-semibold tracking-[0.05em] text-texto-3 uppercase">
                  <th className="sticky left-0 z-[1] bg-marino-50 px-4 py-2 text-left">Persona</th>
                  {dias.map((dia) => {
                    const indice = (new Date(`${dia}T12:00:00`).getDay() + 6) % 7;
                    return (
                      <th key={dia} className={cn('px-2 py-2 text-center', dia === hoy && 'text-marca', indice >= 5 && 'bg-marino-100/40')}>
                        {NOMBRE_DIA[indice]} {dia.slice(8, 10)}
                      </th>
                    );
                  })}
                  <th className="px-3 py-2 text-right">Días</th>
                  <th className="px-3 py-2 text-right">Horas</th>
                  <th className="px-3 py-2 text-right">Entrada media</th>
                </tr>
              </thead>
              <tbody>
                {filas.map((p, fila) => {
                  const porDia = celdas.get(p.idPublico);
                  const delPeriodo = dias.map((d) => porDia?.get(d)).filter((c): c is Celda => Boolean(c));
                  const minutos = delPeriodo.reduce((suma, c) => suma + c.minutos, 0);
                  const entradas = delPeriodo.map((c) => {
                    const [h, m] = HORA.format(new Date(c.entrada)).split(':').map(Number);
                    return h * 60 + m;
                  });
                  const media = entradas.length > 0 ? Math.round(entradas.reduce((a, b) => a + b, 0) / entradas.length) : null;
                  return (
                    <tr
                      key={p.idPublico}
                      className="inicio-fila border-b border-borde/70 last:border-b-0"
                      style={{ '--orden': fila } as CSSProperties}
                    >
                      <td className="sticky left-0 z-[1] bg-superficie px-4 py-2">
                        <span className="flex items-center gap-2.5">
                          <Avatar nombre={p.nombre} tamano="sm" />
                          <span className="truncate font-semibold text-marino-900">{p.nombre}</span>
                        </span>
                      </td>
                      {dias.map((dia) => {
                        const indice = (new Date(`${dia}T12:00:00`).getDay() + 6) % 7;
                        const celda = porDia?.get(dia);
                        return (
                          <td key={dia} className={cn('px-1 py-1.5 text-center', indice >= 5 && 'bg-marino-50/50')}>
                            {celda ? (
                              <Link
                                to={`/replay${consulta({ dispositivo: p.idPublico, desde: dia, hasta: dia })}`}
                                title={`${p.nombre} · ${dia}: ${celda.jornadas.length} jornada(s). Abrir la repetición de ruta.`}
                                className={cn(
                                  'block rounded-[8px] px-1.5 py-1 leading-tight transition-colors hover:bg-marino-100/70',
                                  celda.abiertaLarga ? 'bg-sin-senal-suave' : !celda.salida ? 'bg-movimiento-suave' : 'bg-marino-50',
                                )}
                              >
                                <span className="block font-semibold text-marino-900 cifras">
                                  {HORA.format(new Date(celda.entrada))}–{celda.salida ? HORA.format(new Date(celda.salida)) : '…'}
                                  {celda.cruzaDia && <sup className="ml-0.5 text-[9px] text-texto-3">+1</sup>}
                                </span>
                                <span className={cn('block text-[11px] cifras', celda.abiertaLarga ? 'font-semibold text-sin-senal' : 'text-texto-3')}>
                                  {celda.abiertaLarga ? `Sin cerrar · ${horasTexto(celda.minutos)}` : horasTexto(celda.minutos)}
                                </span>
                              </Link>
                            ) : (
                              <span className="text-texto-3">{dia > hoy ? '' : GUION}</span>
                            )}
                          </td>
                        );
                      })}
                      <td className="px-3 py-2 text-right font-semibold text-marino-900 cifras">{delPeriodo.length}</td>
                      <td className="px-3 py-2 text-right text-marino-900 cifras">{minutos > 0 ? horasTexto(minutos) : GUION}</td>
                      <td className="px-3 py-2 text-right text-texto-2 cifras">
                        {media == null ? GUION : `${String(Math.floor(media / 60)).padStart(2, '0')}:${String(media % 60).padStart(2, '0')}`}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Tarjeta>
    </div>
  );
}

function PlanillaEsqueleto({ columnas }: { columnas: number }) {
  return (
    <div className="space-y-2.5 px-4 pb-4" aria-busy="true" aria-label="Cargando asistencia">
      {Array.from({ length: 6 }, (_, i) => (
        <div key={i} className="flex items-center gap-2">
          <Esqueleto className="size-8 flex-none rounded-full" />
          <Esqueleto className="h-3 w-24 flex-none" />
          {Array.from({ length: columnas }, (_, j) => (
            <Esqueleto key={j} className="h-8 flex-1" />
          ))}
        </div>
      ))}
    </div>
  );
}
