// Configuración desde entorno (ver README.md)
const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL || 'admin@dmj.local';
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD || 'Admin123!';
const WebSocket = require('ws');
const BASE = process.env.TEST_SERVER_URL || 'http://localhost:8082';
async function main() {
  const login = await fetch(`${BASE}/api/session`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ email: ADMIN_EMAIL, password: ADMIN_PASSWORD }), redirect: 'manual' });
  const cookie = login.headers.get('set-cookie').split(';')[0];
  const tok = await fetch(`${BASE}/api/session/token`, { method: 'POST', headers: { cookie, 'Content-Type': 'application/x-www-form-urlencoded' } });
  const token = (await tok.text()).trim();

  // vincular notificaciones web al usuario 1
  const notifs = await (await fetch(`${BASE}/api/notifications?userId=1`, { headers: { cookie } })).json();
  for (const n of notifs) {
    await fetch(`${BASE}/api/permissions`, { method: 'POST', headers: { cookie, 'Content-Type': 'application/json' }, body: JSON.stringify({ userId: 1, notificationId: n.id }) });
  }
  console.log('notificaciones del usuario:', notifs.map(n => n.type).join(', '));

  const ws = new WebSocket(`ws://localhost:8082/api/socket?token=${encodeURIComponent(token)}`);
  const events = [];
  ws.on('message', d => { const m = JSON.parse(d); if (Array.isArray(m.events)) { console.log('EVENTO WS:', JSON.stringify(m.events[0]).slice(0, 180)); events.push(...m.events); } });
  await new Promise(r => ws.on('open', r));
  await new Promise(r => setTimeout(r, 1000));
  console.log('>> enviando posicion (device -> online)');
  await fetch(`http://localhost:5055/?id=demo-001&lat=-33.42&lon=-70.63&timestamp=${Date.now()}`);
  await new Promise(r => setTimeout(r, 4000));
  console.log('eventos recibidos:', events.length);
  process.exit(events.some(e => e.type === 'deviceOnline') ? 0 : 1);
}
main();
