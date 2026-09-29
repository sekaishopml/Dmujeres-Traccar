import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import type { Dispositivo, EstadoSalud } from '@contratos';
import { consulta } from '../api/cliente';
import { bateria, duracion, hace, hora, GUION } from '../util/formato';
import Icono from '../componentes/Icono';
import { traerFlota, traerJornadasFlota, traerSalud, CLAVE_FLOTA, equiposHabilitados } from './operacion/datos';
import { esNoEncontrado, mensajeError } from './operacion/errores';
import { claveEstado } from './operacion/estado';
import { finDeDia, fechaHoyLocal, inicioDeDia } from './operacion/rango';
import '../estilos/paginas.css';

const REFRESCO_MS = 15_000;
// Las jornadas y la salud se consultan agregadas (una petición por sondeo):
// el sondeo va más espaciado que el de flota y se detiene con la pestaña
// oculta.
const REFRESCO_JORNADAS_MS = 60_000;
const MAX_AVISOS = 6;
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

// Chip de salud con las clases existentes: el color ya se lee en el resto del
// panel y no se agregan estilos nuevos.
const CLASE_SALUD: Record<EstadoSalud, string> = {
  HEALTHY: 'enLinea',
  DEGRADED: 'senalDebil',
  OFFLINE: 'sinSenal',
  RECOVERING: 'detenido',
  MISCONFIGURED: 'deshabilitado',
};

const ETIQUETA_SALUD: Record<EstadoSalud, string> = {
  HEALTHY: 'Al día',
  DEGRADED: 'Con problemas',
  OFFLINE: 'Sin señal',
  RECOVERING: 'Reconectando',
  MISCONFIGURED: 'Mal configurado',
};

