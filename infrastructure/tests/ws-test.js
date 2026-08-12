// PT-101/102 — Fase 1: validación WebSocket realtime + autenticación
// Flujo: login (cookie) → token firmado → WS con token → push de posiciones/eventos
// Configuración desde entorno (ver README.md)
const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL || 'admin@dmj.local';
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD || 'Admin123!';
const WebSocket = require('ws');
const BASE = process.env.TEST_SERVER_URL || 'http://localhost:8082';
const WS = process.env.TEST_WS_URL || 'ws://localhost:8082/api/socket';

const ADMIN = { email: ADMIN_EMAIL, password: ADMIN_PASSWORD };
const results = [];
function record(id, ok, detail) { results.push({ id, ok, detail }); console.log(`${ok ? 'PASS' : 'FAIL'} ${id}: ${detail}`); }

async function main() {
  // 1. Login con cookie
  const login = await fetch(`${BASE}/api/session`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(ADMIN), redirect: 'manual' });
  const cookie = login.headers.get('set-cookie').split(';')[0];
  record('AUTH-1 login cookie', login.status === 200, `HTTP ${login.status}`);

  // 2. Emisión de token firmado
  const tok = await fetch(`${BASE}/api/session/token`, { method: 'POST', headers: { cookie, 'Content-Type': 'application/x-www-form-urlencoded' } });
  const token = (await tok.text()).trim();
  record('AUTH-2 emisión token', tok.status === 200 && token.length > 20, `HTTP ${tok.status}, len=${token.length}`);

  // 3. Autenticación por token (GET /api/session?token=)
  const tokAuth = await fetch(`${BASE}/api/session?token=${encodeURIComponent(token)}`);
  const tokUser = await tokAuth.json();
  record('AUTH-3 auth por token', tokAuth.status === 200 && tokUser.email === ADMIN.email, `HTTP ${tokAuth.status}`);

  // 4. Autenticación Basic
  const basic = await fetch(`${BASE}/api/devices`, { headers: { Authorization: 'Basic ' + Buffer.from(`${ADMIN.email}:${ADMIN.password}`).toString('base64') } });
  record('AUTH-4 auth Basic', basic.status === 200, `HTTP ${basic.status}`);

  // 5. Notificación web (para recibir eventos por WS)
  const notif = await fetch(`${BASE}/api/notifications`, { method: 'POST', headers: { cookie, 'Content-Type': 'application/json' }, body: JSON.stringify({ type: 'deviceOnline', notificators: 'web' }) });
  const notif2 = await fetch(`${BASE}/api/notifications`, { method: 'POST', headers: { cookie, 'Content-Type': 'application/json' }, body: JSON.stringify({ type: 'deviceOffline', notificators: 'web' }) });
  record('AUTH-5 notificación web creada', notif.status === 200 && notif2.status === 200, `HTTP ${notif.status}/${notif2.status}`);
  const createdNotifs = [await notif.json(), await notif2.json()];

  // 6. WebSocket con token
  const received = { positions: [], devices: [], events: [] };
  const ws = new WebSocket(`${WS}?token=${encodeURIComponent(token)}`);
  const opened = await new Promise((resolve) => { ws.on('open', () => resolve(true)); ws.on('error', () => resolve(false)); });
  record('WS-1 conexión con token', opened, 'open');
  ws.on('message', (data) => {
    const msg = JSON.parse(data.toString());
    for (const k of ['positions', 'devices', 'events', 'logs']) {
      if (Array.isArray(msg[k])) received[k].push(...msg[k]);
    }
  });

  // 7. Enviar posición OsmAnd → esperar push WS
  await new Promise(r => setTimeout(r, 2000));
  const lat = -33.45 + (Math.random() * 0.02), lon = -70.67 + (Math.random() * 0.02);
  const pos = await fetch(`http://localhost:5055/?id=demo-001&lat=${lat}&lon=${lon}&timestamp=${Date.now()}&hdop=1.0&speed=15.5`);
  record('WS-2 posición aceptada', pos.status === 200, `HTTP ${pos.status}`);

  await new Promise(r => setTimeout(r, 4000));
  const gotPos = received.positions.some(p => Math.abs(p.latitude - lat) < 1e-6);
  record('WS-3 push posición en tiempo real', gotPos, `lat=${lat} lon=${lon}; recibidos=${received.positions.length}`);
  if (gotPos) console.log('    push:', JSON.stringify(received.positions[received.positions.length - 1]).slice(0, 220));

  // 8. Evento deviceOnline por WS
  await new Promise(r => setTimeout(r, 2000));
  const gotEvent = received.events.some(e => ['deviceOnline', 'deviceOffline', 'deviceUnknown'].includes(e.type));
  record('WS-4 evento de estado en tiempo real', gotEvent, `eventos recibidos=${received.events.length}`);
  if (gotEvent) console.log('    evento:', JSON.stringify(received.events[0]).slice(0, 220));

  // 9. Keepalive (mensaje vacío del server)
  let gotKeepalive = false;
  ws.on('message', (data) => { if (data.toString() === '{}') gotKeepalive = true; });
  await new Promise(r => setTimeout(r, 60000));
  record('WS-5 keepalive', gotKeepalive, '{} en 20s');

  ws.close();
  for (const n of createdNotifs) await fetch(`${BASE}/api/notifications/${n.id}`, { method: 'DELETE', headers: { cookie } });
  const fails = results.filter(r => !r.ok);
  console.log(`\nRESULTADO: ${results.length - fails.length}/${results.length} pruebas PASARON`);
  process.exit(fails.length ? 1 : 0);
}

main().catch(e => { console.error('ERROR:', e.message); process.exit(1); });
