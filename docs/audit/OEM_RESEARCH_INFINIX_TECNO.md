# OEM_RESEARCH_INFINIX_TECNO.md — Pantallas HiOS (Tecno) / XOS (Infinix) abribles por Intent

> Investigación de solo lectura (2026-09-18). No se modificó código del repo.
> Objetivo: qué pantallas de supervivencia en segundo plano (auto-inicio, ahorro de
> energía por app, apps protegidas, bloqueo en recientes) se pueden abrir desde una
> app Android en teléfonos Transsion (Tecno HiOS, Infinix XOS, itel), con evidencia
> verificable y sin hacks (no appops, no root, no Accessibility, no ADB en producción).

## 0. Método y clasificación

Fuentes usadas: dontkillmyapp.com (DKMA), análisis estático de APK en Pithus
(manifest real: exported + componente exacto), proyectos OSS que ya lanzan estos
intents (Traccar Client, transistorsoft, auto_start_flutter, gists de
POWERMANAGER_INTENTS), issues de campo (AutoStarter #47 con `dumpsys activity`) y
documentación Android.

| Marca | Significado |
|---|---|
| **VERIFIED** | Fuente confiable con paquete+activity exactos (manifest estático y/o varios OSS usándolo). Aun así: `resolveActivity` + `try/catch` obligatorios. |
| **LIKELY** | Componente exacto de fuente razonable (1 sola fuente, o no aparece en el APK Play, o la ROM puede no exponerlo). Usar solo como candidato con fallback. |
| **UNVERIFIED / NO POSIBLE** | Sin evidencia utilizable. No implementar. |

Aclaración de nombres: **no existe** un paquete `com.hios.*` ni `com.infinix.*` para
estas pantallas. Todo HiOS/XOS vive bajo `com.transsion.*` (firma `CN=HiOS,
O=TecnoMobile` / `CN=XOS, O=InfinixMobility` en los APK analizados en Pithus).
El launcher es `com.transsion.hilauncher` en HiOS.

---

## 1. Tabla por fabricante/pantalla → componente/action exacto

Aplica a **Infinix (XOS) y Tecno (HiOS)** por igual (mismo grupo Transsion; las
variantes itel comparten namespace). Diferencias solo de nombre de menú (§2).

### 1.1 Auto-inicio / autostart

| Pantalla | Componente / Action exacto | Evidencia | Clasificación |
|---|---|---|---|
| Phone Master → Toolbox → **Auto-start management** | `com.transsion.phonemaster` / `com.cyin.himgr.autostart.AutoStartActivity` | Pithus manifest (exported, SIN protección) en 5 versiones: 5.1.3.11141, 5.1.6.00003, 5.2.3.12037, 5.2.7.00003, 6.1.3.00012 — https://beta.pithus.org/report/bb8131a2b891ccd39295f1df157f7d2f30d161dba318392f692c8fe8b7bce69d · uso en OSS: https://github.com/seena98/auto_start_flutter/blob/main/android/src/main/kotlin/co/techFlow/auto_start_flutter/AutoStartIntents.kt (líneas 57-58) · https://gist.github.com/GransonO/8fd3bfa0521c914597e46ebaf3ae39d3 · observación de campo `dumpsys`: https://github.com/judemanutd/AutoStarter/issues/47 | **VERIFIED** (componente y exported). Nota: en algunas ROMs el componente no resuelve ("component not found" en #47) → tratar como candidato, no como certeza. |
| Phone Manager (variante sistema, sin Phone Master) | `com.transsion.phonemanager` / `com.itel.autobootmanager.activity.AutoBootMgrActivity` | Lista OSS mantenida por transistorsoft: https://github.com/transistorsoft/flutter_background_geolocation/issues/733 · gist POWERMANAGER_INTENTS: https://gist.github.com/p32929/41e7af650f6a2c11e9306ab600fb9b03 | **LIKELY**. En el APK Play "PhoneMaster Services" 3.3.0.0017 la activity no aparece (https://beta.pithus.org/report/109b376b5c6fb9cc776506b70355c8be3cd7008b4c627569875f8dd09c706438); existe en variantes de ROM. |
| Ajustes → Seguridad → **Autostart management** (base MediaTek) | Action `com.mediatek.autobootcontroller.AUTO_BOOT` / `com.mediatek.autobootcontroller/.AutoBootAppManageActivity` | Observación de campo vía `dumpsys activity` en AutoStarter #47 (mismo issue, segundo reporte: "new package name… com.mediatek.autobootcontroller/.AutoBootAppManageActivity") | **LIKELY** (1 sola observación; no está en ninguna lista OSS mantenida). |

### 1.2 Ahorro de energía / battery saver por app

| Pantalla | Componente / Action exacto | Evidencia | Clasificación |
|---|---|---|---|
| **Battery Lab → Power Saving Management for apps** (per-app) | `com.transsion.batterylab` / `com.android.settings.batterysave.BatteryLab$TranAppSavingActivity` | Pithus manifest (exported=true) en 3.1.2.038, 3.1.2.116 y 5.1.0.079 — https://beta.pithus.org/report/46710cf43493f970c8e33be0deb04ff5559aea0bd0a0b3e620b35d6e3eee3a26 · https://beta.pithus.org/report/472743bf21947a092a625b963e08c9873e67a2a85d84fe764a08c861f6c04a2e · https://beta.pithus.org/report/b09da4a71008d86c39e114069863fc25eac7391f79ea16b78e8cf1ea0a966c12 · semántica: DKMA "Battery Lab → Battery Saving Settings → Disable Power Saving Management For Apps" https://dontkillmyapp.com/tecno · paquete = sección "Battery & Power Saving" de Ajustes: https://github.com/MuntashirAkon/android-debloat-list/issues/73 | **VERIFIED** el componente/exported; **LIKELY** que sea exactamente la lista per-app. |
| Power saving global / Ultra | `com.transsion.batterylab` / `com.transsion.powersave.activity.PowerSavaMainActivity` y `...PowerSavaLauncherActivity` (exported=true) | Pithus (mismos reportes) | **VERIFIED** existencia/exported; rol **LIKELY** (pantalla principal de ahorro). |
| Phone Master → **Power Manager** | `com.transsion.phonemaster` / `com.cyin.himgr.powermanager.views.activity.PowerManagerActivity` (y `...OsPowerActivity`), exported=true | Pithus (5.1.6 y 6.1.3) | **LIKELY** (existe/exported; pantalla concreta no confirmada en campo). |
| AOSP por app (genérico) | `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` | Documentado: https://developer.android.com/reference/android/provider/Settings#ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS | **VERIFIED** (pero en HiOS NO controla Hiber/Power Saving Management: DKMA + https://github.com/urbandroid-team/dont-kill-my-app/issues/3800). |
| AOSP diálogo exención (genérico) | `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `package:` URI + permiso `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Documentado; política Play: https://support.google.com/googleplay/android-developer/answer/9888379 · restricción de permiso: https://capawesome.io/docs/sdks/capacitor/android-battery-optimization/ | **VERIFIED** técnico, pero **desaconsejado** por política Play (solo si el core del app lo justifica; el APK es interno, decisión del equipo). |

### 1.3 Apps protegidas / background running

| Pantalla | Componente / Action exacto | Evidencia | Clasificación |
|---|---|---|---|
| "Protected apps" / whitelist de memoria (Phone Master) | `com.cyin.himgr.applicationmanager.view.activities.MemoryAccelerateWhitelistActivity` / `...Activity2` | **NO exported** en Pithus 5.1.6 (no figura en la lista "is not Protected"; solo en el listado de componentes). Intento desde app → `SecurityException: Permission Denial`: https://stackoverflow.com/questions/52623051/how-to-start-protected-apps-activity-in-infinix-phone-programatically | **NO POSIBLE** abrirla (protegida por el sistema). Solo pasos guiados. |
| XManager antiguo (Infinix pre-Phone Master) | `com.transsion.mobilebutler.MainActivity` (solo portada) | Mismo SO 52623051: `SettingsActivity`/pantalla de protegidas → SecurityException | **NO POSIBLE** pantalla específica (paquete legacy, no usar). |
| Freeze/hibernación (Phone Master) | `com.cyin.himgr.applicationmanager.view.activities.AddFreezeAppActivity` (exported=true) | Pithus manifest | **LIKELY** existe, pero **no recomendado**: es la lista de apps a congelar (efecto contrario al buscado). |

### 1.4 Bloqueo en recientes (lock)

| Pantalla | Componente / Action exacto | Evidencia | Clasificación |
|---|---|---|---|
| Candado en apps recientes | **No hay componente ni API pública** | DKMA lo describe como gesto manual ("press the first option with the 🔒 icon"): https://dontkillmyapp.com/tecno · AOSP solo tiene screen pinning (`LockTaskController`, `startLockTask()`), que NO es el candado de recientes: https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/wm/LockTaskController.java | **NO POSIBLE** por API. Solo paso guiado. |

### 1.5 Genéricos AOSP (útiles como fallback)

| Action | Uso | Evidencia | Clasificación |
|---|---|---|---|
| `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` + `package:<pkg>` | Página de la app (ruta segura universal) | https://developer.android.com/reference/android/provider/Settings#ACTION_APPLICATION_DETAILS_SETTINGS | **VERIFIED** |
| `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` | Lista AOSP de optimización de batería | https://developer.android.com/reference/android/provider/Settings#ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS | **VERIFIED** |
| `Settings.ACTION_BATTERY_SAVER_SETTINGS` | Ahorro de batería global | https://developer.android.com/reference/android/provider/Settings#ACTION_BATTERY_SAVER_SETTINGS | **VERIFIED** (no per-app) |

---

## 2. Pasos manuales exactos (texto para el usuario)

Usar según versión. En todas: si un nombre no aparece, usar la lupa de Ajustes y
buscar "Auto-start", "Batería" o "Power".

### A. HiOS/XOS antiguos con Phone Master (Tecno HiOS ≤12 / Infinix XOS ≤12, XOS 7–11)

Texto sugerido (basado en DKMA Tecno, https://dontkillmyapp.com/tecno, y guía de
campo Infinix Hot 10, https://github.com/urbandroid-team/dont-kill-my-app/issues/474):

1. Abre **Phone Master → Caja de herramientas → Gestión de auto-inicio** y activa
   DMujeres ("Permitir que la app se ejecute en segundo plano").
2. **Ajustes → Batería → Battery Lab → Battery Saving Settings** y desactiva
   **"Power Saving Management For Apps"**.
3. En **Power Marathon**: desactiva **Power Boost**. (Opcional: App Booster permite
   4 apps; añade DMujeres.)
4. Opcional avanzado: **Screen off push block = OFF**, **Screen off sleep = OFF**,
   **Screen off scheduled push = OFF**.
5. **Recientes**: baja la tarjeta de DMujeres y toca el **candado 🔒**.

### B. XOS 13+ (Infinix; p. ej. Zero 5G / GT 50 Pro; XOS 13–15)

Fuente: https://www.sebertech.com/how-to-fix-infinix-gt-50-pro-with-background-apps-closing/

1. **Ajustes → Apps → Gestión de apps → DMujeres → Batería/Uso de batería** →
   permitir **actividad en segundo plano**.
2. En la misma ficha: **Gestión de auto-inicio → activar DMujeres**.
3. **Ajustes → Batería → Battery Lab / Power Marathon**: quita DMujeres de
   ahorro de energía ("Sin restricciones") y desactiva el ahorro nocturno.
4. **Recientes** → candado 🔒 en la tarjeta de DMujeres.

### C. HiOS 13+ (Tecno Pova/Camon/Spark; HiOS 13–15)

1. **Ajustes → Batería → Battery & Power Saving (Battery Lab)** → desactivar
   "Power Saving Management For Apps" (paquete `com.transsion.batterylab`,
   confirmado como sección de Ajustes en HiOS 15:
   https://github.com/MuntashirAkon/android-debloat-list/issues/73).
2. **Ajustes → Apps → Gestión de apps → DMujeres** → batería "Sin restricciones"
   + **auto-inicio** activado.
3. Si existe **Phone Master / Phone Manager**, repetir A.1.
4. **Recientes** → candado 🔒.

### D. Verificación honesta

No hay API para consultar si el auto-inicio quedó activado (afirmado por DKMA y por
https://www.codewithkarani.com/blog/oem-autostart-battery-optimisation-android-deep-links).
La única señal es el watchdog de la app (servicio que no vuelve). Tras configurar,
probar: pantalla apagada 30 min + reinicio del equipo.

---

## 3. Qué NO es posible abrir por API (y por qué)

1. **Apps protegidas / "Protected apps"**: activity no exportada; un intent externo
   lanza `SecurityException` (SO 52623051 y manifest Pithus). Solo guía manual.
2. **Candado en recientes**: no existe API pública; es un gesto del launcher/SystemUI
   (DKMA). `startLockTask()` es screen pinning/kiosk, no el candado.
3. **Estado de auto-inicio**: no hay permission ni query. No se puede "activar" ni
   "leer"; solo abrir la pantalla y confiar en el usuario.
4. **Hiber de Transsion** (`com.android.server.am.hiber`, dentro de system_server):
   congela y "proxya" alarmas/jobs/servicios incluso con Doze exento
   (https://github.com/urbandroid-team/dont-kill-my-app/issues/3800). No hay
   componente ni ajuste AOSP que lo controle.
5. **Exención AOSP de batería**: en HiOS/XOS no desactiva "Power Saving Management"
   ni Hiber (DKMA + issue 3800). Es necesaria, no suficiente.
6. **Pantallas de Phone Master con `android:exported=false`** (p. ej.
   `MemoryAccelerateWhitelistActivity2`): `startActivity` explícito falla con
   `SecurityException`; no reintentar en bucle.

---

## 4. Recomendación de implementación para `VendorSettings.kt`

Estado actual (`oem/VendorSettings.kt:81-90`): Infinix/Tecno solo tiene
`settingsIntent = appDetailsIntent()`. Propuesta **sin cambiar aún el código**:

1. Añadir una **lista ordenada de candidatos** por rol (mismo patrón que Samsung/ZTE:
   `resolveActivity` antes de lanzar y fallback a la ficha de la app). El llamador
   (Onboarding/diagnóstico) debe envolver `startActivity` en `try/catch`
   (`ActivityNotFoundException` y `SecurityException`).

```kotlin
// Auto-inicio: primer candidato resoluble gana.
private fun transsionAutostartCandidates(): List<Intent> = listOfNotNull(
    autostartIntent("com.transsion.phonemaster",
        "com.cyin.himgr.autostart.AutoStartActivity"),                       // VERIFIED
    autostartIntent("com.transsion.phonemanager",
        "com.itel.autobootmanager.activity.AutoBootMgrActivity"),            // LIKELY
    autostartIntent("com.mediatek.autobootcontroller",
        "com.mediatek.autobootcontroller.AutoBootAppManageActivity"),        // LIKELY
)

// Batería por app.
private fun transsionBatteryCandidates(): List<Intent> = listOfNotNull(
    autostartIntent("com.transsion.batterylab",
        "com.android.settings.batterysave.BatteryLab\$TranAppSavingActivity"), // VERIFIED
    autostartIntent("com.transsion.batterylab",
        "com.transsion.powersave.activity.PowerSavaMainActivity"),             // VERIFIED/LIKELY
    autostartIntent("com.transsion.phonemaster",
        "com.cyin.himgr.powermanager.views.activity.PowerManagerActivity"),    // LIKELY
)

private fun firstResolvable(pm: PackageManager?, candidates: List<Intent>): Intent? =
    candidates.firstOrNull { pm != null && runCatching {
        pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null
    }.getOrDefault(false) }
```

2. En `guideFor("infinix"/"tecno")`:
   - `settingsIntent = firstResolvable(pm, transsionAutostartCandidates()) ?: appDetailsIntent()`
   - `secondaryIntent = firstResolvable(pm, transsionBatteryCandidates())
     ?: Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)`
3. **No** añadir candidatos a pantallas no exportadas (protegidas, freeze, recientes);
   esos quedan solo como pasos de texto en `steps`.
4. **No** implementar `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` salvo que se
   declare el permiso y el equipo acepte la política Play; preferir
   `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` como fallback sin permisos.
5. Añadir un log de diagnóstico cuando un candidato resuelva (marca del componente)
   para evidencia de campo; si ninguno resuelve, registrar "fallback a ficha de app".
6. Clasificación interna sugerida: tratar todos los candidatos como "mejor esfuerzo";
   `OemGuidanceProvider` debe seguir devolviendo VERIFY (no hay forma de comprobar el
   resultado). Coherente con `docs/OEM_COMPATIBILITY.md` §5.

### Verificación pendiente (honesta)

En esta sesión no hubo dispositivo conectado (adb y device-MCP no respondieron), así
que los componentes Pithus están verificados **en el APK**, no en ROM XOS X6531 real.
Antes de cerrar: probar en `santiago` (X6531) el orden de candidatos y anotar cuál
resuelve (`resolveActivity`) + apertura real. No usar ADB en producción; el log de
diagnóstico de la app es suficiente.

---

## 5. Apéndice de evidencia (URLs)

- DKMA Tecno (award 3/5, sin solución de dev): https://dontkillmyapp.com/tecno · API: https://dontkillmyapp.com/api/v2/tecno.json · Infinix no tiene página propia (404).
- Guía de campo Infinix/Tecno: https://github.com/urbandroid-team/dont-kill-my-app/issues/474 y #213.
- Hiber Transsion (alarm proxying, issue 3800): https://github.com/urbandroid-team/dont-kill-my-app/issues/3800
- AutoStarter #47 (Tecno, `dumpsys`, PhoneMaster y MediaTek): https://github.com/judemanutd/AutoStarter/issues/47
- Gist OEM autostart (GransonO): https://gist.github.com/GransonO/8fd3bfa0521c914597e46ebaf3ae39d3
- Plugin Flutter con Infinix/Tecno/itel: https://github.com/seena98/auto_start_flutter/blob/main/android/src/main/kotlin/co/techFlow/auto_start_flutter/AutoStartIntents.kt
- transistorsoft `AutoBootMgrActivity`: https://github.com/transistorsoft/flutter_background_geolocation/issues/733
- Gist POWERMANAGER_INTENTS con Transsion: https://gist.github.com/p32929/41e7af650f6a2c11e9306ab600fb9b03
- Pithus Phone Master: 5.1.6 https://beta.pithus.org/report/bb8131a2b891ccd39295f1df157f7d2f30d161dba318392f692c8fe8b7bce69d · 5.2.7 https://beta.pithus.org/report/0134630b56953cb1ca79e818f03074204fc48f24b532a1fc7d82ac225e23e4dc · 6.1.3 https://beta.pithus.org/report/68ede4017ca4babe8f29b3ee9e88f883ed42ae1e61cad6f3090ca6272f57e3fb
- Pithus Battery & Power Saving (`com.transsion.batterylab`): 3.1.2.116 https://beta.pithus.org/report/472743bf21947a092a625b963e08c9873e67a2a85d84fe764a08c861f6c04a2e · 3.1.2.038 https://beta.pithus.org/report/46710cf43493f970c8e33be0deb04ff5559aea0bd0a0b3e620b35d6e3eee3a26 · 5.1.0.079 https://beta.pithus.org/report/b09da4a71008d86c39e114069863fc25eac7391f79ea16b78e8cf1ea0a966c12
- SO Protected apps Infinix (SecurityException): https://stackoverflow.com/questions/52623051/how-to-start-protected-apps-activity-in-infinix-phone-programatically
- Traccar Client (usa AutoStarter, sin soporte Transsion; fallback a `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`): https://github.com/traccar/traccar-client-android (`BatteryOptimizationHelper.kt`, `AutostartReceiver.kt` + `BOOT_COMPLETED` en manifest).
- GPSLogger (sin lógica OEM; `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` en `GpsMainActivity.java` y chequeos en `Systems.java`): https://github.com/mendhak/gpslogger
- Karani (no hay componente público Transsion; el fallback es el camino primario): https://www.codewithkarani.com/blog/oem-autostart-battery-optimisation-android-deep-links
- Guía daemons MTK/Transsion (rutas de menú modernas): https://github.com/hoshiyomiX/transsion-mtk-init-guide/blob/main/08-device-reports/known-issues.md
- `com.transsion.batterylab` = sección Battery & Power Saving: https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation/issues/1325
- Frameworks AOSP `LockTaskController` (pinning ≠ candado de recientes): https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/wm/LockTaskController.java
