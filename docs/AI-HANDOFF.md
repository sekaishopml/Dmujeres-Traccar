# AI-HANDOFF — DMujeres Tracking (para la IA implementadora)

Lee primero `docs/FINAL-ARCHITECTURE.md` (arquitectura cerrada) y
`docs/audit/BUG-REGISTER.md`. Este documento es el plan de trabajo práctico.
No reabras decisiones: están en `docs/ADR/` y son vinculantes.

Repo: `/home/DMujeres-Tracking` (rama `plataforma`). El código Android NO está
en el árbol de trabajo: está en el historial git (`origin/main:fallback/...`).
Para trabajar la app: `git worktree add --detach /tmp/dmj-app origin/main` y
trabaja ahí; al terminar, commit en una rama nueva desde `plataforma` (no
rompas `main`).

---

## 1. Qué está hecho (no lo toques)

- Plataforma servidor completa: `services/tracking` (OsmAnd + canal móvil),
  `services/api` (/api/v1), `services/web`, `services/recuperacion` (FCM),
  `services/routing` (GraphHopper `/route`), `dmj-web` (panel), nginx :80/:999.
- Base `dmt-db` (PostgreSQL 18.6 + TimescaleDB), particionado mensual con
  creación automática on-write (`services/tracking/src/db.js`,
  `#asegurarParticion` / `#asegurarParticionEvento`).
- Replay con: capas real + chevrones + huecos punteados + `estimados` por
  calles; panel auditoría; tramos estimados cache/cap en
  `services/api/src/ruteo.js`.
- E2E `scripts/validation/e2e.sh` (26/26) y smokes de API/tracking.
- Respaldos diarios y `/home/DMujeres-backups/`.

**Prohibido**: reescribir la plataforma, cambiar de motor de mapas, meter
PostGIS "porque sí", microservicios nuevos, brokers, o tocar el contrato
OsmAnd legacy (`services/tracking/src/osmand.js`) que usan las APK en calle.

## 2. Qué está mal (confirmado, con evidencia)

1. `posicion_actual` y `ultima_conexion_en` **retroceden** con paquetes
   atrasados: `services/tracking/src/db.js:191-222` (upsert sin comparar
   tiempo) y `:225-236` (update de dispositivo sin guarda).
2. **Sin idempotencia**: `db.js:168` inserta sin UNIQUE; SQLite local
   (`fallback/.../DatabaseHelper.kt:64-79`) no tiene `boot_id`/secuencia.
3. **Cola atascable**: `RequestManager.sendRequest` (todo fallo es `false`) +
   `TrackingController.kt:346-357` reintenta cada 30 s fijos; un 404 de
   dispositivo bloquea la cola y con 5000 pendientes se pierde lo viejo
   (cap silencioso, `DatabaseHelper.kt:116-124`).
4. **Movimiento por acelerómetro**: `MotionMonitor.kt:37-38` (std ≤0.15
   quieto) + `MotionSignal.kt` (3 kn como rescate, requiere fix) → el deadlock
   del caso Pilay. `UNKNOWN→STATIONARY` posible en `motionTick`
   (`TrackingController.kt:81-98`).
5. **Fechas absurdas sin validar**: en producción existe la partición
   `dmt_posicion_2037_10` (un equipo mandó un fix de 2037).
6. **Release no reproducible**: OTA 2.1.73 (vc 283) no está en git
   (`fallback/app/build.gradle` dice 282/2.1.72); el keystore está fuera del
   repo (correcto, pero documentar custodia).
7. **Replay**: los tramos estimados se dibujan igual que los reales
   (`apps/web/src/paginas/operacion/replay.ts`, `BANDA_ESTIMADA`).

## 3. Orden de trabajo (sprints)

### Sprint 1 — Servidor (rápido, sin teléfonos)
1. **P1-SERVER-001** Idempotencia. Migración
   `database/migrations/002_idempotencia_posiciones.sql`: columnas
   `boot_id text`, `local_sequence bigint` + índice único
   `(dispositivo_id, boot_id, local_sequence) WHERE boot_id IS NOT NULL`
   (parcial para no romper datos históricos). En `db.js:registrarPosicion`:
   `INSERT ... ON CONFLICT DO NOTHING RETURNING id`; si no hay fila →
   `{ duplicado: true }`. Devolver resultado a `osmand.js` (200 igual) y al
   lote nuevo.
2. **P1-SERVER-002** Guarda de posición viva. En el upsert de
   `dmt_posicion_actual` y en el `UPDATE dmt_dispositivo`: añadir
   `WHERE EXCLUDED.registrado_en > tracking.dmt_posicion_actual.registrado_en`
   (y equivalente en el update del dispositivo con
   `GREATEST(ultima_conexion_en, EXCLUDED)`).
3. **P1-SERVER-003** Validación de fechas en `osmand.js`: si
   `momento > now()+24h` o `< now()-30d` → responder 200/422 sin guardar y
   log `invalid`. (Elegir 200 si se prefiere drenar buffer; documentar).
4. **P1-SERVER-004** `POST /api/mobile/v1/positions` (JSON array ≤500):
   valida, dedupe por identidad, inserta en lote (una transacción por lote),
   responde `{accepted, duplicate, invalid, dead}` por índice. Ruta en
   `services/tracking/src/servidor.js` + handler en `movil.js`.
5. **P2-SERVER-005** Precrear particiones (mes actual + 2) al arrancar
   `servidor.js` y en job diario (cron/systemd timer); retención: preguntar
   al dueño (por defecto conservar todo).

