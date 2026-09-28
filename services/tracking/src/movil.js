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
// Lote de posiciones: hasta 500 eventos (~200 B c/u) caben en 512 KB con
// margen; más allá se rechaza con 413 para no agotar memoria.
const LIMITE_LOTE = 512 * 1024;
const MAX_EVENTOS_LOTE = 500;
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

// ---------------------------------------------------------------------------
// GET /api/mobile/v1/journey (reconciliación cliente↔servidor)
// ---------------------------------------------------------------------------
// Tras recrear proceso/reboot la app pregunta la verdad del servidor
// (operations.dmt_jornada) para continuar o cerrar sin depender de RAM.
// Responde {estado:'abierta'|'cerrada'|'ninguna', journeyId, inicioEn}.
export async function atenderJornadaConsulta(req, res, ctx) {
  if (!ctx.configuracion.canalMovilActivo) return responderSinCuerpo(res, 404);
  if (!claveValida(req.headers['x-api-key'], ctx.configuracion.clavesMoviles)) {
    return responderSinCuerpo(res, 401);
  }
  const identificador = identificadorDe(req, ctx.url);
  if (!identificador) return responderSinCuerpo(res, 400);
  const dispositivo = await buscarOFallar(ctx, res, identificador, 404);
  if (!dispositivo) return;
  try {
    const estado = await ctx.almacen.obtenerJornadaEstado(dispositivo.id);
    return responderJson(res, 200, estado);
  } catch (error) {
    ctx.log.error(`movil/journey-get: fallo al leer jornada: ${error.message}`);
    return responderSinCuerpo(res, 503);
  }
}

// ---------------------------------------------------------------------------
// POST /api/mobile/v1/positions (lote idempotente)
// ---------------------------------------------------------------------------
// Contrato: body {eventos:[{bootId,seq,journeyId,capturedAt,lat,lon,alt,speed,
// bearing,accuracy,battery,charging,mock,provider,movementState}]} →
// {resultados:[{seq,estado}]} con estado accepted|duplicate|invalid|dead.
// - accepted: guardado (o sin identidad pero válido, solo OsmAnd legacy).
// - duplicate: mismo (device,boot,seq) ya registrado (ON CONFLICT).
// - invalid: coordenadas/fecha/identidad fuera de contrato, sin guardar.
// - dead: equipo deshabilitado (drena sin guardar, igual que OsmAnd).
// Límite 500 eventos por lote; una transacción por lote en db.js.
function esLatitud(valor) {
  return typeof valor === 'number' && Number.isFinite(valor) && valor >= -90 && valor <= 90;
}

function esLongitud(valor) {
  return typeof valor === 'number' && Number.isFinite(valor) && valor >= -180 && valor <= 180;
}

function enteroSecuencia(valor) {
  if (typeof valor === 'number' && Number.isInteger(valor) && valor >= 0) return valor;
  if (typeof valor === 'string' && /^\d+$/.test(valor.trim())) {
    const convertido = Number.parseInt(valor.trim(), 10);
    if (Number.isSafeInteger(convertido) && convertido >= 0) return convertido;
  }
  return null;
}

function numeroFinito(valor) {
  if (valor === null || valor === undefined) return null;
  const convertido = typeof valor === 'number' ? valor : Number(String(valor).trim());
  return Number.isFinite(convertido) ? convertido : null;
}

