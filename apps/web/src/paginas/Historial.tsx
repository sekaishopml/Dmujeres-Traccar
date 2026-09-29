import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { GUION, fecha } from '../util/formato';
import FiltroReplay from './operacion/FiltroReplay';
import { traerDireccion, traerFlota, traerJornadasFlota, traerParadas, traerReplay, CACHE_AUDITORIA_MS, CLAVE_FLOTA } from './operacion/datos';
import { esNoEncontrado, mensajeError } from './operacion/errores';
import { fechaHoyLocal, finDeDia, inicioDeDia } from './operacion/rango';
import { milisegundos } from './operacion/replay';
import './operacion.css';
import '../estilos/paginas.css';

// Los <input type="date"> razonan en fecha local: el Replay revive la
// jornada con la misma YYYY-MM-DD que la operadora ya eligió arriba.
const LIMITE_SIN_SENAL_MS = 5 * 60 * 1000;
const HORA = new Intl.DateTimeFormat('es-EC', { hour: '2-digit', minute: '2-digit', hour12: false });
const FECHA_CORTA = new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit' });

function horaReloj(valor: string): string {
  return HORA.format(new Date(valor));
}

// Día local (YYYY-MM-DD) de un instante, para armar el enlace de Replay.
function diaLocal(valor: string): string {
  return FECHA_CORTA.format(new Date(valor));
}

// Hora de fin con marca de cruce de medianoche (+1) cuando la jornada
// termina al día siguiente del inicio; la hora sola engañaría a la auditoría.
function horaFin(inicio: string, fin: string | null): string {
  if (fin == null) return GUION;
  return diaLocal(inicio) === diaLocal(fin) ? horaReloj(fin) : `${horaReloj(fin)} +1`;
}

function minutosTexto(minutos: number): string {
  const total = Math.max(0, Math.round(minutos));
  const horas = Math.floor(total / 60);
  const resto = total % 60;
  if (horas > 0) return resto > 0 ? `${horas} h ${resto} min` : `${horas} h`;
  return `${total} min`;
}

function bateriaTexto(pct: number | null | undefined): string {
  return pct == null || !Number.isFinite(pct) ? GUION : `${Math.round(pct)}%`;
}

// Dirección de una parada bajo demanda: el caché de datos.ts deduplica el
// geocoder y, si el servicio falla, la fila queda con el guion de siempre.
// Si el reporte de paradas ya trajo dirección, se muestra enseguida
// (`previa`) y la consulta solo reemplaza cuando resuelve.
function DireccionParada({ latitud, longitud, previa }: { latitud: number; longitud: number; previa: string | null }) {
  const consulta = useQuery({
    queryKey: ['geocode', latitud.toFixed(5), longitud.toFixed(5)],
    queryFn: () => traerDireccion(latitud, longitud),
    enabled: Number.isFinite(latitud) && Number.isFinite(longitud),
    retry: false,
    staleTime: Infinity,
  });
  const texto = consulta.isPending ? previa : (consulta.data?.direccion ?? null);
  return <span className="linea-secundaria">{texto ? texto : GUION}</span>;
}

// Momento de la cronología: el tipo elige el color del punto (.inicio/.fin/
// .detenido/.sinSenal) y conDireccion enciende el geocoder solo en paradas.
// `hora` va como texto listo para pintar; en "en curso" va null y se oculta.
interface Momento {
  tipo: 'inicio' | 'fin' | 'detenido' | 'sinSenal' | 'enCurso';
  instante: number;
  hora: string | null;
  titulo: string;
  detalle: string | null;
  conDireccion?: boolean;
  latitud?: number;
  longitud?: number;
}

interface PropsExpediente {
  nombre: string;
  inicioEn: string;
  finEn: string | null;
  idNumerico: number;
  idPublico: string;
}

