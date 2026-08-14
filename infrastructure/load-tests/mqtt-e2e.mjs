import mqtt from 'mqtt';

const url = process.env.MQTT_URL || 'mqtt://127.0.0.1:1883';
const deviceId = process.env.MQTT_DEVICE_ID || 'demo-001';
const messageId = process.env.MQTT_MESSAGE_ID || `01JDMJ${Date.now().toString(36).toUpperCase().padStart(10, '0')}`;
const sequence = Number(process.env.MQTT_SEQUENCE || Date.now());
const sentAt = process.env.MQTT_SENT_AT || new Date().toISOString();
const observedAt = process.env.MQTT_OBSERVED_AT || sentAt;
const topic = `dmj/v1/devices/${deviceId}/telemetry`;
const ackTopic = `dmj/v1/devices/${deviceId}/ack`;

const options = { protocolVersion: 5, reconnectPeriod: 0, clean: true,
  ...(process.env.MQTT_USER ? { username: process.env.MQTT_USER } : {}),
  ...(process.env.MQTT_PASSWORD ? { password: process.env.MQTT_PASSWORD } : {}) };
const consumer = mqtt.connect(url, options);
const publisher = mqtt.connect(url, options);

const connect = client => new Promise((resolve, reject) => {
  client.once('connect', resolve);
  client.once('error', reject);
});

await Promise.all([connect(consumer), connect(publisher)]);
await new Promise((resolve, reject) => {
  consumer.subscribe(ackTopic, { qos: 1 }, error => error ? reject(error) : resolve());
});

const payload = JSON.stringify({
  schema: 1,
  type: 'position',
  messageId,
  deviceId,
  sequence,
  sentAt,
  observedAt,
  payload: { latitude: -33.45, longitude: -70.67, accuracy: 8, speed: 36, bearing: 180, altitude: 520 },
});

const ack = new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(new Error('ACK timeout')), 10000);
  consumer.on('message', (receivedTopic, message) => {
    if (receivedTopic !== ackTopic) return;
    const value = JSON.parse(message.toString());
    if (value.messageId === messageId) {
      clearTimeout(timer);
      resolve(value);
    }
  });
});

await new Promise((resolve, reject) => publisher.publish(topic, payload, { qos: 1 }, error => error ? reject(error) : resolve()));
const result = await ack;
console.log(JSON.stringify({ topic, messageId, sequence, ack: result }, null, 2));

await Promise.all([
  new Promise(resolve => consumer.end(false, resolve)),
  new Promise(resolve => publisher.end(false, resolve)),
]);

if (!['accepted', 'duplicate'].includes(result.status)) process.exit(1);
