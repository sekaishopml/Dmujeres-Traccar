#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# DMujeres Tracking - Validacion E2E de la plataforma nueva (FASE 9)
#
# Ejercita el conjunto nuevo (services/api, services/tracking, services/web,
# apps/web, systemd y Nginx) SIN escribir en produccion. Las unicas lecturas
# de produccion son:
#   * `systemctl is-active dmj-traccar` debe estar inactivo (panel viejo retirado)
#   * `curl http://127.0.0.1:999/` (panel nuevo) y OTA/APK por :999
#   * conteos de posiciones: si el contenedor de produccion sigue corriendo se
#     comparan con `tc_positions`; si el legado ya fue retirado (FASE 12) solo
#     se exige que la base nueva tenga posiciones (solo lectura)
#
# Es idempotente: no deja cambios permanentes (los smokes limpian lo suyo, la
# sesion de prueba se cierra con logout y todo lo temporal vive en un mktemp).
#
# Uso:
#   bash scripts/validation/e2e.sh              # incluye npm run build de apps/web
#   bash scripts/validation/e2e.sh --sin-build  # omite el build (si otro proceso compila)
#
# Variables (todas opcionales):
#   DMJ_PROYECTO            raiz de la plataforma nueva (default /home/DMujeres-Tracking)
#   DMJ_TEST_EMAIL          usuario de prueba (default del contrato de QA)
#   DMJ_TEST_PASSWORD       clave del usuario de prueba (default del contrato de QA)
#   DMJ_PROD_DB_CONTAINER   contenedor de produccion (default dmj-db; solo se
#                           usa si sigue corriendo para comparar conteos)
#   DMJ_PROD_DB_USER        usuario de produccion (default traccar)
#   DMJ_PROD_DB_NAME        base de produccion (default traccar)
#   DMJ_NUEVA_DB_CONTAINER  contenedor de la base nueva (default dmt-db)
#   DMJ_NUEVA_DB_USER       usuario de la base nueva (default dmt)
#   DMJ_NUEVA_DB_NAME       base nueva (default dmujeres)
#
# Salida: PASS/FAIL/AVISO por paso y codigo de salida 0 solo si no hay FAIL.
# ---------------------------------------------------------------------------
set -euo pipefail

PROYECTO="${DMJ_PROYECTO:-/home/DMujeres-Tracking}"
DMJ_TEST_EMAIL="${DMJ_TEST_EMAIL:-fernando@dmujeres.local}"
DMJ_TEST_PASSWORD="${DMJ_TEST_PASSWORD:-cctv2026}"

PROD_DB_CONTAINER="${DMJ_PROD_DB_CONTAINER:-dmj-db}"
PROD_DB_USER="${DMJ_PROD_DB_USER:-traccar}"
PROD_DB_NAME="${DMJ_PROD_DB_NAME:-traccar}"
NUEVA_DB_CONTAINER="${DMJ_NUEVA_DB_CONTAINER:-dmt-db}"
NUEVA_DB_USER="${DMJ_NUEVA_DB_USER:-dmt}"
NUEVA_DB_NAME="${DMJ_NUEVA_DB_NAME:-dmujeres}"

SIN_BUILD=0
for argumento in "$@"; do
  case "$argumento" in
    --sin-build) SIN_BUILD=1 ;;
    --help|-h)
      sed -n '2,30p' "$0"
      exit 0
      ;;
    *)
      echo "Argumento no reconocido: $argumento (use --sin-build o --help)" >&2
      exit 2
      ;;
  esac
done

if [ ! -d "$PROYECTO/services" ]; then
  echo "FAIL  No existe $PROYECTO/services; ajuste DMJ_PROYECTO" >&2
  exit 2
fi

TMP="$(mktemp -d /tmp/dmj-e2e.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT

n_pass=0
n_fail=0
n_aviso=0
REGISTRO=()

paso() {
  local estado="$1" nombre="$2" detalle="${3:-}"
  case "$estado" in
    PASS) n_pass=$((n_pass + 1)) ;;
    FAIL) n_fail=$((n_fail + 1)) ;;
    AVISO) n_aviso=$((n_aviso + 1)) ;;
  esac
  printf '%-5s %s%s\n' "$estado" "$nombre" "${detalle:+ -> $detalle}"
  REGISTRO+=("$estado|$nombre|$detalle")
}