// Expediente de una jornada: cronología ordenada de lo que pasó (inicio con
// batería, paradas del servidor con dirección bajo demanda, huecos de señal
// del replay y fin con batería; cuando la jornada sigue abierta, item "en
// curso" al final) y ficha con distancia y batería del tramo. El replay se
// consulta una vez al abrir el expediente; un 404 significa que el equipo no
// reportó posición en el tramo y se lee como dato ausente, no como fallo.
export function ExpedienteJornada({ nombre, inicioEn, finEn, idNumerico, idPublico }: PropsExpediente) {
  // Jornada abierta: el tramo llega hasta ahora. Si no, el rango quedaría con
  // desde == hasta y la API lo rechaza ("desde debe ser anterior a hasta").
  // El mínimo de un minuto evita rangos vacíos en jornadas recién iniciadas.
  const hastaTramo = finEn
    ?? new Date(Math.max(Date.now(), milisegundos(inicioEn) + 60_000)).toISOString();
  const paradas = useQuery({
    queryKey: ['expediente-paradas', idNumerico, inicioEn, finEn],
    queryFn: () => traerParadas(idPublico, inicioEn, hastaTramo),
    retry: false,
    staleTime: CACHE_AUDITORIA_MS,
  });
  const replay = useQuery({
    queryKey: ['expediente-replay', idNumerico, inicioEn, finEn],
    queryFn: () => traerReplay(idPublico, inicioEn, hastaTramo),
    retry: false,
    staleTime: CACHE_AUDITORIA_MS,
  });

  const resumen = replay.data?.resumen ?? null;

  const listaParadas = useMemo<Momento[]>(
    () =>
      (paradas.data?.datos ?? [])
        .filter((p) => p.dispositivoId === idNumerico && Number.isFinite(p.latitud) && Number.isFinite(p.longitud))
        .map((p) => ({
          tipo: 'detenido' as const,
          instante: milisegundos(p.inicio),
          hora: p.inicio,
          titulo: `Detenido ${minutosTexto(p.duracionMin)}`,
          detalle: p.direccion ?? null,
          conDireccion: true,
          latitud: p.latitud,
          longitud: p.longitud,
        })),
    [paradas.data, idNumerico],
  );

  // Huecos de más de 5 minutos entre fixes consecutivos del replay. Los pares
  // que el servidor ya marcó como hueco formal se excluyen para no contar
  // dos veces la misma pérdida de señal.
  const huecos = useMemo<Momento[]>(() => {
    const lista = replay.data?.posiciones ?? [];
    if (lista.length === 0) return [];
    const formal = new Set((replay.data?.huecos ?? []).map((h) => `${h.desde}|${h.hasta}`));
    const hallados: Momento[] = [];
    for (let i = 1; i < lista.length; i += 1) {
      const anterior = lista[i - 1].registradoEn;
      const siguiente = lista[i].registradoEn;
      if (formal.has(`${anterior}|${siguiente}`)) continue;
      const delta = milisegundos(siguiente) - milisegundos(anterior);
      if (delta > LIMITE_SIN_SENAL_MS) {
        hallados.push({
          tipo: 'sinSenal',
          instante: milisegundos(anterior),
          hora: anterior,
          titulo: `Sin señal ${minutosTexto(delta / 60000)}`,
          detalle: null,
        });
      }
    }
    return hallados;
  }, [replay.data]);

  const momentos = useMemo<Momento[]>(() => {
    const lista: Momento[] = [
      {
        tipo: 'inicio',
        instante: milisegundos(inicioEn),
        hora: inicioEn,
        titulo: 'Inicio de jornada',
        detalle: resumen && resumen.bateriaInicialPct != null ? `Batería ${bateriaTexto(resumen.bateriaInicialPct)}` : null,
      },
      ...listaParadas,
      ...huecos,
    ];
    if (finEn == null) {
      lista.push({ tipo: 'enCurso', instante: Number.MAX_SAFE_INTEGER, hora: null, titulo: 'Jornada en curso', detalle: null });
    } else {
      lista.push({
        tipo: 'fin',
        instante: milisegundos(finEn),
        hora: finEn,
        titulo: 'Fin de jornada',
        detalle: resumen && resumen.bateriaFinalPct != null ? `Batería ${bateriaTexto(resumen.bateriaFinalPct)}` : null,
      });
    }
    return lista.sort((a, b) => a.instante - b.instante);
  }, [inicioEn, finEn, resumen, listaParadas, huecos]);

  return (
    <article className="expediente">
      <header className="cabecera-seccion">
        <h2>Jornada de {nombre}</h2>
        <span className="cuenta">{fecha(inicioEn)}</span>
        <span className="acciones">
          <Link
            className="boton-suave"
            to={`/replay?dispositivo=${encodeURIComponent(idPublico)}&desde=${encodeURIComponent(
              diaLocal(inicioEn),
            )}&hasta=${encodeURIComponent(finEn == null ? fechaHoyLocal() : diaLocal(finEn))}`}
          >
            Ir a Replay
          </Link>
        </span>
      </header>
      {replay.isError && !esNoEncontrado(replay.error) && <p className="aviso">{mensajeError(replay.error)}</p>}
      {paradas.isError && (
        <p className="aviso">No se pudo cargar el detalle de paradas; pueden faltar paradas en la cronología.</p>
      )}
      <ul className="cronologia">
        {momentos.map((momento, indice) => (
          <li key={`${momento.tipo}-${momento.instante}-${indice}`}>
            <span className="hora">{momento.hora ? horaReloj(momento.hora) : GUION}</span>
            <span className="eje">
              <span className={`punto ${momento.tipo}`} />
            </span>
            <span>
              <span className="titulo">{momento.titulo}</span>
              {momento.conDireccion && momento.latitud != null && momento.longitud != null ? (
                <DireccionParada latitud={momento.latitud} longitud={momento.longitud} previa={momento.detalle ?? ''} />
              ) : null}
              {!momento.conDireccion && momento.detalle ? <span className="linea-secundaria">{momento.detalle}</span> : null}
            </span>
          </li>
        ))}
      </ul>
      <dl className="ficha ficha-expediente">
        <dt>Distancia</dt>
        <dd>{resumen ? `${resumen.distanciaKm.toFixed(1)} km` : GUION}</dd>
        <dt>Batería al inicio</dt>
        <dd>{bateriaTexto(resumen?.bateriaInicialPct ?? null)}</dd>
        <dt>Batería al fin</dt>
        <dd>{bateriaTexto(resumen?.bateriaFinalPct ?? null)}</dd>
      </dl>
      {!replay.isPending && resumen == null && (
        <p className="linea-secundaria">Sin recorrido del equipo en el tramo de la jornada; batería y distancia no disponibles.</p>
      )}
    </article>
  );
}

