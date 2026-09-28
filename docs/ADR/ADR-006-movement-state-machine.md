# ADR-006 — Máquina de estados de movimiento

Estado: **CERRADO** (2026-09-28).

## Contexto

Caso Pilay: la app decidía moviendo/parado con el **acelerómetro**
(`MotionMonitor.kt:37-38`: std ≤0.15 quieto, ≥0.6 moviendo; UNKNOWN no cambia
pero el siguiente `motionTick` puede resolverlo a parado). En marcha constante
el sensor queda "quieto" → cadencia 120 s → menos fixes → no llega evidencia
de velocidad → deadlock. La velocidad GPS existía como rescate
(`MotionSignal`, 3 kn) pero requiere un fix que el modo lento no produce.

## Decisión

Máquina formal `STOPPED | STARTING | ACTIVE | STATIONARY | DEGRADED |
RECOVERING` con entrada combinada por prioridad:
1. GPS speed ≥3 kn; 2. desplazamiento acumulado ≥150 m; 3. significant motion
/ giro; 4. acelerómetro (último indicio, nunca único); 5. historial persistido.

Reglas: `UNKNOWN → ACTIVE` (captura fina, seguro); `STATIONARY` solo con ≥3 min
de evidencia consistente; sin fix nuevo **no** se degrada a lento; a los 4 min
sin fix → `RECOVERING`. Tras recreación, el estado se reconstruye del store.

## Alternativas descartadas

- Activity Recognition como autoridad: permisos extra y resultados tardíos;
  queda como señal opcional.
- Solo GPS speed: en indoor/túneles no hay velocidad y se perdería captura.

## Consecuencias

- `MotionMonitor` pasa a ser un input más de la máquina.
- Test unitario puro de la máquina en IA-2 (sin dispositivo).
