import { memo } from 'react';
import { bateria } from '../../util/formato';

interface Props {
  porcentaje: number | null;
  cargando?: boolean | null;
}

// Mismos umbrales que el resto del panel: 20% crítico y 50% en atención.
// memo: la lista de En vivo se repinta con cada sondeo y la barra solo cambia
// cuando cambia el porcentaje o el estado de carga.
export default memo(function BarraBateria({ porcentaje, cargando }: Props) {
  if (porcentaje == null || !Number.isFinite(porcentaje)) return <span className="apagado">—</span>;
  const nivel = porcentaje <= 20 ? 'bajo' : porcentaje <= 50 ? 'medio' : '';
  const ancho = `${Math.min(100, Math.max(0, porcentaje))}%`;
  return (
    <span className="barra-bateria">
      <span className="tubo">
        <span className={`relleno ${nivel}`} style={{ width: ancho }} />
      </span>
      <span className="num">{bateria(porcentaje)}</span>
      {cargando === true && <span className="cargando-bateria pulso">cargando</span>}
    </span>
  );
});
