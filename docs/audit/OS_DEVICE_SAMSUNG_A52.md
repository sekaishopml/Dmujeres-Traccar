# OS/Device — Samsung Galaxy A52 (SM-A525M) · One UI 6.1 · FGS location · MQTT

Fecha: 2026-09-19 · Investigación de solo lectura (sin código de producción).
Contexto: `com.dmujeres.traccar` 1.1.7, FGS location, 163 reconexiones MQTT/24 h, 1 crash/24 h.
Confianza: VERIFIED (fuente oficial o 2+ independientes) · LIKELY (familia de ROM/1 fuente) · UNKNOWN.

## 1. Modelo, región y firmware

| Punto | Hallazgo | Evidencia | Confianza |
|---|---|---|---|
| Modelo/región | **SM-A525M** = variante latinoamericana: CSC ZTO/ZTA/ZVV (Brasil), MEX/MXO/IUS/TMM (México), ARO/CTI/PSN/UFN (Argentina), CHE/CHO, COL/COO, PET/PSP/PEO, EON/EBE (Ecuador), CGU/GTO, NBS, TPA, UPO/CTP, UFU/UYO | https://samfw.com/firmware/SM-A525M/CHE/A525MUBSBFYC1 (lista de CSC hermanos) | VERIFIED |
| Build A525MUBSCFYF2 | Android 14 / **One UI 6.1**, changelist 28693178, subida 12-jun-2025 (ECO); hermanas FYF1/FYC1 en otras regiones | https://samfrew.com/firmware/model/SM-A525M/upload/Desc/0/10 · https://samfrew.com/download/Galaxy__A52__/yalr/ECO/A525MUBSCFYF2/A525MOWACFYF2 | VERIFIED |
| ¿Última oficial? | Sí: FYF2/FYF1 (jun-2025) es la build más reciente listada para SM-A525M (consulta 2026-09) | https://samfrew.com/firmware/model/SM-A525M/upload/Desc/0/10 | VERIFIED |
| One UI 6.1 = última versión mayor | A52/A52 5G/A52s recibieron 6.1 (may-2024, p. ej. A525FXXU6FXD2) y **no** recibirán One UI 7 (Android 15) | https://www.sammobile.com/news/samsung-galaxy-a52-one-ui-6-1-update-released/ · https://www.sammobile.com/news/galaxy-a52-5g-a52s-android-one-ui-updates-discontinued/ · https://techgriot.co/english/news/2025/05/one-ui-7-on-samsung-galaxy-a-a-fresh-breeze-that-wont-reach-everyone/ | VERIFIED |
| Soporte restante | Solo parches de seguridad (A52 4G/5G trimestrales, ~hasta 2026); en LATAM último parche observado jun-2025 | https://www.phonearena.com/news/samsungs-galaxy-a52-no-android-updates_id159172 · https://samfrew.com/firmware/model/SM-A525M/upload/Desc/0/10 | LIKELY |

## 2. Pantallas One UI 6.1 (nombres en español de LATAM) y paquetes

