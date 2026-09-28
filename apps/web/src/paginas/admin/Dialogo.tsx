// Diálogo modal compartido por las páginas de gestión. Usa el elemento nativo
// <dialog>: showModal() aporta foco atrapado, fondo inerte y cierre con Escape
// sin dependencias externas.
import { useEffect, useId, useRef } from 'react';
import type { MouseEvent, ReactNode } from 'react';
import Icono from '../../componentes/Icono';

interface PropsDialogo {
  titulo: string;
  onCerrar: () => void;
  children: ReactNode;
  pie?: ReactNode;
}

export function Dialogo({ titulo, onCerrar, children, pie }: PropsDialogo) {
  const referencia = useRef<HTMLDialogElement>(null);
  const idTitulo = useId();

  useEffect(() => {
    const dialogo = referencia.current;
    if (dialogo && !dialogo.open) dialogo.showModal();
  }, []);

  function alPulsarFondo(evento: MouseEvent<HTMLDialogElement>) {
    // El click sobre ::backdrop llega al propio <dialog>.
    if (evento.target === referencia.current) onCerrar();
  }

  return (
    <dialog
      ref={referencia}
      className="dialogo"
      aria-labelledby={idTitulo}
      onCancel={(evento) => {
        evento.preventDefault();
        onCerrar();
      }}
      onClose={onCerrar}
      onClick={alPulsarFondo}
    >
      <div className="dialogo-caja">
        <div className="dialogo-cab">
          <h2 id={idTitulo}>{titulo}</h2>
          <span className="empuja" />
          <button type="button" className="suave icono-solo" onClick={onCerrar} aria-label="Cerrar">
            <Icono nombre="cerrar" />
          </button>
        </div>
        {children}
        {pie && <footer className="dialogo-pie">{pie}</footer>}
      </div>
    </dialog>
  );
}
