# FINAL_CLOSURE_AUDIT.md — Cierre de software DMujeres-Tracking (2026-09-18)

> Ronda de cierre autorizada. Sin commits. Code freeze de R4 físico respetado
> para pruebas de campo (los cambios de esta ronda se implementaron con la
> campaña pausada, por orden explícita del operador).

## 1. Baseline

| Dato | Valor |
|---|---|
| Commit base | `f2975d0` (main) |
| App antes | 1.1.5 · versionCode 115 |
| Tests antes | móvil 592 · server 821 · dashboard 92 |
| Nota crítica P0 | `.gitignore: data/` ocultaba el paquete fuente `mobile/.../traccar/data/` (0 archivos tracked) |

## 2. Cambios realizados

| ID | Cambio | Archivos | Estado |
|---|---|---|---|
| P0 | `.gitignore` con reglas ancladas (`/data/`, `/logs/`) → paquete `data/` visible a Git | `.gitignore` | IMPLEMENTED (verificado `git check-ignore` vacío, `git diff --check` limpio) |
| P1-A1 | `Latest.versionCode/minVersionCode` + **autoridad por versionCode** (`OtaVersionPolicy`, sin downgrades) | `platform/OtaPolicy.kt`, `UpdateManager.kt`, `MainActivity.kt` | IMPLEMENTED + tests |
| P1-A2 | `OtaUrlPolicy`: HTTPS obligatorio en release (excepción host legado documentada mientras falte TLS) | `platform/OtaPolicy.kt` + validación en `UpdateManager.fetchLatest` | IMPLEMENTED + tests |
| P1-A3 | Verificación antes de instalar: paquete, versionCode superior y **firma compatible** (`verifyPackageIntegrity`) | `UpdateManager.kt`, `MainActivity.kt` | IMPLEMENTED (validación Android; ejecución real requiere dispositivo) |
| P1-A5/A6 | Estado OTA persistente (`NONE…INSTALL_REQUESTED/FAILED`) + **puerta de instalación segura** (recovery activo / jornada recién iniciada → UPDATE_READY) | `AppConfig.kt`, `MainActivity.kt` | IMPLEMENTED + tests |
| P1-A7 | `publish-ota.sh` sin URLs hardcodeadas: `OTA_PUBLIC_BASE_URL` obligatorio + **fail closed** sin HTTPS (salvo `OTA_ALLOW_HTTP=1` legado) | `infrastructure/scripts/publish-ota.sh` | IMPLEMENTED (fail-closed probado) |
| P2 | **Dashboard local-first**: IndexedDB + namespacing por usuario + NetworkState LIVE/DEGRADED/OFFLINE/RECONNECTING + stale-while-revalidate + write-through + badge de red montado | `dashboard/src/common/local/*` (11 archivos), `App.jsx` | IMPLEMENTED (tests 50) · integración WS write-through documentada (líneas exactas) PENDING |
| P3 | **Movement Start**: política pura con evidencia (velocidad 1.5 m/s + desplazamiento 15 m + sensor auxiliar), confirmación 2 muestras, burst de captura; integrada en `LocationEngine` con ancla estacionaria y fix real | `location/MovementStartPolicy.kt`, `LocationEngine.kt`, `TrackingService.kt` | IMPLEMENTED + 11 tests |
| P5 | **Timeline operativo server**: `GET /api/devices/{id}/timeline` (auto-registrado) con tipos NETWORK_LOSS/PRESENCE_OFFLINE/RECOVERY_*/CAPTURE_GAP/DELIVERY_DELAY/FGS_STATE/OUTBOX_BACKLOG/… y confianza por evidencia | `server/.../MobileTimelineService.java`, `MobileTimelineResource.java` | IMPLEMENTED + 18 tests · despliegue (restart server) PENDING |
| J | `reconnects24h` **verificado**: el contador existe y se incrementaba solo por el auto-reconnect de Paho (deshabilitado) → se cuenta también la reconexión real del ReconnectGate | `transport/MqttManager.kt` | IMPLEMENTED (fallo real de observabilidad corregido) |
| Tooling | `pack-project.sh` corregido (`*/data/*` excluía el paquete Room) + verificación anti-secretos ampliada | `infrastructure/scripts/pack-project.sh` | IMPLEMENTED (ZIP verificado: 108/108 .kt, 0 secretos) |

