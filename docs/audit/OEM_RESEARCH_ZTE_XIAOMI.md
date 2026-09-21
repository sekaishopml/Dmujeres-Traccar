# INVESTIGACIÓN OEM — ZTE MyOS (Z2450) y XIAOMI MIUI/HyperOS

Auditoría de **solo lectura** (sin cambios de código, sin commits).
Fecha: 2026-09-18. Autor: agente de investigación OEM.
Alcance: cómo abrir desde una app Android las pantallas del sistema relevantes para
supervivencia en segundo plano: (1) auto-inicio, (2) ahorro de energía, (3) apps
protegidas, (4) control de actividad en segundo plano.

Equipos de referencia:
- **ZTE Blade A55 `Z2450`** (Android 14 Go, MyOS 14, Unisoc SC9863A1, 4 GB RAM) —
  equipo `qa-f0` del parque real.
- **Xiaomi/Redmi/POCO** MIUI 10→14 y HyperOS 1→3.

Reglas aplicadas: solo Intents/componentes con `try/catch` + fallback; sin
reflexión de APIs ocultas, sin AppOps por ADB, sin hacks. Verificación de cada
componente contra: dontkillmyapp.com, OSS real (Traccar Client, AutoStarter,
AnkiDroid, Threema, HyperOS FCM Fix, HyperBridge, GameSpaceReplacer, etc.),
AOSP 14 y soporte oficial ZTE/Xiaomi.

Leyenda de clasificación:

| Clase | Significado |
|---|---|
| **VERIFIED** | Componente/acción confirmado en al menos 2 fuentes independientes, o 1 fuente + campo. |
| **LIKELY** | Confirmado en la misma línea de ROM (AOSP 14 / MIUI) pero no en el modelo/versión exactos. Exige `try/catch` + fallback. |
| **UNVERIFIED** | Sin evidencia reproducible. No usar como primario. |

---

## 1. Tabla pantalla → intent exacto → evidencia → clasificación

### 1.1 ZTE MyOS (Z2450 / Blade A55, Android 14)

| # | Pantalla objetivo | Intent exacto | Evidencia | Clasificación |
|---|---|---|---|---|
| Z1 | **"Gestión inteligente" / AppSmartOptimize** (lista de apps con control de optimización) | `ComponentName("com.zte.powersavemode", "com.zte.powersavemode.appsmartoptimizer.AppSmartOptimizeActivity")` | Confirmado en campo en MyOS (Z2450): `VendorSettings.kt:104-107`; `docs/OEM_COMPATIBILITY.md:51-53`. No hay copia pública en OSS (paquete no AOSP). | **VERIFIED (campo)** en Z2450/MyOS 14; LIKELY en otras ROM MyOS |
| Z2 | Detalle/optimización por app del mismo paquete | `ComponentName("com.zte.powersavemode", "com.zte.powersavemode.appsmartoptimizer.AppSmartOptimizeDetailActivity")` | OSS: `TheRealCrazyfuy/GameSpaceReplacer`, `ViewModel.kt:83-100` (app normal, F-Droid, la lanza directo y **luego** cae a `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`). Sin extras documentados; ese repo la lanza sin `package_name`. | **VERIFIED (OSS)** exported lanzable sin permiso; alcance exacto de la pantalla (lista vs detalle) no verificado en Z2450 |
| Z3 | **Optimización de batería / whitelist** ("No optimizar") | `Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)` → `"android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"` | AOSP 14 (`platform_packages_apps_settings/android-14.0.0_r1/AndroidManifest.xml:1608-1617`): `Settings$HighPowerApplicationsActivity` exportada (`android:exported="true"`) con `intent-filter` de esa acción. ZTE soporte oficial: "Settings → Battery → Battery optimization → choose not to optimize" (`support.ztedevices.com/en-uk/why-do-background-programs-always-close`). Ya integrado en ZTE Z2/Z3 de GameSpaceReplacer. | **VERIFIED (AOSP 14)**; LIKELY en Z2450 |
| Z4 | Componente explícito de la misma pantalla | `ComponentName("com.android.settings", "com.android.settings.Settings$HighPowerApplicationsActivity")` | AOSP 14 manifest (misma entrada Z3). Usado en OSS para whitelists (HelloDaemon, SS/SSR; en Lenovo/realme como variante). | **LIKELY** en ZTE (MyOS es AOSP 14); redundante con Z3 (la acción pública ya resuelve a este componente) |
| Z5 | **Página de la app** → "Pausar actividad si no se usa", permisos, batería por app | `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:<pkg>"))` | AOSP 14 `Settings.java:1405`. El toggle "Pausar actividad si no se usa" (AOSP "Pause app activity if unused") vive **dentro de App info** y no tiene acción pública propia: no existe `ACTION_MANAGE_UNUSED_APPS` en AOSP 14 (verificado por grep en `Settings.java`). Ya es el `secondaryIntent` de ZTE en `VendorSettings.kt:108`. | **VERIFIED (AOSP 14)** |
| Z6 | "Control de IA" como pantalla separada de Z1 | — | No existe evidencia de un activity distinto. Ver §3 (la afirmación "protegida por permiso de sistema" no está respaldada por fuentes públicas). | **UNVERIFIED** |
| Z7 | Otros `com.zte.powersavemode/*` | — | No hay manifest público del APK ni referencias OSS (grep.app: 1 solo hit, Z2). Enumeración posible **en dispositivo** con `<queries>` + `queryIntentActivities` (solo lectura). | **UNVERIFIED** (no hardcodear) |

