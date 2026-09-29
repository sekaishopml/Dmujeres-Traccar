// Encabezado de página del dash: contexto (grupo del menú), título y línea de
// detalle, con las acciones a la derecha. Centralizarlo evita que cada página
// invente su propia jerarquía y mantiene el mismo escalón tipográfico.
import type { ReactNode } from 'react';

interface Props {
  titulo: string;
  contexto?: string;
  sub?: ReactNode;
  acciones?: ReactNode;
}

export default function EncabezadoPagina({ titulo, contexto, sub, acciones }: Props) {
  return (
    <header className="cabecera-pagina">
      <div>
        {contexto && <span className="contexto">{contexto}</span>}
        <h1>{titulo}</h1>
        {sub != null && sub !== '' && <p className="sub">{sub}</p>}
      </div>
      {acciones != null && <div className="cabecera-acciones">{acciones}</div>}
    </header>
  );
}
