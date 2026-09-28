// Canal movil compatible: /api/mobile/v1/{config,journey,diagnostics,ota,
// fcm-token,recovery-ack}. Replica el contrato congelado en
// docs/api/COMPATIBILIDAD-APP.md seccion 3 (autenticacion X-Api-Key con clave
// actual y anterior, identidad X-Device-Id, codigos 200/204/400/401/403/404/413)
// traduciendo los efectos al modelo dmt_*.

import { createHash, timingSafeEqual } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';

const LIMITE_JSON = 64 * 1024;
const LIMITE_DIAGNOSTICO = 10_000;
const VENTANA_ANTIRREBOTE = 20_000;
const VENTANA_AUDITORIA_OTA = 60_000;
const MAX_TOKEN = 512;
const MAX_TEXTO = 64;
const MAX_INTENTO_RECUPERACION = 128;
const MAX_ETAPA_RECUPERACION = 32;
const MAX_PRIORIDAD_RECUPERACION = 32;
const MAX_RAZON_RECUPERACION = 512;

const ultimosDiagnosticos = new Map();

// ---------------------------------------------------------------------------
// Respuestas
// ---------------------------------------------------------------------------

function responderSinCuerpo(res, codigo) {
  res.writeHead(codigo, { 'cache-control': 'no-store' });
  res.end();
}

function responderJson(res, codigo, cuerpo) {
  const texto = JSON.stringify(cuerpo);
  res.writeHead(codigo, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(texto),
    'cache-control': 'no-store',
  });
  res.end(texto);
}

// ---------------------------------------------------------------------------
// Autenticacion y lectura de la peticion
// ---------------------------------------------------------------------------

function claveValida(cabecera, claves) {
  if (typeof cabecera !== 'string' || cabecera === '' || claves.length === 0) {
    return false;
  }
  const dada = Buffer.from(cabecera, 'utf8');
  let valida = false;
  for (const clave of claves) {
    const esperada = Buffer.from(clave, 'utf8');
    if (dada.length === esperada.length && timingSafeEqual(dada, esperada)) {
      valida = true;
    }
  }
  return valida;
}

async function leerCuerpo(req, limite) {
  const trozos = [];
  let total = 0;
  let excedido = false;
  for await (const trozo of req) {
    if (excedido) continue;
    total += trozo.length;
    if (total > limite) {
      excedido = true;
      continue;
    }
    trozos.push(trozo);
  }
  if (excedido) return { ok: false, motivo: 'grande' };
  return { ok: true, buffer: Buffer.concat(trozos) };
}

async function leerJson(req, limite) {
  const lectura = await leerCuerpo(req, limite);
  if (!lectura.ok) return lectura;
  const texto = lectura.buffer.toString('utf8');
  if (texto.trim() === '') return { ok: true, datos: {} };
  try {
    return { ok: true, datos: JSON.parse(texto) };
  } catch {
    return { ok: false, motivo: 'invalido' };
  }
}

function identificadorDe(req, url) {
  const cabecera = req.headers['x-device-id'];
  if (typeof cabecera === 'string' && cabecera.trim() !== '') return cabecera.trim();
  const consulta = url.searchParams.get('deviceId');
  return consulta !== null && consulta.trim() !== '' ? consulta.trim() : null;
}

function identificadorDeCabecera(req) {
  const cabecera = req.headers['x-device-id'];
  return typeof cabecera === 'string' && cabecera.trim() !== '' ? cabecera.trim() : null;
}

// ---------------------------------------------------------------------------
// Conversiones de valores (mismos defaults que el servidor actual)
// ---------------------------------------------------------------------------

function numero(valor) {
  if (valor === null || valor === undefined || valor === '') return null;
  const convertido = Number(String(valor).trim());
  return Number.isFinite(convertido) ? convertido : null;
}

function entero(valor, defecto) {
  if (typeof valor === 'number' && Number.isFinite(valor)) return Math.trunc(valor);
  if (typeof valor === 'string' && /^-?\d+$/.test(valor.trim())) {
    return Number.parseInt(valor.trim(), 10);
  }
  return defecto;
}

