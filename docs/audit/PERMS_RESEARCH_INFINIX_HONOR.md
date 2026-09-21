# PERMS_RESEARCH_INFINIX_HONOR.md — Deep links de supervivencia: Infinix/Tecno (XOS/HiOS) y Honor (MagicOS)

> Investigación de solo lectura (2026-09-19). Complementa y cruza con
> `docs/audit/OEM_RESEARCH_INFINIX_TECNO.md`, `OEM_RESEARCH_HONOR_HUAWEI.md` y
> `PERMS_RESEARCH_ANDROID.md`. Objetivo: pantallas exactas abribles por Intent, estado
> leíble/no leíble, y KILL vs FREEZE. Nada de código de producción.
> Leyenda de confianza: **VF** = verificado en campo por fuente citada (no por nosotros) /
> **LIKELY** = componente exacto con 1-2 fuentes / **UNKNOWN** = sin componente público.
> Método: grep.app sobre repos públicos, grep.app/API GitHub, Pithus (manifests), DKMA,
> soporte oficial, XDA/SO/Hovatek. **4PDA devuelve HTTP 403 a fetchers** (no verificable
> automáticamente; se cita como fuente comunitaria no confirmada).

## A) Transsion (Infinix XOS / Tecno HiOS / itel) — namespace único `com.transsion.*`

| Pantalla | Paquete / clase EXACTA | Acción / extra | Evidencia (URL) | ¿Abrible? ¿Verificable? | Confianza |
|---|---|---|---|---|---|
| Phone Master → Toolbox → **Auto-start management** | `com.transsion.phonemaster` / `com.cyin.himgr.autostart.AutoStartActivity` | Intent explícito; implícita: action `com.cyin.himgr.applicationmanager.view.activities.AUTO_START_ACTIVITY` + CATEGORY_DEFAULT | OSS: https://github.com/seena98/auto_start_flutter/blob/master/android/src/main/kotlin/co/techFlow/auto_start_flutter/AutoStartIntents.kt (L57-58) · https://github.com/codehasan/Current-Activity/blob/main/app/src/main/kotlin/io/github/ratul/topactivity/utils/AutostartUtil.kt (L88-90) · https://github.com/kawaiiDango/pano-scrobbler/blob/main/composeApp/src/androidMain/kotlin/com/arn/scrobble/utils/AndroidStuff.kt (L46) · campo: https://github.com/judemanutd/AutoStarter/issues/47 · Pithus exported=true: https://beta.pithus.org/report/bb8131a2b891ccd39295f1df157f7d2f30d161dba318392f692c8fe8b7bce69d | Sí (exported). No leíble por API. En algunas ROMs "component not found" (issue #47) | **VF** (componente/exported) |
| Phone Manager (variante itel / ROMs sin Phone Master) | `com.transsion.phonemanager` / `com.itel.autobootmanager.activity.AutoBootMgrActivity` | Intent explícito | OSS: pano-scrobbler AndroidStuff.kt (L47) · https://github.com/transistorsoft/flutter_background_geolocation/issues/733 | LIKELY (no en APK Play "PhoneMaster Services") | **LIKELY** |
| Ajustes → Seguridad → **Autostart management** (base MediaTek) | `com.mediatek.autobootcontroller` / `.AutoBootAppManageActivity` | action `com.mediatek.autobootcontroller.AUTO_BOOT` | Campo `dumpsys activity` en AutoStarter #47 (2º reporte) | Sí, en builds antiguos MTK | **LIKELY** |
| **Battery Lab → Battery Saving Settings → per-app** | `com.transsion.batterylab` / `com.android.settings.batterysave.BatteryLab$TranAppSavingActivity` | Intent explícito (exported=true) | Pithus: https://beta.pithus.org/report/46710cf43493f970c8e33be0deb04ff5559aea0bd0a0b3e620b35d6e3eee3a26 · DKMA: https://dontkillmyapp.com/tecno · paquete = sección Ajustes: https://github.com/MuntashirAkon/android-debloat-list/issues/73 | Sí (exported). Es la lista per-app de "Power Saving Management" | **VF** componente / LIKELY rol exacto |
| Ahorro global / ultra ahorro | `com.transsion.batterylab` / `com.transsion.powersave.activity.PowerSavaMainActivity` y `...PowerSavaLauncherActivity` | Intent explícito | Pithus (mismos reportes) · `com.transsion.powercenter` = Power Center (WhatsApp/ultra): UAD `oem.json` L18310 | Sí (exported) | **VF** existencia / LIKELY rol |
| Phone Master → **Power Manager** | `com.transsion.phonemaster` / `com.cyin.himgr.powermanager.views.activity.PowerManagerActivity` (+ `...OsPowerActivity`) | Intent explícito | Pithus 5.1.6/6.1.3 (exported=true) | Sí (exported) | **LIKELY** |
| Apps protegidas / whitelist de RAM (Phone Master) | `com.cyin.himgr.applicationmanager.view.activities.MemoryAccelerateWhitelistActivity` | — | NO exported (Pithus 5.1.6); SecurityException: https://stackoverflow.com/questions/52623051 | **NO abrible** por Intent: solo guía manual | **VF** (bloqueada) |
| Lista de apps a **congelar** (Phone Master) | `com.cyin.himgr.applicationmanager.view.activities.AddFreezeAppActivity` | Intent explícito (exported=true) | Pithus manifest | Abrible pero contraproducente (es para congelar) | **LIKELY** |
| Candado en Recientes | — | — | DKMA: gesto manual 🔒 · `startLockTask()` es kiosco, no candado | **NO existe API** | **VF** (no existe) |
| "Otros permisos" estilo MIUI | **No existe** activity Transsion equivalente. Ruta real: Ajustes → Apps → Special access → *Background activity permissions*; y el toggle **Autostart** vive en la ficha de la app (Settings → Apps → app → Autostart) | `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` (+`package:`) y Special access AOSP | https://express.ms/en/faq/background-work-fix (secciones Infinix) · https://crm.shipkloud.com/phone-check/infinix | Solo pantallas AOSP; el "other permissions" MIUI no tiene contraparte | **LIKELY** (no existe) |

