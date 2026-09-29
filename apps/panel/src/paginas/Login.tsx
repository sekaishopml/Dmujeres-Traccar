import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Eye, EyeOff, LoaderCircle, MapPinned, Route, ShieldCheck } from 'lucide-react';
import { useSesion } from '@/lib/sesion';
import { Logo } from '@/componentes/marco/Logo';
import { Boton } from '@/componentes/ui/Boton';
import { Campo, Casilla, Entrada } from '@/componentes/ui/Campo';

const PUNTOS = [
  { icono: MapPinned, texto: 'Flota en vivo con estado real de cada equipo.' },
  { icono: Route, texto: 'Recorridos auditables: lo que no se registró se muestra como hueco, nunca inventado.' },
  { icono: ShieldCheck, texto: 'Jornadas, paradas y batería en un solo lugar.' },
];

// Acceso: panel de marca marino a la izquierda, formulario a la derecha. En
// móvil queda solo el formulario con el logotipo. La sesión es una cookie
// HttpOnly; aquí no se guarda ninguna credencial (solo el usuario, si se pide).
export default function Login() {
  const { usuario, entrar } = useSesion();
  const navegar = useNavigate();
  const [nombre, setNombre] = useState(() => localStorage.getItem('dmj.correo') ?? '');
  const [clave, setClave] = useState('');
  const [verClave, setVerClave] = useState(false);
  const [recordar, setRecordar] = useState(true);
  const [error, setError] = useState('');
  const [enviando, setEnviando] = useState(false);

  useEffect(() => {
    if (usuario) navegar('/', { replace: true });
  }, [usuario, navegar]);

  async function alEnviar(evento: React.FormEvent) {
    evento.preventDefault();
    setError('');
    setEnviando(true);
    try {
      await entrar(nombre, clave);
      if (recordar) localStorage.setItem('dmj.correo', nombre.trim());
      else localStorage.removeItem('dmj.correo');
      navegar('/', { replace: true });
    } catch (fallo) {
      setError(fallo instanceof Error && fallo.message ? fallo.message : 'No se pudo entrar. Revisa el usuario y la clave.');
      setEnviando(false);
    }
  }

  return (
    <div className="grid min-h-full lg:grid-cols-[minmax(420px,5fr)_7fr]">
      <aside className="relative hidden overflow-hidden bg-tinta px-12 py-10 text-[#c5d6ea] lg:flex lg:flex-col">
        <div
          aria-hidden="true"
          className="pointer-events-none absolute inset-0"
          style={{
            background:
              'radial-gradient(90% 60% at 100% 100%, rgb(235 0 69 / 0.30), transparent 60%), radial-gradient(70% 50% at 0% 0%, rgb(44 95 153 / 0.45), transparent 60%)',
          }}
        />
        <svg aria-hidden="true" className="pointer-events-none absolute right-0 bottom-0 h-[70%] w-[90%] opacity-[0.12]" viewBox="0 0 400 300" fill="none">
          <path d="M10 280 C 80 250, 90 170, 160 170 S 250 220, 290 140 S 360 40, 395 20" stroke="white" strokeWidth="3" strokeDasharray="1 0" />
          <path d="M10 280 C 80 250, 90 170, 160 170" stroke="#ff5c8a" strokeWidth="3" />
          <circle cx="160" cy="170" r="7" fill="#ff5c8a" />
          <circle cx="290" cy="140" r="5" fill="white" />
          <circle cx="395" cy="20" r="5" fill="white" />
        </svg>
        <Logo claro className="relative h-12 self-start" />
        <div className="relative mt-auto mb-auto max-w-md">
          <h1 className="font-display text-[34px] leading-[1.15] font-semibold text-white">
            Cada recorrido,
            <br />
            tal como ocurrió.
          </h1>
          <div className="mt-5 h-1 w-12 rounded-full bg-marca" />
          <ul className="mt-8 space-y-4">
            {PUNTOS.map(({ icono: Icono, texto }) => (
              <li key={texto} className="flex gap-3 text-[14px] leading-relaxed">
                <span className="grid size-8 flex-none place-items-center rounded-lg bg-white/8 text-marca-claro">
                  <Icono className="size-4" />
                </span>
                <span className="pt-1">{texto}</span>
              </li>
            ))}
          </ul>
        </div>
        <p className="relative text-[12px] text-[#5a86bb]">© {new Date().getFullYear()} DMujeres · Plataforma de flota</p>
      </aside>

      <main className="flex items-center justify-center bg-fondo px-5 py-10">
        <form onSubmit={alEnviar} className="w-full max-w-[400px] animate-entrar">
          <Logo className="mb-10 h-11 lg:hidden" />
          <h2 className="text-[26px] font-semibold">Bienvenida de nuevo</h2>
          <p className="mt-1 mb-8 text-[13.5px] text-texto-2">Ingresa con tu usuario del panel.</p>

          <div className="space-y-4">
            <Campo etiqueta="Usuario o correo">
              <Entrada
                value={nombre}
                onChange={(e) => setNombre(e.target.value)}
                autoComplete="username"
                autoFocus
                required
                className="h-11"
              />
            </Campo>
            <Campo etiqueta="Clave">
              <span className="relative block">
                <Entrada
                  type={verClave ? 'text' : 'password'}
                  value={clave}
                  onChange={(e) => setClave(e.target.value)}
                  autoComplete="current-password"
                  required
                  className="h-11 pr-11"
                />
                <button
                  type="button"
                  onClick={() => setVerClave((v) => !v)}
                  aria-label={verClave ? 'Ocultar clave' : 'Mostrar clave'}
                  className="absolute inset-y-0 right-0 grid w-11 cursor-pointer place-items-center text-texto-3 hover:text-marino-900"
                >
                  {verClave ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                </button>
              </span>
            </Campo>
            <Casilla etiqueta="Recordar mi usuario" checked={recordar} onChange={(e) => setRecordar(e.target.checked)} />
          </div>

          {error && (
            <p role="alert" className="mt-5 rounded-control bg-peligro-suave px-3 py-2 text-[13px] font-medium text-peligro">
              {error}
            </p>
          )}

          <Boton type="submit" variante="principal" disabled={enviando} className="mt-6 h-11 w-full text-[14px]">
            {enviando && <LoaderCircle className="size-4 animate-spin" />}
            {enviando ? 'Entrando…' : 'Entrar'}
          </Boton>
        </form>
      </main>
    </div>
  );
}
