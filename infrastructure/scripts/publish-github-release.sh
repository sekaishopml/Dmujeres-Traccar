#!/usr/bin/env bash
# Publica la release de GitHub del repo raíz con el APK adjunto.
#
# La app usa este canal como RESPALDO cuando no alcanza el puerto 999
# (`UpdateManager.fetchGithubLatest`): sin release nueva, un teléfono en datos
# móviles ve la última release (vieja) y no muestra el banner de actualización.
#
# Uso:
#   GITHUB_TOKEN=<token con permiso de contenidos> \
#     bash infrastructure/scripts/publish-github-release.sh <apk> <version>
#
# El token NO se guarda: solo vive en el entorno de esta ejecución.
set -euo pipefail

APK="${1:?Uso: publish-github-release.sh <apk> <version>}"
VERSION="${2:?Uso: publish-github-release.sh <apk> <version>}"
REPO="${GITHUB_REPO:-sekaishopml/Dmujeres-Traccar}"
TAG="v$VERSION"
ASSET="DMujeres-Tracking-$VERSION.apk"
NOTES="${OTA_NOTES:-Actualizar a la versión $VERSION}"
TOKEN="${GITHUB_TOKEN:?ERROR: define GITHUB_TOKEN (permiso de contenidos)}"

[[ -f "$APK" ]] || { echo "ERROR: no existe el APK: $APK" >&2; exit 1; }
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "ERROR: versión inválida: $VERSION" >&2; exit 1; }

api() {
  curl -sf -H "Authorization: token $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -H "User-Agent: dmujeres-release" "$@"
}

echo ">> buscando release $TAG en $REPO"
RELEASE_ID="$(api "https://api.github.com/repos/$REPO/releases/tags/$TAG" 2>/dev/null \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' 2>/dev/null || true)"

if [[ -z "$RELEASE_ID" ]]; then
  echo ">> creando release $TAG"
  RELEASE_ID="$(api -X POST "https://api.github.com/repos/$REPO/releases" \
    -d "$(VERSION="$VERSION" TAG="$TAG" NOTES="$NOTES" python3 -c '
import json, os
print(json.dumps({
    "tag_name": os.environ["TAG"],
    "name": os.environ["TAG"],
    "body": os.environ["NOTES"],
    "draft": False,
    "prerelease": False,
}))')" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')"
else
  echo ">> release existente (id $RELEASE_ID): se conserva"
fi

if api "https://api.github.com/repos/$REPO/releases/$RELEASE_ID/assets" \
    | grep -q "\"name\": \"$ASSET\""; then
  echo ">> el asset $ASSET ya existe; no se reemplaza"
else
  echo ">> subiendo $ASSET ($(du -h "$APK" | cut -f1))"
  curl -sf -X POST \
    -H "Authorization: token $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary @"$APK" \
    "https://uploads.github.com/repos/$REPO/releases/$RELEASE_ID/assets?name=$ASSET" \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print(">> asset:", d["name"], d["browser_download_url"])'
fi

echo ">> listo: https://github.com/$REPO/releases/tag/$TAG"
