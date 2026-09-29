// Marca de DMujeres Tracking: monograma propio en SVG (tile con ruta y nodos)
// más wordmark tipográfico "DMujeres Tracking". Sin imágenes ni dependencias;
// el mismo trazo se replica en public/favicon.svg para la pestaña del navegador.
//
// Variantes:
// - claro: para fondos navy (lateral y panel de acceso).
// - compacto: solo el monograma (riel lateral plegado).
// - grande: escala de presentación (acceso y pantalla de carga).
type Props = {
  claro?: boolean;
  compacto?: boolean;
  grande?: boolean;
};

export default function Logotipo({ claro = false, compacto = false, grande = false }: Props) {
  const clases = ['logotipo'];
  if (claro) clases.push('logotipo--claro');
  if (compacto) clases.push('logotipo--compacto');
  if (grande) clases.push('logotipo--grande');

  return (
    <span className={clases.join(' ')}>
      <svg
        className="logotipo-marca"
        viewBox="0 0 32 32"
        width="30"
        height="30"
        aria-hidden="true"
        focusable="false"
      >
        <rect className="logotipo-tile" width="32" height="32" rx="7.5" />
        <path
          className="logotipo-ruta"
          d="M8.5 23.5l5-8 5 4 5.5-10"
          fill="none"
          strokeWidth="2.4"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
        <circle className="logotipo-nodo" cx="8.5" cy="23.5" r="2.3" />
        <circle className="logotipo-nodo-fin" cx="24" cy="9.5" r="2.6" />
      </svg>
      {!compacto && (
        <span className="logotipo-texto">
          <span className="logotipo-nombre">DMujeres</span>
          <span className="logotipo-apellido">Tracking</span>
        </span>
      )}
    </span>
  );
}