## 3. Tests (ejecutados)

| Suite | Comando | Resultado |
|---|---|---|
| Móvil unit | `./gradlew :app:testDebugUnitTest` | **612/0/0** (592 → 612: +11 MovementStart, +9 OtaPolicy) |
| Móvil lint | `./gradlew :app:lintDebug` | BUILD SUCCESSFUL |
| Server | `./gradlew test` | **839/0/29** (821 → 839: +18 timeline) |
| Dashboard | `node --test src` | **142/0** (92 → 142: +50 local-first) |
| Dashboard lint (nuevos) | `npx eslint src/common/local/ src/App.jsx` | 0 errores |
| Repo | `git diff --check` | limpio (whitespace corregido en `DmujeresApp.kt`) |

## 4. Build

- **Mobile:** `:app:compileDebugKotlin` OK. `assembleRelease` no re-ejecutado en esta ronda (sin cambios que afecten el pipeline de firma; OTA vigente intacto).
- **Dashboard:** `npm run build` OK; OTA preservado y verificado (latest.json + APK 1.1.5 → HTTP 200).
- **Server:** `compileJava` OK (recurso timeline auto-registrado por package-scan; **requiere restart del servicio para exponerse**).

## 5. Seguridad

- **OTA:** versionCode autoridad, HTTPS en release (excepción legado documentada), SHA-256 (ronda anterior), verificación de paquete/versionCode/firma antes de instalar, estado persistente, instalación segura.
- **TLS/MQTTS/device-token/rate-limit/Android Keystore:** **INFRA_PENDING / REAL_DEVICE_PENDING** (requiere infraestructura y provisioning coordinado; procedimiento en `SECURITY_BUILD.md`).
- **Secretos:** 0 nuevos; `.gitignore` corregido; ZIP verificado sin secretos.

## 6. Tracking / Dashboard

- Captura y Outbox: sin cambios estructurales (invariantes intactos).
- Movement Start: implementado (evidencia real, nunca coordenadas sintéticas).
- OEM/Health: sin cambios; estados honestos preservados.
- Dashboard: local-first lectura + indicador de red; timeline server listo (falta reinicio + UI de visualización pendiente).

## 7. Pendientes reales (estado estricto)

**IMPLEMENTED:** P0, P1 (A1/A2/A3/A5/A6/A7), P2 (capa + badge), P3, P5 (servicio), J.
**VALIDATION_PENDING:** verificación de firma en dispositivo real; install de OTA real end-to-end con la nueva puerta; timeline UI en dashboard (endpoint listo); P2 write-through WS completo.
**INFRA_PENDING:** TLS/HTTPS + MQTTS; rotación de secretos coordinada; rate limiting móvil; empaquetado de producción con `OTA_PUBLIC_BASE_URL`.
**REAL_DEVICE_PENDING:** R4-1…R4-15 (campaña física), androidTest Room 8→9, wake lock/alarma (sin evidencia aún), OEM Joseph/HONOR guía, endurance.

## 8. Riesgos demostrados

1. OEM freeze/suspensión (R4-0.5): mitigado operativamente, no resuelto por software.
2. Observabilidad: `screenon` vacío en health y thermal ausente (sin cambios por freeze).
3. Recurso timeline sin reiniciar = no expuesto aún.
4. Local-first del dashboard sin write-through WS completo (lectura confiable ya disponible).

## 9. Versión

- **versionName 1.1.5 · versionCode 115** (sin bump en esta ronda: los cambios móviles quedan listos para el próximo release; no se publicó OTA nueva para no mezclar variables con R4).
- APK publicado actual (1.1.5): SHA-256 `ac9cf7d3fd00f4333b98d699439f948526c37dd45d94f3d8a2f922780e2ac2d1`.
