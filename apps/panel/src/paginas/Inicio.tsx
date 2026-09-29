import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Battery, BatteryLow, CalendarClock, FileText, Footprints, Map as MapaIcono, Pause, Route, Users, WifiOff } from 'lucide-react';
import type { Dispositivo } from '@contratos';
import { consulta } from '@/lib/api';
import { cn } from '@/lib/cn';
import { Avatar } from '@/componentes/ui/Avatar';
import { Cifra } from '@/componentes/ui/Cifra';
import { ChipEstado } from '@/componentes/ui/ChipEstado';
import { Tarjeta, CabeceraTarjeta } from '@/componentes/ui/Tarjeta';
import { Tabla, Th, Td, Fila } from '@/componentes/ui/Tabla';
import { Cargando, ErrorCarga, Vacio } from '@/componentes/ui/Estados';
import { claseBoton } from '@/componentes/ui/Boton';
import { AccionesPagina } from '@/componentes/marco/Marco';
import { bateria, duracion, hace, hora, GUION } from '@/dominio/formatoBase';
import {
  traerFlota,
  traerJornadasFlota,
  traerSalud,
  CLAVE_FLOTA,
  equiposHabilitados,
} from '@/dominio/datos';
import { esNoEncontrado, mensajeError } from '@/dominio/errores';
import { etiquetaEstado, claveEstado } from '@/dominio/estado';
import { finDeDia, fechaHoyLocal, inicioDeDia } from '@/dominio/rango';

const REFRESCO_MS = 15_000;
// Jornadas y salud son consultas agregadas: sondeo más espaciado, en pausa con
// la pestaña oculta.
const REFRESCO_JORNADAS_MS = 60_000;
// La API ya exige 5 min sin conexión para SIN_SENAL; se repite el umbral para
// no avisar de equipos que acaban de reconectar entre sondeos.
const MINUTOS_SIN_SENAL = 5;
const BATERIA_BAJA_PCT = 15;

function minutosDesde(valor: string | null): number | null {
  if (!valor) return null;
  const diferencia = Date.now() - new Date(valor).getTime();
  return Number.isFinite(diferencia) ? diferencia / 60_000 : null;
}

interface Aviso {
  equipo: Dispositivo;
  // 0 sin reportar, 1 batería crítica, 2 fuera de jornada.
  rango: number;
  motivos: string[];
}

interface FilaJornada {
  unidadId: string;
  nombre: string;
  identificador: string;
  inicioEn: string;
  finEn: string | null;
  duracionMin: number | null;
}

function TextoJornada({ jornada }: { jornada: FilaJornada | null }) {
  if (!jornada) return <span className="text-texto-3">{GUION}</span>;
  return (
    <>
      <span className="cifras">
        {jornada.finEn ? `${hora(jornada.inicioEn)} – ${hora(jornada.finEn)}` : `Desde ${hora(jornada.inicioEn)}`}
      </span>
      {jornada.duracionMin != null && (
        <span className="block text-[12px] text-texto-3">{duracion(jornada.duracionMin * 60)}</span>
      )}
    </>
  );
}