function texto(valor) {
  if (typeof valor === 'string') {
    const limpio = valor.trim();
    return limpio === '' ? null : limpio;
  }
  if (typeof valor === 'number' && Number.isFinite(valor)) return String(valor);
  return null;
}

function booleanoJson(valor) {
  return typeof valor === 'boolean' ? valor : null;
}

function objeto(valor) {
  return valor !== null && typeof valor === 'object' && !Array.isArray(valor) ? valor : null;
}

function numeroAttr(attrs, clave, defecto) {
  const valor = attrs[clave];
  if (typeof valor === 'number' && Number.isFinite(valor)) return Math.trunc(valor);
  if (typeof valor === 'string' && /^-?\d+$/.test(valor.trim())) {
    return Number.parseInt(valor.trim(), 10);
  }
  return defecto;
}

function textoAttr(attrs, clave, defecto) {
  const valor = attrs[clave];
  return valor !== null && valor !== undefined ? String(valor) : defecto;
}

function booleanoAttr(attrs, clave, defecto) {
  const valor = attrs[clave];
  if (typeof valor === 'boolean') return valor;
  if (valor !== null && valor !== undefined) return String(valor).trim().toLowerCase() === 'true';
  return defecto;
}

function prefijoToken(token) {
  return createHash('sha256').update(token, 'utf8').digest('hex').slice(0, 12);
}

function idCoincide(reportado, dispositivo) {
  if (reportado === null || reportado === undefined) return true;
  const valor = String(reportado).trim();
  if (valor === '') return true;
  if (/^\d+$/.test(valor)) return valor === String(dispositivo.id);
  return valor.toLowerCase() === dispositivo.identificador.toLowerCase();
}

async function buscarOFallar(ctx, res, identificador, codigoDesconocido) {
  let dispositivo;
  try {
    dispositivo = await ctx.almacen.buscarDispositivo(identificador);
  } catch (error) {
    ctx.log.error(`movil: fallo al buscar dispositivo: ${error.message}`);
    responderSinCuerpo(res, 503);
    return null;
  }
  if (!dispositivo) {
    responderSinCuerpo(res, codigoDesconocido);
    return null;
  }
  return dispositivo;
}

// ---------------------------------------------------------------------------
// GET /api/mobile/v1/config
// ---------------------------------------------------------------------------

export async function atenderConfig(req, res, ctx) {
  if (!ctx.configuracion.canalMovilActivo) return responderSinCuerpo(res, 503);
  if (!claveValida(req.headers['x-api-key'], ctx.configuracion.clavesMoviles)) {
    return responderSinCuerpo(res, 401);
  }
  const identificador = identificadorDe(req, ctx.url);
  if (!identificador) return responderSinCuerpo(res, 400);
  const dispositivo = await buscarOFallar(ctx, res, identificador, 404);
  if (!dispositivo) return;
  const attrs = dispositivo.atributos;
  return responderJson(res, 200, {
    intervalSeconds: numeroAttr(attrs, 'mobile.intervalSeconds', 10),
    bufferMax: numeroAttr(attrs, 'mobile.bufferMax', 5000),
    bufferPolicy: textoAttr(attrs, 'mobile.bufferPolicy', 'drop_oldest'),
    ackTimeoutSeconds: numeroAttr(attrs, 'mobile.ackTimeoutSeconds', 15),
    maxRetries: numeroAttr(attrs, 'mobile.maxRetries', 30),
    distanceMeters: numeroAttr(attrs, 'mobile.distanceMeters', 10),
    angleDegrees: numeroAttr(attrs, 'mobile.angleDegrees', 15),
    accuracy: textoAttr(attrs, 'mobile.accuracy', 'high'),
    bufferEnabled: booleanoAttr(attrs, 'mobile.bufferEnabled', true),
    l1_pending_intent_enabled: booleanoAttr(attrs, 'mobile.l1PendingIntentEnabled', false),
    store_all_enabled: booleanoAttr(attrs, 'mobile.storeAllEnabled', false),
    l1_max_update_delay_ms: numeroAttr(attrs, 'mobile.l1MaxUpdateDelayMs', 60000),
    min_interval_seconds: numeroAttr(attrs, 'mobile.minIntervalSeconds', 10),
  });
}

