import { cn } from '@/lib/cn';

// Logotipo oficial de DMujeres Tracking (el mismo de la app Android, tomado de
// sus recursos). Sobre fondos claros va "DMujeres" en negro y en modo
// nocturno o sobre marino (`claro`) en blanco; `simbolo` es la "D" sobre el
// magenta del ícono.
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
  if (claro) {
    return <img src="/marca/dmujeres-tracking-blanco.png" alt="DMujeres Tracking" className={cn('h-9 w-auto', className)} draggable={false} />;
  }
  return (
    <>
      <img src="/marca/dmujeres-tracking.png" alt="DMujeres Tracking" className={cn('h-9 w-auto dark:hidden', className)} draggable={false} />
      <img
        src="/marca/dmujeres-tracking-blanco.png"
        alt=""
        aria-hidden="true"
        className={cn('hidden h-9 w-auto dark:block', className)}
        draggable={false}
      />
    </>
  );
}
