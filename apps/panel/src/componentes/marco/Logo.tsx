import { cn } from '@/lib/cn';

// Logotipo oficial de DMujeres Tracking (el mismo de la app Android, tomado de
// sus recursos). `claro` usa la versión con "DMujeres" en blanco para fondos
// marino; `simbolo` es la "D" del logotipo sobre el magenta del ícono.
export function Logo({
  claro = false,
  simbolo = false,
  className,
}: {
  claro?: boolean;
  simbolo?: boolean;
  className?: string;
}) {
  if (simbolo) {
    return <img src="/marca/icono-192.png" alt="DMujeres Tracking" className={cn('size-8 rounded-lg', className)} />;
  }
  return (
    <img
      src={claro ? '/marca/dmujeres-tracking-blanco.png' : '/marca/dmujeres-tracking.png'}
      alt="DMujeres Tracking"
      className={cn('h-9 w-auto', className)}
      draggable={false}
    />
  );
}
