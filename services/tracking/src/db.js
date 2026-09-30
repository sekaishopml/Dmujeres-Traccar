// Acceso a la base nueva (dmt-db / dmujeres). Este servicio escribe
// exclusivamente en los esquemas tracking, operations y telemetry. No toca la
// base de produccion (traccar) ni ningun servicio dmj-*.

import pg from 'pg';

const { Pool } = pg;

const MAX_CONEXIONES = 10;

export function opcionesPool(configuracion) {
  if (configuracion.bd.url) {
    return { connectionString: configuracion.bd.url, max: MAX_CONEXIONES };
  }
  return {
    host: configuracion.bd.host,
    port: configuracion.bd.puerto,
    database: configuracion.bd.base,
    user: configuracion.bd.usuario,
    password: configuracion.bd.clave,
    max: MAX_CONEXIONES,
  };
}

function dosDigitos(numero) {
  return String(numero).padStart(2, '0');
}

function nombreParticion(fecha) {
  return `dmt_posicion_${fecha.getUTCFullYear()}_${dosDigitos(fecha.getUTCMonth() + 1)}`;
}

function nombreParticionEvento(fecha) {
  return `dmt_evento_${fecha.getUTCFullYear()}_${dosDigitos(fecha.getUTCMonth() + 1)}`;
}

function limitesMesUtc(fecha) {
  const anio = fecha.getUTCFullYear();
  const mes = fecha.getUTCMonth() + 1;
  const inicio = `${anio}-${dosDigitos(mes)}-01 00:00:00+00`;
  const siguienteAnio = mes === 12 ? anio + 1 : anio;
  const siguienteMes = mes === 12 ? 1 : mes + 1;
  const fin = `${siguienteAnio}-${dosDigitos(siguienteMes)}-01 00:00:00+00`;
  return { inicio, fin };
}

// Ventana de captura aceptada (ADR-010): [ahora−30d, ahora+24h]. Fuera de
// ella el fix viene con reloj corrupto (caso 2037_10 en producción) y no se
// guarda: se clasifica como `invalid` para drenar el buffer sin polucionar
// particiones.
const VENTANA_CAPTURA_MS_ATRAS = 30 * 24 * 60 * 60 * 1000;
const VENTANA_CAPTURA_MS_ADELANTE = 24 * 60 * 60 * 1000;

export function fechaCapturaValida(fecha, ahoraMs = Date.now()) {
  if (!(fecha instanceof Date) || Number.isNaN(fecha.getTime())) return false;
  const instante = fecha.getTime();
  return (
    instante >= ahoraMs - VENTANA_CAPTURA_MS_ATRAS &&
    instante <= ahoraMs + VENTANA_CAPTURA_MS_ADELANTE
  );
}

export class Almacen {
  #pool;
  #particiones = new Set();
  #particionesEvento = new Set();
  #log;
  #tieneIdempotencia = null;

  constructor(pool, log) {
    this.#pool = pool;
    this.#log = log;
  }

  get pool() {
    return this.#pool;
  }

  // Detecta si la migración 002 ya aplicó (columnas boot_id/local_sequence).
  // La migración NO se aplica en esta fase: el código funciona sin ella
  // (acepta sin dedupe) y con ella (dedupe por UNIQUE parcial).
  async #soportaIdempotencia(conexion) {
    if (this.#tieneIdempotencia !== null) return this.#tieneIdempotencia;
    const ejecutor = conexion ?? this.#pool;
    try {
      const resultado = await ejecutor.query(
        `SELECT count(*)::int AS total
           FROM information_schema.columns
          WHERE table_schema = 'tracking'
            AND table_name = 'dmt_posicion'
            AND column_name IN ('boot_id', 'local_sequence')`,
      );
      this.#tieneIdempotencia = Number(resultado.rows[0]?.total) === 2;
    } catch {
      // Sin permiso de lectura del esquema se asume sin columnas: no se rompe
      // la ingesta, solo se pierde el dedupe hasta la migración.
      this.#tieneIdempotencia = false;
    }
    if (!this.#tieneIdempotencia) {
      this.#log?.warn?.('idempotencia sin columnas boot_id/local_sequence; dedupe desactivado hasta migración 002');
    }
    return this.#tieneIdempotencia;
  }

