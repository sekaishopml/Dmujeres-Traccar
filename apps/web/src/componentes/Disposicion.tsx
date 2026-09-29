import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import Icono from './Icono';
import type { NombreIcono } from './Icono';
import Logotipo from './Logotipo';
import Cargando from './Cargando';
import { useSesion } from '../store/sesion';
import { alNoAutorizado } from '../api/cliente';

interface Enlace {
  ruta: string;
  texto: string;
  icono: NombreIcono;
  fin?: boolean;
}

const GRUPOS: { titulo: string; enlaces: Enlace[] }[] = [
  {
    titulo: 'Operación',
    enlaces: [
      { ruta: '/', texto: 'Inicio', icono: 'inicio', fin: true },
      { ruta: '/en-vivo', texto: 'En vivo', icono: 'enVivo' },
      { ruta: '/historial', texto: 'Historial', icono: 'historial' },
      { ruta: '/replay', texto: 'Replay', icono: 'replay' },
      { ruta: '/bateria', texto: 'Batería', icono: 'bateria' },
      { ruta: '/reportes', texto: 'Reportes', icono: 'reportes' },
    ],
  },
  {
    titulo: 'Administración',
    enlaces: [
      { ruta: '/usuarios', texto: 'Usuarios', icono: 'usuarios' },
      { ruta: '/grupos', texto: 'Grupos', icono: 'grupos' },
      { ruta: '/configuracion', texto: 'Configuración', icono: 'configuracion' },
      { ruta: '/sistema', texto: 'Sistema', icono: 'sistema' },
    ],
  },
];

// Título y grupo de cada ruta, derivados del menú: una sola fuente de verdad
// para la lateral y para las migas de la barra superior.
const PAGINAS = new Map(
  GRUPOS.flatMap((grupo) => grupo.enlaces.map((enlace) => [enlace.ruta, { texto: enlace.texto, grupo: grupo.titulo }])),
);

// Accesos rápidos de la barra superior: lo que un operador consulta a diario.
const ACCESOS = [
  { ruta: '/en-vivo', texto: 'En vivo' },
  { ruta: '/historial', texto: 'Historial' },
];

// La lateral cambia con el ancho: riel de íconos en tablet (641-860 px, lo
// resuelve el CSS) y capa deslizable en móvil angosto, donde el menú se monta
// encima del contenido en vez de robarle ancho. Se consulta con matchMedia
// para que el botón sepa qué estado alternar.
function usePantallaAngosta(): boolean {
  const [angosta, setAngosta] = useState(() => window.matchMedia('(max-width: 640px)').matches);
  useEffect(() => {
    const consulta = window.matchMedia('(max-width: 640px)');
    const alCambiar = () => setAngosta(consulta.matches);
    consulta.addEventListener('change', alCambiar);
    return () => consulta.removeEventListener('change', alCambiar);
  }, []);
  return angosta;
}

// Iniciales para el avatar de la cuenta: nombre y apellido si existen, o las
// dos primeras letras del correo. Solo presentación; no altera la sesión.
function iniciales(nombre: string, correo: string): string {
  const base = (nombre.trim() || correo.trim()).replace(/\s+/g, ' ');
  const partes = base.split(' ').filter(Boolean);
  if (partes.length >= 2) return (partes[0][0] + partes[1][0]).toUpperCase();
  return base.slice(0, 2).toUpperCase();
}

