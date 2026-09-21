# R3_MASTER_AUDIT.md — Auditoría maestra integrada (2026-09-17)

> Integración de los 9 auditores (ver `R3_AGENT_REPORT.md` y `docs/audit/*.md`).
> Fuente de verdad: código real + suites ejecutadas hoy (móvil 588/0, server 821/0,
> dashboard 80/0). Baseline: `R3_BASELINE.md`.

## 1) Estado real verificado

- Compila, tests verdes, lint verde; 14 reglas de dependencia sin violaciones;
  ciclo de orquestación único y congelado.
- Integridad de datos/Room: limpia (v9, 8 migraciones aditivas, sin destructive).
- Outbox con invariantes correctos y transaccionalidad en server.
- La infraestructura de rutas NO es la causa de los "saltos" (david control 1:1).

## 2) Hallazgos priorizados (maestro)

| # | Hallazgo | Severidad | Fase asignada | Origen |
|---|---|---|---|---|
| P1-A | `linkTracksFor` dibuja conectores rectos sin `shouldCut` (rectas de joseph/macias) | ALTO (bug demostrable, visible) | **F13/F14** | I |
| P1-B | `syncDelayMs` mezcla UTC vs hora local → +18 000 s fantasma; delays reales invisibles | ALTO (bug) | **F13/F14** | I |
| P1-C | `detectStops` sin tope de distancia → parada fantasma (macias 994 m) | MEDIO (bug) | **F13/F14** | I |
| P1-D | R-01: AlertDialog sobre Activity destruida (testConnection→runOnUiThread) | ALTO (crash real potencial) | **F9** | G |
| P1-E | Health snapshots no subidos se purgan a 24 h (`TrackingHealthMonitor.kt:77`) | MEDIO (pérdida de evidencia) | **F6/F10** | C |
| P2-A | AppConfig God Object (911, ~65 claves, 12 consumidores) | MEDIO | **F4** | A |
| P2-B | Races MQTT RR-1/RR-2/RR-3 (callback viejo, doble startDispatch, stall sin wake) | MEDIO | **F5** | D |
| P2-C | UI: lógica de dominio en Activities (login/gate/recovery/OTA) | MEDIO | **F8** | F |
| P2-D | FCM `postDelayed` sin removeCallbacks; watchdog sin interrupt | BAJO | **F9** | G |
| P2-E | Métrica muerta `reconnect24h`; LWT `sequence=0`; `ackTopic` hardcodeado | BAJO (contrato) | **F5** | D |
| P3-A | `core` no puro: `JourneyFormatter` usa Context/R | MEDIO | **F3/F11** | A |
| P3-B | Sin WAKE_LOCK/alarmas exactas (robustez OEM) | MEDIO (mejora) | **F12** | B |
| P3-C | OTA sin verificación de firma/SHA; sin rate limit móvil; MQTT sin TLS | ALTO (seguridad) | **F2** (parcial, coordinado) | E |
| P3-D | androidTest MIGRATION_8_9 ausente | MEDIO | **F10** | H |
| P3-E | Sin tests de caracterización para onNewLocation/dispatchLoop/applyRemote | ALTO (antes de refactorizar F7/F5/F4) | **F5-F7** | A/H |
| P3-F | Secretos expuestos: rotación pendiente (API key, DB, EMQX, release.keystore) | CRÍTICO (operativo) | **F2 (plan)** | E |

## 3) Decisions (§49) y reglas duras que fija el orquestador

1. **Segmentación**: `RouteSegmentAnalyzer` puro decide; el frontend dibuja. Tipos:
   OBSERVED_CONTINUOUS / CAPTURE_GAP / DELIVERY_DELAY / RECOVERY_GAP / UNKNOWN.
   CAPTURE_GAP → **no dibujar la recta** (decisión del operador); DELIVERY_DELAY →
   tramo normal + indicador de retraso; RECOVERY_GAP → indicador; UNKNOWN → neutro.
   Sin reglas por dispositivo (regla 52); `MAX_GAP_MS` deja de ser única regla.
2. **Nada** de alterar posiciones/timestamps/coordenadas; map-matching solo capa
   visual opcional (nunca para ocultar gaps).
3. **Locking** (regla 50): los 7 archivos bloqueados solo se modifican en su fase,
   uno a la vez, por el orquestador (o agente expresamente asignado).
4. **Caracterización ANTES de refactor** (C3 onNewLocation, C2 dispatchLoop, C1
   applyRemote) — sin tests, no se toca la pieza (regla 32/R32).
5. **Seguridad**: rotaciones que requieren flota/ventana se ejecutan coordinadas;
   en F2 solo movimientos seguros (provisioning, verificación, doc).

## 4) Plan de fases (orden del operador, con gate compile→test→lint tras cada una)

F1 Integridad/gate → F2 Seguridad → F3 Guardarraíles → F4 AppConfig → F5 MQTT →
F6 OUTBOX → F7 Pipeline tracking → F8 UI → F9 Coroutines → F10 Room → F11 DI/Clock →
F12 OEM/Readiness → **F13 Route segmentation (policy+tests A–G)** → **F14 Dashboard**
→ F15 Documentación final (R3: ARCHITECTURE_FINAL, DEPENDENCY_RULES,
MODULE_BOUNDARIES, TECHNICAL_DEBT, REFACTORING_LOG, TESTING_STRATEGY,
SECURITY_BUILD, ROUTE_SEGMENTATION) + tabla BEFORE/AFTER + informe final con la
frase exacta: **"ARCHITECTURALLY REFACTORED + AUTOMATED TESTS VALIDATED +
REAL-WORLD VALIDATION PENDING"**.

## 5) Criterios de no-acción (§43)

Sin reescrituras totales, sin Hilt/Compose/modularización por moda, sin borrar
tests, sin ocultar warnings, sin hacks OEM, sin cambiar contratos sin
justificación. Cambios funcionales solo: bug demostrable / seguridad /
segmentación incorrecta / dependencias arquitectónicas — cada uno con
PROBLEMA/CAUSA/CAMBIO/RIESGO/TEST.
