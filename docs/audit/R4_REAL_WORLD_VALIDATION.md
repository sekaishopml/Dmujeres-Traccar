# R4_REAL_WORLD_VALIDATION.md — Validación física real (R4)

> Campaña R4 · **CODE FREEZE** (sin commits, sin cambios de código durante la
> campaña) · Registro vivo: se actualiza a medida que se ejecutan fases.
> Estado a 2026-09-17 23:20 local (−05).

## 1) Executive Summary

- **R4-0 (diagnóstico "SIN SEÑAL" Miguel + Joseph): COMPLETADO** →
  `docs/audit/R4_0_SIGNAL_DIAGNOSTIC.md`. Hallazgo: **TRANSPORT_LOSS /
  NETWORK_LOSS con CAPTURA GPS y OUTBOX funcionando** (sin pérdida de datos).
  Descartados con evidencia: GPS_LOSS, PROCESS_DEATH, DASHBOARD_DELAY.
- **R4-6 (Offline Replay): PASS retrospectivo** — Joseph 15:05–16:06:
  **20 capturadas / 20 recibidas / 0 duplicados / fixTime preservado**.
- **R4-7 (Recovery): DEGRADED** — los probes FCM funcionan cuando llegan
  (ACK + GPS_CONFIRMED + 3 SUCCESS), pero la tasa de timeout es alta y hay una
  inconsistencia de verificación (`TIMEOUT_NO_FGS` vs `already-running`).
- **R4-1..R4-5, R4-8..R4-15: PENDING** — requieren manipulación física de
  dispositivos (pantalla, movimiento, pérdida de red, reboot, endurance).
  ZTE de pruebas offline 14 h; teléfonos de flota sin ADB desde este host.
  Protocolos listos abajo para ejecución por el operador.

## 2) Device Matrix (estado 23:09 local)

| deviceId | user | modelo | Android | app | estado ahora | batería | oemConfirmed | readiness |
|---|---|---|---|---|---|---|---|---|
| 50 | macias | ZTE Z2450 | 14 | 1.1.5 | EN LÍNEA (23:09) | — | sí | ready |
| 40 | kevin | — | — | 1.1.5 | EN LÍNEA (23:08) | — | — | — |
| 41 | miguel | Infinix X6876 | 16 | 1.1.5 | EN LÍNEA (23:08) | 83 % | **sí** | ready |
| 42 | david | — | — | 1.1.5 | sin actividad 23 min | — | — | — |
| 39 | joseph | HONOR LGN-LX3 | 15 | 1.1.5 | **offline 22:39** | 58 % | **no** | **not_ready** |
| 47 | qa-f0 (ZTE test) | ZTE Z2450 | 14 | 1.1.2 | **offline 14 h** (Tailscale) | — | — | — |
| 43,37,38,2 | pedro/admin/test/santiago | — | — | — | >1 día sin actividad | — | — | — |

## 3) R4-0 Signal Diagnostic

Completo en `docs/audit/R4_0_SIGNAL_DIAGNOSTIC.md` (21 secciones + evidencia SQL)
**+ R4-0.5 TRANSPORT ANALYSIS** (constantes reales, máquina de estados, tabla de
correlación, `reconnects=0` causa exacta, clasificación NO_FGS).

Conclusión refinada (R4-0.5): **G COMBINED — ciclos de freeze/suspensión del
proceso por OEM (HONOR `oemConfirmed=false`; también INFINIX) con thaw
intermitente por FCM + OBSERVABILITY GAP; captura GPS y Outbox íntegros.**
NETWORK_LOSS y SERVER descartados en los episodios analizados; TRANSPORT BUG no
demostrado.

## 4) Miguel

15 episodios / 2.5 h / p50 5.1 min / máx 35 min (18:44→19:19). Capturó en el
hueco (fix 18:49:35 DEGRADED, entregado 19:19:30, delta 1795 s); outbox 0→2→0.
`exempt: true`, `oemConfirmed: true`, readiness `ready`. Sin crashes.

## 5) Joseph

