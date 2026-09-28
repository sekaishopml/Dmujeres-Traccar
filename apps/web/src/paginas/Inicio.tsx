import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import type { Dispositivo, Jornada, RespuestaJornadas } from '@contratos';
import { api, consulta } from '../api/cliente';
import { bateria, duracion, hace, hora, GUION } from '../util/formato';
import Icono from '../componentes/Icono';
import { traerFlota, CLAVE_FLOTA } from './operacion/datos';
import { mensajeError } from './operacion/errores';
import { claveEstado } from './operacion/estado';
import { finDeDia, fechaHoyLocal, inicioDeDia } from './operacion/rango';
import '../estilos/paginas.css';

const REFRESCO_MS = 15_000;
// Las jornadas se consultan unidad por unidad (no hay vista agregada en la
// API): el sondeo va más espaciado que el de flota para no multiplicar
// peticiones cada 15 s, y se detiene con la pestaña oculta.
const REFRESCO_JORNADAS_MS = 60_000;
const LOTE_CONSULTAS = 12;
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

// Arma la cola del día con GET /api/v1/fleet/{id}/journeys por unidad visible.
// El fallo de una consulta (404 de un equipo, endpoint todavía no desplegado,
// caída puntual) se lee como "sin filas" y no tumba la tabla; `disponible`
// queda en true si al menos una unidad respondió, para distinguir una flota
// sin jornadas de un servidor que aún no publica el dato.
async function jornadasDeHoy(
  dispositivos: Dispositivo[],
  desde: string,
  hasta: string,
): Promise<{ filas: FilaJornada[]; disponible: boolean }> {
  const filas: FilaJornada[] = [];
  let disponible = false;
  for (let inicio = 0; inicio < dispositivos.length; inicio += LOTE_CONSULTAS) {
    const lote = dispositivos.slice(inicio, inicio + LOTE_CONSULTAS);
    const respuestas = await Promise.all(
      lote.map(async (equipo) => {
        try {
          const respuesta = await api.get<RespuestaJornadas>(
            `/api/v1/fleet/${encodeURIComponent(equipo.idPublico)}/journeys${consulta({ desde, hasta })}`,
          );
          return { equipo, jornadas: respuesta.jornadas, ok: true };
        } catch {
          return { equipo, jornadas: [] as Jornada[], ok: false };
        }
      }),
    );
    for (const { equipo, jornadas, ok } of respuestas) {
      if (ok) disponible = true;
      for (const jornada of jornadas) {
        filas.push({
          unidadId: equipo.idPublico,
          nombre: equipo.nombre,
          identificador: equipo.identificadorUnico,
          inicioEn: jornada.inicioEn,
          finEn: jornada.finEn,
          duracionMin: jornada.duracionMin,
        });
      }
    }
  }
  // Cola de auditoría: la jornada más reciente primero.
  filas.sort((a, b) => new Date(b.inicioEn).getTime() - new Date(a.inicioEn).getTime());
  return { filas, disponible };
}

export default function Inicio() {
  const flota = useQuery({
    queryKey: CLAVE_FLOTA,
    queryFn: traerFlota,
    refetchInterval: REFRESCO_MS,
  });

  const dispositivos = useMemo(() => flota.data?.datos ?? [], [flota.data]);

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
  const jornadas = useQuery({
    queryKey: ['inicio', 'jornadas', 'hoy', hoy, dispositivos.length],
    enabled: flota.isSuccess && dispositivos.length > 0,
    queryFn: () => jornadasDeHoy(dispositivos, inicioDeDia(hoy), finDeDia(hoy)),
    refetchInterval: () => (document.hidden ? false : REFRESCO_JORNADAS_MS),
  });

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
            equipo.ultimaConexion ? `Sin reportar desde las ${hora(equipo.ultimaConexion)}` : 'Sin conexión registrada',
          );
        }
      }
      if (equipo.bateriaPct != null && equipo.bateriaPct <= 20) {
        rango = Math.min(rango, 1);
        motivos.push(`Batería al ${bateria(equipo.bateriaPct)}`);
      }
      if (clave === 'deshabilitado') {
        rango = Math.min(rango, 2);
        motivos.push(equipo.habilitado ? 'Jornada cerrada' : 'Deshabilitado en la cuenta');
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

  const datosJornadas = jornadas.data;
  const unidadesConJornada = datosJornadas
    ? new Set(datosJornadas.filas.map((fila) => fila.unidadId)).size
    : 0;
  const cuentaJornadas = jornadas.isPending
    ? 'Consultando…'
    : datosJornadas && datosJornadas.disponible
      ? `${datosJornadas.filas.length} jornadas · ${unidadesConJornada} unidades`
      : 'Sin datos del servidor';

  return (
    <section className="pagina-inicio">
      <header className="cabecera-pagina">
        <div>
          <h1>Inicio</h1>
          <p className="sub">
            Cola de auditoría del día
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
                <div className="etiqueta">Total</div>
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
                  No hay unidades visibles para esta cuenta.
                </p>
              )}
              {dispositivos.length > 0 && jornadas.isPending && (
                <p className="vacio">Consultando las jornadas del día…</p>
              )}
              {datosJornadas && !datosJornadas.disponible && (
                <p className="vacio">
                  <Icono nombre="historial" />
                  Las jornadas todavía no están disponibles en el servidor.
                </p>
              )}
              {datosJornadas && datosJornadas.disponible && datosJornadas.filas.length === 0 && (
                <p className="vacio">
                  <Icono nombre="historial" />
                  Ninguna unidad abrió jornada hoy.
                </p>
              )}
              {datosJornadas && datosJornadas.disponible && datosJornadas.filas.length > 0 && (
                <div className="tabla-envoltura">
                  <table className="tabla">
                    <thead>
                      <tr>
                        <th>Unidad</th>
                        <th>Inició</th>
                        <th>Finalizó</th>
                        <th className="num">Duración</th>
                        <th>Auditoría</th>
                      </tr>
                    </thead>
                    <tbody>
                      {datosJornadas.filas.map((fila) => (
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
                              Auditar
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
                <h2>Requieren atención</h2>
                <span className="cuenta">{avisos.length === 0 ? 'Nada pendiente' : `${avisos.length} unidades`}</span>
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
                  Ninguna unidad requiere atención: todas reportan conexión y batería suficiente.
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
