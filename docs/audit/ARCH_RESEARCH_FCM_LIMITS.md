# ARCH_RESEARCH_FCM_LIMITS.md — Límites y capacidades reales de FCM como canal de control

> Investigación de solo lectura (2026-09-19). Contexto: `com.dmujeres.traccar` + servidor propio;
> FCM high-priority como "despertador" tras silencio anormal (`tc_recovery_event`:
> `RECOVERY_SENT/RECEIVED/SUCCESS`; ~127 probes y 3 éxitos/día; caso real: reconexión 12 s post-probe).
> Objetivo: exprimir FCM legítimamente y medir la recuperación sin autoengaño.
> Clases: **VERIFIED** (doc oficial/OSS con componente exacto) | **LIKELY** (1 fuente o inferencia documentada) | **UNVERIFIED**.

## 1. Prioridad high: qué garantiza y qué la degrada

- Garantía oficial: "FCM attempts to deliver high priority messages immediately even if the device is in Doze mode" — <https://firebase.google.com/docs/cloud-messaging/android/message-priority> (VERIFIED).
- En Doze/App Standby el sistema entrega el mensaje y da "temporary access to network services and partial wakelocks", luego vuelve a idle — <https://developer.android.com/training/monitoring-device-state/doze-standby> (VERIFIED).
- Presupuesto de ejecución: pocos segundos en `onMessageReceived` (algo más en high); si se necesita más, `WorkManager` expedited (high) o regular (normal) — <https://firebase.google.com/docs/cloud-messaging/android/message-priority> (VERIFIED).
- **Degradación (Android 13+)**: el sistema degrada high→normal si detecta "an app consistently sending high-priority messages that don't result in a notification"; además, los App Standby Buckets ya **no** determinan cuántos high-priority FCM puede usar una app — <https://developer.android.com/about/versions/13/behavior-changes-all> (VERIFIED).
- **Ventana de 7 días**: "FCM uses 7 days of message behavior when determining whether to deprioritize or proxy messages; it makes this determination independently for every instance" — <https://firebase.google.com/docs/cloud-messaging/android-message-priority> (VERIFIED).
- **Permiso de notificaciones**: "If the user has disabled the notification permission for your app, none of your notifications will be posted, as a result, your messages will be deprioritized" — misma URL (VERIFIED).
- **Proxy/delegación**: notificaciones high que cumplen criterios se muestran vía Google Play services **sin arrancar la app**; opt-out con `proxy: DENY` — misma URL (VERIFIED).
- **Cuotas que degradan/retrasan**: 240 msg/min y 5.000 msg/h por dispositivo; colapsables: ráfaga de 20 + recarga de 1 cada 3 min — <https://firebase.google.com/docs/cloud-messaging/throttling-and-quotas> (VERIFIED).
- **Cuota de proyecto**: 600k msg/min por defecto; exceso ⇒ HTTP 429 `QUOTA_EXCEEDED`; se puede pedir +25% — misma URL (VERIFIED).
- Confirmación comunitaria de la degradación ("priority may be reduced to normal") en firebase-talk: <https://groups.google.com/g/firebase-talk/c/LnjLk0B_AwI> (LIKELY, corrobora doc).

## 2. Doze / App Standby / freezer / restricted / battery saver / force-stop

