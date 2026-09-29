import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { ReactNode } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Bell, BatteryWarning, ChevronDown, LogOut, Menu, Search, SignalLow, X } from 'lucide-react';
import { useSesion } from '@/lib/sesion';
import { alNoAutorizado } from '@/lib/api';
import { cn } from '@/lib/cn';
import { Cargando } from '@/componentes/ui/Estados';
import { Avatar } from '@/componentes/ui/Avatar';
import { CLAVE_FLOTA, traerFlota } from '@/dominio/datos';
import { claveEstado } from '@/dominio/estado';
import { hace } from '@/dominio/formatoBase';
import { Logo } from './Logo';
import { GRUPOS, paginaDeRuta } from './navegacion';

const ID_ACCIONES = 'marco-acciones-pagina';

// Las páginas colocan sus acciones (filtros de fecha, exportar, agregar) en la
// barra superior, junto al título, con <AccionesPagina>.
export function AccionesPagina({ children }: { children: ReactNode }) {
  const [destino, setDestino] = useState<HTMLElement | null>(null);
  useEffect(() => setDestino(document.getElementById(ID_ACCIONES)), []);
  return destino ? createPortal(children, destino) : null;
}

// Marco del panel: lateral blanca con el logotipo oficial y la navegación en
// píldoras (la activa en magenta), y barra superior con el título de la
// página, su propósito, la búsqueda de personas, los avisos y la cuenta.
export default function Marco() {
  const { usuario, cargando, cargar, salir } = useSesion();
  const navegar = useNavigate();
  const { pathname } = useLocation();
  const [menuMovil, setMenuMovil] = useState(false);
  const pantallaCompleta = pathname.startsWith('/replay') || pathname.startsWith('/en-vivo');

  useEffect(() => {
    alNoAutorizado(() => navegar('/login', { replace: true }));
    return () => alNoAutorizado(null);
  }, [navegar]);

  useEffect(() => {
    cargar().then((u) => {
      if (!u) navegar('/login', { replace: true });
    });
  }, [cargar, navegar]);

  useEffect(() => setMenuMovil(false), [pathname]);

  if (cargando || !usuario) return <Cargando texto="Comprobando sesión…" className="h-dvh" />;

  const pagina = paginaDeRuta(pathname);
  const nombre = usuario.nombre || usuario.correo || 'Cuenta';

  return (
    <div className="flex h-dvh overflow-hidden bg-fondo">
      <button
        type="button"
        aria-label="Cerrar menú"
        onClick={() => setMenuMovil(false)}
        className={cn('fixed inset-0 z-40 bg-marino-950/40 lg:hidden', menuMovil ? 'block' : 'hidden')}
      />

      <aside
        className={cn(
          'fixed inset-y-0 left-0 z-50 flex w-[248px] flex-none flex-col border-r border-borde bg-superficie transition-transform duration-200',
          'lg:static lg:translate-x-0',
          menuMovil ? 'translate-x-0 shadow-flotante' : '-translate-x-full',
        )}
      >
        <div className="flex h-[76px] flex-none items-center justify-between px-6">
          <Logo className="h-9" />
          <button
            type="button"
            onClick={() => setMenuMovil(false)}
            aria-label="Cerrar menú"
            className="grid size-8 cursor-pointer place-items-center rounded-control text-texto-2 hover:bg-fondo lg:hidden"
          >
            <X className="size-4" />
          </button>
        </div>

        <nav className="flex-1 overflow-y-auto px-4 pt-2 pb-4">
          {GRUPOS.map((grupo) => {
            const enlaces = grupo.enlaces.filter((e) => !e.soloAdmin || usuario.administrador);
            if (enlaces.length === 0) return null;
            return (
              <div key={grupo.titulo} className="mb-6">
                <p className="mb-2 px-3 text-[11px] font-semibold tracking-[0.08em] text-texto-3 uppercase">
                  {grupo.titulo}
                </p>
                <div className="flex flex-col gap-1">
                  {enlaces.map(({ ruta, texto, icono: Icono, exacto }) => (
                    <NavLink
                      key={ruta}
                      to={ruta}
                      end={exacto}
                      className={({ isActive }) =>
                        cn(
                          'flex h-11 items-center gap-3 rounded-[12px] px-3.5 text-[14px] font-medium transition-colors',
                          isActive
                            ? 'bg-marca text-white shadow-[0_6px_16px_-4px_rgb(235_0_69/0.45)]'
                            : 'text-texto-2 hover:bg-fondo hover:text-marino-900',
                        )
                      }
                    >
                      <Icono className="size-[19px] flex-none" strokeWidth={1.9} />
                      {texto}
                    </NavLink>
                  ))}
                </div>
              </div>
            );
          })}
        </nav>

        <div className="flex-none border-t border-borde p-4">
          <button
            type="button"
            onClick={salir}
            className="flex h-11 w-full cursor-pointer items-center gap-3 rounded-[12px] px-3.5 text-[14px] font-medium text-texto-2 transition-colors hover:bg-peligro-suave hover:text-peligro"
          >
            <LogOut className="size-[19px]" strokeWidth={1.9} />
            Cerrar sesión
          </button>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex min-h-[76px] flex-none flex-wrap items-center gap-x-4 gap-y-2 border-b border-borde bg-superficie px-4 py-3 md:px-7">
          <button
            type="button"
            onClick={() => setMenuMovil(true)}
            aria-label="Abrir menú"
            className="grid size-10 cursor-pointer place-items-center rounded-full border border-borde text-texto-2 hover:bg-fondo lg:hidden"
          >
            <Menu className="size-5" />
          </button>
          <div className="min-w-0 flex-1">
            <h1 className="truncate text-[20px] leading-tight font-semibold">{pagina.texto}</h1>
            {pagina.descripcion && <p className="mt-0.5 hidden truncate text-[13px] text-texto-2 sm:block">{pagina.descripcion}</p>}
          </div>
          <div id={ID_ACCIONES} className="order-last flex w-full flex-wrap items-center gap-2 empty:hidden xl:order-none xl:w-auto [&_input]:w-auto [&_select]:w-auto [&_select]:max-w-56" />
          <div className="flex items-center gap-2">
            <BuscadorPersonas />
            <Avisos />
            <ChipCuenta nombre={nombre} rol={usuario.administrador ? 'Administrador' : usuario.soloLectura ? 'Solo lectura' : 'Supervisión'} />
          </div>
        </header>

        <main className={cn('min-h-0 flex-1', pantallaCompleta ? 'overflow-hidden' : 'overflow-y-auto px-4 py-6 md:px-7')}>
          <Outlet />
        </main>
      </div>
    </div>
  );
}

