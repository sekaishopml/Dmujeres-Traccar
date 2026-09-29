// Cabecera de sección: título, cuenta y acciones sobre una regla con segmento
// navy. Es la pieza que separa secciones sin meter cada una en una tarjeta.
import type { ReactNode } from 'react';

interface Props {
  titulo: string;
  cuenta?: ReactNode;
  acciones?: ReactNode;
}

export default function CabeceraSeccion({ titulo, cuenta, acciones }: Props) {
  return (
    <header className="cabecera-seccion">
      <h2>{titulo}</h2>
      {cuenta != null && cuenta !== '' && <span className="cuenta">{cuenta}</span>}
      {acciones != null && <span className="acciones">{acciones}</span>}
    </header>
  );
}
