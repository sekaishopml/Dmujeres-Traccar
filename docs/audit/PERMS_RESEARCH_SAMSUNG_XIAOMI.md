# PERMS RESEARCH — SAMSUNG (One UI) & XIAOMI (MIUI/HyperOS)

Auditoría de solo lectura (sin código de producción). Fecha: 2026-09-19.
Objetivo: qué pantallas de ROM se pueden abrir por Intent desde `com.dmujeres.traccar`
(sideload), con qué paquete/clase/extra exactos, qué es verificable y qué no.
Leyenda: **VERIFIED** = 2+ fuentes independientes o fuente oficial + uso OSS ·
**LIKELY** = misma familia de ROM, sin confirmación versionada reciente ·
**UNKNOWN** = sin evidencia reproducible (no usar como primario).
La columna *Evidencia* usa etiquetas `[S#]` resueltas en **§Fuentes**.

## 0. Detección de fabricante

| Qué | Cómo | Confianza |
|---|---|---|
| Xiaomi/Redmi/POCO | `Build.MANUFACTURER` ∈ {xiaomi, redmi, poco, blackshark} | VERIFIED |
| Samsung | `Build.MANUFACTURER` == "samsung" | VERIFIED |
| MIUI vs HyperOS | `ro.miui.ui.version.name` (MIUI) vs `ro.mi.os.version.name` (HyperOS, prop reportada, no documentada) | MIUI VERIFIED / HyperOS UNKNOWN |

---

## A. XIAOMI / REDMI (MIUI 12–14, HyperOS 1–2)

### A.1 Tabla pantalla → intent → evidencia → verificable → confianza

| # | Pantalla | Paquete / clase exacta | Extra / acción | Evidencia | ¿Verificable? | Confianza |
|---|---|---|---|---|---|---|
| A1 | **Auto-inicio** (lista clásica) | `com.miui.securitycenter` / `com.miui.permcenter.autostart.AutoStartManagementActivity` | — (abre lista; usuario toca switch) | [S1][S2][S3][S11] | No, solo abrir | VERIFIED MIUI 10–14 · LIKELY HyperOS |
| A2 | **Auto-inicio por acción** | — | `Intent("miui.intent.action.OP_AUTO_START").addCategory(DEFAULT)` | [S4][S5] | No, solo abrir | VERIFIED MIUI · LIKELY HyperOS |
| A3 | **Editor de permisos / "Otros permisos"** (contiene "Inicio automático en bg" MIUI14+ y "Mostrar ventanas emergentes") | `com.miui.securitycenter` / `com.miui.permcenter.permissions.PermissionsEditorActivity` (MIUI 8+); fallback `…permissions.AppPermissionsEditorActivity` (V5–V7) | `miui.intent.action.APP_PERM_EDITOR` + `extra_pkgname=<pkg>` (V5: `extra_package_uid`) | [S6][S7][S8][S9] | No, solo abrir | VERIFIED MIUI · LIKELY HyperOS |
| A4 | Fallback del editor (sin clase) | `setPackage("com.miui.securitycenter")` | `APP_PERM_EDITOR` + `extra_pkgname` + `extra_package_uid` | [S5][S10] | No, solo abrir | VERIFIED |
| A5 | **Batería por app → "Sin restricciones"** | `com.miui.powerkeeper` / `com.miui.powerkeeper.ui.HiddenAppsConfigActivity` | `package_name=<pkg>` (variante `packageName`) | [S12][S13][S14][S15] | No, solo abrir | VERIFIED MIUI · LIKELY HyperOS |
| A6 | Lista "apps ocultas" de batería | `com.miui.powerkeeper` / `com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity` | — | [S12] | No, solo abrir | LIKELY |
| A7 | Lista batería por acción | — | `Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").addCategory(DEFAULT)` | [S16][S4] | No, solo abrir | LIKELY |
| A8 | Power center (entrada secundaria) | `com.miui.securitycenter` / `com.miui.powercenter.PowerSettings` | — | [S4] | No, solo abrir | LIKELY |
| A9 | "Inicio automático en segundo plano" (MIUI 14+) | Dentro de A3 (App permissions) — sin activity propia conocida | — | [S11] | No | VERIFIED (UI) · sin deeplink propio |
| A10 | "Ventanas emergentes en 2.º plano" / `OP_BACKGROUND_START_ACTIVITY`=10021 | Dentro de A3 | — | [S18][S19] | Solo lectura (AppOps) | VERIFIED (op) |
| A11 | "Mostrar en pantalla de bloqueo" (`OP_SHOW_WHEN_LOCKED`=10020) | Dentro de A3 | — | [S5] | No | LIKELY |
| A12 | "Bloquear en Recientes" (candado) | **No hay intent** | — | [S11] | No | VERIFIED no-abrible |

