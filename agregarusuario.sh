#!/usr/bin/env bash
set -euo pipefail

USUARIO="Fernando"
CLAVE="cctv2026"

DB="dmj-db"
ID="$(printf '%s' "$USUARIO" | tr '[:upper:]' '[:lower:]')"
NOMBRE="$(printf '%s' "$ID" | sed 's/^./\U&/')"
CORREO="$ID@dmujeres.local"
CONFIG='{"mobile.intervalSeconds":10,"mobile.minIntervalSeconds":10,"mobile.distanceMeters":10,"mobile.angleDegrees":15,"mobile.accuracy":"high","mobile.bufferEnabled":true,"mobile.bufferMax":5000,"mobile.bufferPolicy":"drop_oldest","mobile.ackTimeoutSeconds":15,"mobile.maxRetries":30}'

sql() {
  docker exec "$DB" psql -U traccar -d traccar -qtAc "$1" >/dev/null
}

sql "INSERT INTO tc_devices (name, uniqueid, attributes) SELECT '$NOMBRE', '$ID', '{}' WHERE NOT EXISTS (SELECT 1 FROM tc_devices WHERE uniqueid = '$ID')"

sql "UPDATE tc_devices SET name = '$NOMBRE', attributes = (COALESCE(NULLIF(attributes, '')::jsonb, '{}'::jsonb) || '$CONFIG'::jsonb)::text WHERE uniqueid = '$ID'"

read -r HASH SALT <<<"$(python3 -c "
import hashlib, os
salt = os.urandom(24)
print(hashlib.pbkdf2_hmac('sha1', '$CLAVE'.encode(), salt, 1000, dklen=24).hex(), salt.hex())
")"

sql "INSERT INTO tc_users (name, email, hashedpassword, salt, readonly, administrator, latitude, longitude, zoom) SELECT '$NOMBRE', '$CORREO', '$HASH', '$SALT', false, false, 0, 0, 0 WHERE NOT EXISTS (SELECT 1 FROM tc_users WHERE email = '$CORREO')"

sql "INSERT INTO tc_user_device (userid, deviceid) SELECT u.id, d.id FROM tc_users u, tc_devices d WHERE u.email = '$CORREO' AND d.uniqueid = '$ID' AND NOT EXISTS (SELECT 1 FROM tc_user_device ud WHERE ud.userid = u.id AND ud.deviceid = d.id)"

sql "INSERT INTO tc_user_device (userid, deviceid) SELECT u.id, d.id FROM tc_users u, tc_devices d WHERE u.email IN ('admin@dmj.local', 'cctv') AND d.uniqueid = '$ID' AND NOT EXISTS (SELECT 1 FROM tc_user_device ud WHERE ud.userid = u.id AND ud.deviceid = d.id)"

python3 -c "
import json, pathlib
for directory in ('/DMujeres-Tracking/dashboard/public', '/DMujeres-Tracking/dashboard/build'):
    path = pathlib.Path(directory) / 'rollout.json'
    data = json.loads(path.read_text())
    if '$ID' not in data.get('allow', []):
        data.setdefault('allow', []).append('$ID')
        path.write_text(json.dumps(data, indent=2) + '\n')
"

ESTADO="$(curl -s -o /dev/null -w '%{http_code}' -H 'X-Api-Key: cctv2026' -H "X-Device-Id: $ID" http://68.168.20.219:999/api/mobile/v1/config)"

echo ""
echo "Usuario listo: $NOMBRE"
echo "  App:   usuario $ID   clave cctv2026"
echo "  Panel: $CORREO   clave $CLAVE"
echo "  Login app: $ESTADO"
echo ""
