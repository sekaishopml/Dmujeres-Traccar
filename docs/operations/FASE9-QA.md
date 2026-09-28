# FASE 9 - QA/E2E formal

Validación E2E de la plataforma nueva (plan maestro, secciones 18 y 24) sin
tocar producción. Fecha: 2026-09-26. Agente: QA/APP.

## 1. Qué valida `scripts/validation/e2e.sh`

1. `node24 --check` de todos los archivos de `services/tracking`,
   `services/api` (incluido `smoke.mjs`) y `services/web`.
2. Smoke de `services/api` (arranca su propio servidor en `18081`, 49
   comprobaciones y limpieza de usuarios temporales).
3. Smoke de `services/tracking` (puerto efímero, dispositivo `qa-f0`,
   restauración exacta de la base nueva).
4. `npm run build` de `apps/web` (omitible con `--sin-build`).
5. `systemctl is-active` de `dmj-api`, `dmj-tracking`, `dmj-web`, `nginx`
   y `dmj-traccar` (control de producción, solo consulta).
6. `curl` a la entrada nueva (`http://127.0.0.1/`),
   `http://127.0.0.1/api/v1/health` (cuerpo `estado=ok`),
   `http://127.0.0.1:25565/` directo y al panel viejo
   `http://127.0.0.1:999/` (control de producción).
7. Login real por la puerta de entrada con `DMJ_TEST_EMAIL`/
   `DMJ_TEST_PASSWORD` (defaults del contrato de QA), `GET /api/v1/fleet`
   (200 y `total>=1`) y `POST /api/v1/auth/logout` (204).
8. Conteos de solo lectura: producción `tc_positions` (`docker exec dmj-db
   psql`) vs base nueva `tracking.dmt_posicion` (`docker exec dmt-db psql`);
   avisa si la nueva está vacía.

El script es idempotente: los smokes limpian lo suyo, la sesión de prueba se
revoca con logout y todo lo temporal vive en un `mktemp` que se borra al
salir. Código de salida 0 solo si no hay ningún FAIL (los AVISO no fallan la
corrida). No reinicia servicios ni escribe en producción: de producción solo
lee estado, HTTP 999 y un `SELECT count(*)`.

## 2. Cómo se corre

```bash
cd /home/DMujeres-Tracking
chmod +x scripts/validation/e2e.sh          # una vez
bash scripts/validation/e2e.sh              # con build de apps/web
bash scripts/validation/e2e.sh --sin-build  # sin build (si otro proceso compila)
```

Variables opcionales: `DMJ_PROYECTO`, `DMJ_TEST_EMAIL`, `DMJ_TEST_PASSWORD`,
`DMJ_PROD_DB_CONTAINER/USER/NAME` y `DMJ_NUEVA_DB_CONTAINER/USER/NAME` (ver
cabecera del script). No ejecutar dos instancias en paralelo: los smokes usan
el dispositivo `qa-f0` y se restauran entre sí.

## 3. Evidencia real de la corrida

### 3.1 Sintaxis

```
$ bash -n scripts/validation/e2e.sh && echo "bash -n: OK"
bash -n: OK
```

### 3.2 `bash scripts/validation/e2e.sh --sin-build` (2026-09-26 00:45:40)

```
================================================================
 DMujeres Tracking - E2E FASE 9
 plataforma: /home/DMujeres-Tracking
 build web:  omitido (--sin-build)
 fecha:      2026-09-26T00:45:40-05:00
================================================================
PASS  node24 --check services/tracking -> 5 archivo(s)
PASS  node24 --check services/api -> 21 archivo(s)
PASS  node24 --check services/web -> 1 archivo(s)
PASS  smoke services/api -> PASS  limpieza del bloque de escritura (usuarios, sesiones, asignaciones, auditoria) Smoke API v1: 49 PASS, 0 FAIL RESULTADO: PASS 
PASS  smoke services/tracking -> PASS 
AVISO build apps/web -> omitido por --sin-build
PASS  systemd dmj-api -> active
PASS  systemd dmj-tracking -> active
PASS  systemd dmj-web -> active
PASS  systemd nginx -> active
PASS  systemd dmj-traccar (control produccion) -> active
PASS  nginx :80 (entrada nueva) -> HTTP 200
PASS  API /api/v1/health por :80 -> HTTP 200
PASS  services/web :25565 directo -> HTTP 200
PASS  panel viejo :999 (control produccion) -> HTTP 200
PASS  health devuelve estado ok -> estado=ok
PASS  login real por :80 -> HTTP 200 + cookie dmj_sesion
PASS  GET /api/v1/fleet con sesion -> HTTP 200 total=1
PASS  POST /api/v1/auth/logout -> HTTP 204
PASS  conteos tc_positions vs tracking.dmt_posicion -> produccion=27140 nueva=80809
================================================================
 RESUMEN: 19 PASS, 0 FAIL, 1 AVISO
   AVISO build apps/web (omitido por --sin-build)
================================================================
RESULTADO: PASS
```

### 3.3 Corridas anteriores (idempotencia)

Se ejecutó el script tres veces seguidas (00:42:53, 00:43:13 y 00:45:40), con
el mismo resultado en todas:

```
RESUMEN: 19 PASS, 0 FAIL, 1 AVISO
RESULTADO: PASS
```

Los conteos de producción crecieron por tráfico real de la App
(27133 → 27135 → 27140); la base nueva se mantuvo en 80809 y el receptor
nuevo no escribió en producción. El AVISO es esperado al usar `--sin-build`.

## 4. Checklist de la sección 18 del plan

| # | Punto | Estado | Evidencia | Pendiente |
|---|---|---|---|---|
| 1 | Login | PASS | E2E: login real por `:80` 200 + cookie `dmj_sesion`; cookie `HttpOnly`/`SameSite=Lax` (smoke API); `auth/me` 200 | - |
| 2 | Logout | PASS | E2E: logout 204; smoke: `auth/me` tras logout 401 y sesión revocada en `iam.dmt_sesion` | - |
| 3 | Sesión expirada | PARCIAL | `validarSesion` exige `revocada_en IS NULL AND expira_en > now()` (`services/api/src/sesiones.js:69-80`); cookie con `Max-Age` | Falta prueba destructiva (adelantar `expira_en` o reloj) en un E2E dedicado |
| 4 | Permisos | PASS | Smoke API: no admin → 403 `SIN_PERMISO`; fleet ajeno → 404; configuración solo admin; borrado con guardas de último admin | - |
| 5 | Fleet | PASS | E2E: `GET /api/v1/fleet` 200 `total=1`; smoke: paginado, detalle por id e id público | - |
| 6 | Posición | PASS | Smoke tracking: `dmt_posicion` + `dmt_posicion_actual` + dispositivo online; smoke API: `fleet/{id}/position` y `positions/live` 200 | - |
| 7 | Replay | PASS | Smoke API: recorridos disponibles (7 días) y detalle con posiciones, huecos y resumen | - |
| 8 | Batería | PASS | Smoke API: `/battery` y `/battery/{id}` con muestras; smoke tracking: `telemetry.dmt_bateria` | - |
| 9 | Reportes | PASS | Smoke API: `reports/trips`, `reports/stops`, `reports/summary` 200 con totales | - |
| 10 | Usuarios | PASS | Smoke API 4b: crear/editar/eliminar, asignaciones, hash heredado, auditoría sin claves | - |
| 11 | Caída de servicio | PARCIAL | Diseño verificado en código: web responde 502 si la API no contesta (`services/web/src/servidor.js:56-74`); API `ready` degradado (`salud.js`); App reintenta 5xx/red con backoff 30→300 s (`TrackingController.kt:373-401`) | Simulacro real deteniendo/levantando servicios en ventana controlada (regla vigente: no detener producción) |
| 12 | Recuperación | PARCIAL | `POST /api/mobile/v1/recovery-ack` acepta etapas `RECOVERY_*` y responde 200 `accepted` (`movil.js:450-472`); FCM real y máquina de estados planificados | FASE 4b: envío push, `iam.dmt_token_fcm`, validación de orden, `SERVER_ACK` |
| 13 | App actual conectando | PARCIAL (simulado PASS) | Protocolo OsmAnd y canal móvil probados con smokes (mismos parámetros y códigos); `config` en vivo 200/401/404; identificadores y claves de flota preservados (comparación por hash); huecos de cutover en `docs/app/COMPATIBILIDAD-MATRIZ.md` | Prueba con dispositivo real en el paso 18 del cutover (FASE 10) y publicación de `:5055`/`:999` hacia la plataforma nueva |

## 5. Huecos detectados por el QA

1. **Publicación de puertos para el App (crítico, FASE 8/cutover):** el App
   deriva todo el canal móvil a `:999` desde la URL `:5055`
   (`DmujeresApi.kt:55`). Hoy `:999`/`:5055` son `dmj-traccar`; la plataforma
   nueva escucha en `127.0.0.1:6066` y publica el panel en `:80`/`:25565`. Sin
   publicar `:999` (canal móvil) y `:5055` (OsmAnd) hacia lo nuevo, la App no
   reconecta sola tras el cutover.
2. **OTA sin rollout (alto, FASE 4b):** el receptor nuevo sirve `latest.json`
   sin aplicar `rollout.json` (`paused`/`allow`/`percent`) ni `minVersionCode`.
3. **Diagnósticos incompletos (medio, FASE 4b):** faltan los atajos
   `mobile.lastOta*`, `mobile.cadenceMovingMs` y `mobile.pending` que el panel
   usa para ver OTA/cola del fallback.
4. **Eventos y FCM (medio, FASE 4b/6):** sin `mobileJourneyStarted/Ended` ni
   recuperación FCM real.
5. **Sesión expirada y caída de servicio (QA pendiente):** diseño verificado,
   falta prueba destructiva controlada fuera de producción.

## 6. Notas

- Ningún secreto se imprimió: la clave de prueba se pasa por variable de
  entorno y el script no la escribe en pantalla; las comparaciones de claves
  de flota se hicieron por SHA-256 sin exponer valores.
- Producción (`/DMujeres-Tracking`, `dmj-db`, servicios `dmj-*`) solo se leyó:
  estado systemd, `curl :999`, un `SELECT count(*) FROM tc_positions` y código
  fuente.
- Sin commits: los entregables quedan en el árbol de trabajo.
