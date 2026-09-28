#!/usr/bin/env bash
set -euo pipefail

RAIZ_REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ORIGEN_UNIDADES="$RAIZ_REPO/infrastructure/production/systemd"
DESTINO_UNIDADES="/etc/systemd/system"
UNIDADES=(dmj-api.service dmj-tracking.service dmj-web.service dmj-recuperacion.service)
PATRON_PROCESO="node24 src/servidor.js"
PUERTO_API=8081
PUERTO_WEB=25565

if [[ "$EUID" -eq 0 ]]; then
  SUDO=()
elif sudo -n true 2>/dev/null; then
  SUDO=(sudo)
else
  echo "ERROR: se requiere root o sudo sin clave para instalar unidades systemd." >&2
  exit 1
fi

for unidad in "${UNIDADES[@]}"; do
  if [[ ! -f "$ORIGEN_UNIDADES/$unidad" ]]; then
    echo "ERROR: falta $ORIGEN_UNIDADES/$unidad" >&2
    exit 1
  fi
done

matar_procesos_sueltos() {
  local pid="" cwd="" cgroup="" cmdline="" matados=()
  while read -r pid; do
    [[ -n "$pid" ]] || continue
    cwd="$(readlink -f "/proc/$pid/cwd" 2>/dev/null || true)"
    case "$cwd" in
      "$RAIZ_REPO/services"/*) ;;
      *) continue ;;
    esac
    cgroup="$(cat "/proc/$pid/cgroup" 2>/dev/null || true)"
    if grep -qE '/dmj-(api|tracking|web)\.service([[:space:]]|$)' <<<"$cgroup"; then
      continue
    fi
    cmdline="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)"
    echo "[install] deteniendo proceso suelto pid=$pid (cwd=$cwd): $cmdline"
    if kill "$pid" 2>/dev/null; then
      matados+=("$pid")
    fi
  done < <(pgrep -f "$PATRON_PROCESO" 2>/dev/null || true)

  for pid in "${matados[@]:-}"; do
    [[ -n "$pid" ]] || continue
    for _ in $(seq 1 20); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.25
    done
    if kill -0 "$pid" 2>/dev/null; then
      echo "[install] proceso pid=$pid no cerro en 5 s: SIGKILL"
      kill -9 "$pid" 2>/dev/null || true
    fi
  done
}

esperar_http() {
  local url="$1" codigo=""
  for _ in $(seq 1 20); do
    codigo="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$url" || true)"
    [[ "$codigo" == "200" ]] && break
    sleep 0.5
  done
  printf '%s' "$codigo"
}

echo "[install] terminando procesos sueltos de $RAIZ_REPO (si existen)"
matar_procesos_sueltos

echo "[install] copiando unidades a $DESTINO_UNIDADES"
for unidad in "${UNIDADES[@]}"; do
  "${SUDO[@]}" install -m 0644 "$ORIGEN_UNIDADES/$unidad" "$DESTINO_UNIDADES/$unidad"
done

echo "[install] daemon-reload + enable --now"
"${SUDO[@]}" systemctl daemon-reload
"${SUDO[@]}" systemctl enable --now "${UNIDADES[@]}"

sleep 2

estado=0
echo "[install] estado de unidades"
for unidad in "${UNIDADES[@]}"; do
  activo="$(systemctl is-active "$unidad" 2>/dev/null || true)"
  habilitado="$(systemctl is-enabled "$unidad" 2>/dev/null || true)"
  printf '[install]   %-22s active=%-8s enabled=%s\n' "$unidad" "$activo" "$habilitado"
  [[ "$activo" == "active" ]] || estado=1
done

echo "[install] verificacion HTTP local"
codigo_api="$(esperar_http "http://127.0.0.1:$PUERTO_API/api/v1/health")"
codigo_web="$(esperar_http "http://127.0.0.1:$PUERTO_WEB/")"
printf '[install]   GET http://127.0.0.1:%s/api/v1/health -> %s\n' "$PUERTO_API" "${codigo_api:-sin_respuesta}"
printf '[install]   GET http://127.0.0.1:%s/                 -> %s\n' "$PUERTO_WEB" "${codigo_web:-sin_respuesta}"
[[ "$codigo_api" == "200" ]] || estado=1
[[ "$codigo_web" == "200" ]] || estado=1

if [[ "$estado" -eq 0 ]]; then
  echo "[install] OK"
else
  echo "[install] ATENCION: alguna unidad o verificacion HTTP fallo; revise journalctl -u dmj-api/dmj-tracking/dmj-web" >&2
fi
exit "$estado"