### Sprint 2 — App (requiere worktree de `origin/main:fallback/`)
6. **P1-APP-001** MovementStateMachine. Nuevo archivo
   `movement/MovementStateMachine.kt` (puro, testeable) con estados y
   entradas GPS/distancia/tiempo/IMU/historial; regla `UNKNOWN→ACTIVE` y
   `STATIONARY` solo con ≥3 min de evidencia. `TrackingController` consume la
   máquina; `MotionMonitor` pasa a ser un input más.
7. **P1-APP-002** LocalPositionStore v5: migración SQLite con `boot_id`,
   `local_sequence`, `journey_id`, `provider`, `movement_state`, `status`,
   `attempts` + tabla `meta`. La secuencia persiste entre recreaciones y
   reboot. Subir `DATABASE_VERSION` a 5 (migración real, no DROP).
8. **P1-APP-003** UploadQueue: clasificación HTTP (2xx ok, 400/404/413 dead,
   401 pausa, 408/429/5xx/red retry) + backoff exponencial con jitter (base
   5 s, tope 5 min) + envío en lotes de hasta 50 al endpoint nuevo cuando el
   servidor lo soporte (fallback a 1×1 OsmAnd).
9. **P1-APP-004** RecoveryEngine: `AlarmManager.setAndAllowWhileIdle` 9 min,
   rearme en servicio/boot/replace; al despertar: `getCurrentLocation` fresco
   + `read()`; `recovery_count` en diagnóstico.
10. **P1-APP-005** JourneyManager: persistir journey en `meta`; `GET
    /api/mobile/v1/journey` y reconciliación al arrancar.
11. **P2-APP-006** Diagnóstico completo (boot_id, recovery_count, fgs,
    buffer_depth, movement_state) en `ServiceHeartbeat`.

### Sprint 3 — Replay y panel
12. **P1-WEB-001** Replay REAL/MATCHED/ESTIMATED: API renombra `estimados` →
    `reconstruidos` con `metodo` + `mapaVersion`; `replay.ts` dibuja ESTIMATED
    punteado gris con leyenda "tramo estimado"; MATCHED (cuando exista
    `/match`) con otro patrón. Revertir el estilo "ruta normal" del tramo
    estimado (ADR-007).
13. **P1-ROUTING-001** `dmj-routing`: añadir `/match` (reusar
    `MatchService.java` del respaldo `legado-final/matchservice/`) y versión
    de grafo en la respuesta; gap reconstruction: `/match` si hay ≥2 fixes en
    el hueco, si no `/route`.
14. **P2-WEB-002** Dashboard de salud con causa (HEALTHY/DEGRADED/OFFLINE/
    RECOVERING/MISCONFIGURED) usando los campos del diagnóstico.
15. **P2-WEB-003** Auditoría dashboard: revisar query keys (evitar refetch
    doble), 401 en `apps/web/src/api/cliente.ts` (hoy redirige con
    `window.location.href`; usar navegación SPA y evitar redirect en
    queries de fondo), mapa (destruir instancia al desmontar), y responsive
    ya validado 320→1920.

### Sprint 4 — Release y seguridad
16. **P1-REL-001** Bump `versionCode 284` / `versionName 2.1.74` en
    `fallback/app/build.gradle` (rama nueva), compilar `assembleRegularDebug`
    y `assembleGoogleRelease` (con keystore por canal seguro), commitear el
    source, publicar en `ota/` (APK + `latest.json` con sha256) y probar OTA.
17. **P2-SEC-001** Quitar `cctv2026` de `scripts/validation/e2e.sh` (usar
    `DMJ_TEST_PASSWORD` sin default; actualizar docs). TLS/HSTS/cookies
    seguras: bloque de entrega a producción (Nginx ya tiene example).

## 4. Testing (fase física, IA-2) — procedimiento reproducible

Ejecutar en 2 teléfonos (uno referencia tipo Pixel/Samsung, uno OEM duro tipo
Infinix/Xiaomi). Para cada uno, con `adb`:

```bash
adb shell dumpsys deviceidle force-idle     # Doze; luego unforce-idle
adb shell am kill <package>                 # process recreation
adb reboot                                  # reboot recovery
adb shell dumpsys package <pkg> | grep -A5 request
```

Secuencia: jornada corta → apagar pantalla 30 min → forzar Doze → matar
proceso → reboot → cortar red (modo avión) 30/120 min → restaurar → validar en
BD: `count(*)`, huecos máximos, duplicados (deben ser 0), `posicion_actual`
monotónica, y en el panel: replay con capas correctas. Registrar en
`docs/operations/PRUEBAS-FISICAS.md` (crear con la plantilla de §6).

## 5. Criterios de aceptación de la release

- `npm ci && npm run lint && npm run build` limpios (web), smokes y E2E 26/26.
- Migraciones aplican desde cero y sobre la base actual sin error.
- Idempotencia: insertar el mismo `(device,boot,seq)` dos veces → 1 fila.
- Paquete atrasado no retrocede `posicion_actual` (test SQL).
- App: captura con pantalla apagada ≥30 min sin hueco >11 min con cobertura.
- Cola: 404 no bloquea (evento DEAD), 500 reintenta con backoff.
- Replay: REAL/MATCHED/ESTIMATED distinguibles y rotulados.
- Sin credenciales en el repo; TLS documentado para producción.

## 6. Qué NO tocar

- `services/tracking/src/osmand.js` (contrato congelado salvo la validación de
  fechas), el esquema `dmt_*` existente (solo migraciones aditivas), el grafo
  GraphHopper en `/opt/graphhopper`, `main` del repo, `docs/agents/*`
  históricos, y la operación viva (systemd) durante horario laboral.
