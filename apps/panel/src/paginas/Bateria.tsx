import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Battery, BatteryCharging, BatteryLow, CircleHelp, ExternalLink } from 'lucide-react';
import { CLAVE_FLOTA, equiposHabilitados, traerBateriaEquipo, traerFlota } from '@/dominio/datos';
import { BATERIA_BAJA_PCT } from '@/dominio/bitacora';
import { mensajeError } from '@/dominio/errores';
import { GUION, bateria, fechaHora } from '@/dominio/formatoBase';
import { AccionesPagina } from '@/componentes/marco/Marco';
import { Cifra } from '@/componentes/ui/Cifra';
import { Segmentado } from '@/componentes/ui/Segmentado';
import { Tarjeta, CabeceraTarjeta } from '@/componentes/ui/Tarjeta';
import { Cargando, ErrorCarga, Esqueleto, Vacio } from '@/componentes/ui/Estados';
import { claseBoton } from '@/componentes/ui/Boton';
import { CurvaBateria } from '@/componentes/bateria/CurvaBateria';
import { TarjetaPersona } from '@/componentes/bateria/TarjetaPersona';
import { calcularTendencia } from '@/componentes/bateria/nivel';

// Igual que el panel anterior: 60 s de gracia antes de releer la flota al navegar.
const CACHE_FLOTA_MS = 60_000;

const RANGOS = [
  { valor: '24', etiqueta: '24 h' },
  { valor: '168', etiqueta: '7 días' },
  { valor: '720', etiqueta: '30 días' },
] as const;
type Horas = (typeof RANGOS)[number]['valor'];

function rangoDeHoras(horas: number) {
  const hasta = new Date();
  return { desde: new Date(hasta.getTime() - horas * 3_600_000).toISOString(), hasta: hasta.toISOString() };
}