function ChipCuenta({ nombre, rol }: { nombre: string; rol: string }) {
  return (
    <div className="flex items-center gap-2.5 rounded-full border border-borde bg-superficie py-1 pr-3.5 pl-1">
      <Avatar nombre={nombre} tamano="sm" />
      <span className="hidden leading-tight md:block">
        <span className="block max-w-[160px] truncate text-[13px] font-semibold text-marino-900">{nombre}</span>
        <span className="block text-[11px] text-texto-3">{rol}</span>
      </span>
      <ChevronDown className="hidden size-4 text-texto-3 md:block" />
    </div>
  );
}

// Consulta compartida con las páginas (misma clave de caché): no duplica
// peticiones. Sin redirección en 401 porque es de fondo.
function useFlota() {
  return useQuery({
    queryKey: CLAVE_FLOTA,
    queryFn: () => traerFlota({ redirigir401: false }),
    refetchInterval: 30_000,
  });
}

function BuscadorPersonas() {
  const navegar = useNavigate();
  const flota = useFlota();
  const [abierto, setAbierto] = useState(false);
  const [texto, setTexto] = useState('');
  const caja = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!abierto) return;
    const fuera = (e: MouseEvent) => {
      if (!caja.current?.contains(e.target as Node)) setAbierto(false);
    };
    document.addEventListener('mousedown', fuera);
    return () => document.removeEventListener('mousedown', fuera);
  }, [abierto]);

  const resultados = useMemo(() => {
    const q = texto.trim().toLowerCase();
    const todos = flota.data?.datos ?? [];
    return (q ? todos.filter((d) => `${d.nombre} ${d.identificadorUnico}`.toLowerCase().includes(q)) : todos).slice(0, 8);
  }, [texto, flota.data]);

  return (
    <div ref={caja} className="relative">
      <button
        type="button"
        onClick={() => setAbierto((v) => !v)}
        aria-label="Buscar persona"
        title="Buscar persona"
        className="grid size-10 cursor-pointer place-items-center rounded-full border border-borde text-texto-2 transition-colors hover:bg-fondo hover:text-marino-900"
      >
        <Search className="size-[18px]" />
      </button>
      {abierto && (
        <div className="absolute top-12 right-0 z-50 w-[min(340px,calc(100vw-32px))] animate-entrar rounded-tarjeta border border-borde bg-superficie p-2 shadow-flotante">
          <input
            autoFocus
            value={texto}
            onChange={(e) => setTexto(e.target.value)}
            placeholder="Buscar persona o equipo…"
            className="h-10 w-full rounded-control border border-borde bg-fondo px-3 text-[13px] focus:border-marca focus:outline-none"
          />
          <ul className="mt-2 max-h-80 overflow-y-auto">
            {resultados.map((d) => (
              <li key={d.idPublico}>
                <button
                  type="button"
                  onClick={() => {
                    setAbierto(false);
                    setTexto('');
                    navegar(`/unidad/${d.idPublico}`);
                  }}
                  className="flex w-full cursor-pointer items-center gap-3 rounded-control px-2 py-2 text-left hover:bg-fondo"
                >
                  <Avatar nombre={d.nombre} estado={claveEstado(d)} tamano="sm" />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-[13px] font-semibold text-marino-900">{d.nombre}</span>
                    <span className="block truncate text-[11.5px] text-texto-3">Último reporte {hace(d.ultimaConexion)}</span>
                  </span>
                </button>
              </li>
            ))}
            {resultados.length === 0 && <li className="px-2 py-6 text-center text-[12.5px] text-texto-3">Sin coincidencias.</li>}
          </ul>
        </div>
      )}
    </div>
  );
}

