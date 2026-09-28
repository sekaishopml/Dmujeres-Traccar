import { useQuery } from '@tanstack/react-query';
import { api } from '../api/cliente';
import { GUION, fechaHora, hace } from '../util/formato';
import { MensajeError, mensajeDeError } from './admin/comunes';
import type { Disponibilidad, Salud, Version } from './admin/tipos';
import './admin.css';
import '../estilos/paginas.css';

// El contrato pide sondeo de salud; 15 s es suficiente para detectar caídas
// sin castigar al servidor.
const INTERVALO_MS = 15_000;

type EstadoDependencia = 'ok' | 'error' | 'desconocido';

// Cada dependencia se explica en una línea con lo que implica su caída, no con
// un "Ok" suelto: el personal de oficina necesita saber qué deja de funcionar.
const SIGNIFICADO: Record<'proceso' | 'baseDatos' | 'tracking', Record<EstadoDependencia, string>> = {
  proceso: {
    ok: 'El proceso de la API responde a las peticiones del panel.',
    error: 'La API no responde: el panel no carga ni guarda datos.',
    desconocido: 'Comprobando si la API responde…',
  },
  baseDatos: {
    ok: 'PostgreSQL acepta consultas: flota e histórico disponibles.',
    error: 'PostgreSQL no responde: sin datos de flota ni histórico.',
    desconocido: 'Sin confirmación de PostgreSQL.',
  },
  tracking: {
    ok: 'El motor de seguimiento está conectado: las posiciones llegan.',
    error: 'El motor de seguimiento no responde: las unidades dejarán de actualizarse.',
    desconocido: 'Sin confirmación del motor de seguimiento.',
  },
};

function estadoDe(valor: 'ok' | 'error' | undefined, cargando: boolean, fallo: boolean): EstadoDependencia {
  if (valor === 'ok') return 'ok';
  if (cargando) return 'desconocido';
  if (valor === 'error' || fallo) return 'error';
  return 'desconocido';
}

function FilaDependencia({
  nombre,
  estado,
  significado,
}: {
  nombre: string;
  estado: EstadoDependencia;
  significado: string;
}) {
  const clasePunto = estado === 'desconocido' ? 'sin-datos' : estado;
  const claseResultado = estado === 'ok' ? 'si' : estado === 'error' ? 'no' : 'apagado';
  return (
    <div className="fila-dependencia">
      <span className={`punto-estado ${clasePunto}`} aria-hidden="true" />
      <strong>{nombre}</strong>
      <span className="detalle">{significado}</span>
      <span className={`resultado ${claseResultado}`}>
        {estado === 'ok' ? 'Ok' : estado === 'error' ? 'Error' : 'Sin respuesta'}
      </span>
    </div>
  );
}