| Estado | Entrega HP FCM | Red de la app tras el wake | Evidencia |
|---|---|---|---|
| Doze (pantalla off, sin cargar, estacionario) | Sí, inmediata | Temporal (partial wakelock + red) | <https://developer.android.com/training/monitoring-device-state/doze-standby> |
| Cached app freezer (Android 11+) | **LIKELY**: el proceso se descongela con lifecycle events/intents; GMS entrega FCM fuera del proceso | Sí, tras unfreeze | <https://source.android.com/docs/core/perf/cached-apps-freezer> |
| Restricted bucket (App Standby) | Android 13+ los buckets ya no limitan HP FCM; restricted bloquea red de fondo | Bloqueada salvo temp-allowlist de FCM | <https://developer.android.com/topic/performance/power/power-details> y <https://android.googlesource.com/platform/frameworks/base/+/main/services/core/java/com/android/server/content/SyncManager.md> |
| Hibernación (Android 12+, app sin uso) | **No**: "can't receive push notifications, including high-priority messages that are sent through Firebase Cloud Messaging" | — | <https://developer.android.com/topic/performance/app-hibernation> |
| Battery Saver | Entrega posible, pero "if the device is dozing or in battery saver, promoting to the ACTIVE bucket will still not give the app network access" | Bloqueada salvo allowlist | SyncManager.md (arriba) y <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/network-policy-restrictions.md> |
| Data Saver | No degrada FCM (GMS exento); bloquea datos de fondo de la app en redes metered | Bloqueada salvo allowlist | <https://developer.android.com/develop/connectivity/network-ops/data-saver> |
| Force-stop / stopped state | **No entrega**; el sistema añade `FLAG_EXCLUDE_STOPPED_PACKAGES` a los broadcasts | — | <https://developer.android.com/about/versions/android-3.1#launchcontrols> + <http://help.rongcloud.io/t/can-t-receive-fcm-push-notifications-after-force-stopping-killing-the-app/176> |

- **Force-stop (respuesta clara)**: FCM no arranca apps detenidas; no hay entrega hasta que el usuario abre la app. Hilo oficial GCM/FCM: "If you press 'force stop' in the application settings then FCM will not deliver new messages to app" — <https://groups.google.com/g/android-gcm/c/1J6XNVOzCcE>. Sin workaround legítimo: `FLAG_INCLUDE_STOPPED_PACKAGES` solo aplica a broadcasts emitidos por terceros, no a la entrega de GMS — <https://developer.android.com/about/versions/android-3.1#launchcontrols>. OEMs pueden emular force-stop al deslizar la app de recientes (OxygenOS; también Huawei/Xiaomi task killers) — <https://github.com/firebase/quickstart-android/issues/378> y <https://gist.github.com/gdeglin/98aeda28035b45cef04bb6c2cb41a4aa>. Ver también <https://stackoverflow.com/questions/56904604/fcm-push-notification-works-after-app-force-stop>.

## 3. Data message vs notification message

- Dos tipos: notification (la muestra el SDK) y data (la procesa la app); payload máximo 4.096 bytes — <https://firebase.google.com/docs/cloud-messaging/customize-messages/set-message-type> (VERIFIED).
- Defaults: notification = high; data = normal — <https://firebase.google.com/docs/reference/admin/node/firebase-admin.messaging.messagingoptions> (VERIFIED).
- En background, notification va a la bandeja del sistema y `onMessageReceived` no se llama hasta el tap; data-only siempre llama a `onMessageReceived` (foreground y background) — <https://firebase.google.com/docs/cloud-messaging/receive-messages> y set-message-type (VERIFIED).
- Android 13+: sin `POST_NOTIFICATIONS` el SDK no puede mostrar la notificación y el sistema no pide el permiso por sí solo — <https://firebase.google.com/docs/cloud-messaging/android/get-started> (VERIFIED). Data-only no se ve afectado por el permiso, pero sin notificación visible entra el riesgo de degradación high→normal (§1).
- Notificaciones "proxied" pueden mostrarse sin arrancar la app ⇒ **inútiles como canal de control**; los probes deben ser data-only — <https://firebase.google.com/docs/cloud-messaging/android/message-priority> (VERIFIED).
- `collapse_key`: solo se entrega el último mensaje pendiente con la misma clave; máximo 4 claves activas a la vez — <https://firebase.google.com/docs/cloud-messaging/customize-messages/setting-message-lifespan> y <https://firebase.google.com/docs/reference/admin/python/firebase_admin.messaging> (VERIFIED). No usar collapse en probes (perdería intentos); usar clave única o ninguna.
- TTL: 0–2.419.200 s (28 días); default 4 semanas; si el dispositivo está en Doze, normal se guarda hasta salir — lifespan (arriba) (VERIFIED).

## 4. Token lifecycle (FID vs legacy)

