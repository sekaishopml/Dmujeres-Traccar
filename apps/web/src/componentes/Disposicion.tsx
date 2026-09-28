import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import Icono from './Icono';
import type { NombreIcono } from './Icono';
import Cargando from './Cargando';
import { useSesion } from '../store/sesion';

const ENLACES: { ruta: string; texto: string; icono: NombreIcono; fin?: boolean }[] = [
  { ruta: '/', texto: 'Inicio', icono: 'inicio', fin: true },
  { ruta: '/en-vivo', texto: 'En vivo', icono: 'enVivo' },
  { ruta: '/historial', texto: 'Historial', icono: 'historial' },
  { ruta: '/replay', texto: 'Replay', icono: 'replay' },
  { ruta: '/bateria', texto: 'Batería', icono: 'bateria' },
  { ruta: '/reportes', texto: 'Reportes', icono: 'reportes' },
  { ruta: '/usuarios', texto: 'Usuarios', icono: 'usuarios' },
  { ruta: '/configuracion', texto: 'Configuración', icono: 'configuracion' },
  { ruta: '/sistema', texto: 'Sistema', icono: 'sistema' },
];

const GRUPOS: { titulo: string; enlaces: typeof ENLACES }[] = [
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
      { ruta: '/configuracion', texto: 'Configuración', icono: 'configuracion' },
      { ruta: '/sistema', texto: 'Sistema', icono: 'sistema' },
    ],
  },
];

const TITULOS: Record<string, string> = Object.fromEntries(ENLACES.map((e) => [e.ruta, e.texto]));

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

// Marco del panel: barra lateral fija (estilo panel de flota) + barra superior
// con el título y la cuenta. En Replay la lateral se recoge sola para que el
// mapa ocupe toda la pantalla; el resto de páginas respetan la preferencia.
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

  const titulo = ubicacion.pathname.startsWith('/unidad/') ? 'Detalle de unidad' : TITULOS[ubicacion.pathname] ?? '';

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
          <img src="/logo.png" alt="DMujeres" />
          <div style={{ display: 'flex', flexDirection: 'column', lineHeight: 1.15 }}>
            <span>DMujeres Tracking</span>
            <span className="lema">Plataforma de flota</span>
          </div>
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
        <div className="pie">
          <button type="button" onClick={salir} title="Salir">
            <Icono nombre="salir" />
            <span>Salir</span>
          </button>
        </div>
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
          <span className="titulo">{titulo}</span>
          <span className="empuja" />
          <span className="usuario">
            {usuario.nombre || usuario.correo}
            {usuario.administrador && <em>admin</em>}
            {usuario.soloLectura && <em>solo lectura</em>}
          </span>
        </header>
        <main className="contenido">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
