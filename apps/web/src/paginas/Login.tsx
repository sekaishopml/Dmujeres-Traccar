import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
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
      <div className="login-acceso">
        <Logotipo grande />
        <form className="login-caja" onSubmit={alEnviar}>
          <h2>Iniciar sesión</h2>

          <label className="campo">
            <span>Usuario o correo</span>
            <input
              type="text"
              value={correo}
              onChange={(e) => setCorreo(e.target.value)}
              autoComplete="username"
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