| Pantalla (usuario) | Ruta en el teléfono | Paquete/clase/capa | Evidencia |
|---|---|---|---|
| **Límites de uso en segundo plano** (hub) | Ajustes → Cuidado del dispositivo → Batería → Límites de uso en segundo plano | UI de `com.samsung.android.lool` (Device Care) | https://www.samsung.com/co/support/mobile-devices/sleeping-apps-on-your-galaxy-phone/ · https://www.samsung.com/latin/support/galaxy-battery/optimization/ |
| **Aplicaciones sin autosuspensión** (= Never sleeping apps; en One UI viejo "Aplicaciones que no se suspenden") | … → Límites de uso en segundo plano → Aplicaciones sin autosuspensión → “+” | lista interna Device Care (`CheckableAppListActivity`) | https://www.samsung.com/co/support/mobile-devices/sleeping-apps-on-your-galaxy-phone/ · https://developer.samsung.com/mobile/app-management.html |
| **Aplicaciones suspendidas** (= Sleeping apps) | … → Límites de uso en segundo plano → Aplicaciones suspendidas | idem | https://www.samsung.com/co/support/mobile-devices/sleeping-apps-on-your-galaxy-phone/ |
| **Aplicaciones en suspensión profunda** (= Deep sleeping apps) | … → Límites de uso en segundo plano → Aplicaciones en suspensión profunda | idem | https://www.samsung.com/latin/support/galaxy-battery/optimization/ |
| **Suspender aplicaciones sin uso** (master switch) | … → Límites de uso en segundo plano (interruptor superior) | idem | https://www.samsung.com/co/support/mobile-devices/sleeping-apps-on-your-galaxy-phone/ · https://dontkillmyapp.com/samsung |
| **Batería por app** (Sin restricciones / Optimizado / Restringido) | Ajustes → Aplicaciones → [app] → Batería | pantalla de batería de la app | https://www.samsung.com/latin/support/mobile-devices/audio-stops-playing-on-galaxy-mobile-devices-or-accessories/ |
| **Permitir actividad en segundo plano** (toggle tipo AOSP background-restricted) | Ajustes → Aplicaciones → [app] → Batería (junto a lo anterior) | AOSP `ActivityManager.isBackgroundRestricted()` | https://developer.android.com/reference/android/app/ActivityManager#isBackgroundRestricted() · https://vpn.how/es/pages/android-15-y-16-always-on-y-vpn-por-aplicacion-guia-paso-a-paso-con-configuracion-en-pixel.html (etiqueta exacta One UI, 1 fuente) |
| **Eliminar permisos / Pausar actividad si no se usa** (auto-revoke/hibernación) | Ajustes → Aplicaciones → [app] → (sección “apps sin usar” en App info) | AOSP Settings; Android 13+: “Pausar actividad en la app si no se usa” | https://developer.android.com/topic/performance/app-hibernation?hl=es-419 |
| **Optimización de batería** (lista AOSP “No optimizar”) | Ajustes → Aplicaciones → [app] → Batería → Optimizar uso de batería (o Acceso especial) | `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` | https://developer.android.com/reference/android/provider/Settings#ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS |
| **Ahorro de energía** | Ajustes → Batería → Ahorro de energía | bloquea Wi-Fi/datos en segundo plano | https://www.samsung.com/latin/support/galaxy-battery/optimization/ |

## 3. Qué pantalla rompe / beneficia al FGS location

- **Rompe — “Aplicaciones suspendidas” (sleeping):** Samsung aplica *restricted bucket* y restringe **Job, Alarm y Foreground-service**; una app que pasó ~3 días sin abrirse puede caer ahí automáticamente. https://developer.samsung.com/mobile/app-management.html (§3.1) · https://source.android.com/devices/tech/power/app_mgmt
- **Rompe más — “Aplicaciones en suspensión profunda”:** solo corre al abrirla; sin notificaciones ni updates; a los ~16 días de desuso igual cae sola. https://developer.samsung.com/mobile/app-management.html (§3.2)
- **Rompe — Batería “Restringido” y “Permitir actividad en segundo plano” OFF:** equivalen al *restricted bucket* AOSP. La lectura fiable es `ActivityManager.isBackgroundRestricted()` / `UsageStatsManager.getAppStandbyBucket()`. https://developer.android.com/reference/android/app/ActivityManager#isBackgroundRestricted()
- **Rompe — “Ahorro de energía” ON:** “las aplicaciones en segundo plano no podrán usar Wi-Fi ni datos móviles” (causa directa de caídas MQTT). https://www.samsung.com/latin/support/galaxy-battery/optimization/
- **Beneficia y es seguro — “Aplicaciones sin autosuspensión” (Never sleeping):** es la excepción documentada por Samsung al control automático; sin ella, el master “Suspender apps sin uso” puede re-dormir la app a los ~3 días. https://developer.samsung.com/mobile/app-management.html (§3.3) · https://dontkillmyapp.com/samsung
- **Beneficia — Batería “Sin restricciones” / exención de optimización:** recomendado por Samsung para reproducción en background. https://www.samsung.com/latin/support/mobile-devices/audio-stops-playing-on-galaxy-mobile-devices-or-accessories/
- **Evitar tocar:** no meter la app en listas de suspensión, no bajar de “Optimizado”, no activar ahorro de energía. Nota: en algunos One UI, si la batería está “Sin restricciones” la app desaparece de las listas de suspensión (ya está exenta); en otros, para poder añadirla a “Never sleeping” hay que tenerla en “Optimizado”. https://github.com/urbandroid-team/dont-kill-my-app/issues/229
- **One UI 6.0+ y FGS:** Samsung promete que los FGS de apps que apuntan a Android 14+ funcionan según la política AOSP si están bien implementados (nuestro repo apunta a `targetSdk 35`, `FOREGROUND_SERVICE_LOCATION`, tipo `location` — evidencia local `mobile/app/build.gradle.kts:39`, `AndroidManifest.xml:14-15,109,115`), pero el bucket/batería siguen aplicando. https://developer.samsung.com/mobile/app-management.html (§5)