// Edad del último fix en texto corto ("hace 8 min"): la causa explica el
// porqué y esto pone el cuándo. Sin dato se muestra el guion, nunca un cero
// inventado.
function haceSegundos(segundos: number | null | undefined): string {
  if (segundos == null || !Number.isFinite(segundos)) return GUION;
  if (segundos < 60) return `hace ${Math.max(0, Math.round(segundos))} s`;
  const minutos = Math.round(segundos / 60);
  if (minutos < 60) return `hace ${minutos} min`;
  return `hace ${Math.floor(minutos / 60)} h ${minutos % 60} min`;
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
  const idsHabilitados = useMemo(() => new Set(dispositivos.map((equipo) => equipo.id)), [dispositivos]);

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

  const avisosVisibles = avisos.slice(0, MAX_AVISOS);
  const avisosRestantes = avisos.length - avisosVisibles.length;

  const actualizado = flota.dataUpdatedAt ? hace(new Date(flota.dataUpdatedAt).toISOString()) : null;

  const unidadesConJornada = useMemo(
    () => new Set(filasJornadas.map((fila) => fila.unidadId)).size,
    [filasJornadas],
  );
  const cuentaJornadas = jornadas.isPending
    ? 'Consultando…'
    : jornadasDisponibles
      ? `${filasJornadas.length} jornadas · ${unidadesConJornada} equipos`
      : 'No disponibles';

  // La salud por equipo es estado vivo: los equipos deshabilitados no se
  // listan. Sin flota cargada no se puede distinguir un habilitado y se
  // conserva la respuesta tal cual para no vaciar la sección por un fallo de
  // /fleet.
  const equiposSalud = useMemo(() => {
    const datos = salud.data?.datos ?? [];
    if (!flota.data) return datos;
    return datos.filter((equipo) => idsHabilitados.has(equipo.dispositivoId));
  }, [salud.data, flota.data, idsHabilitados]);
  // Los nombres se completan con la flota cruda: una entrada de salud solo se
  // pinta si su equipo está habilitado, pero el nombre debe existir aunque el
  // contrato lo haya omitido.
  const nombresSalud = useMemo(() => new Map(equiposFlota.map((equipo) => [equipo.id, equipo])), [equiposFlota]);
  const cuentaSalud = salud.isPending
    ? 'Consultando…'
    : salud.error
      ? 'No disponibles'
      : `${equiposSalud.length} equipos`;

  return (
    <section className="pagina-inicio">
      <header className="cabecera-pagina">
        <div>
          <h1>Inicio</h1>
          <p className="sub">
            Resumen de la operación de hoy
            {actualizado ? ` · actualizado ${actualizado}` : ' · sin datos todavía'}
            {flota.isFetching ? ' · actualizando…' : ''}
          </p>
        </div>
        <span className="empuja" />
        <Link className="boton boton-suave con-icono" to="/en-vivo">
          <Icono nombre="enVivo" />
          Ver mapa en vivo
        </Link>
      </header>

      {flota.isPending && <p className="vacio">Cargando…</p>}
      {flota.error && (
        <p className="vacio">
          <Icono nombre="sistema" />
          {mensajeError(flota.error)}
        </p>
      )}

      {flota.data && (
        <>
          <section className="seccion">
            <div className="tira-datos">
              <div className="dato">
                <div className="valor">{flota.data.total}</div>
                <div className="etiqueta">Equipos</div>
              </div>
              <div className="dato">
                <div className="valor">{metricas.enLinea}</div>
                <div className="etiqueta">En línea</div>
              </div>
              <div className="dato">
                <div className="valor">{metricas.detenido}</div>
                <div className="etiqueta">Detenido</div>
              </div>
              <div className={`dato${metricas.sinSenal > 0 ? ' aviso' : ''}`}>
                <div className="valor">{metricas.sinSenal}</div>
                <div className="etiqueta">Sin señal / débil</div>
              </div>
              <div className={`dato${metricas.bateriaBaja > 0 ? ' alerta' : ''}`}>
                <div className="valor">{metricas.bateriaBaja}</div>
                <div className="etiqueta">Batería ≤ 20%</div>
              </div>
            </div>
          </section>

          <section className="seccion">
            <div className="bloque">
              <header className="cabecera-seccion">
                <h2>Jornadas de hoy</h2>
                <span className="cuenta">{cuentaJornadas}</span>
              </header>
              {dispositivos.length === 0 && (
                <p className="vacio">
                  <Icono nombre="historial" />
                  No hay equipos asignados a esta cuenta.
                </p>
              )}
              {dispositivos.length > 0 && jornadas.isPending && (
                <p className="vacio">Consultando las jornadas del día…</p>
              )}
              {jornadasDisponibles && filasJornadas.length === 0 && (
                <p className="vacio">
                  <Icono nombre="historial" />
                  Ningún equipo abrió jornada hoy.
                </p>
              )}
              {!jornadas.isPending && !jornadasDisponibles && (
                <p className="vacio">
                  <Icono nombre="historial" />
                  El servidor todavía no entrega las jornadas.
                </p>
              )}
              {jornadasDisponibles && filasJornadas.length > 0 && (
                <div className="tabla-envoltura">
                  <table className="tabla">
                    <thead>
                      <tr>
                        <th>Equipo</th>
                        <th>Inició</th>
                        <th>Finalizó</th>
                        <th className="num">Duración</th>
                        <th>Historial</th>
                      </tr>
                    </thead>
                    <tbody>
                      {filasJornadas.map((fila) => (
                        <tr key={`${fila.unidadId}-${fila.inicioEn}`}>
                          <td>
                            <Link className="enlace-tabla" to={`/unidad/${fila.unidadId}`}>
                              {fila.nombre}
                            </Link>
                            <div className="apagado mono">{fila.identificador}</div>
                          </td>
                          <td>{hora(fila.inicioEn)}</td>
                          <td>{fila.finEn ? hora(fila.finEn) : 'En curso'}</td>
                          <td className="num">{fila.duracionMin != null ? duracion(fila.duracionMin * 60) : GUION}</td>
                          <td>
                            <Link
                              className="enlace-tabla"
                              to={`/historial${consulta({ dispositivo: fila.unidadId, desde: hoy, hasta: hoy })}`}
                            >
                              Ver jornada
                            </Link>
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
                <h2>Salud de la flota</h2>
                <span className="cuenta">{cuentaSalud}</span>
              </header>
              {salud.isPending && <p className="vacio">Consultando la salud de los equipos…</p>}
              {salud.error && esNoEncontrado(salud.error) && (
                <p className="vacio">
                  <Icono nombre="sistema" />
                  La salud de los equipos todavía no está disponible.
                </p>
              )}
              {salud.error && !esNoEncontrado(salud.error) && (
                <p className="vacio">
                  <Icono nombre="sistema" />
                  {mensajeError(salud.error)}
                </p>
              )}
              {salud.data && equiposSalud.length === 0 && (
                <p className="vacio">
                  <Icono nombre="sistema" />
                  Sin equipos para mostrar.
                </p>
              )}
              {salud.data && equiposSalud.length > 0 && (
                <ul className="lista-avisos">
                  {equiposSalud.map((equipo) => {
                    const conocido = nombresSalud.get(equipo.dispositivoId);
                    const nombre = conocido?.nombre ?? `Equipo ${equipo.dispositivoId}`;
                    const identificador = conocido?.identificadorUnico ?? String(equipo.dispositivoId);
                    // La causa del servidor ya suele traer la edad ("Último
                    // GPS hace 8 min", ADR-009); solo se compone desde
                    // lastFixAgeS cuando viene vacía.
                    const causa =
                      equipo.causa ||
                      (                      equipo.lastFixAgeS != null
                        ? `Última posición ${haceSegundos(equipo.lastFixAgeS)}`
                        : 'Sin causa informada');
                    return (
                      <li key={equipo.dispositivoId}>
                        <span className={`chip ${CLASE_SALUD[equipo.estado]}`}>{ETIQUETA_SALUD[equipo.estado]}</span>
                        <span>
                          <strong>{nombre}</strong>{' '}
                          <span className="mono apagado">{identificador}</span>
                        </span>
                        <span className="motivo">{causa}</span>
                      </li>
                    );
                  })}
                </ul>
              )}
            </div>
          </section>

          <section className="seccion">
            <div className="bloque">
              <header className="cabecera-seccion">
                <h2>Requieren atención</h2>
                <span className="cuenta">{avisos.length === 0 ? 'Nada pendiente' : `${avisos.length} equipos`}</span>
                <span className="acciones">
                  {avisosRestantes > 0 && <span className="cuenta">y {avisosRestantes} más</span>}
                  <Link className="boton boton-suave" to="/en-vivo">
                    Ver en vivo
                  </Link>
                </span>
              </header>
              {avisos.length === 0 ? (
                <p className="vacio">
                  <Icono nombre="sistema" />
                  Ningún equipo requiere atención: todos reportan conexión y batería suficiente.
                </p>
              ) : (
                <ul className="lista-avisos">
                  {avisosVisibles.map(({ equipo, motivos }) => (
                    <li key={equipo.id}>
                      <Link className="enlace-tabla" to={`/unidad/${equipo.idPublico}`}>
                        {equipo.nombre}
                      </Link>
                      <span className="mono apagado">{equipo.identificadorUnico}</span>
                      <span className="motivo">{motivos.join(' · ')}</span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </section>
        </>
      )}
    </section>
  );
}
