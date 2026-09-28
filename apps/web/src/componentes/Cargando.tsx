// Pantalla de carga de la plataforma: marca, giro y texto, a pantalla completa
// como el panel clásico. Se usa mientras llega el trozo de código de una página
// o al comprobar la sesión.
export default function Cargando({ texto = 'Cargando…' }: { texto?: string }) {
  return (
    <div className="cargando-pantalla">
      <img src="/logo.png" alt="DMujeres Tracking" />
      <div className="marca-texto">DMujeres Tracking</div>
      <div className="giro" aria-hidden="true" />
      <div className="texto">{texto}</div>
    </div>
  );
}