// Página de histórico: filtro de equipo o toda la flota, fecha del día que se
// audita, contadores del día y la lista de expedientes; al elegir una jornada
// se abre su expediente debajo con la cronología y el enlace al Replay.
export default function Historial() {
  const [parametros] = useSearchParams();
  const [dispositivoId, setDispositivoId] = useState(parametros.get('dispositivo') ?? '');
  const [desde, setDesde] = useState(parametros.get('desde') ?? fechaHoyLocal());
  const [hasta, setHasta] = useState(parametros.get('hasta') ?? fechaHoyLocal());
  const [seleccion, setSeleccion] = useState<string | null>(null);

  const flota = useQuery({ queryKey: CLAVE_FLOTA, queryFn: () => traerFlota() });
  const equipos = flota.data?.datos ?? [];

  const rangoValido = desde !== '' && hasta !== '' && desde <= hasta;

  const jornadas = useQuery({
    queryKey: ['jornadas-flota', dispositivoId, desde, hasta],
    queryFn: () => traerJornadasFlota(inicioDeDia(desde), finDeDia(hasta), dispositivoId || undefined),
    enabled: rangoValido,
    staleTime: CACHE_AUDITORIA_MS,
  });

  const filas = jornadas.data?.datos ?? [];
  const abiertas = filas.filter((fila) => fila.abierta).length;
  const unidades = new Set(filas.map((fila) => fila.nombre)).size;
  const totalMinutos = filas.reduce((suma, fila) => suma + (Number.isFinite(fila.duracionMin) ? fila.duracionMin : 0), 0);

  // El par de la tabla (fila clicable) conviene en <tr> con onClick; para
  // teclado el tr obtiene tabIndex y Enter también abre el expediente.
  function elegir(id: string) {
    setSeleccion((valor) => (valor === id ? null : id));
  }

  function soltar() {
    setSeleccion(null);
  }

  return (
    <section className="pagina-historial">
      <header className="cabecera-pagina">
        <div>
          <h1>Historial</h1>
          <p className="sub">Cada jornada de inicio a fin: paradas, duración y tramos sin señal.</p>
        </div>
      </header>

      <section className="seccion">
        <div className="barra-herramientas">
          <FiltroReplay
            conTodas
            compacto
            equipos={equipos}
            cargandoEquipos={flota.isPending}
            dispositivoId={dispositivoId}
            alCambiarDispositivo={(valor) => {
              setDispositivoId(valor);
              soltar();
            }}
            desde={desde}
            hasta={hasta}
            alCambiarDesde={(valor) => {
              setDesde(valor);
              soltar();
            }}
            alCambiarHasta={(valor) => {
              setHasta(valor);
              soltar();
            }}
          />
        </div>
        {rangoValido && flota.error && <p className="vacio">{mensajeError(flota.error)}</p>}
        {rangoValido && jornadas.error && <p className="vacio">{mensajeError(jornadas.error)}</p>}
        {rangoValido && !flota.error && !jornadas.error && (
          <div className="tira-datos">
            <div className="dato">
              <div className="valor" title="Jornadas registradas en el día">
                {jornadas.isPending ? GUION : filas.length}
              </div>
              <div className="etiqueta">Jornadas</div>
            </div>
            <div className="dato">
              <div className="valor">{jornadas.isPending ? GUION : abiertas}</div>
              <div className="etiqueta">Abiertas</div>
            </div>
            <div className="dato">
              <div className="valor">{jornadas.isPending ? GUION : unidades}</div>
              <div className="etiqueta">Equipos</div>
            </div>
            <div className="dato">
              <div className="valor">{filas.length === 0 ? GUION : minutosTexto(totalMinutos)}</div>
              <div className="etiqueta">Tiempo total</div>
            </div>
          </div>
        )}
      </section>

      {rangoValido && !flota.error && !jornadas.error && (
        <section className="seccion">
          <header className="cabecera-seccion">
            <h2>Jornadas del día</h2>
            <span className="cuenta">{fecha(inicioDeDia(desde))}</span>
          </header>
          <div className="bloque">
            {jornadas.isPending ? (
              <p className="vacio">Cargando jornadas…</p>
            ) : filas.length === 0 ? (
              <p className="vacio">Sin jornadas registradas ese día.</p>
            ) : (
              <div className="tabla-envoltura">
                <table className="tabla tabla-datas">
                  <thead>
                    <tr>
                      <th>Hora inicio</th>
                      <th>Hora fin</th>
                      <th className="num">Duración</th>
                      <th>Equipo</th>
                      <th>Estado</th>
                    </tr>
                  </thead>
                  <tbody>
                    {filas.map((fila) => (
                      <tr
                        key={fila.id}
                        className={String(fila.id) === seleccion ? 'seleccionada' : ''}
                        onClick={() => elegir(String(fila.id))}
                        onKeyDown={(evento) => {
                          if (evento.key === 'Enter' || evento.key === ' ') elegir(String(fila.id));
                        }}
                        tabIndex={0}
                        title="Abrir el detalle de la jornada"
                      >
                        <td>{horaReloj(fila.inicioEn)}</td>
                        <td>{fila.finEn == null ? 'En curso' : horaFin(fila.inicioEn, fila.finEn)}</td>
                        <td className="num">{minutosTexto(fila.duracionMin)}</td>
                        <td>{fila.nombre}</td>
                        <td>{fila.abierta ? 'Abierta' : 'Cerrada'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </section>
      )}

      {seleccion != null &&
        (() => {
          const fila = filas.find((item) => String(item.id) === seleccion);
          if (!fila) return null;
          return (
            <section className="seccion">
              <div className="bloque expediente">
                <ExpedienteJornada
                  nombre={fila.nombre}
                  inicioEn={fila.inicioEn}
                  finEn={fila.finEn}
                  idNumerico={fila.dispositivoId}
                  idPublico={fila.idPublico}
                />
              </div>
            </section>
          );
        })()}
    </section>
  );
}
