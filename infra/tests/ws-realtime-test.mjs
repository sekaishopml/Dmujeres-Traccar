import WebSocket from 'ws';

const API = 'http://localhost:8082';
const WS = 'ws://localhost:8082/api/socket';
const OSMAND = 'http://localhost:5055';

const EMAIL = 'admin@dmujeres.local';
const PASSWORD = 'admin123';
const UNIQUE_ID = '860123456789';

// Nueva ubicacion distinta a la anterior (para reconocerla en el push)
const NEW_LAT = -2.1500;
const NEW_LON = -79.9000;

function fail(msg) { console.error('FAIL:', msg); process.exit(1); }

const login = await fetch(`${API}/api/session`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: `email=${encodeURIComponent(EMAIL)}&password=${encodeURIComponent(PASSWORD)}`,
});
if (!login.ok) fail(`login HTTP ${login.status}`);
const cookies = login.headers.getSetCookie().map((c) => c.split(';')[0]).join('; ');
console.log('Login OK. Cookie:', cookies.split('=')[0] + '=...');

const ws = new WebSocket(WS, { headers: { Cookie: cookies } });

let pinged = false;
const timeout = setTimeout(() => fail('sin push de la nueva posicion en 20s'), 20000);

ws.on('open', async () => {
  console.log('WebSocket conectado.');
  // Esperar un momento y enviar un ping GPS nuevo
  setTimeout(async () => {
    const ts = Math.floor(Date.now() / 1000);
    const url = `${OSMAND}/?id=${UNIQUE_ID}&lat=${NEW_LAT}&lon=${NEW_LON}&timestamp=${ts}&speed=25&bearing=180&batt=88`;
    const r = await fetch(url);
    pinged = true;
    console.log(`Ping OsmAnd enviado (lat=${NEW_LAT}, lon=${NEW_LON}) -> HTTP ${r.status}`);
  }, 1500);
});

ws.on('message', (data) => {
  const text = data.toString();
  let msg;
  try { msg = JSON.parse(text); } catch { return; }
  if (!pinged) {
    console.log('Push inicial recibido (snapshot).');
    return;
  }
  if (msg.positions && msg.positions.length) {
    const p = msg.positions.find((x) => Math.abs(x.latitude - NEW_LAT) < 1e-4 && Math.abs(x.longitude - NEW_LON) < 1e-4);
    if (p) {
      clearTimeout(timeout);
      console.log('PUSH EN TIEMPO REAL RECIBIDO:');
      console.log(JSON.stringify({ deviceId: p.deviceId, latitude: p.latitude, longitude: p.longitude, speed: p.speed, course: p.course, attributes: p.attributes }, null, 2));
      console.log('SUCCESS: la posicion llego por WebSocket en tiempo real.');
      ws.close();
      process.exit(0);
    }
  }
});

ws.on('error', (e) => fail('WS error: ' + e.message));
