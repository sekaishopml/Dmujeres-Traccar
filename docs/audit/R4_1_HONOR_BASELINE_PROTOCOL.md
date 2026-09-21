# R4_1_HONOR_BASELINE_PROTOCOL.md — Protocolo corto (Joseph / HONOR)

> R4-1 · sin cambios de código (CODE FREEZE) · captura con
> `infrastructure/scripts/r4_capture_session.sh` (solo lectura).

## 0. Pre-flight (teléfono del operador)

1. En la app: **Diagnóstico → Device Readiness** (o al iniciar jornada, el paso
   vendor). Completar la guía HONOR que muestra la app:
   - Ajustes → Batería → **Inicio de aplicaciones** → DMujeres →
     **Gestionar manualmente** → activar **Auto-inicio**, **Inicio secundario**
     y **Ejecutar en segundo plano**.
   - **Batería → Sin restricciones** (quitar optimización) para DMujeres.
   - **Recientes → bloquear DMujeres** (candado).
   - Ahorro de energía OFF durante la prueba.
2. Verificar en la app: **Device Readiness = READY** (todos los checks).
3. Anotar estado: sessionId, bootId, journeyId, batería, versión (la captura lo
   registra automáticamente).
4. **Sesión limpia:** finalizar jornada anterior → iniciar jornada nueva.

## Fase A — 15 min con pantalla encendida

- Tracking normal, app abierta o en primer plano sin usarla.
- Esperado: GPS OK, FGS activo, MQTT/HTTP entregando (delta ~2-7 s), outbox ≈0.

## Fase B — pantalla apagada (30 min; si va bien, extender a 60)

- Apagar pantalla y **no tocar el teléfono**. No abrir la app.
- Si se puede dejar quieto (encima de una mesa), mejor: el caso es "nadie toca".

## Captura y clasificación (yo lo ejecuto)

```bash
bash infrastructure/scripts/r4_capture_session.sh 39 "YYYY-MM-DD HH:MM" "YYYY-MM-DD HH:MM"
```

Interpretación (solo con evidencia):

| Patrón observado | Clasificación |
|---|---|
| Fixes capturados (calidad) + outbox crece + sin entrega + flush al despertar | **OEM/process suspension** (no es GPS, no es transporte) |
| GPS deja de capturar + FGS ausente + heap muerto | **PROCESS_DEATH / FGS_LOSS / OEM_LIMITATION** |
| GPS + outbox OK, MQTT cae, HTTP entrega | **MQTT transport separado del freeze** |

## Criterio de fase

- **PASS**: 30–60 min con pantalla apagada manteniendo captura + FGS + entrega
  (o buffering demostrado con drenaje completo y 0 pérdidas).
- **FAIL/DEGRADED**: si falla a los ~8 min → no tiene sentido ir a 6/12/24 h sin
  investigar antes.

## Observaciones pre-test (captura 21:57–23:27, sin cambios)

- Joseph despierta ~cada 14-15 min: fixes EXCELLENT/GOOD (gnssUsed 8–26), a
  veces entrega en vivo (delta 2–33 s), a veces buffer (delta 930–1841 s).
- Cadena FCM completa OK en 22:24:06 (RECEIVED→STARTED→FGS_ACTIVE→
  TRACKING_ACTIVE→GPS_CONFIRMED); otros attempts `TIMEOUT_NO_DELIVERY`.
- `RECOVERY_ACK_REJECTED INVALID_TRANSITION` (22:08): la app ACKea etapas de un
  attempt que el servidor ya cerró → nota para R4-7 (observabilidad), NO tocar
  ahora.
