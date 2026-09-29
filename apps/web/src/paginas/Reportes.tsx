import { useMemo, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import type { Pagina, ReporteParada, ReporteViaje, ResumenReporte } from '@contratos';
import { api, consulta } from '../api/cliente';
import { GUION, duracion, fechaHora, kilometros, velocidad } from '../util/formato';
import EncabezadoPagina from '../componentes/EncabezadoPagina';
import CabeceraSeccion from '../componentes/CabeceraSeccion';
import EstadoVacio from '../componentes/EstadoVacio';
import { MensajeError, Paginacion, SelectorEquipo, useEquipos } from './admin/comunes';
import { CACHE_AUDITORIA_MS } from './operacion/datos';
import './admin.css';
import '../estilos/paginas.css';

// Tamaño de página: el valor por defecto del contrato para no pedir de más.
const TAMANO = 25;

type Pestana = 'viajes' | 'paradas' | 'resumen';

function aFechaLocal(fecha: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${fecha.getFullYear()}-${pad(fecha.getMonth() + 1)}-${pad(fecha.getDate())}`;
}

function rangoPorDefecto() {
  const hoy = new Date();
  const desde = new Date(hoy);
  desde.setDate(hoy.getDate() - 7);
  return { desde: aFechaLocal(desde), hasta: aFechaLocal(hoy) };
}

function inicioDelDia(valor: string): string | undefined {
  if (!valor) return undefined;
  const fecha = new Date(`${valor}T00:00:00`);
  return Number.isNaN(fecha.getTime()) ? undefined : fecha.toISOString();
}

function finDelDia(valor: string): string | undefined {
  if (!valor) return undefined;
  const fecha = new Date(`${valor}T23:59:59.999`);
  return Number.isNaN(fecha.getTime()) ? undefined : fecha.toISOString();
}

export default function Reportes() {
  const equipos = useEquipos();
  const [equipo, setEquipo] = useState('');
  const [pestana, setPestana] = useState<Pestana>('viajes');
  const [rango, setRango] = useState(rangoPorDefecto);
  const [paginaViajes, setPaginaViajes] = useState(1);
  const [paginaParadas, setPaginaParadas] = useState(1);

  const filtros = {
    dispositivoId: equipo || undefined,
    desde: inicioDelDia(rango.desde),
    hasta: finDelDia(rango.hasta),
  };

  // Cada pestaña consulta al abrirse por primera vez; react-query conserva la
  // página anterior mientras llega la nueva (keepPreviousData) para evitar
  // que la tabla parpadee al paginar.
  const viajes = useQuery({
    queryKey: ['reportes', 'viajes', filtros, paginaViajes],
    enabled: pestana === 'viajes',
    placeholderData: keepPreviousData,
    staleTime: CACHE_AUDITORIA_MS,
    queryFn: () =>
      api.get<Pagina<ReporteViaje>>(
        `/api/v1/reports/trips${consulta({ ...filtros, pagina: paginaViajes, tamano: TAMANO, orden: '-inicio' })}`,
      ),
  });

  const paradas = useQuery({
    queryKey: ['reportes', 'paradas', filtros, paginaParadas],
    enabled: pestana === 'paradas',
    placeholderData: keepPreviousData,
    staleTime: CACHE_AUDITORIA_MS,
    queryFn: () =>
      api.get<Pagina<ReporteParada>>(
        `/api/v1/reports/stops${consulta({ ...filtros, pagina: paginaParadas, tamano: TAMANO, orden: '-inicio' })}`,
      ),
  });

  // Alimenta el conteo de la pestaña Resumen; keepPreviousData evita que la
  // tabla parpadee al cambiar el rango.
  const resumen = useQuery({
    queryKey: ['reportes', 'resumen', filtros],
    placeholderData: keepPreviousData,
    staleTime: CACHE_AUDITORIA_MS,
    queryFn: () => api.get<ResumenReporte>(`/api/v1/reports/summary${consulta(filtros)}`),
  });

  // Los reportes traen el id legado del dispositivo; la flota lo traduce a
  // nombre. Si el equipo no está en la lista se muestra "—", sin inventarlo.
  const nombres = useMemo(() => {
    const mapa = new Map<number, string>();
    for (const d of equipos.data?.datos ?? []) mapa.set(d.id, d.nombre);
    return mapa;
  }, [equipos.data]);

  function cambiarEquipo(valor: string) {
    setEquipo(valor);
    setPaginaViajes(1);
    setPaginaParadas(1);
  }

  function cambiarRango(parcial: { desde?: string; hasta?: string }) {
    setRango((actual) => ({ ...actual, ...parcial }));
    setPaginaViajes(1);
    setPaginaParadas(1);
  }

  return (
    <section>
      <EncabezadoPagina
        contexto="Operación"
        titulo="Reportes"
      />

      <div className="barra-herramientas">
        <SelectorEquipo
          equipos={equipos.data?.datos ?? []}
          valor={equipo}
          onCambio={cambiarEquipo}
          etiqueta="Equipo"
          incluirTodos
        />
        <label className="campo">
          <span>Desde</span>
          <input type="date" value={rango.desde} onChange={(e) => cambiarRango({ desde: e.target.value })} />
        </label>
        <label className="campo">
          <span>Hasta</span>
          <input type="date" value={rango.hasta} onChange={(e) => cambiarRango({ hasta: e.target.value })} />
        </label>
      </div>
      {equipos.error && <MensajeError error={equipos.error} />}
      <p className="rango-efectivo">
        {resumen.data
          ? `Rango consultado: ${fechaHora(resumen.data.desde)} — ${fechaHora(resumen.data.hasta)}`
          : resumen.error
            ? 'No se pudo calcular el resumen del rango.'
            : 'Consultando el rango seleccionado…'}
      </p>

      <div className="pestanas" role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={pestana === 'viajes'}
          className={pestana === 'viajes' ? 'activa' : ''}
          onClick={() => setPestana('viajes')}
        >
          Viajes
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={pestana === 'paradas'}
          className={pestana === 'paradas' ? 'activa' : ''}
          onClick={() => setPestana('paradas')}
        >
          Paradas
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={pestana === 'resumen'}
          className={pestana === 'resumen' ? 'activa' : ''}
          onClick={() => setPestana('resumen')}
        >
          Resumen
        </button>
      </div>

      {pestana === 'viajes' && (
        <section className="seccion">
          <CabeceraSeccion
            titulo="Viajes"
            cuenta={viajes.data ? `${viajes.data.total} registros` : 'Consultando…'}
          />
          {viajes.isPending && <p className="vacio pulso">Cargando viajes…</p>}
          {viajes.error && <MensajeError error={viajes.error} />}
          {viajes.data && viajes.data.datos.length === 0 && (
            <EstadoVacio icono="reportes">No hay viajes en el rango.</EstadoVacio>
          )}
          {viajes.data && viajes.data.datos.length > 0 && (
            <>
              <div className="tabla-envoltura">
                <table className="tabla">
                  <thead>
                    <tr>
                      <th>Equipo</th>
                      <th>Inició</th>
                      <th>Finalizó</th>
                      <th className="num">Duración</th>
                      <th className="num">Distancia</th>
                      <th className="num">Velocidad promedio</th>
                      <th className="num">Velocidad máxima</th>
                      <th className="num">Paradas</th>
                    </tr>
                  </thead>
                  <tbody>
                    {viajes.data.datos.map((v) => (
                      <tr key={v.id}>
                        <td>{nombres.get(v.dispositivoId) ?? GUION}</td>
                        <td>{fechaHora(v.inicio)}</td>
                        <td>{fechaHora(v.fin)}</td>
                        <td className="num">{duracion(v.duracionMin * 60)}</td>
                        <td className="num">{kilometros(v.distanciaKm * 1000)}</td>
                        <td className="num">{velocidad(v.velocidadPromedioKmh)}</td>
                        <td className="num">{velocidad(v.velocidadMaximaKmh)}</td>
                        <td className="num">{v.paradas}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Paginacion
                pagina={viajes.data.pagina}
                tamano={viajes.data.tamano}
                total={viajes.data.total}
                onPagina={setPaginaViajes}
              />
            </>
          )}
        </section>
      )}

      {pestana === 'paradas' && (
        <section className="seccion">
          <CabeceraSeccion
            titulo="Paradas"
            cuenta={paradas.data ? `${paradas.data.total} registros` : 'Consultando…'}
          />
          {paradas.isPending && <p className="vacio pulso">Cargando paradas…</p>}
          {paradas.error && <MensajeError error={paradas.error} />}
          {paradas.data && paradas.data.datos.length === 0 && (
            <EstadoVacio icono="reportes">No hay paradas en el rango.</EstadoVacio>
          )}
          {paradas.data && paradas.data.datos.length > 0 && (
            <>
              <div className="tabla-envoltura">
                <table className="tabla">
                  <thead>
                    <tr>
                      <th>Equipo</th>
                      <th>Inició</th>
                      <th>Finalizó</th>
                      <th className="num">Duración</th>
                      <th>Dirección</th>
                    </tr>
                  </thead>
                  <tbody>
                    {paradas.data.datos.map((p) => (
                      <tr key={p.id}>
                        <td>{nombres.get(p.dispositivoId) ?? GUION}</td>
                        <td>{fechaHora(p.inicio)}</td>
                        <td>{fechaHora(p.fin)}</td>
                        <td className="num">{duracion(p.duracionMin * 60)}</td>
                        {/* Sin dirección se muestran las coordenadas reales, nunca un texto inventado. */}
                        <td>{p.direccion ?? `${p.latitud.toFixed(5)}, ${p.longitud.toFixed(5)}`}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Paginacion
                pagina={paradas.data.pagina}
                tamano={paradas.data.tamano}
                total={paradas.data.total}
                onPagina={setPaginaParadas}
              />
            </>
          )}
        </section>
      )}

      {pestana === 'resumen' && (
        <section className="seccion">
          <CabeceraSeccion
            titulo="Resumen por equipo"
            cuenta={resumen.data ? `${resumen.data.porDispositivo.length} equipos` : 'Consultando…'}
          />
          {resumen.isPending && <p className="vacio pulso">Cargando resumen…</p>}
          {resumen.error && <MensajeError error={resumen.error} />}
          {resumen.data && (
            <>
              <div className="tabla-envoltura">
                <table className="tabla">
                  <thead>
                    <tr>
                      <th>Equipo</th>
                      <th className="num">Distancia</th>
                      <th className="num">Duración</th>
                      <th className="num">Viajes</th>
                      <th className="num">Paradas</th>
                      <th>Última posición</th>
                    </tr>
                  </thead>
                  <tbody>
                    {resumen.data.porDispositivo.map((d) => (
                      <tr key={d.dispositivoId}>
                        <td>{d.nombre}</td>
                        <td className="num">{kilometros(d.distanciaKm * 1000)}</td>
                        <td className="num">{duracion(d.duracionMin * 60)}</td>
                        <td className="num">{d.viajes}</td>
                        <td className="num">{d.paradas}</td>
                        <td>{fechaHora(d.ultimaPosicionEn)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {resumen.data.porDispositivo.length === 0 && (
                <EstadoVacio icono="reportes">No hay actividad de equipos en el rango.</EstadoVacio>
              )}
            </>
          )}
        </section>
      )}
    </section>
  );
}