## 4. Abrir cada pantalla por Intent (clases/acciones exactas)

| Pantalla | Intent exacto | Evidencia | Confianza |
|---|---|---|---|
| Sleeping / Deep sleeping / Never sleeping (0/1/2) | `Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY")`, `setPackage("com.samsung.android.lool")`, extra `activity_type`: **0=sleeping, 1=deep, 2=never sleeping**, luego `startActivity` | https://developer.samsung.com/mobile/app-management.html (§4, API oficial) | VERIFIED |
| Lista checkable por componente (variante OSS) | `ComponentName("com.samsung.android.lool","com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity")` | https://github.com/judemanutd/AutoStarter/blob/master/autostarter/src/main/java/com/judemanutd/autostarter/AutoStartPermissionHelper.kt | LIKELY |
| Batería / Device Care (One UI 5+) | `ComponentName("com.samsung.android.lool","com.samsung.android.sm.battery.ui.BatteryActivity")` | AutoStarter (arriba) · https://github.com/transistorsoft/flutter_background_geolocation/issues/1234 · https://stackoverflow.com/questions/48166206 | VERIFIED OSS |
| Batería (One UI ≤5 / legacy) | `ComponentName("com.samsung.android.lool" ó "com.samsung.android.sm","com.samsung.android.sm.ui.battery.BatteryActivity")` | https://stackoverflow.com/questions/59607933 · https://stackoverflow.com/questions/37205106 | LIKELY |
| Batería por acción (fallback) | `Intent("com.samsung.android.sm.ACTION_BATTERY")` | https://github.com/pvsvamsi/Disable-Battery-Optimizations (devices/Samsung.java) | LIKELY |
| Optimización de batería (lista) / diálogo | `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` · `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + extra `package:<pkg>` (requiere `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | https://developer.android.com/reference/android/provider/Settings | VERIFIED (AOSP) |
| Auto-revoke (“no quitar permisos si no se usa”) | No hay pantalla directa; usar `PackageManagerCompat.createManageUnusedAppRestrictionsIntent()` (abre App info) | https://developer.android.com/reference/kotlin/androidx/core/content/PackageManagerCompat | VERIFIED |
| App info genérica (fallback universal) | `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`, data `package:<pkg>` | https://developer.android.com/reference/android/provider/Settings#ACTION_APPLICATION_DETAILS_SETTINGS | VERIFIED |

Regla de oro: resolver con `queryIntentActivities/resolveActivity` y `try/catch` en cadena (Samsung cambia clases entre One UI); nunca asumir que la activity existe. https://stackoverflow.com/questions/59607933

## 5. MQTT: ¿One UI mata/duerme las conexiones con pantalla apagada?