export default function Bateria() {
  const flotaQ = useQuery({ queryKey: CLAVE_FLOTA, queryFn: () => traerFlota(), staleTime: CACHE_FLOTA_MS });
  const [horas, setHoras] = useState<Horas>('24');
  const [rango, setRango] = useState(() => rangoDeHoras(24));
  const [seleccionManual, setSeleccionManual] = useState('');

  // Menor carga primero; sin lectura (null) al final, nunca se lee como 0.
  const flota = useMemo(() => {
    const datos = equiposHabilitados(flotaQ.data?.datos ?? []);
    return [...datos].sort((a, b) => {
      if (a.bateriaPct == null && b.bateriaPct == null) return a.nombre.localeCompare(b.nombre, 'es');
      if (a.bateriaPct == null) return 1;
      if (b.bateriaPct == null) return -1;
      return a.bateriaPct - b.bateriaPct;
    });
  }, [flotaQ.data]);

  // La selección efectiva se deriva: manda la elección y, si no hay, la más crítica.
  const seleccion = flota.some((d) => d.idPublico === seleccionManual) ? seleccionManual : (flota[0]?.idPublico ?? '');
  const persona = flota.find((d) => d.idPublico === seleccion) ?? null;

  const serie = useQuery({
    queryKey: ['bateria', seleccion, rango.desde, rango.hasta],
    enabled: seleccion !== '',
    queryFn: () => traerBateriaEquipo(seleccion, rango.desde, rango.hasta),
  });

  const muestras = useMemo(() => serie.data?.muestras ?? [], [serie.data]);
  const valores = muestras.flatMap((m) => (m.bateriaPct == null ? [] : [m.bateriaPct]));
  const minima = valores.length > 0 ? Math.min(...valores) : null;
  const tendencia = useMemo(() => calcularTendencia(muestras), [muestras]);
  const textoTendencia = !tendencia
    ? GUION
    : tendencia.direccion === 'estable'
      ? 'Estable'
      : `${tendencia.direccion === 'sube' ? 'Sube' : 'Baja'} ${Math.abs(tendencia.tasaPctHora).toFixed(1)} %/h`;

  const conDato = flota.filter((d) => d.bateriaPct != null);
  const promedio = conDato.length > 0 ? conDato.reduce((t, d) => t + (d.bateriaPct ?? 0), 0) / conDato.length : null;
  const enRiesgo = conDato.filter((d) => (d.bateriaPct ?? 100) <= BATERIA_BAJA_PCT).length;
  const cargando = flota.filter((d) => d.cargando).length;
  const sinDato = flota.length - conDato.length;

  return (
    <div className="space-y-5">
      <AccionesPagina>
        <Segmentado
          opciones={RANGOS}
          valor={horas}
          alCambiar={(v) => {
            setHoras(v);
            setRango(rangoDeHoras(Number(v)));
          }}
        />
      </AccionesPagina>

      <div className="grid grid-cols-2 gap-3 xl:grid-cols-4">
        <Cifra etiqueta="Promedio de la flota" valor={promedio == null ? GUION : `${Math.round(promedio)}%`} icono={Battery} tono="marino" />
        <Cifra
          etiqueta={`En riesgo (≤${BATERIA_BAJA_PCT}%)`}
          valor={enRiesgo}
          icono={BatteryLow}
          tono="peligro"
          resaltar={enRiesgo > 0}
        />
        <Cifra etiqueta="Cargando" valor={cargando} icono={BatteryCharging} tono="movimiento" />
        <Cifra etiqueta="Sin dato" valor={sinDato} icono={CircleHelp} tono="neutro" />
      </div>

      {flotaQ.isPending && (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {Array.from({ length: 4 }, (_, i) => (
            <Esqueleto key={i} className="h-36" />
          ))}
        </div>
      )}
      {flotaQ.error && <ErrorCarga mensaje={mensajeError(flotaQ.error)} alReintentar={() => void flotaQ.refetch()} />}
      {flotaQ.data && flota.length === 0 && (
        <Tarjeta>
          <Vacio icono={Battery} titulo="No hay personas en la flota" />
        </Tarjeta>
      )}
      {flota.length > 0 && (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4">
          {flota.map((d) => (
            <TarjetaPersona
              key={d.idPublico}
              equipo={d}
              seleccionada={d.idPublico === seleccion}
              alSeleccionar={() => setSeleccionManual(d.idPublico)}
            />
          ))}
        </div>
      )}

      {persona && (
        <Tarjeta>
          <CabeceraTarjeta
            titulo={persona.nombre}
            detalle={persona.identificadorUnico}
            acciones={
              <Link to={`/unidad/${persona.idPublico}`} className={claseBoton('secundario', 'sm')}>
                <ExternalLink className="size-3.5" /> Expediente
              </Link>
            }
          />
          <div className="grid grid-cols-2 gap-4 px-5 pb-4 sm:grid-cols-4">
            <Cifra etiqueta="Actual" valor={bateria(serie.data?.actual)} />
            <Cifra etiqueta="Mínima del rango" valor={bateria(minima)} />
            <Cifra etiqueta="Lecturas" valor={serie.data ? muestras.length : GUION} />
            <Cifra etiqueta="Tendencia" valor={<span className="text-[18px]">{textoTendencia}</span>} />
          </div>
          <div className="px-5 pb-5">
            {serie.isPending && <Cargando texto="Cargando historial…" />}
            {serie.error && <ErrorCarga mensaje={mensajeError(serie.error)} alReintentar={() => void serie.refetch()} />}
            {serie.data && muestras.length === 0 && (
              <Vacio icono={Battery} titulo="Sin lecturas">No hay lecturas de batería en el rango.</Vacio>
            )}
            {serie.data && muestras.length > 0 && valores.length === 0 && (
              <Vacio icono={Battery} titulo="Sin porcentaje">Las lecturas del rango no traen porcentaje de batería.</Vacio>
            )}
            {valores.length > 0 && (
              <>
                <p className="mb-2 text-[11.5px] text-texto-3">
                  {fechaHora(rango.desde)} — {fechaHora(rango.hasta)}
                </p>
                <CurvaBateria muestras={muestras} />
              </>
            )}
          </div>
        </Tarjeta>
      )}
    </div>
  );
}
