# DEPENDENCY_RULES.md — Reglas de dependencia entre paquetes (fase R4/R5)

> Estado: **aplicadas y verificadas** (2026-09-17). Fuente de verdad: imports
> reales en `mobile/app/src/main/java/com/dmujeres/traccar` + test
> `ArchitectureDependencyTest` (JVM, suite móvil 588/0).
>
> Este documento fija DIRECCIONES permitidas; no describe features. Si una
> necesidad legítima rompe una regla, primero se discute la frontera y luego se
> actualiza el test + este documento (nunca al revés).

## 1. Grafo real por dominio (tras R3/R4)

```
ui ──────────────► (todo: es la capa de presentación)
(raiz/DmujeresApp)► config, data, platform, recovery
tracking ────────► config core data diagnostics health location outbox
                   platform readiness recovery sensors transport ui.widget
readiness ───────► config core oem platform recovery tracking*
recovery ────────► config core data outbox platform tracking*
diagnostics ─────► config core data oem outbox platform sensors
platform ────────► config core ui (PendingIntent del launcher)
outbox ──────────► core data transport
transport ───────► config core (R7: puerto ControlQueueStore)
health ──────────► config core data oem
location ────────► config core
data ────────────► config core
config ──────────► core
core ────────────► (ninguno)
oem / sensors ───► (ninguno hoy; se permite config/core)
```

`*` = arista de ENTRADA (receivers/worker/readiness arrancan `TrackingService`);
es la única forma en que un dominio "hacia arriba" nombra tracking. No se
considera violación porque es el punto de composición del sistema Android.

## 2. Reglas ejecutables (lo que el test prohíbe)

| Paquete | Prohibido importar | Excepción |
|---|---|---|
| `core` | todo `com.dmujeres.traccar.*` | — |
| `config` | todo salvo `core` | — |
| `data` | todo salvo `config`, `core` | — |
| `health` | `tracking`, `ui` | — |
| `location` | `tracking`, `ui` | — |
| `oem` | `tracking`, `ui`, `outbox`, `transport`, `health`, `location`, `diagnostics`, `recovery`, `readiness` | — |
| `sensors` | ídem `oem` | — |
| `diagnostics` | `tracking`, `ui`, `transport` | — |
| `transport` (R7) | `data`, `outbox`, `tracking`, `ui`, y todos los dominios de datos | — |
| `outbox` | `tracking`, `ui`, `diagnostics`, `health`, `location`, `oem`, `platform`, `readiness`, `recovery`, `sensors` | — |
| `platform` | `tracking` y todos los dominios de datos/transporte | `ui` (PendingIntent) |
| `readiness` | `ui` | `tracking` (entrada) |
| `recovery` | `ui` | `tracking` (entrada) |
| `tracking` | `ui` | `ui.widget` (fachada de widget) |

El enforcement vive en
`app/src/test/java/com/dmujeres/traccar/architecture/ArchitectureDependencyTest.kt`
(escaneo de imports; sin Android, sin dependencias nuevas).

## 3. Ciclos: antes → después

Auditoría R0 detectó 9 ciclos de paquete. Tras R3/R4:

| Ciclo R0 | Estado actual | Cómo |
|---|---|---|
| `raíz ↔ location` | ELIMINADO | TrackingService vive en `tracking` |
| `raíz ↔ util` | ELIMINADO | `util` desapareció; `diagnostics` no depende de la raíz |
| `raíz ↔ worker` | ELIMINADO | `recovery` no importa la raíz |
| `config ↔ mqtt` | RESUELTO | topics en `core/MobileProtocol`; `MqttStatus` en `core` |
| `config ↔ util` | ELIMINADO | `util` desapareció |
| `location ↔ widget` | ELIMINADO | notificación/widget en `tracking`+`ui` con callbacks |
| `location ↔ worker` | ELIMINADO | `location` ya no conoce recovery |
| `mqtt ↔ util` | RESUELTO | `RttMeter`/`SentryLog` en `diagnostics`/`platform` |
| `receiver ↔ worker` | ELIMINADO | ambos en `recovery` |

Ciclos restantes (todos de ENTRADA/UI, aceptados y documentados):

| Ciclo | Arista "hacia arriba" | Por qué se acepta |
|---|---|---|
| `readiness ↔ tracking` | `DeviceReadinessChecker`/`ContinuityTracker` → `TrackingService.start` | Composición Android: un checker arranca el servicio |
| `recovery ↔ tracking` | `BootReceiver`/`SessionKeeper`/worker → `TrackingService` | Ídem: receivers son puntos de entrada del SO |
| `platform ↔ ui` | `Notifications` → `MainActivity` (PendingIntent) | La notificación abre el launcher: clase de UI inevitable |

## 4. Aristas correctas valiosas (no tocar a la ligera)

- `tracking → health/diagnostics/outbox/transport` por callbacks y fachadas:
  el servicio orquesta, los dominios ejecutan.
- `outbox → transport` solo para `Envelope` (formato de cable compartido).
- `transport → diagnostics` solo `RttMeter` (métrica de RTT MQTT).
- `oem/sensors` sin dependencias: son hojas puras/aisladas.

## 5. Cómo evolucionar

1. ¿Nueva clase? Va al dominio por RESPONSABILIDAD, no a `tracking` por costumbre.
2. ¿Necesitas una arista prohibida? Posibles salidas en orden:
   callback inyectado por el borde, mover el VALOR compartido a `core`,
   o mover la clase al dominio que corresponde.
3. Si aun así se justifica: actualizar test + tabla de §2 + `REFACTORING_LOG.md`
   con la razón. El test nunca se debilita en silencio.

## 5. Ciclos (R7, 2026-09-17)

`DependencyCycleTest` congela los ciclos: ningún paquete fuera de la deuda
aceptada puede participar en uno (y `transport` nunca). Ciclo aceptado hoy:
`tracking ↔ readiness ↔ recovery ↔ ui ↔ platform ↔ diagnostics` (plan de rotura
en `TECHNICAL_DEBT.md` A-1).

Movimientos R2 que habilitaron la regla `transport` sin `data`:

- `data/DispatchLock.kt` → `core/DispatchLock.kt` (primitiva pura compartida).
- `diagnostics/RttMeter.kt` → `core/RttMeter.kt` (puro).
- `transport/ControlQueue.kt` (puerto) + `outbox/RoomControlQueueStore.kt`
  (adaptador Room). `MqttManager` ya no importa `data`/`outbox`.
