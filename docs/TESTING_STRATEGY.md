# TESTING_STRATEGY.md — Estrategia de pruebas (R25/R26, 2026-09-17)

> Regla transversal: **un test JVM nunca certifica comportamiento del sistema
> operativo**. Esta guía separa categorías y dice explícitamente qué NO se
> puede probar sin dispositivo.

## 1. UNIT (JVM, rápido, determinista)

**Qué cubre:** políticas puras, filtros, fusión de sensores, retry/backoff,
outbox (selección/orden/retención), recovery policy, health policy, OEM
capability, formatters, contratos de protocolo, reglas de arquitectura.

**Ejemplo real (hoy):**
`DispatchPolicyTest`, `PositionOutboxDispatcherTest`, `EnvelopeTest`,
`MobileProtocolTest`, `Recovery*PolicyTest`, `DeviceCapsTest`,
`ArchitectureDependencyTest`, `DependencyCycleTest`.

**No puede probar:** Android lifecycle, Service, Doze, freeze OEM, sensores
reales, red real.

## 2. INTEGRATION (JVM + componentes reales)

**Qué cubre:** Room en JVM (Robolectric no está en el proyecto: se usan fakes y
tests instrumentados), serialización/parseo, cliente HTTP contra fake server,
consumer MQTT server-side (suite Java del server).

**Ejemplo real:** suite `server/` (`./gradlew test`), `FcmSenderTest`,
`Mobile*Service` tests, dashboard (`node --test`).

## 3. INSTRUMENTATION (androidTest, dispositivo/emulador)

**Qué cubre:** migraciones Room reales (`MigrationTestHelper`), lifecycle,
Service, permisos, notificaciones.

**Estado real:** 1 archivo `androidTest` (migración 7→8 verificada en ZTE en la
ronda F0). **Pendiente:** ampliar a 8→9 cuando exista migración y correr en CI
con emulador.

## 4. REAL DEVICE (QA física — FASE POSTERIOR, no en este refactor)

- screen-off continuo (FGS), Doze, app-standby.
- Freeze OEM (ZTE cfreezer, Samsung, Xiaomi, Infinix).
- Batería/thermal reales, comportamiento módem, GPS bajo techo.
- Matriz OEM: ver `docs/OEM_COMPATIBILITY.md`.

## 5. FIELD ENDURANCE (QA física — FASE POSTERIOR)

Jornadas 6/12/24 h reales con reconstrucción de continuidad
(`tc_device_health`, `MobileContinuityService`). Runbook:
`docs/PRODUCTION_RUNBOOK.md`.

## 6. Falsa confianza (prohibido declarar)

Un suite verde NO demuestra:

| Afirmación | ¿Probable en JVM? |
|---|---|
| "el tracking sobrevive al cfreezer" | **NO** — solo dispositivo |
| "FCM despierta la app en Doze real" | **NO** — solo dispositivo |
| "no hay pérdida de posiciones 24 h" | **NO** — solo jornada real |
| "la migración Room conserva datos" | Instrumentado, no unit |
| "el ACK/retry no pierde mensajes" | Sí, como POLÍTICA (unit) |

## 7. Criterio de cierre de fase

- Unit + integration verdes (ejecutados, no asumidos).
- Lint limpio o excepción documentada.
- Lo físico queda como **PENDING REAL-WORLD VALIDATION** con evidencia
  (logs/DB), nunca como PASS inferido.