  async buscarDispositivo(identificador) {
    const resultado = await this.#pool.query(
      `SELECT id, identificador, estado, habilitado, atributos
         FROM tracking.dmt_dispositivo
        WHERE lower(identificador) = lower($1)
        LIMIT 1`,
      [identificador],
    );
    const fila = resultado.rows[0];
    if (!fila) return null;
    return {
      id: String(fila.id),
      identificador: fila.identificador,
      estado: fila.estado,
      habilitado: fila.habilitado !== false,
      atributos: fila.atributos ?? {},
    };
  }

  // Garantiza la particion mensual de dmt_posicion para la fecha indicada. El
  // esquema crea 2026-09 y 2026-10; los meses siguientes se crean aqui (misma
  // plantilla documentada en database/schema/03_tracking.sql) para que ninguna
  // escritura quede sin particion. Se recuerda por mes en memoria.
  async #asegurarParticion(conexion, fecha) {
    const nombre = nombreParticion(fecha);
    if (this.#particiones.has(nombre)) return;
    const { inicio, fin } = limitesMesUtc(fecha);
    await conexion.query(
      `CREATE TABLE IF NOT EXISTS tracking.${nombre}
         PARTITION OF tracking.dmt_posicion
         FOR VALUES FROM ('${inicio}') TO ('${fin}')`,
    );
    this.#particiones.add(nombre);
    this.#log?.info(`particion ${nombre} verificada`);
  }

  // Misma garantia para dmt_evento (particionada por ocurrido_en): el esquema
  // trae 2026-09 y 2026-10; los meses siguientes se crean aqui con la misma
  // plantilla de 03_tracking.sql para que ningun evento quede sin particion.
  async #asegurarParticionEvento(conexion, fecha) {
    const nombre = nombreParticionEvento(fecha);
    if (this.#particionesEvento.has(nombre)) return;
    const { inicio, fin } = limitesMesUtc(fecha);
    await conexion.query(
      `CREATE TABLE IF NOT EXISTS tracking.${nombre}
         PARTITION OF tracking.dmt_evento
         FOR VALUES FROM ('${inicio}') TO ('${fin}')`,
    );
    this.#particionesEvento.add(nombre);
    this.#log?.info(`particion ${nombre} verificada`);
  }

  // Inserta un evento de jornada sin duplicar: la clave es (dispositivo, tipo,
  // atributos->>'journeyId'), por lo que un reintento del cliente con el mismo
  // journeyId no genera otra fila. Se ejecuta dentro de la transaccion de la
  // jornada para que evento y jornada queden consistentes. El SELECT previo
  // evita el duplicado y el ON CONFLICT DO NOTHING cubre la carrera entre dos
  // peticiones simultáneas (sin UNIQUE dedicado, el bare DO NOTHING no falla).
  async #insertarEvento(conexion, { dispositivoId, tipo, journeyId, bateria, ocurridoEn = null }) {
    if (journeyId === null || journeyId === undefined) return false;
    const existente = await conexion.query(
      `SELECT 1
         FROM tracking.dmt_evento
        WHERE dispositivo_id = $1
          AND tipo = $2
          AND atributos->>'journeyId' = $3
        LIMIT 1`,
      [dispositivoId, tipo, String(journeyId)],
    );
    if (existente.rowCount > 0) return false;
    const marca = ocurridoEn ?? (await conexion.query('SELECT now() AS ahora')).rows[0].ahora;
    await this.#asegurarParticionEvento(conexion, marca);
    const atributos = { journeyId, mobileSeverity: 'info' };
    if (typeof bateria === 'number' && Number.isFinite(bateria)) atributos.battery = bateria;
    const insertado = await conexion.query(
      `INSERT INTO tracking.dmt_evento (dispositivo_id, tipo, ocurrido_en, atributos)
       VALUES ($1, $2, $3, $4::jsonb)
       ON CONFLICT DO NOTHING
       RETURNING id`,
      [dispositivoId, tipo, marca, JSON.stringify(atributos)],
    );
    return insertado.rowCount > 0;
  }

  async #fusionarAtributos(conexion, dispositivoId, parche) {
    await conexion.query(
      `UPDATE tracking.dmt_dispositivo
          SET atributos = atributos || $2::jsonb,
              actualizado_en = now()
        WHERE id = $1`,
      [dispositivoId, JSON.stringify(parche)],
    );
  }

  async fusionarAtributosDispositivo(dispositivoId, parche) {
    await this.#pool.query(
      `UPDATE tracking.dmt_dispositivo
          SET atributos = atributos || $2::jsonb,
              actualizado_en = now()
        WHERE id = $1`,
      [dispositivoId, JSON.stringify(parche)],
    );
  }

  // Inserta una posición con idempotencia (device, boot, seq) y guarda de
  // posición viva: un paquete atrasado nunca retrocede dmt_posicion_actual ni
  // ultima_conexion_en. Devuelve {posicionId} | {duplicado:true} |
  // {invalido:true}. La fecha fuera de [ahora−30d, ahora+24h] se rechaza sin
  // tocar la base (evita la partición 2037_10).
  async registrarPosicion(dispositivoId, posicion) {
    if (!fechaCapturaValida(posicion.registradoEn)) {
      return { invalido: true };
    }
    const conexion = await this.#pool.connect();
    try {
      await conexion.query('BEGIN');
      await this.#asegurarParticion(conexion, posicion.registradoEn);
      const usaIdempotencia = await this.#soportaIdempotencia(conexion);
      const bootId = typeof posicion.bootId === 'string' && posicion.bootId.trim() !== ''
        ? posicion.bootId.trim().slice(0, 64)
        : null;
      const secuencia = Number.isInteger(posicion.secuencia) && posicion.secuencia >= 0
        ? posicion.secuencia
        : (typeof posicion.seq === 'number' && Number.isInteger(posicion.seq) && posicion.seq >= 0
          ? posicion.seq
          : null);
      let insertada;
      if (usaIdempotencia) {
        insertada = await conexion.query(
          `INSERT INTO tracking.dmt_posicion (
             dispositivo_id, protocolo, latitud, longitud, altitud_m,
             velocidad_kmh, rumbo_grados, precision_m, bateria_pct, valida,
             fijado_en, registrado_en, atributos, boot_id, local_sequence
           ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $11, $12::jsonb, $13, $14)
           ON CONFLICT DO NOTHING
           RETURNING id`,
          [
            dispositivoId,
            posicion.protocolo,
            posicion.latitud,
            posicion.longitud,
            posicion.altitud,
            posicion.velocidadKmh,
            posicion.rumbo,
            posicion.precision,
            posicion.bateria,
            posicion.valida,
            posicion.registradoEn,
            JSON.stringify(posicion.atributos ?? {}),
            bootId,
            secuencia,
          ],
        );
      } else {
        insertada = await conexion.query(
          `INSERT INTO tracking.dmt_posicion (
             dispositivo_id, protocolo, latitud, longitud, altitud_m,
             velocidad_kmh, rumbo_grados, precision_m, bateria_pct, valida,
             fijado_en, registrado_en, atributos
           ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $11, $12::jsonb)
           RETURNING id`,
          [
            dispositivoId,
            posicion.protocolo,
            posicion.latitud,
            posicion.longitud,
            posicion.altitud,
            posicion.velocidadKmh,
            posicion.rumbo,
            posicion.precision,
            posicion.bateria,
            posicion.valida,
            posicion.registradoEn,
            JSON.stringify(posicion.atributos ?? {}),
          ],
        );
      }
      if (insertada.rowCount === 0) {
        await conexion.query('ROLLBACK');
        return { duplicado: true };
      }
      const posicionId = String(insertada.rows[0].id);
      // Solo avanza: un fix viejo no pisa la posición viva (corrige el bug de
      // retroceso confirmado en db.js:191-222).
      await conexion.query(
        `INSERT INTO tracking.dmt_posicion_actual (
           dispositivo_id, posicion_id, latitud, longitud, altitud_m,
           velocidad_kmh, rumbo_grados, precision_m, bateria_pct, valida,
           fijado_en, registrado_en, atributos
         ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $11, $12::jsonb)
         ON CONFLICT (dispositivo_id) DO UPDATE SET
           posicion_id = EXCLUDED.posicion_id,
           latitud = EXCLUDED.latitud,
           longitud = EXCLUDED.longitud,
           altitud_m = EXCLUDED.altitud_m,
           velocidad_kmh = EXCLUDED.velocidad_kmh,
           rumbo_grados = EXCLUDED.rumbo_grados,
           precision_m = EXCLUDED.precision_m,
           bateria_pct = EXCLUDED.bateria_pct,
           valida = EXCLUDED.valida,
           fijado_en = EXCLUDED.fijado_en,
           registrado_en = EXCLUDED.registrado_en,
           atributos = EXCLUDED.atributos,
           recibido_en = now(),
           actualizado_en = now()
         WHERE EXCLUDED.registrado_en > tracking.dmt_posicion_actual.registrado_en`,
        [
          dispositivoId,
          posicionId,
          posicion.latitud,
          posicion.longitud,
          posicion.altitud,
          posicion.velocidadKmh,
          posicion.rumbo,
          posicion.precision,
          posicion.bateria,
          posicion.valida,
          posicion.registradoEn,
          JSON.stringify(posicion.atributos ?? {}),
        ],
      );
      // Solo avanza: ultima_conexion_en/ultima_posicion_id no retroceden con
      // paquetes atrasados (GREATEST + guarda por tiempo).
      await conexion.query(
        `UPDATE tracking.dmt_dispositivo
            SET estado = 'online',
                ultima_conexion_en = GREATEST(coalesce(ultima_conexion_en, $2), $2),
                ultima_posicion_id = CASE
                  WHEN ultima_conexion_en IS NULL OR $2 > ultima_conexion_en THEN $3
                  ELSE ultima_posicion_id
                END,
                actualizado_en = now()
          WHERE id = $1
            AND (ultima_conexion_en IS NULL OR $2 >= ultima_conexion_en)`,
        [dispositivoId, posicion.registradoEn, posicionId],
      );
      await conexion.query('COMMIT');
      return { posicionId };
    } catch (error) {
      await conexion.query('ROLLBACK').catch(() => {});
      throw error;
    } finally {
      conexion.release();
    }
  }

  // Lote idempotente en una sola transacción: valida fechas, inserta con
  // ON CONFLICT DO NOTHING y avanza la posición viva solo con el fix más
  // nuevo del lote. Devuelve por evento {seq, estado} con
  // accepted|duplicate|invalid (dead lo decide el llamador por dispositivo
  // deshabilitado). No lanza por eventos sueltos: los clasifica.
  async registrarLotePosiciones(dispositivoId, posiciones) {
    const resultados = [];
    const conexion = await this.#pool.connect();
    try {
      await conexion.query('BEGIN');
      const usaIdempotencia = await this.#soportaIdempotencia(conexion);
      let mejorPosicionId = null;
      let mejorRegistradoEn = null;
      let mejorPosicion = null;
      for (const posicion of posiciones) {
        const seqEco = Number.isInteger(posicion.secuencia) ? posicion.secuencia
          : (Number.isInteger(posicion.seq) ? posicion.seq : null);
        if (!fechaCapturaValida(posicion.registradoEn)) {
          resultados.push({ seq: seqEco, estado: 'invalid' });
          continue;
        }
        await this.#asegurarParticion(conexion, posicion.registradoEn);
        const bootId = typeof posicion.bootId === 'string' && posicion.bootId.trim() !== ''
          ? posicion.bootId.trim().slice(0, 64)
          : null;
        const secuencia = Number.isInteger(posicion.secuencia) && posicion.secuencia >= 0
          ? posicion.secuencia
          : (Number.isInteger(posicion.seq) && posicion.seq >= 0 ? posicion.seq : null);
        let insertada;
        if (usaIdempotencia) {
          insertada = await conexion.query(
            `INSERT INTO tracking.dmt_posicion (
               dispositivo_id, protocolo, latitud, longitud, altitud_m,
               velocidad_kmh, rumbo_grados, precision_m, bateria_pct, valida,
               fijado_en, registrado_en, atributos, boot_id, local_sequence
             ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $11, $12::jsonb, $13, $14)
             ON CONFLICT DO NOTHING
             RETURNING id`,
            [
              dispositivoId,
              posicion.protocolo ?? 'lote',
              posicion.latitud,
              posicion.longitud,
              posicion.altitud,
              posicion.velocidadKmh,
              posicion.rumbo,
              posicion.precision,
              posicion.bateria,
              posicion.valida ?? true,
              posicion.registradoEn,
              JSON.stringify(posicion.atributos ?? {}),
              bootId,
              secuencia,
            ],
          );
        } else {
          insertada = await conexion.query(
            `INSERT INTO tracking.dmt_posicion (
               dispositivo_id, protocolo, latitud, longitud, altitud_m,
               velocidad_kmh, rumbo_grados, precision_m, bateria_pct, valida,
               fijado_en, registrado_en, atributos
             ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $11, $12::jsonb)
             RETURNING id`,
            [
              dispositivoId,
              posicion.protocolo ?? 'lote',
              posicion.latitud,
              posicion.longitud,
              posicion.altitud,
              posicion.velocidadKmh,
              posicion.rumbo,
              posicion.precision,
              posicion.bateria,
              posicion.valida ?? true,
              posicion.registradoEn,
              JSON.stringify(posicion.atributos ?? {}),
            ],
          );
        }
        if (insertada.rowCount === 0) {
          resultados.push({ seq: seqEco, estado: 'duplicate' });
          continue;
        }
        const posicionId = String(insertada.rows[0].id);
        resultados.push({ seq: seqEco, estado: 'accepted' });
        if (mejorRegistradoEn === null || posicion.registradoEn > mejorRegistradoEn) {
          mejorRegistradoEn = posicion.registradoEn;
          mejorPosicionId = posicionId;
          mejorPosicion = posicion;
        }
      }
      // La posición viva y el dispositivo solo avanzan con el fix más nuevo
      // del lote aceptado (nunca con un lote entero atrasado).
      if (mejorPosicion !== null) {
        await conexion.query(
          `INSERT INTO tracking.dmt_posicion_actual (
             dispositivo_id, posicion_id, latitud, longitud, altitud_m,
             velocidad_kmh, rumbo_grados, precision_m, bateria_pct, valida,
             fijado_en, registrado_en, atributos
           ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $11, $12::jsonb)
           ON CONFLICT (dispositivo_id) DO UPDATE SET
             posicion_id = EXCLUDED.posicion_id,
             latitud = EXCLUDED.latitud,
             longitud = EXCLUDED.longitud,
             altitud_m = EXCLUDED.altitud_m,
             velocidad_kmh = EXCLUDED.velocidad_kmh,
             rumbo_grados = EXCLUDED.rumbo_grados,
             precision_m = EXCLUDED.precision_m,
             bateria_pct = EXCLUDED.bateria_pct,
             valida = EXCLUDED.valida,
             fijado_en = EXCLUDED.fijado_en,
             registrado_en = EXCLUDED.registrado_en,
             atributos = EXCLUDED.atributos,
             recibido_en = now(),
             actualizado_en = now()
           WHERE EXCLUDED.registrado_en > tracking.dmt_posicion_actual.registrado_en`,
          [
            dispositivoId,
            mejorPosicionId,
            mejorPosicion.latitud,
            mejorPosicion.longitud,
            mejorPosicion.altitud,
            mejorPosicion.velocidadKmh,
            mejorPosicion.rumbo,
            mejorPosicion.precision,
            mejorPosicion.bateria,
            mejorPosicion.valida ?? true,
            mejorPosicion.registradoEn,
            JSON.stringify(mejorPosicion.atributos ?? {}),
          ],
        );
        await conexion.query(
          `UPDATE tracking.dmt_dispositivo
              SET estado = 'online',
                  ultima_conexion_en = GREATEST(coalesce(ultima_conexion_en, $2), $2),
                  ultima_posicion_id = CASE
                    WHEN ultima_conexion_en IS NULL OR $2 > ultima_conexion_en THEN $3
                    ELSE ultima_posicion_id
                  END,
                  actualizado_en = now()
            WHERE id = $1
              AND (ultima_conexion_en IS NULL OR $2 >= ultima_conexion_en)`,
          [dispositivoId, mejorRegistradoEn, mejorPosicionId],
        );
      }
      await conexion.query('COMMIT');
      return resultados;
    } catch (error) {
      await conexion.query('ROLLBACK').catch(() => {});
      throw error;
    } finally {
      conexion.release();
    }
  }

  // Estado de jornada del servidor para reconciliación del cliente tras
  // recrear proceso/reboot. Lee operations.dmt_jornada (verdad del servidor),
  // no la RAM del teléfono. Sin filas → ninguna.
  async obtenerJornadaEstado(dispositivoId) {
    const resultado = await this.#pool.query(
      `SELECT estado, inicio_en, atributos
         FROM operations.dmt_jornada
        WHERE dispositivo_id = $1
        ORDER BY inicio_en DESC, id DESC
        LIMIT 1`,
      [dispositivoId],
    );
    const fila = resultado.rows[0];
    if (!fila) return { estado: 'ninguna', journeyId: null, inicioEn: null };
    const attrs = fila.atributos ?? {};
    const crudo = attrs.journeyId ?? attrs.journey_id ?? attrs.journey_id_legado ?? null;
    const journeyId = crudo === null || crudo === undefined ? null : Number(crudo);
    const inicioEn = fila.inicio_en instanceof Date ? fila.inicio_en.toISOString() : new Date(fila.inicio_en).toISOString();
    if (fila.estado === 'abierta') {
      return {
        estado: 'abierta',
        journeyId: Number.isFinite(journeyId) ? journeyId : null,
        inicioEn,
      };
    }
    return {
      estado: 'cerrada',
      journeyId: Number.isFinite(journeyId) ? journeyId : null,
      inicioEn,
    };
  }

  // Precreación proactiva (ADR-010): mes actual + 2 siguientes al arrancar
  // (y en job diario) para que ninguna escritura quede sin partición. La
  // creación on-write se conserva como red de seguridad.
  async precrearParticionesProximas(ahora = new Date()) {
    const conexion = await this.#pool.connect();
    try {
      const base = new Date(Date.UTC(ahora.getUTCFullYear(), ahora.getUTCMonth(), 1));
      for (let desplazamiento = 0; desplazamiento < 3; desplazamiento += 1) {
        const fecha = new Date(Date.UTC(base.getUTCFullYear(), base.getUTCMonth() + desplazamiento, 1));
        await this.#asegurarParticion(conexion, fecha);
        await this.#asegurarParticionEvento(conexion, fecha);
      }
    } finally {
      conexion.release();
    }
  }

  // inicioEn: hora real del toque en el teléfono (la app reintenta el aviso
  // hasta que llega; sin esto una jornada iniciada sin señal quedaba con la
  // hora del reintento). Idempotente por journeyId: un reintento del mismo
  // inicio no cierra ni duplica la jornada ya abierta.
  async abrirJornada({ dispositivoId, journeyId, bateriaInicio, atributosJornada, parcheDispositivo, inicioEn = new Date() }) {
    const conexion = await this.#pool.connect();
    try {
      await conexion.query('BEGIN');
      if (journeyId != null) {
        const existente = await conexion.query(
          `SELECT id FROM operations.dmt_jornada
            WHERE dispositivo_id = $1 AND atributos->>'journeyId' = $2::text
            ORDER BY inicio_en DESC LIMIT 1`,
          [dispositivoId, String(journeyId)],
        );
        if (existente.rows.length > 0) {
          await conexion.query('COMMIT');
          return String(existente.rows[0].id);
        }
      }
      await conexion.query(
        `UPDATE operations.dmt_jornada
            SET estado = 'cerrada',
                fin_en = GREATEST($2::timestamptz, inicio_en),
                duracion_s = GREATEST(0, EXTRACT(EPOCH FROM ($2::timestamptz - inicio_en))::bigint),
                actualizado_en = now()
          WHERE dispositivo_id = $1 AND estado = 'abierta'`,
        [dispositivoId, inicioEn],
      );
      const insertada = await conexion.query(
        `INSERT INTO operations.dmt_jornada (
           dispositivo_id, usuario_id, inicio_en, estado, bateria_inicio_pct, atributos
         ) VALUES (
           $1,
           (SELECT usuario_id
              FROM operations.dmt_asignacion
             WHERE dispositivo_id = $1
               AND activa
               AND (hasta_en IS NULL OR hasta_en > now())
             ORDER BY desde_en DESC
             LIMIT 1),
           $4, 'abierta', $2, $3::jsonb
         )
         RETURNING id`,
        [dispositivoId, bateriaInicio, JSON.stringify(atributosJornada), inicioEn],
      );
      await this.#insertarEvento(conexion, {
        dispositivoId,
        tipo: 'mobileJourneyStarted',
        journeyId: journeyId ?? atributosJornada?.journeyId ?? null,
        bateria: bateriaInicio,
        ocurridoEn: inicioEn,
      });
      await this.#fusionarAtributos(conexion, dispositivoId, parcheDispositivo);
      await conexion.query('COMMIT');
      return String(insertada.rows[0].id);
    } catch (error) {
      await conexion.query('ROLLBACK').catch(() => {});
      throw error;
    } finally {
      conexion.release();
    }
  }

  // Apagado/encendido del teléfono informado por la app (con la hora real y
  // la causa). Idempotente por (tipo, clave) para los reintentos.
  async registrarEventoEnergia({ dispositivoId, tipo, ocurridoEn, atributos }) {
    const conexion = await this.#pool.connect();
    try {
      const clave = String(atributos.clave);
      const existente = await conexion.query(
        `SELECT 1 FROM tracking.dmt_evento WHERE dispositivo_id = $1 AND tipo = $2 AND atributos->>'clave' = $3 LIMIT 1`,
        [dispositivoId, tipo, clave],
      );
      if (existente.rowCount > 0) return false;
      await this.#asegurarParticionEvento(conexion, ocurridoEn);
      await conexion.query(
        `INSERT INTO tracking.dmt_evento (dispositivo_id, tipo, ocurrido_en, atributos)
         VALUES ($1, $2, $3, $4::jsonb) ON CONFLICT DO NOTHING`,
        [dispositivoId, tipo, ocurridoEn, JSON.stringify(atributos)],
      );
      return true;
    } finally {
      conexion.release();
    }
  }

  async cerrarJornada({ dispositivoId, journeyId, finEn, bateriaFin, parcheDispositivo }) {
    const conexion = await this.#pool.connect();
    try {
      await conexion.query('BEGIN');
      const cerradas = await conexion.query(
        `UPDATE operations.dmt_jornada
            SET estado = 'cerrada',
                fin_en = $2,
                duracion_s = GREATEST(0, EXTRACT(EPOCH FROM ($2 - inicio_en))::bigint),
                bateria_fin_pct = $3,
                actualizado_en = now()
          WHERE dispositivo_id = $1 AND estado = 'abierta'`,
        [dispositivoId, finEn, bateriaFin],
      );
      // Un reintento del mismo cierre no encuentra jornada abierta: no se
      // repite el evento.
      if (cerradas.rowCount > 0) {
        await this.#insertarEvento(conexion, {
          dispositivoId,
          tipo: 'mobileJourneyEnded',
          journeyId: journeyId ?? null,
          bateria: bateriaFin,
          ocurridoEn: finEn,
        });
      }
      await this.#fusionarAtributos(conexion, dispositivoId, parcheDispositivo);
      await conexion.query('COMMIT');
      return cerradas.rowCount;
    } catch (error) {
      await conexion.query('ROLLBACK').catch(() => {});
      throw error;
    } finally {
      conexion.release();
    }
  }

  async registrarDiagnostico({ dispositivoId, parcheDispositivo, bateria }) {
    const conexion = await this.#pool.connect();
    try {
      await conexion.query('BEGIN');
      await this.#fusionarAtributos(conexion, dispositivoId, parcheDispositivo);
      if (bateria) {
        await conexion.query(
          `INSERT INTO telemetry.dmt_bateria (
             dispositivo_id, porcentaje, cargando, registrado_en, atributos
           ) VALUES ($1, $2, $3, $4, $5::jsonb)`,
          [
            dispositivoId,
            bateria.porcentaje,
            bateria.cargando,
            bateria.registradoEn,
            JSON.stringify({ origen: 'movil.diagnostics' }),
          ],
        );
      }
      await conexion.query('COMMIT');
    } catch (error) {
      await conexion.query('ROLLBACK').catch(() => {});
      throw error;
    } finally {
      conexion.release();
    }
  }

  // Token FCM completo en iam.dmt_token_fcm. Upsert por token (UNIQUE): un
  // re-registro del mismo token actualiza dispositivo/estado y ultimo_uso_en.
  // No se desactivan otros tokens del equipo: FCM puede tener varias
  // instalaciones validas y el envio real llega en una fase siguiente.
  async guardarTokenFcm({ dispositivoId, token }) {
    const resultado = await this.#pool.query(
      `INSERT INTO iam.dmt_token_fcm (
         dispositivo_id, token, activo, invalido, ultimo_uso_en, actualizado_en
       ) VALUES ($1, $2, TRUE, FALSE, now(), now())
       ON CONFLICT (token) DO UPDATE SET
         dispositivo_id = EXCLUDED.dispositivo_id,
         activo = TRUE,
         invalido = FALSE,
         ultimo_uso_en = now(),
         actualizado_en = now()
       RETURNING id`,
      [dispositivoId, token],
    );
    return String(resultado.rows[0].id);
  }

  // Ack de recuperacion en operations.dmt_alerta (origen 'recuperacion'): el
  // detalle variable (attemptId, stage, priority, reason) vive en atributos.
  async registrarAlertaRecuperacion({ dispositivoId, atributos }) {
    const resultado = await this.#pool.query(
      `INSERT INTO operations.dmt_alerta (
         origen, dispositivo_id, tipo, severidad, estado, ocurrido_en, atributos
       ) VALUES ('recuperacion', $1, 'recovery_ack', 'media', 'nueva', now(), $2::jsonb)
       RETURNING id`,
      [dispositivoId, JSON.stringify(atributos)],
    );
    return String(resultado.rows[0].id);
  }

  /**
   * Reconcilia jornadas al arrancar (y cada hora):
   *  1. Crea la jornada abierta de los equipos que la iniciaron antes del corte
   *     o mientras el servidor estaba caído. El journeyId de la App es el epoch
   *     ms del inicio, así que la hora de arranque no se inventa.
   *  2. Cierra las jornadas abiertas con más de `horasTimeout` sin actividad
   *     (posiciones ni conexión), como hacía el monitor anterior.
   * Devuelve cuántas creó y cuántas cerró.
   */
  async reconciliarJornadas({ horasTimeout = 12 } = {}) {
    const creadas = await this.#pool.query(
      `INSERT INTO operations.dmt_jornada (dispositivo_id, usuario_id, inicio_en, estado, atributos)
       SELECT d.id,
              (SELECT a.usuario_id
                 FROM operations.dmt_asignacion a
                WHERE a.dispositivo_id = d.id AND a.activa
                  AND (a.hasta_en IS NULL OR a.hasta_en > now())
                ORDER BY a.desde_en DESC
                LIMIT 1),
              to_timestamp(((d.atributos ->> 'mobile.journeyId')::bigint) / 1000.0),
              'abierta',
              jsonb_build_object('origen', 'reconciliacion',
                                 'journeyId', (d.atributos ->> 'mobile.journeyId')::bigint)
         FROM tracking.dmt_dispositivo d
        WHERE d.habilitado
          AND (d.atributos ->> 'mobile.journeyId') ~ '^[0-9]+$'
          AND (d.atributos ->> 'mobile.journeyId')::bigint > 0
          AND NOT EXISTS (
                SELECT 1 FROM operations.dmt_jornada j
                 WHERE j.dispositivo_id = d.id AND j.estado = 'abierta')
       RETURNING id, dispositivo_id, atributos->>'journeyId' AS journey_id`,
    );
    for (const fila of creadas.rows) {
      await this.#insertarEvento(this.#pool, {
        dispositivoId: fila.dispositivo_id,
        tipo: 'mobileJourneyStarted',
        journeyId: fila.journey_id,
        bateria: null,
      });
      this.#log?.info(`jornada reconciliada dispositivo=${fila.dispositivo_id} journey=${fila.journey_id}`);
    }

    const vencidas = await this.#pool.query(
      `WITH actividad AS (
         SELECT j.id AS jornada_id, j.dispositivo_id,
                GREATEST(j.inicio_en,
                         coalesce(max(p.registrado_en), j.inicio_en),
                         coalesce(d.ultima_conexion_en, j.inicio_en)) AS ultima
           FROM operations.dmt_jornada j
           JOIN tracking.dmt_dispositivo d ON d.id = j.dispositivo_id
           LEFT JOIN tracking.dmt_posicion p
                  ON p.dispositivo_id = j.dispositivo_id AND p.registrado_en >= j.inicio_en
          WHERE j.estado = 'abierta'
          GROUP BY j.id, j.dispositivo_id, j.inicio_en, d.ultima_conexion_en
       )
       SELECT jornada_id, dispositivo_id, ultima
         FROM actividad
        WHERE now() - ultima > make_interval(hours => $1)`,
      [horasTimeout],
    );
    for (const fila of vencidas.rows) {
      await this.#pool.query(
        `UPDATE operations.dmt_jornada
            SET estado = 'cerrada',
                fin_en = $2,
                duracion_s = GREATEST(0, EXTRACT(EPOCH FROM ($2 - inicio_en))::bigint),
                atributos = coalesce(atributos, '{}'::jsonb)
                            || jsonb_build_object('reason', 'timeout'),
                actualizado_en = now()
          WHERE id = $1`,
        [fila.jornada_id, fila.ultima],
      );
      await this.#pool.query(
        `UPDATE tracking.dmt_dispositivo
            SET atributos = coalesce(atributos, '{}'::jsonb)
                            || jsonb_build_object(
                                 'mobile.journeyId', 0,
                                 'mobile.lastEndedJourneyId',
                                   coalesce(atributos ->> 'mobile.journeyId', '0')::bigint),
                actualizado_en = now()
          WHERE id = $1`,
        [fila.dispositivo_id],
      );
      this.#log?.info(`jornada cerrada por timeout dispositivo=${fila.dispositivo_id} jornada=${fila.jornada_id}`);
    }
    return { creadas: creadas.rowCount, cerradas: vencidas.rowCount };
  }

  async cerrar() {
    await this.#pool.end();
  }
}

export async function crearAlmacen(configuracion, log) {
  const pool = new Pool(opcionesPool(configuracion));
  pool.on('error', (error) => log?.error(`pool de base de datos: ${error.message}`));
  await pool.query('SELECT 1');
  return new Almacen(pool, log);
}
