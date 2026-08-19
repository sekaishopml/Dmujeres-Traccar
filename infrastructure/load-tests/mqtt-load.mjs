// mqtt-load.mjs — carga por MQTT contra el consumidor real
// Publica --messages N en --devices D dispositivos y cuenta ACKs de aplicación.
import mqtt from 'mqtt';

const args = new Map();
for (let i = 2; i < process.argv.length; i += 1) {
  if (process.argv[i].startsWith('--')) args.set(process.argv[i].slice(2), process.argv[i + 1]);
}

const devices = Number(args.get('devices') || 20);
const perDevice = Number(args.get('perDevice') || 10);
const url = process.env.MQTT_URL || 'mqtt://127.0.0.1:1883';
const intervalMs = Number(process.env.MQTT_INTERVAL_MS || 50);
const timeoutMs = Number(process.env.MQTT_TIMEOUT_MS || 120000);

const options = { protocolVersion: 5, reconnectPeriod: 0, clean: true,
  ...(process.env.MQTT_USER ? { username: process.env.MQTT_USER } : {}),
  ...(process.env.MQTT_PASSWORD ? { password: process.env.MQTT_PASSWORD } : {}) };

const consumer = mqtt.connect(url, options);
const publisher = mqtt.connect(url, options);
const connect = c => new Promise((resolve, reject) => { c.once('connect', resolve); c.once('error', reject); });
await Promise.all([connect(consumer), connect(publisher)]);
await new Promise((resolve, reject) => consumer.subscribe('dmj/v1/devices/+/ack', { qos: 1 },
  error => error ? reject(error) : resolve()));

const acks = { accepted: 0, duplicate: 0, rejected: 0, invalid: 0, pending: 0 };
const started = Date.now();
const runNonce = started % 100000000;
const pendingAcks = new Set();
let sent = 0;
let settled = 0;

consumer.on('message', (topic, message) => {
  const ack = JSON.parse(message.toString());
  if (!pendingAcks.has(ack.messageId)) return;
  pendingAcks.delete(ack.messageId);
  acks[ack.status] = (acks[ack.status] || 0) + 1;
  settled += 1;
});

for (let d = 0; d < devices; d += 1) {
  for (let m = 0; m < perDevice; m += 1) {
    const deviceId = `load-e2e-${String(d + 1).padStart(3, '0')}`;
    const messageId = `01JLOADX${String(runNonce).padStart(8, '0')}${String(d).padStart(3, '0')}${String(m).padStart(2, '0')}`;
    const sequence = runNonce + m + 1;
    const payload = JSON.stringify({
      schema: 1, type: 'position', messageId, deviceId, sequence,
      sentAt: new Date().toISOString(), observedAt: new Date().toISOString(),
      payload: { latitude: -33.4 + d / 1000, longitude: -70.6, accuracy: 8, speed: 20, bearing: 0, altitude: 500 },
    });
    pendingAcks.add(messageId);
    sent += 1;
    publisher.publish(`dmj/v1/devices/${deviceId}/telemetry`, payload, { qos: 1 }, () => {});
  }
}

// esperar a que todos los ACKs lleguen o timeout
while (settled < sent && Date.now() - started < timeoutMs) {
  await new Promise(r => setTimeout(r, 200));
}

const elapsed = (Date.now() - started) / 1000;
const result = {
  scope: 'mqtt-end-to-end-acks',
  devices, perDevice, sent, settled,
  statuses: acks,
  unacknowledged: sent - settled,
  messagesPerSecond: sent / elapsed,
  ackLatencyMs: { totalElapsedMs: elapsed * 1000 },
};
console.log(JSON.stringify(result, null, 2));
await Promise.all([new Promise(r => consumer.end(false, r)), new Promise(r => publisher.end(false, r))]);
const ok = sent === settled && acks.accepted === sent;
process.exit(ok ? 0 : 1);
