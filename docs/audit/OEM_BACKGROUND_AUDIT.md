# AUDIT — OEM / BACKGROUND EXECUTION (Agente B, R3)

App: DMujeres Tracking Android `1.1.5` (targetSdk 35, minSdk 26, versionCode 115 — `mobile/app/build.gradle.kts:35-38`).
Alcance: auditoría de solo-lectura del mecanismo de ejecución en segundo plano + investigación documental (fuentes oficiales Android/Firebase y proyectos OSS reales). Sin cambios de código, sin commits.

---

## 1. Inventario de mecanismos presentes (file:line)

### 1.1 Declaraciones en manifest (`mobile/app/src/main/AndroidManifest.xml`)

| Mecanismo | Referencia | Nota |
|---|---|---|
| `ACCESS_FINE/COARSE/BACKGROUND_LOCATION` | :11-13 | El background location es el requisito de la excepción "while-in-use" para FGS location iniciado en segundo plano (docs Android 14). |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION` | :14-15 | Requerido por Android 14 para el tipo `location`. |
| `RECEIVE_BOOT_COMPLETED` | :17 | Arranque tras boot/update. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | :18 | Exención Doze/bucket + exención de arranque FGS en bg (docs: "The user turns off battery optimizations"). |
| `USE_FULL_SCREEN_INTENT` | :19 | Wake de pantalla en alertas. |
| FGS `TrackingService` `foregroundServiceType="location"`, `stopWithTask=false` | :90-93 | Correcto para Android 14/15: `location` es de los tipos permitidos desde `BOOT_COMPLETED` en target 35 (compat framework `FGS_BOOT_COMPLETED_RESTRICTIONS`). |
| `SystemForegroundService` (WorkManager) merge con tipo `location` | :98-100 | Necesario para el one-shot expedited del worker (API 31+). |
| Receiver `BootReceiver` (BOOT_COMPLETED, MY_PACKAGE_REPLACED, USER_UNLOCKED, QUICKBOOT_POWERON) | :103-115 | Cubre reboot + OTA + unlock. |
| Receiver `SessionKeeperReceiver` (exported=false) | :118-119 | Canal de la alarma guardiana. |
| `FcmRecoveryMessagingService` (MESSAGING_EVENT) | :61-66 | Probe de recovery por FCM. |
| **NO declara `WAKE_LOCK`** | — | **Gap 1**: ni el manifest ni el código usan wake locks (verificado por grep; Traccar SDK sí mantiene `PARTIAL_WAKE_LOCK` durante tracking). |

### 1.2 FGS y ciclo de vida (`tracking/TrackingService.kt`)

- `startForeground` como PRIMER acto con decisión pura anti `ForegroundServiceDidNotStartInTimeException` / `ForegroundServiceStartNotAllowedException`: `ForegroundClaimPolicy.shouldClaimForeground` (TrackingService.kt:1379-1382, aplicado en :420-450). Iniciado pesado (DB/MQTT/FLP) diferido a `initCore()` tras el reclamo (:151-150, :376-393) — evita quemar el timeout de 5 s del sistema (crash real en Sentry ×2, documentado en el KDoc :146-149).
- `START_STICKY` para START (null-intent re-entregado) y `START_NOT_STICKY` para STOP (:476, :501).
- Guard anti-OEM por pantalla: receiver dinámico SCREEN_ON/SCREEN_OFF que re-afirma el foreground (`notifier.reassertForeground("screen_on")`) porque los OEM des-promueven el FGS con pantalla apagada (:321-362).
- Re-afirmación barata por cada fix aceptado (:921-924, `reassertForeground("fix_aceptado")` en TrackingNotificationController.kt:102).
- `onTaskRemoved` con doble red (reinicio directo + worker expedited) para el swipe-away de recientes, documentado como patrón MIUI (:1333-1344).
- Detección honesta de muertes anómalas: `detectAbnormalRestarts` cuenta crashes/stuck-stops y reporta `crash_boot` (:407-418).

### 1.3 Alarmas (`recovery/SessionKeeper.kt`)

- `AlarmManager.ELAPSED_REALTIME_WAKEUP` + **`setAndAllowWhileIdle` (inexacta)**: SessionKeeper.kt:41. Cadena auto-re-armada mientras `trackingEnabled` (:67-69).
- Periodo dual por `ForegroundGuardPolicy.keeperPeriodMs`: **2 min en marcha / 15 min en quietud** (ForegroundGuardPolicy.kt:47; aplicado en TrackingService.kt:512-516 y re-agendado al cambiar de modo adaptativo vía `onAdaptiveModeChanged`, TrackingService.kt:210-216).
- Verificación honesta del intento anterior (`RecoveryJournal.verdictForPreviousAttempt`, SessionKeeper.kt:79-104) y escalada a `Notifications.resumeRequired` cuando el sistema rechazó el start (:94-102, :112-117) — nivel 5 USER ACTION según FASE 6 (Notifications.kt:230).
- **No usa `setExactAndAllowWhileIdle` ni `setAlarmClock`; no chequea `canScheduleExactAlarms`** (grep sin resultados). Gap 2.

### 1.4 WorkManager (`recovery/TrackingRecoveryWorker.kt`, `DmujeresApp.kt`)

- Periódico 15 min **sin `setRequiredNetworkType`** (deliberado: drenar outbox offline y re-activar GPS sin red; `RecoverySchedule.requiresNetwork()==false`, TrackingRecoveryWorker.kt:244) + backoff exponencial 10 min (:180-188).
- One-shot **expedited** con `RUN_AS_NON_EXPEDITED_WORK_REQUEST` (degradación segura, :194-201) y `getForegroundInfo` con tipo `location` en API 29+ (:108-124).
- Encolado en: arranque de app (DmujeresApp.kt:115-124, periódico UPDATE + one-shot startup), boot (`enqueueImmediate`, BootReceiver.kt:127), reconexión de red (DmujeresApp.kt:91-102), `onTaskRemoved` (TrackingService.kt:1342).
- Políticas puras JVM: `StuckStopPolicy.decideRecovery/decideBoot` (START_PREFER_RECOVERY ante crash a mitad de stop).

### 1.5 FCM recovery (`recovery/FcmRecovery*.kt` + servidor)

- Servidor envía **DATA message, prioridad HIGH, TTL corto** (`server/src/main/java/org/traccar/mobile/FcmSender.java:109-127`, `AndroidConfig.Priority.HIGH`); backend con ADC (doc `docs/FCM_RECOVERY.md`).
- Cliente compara `originalPriority` vs `priority` entregada y clasifica degradación HIGH→NORMAL (`FcmRecoveryPolicy.classifyDelivery`, FcmRecoveryPolicy.kt:88-96; aplicado en FcmRecoveryMessagingService.kt:41-74). Coincide con la recomendación oficial de revisar `getPriority()` antes de iniciar FGS.
- Validación de payload, dedupe por attemptId, anti-replay de 24 h (FcmRecoveryPolicy.kt:41-70).
- ACK por etapas vía canal HTTP (`RECOVERY_RECEIVED→STARTED→FGS_ACTIVE→TRACKING_ACTIVE→GPS_CONFIRMED`); el SUCCESS lo decide el servidor con evidencia (FcmRecoveryMessagingService.kt:1-34, FcmAck FcmTokenRegistrar.kt:19-59). Verificación a 6 s + re-check GPS único a 30 s (:118-169).
- Bloqueos capturados con razón exacta: `BLOCKED_BACKGROUND_RESTRICTION` (ForegroundServiceStartNotAllowedException), `BLOCKED_PERMISSION`, `BLOCKED_SERVICE_START` (:96-108).
- Token registrado con metadata de dispositivo y prefijo hash, nunca en logs (FcmTokenRegistrar.kt:94-146).

### 1.6 Ubicación (`location/LocationEngine.kt`)

- FLP con `PRIORITY_HIGH_ACCURACY` + minUpdateInterval 0.5×/minUpdateDistance adaptativa 0 m/15 m (LocationEngine.kt:304-351).
- Escalera anti-hambruna en el tick de 30 s del watchdog: re-registro cada 2 min, re-creación del cliente FLP si no hay callbacks >10 min (máx 1/15 min), **fallback directo a GPS_PROVIDER** con listener propio y looper main (:182-235, :398-430), **polling activo `getCurrentLocation`** con backoff 90 s→3 min→5 min (:441-506).
- GNSS real (satélites usados/total) para calidad y alerta "sin GPS" con throttle (:353-396).
- Nota: **sin wake lock**; en FGS location el CPU no duerme mientras el FLP entrega, pero en OEM freezer el proceso entero se congela (no es un problema resoluble con wake lock).

### 1.7 Perfil OEM / readiness (`oem/`, `readiness/`)

- Detección **por capacidad, no por listas**: `DeviceCapabilityProfile.read` combina hardware (`DeviceCaps`), `PowerManager.isIgnoringBatteryOptimizations`, `ActivityManager.isBackgroundRestricted` (API 28+), `UsageStatsManager.appStandbyBucket` (DeviceCapabilityProfile.kt:120-157). Estados: SUPPORTED / SUPPORTED_WITH_GUIDANCE / DEGRADED / UNSUPPORTED (:46-51).
- `KNOWN_FREEZER_VENDORS = {zte}` con evidencia de campo (DeviceCapabilityProfile.kt:77); `hardRestricted()` = backgroundRestricted ∨ bucket RESTRICTED ∨ freezer conocido (:59-60).
- Guías por fabricante con deep links: Xiaomi (autostart MIUI), Samsung (deeplink oficial "Never sleeping apps" de Device Care), Honor (systemmanager Huawei autostart), Infinix/Tecno (Phone Master), ZTE (AppSmartOptimize + fallback Ajustes, pantalla protegida por permiso de sistema) — VendorSettings.kt:47-130.
- Gate de arranque: `shouldBlockStart` (background-restricted o bucket 45 ⇒ no iniciar jornada) y `shouldWarnStart` (bucket RARE ⇒ iniciar con aviso) — ForegroundGuardPolicy.kt:29-39.
- Onboarding: diálogo de ubicación en fondo desviado a Ajustes de app en Xiaomi/Infinix/Tecno (el diálogo del sistema falla en silencio; VendorSettings.kt:38-45, OnboardingActivity.kt:248-273); exención de batería vía `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (OnboardingActivity.kt:286-293); paso OEM con `vendorGuideDone` persistido (:167, :200).
- Readiness con **prueba de continuidad** (fixes aceptados con pantalla apagada, `ContinuityTracker.onFixAccepted` en TrackingService.kt:925-927; ventana screen-off/on en :334-342) y prueba de recovery observacional: DeviceReadinessPolicy.kt:206-243 (OEM `NOT_VERIFIABLE` nunca es PASS) — continuidad hoy informativa, no bloquea el gate (:291-299).
- Watchdog de 30 s con 7 dominios (salud, sensores, presencia, enlace en 4 dominios vía `LinkState`, reconexión MQTT con `ReconnectGate`, alertas operativas, goteo HTTP) — TrackingWatchdog.kt:86-346.

