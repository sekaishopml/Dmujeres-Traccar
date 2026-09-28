import { bateria } from '../../util/formato';

interface Props {
  porcentaje: number | null;
  cargando?: boolean | null;
}

// Mismos umbrales que el resto del panel: 20% crítico y 50% en atención.
export default function BarraBateria({ porcentaje, cargando }: Props) {
  if (porcentaje == null || !Number.isFinite(porcentaje)) return <span className="apagado">—</span>;
  const nivel = porcentaje <= 20 ? 'bajo' : porcentaje <= 50 ? 'medio' : '';
  const ancho = `${Math.min(100, Math.max(0, porcentaje))}%`;
  return (
    <span className="barra-bateria">
      <span className="tubo">
        <span className={`relleno ${nivel}`} style={{ width: ancho }} />
      </span>
      <span className="num">{bateria(porcentaje)}</span>
      {cargando === true && <span className="cargando-bateria">cargando</span>}
    </span>
  );
}
