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

export class Almacen {
  #pool;
  #particiones = new Set();
  #particionesEvento = new Set();
  #log;

  constructor(pool, log) {
    this.#pool = pool;
    this.#log = log;
  }

  get pool() {
    return this.#pool;
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
  // jornada para que evento y jornada queden consistentes.
  async #insertarEvento(conexion, { dispositivoId, tipo, journeyId, bateria }) {
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
    const marca = (await conexion.query('SELECT now() AS ahora')).rows[0].ahora;
    await this.#asegurarParticionEvento(conexion, marca);
    const atributos = { journeyId, mobileSeverity: 'info' };
    if (typeof bateria === 'number' && Number.isFinite(bateria)) atributos.battery = bateria;
    await conexion.query(
      `INSERT INTO tracking.dmt_evento (dispositivo_id, tipo, ocurrido_en, atributos)
       VALUES ($1, $2, $3, $4::jsonb)`,
      [dispositivoId, tipo, marca, JSON.stringify(atributos)],
    );
    return true;
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

  async registrarPosicion(dispositivoId, posicion) {
    const conexion = await this.#pool.connect();
    try {
      await conexion.query('BEGIN');
      await this.#asegurarParticion(conexion, posicion.registradoEn);
      const insertada = await conexion.query(
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
      const posicionId = String(insertada.rows[0].id);
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
           actualizado_en = now()`,
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
      await conexion.query(
        `UPDATE tracking.dmt_dispositivo
            SET estado = 'online',
                ultima_conexion_en = $2,
                ultima_posicion_id = $3,
                actualizado_en = now()
          WHERE id = $1`,
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

  async abrirJornada({ dispositivoId, journeyId, bateriaInicio, atributosJornada, parcheDispositivo }) {
    const conexion = await this.#pool.connect();
    try {
      await conexion.query('BEGIN');
      await conexion.query(
        `UPDATE operations.dmt_jornada
            SET estado = 'cerrada',
                fin_en = now(),
                duracion_s = GREATEST(0, EXTRACT(EPOCH FROM (now() - inicio_en))::bigint),
                actualizado_en = now()
          WHERE dispositivo_id = $1 AND estado = 'abierta'`,
        [dispositivoId],
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
           now(), 'abierta', $2, $3::jsonb
         )
         RETURNING id`,
        [dispositivoId, bateriaInicio, JSON.stringify(atributosJornada)],
      );
      await this.#insertarEvento(conexion, {
        dispositivoId,
        tipo: 'mobileJourneyStarted',
        journeyId: journeyId ?? atributosJornada?.journeyId ?? null,
        bateria: bateriaInicio,
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
      await this.#insertarEvento(conexion, {
        dispositivoId,
        tipo: 'mobileJourneyEnded',
        journeyId: journeyId ?? null,
        bateria: bateriaFin,
      });
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
