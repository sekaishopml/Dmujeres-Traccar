// PT-103 — Fase 1: CRUD API + aislamiento de permisos multi-usuario
// Configuración desde entorno (ver README.md)
const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL || 'admin@dmj.local';
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD || 'Admin123!';
const BASE = process.env.TEST_SERVER_URL || 'http://localhost:8082';

const results = [];
function record(id, ok, detail) { results.push({ id, ok, detail }); console.log(`${ok ? 'PASS' : 'FAIL'} ${id}: ${detail}`); }

async function login(email, password) {
  const r = await fetch(`${BASE}/api/session`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ email, password }), redirect: 'manual' });
  return { cookie: r.headers.get('set-cookie')?.split(';')[0], status: r.status };
}

async function main() {
  const admin = await login('admin@dmj.local', 'Admin123!');
  const H = { cookie: admin.cookie, 'Content-Type': 'application/json' };

  // 1. CRUD grupo
  const grp = await fetch(`${BASE}/api/groups`, { method: 'POST', headers: H, body: JSON.stringify({ name: `Flota Demo ${Date.now()}` }) });
  const group = await grp.json();
  record('CRUD-1 crear grupo', grp.status === 200 && group.id > 0, `id=${group.id}`);

  // 2. CRUD dispositivo (en grupo)
  const dev = await fetch(`${BASE}/api/devices`, { method: 'POST', headers: H, body: JSON.stringify({ name: 'Camioneta 7', uniqueId: `dev-${Date.now()}`, groupId: group.id, category: 'vehicle' }) });
  const device = await dev.json();
  record('CRUD-2 crear dispositivo', dev.status === 200 && device.id > 0, `id=${device.id}`);

  // 3. GET por id + filtro
  const g1 = await fetch(`${BASE}/api/devices/${device.id}`, { headers: { cookie: admin.cookie } });
  record('CRUD-3 get dispositivo por id', g1.status === 200, `HTTP ${g1.status}`);
  const list = await (await fetch(`${BASE}/api/devices`, { headers: { cookie: admin.cookie } })).json();
  record('CRUD-4 listar dispositivos', Array.isArray(list) && list.length >= 2, `${list.length} dispositivos`);

  // 4. UPDATE
  const upd = await fetch(`${BASE}/api/devices/${device.id}`, { method: 'PUT', headers: H, body: JSON.stringify({ ...device, name: 'Camioneta 7 RENOMBRADA' }) });
  record('CRUD-5 update dispositivo', upd.status === 200, `HTTP ${upd.status}`);

  // 5. Usuario no-admin: crear usuario con deviceLimit
  const user = await fetch(`${BASE}/api/users`, { method: 'POST', headers: H, body: JSON.stringify({ name: 'Operador', email: `op-${Date.now()}@dmj.local`, password: 'Op123456!' }) });
  const operator = await user.json();
  record('CRUD-6 crear usuario no-admin', user.status === 200 && operator.id > 0 && operator.administrator === false, `id=${operator.id} admin=${operator.administrator}`);

  // 6. Permiso: operador ve SOLO el dispositivo asignado
  await fetch(`${BASE}/api/permissions`, { method: 'POST', headers: H, body: JSON.stringify({ userId: operator.id, deviceId: device.id }) });
  const opLogin = await login(operator.email, 'Op123456!');
  const opDevices = await (await fetch(`${BASE}/api/devices`, { headers: { cookie: opLogin.cookie } })).json();
  const ok1 = opDevices.length === 1 && opDevices[0].uniqueId === device.uniqueId;
  record('CRUD-7 aislamiento: operador ve solo su dispositivo', ok1, `ve ${opDevices.length}: ${opDevices.map(d => d.uniqueId).join(',')}`);

  // 7. Aislamiento: operador NO puede ver dispositivos de admin (demo-001)
  const opAll = await (await fetch(`${BASE}/api/devices?all=true`, { headers: { cookie: opLogin.cookie } })).json();
  const ok2 = !opAll.some(d => d.uniqueId === 'demo-001');
  record('CRUD-8 aislamiento: no ve dispositivo ajeno', ok2, `total=${opAll.length}`);

  // 8. Geofence CRUD
  const geo = await fetch(`${BASE}/api/geofences`, { method: 'POST', headers: H, body: JSON.stringify({ name: `Zona ${Date.now()}`, area: 'CIRCLE (1, -33.45, -70.66, 5000)' }) });
  const geofence = await geo.json();
  record('CRUD-9 crear geofence', geo.status === 200 && geofence.id > 0, `id=${geofence.id}`);

  // 9. DELETE geofence + device
  const dg = await fetch(`${BASE}/api/geofences/${geofence.id}`, { method: 'DELETE', headers: { cookie: admin.cookie } });
  const dd = await fetch(`${BASE}/api/devices/${device.id}`, { method: 'DELETE', headers: { cookie: admin.cookie } });
  record('CRUD-10 delete geofence+device', dg.status === 204 && dd.status === 204, `HTTP ${dg.status}/${dd.status}`);

  const fails = results.filter(r => !r.ok);
  console.log(`\nRESULTADO: ${results.length - fails.length}/${results.length} pruebas PASARON`);
  process.exit(fails.length ? 1 : 0);
}
main().catch(e => { console.error('ERROR:', e.message); process.exit(1); });
