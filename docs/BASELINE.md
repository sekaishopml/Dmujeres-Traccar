# BASELINE.md — Resultados de tests

## 1. Baseline original (FASE 1, 2026-09-16)

Comandos ejecutados y resultados reales (no "UP-TO-DATE", `cleanTest` en server):

| Suite | Comando | Resultado |
|---|---|---|
| Server (Traccar + mobile) | `cd server && ./gradlew cleanTest test` | `BUILD SUCCESSFUL` — 450 clases de test, **805 tests / 0 failures / 0 errors / 29 skipped** |
| Mobile (unit JVM) | `cd mobile && ./gradlew :app:testDebugUnitTest` | `BUILD SUCCESSFUL` — 65 clases, **507 tests / 0 failures / 0 errors** |
| Dashboard (node) | `cd dashboard && node --test src/map/util/ src/other/qualityLabel.test.js src/common/util/deviceHealth.test.js` | **73 tests / 0 fail** |

## 2. Re-baseline del refactor (R0→R8, 2026-09-17)

Comandos y resultados de la ronda de refactor (evidencia en
`REFACTORING_LOG.md`):

| Suite | Comando | Antes (R0) | Después (R8) |
|---|---|---|---|
| Mobile JVM | `cd mobile && ./gradlew :app:testDebugUnitTest` | 575 / 0 / 0 / 0 | **586 / 0 / 0 / 0** |
| Mobile compile | `cd mobile && ./gradlew :app:compileDebugKotlin` | verde | **verde** |
| Mobile lint | `cd mobile && ./gradlew :app:lintDebug` | no ejecutado | **verde** (0 errores) |
| Server (sin tocar en esta ronda) | `cd server && ./gradlew cleanTest test` | 821 / 0 / 29 skipped | **821 / 0 / 0 / 29 skipped** |
| Dashboard | fuera de alcance | 80 / 0 | no ejecutado |

Notas:

- El baseline publicado en F1 fue 549 tests móviles; el working tree de la
  ronda R ya traía 575 (tests F2/recovery añadidos por la corrida previa). No
  se borró ni desactivó ningún test.
- Server: los 29 skipped son tests condicionados por entorno (FCM smoke sin
  credenciales, integraciones). No se contabilizan como PASS.
- Mobile `androidTest` (migración Room) requiere dispositivo/emulador; no se
  ejecutó en esta máquina.
- Los XML de resultados quedan en `mobile/app/build/test-results/` y
  `server/build/test-results/` de esta corrida.