- **Sí hay efecto, incluso con FGS:** caso Paho en **Samsung A52s** (misma familia): con pantalla apagada y sin cargador no se envían PINGREQ y el broker corta por timeout; el propio autor lo atribuye a gestión de energía y lo mitiga con `automaticReconnect`. https://stackoverflow.com/questions/79835732/paho-android-mqtt-client-does-not-send-pingreq-to-broker-when-phone-screen-is-of
- **Mecanismo:** en Doze se suspende red/CPU y los alarms (`AlarmPingSender` usa `setExactAndAllowWhileIdle`) se difieren; reportado en FGS con cortes periódicos. Workarounds usados: `setKeepAliveInterval(0)` (sin pings, servidor no espera) o keepalive largo (>20 min) o `AlarmClock`. https://github.com/eclipse-paho/paho.mqtt.android/issues/449 · https://developer.android.com/training/monitoring-device-state/doze-standby
- **Keepalive recomendado:** históricamente 300 s resolvió cortes donde 60 s fallaba (Android posterga el ping más que 2×keepalive). https://github.com/eclipse-paho/paho.mqtt.java/issues/270 · https://github.com/eclipse-paho/paho.mqtt.android/issues/226
- **En este equipo:** la app usa `keepAliveInterval = 45` + `automaticReconnect` (evidencia local `mobile/.../MqttManager.kt:323,231`). 163 reconexiones/24 h ≈ una cada **8,8 min**, compatible con ciclos de suspensión/ventanas de mantenimiento y con el corte 1,5×45 s=67 s; con período de pantalla apagada y sin cargador. (Inferencia aritmética; sin URL) → Confianza MEDIA, refuerza la hipótesis de Doze/standby, no de red.
- **Contexto Samsung:** desde Android 11 Samsung bloquea wake locks en FGS y duerme apps no usadas; desde One UI 6 garantiza FGS de apps target 14+, pero Doze/bucket siguen aplicando. https://dontkillmyapp.com/samsung

## 6. “Eliminar permisos si la app no se usa” (auto-revoke/hibernación)

- Efecto real: Android 12+ **restablece permisos runtime**, impide jobs/alarms y **no recibe FCM** hasta reabrir la app (perdería ubicación y tracking). https://developer.android.com/topic/performance/app-hibernation?hl=es-419
- Mitigación **usuaria** (única vía fiable): desactivar el interruptor por app en App info (“Pausar actividad en la app si no se usa” en Android 13+). https://developer.android.com/topic/performance/app-hibernation?hl=es-419 · https://tecnobits.com/evitar-que-android-elimine-permisos-de-las-aplicaciones/ (paso a paso por marca)
- Mitigación **app (API pública):** detectar estado con `PackageManagerCompat.getUnusedAppRestrictionsStatus()` y llevar a la usuaria con `createManageUnusedAppRestrictionsIntent()` (obligatorio `startActivityForResult`). https://developer.android.com/reference/kotlin/androidx/core/content/PackageManagerCompat
- **`setAutoRevokeWhitelisted` NO es usable por la app:** exige permiso `WHITELIST_AUTO_REVOKE_PERMISSIONS` (sistema/instalador) o ser el instalador registrado. https://learn.microsoft.com/en-us/dotnet/api/android.content.pm.packagemanager.setautorevokewhitelisted · https://android.googlesource.com/platform/frameworks/base/+/9b1ead5%5E%21/ ; `isAutoRevokeWhitelisted()` (sin args) sí es público y solo lee el estado propio. https://learn.microsoft.com/en-us/dotnet/api/android.content.pm.packagemanager.isautorevokewhitelisted
- **Crash 1/24 h:** si coincide con revocación de permiso o force-stop, el FGS puede morir al no poder usar ubicación; verificar estado de permisos y de auto-revoke antes de concluirlo. Confianza BAJA (hipótesis, sin evidencia directa).

## 7. Recomendaciones priorizadas

**Usuaria (5 min, en este orden):**
1. Actualizar la app de 1.1.7 a la versión actual del repo (1.1.10): incluye fixes de reconexión/keepalive (evidencia local; `mobile/app/build.gradle.kts:40-41`).
2. Batería por app → **Sin restricciones** (no “Optimizado”, nunca “Restringido”). https://www.samsung.com/latin/support/mobile-devices/audio-stops-playing-on-galaxy-mobile-devices-or-accessories/
3. Cuidado del dispositivo → Batería → Límites de uso en segundo plano → **Aplicaciones sin autosuspensión → añadir la app**; verificar que NO esté en Suspendidas/Suspensión profunda y que “Suspender apps sin uso” no la recapture (revisar tras 3 días). https://developer.samsung.com/mobile/app-management.html
4. Desactivar **“Pausar actividad/Eliminar permisos si no se usa”** en App info. https://developer.android.com/topic/performance/app-hibernation?hl=es-419
5. Evitar modo **Ahorro de energía**; opcional: exención de optimización de batería (lista “No optimizar”). https://www.samsung.com/latin/support/galaxy-battery/optimization/

