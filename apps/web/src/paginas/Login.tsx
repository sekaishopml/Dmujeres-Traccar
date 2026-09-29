import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Icono from '../componentes/Icono';
import Logotipo from '../componentes/Logotipo';
import { useSesion } from '../store/sesion';

// Acceso: panel de marca sobrio a la izquierda y formulario a la derecha.
// La sesión real es una cookie HttpOnly; aquí no se guarda ninguna credencial.
export default function Login() {
  const { usuario, entrar } = useSesion();
  const navegar = useNavigate();
  const [correo, setCorreo] = useState(() => localStorage.getItem('dmj.correo') ?? '');
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
      await entrar(correo, clave);
      if (recordar) localStorage.setItem('dmj.correo', correo.trim());
      else localStorage.removeItem('dmj.correo');
      navegar('/', { replace: true });
    } catch (fallo) {
      setError(fallo instanceof Error && fallo.message ? fallo.message : 'No se pudo entrar. Revisa el correo y la clave.');
      setEnviando(false);
    }
  }

  return (
    <div className="login-pantalla">
      <aside className="login-marca">
        <Logotipo claro grande />
        <div>
          <h1>Cada equipo, su jornada y su recorrido.</h1>
          <p className="login-intro">
            En vivo, Historial, Replay y Reportes de cada equipo, para seguir la jornada
            completa de la operación.
          </p>
        </div>
        <ul className="login-puntos">
          <li>
            <Icono nombre="enVivo" tamano={16} />
            Posición, estado y batería de cada equipo
          </li>
          <li>
            <Icono nombre="replay" tamano={16} />
            Recorridos y paradas, jornada por jornada
          </li>
          <li>
            <Icono nombre="reportes" tamano={16} />
            Reportes por equipo y rango de fechas
          </li>
        </ul>
        <p className="login-pie">DMujeres Tracking · Operación de motos y cuadrillas</p>
      </aside>

      <div className="login-acceso">
        <form className="login-caja" onSubmit={alEnviar}>
          <h2>Iniciar sesión</h2>
          <p className="lema">Entra con tu correo y tu clave.</p>

          <label className="campo">
            <span>Correo</span>
            <input
              type="text"
              value={correo}
              onChange={(e) => setCorreo(e.target.value)}
              autoComplete="username"
              placeholder="nombre@dmujeres.local"
              required
              autoFocus
            />
          </label>
          <label className="campo">
            <span>Clave</span>
            <input
              type={verClave ? 'text' : 'password'}
              value={clave}
              onChange={(e) => setClave(e.target.value)}
              autoComplete="current-password"
              required
            />
          </label>

          <div className="login-opciones">
            <label className="casilla">
              <input type="checkbox" checked={verClave} onChange={(e) => setVerClave(e.target.checked)} />
              Mostrar clave
            </label>
            <label className="casilla">
              <input type="checkbox" checked={recordar} onChange={(e) => setRecordar(e.target.checked)} />
              Recordar correo
            </label>
          </div>

          <button type="submit" className="principal" disabled={enviando}>
            {enviando ? 'Entrando…' : 'Entrar'}
          </button>
          <div className="error" role="alert">{error}</div>
        </form>
      </div>
    </div>
  );
}
