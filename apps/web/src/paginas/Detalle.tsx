import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Marker, Popup } from 'maplibre-gl';
import type { Map as TipoMapa } from 'maplibre-gl';
import { bateria, fechaHora, hace, velocidad, GUION } from '../util/formato';
import { consulta } from '../api/cliente';
import Icono from '../componentes/Icono';
import MapaRaster from './operacion/MapaRaster';
import ChipEstado from './operacion/ChipEstado';
import BarraBateria from './operacion/BarraBateria';
import { colorEstado } from './operacion/estado';
import { contenidoPopup } from './operacion/popup';
import { coordenadas, entero, grados, metros, siNo } from './operacion/formato';
import { traerDispositivo, traerUltimaPosicion } from './operacion/datos';
import { mensajeError } from './operacion/errores';
import { fechaHoyLocal } from './operacion/rango';
import '../estilos/paginas.css';

const REFRESCO_MS = 10_000;

// Par etiqueta/valor de la ficha (dl sobrio definido en global.css).
function Dato({ etiqueta, children }: { etiqueta: string; children: ReactNode }) {
  return (
    <>
      <dt>{etiqueta}</dt>
      <dd>{children}</dd>
    </>
  );
}

export default function Detalle() {
  const { id } = useParams();
  const identificador = id ?? '';
  const [mapa, setMapa] = useState<TipoMapa | null>(null);
  const marcador = useRef<Marker | null>(null);

  const consultaEquipo = useQuery({
    queryKey: ['dispositivo', identificador],
    queryFn: () => traerDispositivo(identificador),
    enabled: identificador !== '',
    refetchInterval: REFRESCO_MS,
  });

  const consultaPosicion = useQuery({
    queryKey: ['dispositivo', identificador, 'posicion'],
    queryFn: () => traerUltimaPosicion(identificador),
    enabled: identificador !== '',
    refetchInterval: REFRESCO_MS,
    // Un 404 aquí no es fallo: la unidad simplemente no tiene fix conocido.
    retry: false,
  });

  const equipo = consultaEquipo.data;
  const posicion = consultaPosicion.data;

  useEffect(() => {
    if (!mapa) {
      marcador.current?.remove();
      marcador.current = null;
      return;
    }
    if (!posicion) return;
    const centro: [number, number] = [posicion.longitud, posicion.latitud];
    const color = equipo ? colorEstado(equipo) : '#8A8A8A';
    if (!marcador.current) {
      const elemento = document.createElement('div');
      elemento.className = 'marcador-equipo';
      elemento.style.background = color;
      marcador.current = new Marker({ element: elemento, anchor: 'center' }).setLngLat(centro).addTo(mapa);
      if (equipo) {
        marcador.current.setPopup(
          new Popup({ offset: 16, maxWidth: '280px' }).setDOMContent(contenidoPopup(equipo, posicion)),
        );
      }
    } else {
      // El marcador se conserva entre refrescos; solo se mueve y recolorea si
      // el estado de la unidad cambió.
      marcador.current.setLngLat(centro);
      marcador.current.getElement().style.background = color;
      const popup = marcador.current.getPopup();
      if (popup?.isOpen() && equipo) popup.setDOMContent(contenidoPopup(equipo, posicion));
    }
    mapa.jumpTo({ center: centro, zoom: 15 });
  }, [mapa, posicion, equipo]);

  if (consultaEquipo.isPending) return <p className="vacio">Cargando…</p>;
  if (consultaEquipo.error) return <p className="vacio">{mensajeError(consultaEquipo.error)}</p>;
  if (!equipo) return <p className="vacio">La unidad no está disponible.</p>;

  const hoy = fechaHoyLocal();
  const enlaceReplay = `/replay${consulta({ dispositivo: equipo.idPublico, desde: hoy, hasta: hoy })}`;
  const enlaceAuditoria = `/historial${consulta({ dispositivo: equipo.idPublico, desde: hoy, hasta: hoy })}`;

  return (
    <section className="pagina-detalle">
      <header className="cabecera-pagina">
        <div>
          <h1>{equipo.nombre}</h1>
          <p className="sub mono">{equipo.identificadorUnico}</p>
        </div>
        <ChipEstado dispositivo={equipo} />
        <span className="empuja" />
        <Link className="boton boton-suave con-icono" to="/en-vivo">
          <Icono nombre="enVivo" />
          Ver en el mapa
        </Link>
      </header>

      <div className="rejilla cols-2">
        <section className="bloque">
          <header className="cabecera-seccion">
            <h2>Estado</h2>
          </header>
          <dl className="ficha">
            <Dato etiqueta="ID interno">{entero(equipo.id)}</Dato>
            <Dato etiqueta="ID público">
              <span className="mono">{equipo.idPublico}</span>
            </Dato>
            <Dato etiqueta="Nombre">{equipo.nombre}</Dato>
            <Dato etiqueta="Identificador">{equipo.identificadorUnico}</Dato>
            <Dato etiqueta="Estado">
              <ChipEstado dispositivo={equipo} />
            </Dato>
            <Dato etiqueta="Habilitado">{siNo(equipo.habilitado)}</Dato>
            <Dato etiqueta="Jornada activa">{siNo(equipo.jornadaActiva)}</Dato>
            <Dato etiqueta="Versión de la app">{equipo.versionApp ?? GUION}</Dato>
            <Dato etiqueta="Última conexión">
              {equipo.ultimaConexion ? `${fechaHora(equipo.ultimaConexion)} · ${hace(equipo.ultimaConexion)}` : GUION}
            </Dato>
          </dl>
        </section>

        <section className="bloque">
          <header className="cabecera-seccion">
            <h2>Batería</h2>
          </header>
          <dl className="ficha">
            <Dato etiqueta="Nivel actual">
              <BarraBateria porcentaje={equipo.bateriaPct} cargando={equipo.cargando} />
            </Dato>
            <Dato etiqueta="Cargando">{siNo(equipo.cargando)}</Dato>
            <Dato etiqueta="Nivel en el último fix">{bateria(posicion?.bateriaPct)}</Dato>
            <Dato etiqueta="Pendientes por enviar">{entero(equipo.pendientes)}</Dato>
          </dl>
        </section>
      </div>

      <section className="bloque bloque-sep">
        <header className="cabecera-seccion">
          <h2>Última posición</h2>
          <span className="acciones">
            <Link className="boton boton-suave con-icono" to={enlaceAuditoria}>
              <Icono nombre="historial" />
              Auditoría del día
            </Link>
            <Link className="boton con-icono" to={enlaceReplay}>
              <Icono nombre="replay" />
              Replay del día
            </Link>
          </span>
        </header>
        <div className="rejilla cols-2">
          <div>
            {posicion && (
              <div className="mini-mapa">
                <MapaRaster clase="mapa" alListo={setMapa} />
              </div>
            )}
            {!posicion && (
              <p className="vacio">
                <Icono nombre="enVivo" />
                {consultaPosicion.isPending
                  ? 'Cargando posición…'
                  : consultaPosicion.error
                    ? mensajeError(consultaPosicion.error)
                    : 'La unidad no tiene posición conocida.'}
              </p>
            )}
          </div>
          <dl className="ficha">
            <Dato etiqueta="Coordenadas">{coordenadas(posicion?.latitud, posicion?.longitud)}</Dato>
            <Dato etiqueta="Altitud">{metros(posicion?.altitudM)}</Dato>
            <Dato etiqueta="Velocidad">{velocidad(posicion?.velocidadKmh)}</Dato>
            <Dato etiqueta="Rumbo">{grados(posicion?.rumboGrados)}</Dato>
            <Dato etiqueta="Precisión">{metros(posicion?.precisionM)}</Dato>
            <Dato etiqueta="Batería">{bateria(posicion?.bateriaPct)}</Dato>
            <Dato etiqueta="Registrada">
              {posicion?.registradoEn ? `${fechaHora(posicion.registradoEn)} · ${hace(posicion.registradoEn)}` : GUION}
            </Dato>
            <Dato etiqueta="Recibida">{posicion ? fechaHora(posicion.recibidoEn) : GUION}</Dato>
            <Dato etiqueta="Válida">{siNo(posicion?.valida)}</Dato>
          </dl>
        </div>
      </section>
    </section>
  );
}