export async function atenderLotePosiciones(req, res, ctx) {
  if (!ctx.configuracion.canalMovilActivo) return responderSinCuerpo(res, 503);
  if (!claveValida(req.headers['x-api-key'], ctx.configuracion.clavesMoviles)) {
    return responderSinCuerpo(res, 401);
  }
  const lectura = await leerJson(req, LIMITE_LOTE);
  if (!lectura.ok) return responderSinCuerpo(res, lectura.motivo === 'grande' ? 413 : 400);
  const cuerpo = objeto(lectura.datos);
  // El dispositivo viaja en X-Device-Id (canal móvil); se acepta ?deviceId o
  // body.deviceId como respaldo para pruebas con curl.
  let identificador = identificadorDeCabecera(req);
  if (!identificador) {
    try {
      const urlAux = ctx.url ?? new URL(req.url, 'http://127.0.0.1');
      identificador = identificadorDe(req, urlAux);
    } catch {
      identificador = null;
    }
  }
  if (!identificador && cuerpo) {
    identificador = texto(cuerpo.deviceId) ?? texto(cuerpo.dispositivoId);
  }
  if (!identificador) return responderSinCuerpo(res, 400);
  const dispositivo = await buscarOFallar(ctx, res, identificador, 404);
  if (!dispositivo) return;
  const eventos = cuerpo ? cuerpo.eventos : null;
  if (!Array.isArray(eventos) || eventos.length === 0) return responderSinCuerpo(res, 400);
  if (eventos.length > MAX_EVENTOS_LOTE) return responderSinCuerpo(res, 413);
  if (cuerpo && cuerpo.deviceId !== undefined && !idCoincide(cuerpo.deviceId, dispositivo)) {
    return responderSinCuerpo(res, 403);
  }

  // Equipo deshabilitado: dead para todo el lote (drena sin guardar).
  if (dispositivo.habilitado === false) {
    const resultados = eventos.map((evento) => {
      const crudo = evento !== null && typeof evento === 'object' ? evento.seq : null;
      return { seq: enteroSecuencia(crudo), estado: 'dead' };
    });
    return responderJson(res, 200, { resultados });
  }

  // Prevalidación sin base: coordenadas, fecha e identidad. Lo inválido no
  // llega a la transacción; lo válido se inserta en un solo lote.
  const ahora = Date.now();
  const limiteAtras = ahora - 30 * 24 * 60 * 60 * 1000;
  const limiteAdelante = ahora + 24 * 60 * 60 * 1000;
  const resultados = new Array(eventos.length);
  const validos = [];
  for (let indice = 0; indice < eventos.length; indice += 1) {
    const evento = eventoObjeto(eventos[indice]);
    const seqEco = evento ? enteroSecuencia(evento.seq) : null;
    if (!evento || seqEco === null) {
      resultados[indice] = { seq: seqEco, estado: 'invalid' };
      continue;
    }
    const bootId = typeof evento.bootId === 'string' ? evento.bootId.trim() : null;
    if (!bootId) {
      resultados[indice] = { seq: seqEco, estado: 'invalid' };
      continue;
    }
    const lat = numeroFinito(evento.lat);
    const lon = numeroFinito(evento.lon);
    if (!esLatitud(lat) || !esLongitud(lon)) {
      resultados[indice] = { seq: seqEco, estado: 'invalid' };
      continue;
    }
    const capturado = evento.capturedAt === undefined || evento.capturedAt === null
      ? null
      : new Date(evento.capturedAt);
    if (!(capturado instanceof Date) || Number.isNaN(capturado.getTime())) {
      resultados[indice] = { seq: seqEco, estado: 'invalid' };
      continue;
    }
    const instante = capturado.getTime();
    if (instante < limiteAtras || instante > limiteAdelante) {
      resultados[indice] = { seq: seqEco, estado: 'invalid' };
      continue;
    }
    // Opcionales: se normalizan sin tumbar el evento (null si no sirven).
    const bateriaCruda = numeroFinito(evento.battery);
    const bateria = bateriaCruda === null ? null : Math.min(100, Math.max(0, bateriaCruda));
    const simulado = evento.mock === true;
    const atributos = {};
    if (evento.journeyId !== undefined && evento.journeyId !== null) {
      const jornadaCruda = enteroSecuencia(evento.journeyId) ?? numeroFinito(evento.journeyId);
      if (jornadaCruda !== null) atributos.journeyId = jornadaCruda;
    }
    if (typeof evento.provider === 'string' && evento.provider.trim() !== '') {
      atributos.provider = evento.provider.trim().slice(0, 32);
    }
    if (typeof evento.movementState === 'string' && evento.movementState.trim() !== '') {
      atributos.movementState = evento.movementState.trim().slice(0, 32);
    }
    if (evento.charging !== undefined) atributos.charging = evento.charging === true;
    if (simulado) atributos.mock = true;
    validos.push({
      indice,
      seqEco,
      posicion: {
        protocolo: 'lote',
        latitud: lat,
        longitud: lon,
        altitud: numeroFinito(evento.alt),
        // speed se interpreta como km/h del contrato móvil (sin conversión
        // inventada; si la app enviara m/s se ajusta en Sprint 2 con versión).
        velocidadKmh: numeroFinito(evento.speed),
        rumbo: numeroFinito(evento.bearing),
        precision: numeroFinito(evento.accuracy),
        bateria,
        valida: !simulado,
        registradoEn: capturado,
        bootId,
        secuencia: seqEco,
        atributos,
      },
    });
  }

  try {
    if (validos.length > 0) {
      const lote = validos.map((entrada) => ({ ...entrada.posicion, seq: entrada.seqEco }));
      const parciales = await ctx.almacen.registrarLotePosiciones(dispositivo.id, lote);
      const porSeq = new Map();
      for (const parcial of parciales) {
        // La clave de dedupe es (boot,seq); el seq basta para reatar porque el
        // boot ya se validó por evento y el lote es de un solo dispositivo.
        if (!porSeq.has(parcial.seq)) porSeq.set(parcial.seq, []);
        porSeq.get(parcial.seq).push(parcial.estado);
      }
      const usados = new Map();
      for (const entrada of validos) {
        const estados = porSeq.get(entrada.seqEco) ?? ['accepted'];
        const vez = usados.get(entrada.seqEco) ?? 0;
        usados.set(entrada.seqEco, vez + 1);
        resultados[entrada.indice] = { seq: entrada.seqEco, estado: estados[Math.min(vez, estados.length - 1)] };
      }
    }
    // Los inválidos ya quedaron en `resultados`; por seguridad se rellena
    // cualquier hueco como invalid (nunca se deja undefined).
    for (let indice = 0; indice < resultados.length; indice += 1) {
      if (!resultados[indice]) {
        const evento = eventoObjeto(eventos[indice]);
        resultados[indice] = { seq: evento ? enteroSecuencia(evento.seq) : null, estado: 'invalid' };
      }
    }
    return responderJson(res, 200, { resultados });
  } catch (error) {
    ctx.log.error(`movil/positions: fallo al guardar lote: ${error.message}`);
    return responderSinCuerpo(res, 503);
  }
}

function eventoObjeto(valor) {
  return valor !== null && typeof valor === 'object' && !Array.isArray(valor) ? valor : null;
}
