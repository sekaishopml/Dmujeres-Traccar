# REFACTOR_BASELINE.md — Punto de partida R0 (2026-09-17)

## Snapshot

| Dato | Valor |
|---|---|
| Branch | `main` |
| Commit | `f2975d0` (Arreglo de paquetes de actualización en dmujeres) |
| Tag local de restauración | `refactor-r2-pre-refactor` |
| Snapshot working tree | `backups/dmj-worktree-20260917-1352.tar.gz` (sources, sin cachés) |
| Push | **NO** (orden explícita) |

## Versiones

| Componente | Versión |
|---|---|
| Android (`mobile/app/build.gradle.kts`) | **1.1.5** (versionCode 115) |
| Server (fork Traccar) | 6.14.5 |
| Dashboard (traccar-web) | 6.14.5 |
| PostgreSQL | 17 (TimescaleDB, contenedor `dmj-db` 5433) |
| EMQX | 5.8.5 (contenedor `dmj-mqtt`) |

## Tests iniciales (verificados en esta ronda)

| Suite | Resultado |
|---|---|
| Mobile unit (`:app:testDebugUnitTest`) | **588/0/0** (586 pre-refactor + 2 de `DependencyCycleTest`) |
| Server (`./gradlew test`) | re-ejecutado en esta ronda (ver REFACTORING_LOG) |
| Dashboard (`node --test`) | re-ejecutado en esta ronda (ver REFACTORING_LOG) |
| Lint Android (`:app:lintDebug`) | BUILD SUCCESSFUL |

## Tamaño y estructura (BEFORE)

| Métrica | Valor |
|---|---|
| Archivos Kotlin `main` | 106 |
| Archivos Kotlin `test` | 82 (+1 androidTest) |
| Líneas `main` (total) | 18 851 |
| Paquetes por dominio | 14 (`config core data diagnostics health location oem outbox platform readiness recovery sensors tracking transport ui`) |
| Módulos Gradle | 1 (`:app`) |

### Clases grandes (BEFORE, líneas)

| Clase | Líneas |
|---|---|
| `ui/MainActivity.kt` | 1472 |
| `tracking/TrackingService.kt` | 1383 |
| `ui/OnboardingActivity.kt` | 980 |
| `config/AppConfig.kt` | 911 |
| `ui/DiagnosticsActivity.kt` | 890 |
| `transport/MqttManager.kt` | 778 |
| `location/LocationEngine.kt` | 531 |
| `ui/components/MetricsRow.kt` | 522 |
| `outbox/PositionOutboxDispatcher.kt` | 412 |

## Riesgos identificados en R0/R1 (resueltos o en deuda)

1. **ZIPs públicos con secretos** (`.env`, `secrets.properties`,
   `keystore.properties`, `release.keystore`) servidos en `:999` → retirados y
   verificados 404; script seguro `pack-project.sh`; plan de rotación en
   `SECURITY_BUILD.md` (R2).
2. `mobile/keystore.properties` apuntaba a `release.keystore` (rompería el OTA
   de la flota al firmar distinto) → provisioning explícito y fallo duro sin
   archivo (R2).
3. `transport → data` (Room) real → puerto `ControlQueueStore` (R7/R11).
4. Ciclo de orquestación `tracking ↔ readiness ↔ recovery ↔ ui ↔ platform ↔
   diagnostics` → congelado por test; rotura planificada (deuda M-2).

## Estado de integridad R1

- `data/*` **completo** (`AppDatabase`, `PendingPosition`, `PositionDao`,
  `SequenceState`, `DeadLetter`, `DispatchLock`→movido a `core`, políticas,
  `HealthSnapshot*`, `RemoteConfig`); sin referencias a `com.dmujeres.traccar.db.*`.
- `:app:compileDebugKotlin` verde; `:app:testDebugUnitTest` 588/0.
- No hay código referenciado pero ausente en `mobile`; server y dashboard
  compilan/prueban con sus suites.