24 episodios / 5.9 h / p50 5.1 min / máx 80 min (12:13→13:34). Incluye al menos
2 huecos de 50 min con 0 mensajes al servidor y drenaje en lote (23 msgs/31 s).
`exempt: true`, **`oemConfirmed: false`**, readiness `not_ready`, **crashes24h=3**.
Acción: completar guía OEM en el teléfono (fuera de CODE FREEZE, es operativo).

## 6) Baseline (R4-1) — PENDING

Protocolo: tracking normal 15–30 min y confirmar GPS/GNSS/accuracy/speed/bearing,
Room, Outbox, MQTT, HTTP, server, timestamps.
Comandos (ZTE cuando esté online):
`adb install -r DMujeres-Tracking-1.1.5.apk` → abrir app → iniciar jornada →
`adb shell dumpsys location | grep -i location` + verificación SQL.

## 7) Screen Off (R4-2) — PENDING

Protocolo: iniciar tracking → confirmar fixes → apagar pantalla
(`adb shell input keyevent KEYCODE_POWER`) → mover el dispositivo → verificar
posiciones reales en servidor (no basta proceso vivo). Registrar PROCESS/FGS/
GPS/GNSS/NETWORK/MQTT/HTTP/OUTBOX/SERVER.

## 8) Stationary → Movement (R4-3) — PENDING

Métricas: movementDetectionLatency, gpsReacquisitionLatency, firstFixLatency,
firstFixAccuracy, timeToStableTracking, falseMovementCount. Verificar en SQL:
primer fix tras el estacionamiento (`qualityClass`, `gnssUsed`, `fixAgeSec`,
delta servertime−fixtime). Prohibido lastLocation como fix nuevo (ya garantizado
por diseño; verificar empíricamente).

## 9) Screen Off + Movement (R4-4) — PENDING (prioridad alta)

Estacionado + pantalla apagada → movimiento real → sensor → MOVEMENT_CANDIDATE →
GPS_REACQUISITION → FIRST_FIX → MOVING → outbox → transport → server.

## 10) Network Loss (R4-5) — PENDING

Durante movimiento: modo avión/datos off → verificar outbox crece con capturas →
restaurar → verificar drenaje y `fixTime` original vs `serverReceivedAt`.

## 11) Offline Replay (R4-6) — **PASS (retrospectivo, evidencia real)**

Ventana Joseph 15:05–16:06: **capturadas 20 = recibidas 20, duplicados 0**,
todas con `fixTime` original (delta 899–2700 s), drenaje completo (outbox 21→0).
Miguel 18:44–19:19: 1/1 capturada entregada (1795 s).
No aplica aún la comparación en vivo con pérdida provocada (R4-5 pendiente).

## 12) Recovery (R4-7) — **DEGRADED** (evidencia server-side)

- Joseph: 38 sent / 35 timeout / 3 SUCCESS / 21 SERVER_ACK / 17 ACK_REJECTED /
  19 GPS_CONFIRMED. Miguel: 56 SKIPPED_NO_TOKEN (hasta 17:54) y 19 sent / 18
  timeout.
- FCM SUCCESS solo con evidencia server-side (respetado: `RECOVERY_ACK` +
  `position-after-probe`).
- Inconsistencia a documentar: `TIMEOUT_NO_FGS` con `STARTED already-running`.

## 13) Doze (R4-8) — PENDING (dispositivo real)

## 14) Reboot (R4-9) — PENDING

Protocolo: tracking + jornada activos → anotar outbox/FCM token → reboot
(`adb reboot`) → verificar BootReceiver, TrackingService, jornada, outbox, FCM,
health.

## 15) Battery Restriction (R4-10) — PENDING

Ambos candidatos están `exempt: true`; no hay comparativo Unrestricted/Optimized/
Restricted aún.

## 16) Thermal (R4-11) — **UNKNOWN** (sin telemetría térmica actual)

No se atribuye nada al thermal; requiere instrumentación/medición en R4.

## 17) Force Stop (R4-12) — PENDING

Protocolo: `adb shell am force-stop com.dmujeres.traccar` → registrar before/
after; no exigir recuperación automática tras USER_FORCE_STOP.