- FCM está migrando de registration tokens a **Firebase Installation IDs (FID)**; el token legacy = FID + autorización; ambos co-soportados — <https://firebase.google.com/docs/cloud-messaging/manage-tokens> (VERIFIED).
- El FID rota/se borra al desinstalar/reinstalar, restaurar en otro dispositivo, limpiar datos/caché, y por inactividad (270 días) — <https://firebase.google.com/docs/projects/manage-installations> (VERIFIED). La doc legacy lista las mismas causas — <https://firebase.google.com/docs/reference/android/com/google/firebase/iid/FirebaseInstanceId> (VERIFIED).
- Token stale tras ~1 mes sin conexión; **expira** a los 270 días de inactividad y FCM lo rechaza; errores `UNREGISTERED` (404) e `INVALID_ARGUMENT` (400) ⇒ borrar el registro del servidor — manage-tokens (arriba) (VERIFIED).
- Buenas prácticas: `onRegistered()` (auto-init) o `register()` + `onNewToken()`; subir FID/token con **timestamp** y refrescar ≤1 vez/semana; no programar jobs periódicos con FID APIs — manage-tokens (arriba) (VERIFIED).
- La migración InstanceID→FID puede fallar silenciosamente (37% en un reporte de producción) ⇒ mantener fallback por staleness — <https://github.com/firebase/firebase-android-sdk/issues/8053> (LIKELY).

## 5. Optimizaciones

- **collapse keys**: para sync/no spamear; con probes de recuperación no colapsar (cada intento debe auditarse).
- **TTL**: probes corto (p. ej. 300 s) para no despertar tarde por un intento viejo; Home Assistant usa `ttl: 0` para críticas — <https://companion.home-assistant.io/docs/notifications/critical-notifications> (VERIFIED).
- **direct_boot** (sí existe para FCM): lib `firebase-messaging-directboot:20.2.0`, servicio `android:directBootAware="true"` y `"direct_boot_ok": true` en `AndroidConfig`; permite entrega antes del primer unlock (requisito: no tocar storage credential-protected) — <https://firebase.google.com/docs/cloud-messaging/customize-messages/android-direct-boot> (VERIFIED). **LIKELY** útil para recuperación tras reinicio sin desbloqueo; exige rediseñar el handler para no leer storage cifrado.
- **topics vs tokens**: topics son "optimized for throughput rather than latency"; 2.000 topics/instancia; para probes individuales usar FID/token — <https://firebase.google.com/docs/cloud-messaging/topic-messaging> (VERIFIED).
- **batch**: Admin SDK `sendEachForMulticast` hasta 500 tokens/FIDs por llamada (una RPC por mensaje internamente) — <https://firebase.google.com/docs/reference/admin/java/reference/com/google/firebase/messaging/MulticastMessage> y <https://firebase.google.com/docs/cloud-messaging/send/admin-sdk> (VERIFIED). Para probes 1:1 no aporta; útil para difusión de configuración.
- **coste**: FCM es gratis; BigQuery export sin cargo; los límites son de cuota, no de facturación — <https://firebase.google.com/docs/cloud-messaging/understand-delivery> (VERIFIED).
- **analyticsLabel** por tipo de probe para reporting/Data API — misma URL (VERIFIED).

## 6. Cuotas por proyecto y por dispositivo

| Límite | Valor | Al exceder |
|---|---|---|
| Mensajes downstream proyecto | 600.000/min (default) | HTTP 429 `QUOTA_EXCEEDED`; +25% solicitable |
| Por dispositivo (Android) | 240/min y 5.000/h | retraso (`delayedMessageThrottled`) |
| Colapsables | ráfaga 20 + 1 cada 3 min por app/dispositivo | retraso |
| Pendientes no colapsables | 100 por app/dispositivo | `droppedTooManyPendingMessages` |
| TTL | máx 28 días | descarte |
| Payload | 4.096 bytes | rechazo |
| Topics | 2.000/instancia; 3.000 QPS sub; 1.000 fanouts concurrentes | 429 / rechazo |

Fuentes: <https://firebase.google.com/docs/cloud-messaging/throttling-and-quotas>, <https://firebase.google.com/docs/cloud-messaging/topic-messaging>, <https://firebase.google.com/docs/cloud-messaging/understand-delivery> (VERIFIED). Advertencia: "your app may be marked as abusive" si se roza el máximo — throttling-and-quotas (VERIFIED).

