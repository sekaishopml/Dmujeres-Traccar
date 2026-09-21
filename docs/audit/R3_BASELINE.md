# R3_BASELINE.md — Estado inicial antes del refactor R3 (2026-09-17)

> Regla 3 del prompt R3: registrar el estado ANTES de cambiar código. Sin commits automáticos.

| Dato | Valor |
|---|---|
| Commit | `f2975d0` (branch `main`) |
| App | 1.1.5 · versionCode 115 · targetSdk 35 · minSdk 26 |
| Server | Traccar fork 6.14.5 (corriendo, sin cambios hoy) |
| Dashboard | traccar-web 6.14.5 · build servido 2026-09-16 22:36 |
| Kotlin files | 108 main · 83 test · 1 androidTest |
| Paquetes dominio | 14 (`config core data diagnostics health location oem outbox platform readiness recovery sensors tracking transport ui`) |
| Módulos Gradle | 1 (`:app`) |
| Tests móviles | **588/0/0** (586 previos + 2 `DependencyCycleTest`) |
| Tests server | 821/0/29 skipped (cache Gradle; server sin cambios hoy) |
| Tests dashboard | 80/0 |
| Lint `:app:lintDebug` | BUILD SUCCESSFUL |
| Compile `:app:compileDebugKotlin` | OK |

## Clases grandes (BEFORE, líneas) — archivos con locking (regla 50)

| Clase | LOC |
|---|---|
| ui/MainActivity.kt | 1472 |
| tracking/TrackingService.kt | 1382 |
| ui/OnboardingActivity.kt | 980 |
| config/AppConfig.kt | 911 |
| ui/DiagnosticsActivity.kt | 890 |
| transport/MqttManager.kt | 771 |
| outbox/PositionOutboxDispatcher.kt | 412 |

## Estado heredado de R2 (contexto)

- Deuda A-1..A-8 en `docs/TECHNICAL_DEBT.md` (ciclo de orquestación, UI sin ViewModel, AppConfig God Object, MQTT monolito, singleton outbox, reloj, rotación de secretos por fuga de ZIPs).
- `transport` ya NO depende de `data` (puerto `transport/ControlQueueStore` + `outbox/RoomControlQueueStore`).
- Guardarraíles: `ArchitectureDependencyTest` (reglas) + `DependencyCycleTest` (ciclo aceptado congelado); docs: `DEPENDENCY_RULES.md`, `ARCHITECTURE_AUDIT_R2.md`, `SECURITY_BUILD.md`, `TESTING_STRATEGY.md`.

## Contexto forense del día (evidencia para el Agente I)

Auditoría de rutas 2026-09-17 (5 subagentes, solo lectura): causa común = suspensión/
conectividad del teléfono; infraestructura limpia (david control 1:1 a 3 s). joseph/
macias/kevin = evidencia QA para el algoritmo de segmentación (regla 52: prohibidas
reglas por dispositivo). Respuestas del operador: presentación "Mañana o después";
tramos sin cobertura = "No dibujar la recta".