// Avisos: personas en jornada sin señal y equipos con batería baja, tomados
// del estado real de la flota (nada se inventa ni se guarda).
function Avisos() {
  const navegar = useNavigate();
  const flota = useFlota();
  const [abierto, setAbierto] = useState(false);
  const caja = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!abierto) return;
    const fuera = (e: MouseEvent) => {
      if (!caja.current?.contains(e.target as Node)) setAbierto(false);
    };
    document.addEventListener('mousedown', fuera);
    return () => document.removeEventListener('mousedown', fuera);
  }, [abierto]);

  const avisos = useMemo(() => {
    const lista: { id: string; nombre: string; texto: string; tipo: 'senal' | 'bateria' }[] = [];
    for (const d of flota.data?.datos ?? []) {
      if (!d.habilitado) continue;
      const clave = claveEstado(d);
      if (d.jornadaActiva && clave === 'sinSenal') {
        lista.push({ id: `${d.idPublico}-s`, nombre: d.nombre, texto: `Sin señal · último reporte ${hace(d.ultimaConexion)}`, tipo: 'senal' });
      }
      if (d.bateriaPct != null && d.bateriaPct <= 15 && !d.cargando) {
        lista.push({ id: `${d.idPublico}-b`, nombre: d.nombre, texto: `Batería en ${Math.round(d.bateriaPct)}%`, tipo: 'bateria' });
      }
    }
    return lista.map((a) => ({ ...a, idPublico: a.id.slice(0, -2) }));
  }, [flota.data]);

  return (
    <div ref={caja} className="relative">
      <button
        type="button"
        onClick={() => setAbierto((v) => !v)}
        aria-label="Avisos"
        title="Avisos"
        className="relative grid size-10 cursor-pointer place-items-center rounded-full border border-borde text-texto-2 transition-colors hover:bg-fondo hover:text-marino-900"
      >
        <Bell className="size-[18px]" />
        {avisos.length > 0 && (
          <span className="absolute -top-0.5 -right-0.5 grid h-[18px] min-w-[18px] place-items-center rounded-full bg-marca px-1 text-[10px] font-bold text-white ring-2 ring-white">
            {avisos.length}
          </span>
        )}
      </button>
      {abierto && (
        <div className="absolute top-12 right-0 z-50 w-[min(360px,calc(100vw-32px))] animate-entrar rounded-tarjeta border border-borde bg-superficie shadow-flotante">
          <p className="border-b border-borde px-4 py-3 font-display text-[14px] font-semibold text-marino-900">Requieren atención</p>
          <ul className="max-h-96 overflow-y-auto p-2">
            {avisos.map((a) => (
              <li key={a.id}>
                <button
                  type="button"
                  onClick={() => {
                    setAbierto(false);
                    navegar(`/unidad/${a.idPublico}`);
                  }}
                  className="flex w-full cursor-pointer items-start gap-3 rounded-control px-2 py-2.5 text-left hover:bg-fondo"
                >
                  <span
                    className={cn(
                      'grid size-8 flex-none place-items-center rounded-full',
                      a.tipo === 'senal' ? 'bg-sin-senal-suave text-sin-senal' : 'bg-peligro-suave text-peligro',
                    )}
                  >
                    {a.tipo === 'senal' ? <SignalLow className="size-4" /> : <BatteryWarning className="size-4" />}
                  </span>
                  <span className="min-w-0">
                    <span className="block text-[13px] font-semibold text-marino-900">{a.nombre}</span>
                    <span className="block text-[12px] text-texto-2">{a.texto}</span>
                  </span>
                </button>
              </li>
            ))}
            {avisos.length === 0 && <li className="px-2 py-8 text-center text-[12.5px] text-texto-3">Todo en orden.</li>}
          </ul>
        </div>
      )}
    </div>
  );
}