**Contexto ROM (Transsion):** HiOS 12+/XOS 13+ renombran menús: *Battery Lab* y *Power Marathon*
(icono de `com.transsion.batterylab.icon`; UAD: "Power Marathon, it's only icon app") y Phone Master
puede no venir en itel. XOS antiguo: *XManager → Auto-start manager* y *XPower → Advanced settings*
(https://support.mobile-tracker-free.com/hc/en-us/articles/360009769373-Infinix-Android-6-7).
Existe además un daemon de sistema **Hiber** (`com.android.server.am.hiber`, dentro de system_server)
que congela/proxya alarmas y jobs **aunque Doze esté exento**: https://github.com/urbandroid-team/dont-kill-my-app/issues/3800.
Campo XOS 12: muerte en background incluso con batería sin restricciones + auto-inicio + candado:
https://github.com/Mimir-IM/MimirForAndroid/issues/45.

## B) Honor (MagicOS 7/8/9/10, base Huawei) — paquete vigente `com.hihonor.systemmanager`

| Pantalla | Paquete / clase EXACTA | Acción / extra | Evidencia (URL) | ¿Abrible? ¿Verificable? | Confianza |
|---|---|---|---|---|---|
| **Inicio de aplicaciones / Gestión del inicio / App launch** (Auto-inicio, Inicio secundario, Ejecutar en segundo plano) | `com.hihonor.systemmanager` / `.startupmgr.ui.StartupNormalAppListActivity` | Intent explícito | OSS: https://github.com/wxpusher/wxpusher-app/blob/master/androidApp/src/androidMain/kotlin/com/smjcco/wxpusher/utils/WxpJumpPageUtils.kt (L129-133) · https://github.com/kyujin-cho/Bada/blob/main/app/src/main/kotlin/dev/bluehouse/bada/battery/BatteryOptimizationOemHelper.kt (L199-210) · https://github.com/Aliothmoon/MAA-Meow/blob/main/app/src/main/java/com/aliothmoon/maameow/schedule/service/AutoStartHelper.kt (L81-82) · https://github.com/seena98/auto_start_flutter (L40-42) · https://github.com/DrewCyber/yggstack-android/blob/main/app/src/main/java/link/yggdrasil/yggstack/android/utils/AutostartHelper.kt (L154-156) | Sí. Pantalla **de lista** (no recibe paquete). No leíble por API; sí por Accessibility (leyendo `isChecked`) | **VF** componente/apertura OSS |
| Variante "App control" (legacy/port EMUI) | `com.hihonor.systemmanager` / `.appcontrol.activity.StartupAppControlActivity` | Intent explícito | auto_start_flutter L42 | Probable; puede no existir en MagicOS 9 | **LIKELY** |
| Apps protegidas (legacy MagicUI) | `com.hihonor.systemmanager` / `.optimize.process.ProtectActivity` | Intent explícito | auto_start_flutter L41 · AutoStarter (mapeo Huawei): https://github.com/judemanutd/AutoStarter/blob/master/autostarter/src/main/java/com/judemanutd/autostarter/AutoStartPermissionHelper.kt | Probable; puede ser pantalla muerta (EMUI 8+) | **LIKELY** |
| **Batería / consumo por app → Gestión de inicio** ("Detalle de consumo") | `com.hihonor.systemmanager` / `.power.ui.DetailOfSoftConsumptionActivity` | **Protegida**: requiere `HW_SIGNATURE_OR_SYSTEM` para apertura directa; se llega tocando la fila de la app desde la lista de batería | hushd (campo Magic V2, MagicOS 7/8): https://github.com/Labushuya/hushd/blob/main/core/automation/src/main/kotlin/dev/labushuya/hushd/core/automation/oem/HonorMagicOsProfile.kt (L13-22, L116-123) + perfil https://github.com/Labushuya/hushd/blob/main/app/src/main/res/raw/honor_magicos.json · MagicOS 9: https://github.com/ityulong/LegitKeepAlive/blob/main/keepalive-core/src/main/assets/legitkeepalive/honor.json (L53-64, "耗电详情-启动管理-允许后台活动") | Directa **NO** (protegida); indirecta sí (tap en fila). Verificable con Accessibility (resource-ids) | **VF** protegida / LIKELY apertura directa |
| Lista de batería (punto de entrada público) | `com.hihonor.systemmanager` / `.power.ui.HwPowerManagerActivity` | Intent explícito (público, sin permiso) | hushd HonorMagicOsProfile.kt (L135-147) + honor_magicos.json | Sí (campo). Igual en Huawei `com.huawei.systemmanager/.power.ui.HwPowerManagerActivity`: https://github.com/trah01/Accnotify/blob/main/app/src/main/java/com/trah/accnotify/util/KeepAliveHelper.kt (L419-420, L464-465) | **VF** campo Honor / **LIKELY** Huawei |
| Toggles exactos de la pantalla de inicio (por app) | resource-ids: `com.hihonor.systemmanager:id/switch_auto_management` (master), `switch_startup`, `switch_secondary_launch`, `switch_background_running`; diálogo `android:id/alertTitle`/`button1` | Taps vía Accessibility | honor_magicos.json (ver arriba) | Solo lectura/escritura vía Accessibility; no hay API | **VF** campo (hushd) |
| Optimización de batería AOSP (funciona en MagicOS) | `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` / `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Intent implícito | Docs Android + soporte Honor: https://www.honor.com/za/support/content/en-us00428704 | Sí; leíble con `PowerManager.isIgnoringBatteryOptimizations()` | **VF** |
| PowerGenie / PowerKit Honor (hibernación) | paquetes `com.hihonor.powergenie` y `com.hihonor.android.powerkit`; servicios `...powergenie.core.hibernation.PGASHStateService`, `PowerKitService` | **Sin UI pública** | crossly (MagicOS 10, campo): https://github.com/crossly/honor-systemmanager-patch (perfiles `background`/`powerkit`) | No hay Intent que ofrecer; se documenta pero no se enlaza | **VF** servicios / UNKNOWN UI |
| "Otros permisos" estilo MIUI | **No existe**. Ruta real: Ajustes → Aplicaciones → (3 puntos) → Acceso especial; o "Buscar en Ajustes: Launch/Inicio de aplicaciones" | `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` | https://express.ms/en/faq/background-work-fix (Honor M8/M9) · Honor https://www.honor.com/global/support/content/en-us00406916 | Sin pantalla "otros permisos" | **LIKELY** (no existe) |

**Diferencias MagicOS vs EMUI/HarmonyOS legacy.** Hasta 2020 Honor reportaba `MANUFACTURER="HUAWEI"`
y usaba `com.huawei.systemmanager`; tras la separación (nov-2020) MagicOS usa `com.hihonor.systemmanager`
(evidencia de paquete en MagicOS 9/10: LegitKeepAlive `honor.json` y crossly). EMUI/HarmonyOS Android
siguen en `com.huawei.*`; HarmonyOS NEXT ya no ejecuta APK. Los intents Honor↔Huawei son homólogos
pero **no intercambiables**: la app debe probar `hihonor` primero y Huawei solo como fallback
(`kyujin-cho/Bada` lo hace así). EMUI 2021+ puede bloquear `startupmgr` con `SecurityException`
(permiso de firma `com.huawei.permission.external_app_settings.USE_COMPONENT`; declararlo no basta):
https://stackoverflow.com/questions/68100440 · https://stackoverflow.com/questions/76212870 ·
https://github.com/judemanutd/AutoStarter/issues/91.

## C) Ambos: lo abrible, lo bloqueado y la LECTURA de estado

| Tema | Respuesta corta | Evidencia |
|---|---|---|
| Pantallas **abribles** | Transsion: autostart PhoneMaster, Battery Lab per-app/global, Power Manager, AddFreeze. Honor: StartupNormalAppList, HwPowerManager, StartupAppControl/Protect (LIKELY) | tablas A/B |
| Pantallas **NO abribles** | Transsion: Protected apps (`MemoryAccelerateWhitelistActivity`, no exported → `SecurityException`, SO 52623051). Honor: `DetailOfSoftConsumptionActivity` (signature/system) y cualquier pantalla interna de Phone Master/SystemManager no exportada. Candado de Recientes: no hay API en ninguna | SO 52623051 · hushd L13-22 · DKMA tecno |
| Leer si auto-inicio está activo | **No hay API** en ninguna ROM. `resolveActivity()` solo dice si la pantalla existe. AutoStarter solo comprueba existencia de paquete/activity al pedir permiso | https://stackoverflow.com/questions/56378810 (respuesta: imposible) · AutoStarter `isAutoStartPermissionAvailable()` |
| Leer "no optimizado" (Doze) | Sí: `PowerManager.isIgnoringBatteryOptimizations(pkg)` (API 23+) | Docs AOSP |
| Leer "background restricted" | Parcial: `ActivityManager.isBackgroundRestricted()` (API 28+) y AppOps `RUN_IN_BACKGROUND` del propio uid; **no** reflejan whitelists OEM (App launch/Battery Lab) | Docs AOSP |
| Leer congelado (freeze) | **Imposible por API pública.** `Process.isFrozen()` es hidden/non-SDK. Solo: (a) `ActivityManager.getHistoricalProcessExitReasons()` con `REASON_FREEZER` (**API 33+**, kill por freezer), (b) heurística de saltos de tiempo `elapsedRealtime` vs timestamps propios, (c) QA: `adb shell dumpsys activity processes | grep -i frozen` | https://developer.android.com/reference/android/app/ApplicationExitInfo (REASON_FREEZER, API 33) |
| Leer con Accessibility | Sí, es la única vía real de lectura/escritura de toggles: hushd lee `switch_startup`/`switch_background_running` y marca estados ENABLED/DISABLED/UNKNOWN; requiere usuario habilite A11y | hushd `HonorMagicOsProfile.kt` + `feature/applist/AppListViewModel.kt` (`AutostartStatus`) |

### KILL vs FREEZE (por qué importa un FGS de ubicación)

1. **Freezer AOSP (Android 11+):** los procesos *cached* entran a un cgroup congelado; en
   **Android 14+ se congelan 10 s después de pasar a `cached`** y se descongelan al recibir intent,
   job o volver a foreground. Un **FGS activo no está en estado cached**, por lo que el freezer
   estándar no debería aplicarle: https://source.android.com/docs/core/perf/cached-apps-freezer
2. **Freeze de fabricante (más agresivo):** EMUI/Honor ya congelaba procesos con el cgroup freezer
   de Phone Manager (detección clásica: `/proc/<pid>/wchan` contiene `__refrigerator`):
   https://xdaforums.com/t/guide-huaweis-background-task-freezer-and-how-to-turn-it-off.3728298
   En MagicOS 10 hay hibernación propia (`PGASHStateService`) y `BgPowerManagerService`:
   https://github.com/crossly/honor-systemmanager-patch
3. **Cómo evitarlo (lo único que está en manos del usuario):** batería **sin restricciones / No
   permitir optimización**, App launch en **manual** con los 3 toggles ON, fijar en Recientes, y en
   Transsion además sacar la app de las listas de Phone Master/Battery Lab (Power Saving Management
   for apps). Aun así hay reportes de campo (XOS 12, Mimir #45) donde el daemon de sistema sigue
   matando: el freeze/kill OEM **no es evitable por API**, solo mitigable.
4. **Recomendación de arquitectura:** FGS `location` + `foregroundServiceType` + `stopWithTask=false`
   + watchdog externo (AlarmManager/WorkManager+Boot) y onboarding guiado; medir con
   `ApplicationExitInfo` (Android 11+) para evidencia.

### Detección de fabricante / ROM

- Transsion: `Build.BRAND`/`MANUFACTURER` ∈ {infinix, tecno, itel}; los APK están firmados por
  XOS/HiOS (Pithus). No hay propiedad `ro.build.version.xos` con evidencia pública (0 hits en grep.app).
- Honor: `Build.BRAND` contiene "honor" (evaluar antes de Huawei); MagicOS se detecta con la
  propiedad `ro.build.version.magic` (patrón `^(MagicOS[ _])?9\..*`) — así lo hace LegitKeepAlive
  `honor.json`. No usar `ro.build.version.emui` en Honor moderno.

## D) Repos con cadenas exactas (búsqueda pedida)

**`com.transsion.phonemaster` (grep.app/GitHub):**
1. kawaiiDango/pano-scrobbler — `AndroidStuff.kt` L46 (`com.cyin.himgr.autostart.AutoStartActivity`) y L47 (`com.transsion.phonemanager` → `com.itel.autobootmanager...`): https://github.com/kawaiiDango/pano-scrobbler
2. codehasan/Current-Activity — `AutostartUtil.kt` L88-90 (componente + action implícita): https://github.com/codehasan/Current-Activity
3. ModalityDance/PalmClaw — `AndroidUiHelpers.kt` L294-296: https://github.com/ModalityDance/PalmClaw
4. seena98/auto_start_flutter — `AutoStartIntents.kt` L57-58: https://github.com/seena98/auto_start_flutter
5. Listas de debloat (contexto de paquetes): 0x192/universal-android-debloater `uad_lists.json` L19965; ImKKingshuk/BloatwareHatao `packages/infinix.json` L913; MuntashirAkon/android-debloat-list `oem.json` L18300
6. Gist comunitario (Transsion/China -> `com.cyin.himgr...`): https://gist.github.com/GransonO/8fd3bfa0521c914597e46ebaf3ae39d3

**`com.hihonor.systemmanager` (grep.app/GitHub):**
1. wxpusher/wxpusher-app — `WxpJumpPageUtils.kt` L129-133: https://github.com/wxpusher/wxpusher-app
2. Aliothmoon/MAA-Meow — `AutoStartHelper.kt` L80-82: https://github.com/Aliothmoon/MAA-Meow
3. kyujin-cho/Bada — `BatteryOptimizationOemHelper.kt` L202-204 (y fallback Huawei): https://github.com/kyujin-cho/Bada
4. seena98/auto_start_flutter — `AutoStartIntents.kt` L40-42 (3 activities Honor): https://github.com/seena98/auto_start_flutter
5. qianqianhhh2/jianyin — `ManufacturerCompat.kt` L128-130; yanglongyun/one — `SettingsActivity.kt` L348; DrewCyber/yggstack-android — `AutostartHelper.kt` L154-156
6. Labushuya/hushd — perfil MagicOS campo (HwPowerManager + DetailOfSoftConsumption + resource-ids): https://github.com/Labushuya/hushd
7. ityulong/LegitKeepAlive — `honor.json` L53-64 (MagicOS 9): https://github.com/ityulong/LegitKeepAlive
8. crossly/honor-systemmanager-patch — servicios/paquetes MagicOS 10 (PowerKit, hibernación): https://github.com/crossly/honor-systemmanager-patch

**Correcciones de nombres:** no existen repos "Heckar" ni "AutoStarter de jenly/jetradar" (0 resultados
en la API de GitHub). El referente real de DKMA es `urbandroid-team/dont-kill-my-app`; la librería de
auto-inicio es `judemanutd/AutoStarter` (solo cubre Huawei/Honor vía paquete Huawei, **no** Transsion)
y su port Flutter `aminnez/flutter_autostart_android` + `seena98/auto_start_flutter` (este sí Transsion).

## E) Fuentes oficiales / comunitarias y límites

- DKMA: https://dontkillmyapp.com/tecno (Infinito no tiene página propia: `/infinix` → 404) y
  https://dontkillmyapp.com/huawei (no hay `/honor`: 404). API v2: `/api/v2/tecno.json`.
- Honor oficial: https://www.honor.com/za/support/content/en-us00428704 · https://www.honor.com/global/support/content/en-us00406916 · https://www.honor.com/uk/support/content/en-us00685514 · Huawei (legacy Honor): https://consumer.huawei.com/en/support/content/en-us15848660
- XDA: freezer Huawei https://xdaforums.com/t/guide-huaweis-background-task-freezer-and-how-to-turn-it-off.3728298 · tag Infinix https://xdaforums.com/tags/infinix (no hay hilo canónico de deep-links Transsion).
- Hovatek (foro): https://www.hovatek.com/forum/thread-23758.html (sin info de intents; se cita como foro).
- 4PDA: **403 a fetchers**; hilo de firmware Infinix Note 30 https://4pda.to/forum/index.php?showtopic=1073974 (no verificable; no hay hilo de intents confirmado por nosotros).
- Telegram comunitario (lista de system apps Transsion, uso de Activity Launcher): https://t.me/s/tecnoinfinixthemes
- Fix de referencia de Android 14 sobre FGS/freezer: `REASON_FREEZER` API 33 (docs arriba).

### Pendiente de verificación en campo (honesto)
1. XOS 13/14 real: ¿resuelve `com.cyin.himgr.autostart.AutoStartActivity` y `BatteryLab$TranAppSavingActivity`?
   (los manifests Pithus son de PhoneMaster 5.x/6.x y BatteryLab 3.x/5.x, no de la ROM XOS 13+ exacta).
2. MagicOS 9 (Honor X7d/LGN-LX3): apertura directa de `StartupNormalAppListActivity` y si
   `HwPowerManagerActivity` + tap de fila sigue funcionando (hushd verificó MagicOS 7/8 en Magic V2).
3. Confirmar en dispositivo si el freezer OEM de Honor mata FGS o solo cached, con
   `adb shell dumpsys activity processes | grep -iE "frozen|importance"` (solo QA).
