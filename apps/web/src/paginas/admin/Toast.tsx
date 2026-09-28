// Confirmación flotante de éxito: se cierra sola a los pocos segundos y puede
// descartarse a mano. El cierre lo controla la página que la muestra.
import { useEffect } from 'react';

interface PropsToast {
  mensaje: string;
  onCerrar: () => void;
}

export function Toast({ mensaje, onCerrar }: PropsToast) {
  useEffect(() => {
    const temporizador = setTimeout(onCerrar, 4000);
    return () => clearTimeout(temporizador);
  }, [mensaje, onCerrar]);

  return (
    <div className="toast" role="status" aria-live="polite">
      <span>{mensaje}</span>
      <button type="button" className="suave" onClick={onCerrar}>
        Cerrar
      </button>
    </div>
  );
}
