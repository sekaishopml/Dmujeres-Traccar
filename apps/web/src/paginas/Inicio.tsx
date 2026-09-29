import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import type { Dispositivo } from '@contratos';
import { consulta } from '../api/cliente';
import { bateria, duracion, hace, hora, GUION } from '../util/formato';
import Icono from '../componentes/Icono';
import EncabezadoPagina from '../componentes/EncabezadoPagina';
import EstadoVacio from '../componentes/EstadoVacio';
import { traerFlota, traerJornadasFlota, traerSalud, CLAVE_FLOTA, equiposHabilitados } from './operacion/datos';
import { esNoEncontrado, mensajeError } from './operacion/errores';
import { claveEstado, etiquetaEstado } from './operacion/estado';
import { finDeDia, fechaHoyLocal, inicioDeDia } from './operacion/rango';
import '../estilos/paginas.css';

const REFRESCO_MS = 15_000;
// Las jornadas y la salud se consultan agregadas (una petición por sondeo):
// el sondeo va más espaciado que el de flota y se detiene con la pestaña
// oculta.
const REFRESCO_JORNADAS_MS = 60_000;
// El estado SIN_SENAL de la API ya exige 5 minutos sin conexión; el umbral se
// repite para no avisar de equipos que acaban de reconectar entre sondeos.
const MINUTOS_SIN_SENAL = 5;

// Minutos transcurridos desde una marca ISO. Aislado en una función para que
// el render no lea el reloj directamente: el cálculo de avisos es derivado.
function minutosDesde(valor: string | null): number | null {
  if (!valor) return null;
  const diferencia = Date.now() - new Date(valor).getTime();
  return Number.isFinite(diferencia) ? diferencia / 60_000 : null;
}

interface Aviso {
  equipo: Dispositivo;
  rango: number;
  motivos: string[];
}

// Una fila de la tabla de jornadas: el turno que abrió una unidad hoy.
interface FilaJornada {
  unidadId: string;
  nombre: string;
  identificador: string;
  inicioEn: string;
  finEn: string | null;
  duracionMin: number | null;
}

