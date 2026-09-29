// Encabezado de página: título y, si aporta, un dato corto (hora de la última
// lectura, identificador). El grupo del menú ya está en la miga de la barra
// superior, así que `contexto` se acepta pero no se repite en pantalla.
import type { ReactNode } from 'react';

interface Props {
  titulo: string;
  contexto?: string;
  sub?: ReactNode;
  acciones?: ReactNode;
}

export default function EncabezadoPagina({ titulo, sub, acciones }: Props) {
  return (
    <header className="cabecera-pagina">
      <div>
        <h1>{titulo}</h1>
        {sub != null && sub !== '' && <p className="sub">{sub}</p>}
      </div>
      {acciones != null && <div className="cabecera-acciones">{acciones}</div>}
    </header>
  );
}
