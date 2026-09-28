import type { Dispositivo } from '@contratos';
import { claveEstado, etiquetaEstado } from './estado';

interface Props {
  dispositivo: Pick<Dispositivo, 'estado' | 'habilitado'>;
}

export default function ChipEstado({ dispositivo }: Props) {
  return <span className={`chip ${claveEstado(dispositivo)}`}>{etiquetaEstado(dispositivo)}</span>;
}