Notas ZTE:
- El paquete `com.zte.powersavemode` está clasificado en listas de debloat como
  "Battery optimization settings. Not Safe to Delete" (`MuntashirAkon/android-debloat-list`,
  `Ayudaroot/MTKRoot`), consistente con que es el gestor de optimización.
- ZTE soporte oficial documenta "Intelligent Battery Management": el sistema
  "identifica apps y escenarios y controla los recursos de las apps en segundo
  plano" (`support.ztedevices.com/en-sa/in-this---mode-the-phone-automatically-adjusts-and`).
- Zombies, Run! (soporte del vendor de apps) publica para ZTE: *Power Manager →
  Power management for apps → desactivar "Disallow auto-start", "scheduled
  background wake-up" y "Allow deep sleep"* (`support.sixtostart.com/.../ZTE-Battery-Optimisation`).

### 1.2 Xiaomi MIUI / HyperOS

| # | Pantalla objetivo | Intent exacto | Evidencia | Clasificación |
|---|---|---|---|---|
| X1 | **Auto-inicio** (lista clásica MIUI) | `ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")` | `judemanutd/AutoStarter` (`AutoStartPermissionHelper.kt`, rama Xiaomi); `traccar-client-android` (`BatteryOptimizationHelper.kt:41-42`); `AnkiDroid AdaptionUtil.kt:145-148`; `XomaDev/MIUI-autostart` (probado MIUI 10→14); `D4vidDf/HyperBridge` (proyecto HyperOS 2025-2026) y `keymapperorg/KeyMapper`. | **VERIFIED** en MIUI 10-14; **LIKELY** en HyperOS (paquete `com.miui.securitycenter` sigue existiendo en HyperOS 2/3; la UI pudo mover la perilla por app) |
| X2 | Auto-inicio **por acción** (sin componente; sobrevive a refactors) | `Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)` | `AnkiDroid AdaptionUtil.kt:140-141`; `threema-android PowermanagerUtil.java:49`; `oasisfeng/deagle Miui.java:56`; `zacharee/LockscreenWidgets AutostartUtil.kt:24-25`; `pvsvamsi/Disable-Battery-Optimizations Xiaomi.java:115`. | **VERIFIED** en MIUI; **LIKELY** en HyperOS |
| X3 | **Editor de permisos de la app** (donde MIUI 14+/HyperOS expone "Inicio automático en segundo plano") | `Intent("miui.intent.action.APP_PERM_EDITOR")` + `putExtra("extra_pkgname", pkg)` + clase: V8+ `com.miui.permcenter.permissions.PermissionsEditorActivity`; V6/V7 `com.miui.permcenter.permissions.AppPermissionsEditorActivity`; si falla, misma acción solo con `setPackage("com.miui.securitycenter")` | `alipay/SoloPi MiuiUtils.java:121-170` (V6/V7/V8 con fallback); `rRemix/APlayer`, `princekin-f/EasyFloat`, `SuperMonster003/AutoJs6` (`XiaomiBackgroundPopupPermission.kt:44-55`, cita MIUI 8+), `Launcher3-mx MiUISettingCompat.java:22-24` (`extra_pkgname`). UI: dontkillmyapp.com/xiaomi ("MIUI 14: Settings > Apps > Your app > App permissions > Background autostart"); Xiaomi Community 2025-06-30 ("Background Autostart"). | **VERIFIED** el editor por app; **LIKELY** que la misma pantalla contenga "Background autostart" según versión (el nombre/posición de la perilla varía) |
| X4 | **Batería por app → "Sin restricciones"** | `ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")` + `putExtra("package_name", pkg)` | `yangchong211/YCAppTool AliveSettingConst.java:118-126` + `DefaultWhiteListProvider.java:67-69` ("Xiaomi God Intent"); `xiaojieonly/Ehviewer MiuiOptimizationHelper.kt:213-215`; `pvsvamsi Xiaomi.java:22`; `kooritea/fcmfix` (powerkeeper es el motor de restricción). | **VERIFIED** en MIUI; **LIKELY** en HyperOS |
| X5 | Lista de batería / apps "ocultas" | `com.miui.powerkeeper/.ui.HiddenAppsContainerManagementActivity` (lista, sin extra); alternativa por acción `Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").addCategory(CATEGORY_DEFAULT)` | `YCAppTool AliveSettingConst.java:125` (constante del contenedor); `AnkiDroid AdaptionUtil.kt:150-151` (acción usada para detectar MIUI); `pvsvamsi Xiaomi.java:114` (comentario del contenedor). | **LIKELY** (componente/acción; semántica exacta de la acción no documentada) |
| X6 | Power center (entrada secundaria de batería) | `ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")` | `AnkiDroid AdaptionUtil.kt:153-157` (4.º candidato de detección MIUI). | **LIKELY** |
| X7 | `com.miui.securitycenter/com.miui.permcenter.power.PowerManagementActivity` | — | **Cero referencias** en OSS (grep.app: 0 hits), foros y búsqueda web. No aparece en AnkiDroid, Traccar, AutoStarter ni proyectos MIUI/HyperOS. | **UNVERIFIED** — no usar |
| X8 | HyperOS: nuevo paquete/acción | — | No hay paquete nuevo: Security sigue en `com.miui.securitycenter` (versión 13.x en HyperOS 2/3). HyperOS añade **AppOps de autostart**, listas ocultas `MILLET_NO_RESTRICT_APP` y "Greezer"/freezer, pero sin action pública nueva: `dingwen07/hyperos-fcm-fix` (2026) los opera por Shizuku/ADB, no por intent. | **VERIFIED** (no existe deeplink público nuevo); seguir con X1/X2/X3 + fallback |