// Marco del panel: barra lateral fija (estilo panel de flota) + barra superior
// con migas de ubicación, accesos de operación, la cuenta y la salida. En
// Replay la lateral se recoge sola para que el mapa ocupe toda la pantalla;
// el resto de páginas respetan la preferencia.
export default function Disposicion() {
  const { usuario, cargando, cargar, salir } = useSesion();
  const navegar = useNavigate();
  const ubicacion = useLocation();
  const [recogida, setRecogida] = useState(() => localStorage.getItem('dmj.lateral') === 'min');
  // En Replay la lateral se recoge al entrar, pero la hamburguesa puede
  // desplegarla y volverla a plegar mientras se esté en la página; al salir
  // rige la preferencia guardada. Antes la página forzaba el plegado siempre y
  // el botón parecía roto.
  const [plegadaEnReplay, setPlegadaEnReplay] = useState(true);
  const [menuAbierto, setMenuAbierto] = useState(false);
  const angosta = usePantallaAngosta();
  const enReplay = ubicacion.pathname.startsWith('/replay');
  // En móvil la lateral es una capa: el plegado de escritorio no aplica.
  const colapsada = !angosta && (enReplay ? plegadaEnReplay : recogida);

  useEffect(() => {
    setPlegadaEnReplay(true);
  }, [enReplay]);

  // El menú móvil se cierra al navegar: al tocar un enlace ya cumplió su
  // función y dejarlo abierto tapa el contenido.
  useEffect(() => {
    setMenuAbierto(false);
  }, [ubicacion.pathname]);

  // El 401 del cliente API navega por SPA en vez de recargar la página
  // completa: conserva el estado del enrutador y evita perder la ruta.
  useEffect(() => {
    alNoAutorizado(() => navegar('/login', { replace: true }));
    return () => alNoAutorizado(null);
  }, [navegar]);

  useEffect(() => {
    cargar().then((u) => {
      if (!u) navegar('/login', { replace: true });
    });
  }, [cargar, navegar]);

  useEffect(() => {
    localStorage.setItem('dmj.lateral', recogida ? 'min' : 'completo');
  }, [recogida]);

  if (cargando || !usuario) {
    return <Cargando texto="Comprobando sesión…" />;
  }

  // El título y el grupo del menú alimentan las migas de la barra superior.
  const pagina = ubicacion.pathname.startsWith('/unidad/')
    ? { texto: 'Detalle del equipo', grupo: 'Operación' }
    : PAGINAS.get(ubicacion.pathname) ?? { texto: '', grupo: '' };

  function alternarMenu() {
    if (angosta) setMenuAbierto((v) => !v);
    else if (enReplay) setPlegadaEnReplay((v) => !v);
    else setRecogida((v) => !v);
  }

  return (
    <div className={`app${colapsada ? ' colapsado' : ''}${menuAbierto ? ' menu-abierto' : ''}`}>
      <button type="button" className="velo-menu" aria-label="Cerrar menú" onClick={() => setMenuAbierto(false)} />
      <aside className="lateral">
        <div className="marca">
          <Logotipo claro />
          <span className="lema">Plataforma de flota</span>
        </div>
        <nav>
          {GRUPOS.map((grupo) => (
            <div key={grupo.titulo}>
              <div className="grupo">{grupo.titulo}</div>
              {grupo.enlaces.map((enlace) => (
                <NavLink key={enlace.ruta} to={enlace.ruta} end={enlace.fin} title={enlace.texto}>
                  <Icono nombre={enlace.icono} />
                  <span>{enlace.texto}</span>
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
      </aside>

      <div className="main">
        <header className="barra-superior">
          <button
            type="button"
            className="plegar"
            title="Mostrar u ocultar menú"
            onClick={alternarMenu}
          >
            <Icono nombre="menu" />
          </button>
          <nav className="miga" aria-label="Ubicación">
            {pagina.grupo !== '' && (
              <>
                <span className="miga-grupo">{pagina.grupo}</span>
                <span className="miga-sep" aria-hidden="true" />
              </>
            )}
            <span className="miga-pagina" aria-current="page">{pagina.texto}</span>
          </nav>
          <span className="empuja" />
          <nav className="barra-acciones" aria-label="Accesos de operación">
            {ACCESOS.map((acceso) => (
              <NavLink key={acceso.ruta} to={acceso.ruta}>
                {acceso.texto}
              </NavLink>
            ))}
          </nav>
          <span className="barra-sep" aria-hidden="true" />
          <div className="cuenta-chip" title={usuario.correo ?? undefined}>
            <span className="usuario-avatar" aria-hidden="true">
              {iniciales(usuario.nombre ?? '', usuario.correo ?? '')}
            </span>
            <span className="usuario-ficha">
              <span className="usuario-nombre">{usuario.nombre || usuario.correo}</span>
              {(usuario.administrador || usuario.soloLectura) && (
                <span className="usuario-roles">
                  {usuario.administrador && <em className="rol admin">admin</em>}
                  {usuario.soloLectura && <em className="rol lectura">solo lectura</em>}
                </span>
              )}
            </span>
          </div>
          <span className="barra-sep" aria-hidden="true" />
          <button type="button" className="salir-barra" onClick={salir} title="Salir">
            <Icono nombre="salir" tamano={16} />
            <span>Salir</span>
          </button>
        </header>
        <main className="contenido">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