// ---------------------------------------------------------------------------
// POST /api/mobile/v1/journey
// ---------------------------------------------------------------------------

export async function atenderJornada(req, res, ctx) {
  if (!ctx.configuracion.canalMovilActivo) return responderSinCuerpo(res, 404);
  if (!claveValida(req.headers['x-api-key'], ctx.configuracion.clavesMoviles)) {
    return responderSinCuerpo(res, 401);
  }
  const lectura = await leerJson(req, LIMITE_JSON);
  if (!lectura.ok) return responderSinCuerpo(res, lectura.motivo === 'grande' ? 413 : 400);
  const cuerpo = objeto(lectura.datos);
  const identificador = cuerpo ? texto(cuerpo.deviceId) : null;
  if (!identificador) return responderSinCuerpo(res, 400);
  const dispositivo = await buscarOFallar(ctx, res, identificador, 404);
  if (!dispositivo) return;
  const accion = texto(cuerpo.action);
  if (accion !== 'start' && accion !== 'stop') return responderSinCuerpo(res, 400);

  const ahora = Date.now();
  const bateria = numeroAttr(dispositivo.atributos, 'mobile.battery', -1);
  try {
    if (accion === 'start') {
      let jornadaId = entero(cuerpo.journeyId, ahora);
      if (jornadaId <= 0) jornadaId = ahora;
      const cliente = cuerpo.client !== undefined ? texto(cuerpo.client) : null;
      const parche = { 'mobile.journeyId': jornadaId };
      if (cliente) parche['mobile.client'] = cliente;
      await ctx.almacen.abrirJornada({
        dispositivoId: dispositivo.id,
        journeyId: jornadaId,
        bateriaInicio: bateria >= 0 && bateria <= 100 ? bateria : null,
        atributosJornada: {
          journeyId: jornadaId,
          client: cliente,
          origen: 'movil.journey',
        },
        parcheDispositivo: parche,
      });
    } else {
      let jornadaId = entero(cuerpo.journeyId, 0);
      if (jornadaId <= 0) {
        jornadaId = numeroAttr(dispositivo.atributos, 'mobile.journeyId', ahora);
      }
      if (jornadaId <= 0) jornadaId = ahora;
      await ctx.almacen.cerrarJornada({
        dispositivoId: dispositivo.id,
        journeyId: jornadaId,
        finEn: new Date(ahora),
        bateriaFin: bateria >= 0 && bateria <= 100 ? bateria : null,
        parcheDispositivo: {
          'mobile.journeyId': 0,
          'mobile.lastEndedJourneyId': jornadaId,
          'mobile.journeyEndedAt': ahora,
        },
      });
    }
  } catch (error) {
    ctx.log.error(`movil/journey: fallo al registrar jornada: ${error.message}`);
    return responderSinCuerpo(res, 503);
  }
  return responderJson(res, 200, { ok: true });
}

// ---------------------------------------------------------------------------
// POST /api/mobile/v1/diagnostics
// ---------------------------------------------------------------------------