casi_log() {
  # Muestra las ultimas lineas de un log capturado (para fallos).
  local log="$1"
  if [ -s "$log" ]; then
    echo "      ---- ultimas lineas de $(basename "$log") ----"
    tail -n 15 "$log" | sed 's/^/      /'
    echo "      ------------------------------------------"
  fi
}

resumen_log() {
  # Extrae la linea final de resultado de un smoke.
  local log="$1"
  grep -E '^(PASS|FAIL|RESULTADO|Smoke)' "$log" | tail -n 3 | tr '\n' ' ' || true
}

leer_total_json() {
  # Lee `.total` (numerico) de un archivo JSON; imprime -1 si no se puede.
  node24 -e '
    const fs = require("fs");
    try {
      const datos = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
      process.stdout.write(Number.isFinite(datos.total) ? String(datos.total) : "-1");
    } catch {
      process.stdout.write("-1");
    }
  ' "$1"
}

echo "================================================================"
echo " DMujeres Tracking - E2E FASE 9"
echo " plataforma: $PROYECTO"
echo " build web:  $([ "$SIN_BUILD" -eq 1 ] && echo 'omitido (--sin-build)' || echo 'incluido')"
echo " fecha:      $(date -Is)"
echo "================================================================"

# ---------------------------------------------------------------------------
# 1. Sintaxis de todos los servicios (node24 --check)
# ---------------------------------------------------------------------------
archivos_tracking=("$PROYECTO"/services/tracking/src/*.js)
archivos_api=("$PROYECTO"/services/api/src/*.js "$PROYECTO"/services/api/smoke.mjs)
archivos_web=("$PROYECTO"/services/web/src/*.js)

check_grupo() {
  local etiqueta="$1"
  shift
  local total=0 fallidos=0 archivo
  for archivo in "$@"; do
    [ -f "$archivo" ] || continue
    total=$((total + 1))
    if ! node24 --check "$archivo" >/dev/null 2>&1; then
      fallidos=$((fallidos + 1))
      echo "      sintaxis invalida: $archivo"
    fi
  done
  if [ "$total" -eq 0 ]; then
    paso FAIL "node24 --check $etiqueta" "no se encontraron archivos"
  elif [ "$fallidos" -eq 0 ]; then
    paso PASS "node24 --check $etiqueta" "$total archivo(s)"
  else
    paso FAIL "node24 --check $etiqueta" "$fallidos de $total con errores"
  fi
}

check_grupo "services/tracking" "${archivos_tracking[@]}"
check_grupo "services/api" "${archivos_api[@]}"
check_grupo "services/web" "${archivos_web[@]}"

# ---------------------------------------------------------------------------
# 2. Smoke de services/api (arranca su propio servidor en 18081 y limpia)
# ---------------------------------------------------------------------------
if (cd "$PROYECTO/services/api" && node24 smoke.mjs) >"$TMP/smoke-api.log" 2>&1; then
  paso PASS "smoke services/api" "$(resumen_log "$TMP/smoke-api.log")"
else
  paso FAIL "smoke services/api" "ver log"
  casi_log "$TMP/smoke-api.log"
fi

# ---------------------------------------------------------------------------
# 3. Smoke de services/tracking (puerto efimero, dispositivo qa-f0, limpia)
# ---------------------------------------------------------------------------
if (cd "$PROYECTO/services/tracking" && node24 src/smoke.mjs) >"$TMP/smoke-tracking.log" 2>&1; then
  paso PASS "smoke services/tracking" "$(resumen_log "$TMP/smoke-tracking.log")"
else
  paso FAIL "smoke services/tracking" "ver log"
  casi_log "$TMP/smoke-tracking.log"
fi

# ---------------------------------------------------------------------------
# 4. Build de apps/web (salvo --sin-build)
# ---------------------------------------------------------------------------
if [ "$SIN_BUILD" -eq 1 ]; then
  paso AVISO "build apps/web" "omitido por --sin-build"
else
  if (cd "$PROYECTO/apps/web" && npm run build) >"$TMP/build-web.log" 2>&1; then
    paso PASS "build apps/web" "$(grep -E 'built in|modules transformed' "$TMP/build-web.log" | tail -n 1)"
  else
    paso FAIL "build apps/web" "ver log"
    casi_log "$TMP/build-web.log"
  fi
fi

# ---------------------------------------------------------------------------
# 5. Servicios systemd (solo consulta de estado; dmj-traccar como control)
# ---------------------------------------------------------------------------
for servicio in dmj-api dmj-tracking dmj-web nginx; do
  estado="$(systemctl is-active "$servicio" 2>/dev/null || true)"
  if [ "$estado" = "active" ]; then
    paso PASS "systemd $servicio" "$estado"
  else
    paso FAIL "systemd $servicio" "$estado"
  fi
done
estado_control="$(systemctl is-active dmj-traccar 2>/dev/null || true)"
if [ "$estado_control" = "inactive" ]; then
  paso PASS "systemd dmj-traccar detenido (panel viejo retirado)" "$estado_control"
else
  paso FAIL "systemd dmj-traccar detenido (panel viejo retirado)" "$estado_control"
fi
if ss -ltn 2>/dev/null | grep -q ':5055 '; then
  paso PASS "OsmAnd publico :5055 en la plataforma nueva" "escuchando"
else
  paso FAIL "OsmAnd publico :5055 en la plataforma nueva" "sin escucha"
fi

# ---------------------------------------------------------------------------
# 6. Puertas HTTP
# ---------------------------------------------------------------------------
comprobar_http() {
  local url="$1" esperado="$2" etiqueta="$3"
  local codigo
  codigo="$(curl -sS -o "$TMP/http.out" -w '%{http_code}' --max-time 10 "$url" 2>"$TMP/http.err" || true)"
  if [ "$codigo" = "$esperado" ]; then
    paso PASS "$etiqueta" "HTTP $codigo"
  else
    paso FAIL "$etiqueta" "HTTP ${codigo:-sin respuesta} $(tr '\n' ' ' <"$TMP/http.err" | cut -c1-120)"
  fi
}

comprobar_http "http://127.0.0.1/" 200 "nginx :80 (entrada nueva)"
comprobar_http "http://127.0.0.1/api/v1/health" 200 "API /api/v1/health por :80"
comprobar_http "http://127.0.0.1:25565/" 200 "services/web :25565 directo"
comprobar_http "http://127.0.0.1:999/" 200 "panel nuevo :999 (plataforma)"
comprobar_http "http://127.0.0.1:999/DMujeres-Tracking-2.1.73.apk" 200 "OTA APK por :999"

# Canal movil de la App por :999 (config con clave y equipo reales)
codigo_movil="$(curl -sS -o "$TMP/movil.json" -w '%{http_code}' --max-time 10 \
  -H "X-Api-Key: ${DMJ_TEST_MOVIL_KEY:-cctv2026}" -H "X-Device-Id: macias" \
  http://127.0.0.1:999/api/mobile/v1/config || true)"
if [ "$codigo_movil" = "200" ]; then
  paso PASS "canal movil /api/mobile/v1/config por :999" "HTTP 200"
else
  paso FAIL "canal movil /api/mobile/v1/config por :999" "HTTP ${codigo_movil:-sin respuesta}"
fi

# Manifiesto OTA por :999
codigo_ota="$(curl -sS -o "$TMP/ota.json" -w '%{http_code}' --max-time 10 \
  -H "X-Api-Key: ${DMJ_TEST_MOVIL_KEY:-cctv2026}" \
  "http://127.0.0.1:999/api/mobile/v1/ota?deviceId=macias&versionCode=275" || true)"
if [ "$codigo_ota" = "200" ]; then
  paso PASS "OTA manifiesto por :999" "HTTP 200"
else
  paso FAIL "OTA manifiesto por :999" "HTTP ${codigo_ota:-sin respuesta}"
fi

# Contenido de health: {"estado":"ok"}
codigo_health="$(curl -sS -o "$TMP/health.json" -w '%{http_code}' --max-time 10 http://127.0.0.1/api/v1/health || true)"
estado_health="$(node24 -e '
  const fs = require("fs");
  try { process.stdout.write(JSON.parse(fs.readFileSync(process.argv[1], "utf8")).estado ?? ""); }
  catch { process.stdout.write(""); }
' "$TMP/health.json" 2>/dev/null || true)"
if [ "$codigo_health" = "200" ] && [ "$estado_health" = "ok" ]; then
  paso PASS "health devuelve estado ok" "estado=$estado_health"
else
  paso FAIL "health devuelve estado ok" "http=$codigo_health estado='${estado_health:-?}'"
fi

# ---------------------------------------------------------------------------
# 7. Login real por la puerta de entrada + GET /api/v1/fleet
# ---------------------------------------------------------------------------
DMJ_TEST_EMAIL="$DMJ_TEST_EMAIL" DMJ_TEST_PASSWORD="$DMJ_TEST_PASSWORD" node24 -e '
  const fs = require("fs");
  fs.writeFileSync(process.argv[1], JSON.stringify({
    usuario: process.env.DMJ_TEST_EMAIL,
    clave: process.env.DMJ_TEST_PASSWORD,
  }));
' "$TMP/login-body.json"
chmod 600 "$TMP/login-body.json"

codigo_login="$(curl -sS -o "$TMP/login.json" -D "$TMP/login.headers" -w '%{http_code}' \
  --max-time 10 -H 'Content-Type: application/json' \
  --data-binary @"$TMP/login-body.json" \
  http://127.0.0.1/api/v1/auth/login || true)"

cookie="$(grep -i '^set-cookie: dmj_sesion=' "$TMP/login.headers" 2>/dev/null | head -n 1 \
  | sed 's/^[Ss]et-[Cc]ookie: //' | cut -d';' -f1 || true)"

if [ "$codigo_login" = "200" ] && [ -n "$cookie" ]; then
  paso PASS "login real por :80" "HTTP $codigo_login + cookie dmj_sesion"
else
  paso FAIL "login real por :80" "HTTP $codigo_login (revise DMJ_TEST_EMAIL/DMJ_TEST_PASSWORD)"
fi

if [ -n "$cookie" ]; then
  codigo_fleet="$(curl -sS -o "$TMP/fleet.json" -w '%{http_code}' --max-time 15 \
    -H "Cookie: $cookie" 'http://127.0.0.1/api/v1/fleet?pagina=1&tamano=5' || true)"
  total_fleet="$(leer_total_json "$TMP/fleet.json")"
  total_fleet="${total_fleet:--1}"
  if [ "$codigo_fleet" = "200" ] && [ "$total_fleet" -ge 1 ] 2>/dev/null; then
    paso PASS "GET /api/v1/fleet con sesion" "HTTP $codigo_fleet total=$total_fleet"
  else
    paso FAIL "GET /api/v1/fleet con sesion" "HTTP $codigo_fleet total=$total_fleet"
  fi

  # Ruteo estimado por calles: servicio local vivo y campo `estimados` en el
  # replay (los tramos a saltos se dibujan pegados a las vias).
  salud_ruteo="$(curl -sS --max-time 5 http://127.0.0.1:8992/health 2>/dev/null || true)"
  if [ "$salud_ruteo" = "ok" ]; then
    paso PASS "servicio de ruteo por calles (8992)" "health=ok"
  else
    paso FAIL "servicio de ruteo por calles (8992)" "health='${salud_ruteo:-sin respuesta}'"
  fi

  id_replay="$(node24 -e '
    const fs = require("fs");
    try {
      const flota = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
      process.stdout.write(flota.datos?.[0]?.idPublico ?? "");
    } catch { process.stdout.write(""); }
  ' "$TMP/fleet.json" 2>/dev/null || true)"
  if [ -n "$id_replay" ]; then
    desde_replay="$(node24 -e 'process.stdout.write(new Date(Date.now() - 7 * 86400000).toISOString())')"
    hasta_replay="$(node24 -e 'process.stdout.write(new Date().toISOString())')"
    codigo_replay="$(curl -sS -o "$TMP/replay.json" -w '%{http_code}' --max-time 20 -H "Cookie: $cookie" \
      "http://127.0.0.1/api/v1/replay/$id_replay?desde=$desde_replay&hasta=$hasta_replay" || true)"
    tiene_estimados="$(node24 -e '
      const fs = require("fs");
      try {
        const datos = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
        process.stdout.write(Array.isArray(datos.estimados) ? "si" : "no");
      } catch { process.stdout.write("no"); }
    ' "$TMP/replay.json" 2>/dev/null || echo no)"
    if [ "$codigo_replay" = "200" ] && [ "$tiene_estimados" = "si" ]; then
      paso PASS "replay expone tramos estimados" "HTTP $codigo_replay"
    else
      paso AVISO "replay expone tramos estimados" "http=$codigo_replay estimados=$tiene_estimados"
    fi
  else
    paso AVISO "replay expone tramos estimados" "sin dispositivo en la flota de prueba"
  fi

  codigo_logout="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 \
    -X POST -H "Cookie: $cookie" http://127.0.0.1/api/v1/auth/logout || true)"
  if [ "$codigo_logout" = "204" ]; then
    paso PASS "POST /api/v1/auth/logout" "HTTP $codigo_logout"
  else
    paso FAIL "POST /api/v1/auth/logout" "HTTP $codigo_logout"
  fi
else
  paso FAIL "GET /api/v1/fleet con sesion" "sin cookie de login"
  paso FAIL "POST /api/v1/auth/logout" "sin cookie de login"
fi

# ---------------------------------------------------------------------------
# 8. Conteos produccion vs base nueva (solo SELECT via docker exec)
# ---------------------------------------------------------------------------
consulta_contenedor() {
  local contenedor="$1" usuario="$2" base="$3" sentencia="$4"
  docker exec "$contenedor" psql -U "$usuario" -d "$base" -tAc "$sentencia" 2>"$TMP/db.err" | tr -d '[:space:]'
}

if ! command -v docker >/dev/null 2>&1; then
  paso AVISO "conteos de posiciones (legado vs nueva)" "docker no disponible"
else
  nueva_posiciones="$(consulta_contenedor "$NUEVA_DB_CONTAINER" "$NUEVA_DB_USER" "$NUEVA_DB_NAME" \
    'SELECT count(*) FROM tracking.dmt_posicion' || true)"

  if [ "$(docker inspect -f '{{.State.Running}}' "$PROD_DB_CONTAINER" 2>/dev/null || echo false)" = "true" ]; then
    prod_posiciones="$(consulta_contenedor "$PROD_DB_CONTAINER" "$PROD_DB_USER" "$PROD_DB_NAME" \
      'SELECT count(*) FROM tc_positions' || true)"

    if [[ "$prod_posiciones" =~ ^[0-9]+$ ]] && [[ "$nueva_posiciones" =~ ^[0-9]+$ ]]; then
      if [ "$nueva_posiciones" -eq 0 ]; then
        paso AVISO "conteos tc_positions vs tracking.dmt_posicion" \
          "produccion=$prod_posiciones nueva=0 (la base nueva esta vacia)"
      else
        paso PASS "conteos tc_positions vs tracking.dmt_posicion" \
          "produccion=$prod_posiciones nueva=$nueva_posiciones"
      fi
    else
      paso FAIL "conteos tc_positions vs tracking.dmt_posicion" \
        "no se pudieron leer los conteos ($(tr '\n' ' ' <"$TMP/db.err" | cut -c1-120))"
    fi
  else
    if [[ "$nueva_posiciones" =~ ^[0-9]+$ ]] && [ "$nueva_posiciones" -gt 0 ]; then
      paso PASS "conteos de posiciones (legado retirado)" "nueva=$nueva_posiciones"
    else
      paso FAIL "conteos de posiciones (legado retirado)" \
        "la base nueva no tiene posiciones o no se pudo leer ($(tr '\n' ' ' <"$TMP/db.err" | cut -c1-120))"
    fi
  fi
fi

# ---------------------------------------------------------------------------
# Resumen final
# ---------------------------------------------------------------------------
echo "================================================================"
echo " RESUMEN: $n_pass PASS, $n_fail FAIL, $n_aviso AVISO"
for linea in "${REGISTRO[@]}"; do
  IFS='|' read -r estado nombre detalle <<<"$linea"
  if [ "$estado" = "FAIL" ]; then
    echo "   FAIL  $nombre${detalle:+ ($detalle)}"
  elif [ "$estado" = "AVISO" ]; then
    echo "   AVISO $nombre${detalle:+ ($detalle)}"
  fi
done
echo "================================================================"

if [ "$n_fail" -gt 0 ]; then
  echo "RESULTADO: FAIL ($n_fail fallos)"
  exit 1
fi
echo "RESULTADO: PASS"
exit 0