export default function Inicio() {
  // Sondeo de fondo: un 401 aquí no redirige, solo deja el estado de error.
  const flota = useQuery({
    queryKey: CLAVE_FLOTA,
    queryFn: () => traerFlota({ redirigir401: false }),
    refetchInterval: REFRESCO_MS,
  });

  const equiposFlota = useMemo(() => flota.data?.datos ?? [], [flota.data]);
  // Solo equipos habilitados alimentan métricas y avisos; la flota cruda
  // completa los identificadores de jornadas de equipos dados de baja.
  const dispositivos = useMemo(() => equiposHabilitados(equiposFlota), [equiposFlota]);

  const metricas = useMemo(() => {
    const resumen = { enLinea: 0, detenido: 0, sinSenal: 0, deshabilitado: 0, bateriaBaja: 0 };
    for (const equipo of dispositivos) {
      const clave = claveEstado(equipo);
      if (clave === 'enLinea') resumen.enLinea += 1;
      else if (clave === 'detenido') resumen.detenido += 1;
      else if (clave === 'deshabilitado') resumen.deshabilitado += 1;
      // Señal: SIN_SENAL, SEÑAL_DÉBIL y DESCONOCIDO.
      else resumen.sinSenal += 1;
      if (equipo.bateriaPct != null && equipo.bateriaPct <= BATERIA_BAJA_PCT) resumen.bateriaBaja += 1;
    }
    return resumen;
  }, [dispositivos]);

  const hoy = fechaHoyLocal();
  const jornadas = useQuery({
    queryKey: ['inicio', 'jornadas', 'hoy', hoy],
    queryFn: () => traerJornadasFlota(inicioDeDia(hoy), finDeDia(hoy), undefined, { redirigir401: false }),
    refetchInterval: () => (document.hidden ? false : REFRESCO_JORNADAS_MS),
  });

  // GET /api/v1/salud puede no existir aún: el 404 se lee como "sin dato".
  const salud = useQuery({
    queryKey: ['salud'],
    queryFn: traerSalud,
    refetchInterval: () => (document.hidden ? false : REFRESCO_JORNADAS_MS),
    retry: false,
  });

  const filasJornadas = useMemo<FilaJornada[]>(() => {
    const identificadores = new Map(equiposFlota.map((equipo) => [equipo.idPublico, equipo.identificadorUnico]));
    const filas = (jornadas.data?.datos ?? []).map((jornada) => ({
      unidadId: jornada.idPublico,
      nombre: jornada.nombre,
      identificador: identificadores.get(jornada.idPublico) ?? jornada.idPublico,
      inicioEn: jornada.inicioEn,
      finEn: jornada.finEn,
      duracionMin: jornada.duracionMin,
    }));
    filas.sort((a, b) => new Date(b.inicioEn).getTime() - new Date(a.inicioEn).getTime());
    return filas;
  }, [jornadas.data, equiposFlota]);

  const jornadasDisponibles = jornadas.data != null;

  const avisos = useMemo(() => {
    const lista: Aviso[] = [];
    for (const equipo of dispositivos) {
      const clave = claveEstado(equipo);
      const motivos: string[] = [];
      let rango = Number.POSITIVE_INFINITY;
      if (clave === 'sinSenal' || clave === 'desconocido') {
        const minutos = minutosDesde(equipo.ultimaConexion);
        if (minutos == null || minutos > MINUTOS_SIN_SENAL) {
          rango = 0;
          motivos.push(
            equipo.ultimaConexion ? `Sin reportar desde las ${hora(equipo.ultimaConexion)}` : 'Nunca ha reportado',
          );
        }
      }
      if (equipo.bateriaPct != null && equipo.bateriaPct <= BATERIA_BAJA_PCT) {
        rango = Math.min(rango, 1);
        motivos.push(`Batería al ${bateria(equipo.bateriaPct)}`);
      }
      if (clave === 'deshabilitado') {
        rango = Math.min(rango, 2);
        motivos.push(equipo.habilitado ? 'Jornada cerrada' : 'Dado de baja');
      }
      if (motivos.length > 0) lista.push({ equipo, rango, motivos });
    }
    return lista.sort((a, b) => a.rango - b.rango || (a.equipo.bateriaPct ?? 101) - (b.equipo.bateriaPct ?? 101));
  }, [dispositivos]);

  const filas = useMemo(() => {
    const saludPorId = new Map((salud.data?.datos ?? []).map((equipo) => [equipo.dispositivoId, equipo]));
    const avisoPorId = new Map(avisos.map((aviso) => [aviso.equipo.id, aviso]));
    const jornadaPorId = new Map<string, FilaJornada>();
    for (const fila of filasJornadas) if (!jornadaPorId.has(fila.unidadId)) jornadaPorId.set(fila.unidadId, fila);
    return dispositivos
      .map((equipo) => {
        const aviso = avisoPorId.get(equipo.id);
        const diagnostico = saludPorId.get(equipo.id);
        const problema = diagnostico != null && diagnostico.estado !== 'HEALTHY';
        const nota = (problema ? diagnostico.causa : (aviso?.motivos.join(' · ') ?? '')).replace(
          /^Último GPS hace [^.]*\.?\s*/,
          '',
        );
        return {
          equipo,
          jornada: jornadaPorId.get(equipo.idPublico) ?? null,
          nota,
          rango: aviso?.rango ?? (problema ? 3 : 9),
        };
      })
      .sort((a, b) => a.rango - b.rango || a.equipo.nombre.localeCompare(b.equipo.nombre, 'es'));
  }, [dispositivos, avisos, salud.data, filasJornadas]);

  // Tarjeta "Requieren atención": sin señal, batería baja y causas de salud.
  const atencion = useMemo(
    () => filas.filter((f) => f.rango <= 1 || (f.rango === 3 && f.nota !== '')),
    [filas],
  );

  const actualizado = flota.dataUpdatedAt ? hace(new Date(flota.dataUpdatedAt).toISOString()) : null;

  return (
    <div className="space-y-5">
      <AccionesPagina>
        <span className="hidden text-[12px] text-texto-3 sm:inline">
          {actualizado ? `Actualizado ${actualizado}` : 'Sin datos todavía'}
          {flota.isFetching ? ' · actualizando…' : ''}
        </span>
        <Link to="/en-vivo" className={claseBoton('secundario')}>
          <MapaIcono className="size-4" />
          Ver mapa en vivo
        </Link>
      </AccionesPagina>

      {flota.isPending && <Cargando />}
      {flota.error && <ErrorCarga mensaje={mensajeError(flota.error)} alReintentar={() => void flota.refetch()} />}

      {flota.data && (
        <>
          <div className="grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-3 2xl:grid-cols-6">
            <Cifra etiqueta="Personas" valor={flota.data.total} icono={Users} tono="marino" />
            <Cifra etiqueta="En movimiento" valor={metricas.enLinea} icono={Footprints} tono="movimiento" />
            <Cifra etiqueta="Detenidas" valor={metricas.detenido} icono={Pause} tono="detenido" />
            <Cifra
              etiqueta="Sin señal"
              valor={metricas.sinSenal}
              icono={WifiOff}
              tono="sinSenal"
              resaltar={metricas.sinSenal > 0}
            />
            <Cifra
              etiqueta="Jornadas hoy"
              valor={jornadasDisponibles ? filasJornadas.length : GUION}
              icono={CalendarClock}
              tono="marca"
            />
            <Cifra
              etiqueta="Batería baja"
              valor={metricas.bateriaBaja}
              detalle={`≤ ${BATERIA_BAJA_PCT}%`}
              icono={BatteryLow}
              tono="peligro"
              resaltar={metricas.bateriaBaja > 0}
            />
          </div>

          <div className="grid gap-5 xl:grid-cols-3">
            <Tarjeta className="min-w-0 xl:col-span-2">
              <CabeceraTarjeta titulo="Personas" detalle={dispositivos.length} />
              {dispositivos.length === 0 ? (
                <Vacio titulo="Sin personas asignadas">No hay equipos asignados a esta cuenta.</Vacio>
              ) : (
                <Tabla>
                  <thead>
                    <tr>
                      <Th>Persona</Th>
                      <Th>Estado</Th>
                      <Th numerico>Batería</Th>
                      <Th>Jornada de hoy</Th>
                      <Th className="w-0" aria-label="Acciones" />
                    </tr>
                  </thead>
                  <tbody>
                    {filas.map(({ equipo, jornada, nota }) => (
                      <Fila key={equipo.id}>
                        <Td>
                          <div className="flex items-center gap-3">
                            <Avatar nombre={equipo.nombre} estado={claveEstado(equipo)} tamano="sm" />
                            <div className="min-w-0">
                              <Link
                                to={`/unidad/${equipo.idPublico}`}
                                className="block font-semibold text-marino-900 hover:text-marca"
                              >
                                {equipo.nombre}
                              </Link>
                              <span className="block max-w-56 truncate text-[11.5px] text-texto-3" title={nota || undefined}>
                                {nota || equipo.identificadorUnico}
                              </span>
                            </div>
                          </div>
                        </Td>
                        <Td>
                          <ChipEstado equipo={equipo} />
                          <span className="mt-1 block pl-1 text-[11.5px] whitespace-nowrap text-texto-3">
                            {equipo.ultimaConexion ? hace(equipo.ultimaConexion) : 'Sin reportes'}
                          </span>
                        </Td>
                        <Td numerico className="whitespace-nowrap text-texto-2">
                          <span className={cn(equipo.bateriaPct != null && equipo.bateriaPct <= BATERIA_BAJA_PCT && 'font-semibold text-peligro')}>
                            {bateria(equipo.bateriaPct)}
                          </span>
                        </Td>
                        <Td className="whitespace-nowrap">
                          <TextoJornada jornada={jornada} />
                        </Td>
                        <Td className="whitespace-nowrap">
                          <div className="flex items-center justify-end gap-1">
                            <Link
                              to={`/unidad/${equipo.idPublico}`}
                              title="Expediente"
                              aria-label={`Expediente de ${equipo.nombre}`}
                              className="grid size-8 place-items-center rounded-control text-texto-2 transition-colors hover:bg-fondo hover:text-marca"
                            >
                              <FileText className="size-4" />
                            </Link>
                            <Link
                              to={`/replay${consulta({ dispositivo: equipo.idPublico, desde: hoy, hasta: hoy })}`}
                              title="Replay de hoy"
                              aria-label={`Replay de hoy de ${equipo.nombre}`}
                              className="grid size-8 place-items-center rounded-control text-texto-2 transition-colors hover:bg-fondo hover:text-marca"
                            >
                              <Route className="size-4" />
                            </Link>
                          </div>
                        </Td>
                      </Fila>
                    ))}
                  </tbody>
                </Tabla>
              )}
              {salud.error && !esNoEncontrado(salud.error) && (
                <p className="px-5 py-3 text-[12px] text-texto-3">
                  Diagnóstico no disponible: {mensajeError(salud.error)}
                </p>
              )}
            </Tarjeta>

            <div className="min-w-0 space-y-5">
              <Tarjeta>
                <CabeceraTarjeta titulo="Requieren atención" detalle={atencion.length} />
                {atencion.length === 0 ? (
                  <Vacio icono={Battery} titulo="Todo en orden">
                    Ninguna persona sin señal, con batería baja u observaciones.
                  </Vacio>
                ) : (
                  <ul className="px-2 pb-2">
                    {atencion.map(({ equipo, nota }) => (
                      <li key={equipo.id}>
                        <Link
                          to={`/unidad/${equipo.idPublico}`}
                          className="flex items-center gap-2.5 rounded-control px-3 py-2 transition-colors hover:bg-fondo"
                        >
                          <Avatar nombre={equipo.nombre} estado={claveEstado(equipo)} tamano="sm" />
                          <span className="min-w-0 flex-1 leading-tight">
                            <span className="block truncate text-[13px] font-semibold text-marino-900">{equipo.nombre}</span>
                            <span className="block truncate text-[11.5px] text-texto-3">
                              {nota || `${etiquetaEstado(equipo)} · ${hace(equipo.ultimaConexion)}`}
                            </span>
                          </span>
                          <span className="text-[12px] text-texto-2 cifras">{bateria(equipo.bateriaPct)}</span>
                        </Link>
                      </li>
                    ))}
                  </ul>
                )}
              </Tarjeta>

              <Tarjeta>
                <CabeceraTarjeta titulo="Jornadas de hoy" detalle={jornadasDisponibles ? filasJornadas.length : undefined} />
                {jornadas.isPending ? (
                  <Cargando texto="Cargando jornadas…" className="py-8" />
                ) : jornadas.error ? (
                  <div className="px-5 pb-4">
                    <ErrorCarga mensaje={mensajeError(jornadas.error)} />
                  </div>
                ) : filasJornadas.length === 0 ? (
                  <Vacio icono={CalendarClock} titulo="Sin jornadas hoy">
                    Aún no se registran inicios de jornada en el día.
                  </Vacio>
                ) : (
                  <ul className="px-2 pb-2">
                    {filasJornadas.map((j) => (
                      <li key={`${j.unidadId}-${j.inicioEn}`} className="flex items-center gap-2.5 px-3 py-2">
                        <span
                          className={cn('size-2 flex-none rounded-full', j.finEn ? 'bg-deshabilitado' : 'bg-movimiento')}
                          title={j.finEn ? 'Cerrada' : 'En curso'}
                        />
                        <span className="min-w-0 flex-1 truncate text-[13px] font-semibold text-marino-900">{j.nombre}</span>
                        <span className="text-[12px] whitespace-nowrap text-texto-2 cifras">
                          {hora(j.inicioEn)} – {j.finEn ? hora(j.finEn) : 'ahora'}
                        </span>
                        <span className="w-16 text-right text-[11.5px] whitespace-nowrap text-texto-3 cifras">
                          {j.duracionMin != null ? duracion(j.duracionMin * 60) : GUION}
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