**App (solo APIs públicas):**
6. Onboarding OEM Samsung con deeplink oficial `ACTION_OPEN_CHECKABLE_LISTACTIVITY` + `activity_type=2`, fallback `BatteryActivity`/`ACTION_BATTERY`/App info; verificación con `isIgnoringBatteryOptimizations`, `isBackgroundRestricted`, `getAppStandbyBucket`. (URLs §2-§4)
7. Auto-revoke: mostrar aviso si `getUnusedAppRestrictionsStatus()` indica restricción y abrir `createManageUnusedAppRestrictionsIntent()`. https://developer.android.com/reference/kotlin/androidx/core/content/PackageManagerCompat
8. Transporte: subir keepalive a ~300 s (o 0 con heartbeat de app, p. ej. cada 60 s) manteniendo `automaticReconnect` y watchdog; medir reconexiones/24 h como KPI. https://github.com/eclipse-paho/paho.mqtt.android/issues/449 · https://github.com/eclipse-paho/paho.mqtt.java/issues/270
9. Plan B de despertar con FCM de alta prioridad (ya previsto en el repo) para forzar reconexión tras Doze. https://github.com/eclipse-paho/paho.mqtt.android/issues/226
10. No intentar whitelisting programático de listas Samsung ni auto-revoke (imposible sin permisos de sistema); solo abrir UI y verificar. https://learn.microsoft.com/en-us/dotnet/api/android.content.pm.packagemanager.setautorevokewhitelisted

## Tabla final — acción → quién → evidencia → confianza

| Acción | Quién | Evidencia | Confianza |
|---|---|---|---|
| Añadir a “Aplicaciones sin autosuspensión” | Usuaria | developer.samsung.com/mobile/app-management.html (§3.3) | ALTA |
| Batería “Sin restricciones” (no Restringido/Optimizado) | Usuaria | samsung.com/latin/.../audio-stops-playing... | ALTA |
| No meter en Suspendidas/Suspensión profunda | Usuaria | developer.samsung.com/mobile/app-management.html (§3.1/3.2) | ALTA |
| Desactivar auto-revoke por app | Usuaria | developer.android.com/topic/performance/app-hibernation | ALTA |
| Evitar Ahorro de energía | Usuaria | samsung.com/latin/support/galaxy-battery/optimization/ | MEDIA-ALTA |
| Actualizar 1.1.7 → 1.1.10 | Usuaria/Dev | evidencia local (repo mobile/) + samsung dev §5 | ALTA (local) |
| Deeplink intent sueño/batería con fallbacks | App | developer.samsung.com §4 · github.com/judemanutd/AutoStarter | ALTA |
| Verificar estado con APIs AOSP (ignoring/background/bucket) | App | developer.android.com (PowerManager/ActivityManager/UsageStatsManager) | ALTA |
| Wizard auto-revoke (`getUnusedAppRestrictionsStatus`) | App | developer.android.com/reference/kotlin/androidx/core/content/PackageManagerCompat | ALTA |
| Keepalive 300 s (o 0) + autoReconnect + KPI reconexiones | App | github.com/eclipse-paho/paho.mqtt.android/issues/449 · #270 · SO 79835732 | MEDIA-ALTA |
| FCM alta prioridad como despertador | App | github.com/eclipse-paho/paho.mqtt.android/issues/226 | MEDIA |
| No whitelisting programático (Samsung/auto-revoke) | App | learn.microsoft.com setautorevokewhitelisted | ALTA (no posible) |
| Contar reconexiones como métrica de doze/OEM | App | SO 79835732 + datos 163/24 h (inferencia) | MEDIA |
