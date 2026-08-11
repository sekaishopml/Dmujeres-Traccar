import WebSocket from 'ws';
import { spawn } from 'node:child_process';

const API = 'http://localhost:8082';
const WS = 'ws://localhost:8082/api/socket';

const EMAIL = 'admin@dmujeres.local';
const PASSWORD = 'admin123';
const UNIQUE_ID = '860123456789';   // clientId MQTT = uniqueId del dispositivo
const MQTT_PORT = 8010;             // protocolo dmujeres (MQTT)
const TOPIC = 'dmujeres/position';

// Ubicacion nueva para reconocer el push que entra por MQTT
const NEW_LAT = -2.1234;
const NEW_LON = -79.9876;

function fail(msg) { console.error('FAIL:', msg); process.exit(1); }

function publishMqtt() {
  const ts = Math.floor(Date.now() / 1000);
  const payload = JSON.stringify({
    lat: NEW_LAT, lon: NEW_LON, ts, speed: 10.0, course: 270, alt: 8, acc: 3, batt: 64,
  });
  return new Promise((resolve, reject) => {
    const p = spawn('mosquitto_pub', [
      '-h', 'localhost', '-p', String(MQTT_PORT),
      '-i', UNIQUE_ID, '-t', TOPIC, '-q', '1', '-m', payload,
    ]);
    p.on('close', (code) => (code === 0 ? resolve() : reject(new Error('mosquitto_pub exit ' + code))));
  });
}

const login = await fetch(`${API}/api/session`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: `email=${encodeURIComponent(EMAIL)}&password=${encodeURIComponent(PASSWORD)}`,
});
if (!login.ok) fail(`login HTTP ${login.status}`);
const cookies = login.headers.getSetCookie().map((c) => c.split(';')[0]).join('; ');
console.log('Login OK.');

const ws = new WebSocket(WS, { headers: { Cookie: cookies } });
let published = false;
const timeout = setTimeout(() => fail('sin push de la posicion MQTT en 20s'), 20000);

ws.on('open', () => {
  console.log('WebSocket conectado.');
  setTimeout(async () => {
    await publishMqtt();
    published = true;
    console.log(`Publicado por MQTT (:${MQTT_PORT}) lat=${NEW_LAT}, lon=${NEW_LON}`);
  }, 1500);
});

ws.on('message', (data) => {
  let msg;
  try { msg = JSON.parse(data.toString()); } catch { return; }
  if (!published || !msg.positions) return;
  const p = msg.positions.find(
    (x) => Math.abs(x.latitude - NEW_LAT) < 1e-4 && Math.abs(x.longitude - NEW_LON) < 1e-4);
  if (p) {
    clearTimeout(timeout);
    console.log('PUSH EN TIEMPO REAL (origen MQTT):');
    console.log(JSON.stringify({
      deviceId: p.deviceId, protocol: p.protocol, latitude: p.latitude,
      longitude: p.longitude, speed: p.speed, course: p.course, attributes: p.attributes,
    }, null, 2));
    console.log('SUCCESS: posicion publicada por MQTT llego en vivo por WebSocket.');
    ws.close();
    process.exit(0);
  }
});

ws.on('error', (e) => fail('WS error: ' + e.message));