## 18-20) Endurance 6 h / 12 h / 24 h (R4-13..15) — PENDING

Solo tras R4-0 (✔) y pruebas funcionales. Seleccionar 2–3 dispositivos
recientes/saludables (p. ej. miguel, kevin, macias). Métricas completas §23.

## 21) Replay

Segmentación R3/F14 desplegada (B-1/B-2/B-4): no se une CAPTURE_GAP; rectas de
joseph/macias eliminadas del build servido. Validación retrospectiva OK; falta
validación visual con los tipos nuevos cableados a la UI (fase 2/3 documentada).

## 22) Dashboard

Verificado: "SIN SEÑAL" = presencia offline por timeout (correcto). Se registró
la semántica pedida: **EN LÍNEA ≠ TRACKING HEALTHY** (p. ej. Joseph aparece
offline con captura y outbox OK; health SILENT/DEGRADED).

## 23) Metrics (calculadas donde hay evidencia)

| Métrica | Joseph | Miguel | Nota |
|---|---|---|---|
| JOURNEY_TIME (hoy) | continuo desde 15/09 (jornada eterna) | desde 15/09 | cerrar jornadas = acción operativa |
| TRACKING_TIME | captura con cadencia reducida en stationary | ídem | — |
| CAPTURE_GAP_TIME | 0 demostrado en ventanas analizadas | 0 | fixes capturados en cada hueco |
| DELIVERY_DELAY (buffered) | 899–2700 s | 1795 s | visualizable ahora (B-2 fix) |
| RECOVERY_GAP_TIME | 5.9 h presencia offline | 2.5 h | recuperado por RECONNECT |
| positions captured vs received (ventana R4-6) | 20/20 | 1/1 | loss 0, dup 0 |
| outboxPeak / drain | 21 → 0 en ~31 s | 2 → 0 | PASS |
| recoverySuccessRate (24 h) | 3/38 | 0/19 | DEGRADED |
| duplicateRate | 0 | 0 | — |
| dataLossRate | 0 (evidencia) | 0 (evidencia) | — |
| battery/h y thermal | PENDING (R4 físico) | PENDING | — |

## 24) Failures

Ninguno bloqueante. Observabilidad incompleta (§20 R4-0) y recovery con tasa
baja quedan como DEGRADED/PENDING; no se parchean durante CODE FREEZE.

## 25) OEM limitations

- HONOR (Joseph): `oemConfirmed:false` + 3 crashes/24 h → completar guía.
- INFINIX (Miguel): `oemConfirmed:true` y aun así suspensión nocturna (histórico).
- ZTE (qa-f0/macias): cfreezer conocido (histórico F2) — limitación documentada.

## 26) Evidence

- `docs/audit/R4_0_SIGNAL_DIAGNOSTIC.md` (SQL completo).
- Consultas R4-6: `tc_positions` (20), `tc_mobile_messages` (20 posiciones en
  lote 16:05, 0 duplicados), `tc_device_health` (outbox 0→2→21→0),
  `tc_recovery_event` (ladder).
- Build/OTA vigentes: `latest.json` con sha256 verificado (R3.5).

## 27) Remaining risks

1. Fases físicas no ejecutadas (dependen de dispositivos/operador).
2. Recovery FCM con tasa de timeout alta y verificación inconsistente.
3. Observabilidad: sin intentos de conexión fallidos, sin thermal, screenon
   vacío en health, métrica `reconnects` muerta.
4. Jornadas eternas en varios dispositivos (joseph ~2.5 días): inflan estados.
5. Rotaciones de secretos y wake lock/alarmas siguen diferidos (sin evidencia).

## 28) Final verdict

- R4-0: **PASS** (diagnóstico concluyente con evidencia).
- R4-6 (offline replay): **PASS** (retrospectivo).
- R4-7 (recovery): **DEGRADED**.
- R4-1..5, R4-8..15: **PENDING** (campaña física — ejecutar con dispositivos).
- Global: **PENDING — REAL-WORLD CAMPAIGN IN PROGRESS** (R4-0 completado;
  no se declara validación total sin fases físicas).
