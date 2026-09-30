#!/usr/bin/env bash
# Crea el release de GitHub (tag vX.Y.Z) con el APK ya publicado en ota/.
# Es el canal de respaldo de la app: los teléfonos que no llegan al puerto 999
# (datos móviles) buscan la actualización en el último release de GitHub.
#
# Uso: publicar-release-github.sh [version]   (por defecto, la de ota/latest.json)
# El token se pide por teclado y no se guarda en ningún archivo.
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/../.." && pwd)"
REPO="sekaishopml/Dmujeres-Traccar"
VERSION="${1:-$(python3 -c "import json;print(json.load(open('$RAIZ/ota/latest.json'))['version'])")}"
NOTAS="$(python3 -c "import json;print(json.load(open('$RAIZ/ota/latest.json')).get('notes',''))")"
APK="$RAIZ/ota/DMujeres-Tracking-$VERSION.apk"
test -f "$APK" || { echo "ERROR: no existe $APK"; exit 1; }

read -rsp "Token de GitHub: " TOKEN; echo
AUTH=(-H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json")

# Si el release ya existe, se reutiliza (solo se sube el APK si falta).
ID=$(curl -s "${AUTH[@]}" "https://api.github.com/repos/$REPO/releases/tags/v$VERSION" \
  | python3 -c "import json,sys;print(json.load(sys.stdin).get('id',''))")
if [ -z "$ID" ]; then
  CUERPO=$(python3 -c "import json,sys;print(json.dumps({'tag_name':'v$VERSION','target_commitish':'plataforma','name':'DMujeres Tracking $VERSION','body':sys.argv[1]}))" "$NOTAS")
  ID=$(curl -s "${AUTH[@]}" -d "$CUERPO" "https://api.github.com/repos/$REPO/releases" \
    | python3 -c "import json,sys;d=json.load(sys.stdin);print(d.get('id') or '');sys.stderr.write(str(d.get('message','')))")
  test -n "$ID" || { echo; echo "ERROR: no se pudo crear el release"; exit 1; }
  echo "release v$VERSION creado"
fi

curl -s "${AUTH[@]}" -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @"$APK" \
  "https://uploads.github.com/repos/$REPO/releases/$ID/assets?name=DMujeres-Tracking-$VERSION.apk" \
  | python3 -c "import json,sys;d=json.load(sys.stdin);print('APK subido:', d.get('browser_download_url') or d)"
unset TOKEN