### 1.8 Diagnóstico / reporte

- Telemetría por posición estructurada (`POSITION_ACCEPTED+STORED` con provider/quality/seq/confidence, TrackingService.kt:1164-1172) y rechazos tipificados (:864-913).
- Snapshots de salud en Room + subida async (`TrackingHealthMonitor`, `HealthUploader`), métricas 24 h (crashes/stuck-stops/ANR/speed-stuck), Sentry con ANR counting y breadcrumbs de device (screen_on/off) — DmujeresApp.kt:38-71, TrackingWatchdog.kt:103-142.
- `DiagnosticsCollector/Reporter` con reportes forzados en hitos (`journey_start`, `journey_stop`, `crash_boot`).

---

## 2. Tabla OEM — capacidades / limitaciones documentadas

Fuentes: docs oficiales Android (developer.android.com), Firebase (firebase.google.com), soporte oficial ZTE/Honor/Samsung, dontkillmyapp.com, y comportamiento observado en proyectos OSS (Traccar Client/SDK, GPSLogger).

| Dominio | Qué permite Android (oficial) | Qué hace el OEM / límite real | Diagnóstico posible | Recovery posible | Fuente |
|---|---|---|---|---|---|
| **AOSP / Google (Doze)** | FGS location + `ACCESS_BACKGROUND_LOCATION` funciona con pantalla apagada; redes y wake locks del FGS no se cortan | En deep Doze el sistema ignora wake locks, difiere alarmas estándar y **`setAndAllowWhileIdle` está limitada a ~1 disparo/9 min (docs AlarmManager) o 1/15 min en deep idle (AOSP)** | Sí: bucket + `isBackgroundRestricted` + health snapshots | FCM HIGH temporalmente exime (red + wake lock parcial); alarmas while-idle siguen entrando con diferimiento | developer.android.com/training/monitoring-device-state/doze-standby; source.android.com/docs/core/power/platform_mgmt; AlarmManager reference |
| **App Standby Buckets (API 30+)** | Buckets reducen jobs/alarmas/FCM; **una app con `ACCESS_BACKGROUND_LOCATION` concedida está EXENTA del bucket RESTRICTED** en AOSP (lista de excepciones del bucket 45) | "Every manufacturer can set their own criteria for bucketing" (docs); ROMs OEM pueden ignorar la exención | Sí: `getAppStandbyBucket()` ya se lee (DeviceReadinessChecker.kt:49-57) | Usuario: "Sin restricciones"/unrestricted en Ajustes (docs: user override supersede bucket) | developer.android.com/topic/performance/appstandby; /topic/performance/power/power-details |
| **Android 12+ arranque FGS en bg** | Solo con exenciones: FCM HIGH (breve), alarma exacta, interacción UI (notificación/widget), BOOT_COMPLETED, batería sin optimizar, etc. | En Motorola/Android 15 el foro Traccar documenta `mAllowStartForeground false` pese a todos los settings visibles (appops ocultos) | Sí: la app ya distingue SecurityException/ForegroundServiceStartNotAllowedException | Llamar al usuario (resumeRequired, nivel 5); en widgets hay exención de interacción | developer.android.com/develop/background-work/services/fgs/restrictions-bg-start; foro traccar.org (Motorola g06 Android 15) |
| **Android 14 (target 34) FGS types + while-in-use** | FGS `location` exige `FOREGROUND_SERVICE_LOCATION` + permisos de ubicación; iniciado en bg exige `ACCESS_BACKGROUND_LOCATION` para acceso todo-el-tiempo | Cumplido en manifest (:13-15, :93). Mientras haya ACCESS_BACKGROUND_LOCATION concedida, no hay bloqueo AOSP | Ya verificado en boot (BootReceiver.kt:140-151) | N/A — ya implementado | developer.android.com/about/versions/14/changes/fgs-types-required; fgs/restrictions-bg-start (sección while-in-use) |
| **Android 15 (target 35)** | BOOT_COMPLETED solo puede lanzar FGS de tipos location/connectedDevice/remoteMessaging/health/systemExempted/specialUse → **el tipo `location` sigue permitido**; dataSync tiene timeout 6 h (no aplica: esta app no usa dataSync) | Sin impacto adicional identificado para este diseño | N/A | N/A | developer.android.com/about/versions/15/behavior-changes-15; compat-framework-changes (`FGS_BOOT_COMPLETED_RESTRICTIONS`) |
| **FCM** | HIGH intenta entrega inmediata incluso en Doze y da exención breve para FGS; **el sistema puede degradar HIGH→NORMAL si el patrón de mensajes no genera notificaciones visibles** (7 días de comportamiento) | En equipos con GMS congelado (ZTE freezer) la entrega misma se difiere; en bucket RARE el acceso a red está limitado | Sí: la app ya clasifica original vs delivered (FcmRecoveryPolicy) — y **podría usar el Aggregate Delivery Data API del server para % de degradación** | Reintentos server-side + variar payload; no hay garantía contractual | firebase.google.com/docs/cloud-messaging/android/message-priority; /throttling-and-quotas (240 msg/min/dipositivo, colapsables 20+refill) |
| **ZTE (MyOS / Z2450)** | AOSP base | Política de ahorro por defecto "automáticamente limpia apps en bg" (soporte oficial ZTE); control de IA / AppSmartOptimize congelación confirmada en campo (KNOWN_FREEZER_VENDORS). Pantalla "Gestión inteligente" protegida con permiso de sistema: ningún deeplink puede abrirla | Sí: freezer conocido + evidence qa-f0/frozen-list | Parcial: guía de 2 toques desde Ajustes (VendorSettings.kt:91-109) + SessionKeeper/FCM; **no recuperable si el proceso está congelado** | support.ztedevices.com (Why do background programs always close); código local |
| **Infinix/Tecno (HiOS/XOS, X6876)** | AOSP base | "Battery Lab → Power Saving Management" congela timers/servicios/FGS **incluidos foreground services** hasta reabrir la app (dontkillmyapp #15); Phone Master auto-start necesario | Sí (bucket/battery) | Guía mapeada (VendorSettings.kt:81-90) + desactivar ahorro nocturno; suspensión nocturna es configuración del usuario | dontkillmyapp.com/tecno; github urbandroid-team/dont-kill-my-app #213/#474 |
| **Honor (LGN-LX3, Android 15)** | AOSP base | Phone Manager / Startup manager gestiona autoinicio; soporte oficial recomienda "Gestionar manualmente + Ejecutar en segundo plano" y batería sin optimizar; conectividad bg (MQTT persistente) se corta en optimización | Sí | Guía mapeada con deeplink a com.huawei.systemmanager (VendorSettings.kt:71-80); MQTT re-connect gate ya existe | honor.com/za/support (Background apps are forced to close) |
| **Samsung** | Deeplink oficial Device Care "Never sleeping apps" | Suspende apps no usadas salvo lista blanca | Sí | Deeplink oficial documentado por Samsung ya integrado (VendorSettings.kt:119-139) | developer.samsung.com/mobile/app-management.html; código local |
| **Traccar Client/SDK (OSS de referencia)** | — | FGS + START_STICKY + **`PARTIAL_WAKE_LOCK` mientras tracking** + **`AlarmManager.setAndAllowWhileIdle` heartbeat** + BootReceiver con goAsync/coroutine | — | En Android 15 Motorola recurre a appops por ADB (no distribuye eso a usuarios); heartbeat 60 s "degrada" a 2-7 min en Doze | github.com/traccar/traccar-client-sdk (DeepWiki: WakeLockHolder, AlarmHeartbeatTrigger); foro traccar.org |
| **GPSLogger (OSS de referencia)** | — | FGS tipo location + **`setExactAndAllowWhileIdle` con `AlarmManagerCompat.canScheduleExactAlarms()`** + significant-motion para pausar + aceptación explícita: "Doze dará puntos intermitentes de todos modos"; recomienda dontkillmyapp | — | Su respuesta oficial a Huawei/doze es guía al usuario, no código | github.com/mendhak/gpslogger (GpsLoggingService.java, issues #435/#916/#1080, gpslogger.app FAQ) |
| **OwnTracks / Freemap / OpenTracks** | — | Mismo patrón: FGS + guía de usuario + documentación honesta de que doze "es lo que hay" (OpenTracks #967, GPSLogger #916 citan dontkillmyapp) | — | — | issues OSS citados |

**Lectura cruzada con la evidencia de hoy:**

- **ZTE sin ruta 10 h + FCM 40× TIMEOUT**: consistente con freezer: proceso congelado ⇒ FCM puede no entregarse o entregarse sin que el proceso pueda ejecutar `onMessageReceived`/ACK ⇒ server ve timeout. El keeper (inexacta) también queda diferido/frozen. **Nada del stack actual puede revivir un proceso congelado por el OEM sin interacción del usuario.** El ciclo correcto es DETECT→GUIDE (ya parcialmente hecho) + REPORT.
- **Infinix suspensión nocturna (FGS muerto en doze)**: coincide con dontkillmyapp/tecno: "Power Saving Management" congela TODO con pantalla apagada. La guía existe; falta verificación post-guía (hoy `vendorGuideDone` es self-report).
- **Honor MQTT intermitente + crashes 1→4**: conexión MQTT persistente se corta por optimización de batería de Honor; el ReconnectGate/watchdog lo re-ataca, y los crashes anómalos se cuentan (`detectAbnormalRestarts`) — patrón correcto; falta correlacionar el crash con el evento OEM (appops/bucket) en el reporte.

---

## 3. GAP ANALYSIS priorizado (por dominio, con DETECT→DIAGNOSE→RECOVER→GUIDE→REPORT)

### P0 — Alto impacto, bajo riesgo

1. **Wake lock de tracking (GAP 1).** No hay `WAKE_LOCK` en manifest ni `PARTIAL_WAKE_LOCK` en el código. Traccar SDK mantiene el lock mientras `enabled && !paused` (WakeLockHolder). En AOSP el FGS + FLP streaming suele bastar, pero en OEMs que "ignoran wake locks" en doze el lock es la señal de intensidad que algunos gestores respetan.
   - DETECT: flag `wakeLockHeld` en telemetría. RECOVER: adquirir partial wake lock con timeout + re-adquisición por watchdog; liberar en `stopTracking`. GUIA: n/a. REPORT: contador de adquisiciones fallidas.
   - Fase: R4 (código pequeño, costo batería medible vía telemetría ya existente).

2. **Alarma exacta opcional para el keeper (GAP 2).** Hoy solo `setAndAllowWhileIdle` (SessionKeeper.kt:41): en deep doze se difiere a ≥15 min (AOSP) y en buckets altos a "7/hora" o "1/día" (power-details). El keeper "2 min en marcha" **nunca cumplirá 2 min en doze** — es honesto saberlo, y en marcha el OEM killing es justo el caso crítico.
   - RECOVER: `setExactAndAllowWhileIdle` cuando `AlarmManager.canScheduleExactAlarms()` (API 31+), con flujo `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` opcional en onboarding para equipos con guía OEM. GPSLogger ya hace exactamente este patrón (`AlarmManagerCompat.setExactAndAllowWhileIdle` + `canScheduleExactAlarms`). Alternativa más potente (y sin permiso): `setAlarmClock()` — el sistema sale de doze antes del disparo (docs Doze) — evaluar costo de UI (ícono de alarma).
   - REPORT: log de "alarma programada exacta/inexacta" + drift medido entre intento y disparo.
   - Fase: R4/R5.

3. **Correlación FCM server-side (usar datos que ya existen).** El cliente ya reporta `originalPriority` vs `delivered`; el server tiene 40 timeouts del ZTE. Falta en server: reporte de % de degradación por dispositivo (Firebase Aggregate Delivery Data API, docs oficiales) y clasificación del timeout: `no-delivery` vs `delivered-sin-ACK` (se distingue con delivery receipts del FCM admin SDK).
   - DETECT/DIAGNOSE/REPORT server-side; nada en cliente.
   - Fase: R4 (server, ADC ya integrado).

### P1 — Importante

4. **Keepalive WorkManager "heartbeat" independiente del periódico actual.** El periódico de 15 min es correcto como red de seguridad, pero en bucket RARE el job corre ≤10 min/24 h (power-details): no hay campana de supervivencia. Añadir un **`PeriodicWorkRequest` de 15 min con `setConstraints(RequiresBatteryNotLow)` separado solo para ACK/health ping** (barato) o mejor: un one-shot re-encadenado estilo GPSLogger. Nota honesta: en bucket RESTRICTED todos los jobs quedan agrupados 1×/día — este gap **no es salvable por código** sin guía (ver §4).
   - Fase: R5 (evaluar costo batería primero).

5. **Verificación post-guía OEM (cerrar el hueco CONFIGURED_UNVERIFIABLE).** `vendorGuideDone` es self-report (OnboardingActivity.kt:167/200); OemProtection documenta que no se puede verificar programáticamente (OemProtection.kt:1-16). Mejora posible: prueba conductual corta guiada (la continuidad ya existe como herramienta: `ContinuityTracker` con ventana screen-off/on, TrackingService.kt:334-342) — promoting de "prueba de 5 min con pantalla apagada" como paso final del onboarding para equipos con guía. La política ya define `continuityRequired` (DeviceReadinessPolicy.kt:113-114, hoy deprecated/informativo).
   - Fase: R5/R6 (producto).

6. **Guía para Motorola y demás "sin guía mapeada" → UNSUPPORTED.** `OemProtection.KNOWN_VENDORS` tiene 6 marcas; cualquier otro OEM agresivo (Motorola, Vivo/OPPO/realme) cae en UNSUPPORTED (OemProtection.kt:92-97) y ForegroundGuardPolicy igual lo bloquea si detecta restricción dura. Añadir guía genérica dontkillmyapp (link + pasos Ajustes) en vez de UNSUPPORTED seco.
   - Fase: R5 (solo datos/strings).

7. **Detección de "proceso congelado" más fina.** Hoy la congelación se infiere (sin health snapshots + sin fixes + sin FGS). Añadir a `SilenceDiagnosis` la distinción: (a) FGS muerto por sistema (verificar `ActivityManager.getRunningServices` deprecado → usar heurística health + clock-drift), (b) GPS sin fix pero proceso vivo, (c) red caída. Parcialmente existe (LinkState 4 dominios); documentar y exponer en el reporte diario.
   - Fase: R5.

### P2 — Deseable

8. **Widget como exención de interacción.** Ya hay widget (JourneyWidget) y la interacción con widget es exención válida de arranque FGS en bg (restrictions-bg-start). Documentar en runbook: si el keeper falla, "tocar el widget" es la vía de exención más rápida sin abrir la app. Costo ~0.
9. **`shortService`** (API 34) para tareas cortas post-stop (drenaje final) en vez de extender FGS. Evaluar: el drenaje actual ya corre en el propio FGS y en el worker expedited.
10. **Prueba automatizada de Doze en CI** (adb: `dumpsys deviceidle force-idle` + `am set-standby-bucket`) para el doc de QA (QA_MATRIX): reproducir los 3 escenarios de evidencia.

---

## 4. Qué NO es técnicamente posible (declaración honesta)

1. **Revivir un proceso congelado por el OEM sin interacción del usuario.** Si ZTE AppSmartOptimize / HiOS Power Saving congela el proceso, ni FCM, ni alarmas (exactas o no), ni WorkManager se ejecutan en ese proceso. Las únicas salidas son: (a) el usuario configura la guía (2 toques), o (b) el usuario interactúa con la app/widget/notificación. Cualquier truco de evasión (servicio separado, multiproceso, `SYSTEM_ALERT_WINDOW` oculto, restart en loop) viola políticas y/o deja de funcionar por versión — descartado por diseño.
2. **Garantizar cadencia de alarmas en deep doze/bucket.** `setAndAllowWhileIdle`: 1/9 min (docs) a 1/15 min (deep idle); buckets RARE: 7/hora; RESTRICTED: 1/día. El keeper "2 min en marcha" es aspiracional bajo doze; exact alarms necesitan `SCHEDULE_EXACT_ALARM` (revocable por el usuario) y siguen limitadas en buckets. Incluso la exención de batería no desactiva el freezer del OEM (solo el AOSP).
3. **Garantizar entrega FCM HIGH.** FCM degrada HIGH→NORMAL si el patrón de mensajes no produce notificaciones visibles (7 días de ventana) y en doze/bucket RARE la entrega se difiere; no hay SLA. El probe ya muestra su clasificación — el server no puede forzar entrega.
4. **Forzar el proveedor GNSS dentro del FLP.** No existe API pública; la app ya usa el fallback correcto (GPS_PROVIDER directo + `getCurrentLocation`), igual que Traccar Client (`ac6d677` de la issue #367).
5. **Verificar programáticamente los ajustes del OEM** (autostart, freezer, never-sleeping): pantallas protegidas por permisos de sistema; ni Samsung/ZTE exponen estado legible. Solo verificable por prueba conductual (continuidad).
6. **Fijar el bucket en ACTIVE.** Los docs lo prohíben explícitamente ("Don't try to manipulate the system into putting your app into a certain bucket"); cada OEM escribe su propio bucketing.
7. **Drenar outbox sin red o sin proceso vivo.** El buffer Room retiene (retención 100k/7d) y el drenaje ocurre en el primer momento de ejecución; si la jornada terminó 10 h antes sin drenar, la ruta no se puede reconstruir retroactivamente — lo que no se capturó no existe.

---

## Resumen final (≤15 líneas)

El mecanismo de fondo de la app está por encima del estándar OSS auditado: FGS `location` correcto para 14/15 (manifest:90-93), startForeground inmediato anti-timeout (TrackingService:1379), triple red de recovery (SessionKeeper inexacta 2/15 min, WorkManager periódico+expedited, FCM HIGH con ACK por etapas y clasificación de degradación), guías OEM con deep links verificados y readiness con prueba de continuidad. Los 3 incidentes de hoy son consistentes con límites documentados, no con bugs del diseño: ZTE freezer congela el proceso (nada revive un proceso congelado: solo guía+usuario), HiOS de Infinix congela FGS en doze nocturno (configuración del usuario, guía ya mapeada), y Honor corta la conectividad MQTT persistente (reconexión ya cubierta). Gaps concretos: (P0) wake lock tipo Traccar, alarma exacta condicional (`canScheduleExactAlarms`) o `setAlarmClock` para el keeper en marcha, y correlación de degradación FCM en server (Aggregate Delivery API); (P1) keepalive WorkManager secundario, verificación conductual post-guía OEM, guía genérica para Motorola/OPPO y diagnóstico fino de congelación. Debe documentarse en onboarding que el keeper de 2 min no se cumple en deep doze y que FCM no tiene garantía de entrega. No es posible: revivir procesos congelados, garantizar cadencia de alarmas en buckets altos, ni verificar ajustes OEM programáticamente — en esos casos la estrategia correcta es detectar, guiar y reportar, que es lo que el código ya hace en su mayoría.

— Agente B (solo lectura; ningún archivo de código modificado)