### A.2 Notas Xiaomi

- **HyperOS**: `com.miui.securitycenter` sigue presente y no desinstalable/deshabilitable
  ([S20], HyperOS 2); HyperBridge (2025–2026) exige "Autostart" + "No restrictions"
  ([S21]); la Security app v10.6.5 en HyperOS 2 cambió la UI de listas ACS y sdmaid
  tuvo que automatizar por accesibilidad ([S22]). No hay confirmación pública, versionada,
  de que `AutoStartManagementActivity` resuelva en HyperOS 2 ⇒ tratar A1–A3 como LIKELY
  en HyperOS y resolver en runtime (`queryIntentActivities`) con cadena de fallback.
- **Pantallas "protegidas"/admin**: el estado de autostart se guarda en el
  ContentProvider `com.lbe.security.miui.permmgr` y su escritura exige `euid=1000`
  ([S23]); `pm disable com.miui.securitycenter` es bloqueado por HyperOS ([S20]).
  No hay API pública para leer ni togglear; la UI de seguridad es la única vía usuario.
- **Verificación**: XomaDev/MIUI-Autostart lee el op `10008` por AppOps + bypass de
  hidden API (probado MIUI 10–14; [S2]). Ops relacionados confirmados en Telegram:
  10008/10020/10021/10023 ([S5]); lectura de 10021 por `checkOp` en LinkSheet ([S19])
  y en hyperos-fcm-fix ([S24]). El estado de batería "No restrictions" (powerkeeper)
  **no** es legible por API pública (UNKNOWN). `PowerManager.isIgnoringBatteryOptimizations`
  puede devolver true/false sin correlación con powerkeeper; en MIUI el diálogo
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` suele fallar ("permission couldn't be set", [S25]).
- 4PDA documenta la ruta manual completa (Autostart, Otros permisos, Control de
  actividad) para GPS-trackers en MIUI ([S26]).

---

## B. SAMSUNG (One UI 4–7, Android 12–16)

### B.1 Tabla pantalla → intent → evidencia → verificable → confianza

| # | Pantalla | Paquete / clase exacta | Extra / acción | Evidencia | ¿Verificable? | Confianza |
|---|---|---|---|---|---|---|
| B1 | **Battery usage limits** (hub) | acción `com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY` con `setPackage("com.samsung.android.lool")` | `activity_type` 0/1/2 | [S30] (API oficial Samsung) | No, solo abrir | VERIFIED |
| B2 | **Never sleeping apps** | B1 | `activity_type=2` | [S30][S31] | No | VERIFIED |
| B3 | **Sleeping / Deep sleeping apps** | B1 | `activity_type=0` (sleeping) / `1` (deep) | [S30][S31] | No | VERIFIED |
| B4 | **Battery / Device Care (One UI 5+)** | `com.samsung.android.lool` / `com.samsung.android.sm.battery.ui.BatteryActivity` | — | [S32][S33][S34][S35][S36] | No, solo abrir | VERIFIED OSS (4+ repos) · LIKELY One UI 7 |
| B5 | Battery (One UI ≤5 / legacy) | `com.samsung.android.lool` / `com.samsung.android.sm.ui.battery.BatteryActivity` (Android N/L: `com.samsung.android.sm`) | — | [S37][S38][S1] | No | LIKELY (versión antigua) |
| B6 | Battery en ROM China | `com.samsung.android.sm_cn` / `com.samsung.android.sm.ui.battery.BatteryActivity` | — | [S12][S39] | No | LIKELY (solo CN) |
| B7 | Battery por acción | — | `Intent("com.samsung.android.sm.ACTION_BATTERY")` | [S40][S41] | No | LIKELY |
| B8 | Autorun (solo ROM CN) | `com.samsung.android.sm_cn` / `com.samsung.android.sm.ui.ram.AutoRunActivity` (variante `…sm.autorun.ui.AutoRunActivity`) | acción `com.samsung.android.sm.ACTION_AUTO_RUN` | [S42][S43] | No | LIKELY (solo CN) |
| B9 | Lista "checkable" por clase (OSS) | `com.samsung.android.lool` / `com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity` | — | [S34] | No | LIKELY |
| B10 | **"Allow background activity"** (App info > Battery; toggle AOSP *background-restricted*) | Sin acción pública específica; `ACTION_APP_BATTERY_SETTINGS` fue **revertida** de API pública ([S44]) | `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` + `package:<pkg>` y navegar a Battery | [S44][S45] | **Lectura:** `ActivityManager.isBackgroundRestricted()` (API 28+) VERIFIED | Deeplink UNKNOWN · lectura VERIFIED |
| B11 | **"Remove permissions if app is unused"** (auto-revoke) | Solo App info; API `PackageManager.setAutoRevokeWhitelisted` exige permiso `WHITELIST_AUTO_REVOKE_PERMISSIONS` (sistema/installer) | — | [S46][S47] | No (app normal no puede ni leerlo) | VERIFIED no-abrible |
| B12 | **Optimize battery usage / lista whitelist** | `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (lista) · `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (diálogo, requiere permiso `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | `package:<pkg>` en el diálogo | [S48][S49] | `PowerManager.isIgnoringBatteryOptimizations()` VERIFIED | VERIFIED (AOSP) |
| B13 | **"Put unused apps to sleep"** (master switch) | Sin acción directa; solo dentro de Battery/Device Care | — | [S31][S50] | No | LIKELY no-abrible |
| B14 | Adaptive battery (global, Battery > More settings) | Sin action pública documentada; indirecto vía B12/B4 | — | [S50] | `getAppStandbyBucket()` da bucket, no el toggle global | UNKNOWN deeplink |

