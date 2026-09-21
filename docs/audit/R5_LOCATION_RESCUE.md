# R5_LOCATION_RESCUE.md — Rescate de localización (1.1.7, versionCode 117)

## Qué problema resuelve
Con jornada activa, el GPS puede dejar de entregar fixes mientras el teléfono
sigue en movimiento (multipath, interior, GNSS degradado). Ya existía la FUSIÓN
de evidencia (`LocationSensorFusion`: GPS_HEALTH/DEGRADED/LOST +
MOVEMENT_LIKELY) pero nadie ACTIVABA una reacquisición agresiva temporal.

## Qué NO hace
- NO produce coordenadas (invariante verificada en test §27).
- NO hace dead reckoning ni interpolación.
- NO toca Room/Outbox, HTTP/MQTT, Replay, FCM Recovery ni fixTime.
- NO resuelve freezes OEM (proceso congelado → MobileSilenceMonitor/FCM siguen
  siendo el camino; `TIMEOUT_NO_FGS` sigue sin demostrar FGS muerto).

## Cómo funciona (máquina de estados, política pura)
NORMAL → (fix envejece >60 s) GPS_DEGRADED → +movimiento (1ª evidencia)
MOVEMENT_CANDIDATE → +2ª evidencia consecutiva → RESCUE (burst 90 s de
reacquisición: `nudgeRefresh()` + one-shot HIGH_ACCURACY existentes, cada 5 s)
→ REAL FIX → FIX_RECOVERED → NORMAL. Sin fix al fin del burst → TIMEOUT con
evidencia en health y cooldown 5 min (anti-loop). GPS_LOST + STATIONARY → sin
burst. GPS_LOST + MOVEMENT_LIKELY → RESCUE directo.

Evidencia de movimiento: MotionSensor (histéresis propia: no flapea con un
pico) + desplazamiento real ≥15 m desde el ancla estacionaria. Umbrales
reutilizados: 1.5 m/s (fusión/stop-detection), 15 m (FixFilter), 60 s/300 s
(fusión). Sin hacks OEM, sin WakeLock permanente, sin feature creep.

## Evidencia y telemetría
- Health snapshots: `GPS_RESCUE_STARTED/TIMEOUT/FIX_RECOVERED` (mismo mecanismo
  existente, asíncrono).
- Payload del primer fix tras un CAPTURE_GAP: `gapMs`, `movementDuringGap`,
  `rescueAttempted`, `rescueRecovered` (additive, schema:1 tolera extras).
- Breadcrumbs Sentry `gps` para auditoría.

## Replay
R3/F13/F14 intactos: `shouldCut`, SAME_PLACE, DELIVERY_DELAY, CAPTURE_GAP sin
cambios. La nueva evidencia (`gapMs`, `movementDuringGap`) queda disponible en
los atributos; el replay no rellena ni oculta nada.

## Tests (nuevos: 17)
- `MovementRescuePolicyTest` (12): A-H, J (cooldown), M (sin jornada),
  desplazamiento como evidencia.
- `MovementRescueControllerTest` (4): arranque/stop con fix real, cooldown,
  GPS_LOST+stationary sin rescate, sin coordenadas.
- `NoSyntheticLocationTest` (2): fusión y política no tienen vía para lat/lon
  (reflexión sobre los data classes: jamás existirán campos lat/lon).

## Limitaciones OEM
Si el proceso está congelado, ni sensores ni LocationEngine corren: eso lo
sigue manejando MobileSilenceMonitor → FCM → Recovery ladder → usuario. Esta
mejora aumenta la recuperación cuando el proceso ejecuta. No promete 100%.

## Afirmación correcta
La arquitectura mejora la detección y recuperación ante degradación de
localización mientras el proceso permanece ejecutándose, conserva la integridad
de las posiciones reales mediante Outbox y representa explícitamente los
periodos donde no existieron fixes reales.