Notas Xiaomi:
- dontkillmyapp.com **no tiene página de ZTE** (`/zte` → 404); el problema ZTE no
  está federado en esa base. Para Xiaomi sí (`/xiaomi`: auto-inicio, bloqueo en
  recientes, ahorro de batería, "MIUI optimizations" oculto en opciones de
  desarrollador).
- "Bloquear en Recientes" (candado) **no tiene intent**: se hace arrastrando la
  tarjeta de la app hacia abajo en Recientes o Security → Boost speed → Lock apps
  (dontkillmyapp).
- `com.miui.securitycenter` y `com.miui.powerkeeper` son paquetes protegidos del
  sistema; sus activities se lanzan desde apps normales en todos los OSS citados
  (sin permiso especial), por lo que son "abribles" a nivel de Android. Lo que
  **no** se puede: leer/alterar el estado del toggle (AppOps oculto; `XomaDev/MIUI-autostart`
  usa APIs ocultas — descartado aquí por política/Play).

---

## 2. Pasos manuales exactos

### 2.1 ZTE Blade A55 (Z2450, MyOS 14)

Dispositivo de referencia de la flota; ruta probada en campo.

1. **Gestión inteligente / control de IA**
   - Ajustes → **Batería** → **Gestión inteligente** (o "Administración inteligente de batería").
   - Buscar **DMujeres** → dejarla en **"Sin control"** / **Permitir** (no "Control inteligente"/"Optimizar").
   - Atajo soportado: botón de la app que dispara `AppSmartOptimizeActivity` (Z1).