### B.2 Notas Samsung

- La única **API oficial** de deeplink es B1–B3 ([S30]); el resto son clases internas
  estables por años (B4–B6) pero **no documentadas** ⇒ siempre `try/catch` + fallback.
- La **lista de sleeping/deep-sleeping no es legible** por la app; `getAppStandbyBucket()`
  refleja buckets AOSP (incluido `RESTRICTED`), pero Samsung aplica capas propias ([S31][S50]).
  El usuario debe sacar la app de las listas manualmente (Editar apps → Remove).
- "Device Care" (`com.samsung.android.lool`) está exento de sus propias restricciones
  y puede re-añadir apps a listas tras updates ([S50][S51]); monitorear regresiones.
- Android 13+: si la app queda en estado *restricted*, el sistema **no entrega**
  `BOOT_COMPLETED` hasta abrirla ([S52]).
- One UI 6+: Samsung promete que FGS de apps target Android 14 funcionan como en AOSP
  ([S30] §5) ⇒ si la app apunta a 34+, priorizar FGS correcto y usar estos intents
  solo como refuerzo.

---

## C. Verificable vs. no verificable (resumen)

| Plataforma | Señal | API / método | ¿App normal? | Confianza |
|---|---|---|---|---|
| Xiaomi | Autostart (op 10008) | AppOps `checkOpNoThrow` por reflexión (p. ej. XomaDev; [S2]) | Sí en sideload (usa hidden API) | VERIFIED ≤MIUI14 · LIKELY HyperOS |
| Xiaomi | Background start activity (op 10021) | AppOps `checkOp` 10021 ([S19][S24]) | Sí (sideload) | LIKELY |
| Xiaomi | Foreground service op 10023 | AppOps ([S24]) | Sí (sideload) | LIKELY |
| Xiaomi | Batería "No restrictions" | Sin API pública (powerkeeper) | — | UNKNOWN |
| Samsung | Exención de optimización | `PowerManager.isIgnoringBatteryOptimizations()` | Sí | VERIFIED |
| Samsung | "Allow background activity" | `ActivityManager.isBackgroundRestricted()` | Sí | VERIFIED |
| Samsung | Bucket de standby | `UsageStatsManager.getAppStandbyBucket()` | Sí | VERIFIED |
| Samsung | Miembro de Sleeping/Deep sleeping | Sin API pública | — | UNKNOWN |
| Samsung | Auto-revoke | `PackageManager.isAutoRevokeWhitelisted` exige permiso sistema ([S46][S47]) | No | VERIFIED no-verificable |
| Ambos | Estado exacto de cada toggle de ROM | No existe API oficial | — | UNKNOWN (por diseño OEM) |

