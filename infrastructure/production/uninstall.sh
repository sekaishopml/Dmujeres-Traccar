#!/usr/bin/env bash
set -euo pipefail

DESTINO_UNIDADES="/etc/systemd/system"
UNIDADES=(dmj-api.service dmj-tracking.service dmj-web.service dmj-recuperacion.service)
CON_NGINX=0

for arg in "$@"; do
  case "$arg" in
    --nginx) CON_NGINX=1 ;;
    -h|--help)
      echo "uso: $0 [--nginx]"
      echo "  sin flags: retira solo las unidades systemd dmj-api/dmj-tracking/dmj-web/dmj-recuperacion"
      echo "  --nginx:   ademas retira el sitio nginx dmj-tracking (no purga el paquete)"
      exit 0
      ;;
    *)
      echo "argumento no reconocido: $arg" >&2
      exit 2
      ;;
  esac
done

if [[ "$EUID" -eq 0 ]]; then
  SUDO=()
elif sudo -n true 2>/dev/null; then
  SUDO=(sudo)
else
  echo "ERROR: se requiere root o sudo sin clave." >&2
  exit 1
fi

echo "[uninstall] disable --now de servicios"
"${SUDO[@]}" systemctl disable --now "${UNIDADES[@]}" 2>/dev/null || true

echo "[uninstall] eliminando unidades de $DESTINO_UNIDADES"
for unidad in "${UNIDADES[@]}"; do
  "${SUDO[@]}" rm -f "$DESTINO_UNIDADES/$unidad"
done
"${SUDO[@]}" systemctl daemon-reload
"${SUDO[@]}" systemctl reset-failed "${UNIDADES[@]}" 2>/dev/null || true

if [[ "$CON_NGINX" -eq 1 ]]; then
  echo "[uninstall] retirando sitio nginx dmj-tracking (el paquete nginx se conserva)"
  "${SUDO[@]}" rm -f /etc/nginx/sites-enabled/dmj-tracking
  "${SUDO[@]}" rm -f /etc/nginx/sites-available/dmj-tracking
  "${SUDO[@]}" rm -f /etc/nginx/sites-available/dmj-tracking-tls.example
  if command -v nginx >/dev/null 2>&1 && "${SUDO[@]}" nginx -t >/dev/null 2>&1; then
    "${SUDO[@]}" systemctl reload nginx 2>/dev/null || true
  fi
fi

echo "[uninstall] estado final"
for unidad in "${UNIDADES[@]}"; do
  printf '[uninstall]   %-22s active=%-8s enabled=%s\n' "$unidad" "$(systemctl is-active "$unidad" 2>/dev/null || true)" "$(systemctl is-enabled "$unidad" 2>/dev/null || true)"
done
echo "[uninstall] listo"