## 7. Cómo usan FCM otras apps OSS como canal de control

- **Signal**: `FcmReceiveService.java` compara `getPriority()`/`getOriginalPriority()`, y si es high encola fetch con servicio en foreground (`FcmFetchForegroundService.kt`, `FcmFetchManager.kt`) — <https://github.com/signalapp/Signal-Android/blob/main/app/src/main/java/org/thoughtcrime/securesms/gcm/FcmReceiveService.java> (VERIFIED).
- **Home Assistant**: `FirebaseCloudMessagingService.kt` delega en `MessagingManager.kt`; para "critical notifications" recomiendan `priority: high` + `ttl: 0`; el sender cloud limita a 150 notificaciones/24 h por usuario; el push local (WebSocket) se intenta primero y FCM es fallback — <https://github.com/home-assistant/android/blob/main/app/src/full/kotlin/io/homeassistant/companion/android/notifications/FirebaseCloudMessagingService.kt>, <https://companion.home-assistant.io/docs/notifications/critical-notifications>, <https://developers.home-assistant.io/docs/api/native-app-integration/notifications> (VERIFIED).
- **Telegram**: `GcmPushListenerService.java` recibe un payload FCM (campo `p`, cifrado) y hace fetch por MTProto: FCM es **wake-up**, no transporte; el push type 2 es "FCM token for google firebase" — <https://github.com/DrKLO/Telegram/blob/master/TMessagesProj/src/main/java/org/telegram/messenger/GcmPushListenerService.java> y <https://core.telegram.org/api/push-updates> (VERIFIED). Telegram-FOSS no usa FCM y mantiene una notificación persistente para sobrevivir — <https://github.com/Telegram-FOSS-Team/Telegram-FOSS/blob/master/Notifications.md> (VERIFIED).
- **OwnTracks**: sin FCM (no hay `firebase`/`gcm` en el repo); usa MQTT bidireccional + comandos remotos, y documenta que el arranque en background "may not work reliably on all Android versions due to manufacturer battery optimizations" — <https://github.com/owntracks/android> y <https://github.com/owntracks/android/blob/master/docs/PREFERENCES.md> (VERIFIED).

## 8. Force-stop: sin workaround

FCM no entrega tras force-stop hasta abrir la app (§2). No existe API legítima para reactivar una app detenida: ni jobs, ni alarms, ni FCM, ni `FLAG_INCLUDE_STOPPED_PACKAGES` (aplica a broadcasts de terceros). Alternativas de campo (todas fuera de FCM, requieren permisos y no son "wake" estándar): SMS entrante de recuperación, o que el usuario vuelva a abrir la app. En OEM con "autostart"/"protected apps" el fabricante puede reactivar (Huawei/Xiaomi), pero es configuración de usuario, no contrato — <https://gist.github.com/gdeglin/98aeda28035b45cef04bb6c2cb41a4aa> (LIKELY). Recomendación: marcar estos casos como `RECOVERY_NOT_DELIVERABLE` y no contarlos como fallo de FCM.

## 9. Medición de entrega/latencia y "soft success"

