#!/usr/bin/env bash
# Publica una nueva version de la app para que los telefonos se actualicen desde
# el boton "Actualizar" (o el aviso automatico). Copia el APK al directorio de
# updates y genera/actualiza latest.json.
#
# Uso:
#   publish-update.sh <ruta-apk> <versionCode> <versionName> [minVersionCode] [mandatory]
#
# Ejemplo:
#   publish-update.sh app-release.apk 5 1.0.5 3 false
#
# En produccion, el directorio de updates lo sirve el reverse proxy (Nginx) en /update/.
set -euo pipefail

APK_SRC="${1:?ruta del APK requerida}"
VERSION_CODE="${2:?versionCode requerido}"
VERSION_NAME="${3:?versionName requerido}"
MIN_VERSION_CODE="${4:-1}"
MANDATORY="${5:-false}"

# Base URL publica de descarga (donde el reverse proxy sirve /update/).
# Configurable por variable de entorno; en la app se usa {updateBaseUrl}/update/...
PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-https://TU-SERVIDOR}"

UPDATE_DIR="${UPDATE_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/update}"
mkdir -p "$UPDATE_DIR"

APK_NAME="dmujeres-${VERSION_NAME}.apk"
cp "$APK_SRC" "$UPDATE_DIR/$APK_NAME"

cat > "$UPDATE_DIR/latest.json" <<JSON
{
  "versionCode": ${VERSION_CODE},
  "versionName": "${VERSION_NAME}",
  "url": "${PUBLIC_BASE_URL}/update/${APK_NAME}",
  "minVersionCode": ${MIN_VERSION_CODE},
  "mandatory": ${MANDATORY},
  "notes": "Version ${VERSION_NAME}"
}
JSON

echo "[publish-update] Publicado ${APK_NAME} (versionCode=${VERSION_CODE}, min=${MIN_VERSION_CODE})"
echo "[publish-update] latest.json:"
cat "$UPDATE_DIR/latest.json"