export default function Sistema() {
  // Sondeo de fondo cada 15 s: un 401 aquí no redirige, solo deja el estado
  // de error; la comprobación de sesión decide.
  const salud = useQuery({
    queryKey: ['sistema', 'salud'],
    queryFn: () => api.get<Salud>('/api/v1/health', { redirigir401: false }),
    refetchInterval: INTERVALO_MS,
  });

  const listo = useQuery({
    queryKey: ['sistema', 'listo'],
    queryFn: () => api.get<Disponibilidad>('/api/v1/ready', { redirigir401: false }),
    refetchInterval: INTERVALO_MS,
  });

  const version = useQuery({
    queryKey: ['sistema', 'version'],
    queryFn: () => api.get<Version>('/api/v1/version', { redirigir401: false }),
    refetchInterval: INTERVALO_MS,
  });

  // /ready puede fallar con 503 o responder 200 con estado "degradado": ambos
  // casos se resaltan igual porque significan que una dependencia no está bien.
  const listoFalla =
    listo.isError ||
    listo.data?.estado === 'degradado' ||
    listo.data?.dependencias.baseDatos === 'error' ||
    listo.data?.dependencias.tracking === 'error';

  const baseDatos = listo.data?.dependencias.baseDatos;
  const tracking = listo.data?.dependencias.tracking;
  const fallidas: string[] = [];
  if (baseDatos === 'error') fallidas.push('PostgreSQL');
  if (tracking === 'error') fallidas.push('el motor de seguimiento');

  const estadoProceso = estadoDe(undefined, salud.isPending, salud.isError);
  // Si /ready falla sin cuerpo no se puede culpar a una dependencia concreta:
  // las filas quedan en "Sin respuesta" y el aviso superior explica el fallo.
  const estadoBaseDatos = estadoDe(baseDatos, listo.isPending, false);
  const estadoTracking = estadoDe(tracking, listo.isPending, false);

  const estados = [estadoProceso, estadoBaseDatos, estadoTracking];
  const disponibles = estados.filter((estado) => estado === 'ok').length;

  return (
    <section className="pagina-sistema">
      <header className="cabecera-pagina">
        <div>
          <h1>Sistema</h1>
          <p className="sub">Disponibilidad y versión del servicio; se refresca cada 15 s.</p>
        </div>
      </header>

      {listoFalla && (
        <section className="seccion">
          <div className="bloque fallo">
            <header className="cabecera-seccion">
              <h2>Disponibilidad degradada</h2>
            </header>
            {listo.error && (
              <p role="alert">La API no pudo comprobar sus dependencias: {mensajeDeError(listo.error)}.</p>
            )}
            {!listo.error && listo.data && (
              <p role="alert">
                {fallidas.length > 0
                  ? `El chequeo de disponibilidad falla para ${fallidas.join(' y ')}.`
                  : 'El chequeo de disponibilidad responde "degradado".'}
              </p>
            )}
            <p className="apagado">
              Los datos del panel pueden estar incompletos; conviene revisar los servicios en el servidor antes de
              operar con la flota.
            </p>
          </div>
        </section>
      )}

      <section className="seccion">
        <div className="tira-datos">
          <div className="dato">
            <div className="valor">{version.data?.version ?? GUION}</div>
            <div className="etiqueta">Versión</div>
          </div>
          <div className="dato">
            <div className="valor">{version.data?.versionApi ?? GUION}</div>
            <div className="etiqueta">Versión API</div>
          </div>
          <div className="dato">
            <div className="valor">{version.data?.versionEsquema ?? GUION}</div>
            <div className="etiqueta">Versión esquema</div>
          </div>
          <div className="dato">
            <div className="valor mono">{version.data?.commit ?? GUION}</div>
            <div className="etiqueta">Commit</div>
          </div>
          <div className="dato">
            <div className="valor">{fechaHora(listo.data?.comprobadoEn)}</div>
            <div className="etiqueta">Última comprobación</div>
          </div>
        </div>
      </section>

      <section className="seccion">
        <div className="bloque">
          <header className="cabecera-seccion">
            <h2>Dependencias</h2>
            <span className="cuenta">{disponibles} de 3 disponibles</span>
            <span className="acciones">
              <span className="cuenta">
                {salud.dataUpdatedAt > 0 ? `Consultado ${hace(new Date(salud.dataUpdatedAt).toISOString())}` : ''}
              </span>
            </span>
          </header>
          <FilaDependencia
            nombre="Proceso API"
            estado={estadoProceso}
            significado={SIGNIFICADO.proceso[estadoProceso]}
          />
          <FilaDependencia
            nombre="Base de datos"
            estado={estadoBaseDatos}
            significado={SIGNIFICADO.baseDatos[estadoBaseDatos]}
          />
          <FilaDependencia
            nombre="Tracking"
            estado={estadoTracking}
            significado={SIGNIFICADO.tracking[estadoTracking]}
          />
          {salud.error && <MensajeError error={salud.error} />}
          {listo.error && <MensajeError error={listo.error} />}
        </div>
      </section>
    </section>
  );
}
