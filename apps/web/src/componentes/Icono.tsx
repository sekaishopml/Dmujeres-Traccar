// Iconos propios en SVG (trazo 1.7, 24x24). Sin librerías ni emojis: el panel
// usa un lenguaje visual sobrio y consistente.
import { memo } from 'react';
import type { ReactElement } from 'react';

export type NombreIcono =
  | 'inicio' | 'enVivo' | 'historial' | 'replay' | 'bateria' | 'reportes'
  | 'usuarios' | 'grupos' | 'configuracion' | 'sistema' | 'salir' | 'menu' | 'buscar'
  | 'capas' | 'play' | 'pausa' | 'atras' | 'adelante' | 'chevronIzq' | 'chevronDer' | 'cerrar' | 'editar'
  | 'basura' | 'mas' | 'flecha';

const TRAZOS: Record<NombreIcono, ReactElement> = {
  inicio: <><path d="M4 10.5 12 4l8 6.5" /><path d="M6 9.8V20h12V9.8" /><path d="M10 20v-5h4v5" /></>,
  enVivo: <><path d="M12 21s6.5-5.4 6.5-10.5a6.5 6.5 0 1 0-13 0C5.5 15.6 12 21 12 21Z" /><circle cx="12" cy="10.5" r="2.3" /></>,
  historial: <><circle cx="12" cy="12" r="8" /><path d="M12 7.5V12l3 2" /></>,
  replay: <><circle cx="12" cy="12" r="8" /><path d="M10.2 9.4l4.3 2.6-4.3 2.6z" /></>,
  bateria: <><rect x="3" y="8" width="15" height="8" rx="2" /><path d="M20 11v2" /><path d="M6 11v2M9 11v2M12 11v2" /></>,
  reportes: <><path d="M4 20h16" /><path d="M7 16V9M12 16V5M17 16v-4" /></>,
  usuarios: <><circle cx="9" cy="9" r="3" /><path d="M3.5 19a5.5 5.5 0 0 1 11 0" /><path d="M16 6.5a3 3 0 0 1 0 5.8" /><path d="M17 19a5.5 5.5 0 0 0-2-4.3" /></>,
  grupos: <><circle cx="12" cy="12" r="2.4" /><circle cx="5.5" cy="3.9" r="1.9" /><circle cx="18.5" cy="3.9" r="1.9" /><circle cx="5.5" cy="20.1" r="1.9" /><circle cx="18.5" cy="20.1" r="1.9" /><path d="M7 5.2l2.9 3.6M17 5.2l-2.9 3.6M7 18.8l2.9-3.6M17 18.8l-2.9-3.6" /></>,
  configuracion: <><path d="M4 7h10M18 7h2M4 12h4M12 12h8M4 17h12M20 17h0" /><circle cx="16" cy="7" r="2" /><circle cx="10" cy="12" r="2" /><circle cx="18" cy="17" r="2" /></>,
  sistema: <><rect x="4" y="4" width="16" height="7" rx="2" /><rect x="4" y="13" width="16" height="7" rx="2" /><path d="M8 7.5h.01M8 16.5h.01" /></>,
  salir: <><path d="M14 5h5v14h-5" /><path d="M4 12h10" /><path d="M11 9l3 3-3 3" /></>,
  menu: <><path d="M4 7h16M4 12h16M4 17h16" /></>,
  buscar: <><circle cx="11" cy="11" r="6" /><path d="M20 20l-4.2-4.2" /></>,
  capas: <><path d="M12 4 4 8.5 12 13l8-4.5z" /><path d="M4 12.5 12 17l8-4.5" /><path d="M4 16.5 12 21l8-4.5" /></>,
  play: <><path d="M8.5 6.5v11l9-5.5z" /></>,
  pausa: <><path d="M9 6.5v11M15 6.5v11" /></>,
  atras: <><path d="M15 6.5 8.5 12l6.5 5.5" /><path d="M7 6v12" /></>,
  adelante: <><path d="M9 6.5 15.5 12 9 17.5" /><path d="M17 6v12" /></>,
  chevronIzq: <path d="M14.5 6 8.5 12l6 6" />,
  chevronDer: <path d="M9.5 6l6 6-6 6" />,
  cerrar: <><path d="M6 6l12 12M18 6 6 18" /></>,
  editar: <><path d="M5 19h4l10-10-4-4L5 15z" /><path d="M13.5 6.5l4 4" /></>,
  basura: <><path d="M5 7h14" /><path d="M9 7V5h6v2" /><path d="M7 7l1 13h8l1-13" /></>,
  mas: <><path d="M12 5v14M5 12h14" /></>,
  flecha: <><path d="M9 6l6 6-6 6" /></>,
};

// memo: el marco, las tablas y las listas se repintan con cada sondeo y los
// iconos solo dependen de nombre y tamaño (primitivas).
export default memo(function Icono({ nombre, tamano = 17 }: { nombre: NombreIcono; tamano?: number }) {
  return (
    <svg className="icono" width={tamano} height={tamano} viewBox="0 0 24 24" fill="none"
      stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {TRAZOS[nombre]}
    </svg>
  );
});
