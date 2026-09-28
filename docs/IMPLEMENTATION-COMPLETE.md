# IMPLEMENTATION-COMPLETE — resumen técnico (2026-09-28)

Implementación de `docs/audit/IMPLEMENTATION-BACKLOG.md` ejecutada por
subagentes e integrada con revisión. E2E **26/26 PASS**. Producción **no**
declarada: queda condicionada a la fase de pruebas físicas.

## Tareas completadas

- **P1-SERVER-001/002/003**: migración `002_idempotencia.sql` aplicada
  (columnas + índice único parcial + purga del fix 2037 id 92908); ingesta con
  `ON CONFLICT DO NOTHING`; guardas de avance en `posicion_actual` y
  `ultima_conexion_en`; validación de fechas [−30 d, +24 h].
- **P1-SERVER-004**: `POST /api/mobile/v1/positions` (lote ≤500, resultado por
  evento) y `GET /api/mobile/v1/journey`.
- **P1-SERVER-005**: precreación de particiones mes+2 al arrancar.
- **P1-APP-001..006**: `MovementStateMachine.kt` + 15 tests; store v5 aditivo
  (`boot_id`, secuencia persistente, `meta`); `UploadQueue` con clasificación
  y backoff; `DozeAlarmReceiver` (9 min) + `JourneyManager` +
  `ServiceHeartbeat` extendido. Build `assembleRegularDebug` y 69 unit tests
  en verde (1 omitido preexistente).
- **P1-WEB-001**: replay REAL/MATCHED/ESTIMATED con `reconstruidos`
  (`estimados` eliminado del contrato y del API); leyenda de 3 capas;
  401 por navegación SPA; caché de flota unificada; agregado de jornadas.
- **P1-ROUTING-001**: `/match` reincorporado; `mapaVersion` (SHA-256 del PBF)
  en `/route` y `/match`; gap reconstruction usa `/match` con ≥2 fixes.
- **P2-WEB-002/003**: salud con causa en Inicio; auditoría de dashboard
  (mapa se destruye, marcadores se limpian, rango con error visible).
- **P1-REL-001**: bump 284/2.1.74 en código (APK sin firmar ni publicar;
  OTA sigue en 2.1.73/283).
- **P2-SEC-001**: `cctv2026` fuera de `e2e.sh` (ahora exige `DMJ_TEST_*` por
  entorno); gate TLS y procedimiento de release documentados; inventario de
  secretos externos.

## Archivos modificados

Servidor: `database/migrations/002_idempotencia.sql` (nuevo),
`services/tracking/src/{db,movil,osmand,servidor,smoke}.js`,
`services/routing/RouteService.java` (+`/match`), `services/api/src/
{ruteo,salud,rutas,replay,smoke}.mjs/.js`, `packages/contracts/openapi.json`,
`packages/shared-types/src/{replay,salud}.ts`. App: `fallback/` (+7 archivos
nuevos, 69 tests). Web: 15 archivos (`apps/web/src/**`). Release:
`scripts/validation/e2e.sh`, `fallback/app/build.gradle`,
`docs/deployment/FASE11-SEGURIDAD-ENTREGA.md`,
`docs/operations/FASE11-RELEASE-PROCEDIMIENTO.md`,
`docs/deployment/SECRETOS-FUERA-DEL-REPO.md`.

## Decisiones respetadas

ADR-001..010 íntegros; regla de oro (estimado siempre rotulado); sin
microservicios/brokers/PostGIS nuevos; OsmAnd legacy intacto salvo validación
de fechas; `main` del repo intacto (todo en `plataforma`).

## Problemas encontrados

1. El índice único exigía la clave de partición (`registrado_en`): se corrigió
   la migración y los docs antes de aplicar.
2. `smoke.mjs` exigía no duplicar `estimados`/`reconstruidos`: se eliminó
   `estimados` del API y del contrato (la web ya toleraba ambos).
3. La app conserva el arranque FGS en `AutostartReceiver` (ADR-002 pedía no
   forzarlo): aceptado como conservador para no romper la flota; validar en
   fase física. `boot_id` rota por `start()` en vez de por proceso (compatible
   con la unicidad).
4. `services/api/smoke.mjs:29` aún trae un default de prueba: extraerlo en
   cuanto el E2E lo cubra (anotado, no bloquea).

## Bloqueadores

Ninguno para cerrar la fase. Para producción faltan: pruebas físicas, APK
2.1.74 firmado y publicado, TLS.

## Comandos ejecutados (integración)

```bash
node24 --check services/api/src/*.js services/tracking/src/*.js
node24 services/api/smoke.mjs && node24 services/tracking/src/smoke.mjs
javac -cp /opt/route-service/matchservice.jar -d /opt/route-service/clases services/routing/RouteService.java
sudo systemctl restart dmj-routing dmj-tracking dmj-api
cd apps/web && npm run build && sudo systemctl restart dmj-web
DMJ_TEST_PASSWORD=... DMJ_TEST_MOVIL_KEY=... bash scripts/validation/e2e.sh  # 26/26
```