export async function atenderDiagnosticos(req, res, ctx) {
  if (!ctx.configuracion.canalMovilActivo) return responderSinCuerpo(res, 404);
  if (!claveValida(req.headers['x-api-key'], ctx.configuracion.clavesMoviles)) {
    return responderSinCuerpo(res, 401);
  }
  const identificador = identificadorDe(req, ctx.url);
  if (!identificador) return responderSinCuerpo(res, 400);
  const dispositivo = await buscarOFallar(ctx, res, identificador, 404);
  if (!dispositivo) return;

  const lectura = await leerJson(req, LIMITE_DIAGNOSTICO);
  if (!lectura.ok) return responderSinCuerpo(res, lectura.motivo === 'grande' ? 413 : 400);
  const datos = objeto(lectura.datos);
  if (!datos) return responderSinCuerpo(res, 400);
  if (!idCoincide(datos.deviceId, dispositivo)) return responderSinCuerpo(res, 403);

  const ahora = Date.now();
  const anterior = ultimosDiagnosticos.get(dispositivo.id);
  if (anterior !== undefined && ahora - anterior < VENTANA_ANTIRREBOTE) {
    return responderSinCuerpo(res, 204);
  }

  const reporte = objeto(datos.report) ?? {};
  const parche = {
    lastDiagnostics: JSON.stringify(datos),
    lastDiagnosticsAt: ahora,
  };
  const permisos = objeto(reporte.perms);
  if (permisos) {
    const fondo = booleanoJson(permisos.background);
    if (fondo !== null) parche['mobile.permBackground'] = fondo;
  }
  const energia = objeto(reporte.power);
  if (energia) {
    const exenta = booleanoJson(energia.exempt);
    if (exenta !== null) parche['mobile.batteryExempt'] = exenta;
  }
  const gps = objeto(reporte.gps);
  if (gps) {
    const simulado = booleanoJson(gps.mock);
    if (simulado !== null) parche['mobile.mockLocation'] = simulado;
    const habilitado = booleanoJson(gps.enabled);
    if (habilitado !== null) {
      parche['mobile.gps'] = habilitado ? 'on' : 'off';
      parche['mobile.gpsAt'] = ahora;
    }
  }
  const crash = texto(reporte.crash);
  if (crash) parche['mobile.lastCrashAt'] = ahora;

  let bateria = null;
  if (energia) {
    const nivel = numero(energia.battery);
    if (nivel !== null && nivel >= 0 && nivel <= 100) {
      const marca = numero(datos.ts);
      bateria = {
        porcentaje: Math.trunc(nivel),
        cargando: booleanoJson(energia.charging),
        registradoEn: new Date(marca ?? ahora),
      };
    }
  }

  try {
    await ctx.almacen.registrarDiagnostico({
      dispositivoId: dispositivo.id,
      parcheDispositivo: parche,
      bateria,
    });
  } catch (error) {
    ctx.log.error(`movil/diagnostics: fallo al guardar diagnostico: ${error.message}`);
    return responderSinCuerpo(res, 503);
  }
  ultimosDiagnosticos.set(dispositivo.id, ahora);
  return responderSinCuerpo(res, 204);
}

// ---------------------------------------------------------------------------
// GET /api/mobile/v1/ota
// ---------------------------------------------------------------------------

// Misma politica que OtaRolloutPolicy del servidor anterior:
//  1) sin downgrade: instalado >= publicado -> no hay actualizacion;
//  2) allowlist no vacia es decisiva: solo sus equipos reciben manifiesto
//     (ignora pausa y porcentaje);
//  3) instalado < minVersionCode -> forzada (se sirve aunque este en pausa);
//  4) paused -> denegada;
//  5) percent < 100 -> bucket estable del deviceId < percent.
// Bucket documentado: SHA-256(deviceId UTF-8), dos primeros bytes big-endian
// modulo 100; el mismo equipo obtiene siempre la misma decision.
const BUCKETS_ROLLOUT = 100;

function bucketRollout(deviceId) {
  const hash = createHash('sha256').update(deviceId ?? '', 'utf8').digest();
  const valor = ((hash[0] & 0xff) << 8) | (hash[1] & 0xff);
  return valor % BUCKETS_ROLLOUT;
}

