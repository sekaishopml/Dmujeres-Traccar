import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useSesion } from '../store/sesion';

// Acceso: marca del producto, formulario sobrio y mensajes en español.
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
      setError(fallo instanceof Error && fallo.message ? fallo.message : 'No se pudo entrar.');
      setEnviando(false);
    }
  }

  return (
    <div className="login-pantalla">
      <form className="login-caja" onSubmit={alEnviar}>
        <div className="marca-login">
          <img src="/logo.png" alt="DMujeres" />
          <strong>DMujeres Tracking</strong>
        </div>
        <p className="lema">Plataforma de la flota. Entra con tu correo y clave.</p>

        <label className="campo">
          <span>Correo</span>
          <input
            type="text"
            value={correo}
            onChange={(e) => setCorreo(e.target.value)}
            autoComplete="username"
            placeholder="usuario@dmujeres.local"
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
  );
}
