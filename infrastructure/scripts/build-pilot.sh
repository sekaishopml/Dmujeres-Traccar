#!/usr/bin/env bash
# Build del cliente de respaldo (fallback/) para el PILOTO:
#   1) suma 1 al versionCode en fallback/app/build.gradle (215 → 216 → …),
#   2) compila el APK release del flavor google (firmado con la clave de flota),
#   3) publica la actualización SOLO para macias (rollout.json con allow estricto).
#
# Uso:
#   bash infrastructure/scripts/build-pilot.sh              # build + publica a macias
#   bash infrastructure/scripts/build-pilot.sh --no-publish # solo compila (para tu teléfono/emulador)
#   bash infrastructure/scripts/build-pilot.sh --notes "Actualizar a la versión 2.1.5"
#
# Variables opcionales:
#   OTA_PUBLIC_BASE_URL   (default: http://68.168.20.219:999, modo legado HTTP)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GRADLE_FILE="$ROOT/fallback/app/build.gradle"
PUBLISH=1
NOTES=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-publish) PUBLISH=0 ;;
    --notes) NOTES="${2:?falta el texto de --notes}"; shift ;;
    *) echo "Opción no reconocida: $1" >&2; exit 1 ;;
  esac
  shift
done

[[ -f "$GRADLE_FILE" ]] || { echo "ERROR: no existe $GRADLE_FILE" >&2; exit 1; }

# ── 1) versionCode +1 (y versionName para mostrarlo en la OTA) ────────────────
CURRENT_CODE="$(grep -oE 'versionCode [0-9]+' "$GRADLE_FILE" | head -1 | awk '{print $2}')"
CURRENT_NAME="$(grep -oE "versionName '[^']+'" "$GRADLE_FILE" | head -1 | sed "s/versionName '//;s/'//")"
NEXT_CODE=$((CURRENT_CODE + 1))
# El nombre sube el último número igual que el código (2.1.5 → 2.1.6).
NEXT_NAME="$(python3 - "$CURRENT_NAME" <<'PY'
import sys
parts = sys.argv[1].split(".")
parts[-1] = str(int(parts[-1]) + 1)
print(".".join(parts))
PY
)"
python3 - "$GRADLE_FILE" "$NEXT_CODE" "$NEXT_NAME" <<'PY'
import pathlib, re, sys
path, code, name = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
s = path.read_text()
s = re.sub(r'versionCode \d+', f'versionCode {code}', s, count=1)
s = re.sub(r"versionName '[^']+'", f"versionName '{name}'", s, count=1)
path.write_text(s)
PY
echo ">> versión: $NEXT_NAME (código $NEXT_CODE)"

# ── 2) compilar ───────────────────────────────────────────────────────────────
cd "$ROOT/fallback"
./gradlew :app:testRegularReleaseUnitTest :app:assembleGoogleRelease
APK="$ROOT/fallback/app/build/outputs/apk/google/release/app-google-release.apk"
[[ -f "$APK" ]] || { echo "ERROR: no se generó el APK" >&2; exit 1; }

if [[ "$PUBLISH" -eq 0 ]]; then
  echo ">> APK listo (sin publicar): $APK"
  echo "   instalación en tu teléfono: adb install -r \"$APK\""
  exit 0
fi

# ── 3) publicar para la flota ────────────────────────────────────────────────
# Fail-closed: se fija la allowlist antes de publicar, para que ninguna versión
# nueva llegue a un teléfono que no esté dado de alta.
DEST_ROOT="${DMJ_PUBLISH_ROOT:-$ROOT}"
python3 - "$DEST_ROOT" <<'PY'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
allow = ["qa-f0", "macias", "jeremy", "kevin", "joseph", "david", "pilay"]
for directory in (root / "dashboard/public", root / "dashboard/build"):
    if not directory.is_dir():
        continue
    (directory / "rollout.json").write_text(
        json.dumps({"percent": 100, "paused": False, "allow": allow}, indent=2) + "\n"
    )
print(">> allowlist fijada: " + ", ".join(allow))
PY

NOTES="${NOTES:-Actualizar a la versión $NEXT_NAME}"
OTA_PUBLIC_BASE_URL="${OTA_PUBLIC_BASE_URL:-http://68.168.20.219:999}" \
  OTA_ALLOW_HTTP="${OTA_ALLOW_HTTP:-1}" \
  DMJ_PUBLISH_ROOT="$DEST_ROOT" \
  bash "$ROOT/infrastructure/scripts/publish-ota.sh" "$APK" "$NEXT_NAME" "$NOTES"

echo ">> macias verá el aviso al abrir la app (banner + botón ACTUALIZAR)"
echo ">> recuerda commitear cuando estés conforme: git add -A && git commit"