// rollout.json opcional: ausente/ilegible/fuera de rango = fail-open 100/false
// (mismo criterio del servidor anterior).
async function leerRollout(directorio) {
  try {
    const crudo = JSON.parse(await readFile(join(directorio, 'rollout.json'), 'utf8'));
    if (objeto(crudo) === null) return { percent: 100, paused: false, allow: [] };
    const percentCrudo = numero(crudo.percent);
    const percent = percentCrudo === null
      ? 100
      : Math.max(0, Math.min(BUCKETS_ROLLOUT, Math.trunc(percentCrudo)));
    const allow = Array.isArray(crudo.allow)
      ? crudo.allow.map((entrada) => texto(entrada)).filter((entrada) => entrada !== null)
      : [];
    return { percent, paused: crudo.paused === true, allow };
  } catch {
    return { percent: 100, paused: false, allow: [] };
  }
}

function permitirActualizacion({ deviceId, instalado, publicado, minVersionCode, rollout }) {
  if (instalado >= publicado) return false;
  if (rollout.allow.length > 0) return rollout.allow.includes(deviceId);
  if (instalado < minVersionCode) return true;
  if (rollout.paused) return false;
  if (rollout.percent >= BUCKETS_ROLLOUT) return true;
  return bucketRollout(deviceId) < rollout.percent;
}

async function auditarOta(ctx, dispositivo, versionCode, actualiza, userAgent) {
  const attrs = dispositivo.atributos;
  const ultima = numeroAttr(attrs, 'mobile.lastOtaCheckAt', 0);
  const ahora = Date.now();
  if (ahora - ultima < VENTANA_AUDITORIA_OTA) return;
  const parche = {
    'mobile.lastOtaCheckAt': ahora,
    'mobile.lastOtaVersionCode': versionCode,
    'mobile.lastOtaUpdate': actualiza,
  };
  const agente = texto(userAgent);
  if (agente) parche['mobile.lastOtaUa'] = agente.slice(0, 80);
  await ctx.almacen.fusionarAtributosDispositivo(dispositivo.id, parche);
}

export async function atenderOta(req, res, ctx) {
  if (!ctx.configuracion.canalMovilActivo) return responderSinCuerpo(res, 503);
  if (!claveValida(req.headers['x-api-key'], ctx.configuracion.clavesMoviles)) {
    return responderSinCuerpo(res, 401);
  }
  const identificador = identificadorDe(req, ctx.url);
  const versionTexto = (ctx.url.searchParams.get('versionCode') ?? '').trim();
  const versionCode = /^\d+$/.test(versionTexto) ? Number.parseInt(versionTexto, 10) : Number.NaN;
  if (!identificador || !Number.isInteger(versionCode)) {
    return responderSinCuerpo(res, 400);
  }
  const dispositivo = await buscarOFallar(ctx, res, identificador, 404);
  if (!dispositivo) return;

  let manifiesto;
  try {
    manifiesto = JSON.parse(await readFile(join(ctx.configuracion.otaDir, 'latest.json'), 'utf8'));
  } catch (error) {
    ctx.log.warn(`movil/ota: sin manifiesto en ${ctx.configuracion.otaDir}: ${error.code ?? error.message}`);
    return responderSinCuerpo(res, 404);
  }
  const publicado = numero(manifiesto?.versionCode) ?? 0;
  const minVersionCode = numero(manifiesto?.minVersionCode) ?? 0;
  const rollout = await leerRollout(ctx.configuracion.otaDir);
  const actualiza = permitirActualizacion({
    deviceId: dispositivo.identificador,
    instalado: versionCode,
    publicado,
    minVersionCode,
    rollout,
  });
  try {
    await auditarOta(ctx, dispositivo, versionCode, actualiza, req.headers['user-agent']);
  } catch (error) {
    ctx.log.warn(`movil/ota: auditoria no escrita: ${error.message}`);
  }
  if (!actualiza) return responderJson(res, 200, { update: false });
  return responderJson(res, 200, manifiesto);
}

// ---------------------------------------------------------------------------
// POST /api/mobile/v1/fcm-token
// ---------------------------------------------------------------------------