2. **Ahorro de energía**
   - Ajustes → Batería → menú de 3 puntos → **Optimización de batería** → DMujeres → **No optimizar** (soporte oficial ZTE; también citado por Kaspersky para ZTE y Zombies, Run!).
   - O desde la app: `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (diálogo del sistema, ya en onboarding).
3. **Pausar actividad si no se usa**
   - Ajustes → **Aplicaciones → DMujeres** → desactivar **"Pausar actividad en la app si no se usa"** (ajuste AOSP "pause app activity if unused"; solo se llega por la página de la app, Z5).
4. **Recientes**: si MyOS ofrece candado, bloquear la tarjeta de DMujeres.
5. (Solo si el equipo lo muestra) Power Manager → "Power management for apps" → desactivar "Disallow auto-start" / "scheduled background wake-up" / "deep sleep" (variante de ROM documentada por Zombies, Run!).

### 2.2 Xiaomi MIUI 14

1. **Auto-inicio**: Seguridad (Security) → Permisos → **Auto-inicio** → DMujeres **ON** (o botón de la app → X1/X2).
2. **Batería**: Ajustes → Apps → DMujeres → **Ahorro de batería → Sin restricciones**. Variante: Ajustes → Batería y rendimiento → Ahorro de energía de apps → DMujeres → Sin restricciones.
3. **Recientes**: abrir Recientes → arrastrar la tarjeta de DMujeres hacia abajo (candado). Alternativa: Seguridad → Boost speed → engranaje → Lock apps → DMujeres.
4. **Permiso por app (MIUI 14+)**: Ajustes → Apps → DMujeres → **Permisos de la app → Inicio automático en segundo plano → Permitir** (X3 abriendo el editor de permisos reduce el camino).

### 2.3 Xiaomi HyperOS 1/2/3

1. **Auto-inicio**: Seguridad → Permisos → **Auto-inicio** → DMujeres ON
   (equivalentemente Ajustes → Apps → DMujeres → Permisos de la app → **Inicio automático en segundo plano**).
2. **Batería**: Ajustes → Apps → Administrar apps → DMujeres → **Ahorro de batería → Sin restricciones** (guías HyperOS 2024: techietut; HyperBridge docs: "Battery saver: No restrictions" + "Autostart: Allowed" en la app Seguridad).
3. **Recientes**: candado (mismo gesto que MIUI).
4. **ROMs CN / conducta agresiva**: en HyperOS el freezer ("Greezer") y las listas
   `MILLET_NO_RESTRICT_APP` no se ajustan desde UI estándar; requieren
   Shizuku/ADB (`dingwen07/hyperos-fcm-fix`) o esperar a que la app esté en la
   lista blanca del OEM. No hay deeplink público para eso.
5. Recordar: HyperOS puede seguir matando aun con la guía hecha (parches 2024-2026);
   la app debe apoyarse en FCM + keeper + prueba conductual de continuidad, no en
   confiar en los toggles.

---

## 3. Pantallas protegidas / no abribles (declaración honesta)

1. **"Gestión inteligente" ZTE — la afirmación de "protegida con permiso de
   sistema" NO está respaldada por evidencia pública.** El repo la afirma en
   `VendorSettings.kt:94-96` y `OEM_BACKGROUND_AUDIT.md:95`, pero:
   - el deep link a `AppSmartOptimizeActivity` está **confirmado en campo** en el
     propio Z2450 (misma ROM sobre la que se hace la afirmación), y
   - un app de terceros (GameSpaceReplacer) lanza `AppSmartOptimizeDetailActivity`
     sin permisos especiales.
   Conclusión: **hasta donde se puede verificar, es abrible**; no hay evidencia de
   `android:permission` de sistema sobre estas activities. Acción: en `qa-f0`,
   ejecutar una vez `resolveActivity` + `startActivity` y registrar
   `SecurityException`/`ActivityNotFoundException` si aparecen; si una build la
   protege, el `try/catch` + fallback ya lo cubre. **No** asumir "no abrible".
2. **"Pausar actividad si no se usa" (AOSP/ZTE)**: no tiene acción pública propia
   (no existe `ACTION_MANAGE_UNUSED_APPS` en AOSP 14). Solo se accede vía
   `ACTION_APPLICATION_DETAILS_SETTINGS`. No es "protegida", es "sin deeplink".
3. **Xiaomi — estado de los toggles**: las pantallas se abren (X1-X5), pero el
   **valor** del auto-inicio/batería no es legible ni escribible por API pública.
   `XomaDev/MIUI-autostart` lo logra con APIs ocultas (bloqueo de Android 9+);
   **no** se debe replicar. Tampoco hay intent para "MIUI optimizations"
   (opciones de desarrollador) ni para el candado de Recientes.
4. **HyperOS**: no hay deeplink público a AppOps de autostart, a
   `MILLET_NO_RESTRICT_APP` ni al control de "Greezer". Solo UI o Shizuku/ADB.
5. **Verificación post-guía**: no es programáticamente verificable (ya documentado
   en `OemProtection.kt` y `OEM_BACKGROUND_AUDIT.md` §4.5); la única prueba es
   conductual (continuidad con pantalla apagada).

---

## 4. Recomendación para `VendorSettings.kt` (fallback en cadena, sin hacks)

Patrón (idéntico a Traccar Client `BatteryOptimizationHelper.kt:40-58` y AutoStarter
`openAutoStartScreen`): lista ordenada → `resolveActivity(MATCH_DEFAULT_ONLY)` →
primer resoluble → `startActivity` con `try/catch (ActivityNotFoundException | SecurityException)`
→ si falla, siguiente → último eslabón siempre `ACTION_APPLICATION_DETAILS_SETTINGS`
(o `ACTION_SETTINGS`). No cambiar de hilo el `startActivity` (necesita looper UI).

### 4.1 Cambio estructural mínimo del `Guide`

Hoy `Guide` tiene `settingsIntent` + `secondaryIntent`. Para cadenas reales,
recomendado:

```kotlin
data class Guide(
    val vendorName: String,
    val title: String,
    val steps: List<String>,
    /** Cadena de intents: primero resoluble/a lanzable gana. */
    val settingsIntent: List<Intent?>? = null,   // o mantener el primario + fallbacks
    val secondaryIntent: List<Intent?>? = null,
)
```

Alternativa de menor impacto: conservar los campos actuales y añadir
`fallbackIntents: List<Intent> = emptyList()`, con un helper
`launchFirstResolvable(primary, fallbacks, context)` en `OnboardingActivity`
(hoy el fallback único está en `OnboardingActivity.kt:186-191` y `:201-208`).
No requiere tocar Samsung si se implementa como helper compartido.

### 4.2 Visibilidad de paquetes (API 30+)

`resolveActivity` de un componente de otro paquete devuelve `null` sin
visibilidad. Añadir en `AndroidManifest.xml` dentro de `<queries>`:

```xml
<package android:name="com.zte.powersavemode" />
<package android:name="com.miui.securitycenter" />
<package android:name="com.miui.powerkeeper" />
```

(Sin esto, el primer eslabón puede parecer "no disponible" aunque exista; el
`startActivity` directo seguiría funcionando y el catch haría el fallback, pero se
perdería la selección inteligente y el log forense.)

### 4.3 Cadena Xiaomi propuesta

**settingsIntent (auto-inicio / permiso de arranque):**
1. `Intent("miui.intent.action.OP_AUTO_START").addCategory(CATEGORY_DEFAULT)` (X2)
2. `ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")` (X1)
3. `Intent("miui.intent.action.APP_PERM_EDITOR")` + `setPackage("com.miui.securitycenter")` + `extra_pkgname` (X3, menos específico y más estable que fijar clase)
4. `ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")` (X6)
5. `ACTION_APPLICATION_DETAILS_SETTINGS` + `package:<pkg>` (fallback universal)

