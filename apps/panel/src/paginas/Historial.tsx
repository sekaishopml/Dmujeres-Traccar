import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { CalendarClock, Clock, Radio, Users } from 'lucide-react';
import { consulta } from '@/lib/api';
import { Avatar } from '@/componentes/ui/Avatar';
import { Cifra } from '@/componentes/ui/Cifra';
import { Insignia } from '@/componentes/ui/ChipEstado';
import { Entrada, Selector } from '@/componentes/ui/Campo';
import { Segmentado } from '@/componentes/ui/Segmentado';
import { Tarjeta, CabeceraTarjeta } from '@/componentes/ui/Tarjeta';
import { Tabla, Th, Td, Fila } from '@/componentes/ui/Tabla';
import { Cargando, ErrorCarga, Vacio } from '@/componentes/ui/Estados';
import { AccionesPagina } from '@/componentes/marco/Marco';
import { BarraDia } from '@/componentes/historial/BarraDia';
import { GUION } from '@/dominio/formatoBase';
import { traerFlota, traerJornadasFlota, CACHE_AUDITORIA_MS, CLAVE_FLOTA, equiposHabilitados } from '@/dominio/datos';
import { mensajeError } from '@/dominio/errores';
import { fechaAyerLocal, fechaHoyLocal, finDeDia, inicioDeDia } from '@/dominio/rango';

const HORA = new Intl.DateTimeFormat('es-EC', { timeZone: 'America/Guayaquil', hour: '2-digit', minute: '2-digit', hour12: false });
const FECHA_CORTA = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Guayaquil', year: 'numeric', month: '2-digit', day: '2-digit' });
const FECHA_LARGA = new Intl.DateTimeFormat('es-EC', { timeZone: 'America/Guayaquil', weekday: 'short', day: '2-digit', month: 'short', year: 'numeric' });

type Atajo = 'hoy' | 'ayer' | 'ambos';
const ATAJOS: readonly { valor: Atajo; etiqueta: string }[] = [
  { valor: 'hoy', etiqueta: 'Hoy' },
  { valor: 'ayer', etiqueta: 'Ayer' },
  { valor: 'ambos', etiqueta: 'Hoy y ayer' },
];

function horaReloj(valor: string): string {
  return HORA.format(new Date(valor));
}

// Día local (YYYY-MM-DD) de un instante, para armar el enlace de Replay.
function diaLocal(valor: string): string {
  return FECHA_CORTA.format(new Date(valor));
}

function ayerLocal(): string {
  return fechaAyerLocal();
}

// Fin con marca de cruce de medianoche (+1): la hora sola engañaría a la auditoría.
function horaFin(inicio: string, fin: string): string {
  return diaLocal(inicio) === diaLocal(fin) ? horaReloj(fin) : `${horaReloj(fin)} +1`;
}

function minutosTexto(minutos: number): string {
  const total = Math.max(0, Math.round(minutos));
  const horas = Math.floor(total / 60);
  const resto = total % 60;
  if (horas > 0) return resto > 0 ? `${horas} h ${resto} min` : `${horas} h`;
  return `${total} min`;
}