export async function atenderTokenFcm(req, res, ctx) {
  if (!claveValida(req.headers['x-api-key'], ctx.configuracion.clavesMoviles)) {
    return responderSinCuerpo(res, 401);
  }
  const identificador = identificadorDeCabecera(req);
  if (!identificador) return responderSinCuerpo(res, 400);
  const dispositivo = await buscarOFallar(ctx, res, identificador, 403);
  if (!dispositivo) return;
  const lectura = await leerJson(req, LIMITE_JSON);
  if (!lectura.ok) return responderSinCuerpo(res, 400);
  const datos = objeto(lectura.datos);
  const token = datos ? texto(datos.fcmToken) : null;
  if (!token || token.length > MAX_TOKEN) return responderSinCuerpo(res, 400);

  const prefijo = prefijoToken(token);
  const ahora = Date.now();
  const parche = {
    'mobile.fcmTokenRegistered': true,
    'mobile.fcmTokenPrefix': prefijo,
    'mobile.fcmUpdatedAt': ahora,
  };
  const version = datos ? texto(datos.appVersion) : null;
  if (version) parche['mobile.appVersion'] = version.slice(0, MAX_TEXTO);
  try {
    await ctx.almacen.guardarTokenFcm({ dispositivoId: dispositivo.id, token });
    await ctx.almacen.fusionarAtributosDispositivo(dispositivo.id, parche);
  } catch (error) {
    ctx.log.error(`movil/fcm-token: fallo al guardar token: ${error.message}`);
    return responderSinCuerpo(res, 503);
  }
  // El token completo solo vive en iam.dmt_token_fcm; los logs usan el prefijo.
  ctx.log.info(`movil/fcm-token: registrado device=${dispositivo.identificador} prefix=${prefijo}`);
  return responderJson(res, 200, { ok: true, success: true, status: 'registered' });
}

// ---------------------------------------------------------------------------
// POST /api/mobile/v1/recovery-ack
// ---------------------------------------------------------------------------

export async function atenderRecuperacionAck(req, res, ctx) {
  if (!claveValida(req.headers['x-api-key'], ctx.configuracion.clavesMoviles)) {
    return responderSinCuerpo(res, 401);
  }
  const identificador = identificadorDeCabecera(req);
  if (!identificador) return responderSinCuerpo(res, 400);
  const dispositivo = await buscarOFallar(ctx, res, identificador, 403);
  if (!dispositivo) return;
  const lectura = await leerJson(req, LIMITE_JSON);
  if (!lectura.ok) return responderSinCuerpo(res, 400);
  const datos = objeto(lectura.datos);
  const intento = datos ? texto(datos.recoveryAttemptId) : null;
  const etapa = datos ? texto(datos.stage) : null;
  if (!intento || !etapa) return responderSinCuerpo(res, 400);
  const prioridad = (datos ? texto(datos.priority) : null) ?? '-';
  const razon = datos ? texto(datos.reason) : null;
  const atributos = {
    attemptId: intento.slice(0, MAX_INTENTO_RECUPERACION),
    stage: etapa.slice(0, MAX_ETAPA_RECUPERACION),
    priority: prioridad.slice(0, MAX_PRIORIDAD_RECUPERACION),
    reason: razon === null ? null : razon.slice(0, MAX_RAZON_RECUPERACION),
  };
  // Fase 4b: se registra el ack en operations.dmt_alerta; el envio real de
  // push (servicio FCM con cuenta de servicio) queda para la fase siguiente.
  try {
    await ctx.almacen.registrarAlertaRecuperacion({
      dispositivoId: dispositivo.id,
      atributos,
    });
  } catch (error) {
    ctx.log.error(`movil/recovery-ack: fallo al registrar ack: ${error.message}`);
    return responderSinCuerpo(res, 503);
  }
  ctx.log.info(
    `movil/recovery-ack: device=${dispositivo.identificador} attempt=${atributos.attemptId} ` +
    `stage=${atributos.stage} priority=${atributos.priority}`,
  );
  return responderJson(res, 200, { ok: true, success: true, status: 'accepted' });
}
