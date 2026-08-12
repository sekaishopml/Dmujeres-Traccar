// http-e2e.mjs — HTTP fallback batch con el mismo envelope/idempotencia
import crypto from 'node:crypto';

const BASE = process.env.TEST_SERVER_URL || 'http://localhost:8082';
const API_KEY = process.env.TEST_MOBILE_API_KEY || 'dev-key';
const deviceId = process.env.MQTT_DEVICE_ID || 'demo-001';
const messageId = process.env.MQTT_MESSAGE_ID || `01JHTTP${Date.now().toString(36).toUpperCase()}PAD`;
const sequence = Number(process.env.MQTT_SEQUENCE || Date.now());
const sentAt = process.env.MQTT_SENT_AT || new Date().toISOString();

const envelope = {
  schema: 1,
  type: 'position',
  messageId,
  deviceId,
  sequence,
  sentAt,
  observedAt: sentAt,
  payload: { latitude: -33.44, longitude: -70.66, accuracy: 8, speed: 36, bearing: 90, altitude: 500 },
};

async function submit() {
  const response = await fetch(`${BASE}/api/mobile/v1/positions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Api-Key': API_KEY },
    body: JSON.stringify([envelope]),
  });
  return { status: response.status, body: await response.json() };
}

const first = await submit();
console.log('primer envío:', JSON.stringify(first));
const duplicate = await submit();
console.log('segundo envío:', JSON.stringify(duplicate));

const statuses = [first.body.results?.[0]?.status, duplicate.body.results?.[0]?.status];
const ok = first.status === 200 && statuses[0] === 'accepted'
  && duplicate.status === 200 && statuses[1] === 'duplicate';
console.log(ok ? '\nRESULTADO: PASS (accepted + duplicate)' : '\nRESULTADO: FAIL');
process.exit(ok ? 0 : 1);