- Herramientas oficiales: (a) Reports en consola (Sends/Received/Impressions/Opens) requiere Google Analytics; (b) **FCM Data API** agregada: `priorityLowered`, `droppedTooManyPendingMessages`, `delayedMessageThrottled`, `deliveredNoDelay` (lag hasta 5 días); (c) **BigQuery export** por mensaje: `MESSAGE_ACCEPTED` y `MESSAGE_DELIVERED` (requiere `setDeliveryMetricsExportToBigQuery(true)` o metadata); (d) cliente: `getPriority()` vs `getOriginalPriority()` detecta degradación — <https://firebase.google.com/docs/cloud-messaging/understand-delivery> y message-priority (VERIFIED). Play Console no aplica a sideload; Crashlytics sí funciona sideloaded para ANRs — <https://firebase.google.com/docs/crashlytics/debug-anr-errors> (VERIFIED).
- **"Soft success" (posición real ≤5 min tras probe) es razonable, pero sesgada**. Sesgos a controlar:
  1. **Selección**: los probes solo se envían tras silencio anómalo ⇒ la muestra está sesgada a malas condiciones; no es una tasa "poblacional".
  2. **Atribución**: una posición a los 3 min pudo generarla el ciclo normal (no el probe). Comparar contra la cadencia base sin probe (contrafactual) y exigir `RECEIVED` previo dentro de la ventana.
  3. **Exclusiones estructurales**: force-stop, hibernación, battery/data saver, sin token, offline total ⇒ etiquetar `NOT_DELIVERABLE`/`BLOCKED`, no computar como "FCM falló".
  4. **Semántica de entrega**: TTL/collapse descartan o reemplazan mensajes; con `collapse_key` el conteo de probes se subestima.
  5. **Reloj**: usar timestamps de servidor para SENT/RECEIVED; el `tst` de la posición puede venir del reloj del equipo (skew).
  6. **N pequeño** (~127/día, 3 éxitos): reportar intervalos de confianza y agregar semanal; separar por OEM/modelo (los OEM ya auditados en `docs/audit/OEM_*`).
  7. **Degradación silenciosa**: sin log de `originalPriority` en `RECEIVED`, un fallo por downgrade se ve igual que un fallo de red.
- Definiciones propuestas: `RECOVERY_SENT` (accepted + messageId) → `RECOVERY_DELIVERED_SDK` (BigQuery `MESSAGE_DELIVERED` o ACK cliente) → `RECOVERY_WOKE` (handler con `priority/originalPriority`) → `RECOVERY_SOFT_SUCCESS` (posición ≤5 min) → `RECOVERY_SUCCESS` (actual, evidencia completa). Añadir `RECOVERY_DEPRIORITIZED`, `RECOVERY_NOT_DELIVERABLE`, `RECOVERY_NO_NETWORK`.

## Tabla final: práctica → recomendación para nuestro caso → evidencia

| Práctica | Recomendación | Evidencia |
|---|---|---|
| Probe high-priority data-only sin notificación | Mantener data-only para despertar, pero **postear notificación silenciosa** (canal `IMPORTANCE_LOW`, sin sonido) al procesar el probe, para no entrar en el patrón de degradación de 7 días; verificar `POST_NOTIFICATIONS` en onboarding | <https://firebase.google.com/docs/cloud-messaging/android/message-priority> + <https://developer.android.com/about/versions/13/behavior-changes-all> |
| Confiar en high tras force-stop | No; detectar stopped/hibernación y clasificar `NOT_DELIVERABLE`; no reintentar en bucle | <https://groups.google.com/g/android-gcm/c/1J6XNVOzCcE> + <https://developer.android.com/topic/performance/app-hibernation> |
| TTL de probes | Corto (300 s) y sin `collapse_key`; un attemptId por probe | <https://firebase.google.com/docs/cloud-messaging/customize-messages/setting-message-lifespan> |
| Tokens | Guardar FID/token con timestamp; borrar en `UNREGISTERED`/`INVALID_ARGUMENT`; `onNewToken` + refresh ≤1/semana | <https://firebase.google.com/docs/cloud-messaging/manage-tokens> |
| Entrega antes del primer unlock tras reboot | Evaluar `direct_boot_ok` + lib directboot (exige handler sin storage credential-protected) | <https://firebase.google.com/docs/cloud-messaging/customize-messages/android-direct-boot> |
| Medir éxito solo server-side | Añadir `RECOVERY_DELIVERED_SDK` (BigQuery) y `RECOVERY_WOKE` con `originalPriority`; reportar "soft success" con contrafactual y por OEM | <https://firebase.google.com/docs/cloud-messaging/understand-delivery> |
| Monitorizar degradación | Alertar si `priorityLowered` sube en FCM Data API o si `RECEIVED` reporta `priority=normal` | <https://firebase.google.com/docs/cloud-messaging/understand-delivery> + <https://firebase.google.com/docs/reference/android/com/google/firebase/messaging/RemoteMessage> |
| Rate limits | ≤1 probe por dispositivo/cooldown actual (muy por debajo de 240/min y 5.000/h); vigilar 429 de proyecto solo en campañas masivas | <https://firebase.google.com/docs/cloud-messaging/throttling-and-quotas> |