export default function Inicio() {
  // Sondeo de fondo: un 401 aquí no redirige, solo deja el estado de error.
  const flota = useQuery({
    queryKey: CLAVE_FLOTA,
    queryFn: () => traerFlota({ redirigir401: false }),
    refetchInterval: REFRESCO_MS,
  });

  const equiposFlota = useMemo(() => flota.data?.datos ?? [], [flota.data]);
  // Los equipos habilitados son la fuente del estado vivo (métricas y avisos):
  // un equipo dado de baja no se cuenta ni se lista, ni aunque la caché
  // conserve la respuesta anterior. Los identificadores del histórico y de
  // salud se completan con la flota cruda para no perder datos de equipos que
  // se dieron de baja después de operar.
  const dispositivos = useMemo(() => equiposHabilitados(equiposFlota), [equiposFlota]);

  const metricas = useMemo(() => {
    const resumen = { enLinea: 0, detenido: 0, sinSenal: 0, deshabilitado: 0, bateriaBaja: 0 };
    for (const equipo of dispositivos) {
      const clave = claveEstado(equipo);
      if (clave === 'enLinea') resumen.enLinea += 1;
      else if (clave === 'detenido') resumen.detenido += 1;
      else if (clave === 'deshabilitado') resumen.deshabilitado += 1;
      // Señal: SIN_SENAL, SEÑAL_DÉBIL y DESCONOCIDO. DESHABILITADO (fuera de
      // jornada) no es un problema de conexión.
      else resumen.sinSenal += 1;
      if (equipo.bateriaPct != null && equipo.bateriaPct <= 20) resumen.bateriaBaja += 1;
    }
    return resumen;
  }, [dispositivos]);

  const hoy = fechaHoyLocal();
  // Cola del día con el agregado GET /api/v1/journeys: una sola petición en
  // vez de una por unidad visible. El identificador se completa con la flota
  // ya cargada; sin ella se muestra el id público, nunca un texto inventado.
  const jornadas = useQuery({
    queryKey: ['inicio', 'jornadas', 'hoy', hoy],
    queryFn: () => traerJornadasFlota(inicioDeDia(hoy), finDeDia(hoy), undefined, { redirigir401: false }),
    refetchInterval: () => (document.hidden ? false : REFRESCO_JORNADAS_MS),
  });

  // Salud por equipo (GET /api/v1/salud, ADR-009): estado derivado con causa.
  // El endpoint aún no existe: el 404 se lee como "sin dato", no como fallo.
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
    // Cola de auditoría: la jornada más reciente primero.
    filas.sort((a, b) => new Date(b.inicioEn).getTime() - new Date(a.inicioEn).getTime());
    return filas;
  }, [jornadas.data, equiposFlota]);

  const jornadasDisponibles = jornadas.data != null;

  // Prioridad del aviso: 0 sin reportar, 1 batería crítica, 2 fuera de jornada.
  // Un equipo puede acumular motivos y se listan juntos, sin repetir la fila.
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
      if (equipo.bateriaPct != null && equipo.bateriaPct <= 20) {
        rango = Math.min(rango, 1);
        motivos.push(`Batería al ${bateria(equipo.bateriaPct)}`);
      }
      if (clave === 'deshabilitado') {
        rango = Math.min(rango, 2);
        motivos.push(equipo.habilitado ? 'Jornada cerrada' : 'Dado de baja');
      }
      if (motivos.length > 0) lista.push({ equipo, rango, motivos });
    }
    return lista.sort(
      (a, b) => a.rango - b.rango || (a.equipo.bateriaPct ?? 101) - (b.equipo.bateriaPct ?? 101),
    );
  }, [dispositivos]);

  const actualizado = flota.dataUpdatedAt ? hace(new Date(flota.dataUpdatedAt).toISOString()) : null;

  // Tablero: una fila por equipo con todo lo que se mira en el día (estado,
  // diagnóstico, último reporte, batería y jornada). Primero lo que requiere
  // atención, después el resto por nombre.
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
        return {
          equipo,
          jornada: jornadaPorId.get(equipo.idPublico) ?? null,
          // "Último GPS hace…" ya está en su columna; la observación queda
          // para la causa real (señal débil, hueco de captura, batería).
          nota: (problema ? diagnostico.causa : aviso?.motivos.join(' · ') ?? '').replace(/^Último GPS hace [^.]*\.?\s*/, ''),
          rango: aviso?.rango ?? (problema ? 3 : 9),
        };
      })
      .sort((a, b) => a.rango - b.rango || a.equipo.nombre.localeCompare(b.equipo.nombre, 'es'));
  }, [dispositivos, avisos, salud.data, filasJornadas]);

  return (
    <section className="pagina-inicio">
      <EncabezadoPagina
        contexto="Operación"
        titulo="Inicio"
        sub={`${actualizado ? `Actualizado ${actualizado}` : 'Sin datos todavía'}${
          flota.isFetching ? ' · actualizando…' : ''
        }`}
        acciones={
          <Link className="boton boton-suave con-icono" to="/en-vivo">
            <Icono nombre="enVivo" />
            Ver mapa en vivo
          </Link>
        }
      />

      {flota.isPending && <p className="vacio pulso">Cargando…</p>}
      {flota.error && <EstadoVacio icono="sistema">{mensajeError(flota.error)}</EstadoVacio>}

      {flota.data && (
        <>
          <div className="tira-datos">
            <div className="dato">
              <div className="valor">{flota.data.total}</div>
              <div className="etiqueta">Equipos</div>
            </div>
            <div className="dato">
              <div className="valor">{metricas.enLinea}</div>
              <div className="etiqueta">En movimiento</div>
            </div>
            <div className="dato">
              <div className="valor">{metricas.detenido}</div>
              <div className="etiqueta">Detenidos</div>
            </div>
            <div className={`dato${metricas.sinSenal > 0 ? ' aviso' : ''}`}>
              <div className="valor">{metricas.sinSenal}</div>
              <div className="etiqueta">Sin señal</div>
            </div>
            <div className="dato">
              <div className="valor">{jornadasDisponibles ? filasJornadas.length : GUION}</div>
              <div className="etiqueta">Jornadas hoy</div>
            </div>
            <div className={`dato${metricas.bateriaBaja > 0 ? ' alerta' : ''}`}>
              <div className="valor">{metricas.bateriaBaja}</div>
              <div className="etiqueta">Batería baja</div>
            </div>
          </div>

          <section className="seccion tablero">
            {dispositivos.length === 0 ? (
              <EstadoVacio icono="historial">No hay equipos asignados a esta cuenta.</EstadoVacio>
            ) : (
              <div className="tabla-envoltura">
                <table className="tabla">
                  <thead>
                    <tr>
                      <th>Equipo</th>
                      <th>Estado</th>
                      <th>Último reporte</th>
                      <th className="num">Batería</th>
                      <th>Jornada de hoy</th>
                      <th>Observación</th>
                      <th aria-label="Acciones" />
                    </tr>
                  </thead>
                  <tbody>
                    {filas.map(({ equipo, jornada, nota }) => (
                      <tr key={equipo.id}>
                        <td>
                          <Link className="enlace-tabla" to={`/unidad/${equipo.idPublico}`}>
                            {equipo.nombre}
                          </Link>
                          <div className="apagado mono">{equipo.identificadorUnico}</div>
                        </td>
                        <td>
                          <span className={`chip ${claveEstado(equipo)}`}>{etiquetaEstado(equipo)}</span>
                        </td>
                        <td>{equipo.ultimaConexion ? hace(equipo.ultimaConexion) : GUION}</td>
                        <td className="num">{equipo.bateriaPct != null ? bateria(equipo.bateriaPct) : GUION}</td>
                        <td>
                          {jornada
                            ? jornada.finEn
                              ? `${hora(jornada.inicioEn)} – ${hora(jornada.finEn)}`
                              : `Desde ${hora(jornada.inicioEn)}`
                            : GUION}
                          {jornada?.duracionMin != null && (
                            <div className="apagado">{duracion(jornada.duracionMin * 60)}</div>
                          )}
                        </td>
                        <td className="observacion">{nota}</td>
                        <td className="acciones-fila">
                          <Link
                            className="enlace-tabla"
                            to={`/replay${consulta({ dispositivo: equipo.idPublico, desde: hoy, hasta: hoy })}`}
                          >
                            Replay
                          </Link>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {salud.error && !esNoEncontrado(salud.error) && (
              <p className="replay-nota">Diagnóstico no disponible: {mensajeError(salud.error)}</p>
            )}
          </section>
        </>
      )}
    </section>
  );
}