---

## D. Qué NO se puede abrir/verificar

- Xiaomi: candado en Recientes (sin intent, [S11]); togglear autostart/ops por código
  (requiere uid 1000, [S23]); estado de powerkeeper/"No restrictions" (sin API).
- Samsung: membership en listas de suspensión; activar "Never sleeping" programáticamente;
  auto-revoke (solo UI); master switch "Put unused apps to sleep".
- Ambos: cualquier acción de escritura sobre los toggles; solo lectura parcial (AppOps/
  APIs estándar) y apertura de pantallas.

## E. Inventario OSS de referencia (código exacto)

- jetradar-era AutoStarter: `judemanutd/AutoStarter` → `autostarter/…/AutoStartPermissionHelper.kt` [S34]
- Multi-OEM: `dirkam/backgroundable-android` [S16]; `pvsvamsi/Disable-Battery-Optimizations`
  (`Xiaomi.java`, `Samsung.java`) [S13][S40]; `yangchong211/YCAppTool`
  (`DefaultWhiteListProvider.java`, `AliveSettingConst.java`) [S12]
- MIUI: `XomaDev/MIUI-Autostart` [S2]; `DrKLO/Telegram/XiaomiUtilities.java` [S5];
  `LinkSheet/MiuiCompat.kt` [S19]; `dingwen07/hyperos-fcm-fix` [S24];
  `SuperMonster003/AutoJs6` (`XiaomiBackgroundPopupPermission.kt`) [S18];
  `5ec1cff/my-notes` (`miui_perms.md`) [S23]
- Samsung: `threema-ch/threema-android` (`PowermanagerUtil.java`) [S32];
  `invertase/notifee` (`PowerManagerUtils.java`) [S33];
  `kawaiiDango/pano-scrobbler` (`AndroidStuff.kt`) [S35];
  `akaMrNagar/Mindful` (`NewActivitiesLaunchHelper.kt`) [S36]
- OEM general: `urbandroid-team/dont-kill-my-app` ([S11][S50]) · sdmaid-se PR #1652 [S22]

## F. Cadenas de fallback recomendadas (orden)

1. **Xiaomi**: A2 (acción) → A1 (componente) → A3 (editor, V8/V6 con extra_pkgname) →
   A4 (`setPackage`) → `ACTION_APPLICATION_DETAILS_SETTINGS`. Batería: A5 → A8 → B12/AOSP.
2. **Samsung**: B1 con `activity_type=2` (Never sleeping) → B4 → B5 → B6 → **B12** →
   App info. No intentar B10/B11/B13 por intent.
3. Siempre `resolveActivity`/`queryIntentActivities` antes de lanzar, `try/catch`
   por versión, y en MIUI enviar `package_name` **y** `packageName` en A5.
4. Verificación emparejada: `isIgnoringBatteryOptimizations` + `isBackgroundRestricted`
   + `getAppStandbyBucket` (estándar, fiable) y `10008/10021` solo si se acepta hidden API.

---

## Fuentes

