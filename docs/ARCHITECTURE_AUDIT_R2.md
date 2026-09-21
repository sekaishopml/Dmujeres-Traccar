# ARCHITECTURE_AUDIT_R2.md — Auditoría de arquitectura real (R3, 2026-09-17)

> Fuente: árbol real `mobile/app/src` (106 archivos `main`, 82 `test`), imports
> reales, suites ejecutadas. No hay conclusiones sin evidencia.

## 1. Clases grandes (líneas)

| Clase | Líneas | ¿Un solo motivo? | Estado |
|---|---|---|---|
| `ui/MainActivity.kt` | 1472 | UI + estado + lógica de arranque/diálogos | Deuda **A-2** (ViewModel R17) |
| `tracking/TrackingService.kt` | 1383 | Orquestador de ciclo de vida | Aceptable con límites; extracciones hechas (R8 previo). Deuda A-3 si crece |
| `ui/OnboardingActivity.kt` | 980 | Flujo de pasos + Compose | Compose con estado; deuda M-3 |
| `config/AppConfig.kt` | 911 | SharedPreferences de todo el sistema | Deuda **A-4** (split R10) |
| `ui/DiagnosticsActivity.kt` | 890 | Presentación de diagnóstico | Deuda A-2 |
| `transport/MqttManager.kt` | 778 | Conexión + suscripción + dispatch de presencia + ACK | Frontera de persistencia ya extraída (puerto). Split de conexión/suscripción en deuda M-6 |
| `location/LocationEngine.kt` | 531 | Adquisición FLP/GNSS + timeouts | OK (R9) |
| `ui/components/MetricsRow.kt` | 522 | UI pura | OK |
| `outbox/PositionOutboxDispatcher.kt` | 412 | Dispatcher HTTP (object con estado) | Deuda **A-6** (singleton mutable, R12) |

## 2. Singletons y estado global

- 68 `object` en total; la mayoría son **políticas puras** (correcto).
- Estado mutable real concentrado en:
  - `outbox/PositionOutboxDispatcher` (`object` con `Transport`, callbacks y
    `AtomicBoolean`). Aceptado hoy por ser propietario único del dispatch HTTP;
    alternativas evaluadas en TECHNICAL_DEBT A-6.
  - `core/MqttStatus` y `platform/*` (estado observado por UI/diagnóstico):
    aceptado (lecturas sin lock vía `@Volatile`).
- `GlobalScope`: **0 usos**. Los 3 `CoroutineScope` propios (`TrackingService`,
  stop controller, dispatcher) tienen owner y cancelación.

## 3. Android en dominio

- `domain` no existe como paquete formal; el rol lo cumple `core` +
  políticas puras por dominio (`*Policy`, `*Formatter`).
- `core` hoy no depende de Android salvo `android.util.Log` en algunas
  políticas (permitido por el proyecto; documentado). `RttMeter` y
  `DispatchLock` son JVM puros (movidos desde `diagnostics`/`data`).
- Room: imports solo en `data/` y `outbox/` (adaptador). **0** usos de Room
  fuera de esas capas.

## 4. Coroutines / concurrencia

- Sin `GlobalScope`; `Handler/Looper` en 14 puntos (notificaciones, UI,
  SessionKeeper) con documentación; 7 `registerReceiver` (recovery/tracking).
- `catch (_: Exception)` 4 usos, todos en bordes de cancelación/parseo con
  comentario (no silencios que oculten fallos de negocio).

## 5. Dependencias prohibidas (test ejecutable)

- `ArchitectureDependencyTest` (1 test) — reglas por paquete.
- `DependencyCycleTest` (2 tests, NUEVO) — ningún paquete fuera de la deuda
  aceptada puede participar en un ciclo; `transport` nunca en ciclo.
- **Fijo ahora**: `transport` no importa `data` ni `outbox`
  (`ControlQueueStore` es el puerto; `RoomControlQueueStore` implementa en
  `outbox`).

## 6. Ciclos (estado real)

Un único ciclo de orquestación:
`tracking ↔ readiness ↔ recovery ↔ ui ↔ platform ↔ diagnostics`.
Causas: receivers/worker arrancan `TrackingService`; readiness evalúa tracking;
tracking notifica a ui/platform. Rotura planificada con interfaces/eventos
(R13/R16/R17). Congelado por `DependencyCycleTest` para que no crezca.

## 7. Tiempo (R21)

96 usos de `System.currentTimeMillis()` y 1 de `nanoTime()`. Las políticas
críticas (backoff, retención, silencio, jornada) son puras y reciben `now` por
parámetro; los usos directos están en bordes Android/servicio. Deuda A-7
(inyectar `Clock` donde habilite tests de expiración end-to-end sin reloj real).

## 8. Errores y logging (R23/R24)

- Errores por capas: transporte (`friendlyMqttError`, `DispatchPolicy`),
  outbox (cuarentena explícita, nunca borrado silencioso), recovery
  (`RecoveryOutcome`). Sin `catch` vacíos injustificados.
- Sentry: breadcrumbs de recovery/tracking; **nunca** se loguean tokens/keys.

## 9. Conclusión

El árbol es **coherente y compila**; las capas de datos/protocolo están
separadas y con guardarraíles ejecutables. Las deudas grandes restantes son de
presentación (ViewModels), configuración (split de `AppConfig`), reloj
inyectable y el ciclo de orquestación — todas con plan en `TECHNICAL_DEBT.md`
(listas A/N) y sin bloquear la siguiente fase física OEM/ADB.
