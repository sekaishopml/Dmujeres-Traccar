import mqtt from 'mqtt';

const args = new Map();
for (let i = 2; i < process.argv.length; i += 1) {
  if (process.argv[i].startsWith('--')) args.set(process.argv[i].slice(2), process.argv[i + 1]);
}

const devices = Number(args.get('devices') || 1);
const durationSeconds = Number(args.get('duration') || 30);
const intervalMs = Number(process.env.MQTT_INTERVAL_MS || 10000);
const qos = Number(process.env.MQTT_QOS || 1);
const url = process.env.MQTT_URL || 'mqtt://127.0.0.1:1883';

if (!Number.isInteger(devices) || devices < 1 || devices > 10000) throw new Error('devices debe estar entre 1 y 10000');
if (![0, 1, 2].includes(qos)) throw new Error('MQTT_QOS debe ser 0, 1 o 2');

const options = {
  clientId: `dmj-load-${process.pid}-${Date.now()}`,
  clean: true,
  reconnectPeriod: 0,
  protocolVersion: 5,
  ...(process.env.MQTT_USER ? { username: process.env.MQTT_USER } : {}),
  ...(process.env.MQTT_PASSWORD ? { password: process.env.MQTT_PASSWORD } : {}),
};

const latencies = [];
let attempted = 0;
let acknowledged = 0;
let errors = 0;
const client = mqtt.connect(url, options);

const now = () => Number(process.hrtime.bigint()) / 1e6;
const percentile = (values, p) => {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * p) - 1)];
};

await new Promise((resolve, reject) => {
  client.once('connect', resolve);
  client.once('error', reject);
});

const started = now();
const stopAt = started + durationSeconds * 1000;
const timers = [];

for (let index = 0; index < devices; index += 1) {
  const deviceId = `load-${index + 1}`;
  const publish = () => {
    if (now() >= stopAt) return;
    const messageId = `${process.pid}-${index}-${attempted}`;
    const payload = JSON.stringify({
      schema: 1,
      type: 'position',
      messageId,
      deviceId,
      sequence: attempted + 1,
      sentAt: new Date().toISOString(),
      observedAt: new Date().toISOString(),
      payload: { latitude: -33.45 + index / 100000, longitude: -70.67, accuracy: 8.2, speed: 12.4 },
    });
    attempted += 1;
    const sentAt = now();
    client.publish(`dmj/v1/devices/${deviceId}/telemetry`, payload, { qos, dup: false }, (error) => {
      if (error) {
        errors += 1;
      } else {
        acknowledged += 1;
        latencies.push(now() - sentAt);
      }
    });
    timers[index] = setTimeout(publish, intervalMs);
  };
  publish();
}

await new Promise((resolve) => setTimeout(resolve, durationSeconds * 1000 + 500));
timers.forEach(clearTimeout);
await new Promise((resolve) => client.end(false, resolve));

const elapsedSeconds = (now() - started) / 1000;
const result = {
  scope: 'mqtt-transport-puback-only',
  devices,
  durationSeconds,
  qos,
  attempted,
  acknowledged,
  errors,
  transportLoss: attempted - acknowledged - errors,
  messagesPerSecond: attempted / elapsedSeconds,
  ackLatencyMs: {
    p50: percentile(latencies, 0.5),
    p95: percentile(latencies, 0.95),
    p99: percentile(latencies, 0.99),
  },
};
console.log(JSON.stringify(result, null, 2));
