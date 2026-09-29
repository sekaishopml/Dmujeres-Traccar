// Nota de producto: candidato a fusionarse más adelante en la ficha de la
// unidad (Detalle); se mantiene página aparte mientras sirva para auditar el
// consumo de toda la flota de una vez.
import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Line } from 'react-chartjs-2';
import type { ChartData, ChartOptions } from 'chart.js';
import type { Bateria as SerieBateria, MuestraBateria } from '@contratos';
import { api, consulta } from '../api/cliente';
import { GUION, bateria as pctTexto, fechaHora, hace } from '../util/formato';
import {
  BarraBateria,
  ChipEstado,
  MensajeError,
  TextoCarga,
  useEquipos,
} from './admin/comunes';
import './admin/chart';
import './admin.css';
import '../estilos/paginas.css';

// Marca de tiempo local en el formato que espera <input type="datetime-local">.
function aTextoLocal(fecha: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${fecha.getFullYear()}-${pad(fecha.getMonth() + 1)}-${pad(fecha.getDate())}T${pad(fecha.getHours())}:${pad(fecha.getMinutes())}`;
}

function rangoDeHoras(horas: number) {
  const hasta = new Date();
  const desde = new Date(hasta.getTime() - horas * 3_600_000);
  return { desde: aTextoLocal(desde), hasta: aTextoLocal(hasta) };
}

// La API quiere ISO-8601 con zona; el rango llega en hora local sin zona.
function aIso(valor: string): string | undefined {
  if (!valor) return undefined;
  const fecha = new Date(valor);
  return Number.isNaN(fecha.getTime()) ? undefined : fecha.toISOString();
}

interface Tendencia {
  direccion: 'sube' | 'baja' | 'estable';
  tasaPctHora: number;
}

// Cambio menor a 0,5 %/h se considera estable: por debajo de eso el ruido de
// las muestras pesa más que la tendencia real. Se comparan la primera y la
// última muestra con porcentaje del rango, no puntos sueltos.
function calcularTendencia(muestras: MuestraBateria[]): Tendencia | null {
  const conValor = muestras.filter((muestra) => muestra.bateriaPct != null);
  if (conValor.length < 2) return null;
  const primera = conValor[0];
  const ultima = conValor[conValor.length - 1];
  const horas =
    (new Date(ultima.registradoEn).getTime() - new Date(primera.registradoEn).getTime()) / 3_600_000;
  if (!Number.isFinite(horas) || horas <= 0) return null;
  const tasaPctHora = (ultima.bateriaPct! - primera.bateriaPct!) / horas;
  if (Math.abs(tasaPctHora) < 0.5) return { direccion: 'estable', tasaPctHora };
  return { direccion: tasaPctHora > 0 ? 'sube' : 'baja', tasaPctHora };
}

export default function Bateria() {
  const equipos = useEquipos();
  const [seleccionManual, setSeleccionManual] = useState('');
  const [rango, setRango] = useState(() => rangoDeHoras(24));

  // Orden ascendente por batería: lo primero que ve la operadora es el equipo
  // con menos carga. Los equipos sin lectura (null) quedan al final, nunca se
  // interpretan como 0.
  const flota = useMemo(() => {
    const datos = equipos.data?.datos ?? [];
    return [...datos].sort((a, b) => {
      if (a.bateriaPct == null && b.bateriaPct == null) return a.nombre.localeCompare(b.nombre, 'es');
      if (a.bateriaPct == null) return 1;
      if (b.bateriaPct == null) return -1;
      return a.bateriaPct - b.bateriaPct;
    });
  }, [equipos.data]);

  // La selección efectiva se deriva: manda la elección de la operadora y,
  // mientras no exista, el equipo más crítico (el primero de la tabla). Así no
  // hay que sincronizar estado con un efecto.
  const seleccion = seleccionManual || flota[0]?.idPublico || '';

  const serie = useQuery({
    queryKey: ['bateria', seleccion, rango.desde, rango.hasta],
    enabled: seleccion !== '',
    queryFn: () =>
      api.get<SerieBateria>(
        `/api/v1/battery/${seleccion}${consulta({ desde: aIso(rango.desde), hasta: aIso(rango.hasta) })}`,
      ),
  });

  // La lista se memoiza porque la tendencia depende de ella: si no, cada
  // render del componente invalidaría el useMemo de la tendencia.
  const muestras = useMemo(() => serie.data?.muestras ?? [], [serie.data]);
  // Solo los puntos con porcentaje entran en la mínima y en el gráfico.
  const valores = muestras.flatMap((m) => (m.bateriaPct == null ? [] : [m.bateriaPct]));
  const minima = valores.length > 0 ? Math.min(...valores) : null;
  const tendencia = useMemo(() => calcularTendencia(muestras), [muestras]);
  const textoTendencia = !tendencia
    ? GUION
    : tendencia.direccion === 'estable'
      ? 'Estable'
      : `${tendencia.direccion === 'sube' ? 'Sube' : 'Baja'} ${Math.abs(tendencia.tasaPctHora).toFixed(1)} %/h`;

  const datosGrafico: ChartData<'line'> = {
    labels: muestras.map((m) => fechaHora(m.registradoEn)),
    datasets: [
      {
        label: 'Batería',
        data: muestras.map((m) => m.bateriaPct),
        borderColor: '#0b6fa4',
        backgroundColor: 'rgba(11, 111, 164, .1)',
        tension: 0.25,
      },
    ],
  };

  const opcionesGrafico: ChartOptions<'line'> = {
    responsive: true,
    maintainAspectRatio: false,
    interaction: { mode: 'index', intersect: false },
    scales: {
      y: {
        min: 0,
        max: 100,
        ticks: { callback: (valor) => `${valor}%`, font: { size: 11 } },
        grid: { color: '#eef1f6' },
        border: { color: '#e2e7ee' },
      },
      x: {
        ticks: { maxTicksLimit: 10, autoSkip: true, font: { size: 11 } },
        grid: { display: false },
        border: { color: '#e2e7ee' },
      },
    },
    plugins: {
      legend: { display: false },
      tooltip: { callbacks: { label: (item) => ` ${item.parsed.y}%` } },
    },
    elements: {
      line: { borderWidth: 2 },
      point: { radius: 2, hitRadius: 8 },
    },
  };

  const equipoSeleccionado = flota.find((d) => d.idPublico === seleccion) ?? null;

  return (
    <section>
      <header className="cabecera-pagina">
        <div>
          <h1>Batería</h1>
          <p className="sub">Consumo de batería: nivel de la flota y detalle por equipo en el rango elegido.</p>
        </div>
      </header>

      <section className="seccion">
        <div className="bloque">
          <header className="cabecera-seccion">
            <h2>Estado de la flota</h2>
            <span className="cuenta">{flota.length} equipos · ordenados de menor a mayor carga</span>
          </header>
          {equipos.isPending && <p className="vacio pulso">Cargando flota…</p>}
          {equipos.error && <MensajeError error={equipos.error} />}
          {equipos.data && (
            <>
              <div className="tabla-envoltura">
                <table className="tabla">
                  <thead>
                    <tr>
                      <th>Equipo</th>
                      <th>Identificador</th>
                      <th>Estado</th>
                      <th>Batería</th>
                      <th>Cargando</th>
                      <th>Última conexión</th>
                    </tr>
                  </thead>
                  <tbody>
                    {flota.map((d) => (
                      <tr key={d.idPublico} className={d.idPublico === seleccion ? 'seleccionada' : ''}>
                        <td>
                          <button type="button" className="enlace-fila" onClick={() => setSeleccionManual(d.idPublico)}>
                            {d.nombre}
                          </button>
                        </td>
                        <td className="mono">{d.identificadorUnico}</td>
                        <td>
                          <ChipEstado estado={d.estado} />
                        </td>
                        <td>
                          <BarraBateria pct={d.bateriaPct} />
                        </td>
                        <td>
                          <TextoCarga cargando={d.cargando} />
                        </td>
                        <td title={d.ultimaConexion ?? undefined}>{hace(d.ultimaConexion)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {flota.length === 0 && <p className="vacio">No hay equipos en la flota.</p>}
            </>
          )}
        </div>
      </section>

      <section className="seccion">
        <div className="bloque">
          <header className="cabecera-seccion">
            <h2>{equipoSeleccionado ? equipoSeleccionado.nombre : 'Historial de batería'}</h2>
            <span className="cuenta mono">{equipoSeleccionado ? equipoSeleccionado.identificadorUnico : GUION}</span>
            <span className="acciones">
              <button type="button" className="suave" onClick={() => setRango(rangoDeHoras(24))}>
                Últimas 24 h
              </button>
              <button type="button" className="suave" onClick={() => setRango(rangoDeHoras(24 * 7))}>
                7 días
              </button>
              <button type="button" className="suave" onClick={() => setRango(rangoDeHoras(24 * 30))}>
                30 días
              </button>
            </span>
          </header>
          <div className="tira-datos">
            <div className="dato">
              <div className="valor">{pctTexto(serie.data?.actual)}</div>
              <div className="etiqueta">Actual</div>
            </div>
            <div className="dato">
              <div className="valor">{pctTexto(minima)}</div>
              <div className="etiqueta">Mínima del rango</div>
            </div>
            <div className="dato">
              <div className="valor">{serie.data ? muestras.length : GUION}</div>
              <div className="etiqueta">Lecturas</div>
            </div>
            <div className="dato">
              <div className="valor">{textoTendencia}</div>
              <div className="etiqueta">Tendencia</div>
            </div>
          </div>

          {serie.isPending && seleccion !== '' && <p className="vacio pulso">Cargando historial…</p>}
          {serie.error && <MensajeError error={serie.error} />}
          {serie.data && muestras.length === 0 && <p className="vacio">No hay lecturas de batería en el rango.</p>}
          {serie.data && muestras.length > 0 && valores.length === 0 && (
            <p className="vacio">Las lecturas del rango no traen porcentaje de batería.</p>
          )}
          {serie.data && valores.length > 0 && (
            <div className="grafico bloque-sep">
              <Line data={datosGrafico} options={opcionesGrafico} />
            </div>
          )}
        </div>
      </section>
    </section>
  );
}
