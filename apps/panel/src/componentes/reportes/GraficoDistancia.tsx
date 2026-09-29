import { BarElement, CategoryScale, Chart as ChartJS, LinearScale, Tooltip } from 'chart.js';
import { Bar } from 'react-chartjs-2';
import type { ResumenDispositivo } from '@contratos';

ChartJS.register(BarElement, CategoryScale, LinearScale, Tooltip);

const MARINO = '#2c5f99';
const MAGENTA = '#eb0045';
const MAX_BARRAS = 12;

// Barras horizontales por persona; la seleccionada (o la de mayor distancia)
// va en magenta y el resto en marino.
export default function GraficoDistancia({
  filas,
  resaltarId,
}: {
  filas: ResumenDispositivo[];
  resaltarId?: string;
}) {
  const ordenadas = [...filas].sort((a, b) => b.distanciaKm - a.distanciaKm).slice(0, MAX_BARRAS);
  const idResaltado = resaltarId || ordenadas[0]?.idPublico;
  return (
    <div style={{ height: Math.max(120, ordenadas.length * 34 + 30) }}>
      <Bar
        data={{
          labels: ordenadas.map((f) => f.nombre),
          datasets: [
            {
              label: 'Distancia',
              data: ordenadas.map((f) => Number(f.distanciaKm.toFixed(2))),
              backgroundColor: ordenadas.map((f) => (f.idPublico === idResaltado ? MAGENTA : MARINO)),
              borderRadius: 4,
              maxBarThickness: 22,
            },
          ],
        }}
        options={{
          indexAxis: 'y',
          responsive: true,
          maintainAspectRatio: false,
          plugins: {
            legend: { display: false },
            tooltip: { callbacks: { label: (c) => ` ${Number(c.parsed.x ?? 0).toLocaleString('es-EC')} km` } },
          },
          scales: {
            x: {
              beginAtZero: true,
              grid: { display: false },
              border: { display: false },
              ticks: { callback: (v) => `${v} km`, color: '#64748b', font: { size: 11 } },
            },
            y: { grid: { display: false }, border: { display: false }, ticks: { color: '#0c1f3d', font: { size: 12 } } },
          },
        }}
      />
    </div>
  );
}
