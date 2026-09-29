// Estado vacío del dash: una línea con icono y el motivo, dentro del flujo de
// la sección. Sustituye a las cajas grandes y huecas: si no hay datos, se dice
// por qué y se sigue.
import type { ReactNode } from 'react';
import Icono from './Icono';
import type { NombreIcono } from './Icono';

interface Props {
  icono?: NombreIcono;
  children: ReactNode;
}

export default function EstadoVacio({ icono, children }: Props) {
  return (
    <p className="nota-vacia">
      {icono && <Icono nombre={icono} tamano={16} />}
      <span>{children}</span>
    </p>
  );
}