export default function Historial() {
  const [parametros] = useSearchParams();
  const [dispositivoId, setDispositivoId] = useState(parametros.get('dispositivo') ?? '');
  const [desde, setDesde] = useState(parametros.get('desde') ?? fechaHoyLocal());
  const [hasta, setHasta] = useState(parametros.get('hasta') ?? fechaHoyLocal());

  const flota = useQuery({ queryKey: CLAVE_FLOTA, queryFn: () => traerFlota() });
  // Solo equipos habilitados: /journeys no devuelve jornadas de los dados de baja.
  const equipos = useMemo(() => equiposHabilitados(flota.data?.datos ?? []), [flota.data]);

  const rangoValido = desde !== '' && hasta !== '' && desde <= hasta;

  const jornadas = useQuery({
    queryKey: ['jornadas-flota', dispositivoId, desde, hasta],
    queryFn: () => traerJornadasFlota(inicioDeDia(desde), finDeDia(hasta), dispositivoId || undefined),
    enabled: rangoValido,
    staleTime: CACHE_AUDITORIA_MS,
  });

  const filas = useMemo(() => jornadas.data?.datos ?? [], [jornadas.data]);
  const abiertas = filas.filter((fila) => fila.abierta).length;
  const personas = new Set(filas.map((fila) => fila.idPublico)).size;
  const totalMinutos = filas.reduce((suma, fila) => suma + (Number.isFinite(fila.duracionMin) ? fila.duracionMin : 0), 0);

  const hoy = fechaHoyLocal();
  const ayer = ayerLocal();
  const atajoActivo: Atajo | null =
    desde === hoy && hasta === hoy ? 'hoy' : desde === ayer && hasta === ayer ? 'ayer' : desde === ayer && hasta === hoy ? 'ambos' : null;

  function aplicarAtajo(atajo: Atajo) {
    setDesde(atajo === 'hoy' ? hoy : ayer);
    setHasta(atajo === 'ayer' ? ayer : hoy);
  }

  const pendiente = jornadas.isPending && rangoValido;
  const errorConsulta = flota.error ?? jornadas.error;

  return (
    <div className="space-y-5">
      <AccionesPagina>
        <Selector
          aria-label="Persona"
          className="w-44"
          value={dispositivoId}
          disabled={flota.isPending}
          onChange={(e) => setDispositivoId(e.target.value)}
        >
          <option value="">Todas las personas</option>
          {equipos.map((equipo) => (
            <option key={equipo.id} value={equipo.idPublico}>
              {equipo.nombre}
            </option>
          ))}
        </Selector>
        <Entrada
          type="date"
          aria-label="Desde"
          className="w-36"
          value={desde}
          max={hasta || undefined}
          onChange={(e) => setDesde(e.target.value)}
        />
        <Entrada
          type="date"
          aria-label="Hasta"
          className="w-36"
          value={hasta}
          min={desde || undefined}
          onChange={(e) => setHasta(e.target.value)}
        />
        <Segmentado opciones={ATAJOS} valor={atajoActivo} alCambiar={aplicarAtajo} />
      </AccionesPagina>

      {!rangoValido && (
        <ErrorCarga mensaje="Elige un rango válido: la fecha «Desde» no puede ser posterior a «Hasta»." />
      )}
      {rangoValido && errorConsulta && <ErrorCarga mensaje={mensajeError(errorConsulta)} />}

      {rangoValido && !errorConsulta && (
        <>
          <div className="grid grid-cols-2 gap-3 sm:gap-4 xl:grid-cols-4">
            <Cifra etiqueta="Jornadas" valor={pendiente ? GUION : filas.length} icono={CalendarClock} tono="marino" />
            <Cifra etiqueta="Abiertas" valor={pendiente ? GUION : abiertas} icono={Radio} tono="movimiento" />
            <Cifra etiqueta="Personas" valor={pendiente ? GUION : personas} icono={Users} tono="detenido" />
            <Cifra
              etiqueta="Tiempo total"
              valor={pendiente || filas.length === 0 ? GUION : minutosTexto(totalMinutos)}
              icono={Clock}
              tono="marca"
            />
          </div>

          <Tarjeta>
            <CabeceraTarjeta
              titulo="Jornadas"
              detalle={desde === hasta ? FECHA_LARGA.format(new Date(inicioDeDia(desde))) : `${desde} → ${hasta}`}
            />
            {pendiente ? (
              <Cargando texto="Cargando jornadas…" />
            ) : filas.length === 0 ? (
              <Vacio icono={CalendarClock} titulo="Sin jornadas registradas">
                No hay jornadas en el rango elegido.
              </Vacio>
            ) : (
              <Tabla>
                <thead>
                  <tr>
                    <Th>Persona</Th>
                    <Th>Fecha</Th>
                    <Th>Inicio</Th>
                    <Th>Fin</Th>
                    <Th numerico>Duración</Th>
                    <Th>Estado</Th>
                    <Th>Día (0–24 h)</Th>
                    <Th aria-label="Acciones" />
                  </tr>
                </thead>
                <tbody>
                  {filas.map((fila) => (
                    <Fila key={fila.id}>
                      <Td>
                        <div className="flex items-center gap-3">
                          <Avatar nombre={fila.nombre} tamano="sm" />
                          <span className="font-semibold text-marino-900">{fila.nombre}</span>
                        </div>
                      </Td>
                      <Td className="whitespace-nowrap text-texto-2">
                        {FECHA_LARGA.format(new Date(fila.inicioEn))}
                      </Td>
                      <Td className="cifras">{horaReloj(fila.inicioEn)}</Td>
                      <Td className="cifras">
                        {fila.finEn == null ? <Insignia tono="exito">En curso</Insignia> : horaFin(fila.inicioEn, fila.finEn)}
                      </Td>
                      <Td numerico className="whitespace-nowrap">
                        {Number.isFinite(fila.duracionMin) ? minutosTexto(fila.duracionMin) : GUION}
                      </Td>
                      <Td>
                        <Insignia tono={fila.abierta ? 'exito' : 'neutro'}>{fila.abierta ? 'Abierta' : 'Cerrada'}</Insignia>
                      </Td>
                      <Td>
                        <BarraDia inicioEn={fila.inicioEn} finEn={fila.finEn} />
                      </Td>
                      <Td className="whitespace-nowrap">
                        <div className="flex items-center gap-3 text-[12.5px] font-semibold">
                          <Link
                            to={`/replay${consulta({
                              dispositivo: fila.idPublico,
                              desde: diaLocal(fila.inicioEn),
                              hasta: fila.finEn == null ? hoy : diaLocal(fila.finEn),
                            })}`}
                            className="text-marino-700 hover:text-marca"
                          >
                            Replay
                          </Link>
                          <Link to={`/unidad/${fila.idPublico}`} className="text-marino-700 hover:text-marca">
                            Expediente
                          </Link>
                        </div>
                      </Td>
                    </Fila>
                  ))}
                </tbody>
              </Tabla>
            )}
          </Tarjeta>
        </>
      )}
    </div>
  );
}