**secondaryIntent (batería):**
1. `ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")` + `package_name` (X4)
2. `ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity")` (X5, lista)
3. `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (whitelist AOSP, funciona también aquí)
4. `ACTION_APPLICATION_DETAILS_SETTINGS`

Alternativa de UI de 2 botones: "Auto-inicio" (cadena 4.3) y "Batería" (cadena
secundaria). Mantener el texto de pasos: en MIUI 14+/HyperOS la perilla clave está
en **Permisos de la app → Inicio automático en segundo plano** (X3), no solo en
Seguridad → Auto-inicio.

### 4.4 Cadena ZTE propuesta

**settingsIntent (Gestión inteligente / batería):**
1. `ComponentName("com.zte.powersavemode", "com.zte.powersavemode.appsmartoptimizer.AppSmartOptimizeActivity")` (Z1, campo)
2. `ComponentName("com.zte.powersavemode", "com.zte.powersavemode.appsmartoptimizer.AppSmartOptimizeDetailActivity")` (Z2, OSS)
3. `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (Z3, público; whitelist real)
4. `ACTION_APPLICATION_DETAILS_SETTINGS` + `package:<pkg>`

**secondaryIntent (página de la app):** `ACTION_APPLICATION_DETAILS_SETTINGS`
(cubre "Pausar actividad si no se usa", Z5). Mantener el título actual ("saca la
app del control de IA") y los 2 pasos.

Opcional (solo diagnóstico, sin lanzar nada): en `DeviceCapabilityProfile`, una
vez por versión de ROM, enumerar con `queryIntentActivities` las activities
exportadas de `com.zte.powersavemode` y volcar el nombre al reporte de
diagnóstico. Es lectura pura; permite detectar si ZTE renombra la pantalla sin
adivinar componentes.

### 4.5 Reglas de seguridad/Play

- Solo componentes/acciones públicos + `try/catch`. Nada de `setClassName` a
  activities no exportadas, nada de reflexión sobre AppOps (XomaDev), nada de
  `pm grant`, nada de multiproceso/evasión.
- Capturar `SecurityException` **y** `ActivityNotFoundException` (ZTE puede
  proteger la pantalla en builds futuras; Xiaomi puede mover el componente).
- Si ni el primario ni el fallback resuelven (p. ej. ROM sin esos paquetes),
  mostrar pasos manuales con la guía ya existente y deshabilitar el botón, en vez
  de abrir Ajustes genéricos a ciegas (el usuario se pierde).
- Telemetría sugerida: `vendorIntentResolved=<componente|accion|none>` y
  `vendorIntentFailed=<nombre|SecurityException|ActivityNotFound>` en el evento
  de onboarding (sin datos sensibles). Permite cerrar el ciclo "CONFIGURED_UNVERIFIABLE"
  con evidencia de campo por ROM.

---

## 5. Fuentes

| Fuente | Uso |
|---|---|
| AOSP 14 `platform_packages_apps_settings/AndroidManifest.xml:1608-1617` | `Settings$HighPowerApplicationsActivity` exportada + `android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS` |
| AOSP 14 `frameworks/base/core/java/android/provider/Settings.java:1405,1435-1461` | acciones públicas de batería/app details; ausencia de `ACTION_MANAGE_UNUSED_APPS` |
| `traccar/traccar-client-android` → `BatteryOptimizationHelper.kt:40-82` | patrón lista vendor → resolve → start; sin ZTE |
| `judemanutd/AutoStarter` → `AutoStartPermissionHelper.kt` | componente Xiaomi y patrón `resolveActivity` |
| `ankidroid/Anki-Android` → `AdaptionUtil.kt:140-158` | 4 candidatos MIUI (`OP_AUTO_START`, componente, `POWER_HIDE_MODE_APP_LIST`, `PowerSettings`) |
| `XomaDev/MIUI-autostart` README | estado del auto-inicio por APIs ocultas; UI probada MIUI 10-14; advertencia Play |
| `dingwen07/hyperos-fcm-fix` (README + docs, 2026) | HyperOS: AppOps autostart, `MILLET_NO_RESTRICT_APP`, "Greezer"; sin intent público |
| `D4vidDf/HyperBridge` docs (HyperOS) | pasos reales HyperOS: Autostart Allowed + Battery No restrictions en Security |
| `TheRealCrazyfuy/GameSpaceReplacer` → `ViewModel.kt:83-100` | ZTE `AppSmartOptimizeDetailActivity` + fallback `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` |
| `yangchong211/YCAppTool` → `AliveSettingConst.java:118-126`, `DefaultWhiteListProvider.java:61-69` | Xiaomi `OP_AUTO_START` y `HiddenAppsConfigActivity` + `package_name` |
| `alipay/SoloPi` → `MiuiUtils.java:121-170` | `APP_PERM_EDITOR` + `extra_pkgname` con variantes V6/V7/V8 y fallback por package |
| `xiaojieonly/Ehviewer_CN_SXJ` → `MiuiOptimizationHelper.kt:213-215` | confirmación `HiddenAppsConfigActivity` |
| `pvsvamsi/Disable-Battery-Optimizations` → `Xiaomi.java:20-31,114-117` | conjunto MIUI (autostart, power save, contenedor) |
| `threema-ch/threema-android` → `PowermanagerUtil.java:49` | `miui.intent.action.OP_AUTO_START` |
| dontkillmyapp.com/xiaomi | pasos MIUI (auto-inicio, candado, batería, "MIUI optimizations"); auto-inicio por app en MIUI 14 |
| dontkillmyapp.com/zte | 404: ZTE no está en la base |
| `support.ztedevices.com/en-uk/why-do-background-programs-always-close` | ruta oficial ZTE: Settings → Battery → Battery optimization → not optimize |
| `support.ztedevices.com/en-sa/...Intelligent Battery Management` | definición oficial del control de recursos en segundo plano |
| `support.sixtostart.com/.../ZTE-Battery-Optimisation` | variante ZTE Power Manager (auto-start, wake-up, deep sleep) |
| Xiaomi Community `c.mi.com/global/post/1755736` (2025-06-30) | "Background Autostart" en HyperOS |
| `mobile/app/src/main/java/com/dmujeres/traccar/oem/VendorSettings.kt` + `ui/OnboardingActivity.kt:177-208` | estado actual y patrón de fallback de la app |
| `docs/OEM_COMPATIBILITY.md`, `docs/audit/OEM_BACKGROUND_AUDIT.md` | evidencia de campo ZTE (`qa-f0`, freezer) y clasificación previa |

---

## Resumen final (≤12 líneas)

1. ZTE Z2450 (Blade A55, MyOS 14): `AppSmartOptimizeActivity` está confirmado en campo y `AppSmartOptimizeDetailActivity` en OSS; ambas lanzables. La afirmación previa de "Gestión inteligente protegida por permiso de sistema" no tiene respaldo público y contradice la evidencia de campo: tratarla como abrible con `try/catch`.
2. Para ZTE la whitelist real y pública es `android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (AOSP 14 exporta `Settings$HighPowerApplicationsActivity`), y "Pausar actividad si no se usa" solo se alcanza vía `ACTION_APPLICATION_DETAILS_SETTINGS` (no existe acción propia).
3. Xiaomi: VERIFIED el auto-inicio clásico (`com.miui.securitycenter/...AutoStartManagementActivity`), la acción `miui.intent.action.OP_AUTO_START`, el editor `APP_PERM_EDITOR`+`extra_pkgname` y la batería por app (`com.miui.powerkeeper/.ui.HiddenAppsConfigActivity`+`package_name`).
4. `com.miui.permcenter.power.PowerManagementActivity` no tiene ninguna evidencia: descartado (UNVERIFIED). HyperOS no trae paquete/acción nuevos; mantiene `com.miui.securitycenter` y añade AppOps/freezer no accesibles por intent.
5. Recomendación: cadenas de intents con `resolveActivity` + `try/catch` (patrón Traccar/AutoStarter), `<queries>` para los 3 paquetes OEM, terminando siempre en app details; registrar qué eslabón resolvió para verificación de campo. Sin hacks, sin reflexión, sin commits.