- [S1] https://stackoverflow.com/questions/48945300/how-to-open-window-of-autostart-application-for-all-devices
- [S2] https://github.com/XomaDev/MIUI-Autostart (y https://dontkillmyapp.com/xiaomi)
- [S3] https://stackoverflow.com/questions/38995469
- [S4] https://github.com/ankidroid/Anki-Android/blob/main/anki-common/src/main/kotlin/com/ichi2/anki/common/android/AdaptionUtil.kt
- [S5] https://github.com/DrKLO/Telegram/blob/master/TMessagesProj/src/main/java/org/telegram/messenger/XiaomiUtilities.java
- [S6] https://stackoverflow.com/questions/33818970
- [S7] https://stackoverflow.com/questions/59214359
- [S8] https://stackoverflow.com/questions/62084762
- [S9] https://stackoverflow.com/questions/36123349
- [S10] https://github.com/yhaolpz/FloatWindow (floatwindow/.../Miui.java)
- [S11] https://dontkillmyapp.com/xiaomi
- [S12] https://github.com/yangchong211/YCAppTool/blob/master/MonitorLib/MonitorAliveLib/src/main/java/com/yc/alive/whitelist/impl/DefaultWhiteListProvider.java
- [S13] https://github.com/pvsvamsi/Disable-Battery-Optimizations/blob/master/android/src/main/java/in/jvapps/disable_battery_optimization/devices/Xiaomi.java
- [S14] https://github.com/keymapperorg/KeyMapper (XiaomiOptimizationUseCase.kt)
- [S15] https://github.com/szkolny-eu/szkolny-android/blob/develop/app/src/main/java/pl/szczodrzynski/edziennik/utils/AppManagerIntentList.kt
- [S16] https://github.com/dirkam/backgroundable-android
- [S18] https://github.com/SuperMonster003/AutoJs6/blob/master/app/src/main/java/org/autojs/autojs/permission/XiaomiBackgroundPopupPermission.kt
- [S19] https://github.com/LinkSheet/LinkSheet/blob/master/features/devicecompat/src/main/kotlin/app/linksheet/feature/devicecompat/miui/MiuiCompat.kt
- [S20] https://forum.f-droid.org/t/hyperos-2-0-any-recommendations-for-installing-f-droid-apps/31728
- [S21] https://github.com/D4vidDf/HyperBridge (README, setup HyperOS)
- [S22] https://github.com/d4rken-org/sdmaid-se/pull/1652
- [S23] https://github.com/5ec1cff/my-notes/blob/master/miui_perms.md
- [S24] https://github.com/dingwen07/hyperos-fcm-fix (Powerkeeper EnforcementCommandPlan.kt)
- [S25] https://stackoverflow.com/questions/68305845
- [S26] https://4pda.to/forum/index.php?showtopic=832642
- [S30] https://developer.samsung.com/mobile/app-management.html
- [S31] https://www.samsung.com/ca/support/mobile-devices/galaxy-phone-sleeping-apps/
- [S32] https://github.com/threema-ch/threema-android/blob/main/app/src/main/java/ch/threema/app/utils/PowermanagerUtil.java
- [S33] https://github.com/invertase/notifee/blob/main/android/src/main/java/app/notifee/core/utility/PowerManagerUtils.java
- [S34] https://github.com/judemanutd/AutoStarter (autostarter/.../AutoStartPermissionHelper.kt)
- [S35] https://github.com/kawaiiDango/pano-scrobbler/blob/main/composeApp/src/androidMain/kotlin/com/arn/scrobble/utils/AndroidStuff.kt
- [S36] https://github.com/akaMrNagar/Mindful (NewActivitiesLaunchHelper.kt)
- [S37] https://stackoverflow.com/questions/59607933
- [S38] https://stackoverflow.com/questions/37205106
- [S39] https://github.com/xingda920813/HelloDaemon (IntentWrapper.java)
- [S40] https://github.com/pvsvamsi/Disable-Battery-Optimizations (devices/Samsung.java)
- [S41] https://github.com/chayanforyou/QuickBall (BatteryPermissionHelper.kt)
- [S42] https://github.com/rongcloud/android-push-settings-advisor (res/xml/config.xml)
- [S43] https://github.com/pppscn/SmsForwarder (SettingsFragment.kt)
- [S44] https://gitlab.e.foundation/e/os/android_frameworks_base/-/commit/5ed91dd1cdb00f5dd97a15ae8d5c660de659710c
- [S45] https://developer.android.com/reference/android/app/ActivityManager#isBackgroundRestricted()
- [S46] https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-30/+/refs/heads/main/com/android/server/pm/permission/PermissionManagerService.java
- [S47] https://developer.android.com/sdk/api_diff/30-incr/changes/android.content.pm.PackageManager
- [S48] https://developer.android.com/reference/android/provider/Settings (ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
- [S49] https://developer.android.com/reference/android/os/PowerManager#isIgnoringBatteryOptimizations(java.lang.String)
- [S50] https://dontkillmyapp.com/samsung
- [S51] https://windowsnews.ai/article/samsung-locked-this-app-to-unrestricted-batteryheres-the-fix-it-hides.440991 (Device Care exento; basado en MakeUseOf 2026-07)
- [S52] https://developer.android.com/about/versions/13/behavior-changes-13
