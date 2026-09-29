import { cn } from '@/lib/cn';
import { bateria } from '@/dominio/formatoBase';

// Barra pequeña de batería con su porcentaje; ámbar bajo 30 % y roja bajo 15 %.
export function BateriaBarra({ pct }: { pct: number | null }) {
  if (pct == null || !Number.isFinite(pct)) return <span className="text-texto-3">—</span>;
  const valor = Math.max(0, Math.min(100, pct));
  return (
    <span className="inline-flex items-center gap-2">
      <span className="h-1.5 w-12 overflow-hidden rounded-full bg-marino-100" aria-hidden="true">
        <span
          className={cn(
            'block h-full rounded-full',
            valor <= 15 ? 'bg-peligro' : valor <= 30 ? 'bg-sin-senal' : 'bg-movimiento',
          )}
          style={{ width: `${valor}%` }}
        />
      </span>
      <span className="w-9 text-right text-[12.5px] cifras">{bateria(pct)}</span>
    </span>
  );
}
