import { memo } from 'react';
import type { Dispositivo } from '@contratos';
import { claveEstado, etiquetaEstado } from './estado';

interface Props {
  dispositivo: Pick<Dispositivo, 'estado' | 'habilitado'>;
}

// memo: la lista de En vivo y la ficha de Detalle se repintan con cada sondeo;
// el chip solo cambia si cambia el estado/habilitado del equipo (react-query
// conserva la referencia de los DTO sin cambios por reparto estructural).
export default memo(function ChipEstado({ dispositivo }: Props) {
  return <span className={`chip ${claveEstado(dispositivo)}`}>{etiquetaEstado(dispositivo)}</span>;
});
