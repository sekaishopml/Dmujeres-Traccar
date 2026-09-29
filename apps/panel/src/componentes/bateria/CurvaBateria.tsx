import { useMemo } from 'react';
import { Line } from 'react-chartjs-2';
import { CategoryScale, Chart, Filler, LineElement, LinearScale, PointElement, Tooltip } from 'chart.js';
import type { ChartData, ChartOptions } from 'chart.js';
import type { MuestraBateria } from '@contratos';
import { fechaHora, hora } from '@/dominio/formatoBase';
import { cn } from '@/lib/cn';

Chart.register(CategoryScale, LinearScale, LineElement, PointElement, Tooltip, Filler);

const MARINO = '#17365d';
const CARGA = '#16a34a';

// Curva de batería en marino; los tramos con el cargador conectado se pintan
// en verde (movimiento) para que se vea cuándo cargó el teléfono.
export function CurvaBateria({
  muestras,
  etiqueta = 'fechaHora',
  className,
}: {
  muestras: MuestraBateria[];
  etiqueta?: 'hora' | 'fechaHora';
  className?: string;
}) {
  const conValor = useMemo(() => muestras.filter((m) => m.bateriaPct != null), [muestras]);
  const hayCarga = conValor.some((m) => m.cargando);

  const datos: ChartData<'line'> = useMemo(
    () => ({
      labels: conValor.map((m) => (etiqueta === 'hora' ? hora(m.registradoEn) : fechaHora(m.registradoEn))),
      datasets: [
        {
          label: 'Batería',
          data: conValor.map((m) => m.bateriaPct),
          borderColor: MARINO,
          backgroundColor: 'rgba(23, 54, 93, 0.08)',
          fill: true,
          tension: 0.25,
          pointBackgroundColor: conValor.map((m) => (m.cargando ? CARGA : MARINO)),
          pointBorderColor: conValor.map((m) => (m.cargando ? CARGA : MARINO)),
          segment: {
            borderColor: (ctx) => (conValor[ctx.p1DataIndex]?.cargando ? CARGA : MARINO),
          },
        },
      ],
    }),
    [conValor, etiqueta],
  );

  const opciones: ChartOptions<'line'> = useMemo(
    () => ({
      responsive: true,
      maintainAspectRatio: false,
      interaction: { mode: 'index', intersect: false },
      scales: {
        y: {
          min: 0,
          max: 100,
          ticks: { callback: (v) => `${v}%`, font: { size: 11 } },
          grid: { color: '#eef1f6' },
          border: { color: '#e1e7ef' },
        },
        x: {
          ticks: { maxTicksLimit: 8, autoSkip: true, font: { size: 11 } },
          grid: { display: false },
          border: { color: '#e1e7ef' },
        },
      },
      plugins: {
        legend: { display: false },
        tooltip: {
          callbacks: {
            label: (item) => ` ${item.parsed.y}%${conValor[item.dataIndex]?.cargando ? ' · cargando' : ''}`,
          },
        },
      },
      elements: { line: { borderWidth: 2 }, point: { radius: 2, hitRadius: 8 } },
    }),
    [conValor],
  );

  return (
    <div className={className}>
      <div className={cn('h-64 w-full')}>
        <Line data={datos} options={opciones} />
      </div>
      {hayCarga && (
        <p className="mt-2 flex items-center gap-2 text-[11.5px] text-texto-2">
          <span className="h-0.5 w-5 rounded bg-movimiento" /> Tramos con el cargador conectado
        </p>
      )}
    </div>
  );
}
