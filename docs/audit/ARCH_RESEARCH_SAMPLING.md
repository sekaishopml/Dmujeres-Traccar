# ARCH_RESEARCH_SAMPLING — Cadencia, batería, ángulo y duty cycle

Pregunta del supervisor: **¿5 s en movimiento es "muy agresivo" o por eso no se traza la ruta?**
Veredicto: **5 s no causa los huecos** (los causa la congelación OEM); sí está en el extremo agresivo (nivel alarma/robo, no flota estándar) y bajar a 10 s preserva la ruta urbana. Evidencia local: `TRACKING_RELIABILITY.md` §3 (qa-f0: continuity 9.02 %, hueco 10 h 29 m, proceso vivo 1 h 02 m). Fecha: 2026-09.

## 0. Qué dice la evidencia sobre "5 s = ruta rota"

- OwnTracks documentó huecos de horas en Android 14 con `BalancedPowerAccuracy` por deep sleep del sistema; se corrigió subiendo a HighAccuracy, no subiendo frecuencia: https://github.com/owntracks/android/issues/1865
- Fabricantes limitan requests repetidos a 30–120 s salvo "continuous tracking" (r/androiddev: https://www.reddit.com/r/androiddev/comments/18tkrmd/background_location_limits) y matan servicios (https://dontkillmyapp.com/). Ninguna cadencia arregla un proceso congelado.
- Traccar advierte lo inverso: distance/angle obligan a mantener GPS activo y suben el consumo: https://www.traccar.org/forums/topic/battery-drainage-issue-with-traccar-client
- Android pide reservar "intervals of a few seconds for foreground use cases" (https://developer.android.com/develop/sensors-and-location/location/battery) y ~2 min o más en background (https://developer.android.com/develop/sensors-and-location/location/battery/scenarios).

## 1. Cadencias de apps maduras (números concretos)

| App / modo | Movimiento | Quieto | Defaults y notas | Fuente |
|---|---|---|---|---|
| OwnTracks iOS move | 100 m **o** 300 s (lo primero) | sin updates; ping ocasional | defaults 100 m/300 s; "battery like a nav app" | https://owntracks.org/booklet/features/location |
| OwnTracks Android move | fix cada **10 s** | igual | `locatorDisplacement` ignorado en Android | https://owntracks.org/booklet/features/location |
| OwnTracks Android significant | ~15 min balanced | pings esporádicos | displacement default **500 m** (AND con interval) | https://owntracks.org/booklet/features/location |
| Traccar Client (Flutter) | interval 300 s / distance **75 m** / angle 0 | heartbeat 0 (off) | accuracy **medium**; stopDetection on; heartbeat min 60 s | https://github.com/traccar/traccar-client/blob/main/lib/preferences.dart + `settings_screen.dart` |
| Traccar Client (Android viejo) | 300 s; con distance/angle>0 pide al LocationManager cada **1 s** y filtra en código | igual | XML 300; fallback 600; `MINIMUM_INTERVAL=1000` | https://raw.githubusercontent.com/traccar/traccar-client-android/master/app/src/main/java/org/traccar/client/PositionProvider.kt |
| transistorsoft BG | `distanceFilter` **10 m** elástico por velocidad | location services off | stopTimeout 5 min; stationaryRadius min 25 m (~200 m reales); time-based = distanceFilter 0; heartbeat off/min 60 s; significant-only 500–1000 m | https://transistorsoft.github.io/capacitor-background-geolocation/latest/interfaces/GeoConfig.html y https://docs.transistorsoft.com/cordova/AppConfig |
| GPSLogger (mendhak) | 60 s default ("60 s or more") | igual | distance 0, accuracy 40 m, retry 60, timeout 120; 0 s no recomendado | https://gpslogger.app/ y https://raw.githubusercontent.com/mendhak/gpslogger/master/gpslogger/src/main/java/com/mendhak/gpslogger/common/PreferenceHelper.java |
| BasicAirData GPSLogger | fix crudo 1–3 s; filtro de tiempo/distancia libre | igual | apagar GPS entre fixes daña exactitud | https://github.com/BasicAirData/GPSLogger/issues/82 |
| Gadgetbridge | no es tracker del teléfono: reenvía GPX del wearable y delega el GPS del teléfono a OpenTracks | — | sirve de referencia de batería/Bluetooth, no de cadencia GPS | https://gadgetbridge.org/basics/features/sports |
| Life360 | no configurable; "high-precision GPS in transit" | algoritmo de wake-up | coste oficial ≈ **10 % menos de batería/24 h** | https://support.life360.com/hc/en-us/articles/23053716563223-Life360-and-Battery-Usage |
| Google Timeline | "multiple times per minute" navegando | "once every few hours" idle | sin cadencia fija pública | https://policies.google.com/technologies/location-data |
| Flota hardware | 1–5 s robo/racing (cableado); 5–30 s flota; 30–60 s bajo costo | 5–30 min activos a batería | "battery-only cannot sustain 1–10 s for more than a few hours" | https://www.gpswox.com/en/blog/useful-information/gps-fleet-tracking-how-it-works y https://trak-4.com/blogs/gps/how-often-should-a-gps-tracker-update |

## 2. Consumo real medido (puntos/día, %/hora, MB/día)

- **Puntos/día** (8 h movimiento + 16 h quieto con heartbeat 120 s ≈ 480 pts):
  - 5 s → 5 760 + 480 ≈ **6 240 pts/día**
  - 10 s → 2 880 + 480 ≈ **3 360 pts/día**
  - 30 s → 960 + 480 ≈ **1 440 pts/día**
- **Datos**: cada update pesa unos cientos de bytes (https://www.gpswox.com/en/blog/useful-information/gps-fleet-tracking-how-it-works). A ~300 B: 5 s ≈ **1.9 MB/día**, 10 s ≈ **1.0 MB/día**, 30 s ≈ 0.45 MB/día por equipo (flota 6: 3–12 MB/día). El dato no es el problema; la batería sí.
- **Batería (referencias medidas)**:
  - Chip GPS solo: **143–166 mW** en navegación continua (https://arxiv.org/pdf/1503.02656v1); smartphone GPS ≈ **176 mW** (https://petewarden.com/2015/10/08/smartphone-energy-consumption); GNSS moderno hasta 25 mW (https://www.u-blox.com/en/blogs/insights/designing-ultra-low-power-gps-solutions-iot). El costo real es CPU/IO/wakeups por fix, no solo el chip.
  - Logger a **1 Hz** (pantalla mitad del tiempo): **10.6 h** de autonomía (Xperia XA1): https://github.com/BasicAirData/GPSLogger/issues/82
  - OwnTracks move mode (10 s): usuario con **50 % de batería en una mañana**; mantenedor: "sucks the battery dry quickly": https://github.com/owntracks/ios/issues/577
  - Life360 declara ≈10 %/24 h con wake-up inteligente: https://support.life360.com/hc/en-us/articles/23053716563223-Life360-and-Battery-Usage
- **¿5 s es sostenible?** Con FGS + pantalla apagada + gating por movimiento, sí; pero no conviene duty-ciclear apagando el GPS entre fixes (degrada exactitud: GPSLogger/BasicAirData). 5 s continuo es "nivel navegación" (OwnTracks) y las flotas lo reservan a equipos cableados (GPSWOX/Trak-4).
- **Vehículo vs caminata**: estándar de flota 5–30 s (GPSWOX). En ciudad las curvas las captura el ángulo, no la frecuencia. Recomendado: vehículo urbano/interurbano **10 s** (15 s a >50 km/h), caminata **15 s**, y 5 s solo en "modo alerta/seguimiento en vivo".

## 3. Disparo por ANGLE (bearing): umbrales y pitfalls

- Traccar: angle en grados, default **0 = off**, "trigger location updates on heading change"; config de regata usa **8°** con interval 60 s y heartbeat 60 s: https://www.traccar.org/client-configuration y https://roundpalagruza.at/downloads/rpc2025/RPC2025_SI_Appendix_B_Traccar-Client-Configuration_en.pdf
- Frecuencia de evaluación: el cliente viejo evalúa **en cada callback**, pidiendo al LocationManager `MINIMUM_INTERVAL = 1000 ms` cuando angle>0: https://raw.githubusercontent.com/traccar/traccar-client-android/master/app/src/main/java/org/traccar/client/AndroidPositionProvider.kt
- Código real viejo: `abs(location.bearing - lastLocation.bearing) >= angle` **sin normalizar 0/360** → falso positivo al cruzar el norte (359°→1° = 358°): `PositionProvider.kt` (arriba). Nuestro `FixFilter.angleDiffDeg` normaliza (correcto).
- transistorsoft **no** expone ángulo (usa distanceFilter elástico + filtro Kalman/ventana: GeoConfig arriba). Geotab usa "curve logic": log si Δvelocidad >7 km/h, Δposición >**28.5 m** o buffer lleno cada **100 s**: https://support.geotab.com/mygeotab/doc/active-tracking
- Pitfalls verificados:
  - `getBearing()` devuelve **0.0 si no hay rumbo**; `hasBearing()`/`hasSpeed()` pueden venir false/0 aunque el proveedor los declare. Chequear `hasBearing()` y descartar 0/no finito: https://developer.android.com/reference/android/location/Location#getBearing() y https://stackoverflow.com/questions/44574738/what-does-android-location-hasbearing-check
  - El rumbo solo es fiable en movimiento (>~1 m/s); quieto es ruido: https://stackoverflow.com/questions/9281333/location-getbearing-always-0-0
  - Doppler en 0 / chipsets de gama baja (observado en nuestra flota ZTE): sin Doppler no hay speed/bearing (https://logiqx.github.io/gps-guides/guidance/doppler). Fallback: bearing calculado desde el último fix aceptado (ya implementado).
  - Ruido angular en recta: exigir pata mínima (≥8 m) y velocidad implícita (≥1.5 m/s) antes de aceptar un giro (patrón ya implementado en `FixFilter.acceptByRule`).
- **Recomendación**: evaluar ángulo en **cada fix (~1 Hz)**, umbral **15° (rango 10–30°)**, pata ≥8 m, implícita ≥1.5 m/s, normalizar 0/360, ignorar bearing inválido. 15° evita micro-jitter sin perder intersecciones (8° usado en regata con interval 60 s).

## 4. Distance filter / displacement: valores y combinación con interval+angle

- Valores de referencia: transistorsoft **10 m** (elástico; con `distanceFilter 50` a 27 m/s escala a ~300 m), OwnTracks **100 m** (iOS move) / **500 m** (Android significant), Traccar **75 m** default (usuarios: 50 m con 30 s; 10 m en clientes viejos), GPSLogger **0** (off): fuentes de §1.
- Google: `setMinUpdateDistanceMeters` es solo una sugerencia; el sistema puede no entregar todos los updates. Además, si se pide distancia >0 al FLP se pueden **suprimir fixes crudos** y romper la detección de giros. Traccar viejo pasa `0f` de distancia al LocationManager y filtra en código: https://raw.githubusercontent.com/traccar/traccar-client-android/master/app/src/main/java/org/traccar/client/AndroidPositionProvider.kt
- Regla OR confirmada por el autor de Traccar: "We basically apply OR logic... it will report if either 60 seconds passed or 500 meters from last location": https://www.traccar.org/forums/topic/android-client-frequency-and-distance
- OwnTracks es la excepción: en iOS move es OR (100 m **o** 300 s) pero la doc aclara relación **AND** en Android significant (si no se movió > displacement, no se publica ni al cumplirse el interval): https://owntracks.org/booklet/features/location
- **Recomendación**: pedir al FLP `minUpdateDistance = 0` en movimiento y aplicar en código **25–30 m** (urbano) con la regla OR interval O distance O angle. A >15 m/s (54 km/h) elasticidad a 40–50 m; caminando (<2 m/s) 15 m. En quieto: distancia 0 (recibir todo lo que el sistema dé).

## 5. Duty cycle y Doze: cómo lo estructuran las apps maduras

- Patrón estándar: máquina de estados moving/stationary con sensores de movimiento (acelerómetro/activity recognition) que **apaga location services** en quieto; `stopTimeout` default 5 min antes de declarar stationary (transistorsoft GeoConfig: https://transistorsoft.github.io/capacitor-background-geolocation/latest/interfaces/GeoConfig.html). OwnTracks usa modos Move/Significant/Quiet y downgrade por batería (https://owntracks.org/booklet/features/ios). Traccar tiene stopDetection + heartbeat y ofrece wakelock opcional (https://www.traccar.org/client-configuration).
- Doze (Android 6+): suspende red, ignora wakelocks, difiere alarmas estándar y **no corre JobScheduler/WorkManager**; hay maintenance windows cada vez más espaciados. `setExactAndAllowWhileIdle`/`setAndAllowWhileIdle` **no pueden disparar más de una vez cada 9 min por app**; en idle normal un `_WAKEUP` sin allow-idle puede tardar ~15 min: https://developer.android.com/training/monitoring-device-state/doze-standby y https://developer.android.com/reference/android/app/AlarmManager
- Android 12+: alarmas inexactas con ventana <10 min se recortan a 10 min: https://developer.android.com/develop/background-work/services/alarms
- Un **foreground service** con tipo `location` mantiene la app activa (no entra en App Standby) y el FLP sigue entregando fixes con pantalla apagada; Doze no corta el sensor, pero sí la subida de datos → el buffer Room es obligatorio y correcto.
- OEM: Samsung/HONOR/Xiaomi/ZTE/Infinix congelan FGS igualmente (https://dontkillmyapp.com/, https://www.reddit.com/r/androiddev/comments/18tkrmd/background_location_limits). Mitigación real: whitelist, autoarranque, watchdog con `AlarmManager`. Nuestro watchdog de 2 min es válido mientras el proceso vive; si Doze/OEM lo congela, se degrada a ~9 min (cap de allow-idle) o más.
- Interiores: HIGH accuracy mantiene el GPS encendido sin obtener fix (drena y no reporta) y MEDIUM es imprecisa; Traccar lo documenta en https://github.com/traccar/traccar-client-android/issues/385. Por eso: timeout + fallback de precisión y sensores de movimiento (nuestro `ActivePollPolicy` + accuracy ceiling).
- **Qué NO hacer**: interval <5 s permanente (batería, "nivel navegación"); interval >15 min (ruta inútil, en ciudad no captura ninguna calle: https://www.traccar.org/forums/topic/traccar-client-941/page/4); desactivar stop detection y quedar "moving" para siempre (Traccar: wakelock permanente = consumo continuo); pedir al FLP distancia >0 y perder giros; usar `WorkManager` para liveness en Doze (no corre).

## 6. Configuración "perfecta" para nuestro caso (vehículo urbano/interurbano EC, A14–16)

Perfiles:
- **MOVING** (speed >5 m/s o acelerómetro MOVING): FLP `PRIORITY_HIGH_ACCURACY`, interval **10 s**, `minUpdateDistance = 0` (el filtro de 25 m es en código), fastest 5 s. Regla OR: 10 s O 25 m O 15°.
- **STATIONARY** (quieto >5 min): FLP `PRIORITY_BALANCED_POWER_ACCURACY`, interval **120 s** (= heartbeat), distancia 0. Sensores: significant motion (coste casi nulo) para re-enganchar MOVING sin esperar 120 s: https://developer.android.com/reference/android/hardware/Sensor#TYPE_SIGNIFICANT_MOTION
- **MODO ALERTA** (botón pánico / supervisor mirando): interval **5 s** temporal, mismo distance/angle; expira sola a los 30 min.
- Batería <15 %: stationario a 300 s y moving a 30 s (patrón `ActivePollPolicy` actual ya espacia polling a 10 min).

Exponer como **configuración remota** (por flota, con clamp):
- `movingIntervalSeconds` 5–30 (default 10)
- `stationaryIntervalSeconds` 60–600 (default 120)
- `heartbeatSeconds` 60–600 (default 120)
- `distanceM` 10–100 (default 25)
- `angleDeg` 10–45 (default 15)
- `movingPriority` high|balanced (default high)
- umbrales de batería baja y modo alerta

Dejar **fijo en código** (no remoto): regla OR y su orden; pata mínima de giro 8 m; velocidad implícita mínima 1.5 m/s; normalización 0/360; techo de accuracy 500 m y primer fix <150 m; staleness 120 s; re-adquisición 15 min; clamp de interval ≥3 s (recomendado ≥5); `minUpdateDistance=0` al FLP; heartbeat mínimo 60 s (clamp).
Justificación de los límites: Traccar fuerza heartbeat ≥60 s (https://github.com/traccar/traccar-client/blob/main/lib/settings_screen.dart) y transistorsoft declara que en Android "values below 60 are not supported" (https://docs.transistorsoft.com/cordova/AppConfig).

## 7. Heartbeat en quieto: qué recomiendan y por qué

- Evita dos patologías: (a) "solo puntos sueltos" al volver a moverse, porque el servidor no sabe si el equipo está quieto, offline o muerto; (b) que el dispositivo figure offline: Traccar marca offline tras **600 s sin datos** (https://www.traccar.org/configuration-file).
- Mínimo universal **60 s** (Traccar lo clampea en UI; transistorsoft: mínimo 60, deshabilitado por default). Traccar permite 0 = off y su comunidad recomienda **300–600 s** para ahorrar (https://www.traccar.org/forums/topic/traccar-client-941/page/4). OwnTracks publica un "ping" cada tanto en quieto y nada más: https://owntracks.org/booklet/features/location
- Doze no limita el heartbeat si el FGS vive; si el proceso está congelado, el heartbeat no se emite aunque se configure a 60 s → el heartbeat **no reemplaza** al watchdog de recuperación.
- **Recomendación**: 120 s (2 min) en jornada activa. Es 2× el mínimo, mantiene presencia viva para el panel, y con 480 pts/día el costo es despreciable. Rango remoto 60–600. No bajar de 60 (lo clampean las apps maduras; <60 no aporta y castiga batería/red). Descartar heartbeats idénticos consecutivos (mismo lat/lon, speed 0) salvo que cambie batería/estado, como hace transistorsoft por default (`allowIdenticalLocations=false`).

## Tabla final — parámetro → valor recomendado → evidencia → por qué

| Parámetro | Valor recomendado | Evidencia | Por qué |
|---|---|---|---|
| Interval movimiento | **10 s** (rango 5–30; 5 s en alerta) | Flota 5–30 s (GPSWOX/Trak-4); OwnTracks move 10 s; Traccar usuario 30 s; Android "segundos = foreground" | Ruta urbana completa a mitad de batería/datos que 5 s; 5 s es nivel navegación/robo |
| Interval quieto | **120 s** | Android background "few times/hour"; Traccar heartbeat 300–600; Life360 wake-up | Presencia viva sin gastar GPS; el acelerómetro cubre el re-arranque |
| Heartbeat quieto | **120 s** (min 60, máx 600) | Traccar clamp ≥60; transistorsoft min 60; Traccar offline a 600 s | Evita "puntos sueltos"/offline; <60 no aporta |
| Distance filter (código) | **25 m** (10–100; 15 m caminata; 40–50 m >54 km/h) | Traccar 75 default, usuarios 50/10; OwnTracks 100/500; ts 10 elástico | Fuerza muestra al avanzar sin depender solo del reloj |
| FLP minUpdateDistance | **0 m** (siempre) | Traccar viejo pasa 0f y filtra en código; docs FLP (hint) | Distancia >0 al FLP suprime fixes crudos → rompe el ángulo |
| Angle | **15°** (10–30), evaluado cada fix (~1 Hz) | Traccar angle (8° en regata); Geotab curva 28.5 m; nuestro FixFilter | Captura intersecciones sin jitter; el ángulo densifica curvas |
| Ángulo: filtros | Pata ≥8 m, implícita ≥1.5 m/s, normalizar 0/360, ignorar bearing inválido | SO getBearing/hasBearing; Doppler opcional; PositionProvider viejo sin normalizar | Evita falsos giros parado, al cruzar el norte o con chips sin Doppler |
| Accuracy | Moving **HIGH**; quieto **BALANCED** | ts: speed/heading solo con GPS; Traccar "highest" para angle; OwnTracks #1865 | HIGH da rumbo/velocidad; BALANCED en quieto ahorra |
| Stop timeout | **5 min** quieto → stationary | transistorsoft default 5; nuestro MotionSensor 5 min | Semáforos/paradas cortas no cortan la densidad |
| Regla de aceptación | OR: interval O distance O angle | Traccar: "we apply OR logic" | Ninguno solo alcanza: reloj cubre rectas, distancia avenidas, ángulo curvas |
| Doze / watchdog | FGS location + acelerómetro + alarma; 2 min si vivo, ≤9 min en Doze | Doze doc: allow-idle 1/9 min, WorkManager no corre; AlarmManager | Recupera freeze OEM; >9 min en Doze no es posible con allow-idle |
| Datos esperados | ~3 360 pts/día, ~1 MB/día/equipo (6: 3–12 MB/día) | GPSWOX (cientos de B/update) | Coste de red irrelevante; el límite es batería |
| Puntos/día a 5 s | ~6 240 pts/día | Cálculo 8 h/16 h + heartbeat 120 s | Casi 2× datos y wakeups sin mejorar la ruta cuando el ángulo ya la captura |

