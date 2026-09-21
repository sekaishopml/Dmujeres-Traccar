# OEM_RESEARCH_HONOR_HUAWEI.md — Pantallas de supervivencia en HONOR (MagicOS) y HUAWEI (EMUI)

> Investigación de solo lectura (2026-09-18). Sin cambios de código, sin commits.
> Dispositivo objetivo del proyecto: **HONOR X7d 4G (LGN-LX3), MagicOS 9.0 / Android 15**
> (modelo confirmado en fuentes públicas: chooseyourmobile.com, deviceatlas.com).
> Alcance: Intents/Activities **públicas** con `try/catch` + fallback. Quedan fuera
> por diseño: `appops`, root, AccessibilityService (p. ej. `hushd`, descartado).

---

## 0. Criterios de clasificación

| Clase | Significado |
|---|---|
| **VERIFIED** | ≥2 fuentes independientes con el **componente exacto** (OSS público o Stack Overflow) y/o documentación oficial del fabricante. |
| **LIKELY** | 1–2 fuentes con el componente exacto, sin confirmación en la ROM objetivo, o componente legacy documentado como alcanzable pero degradado. |
| **UNVERIFIED** | Solo mención del paquete o inferencia; sin componente exacto publicado. No usarlo como primario. |

Nota metodológica: la existencia del componente en ROMs recientes se apoya en OSS que
lo usa en producción y en listados de paquetes MagicOS. **No hubo verificación de campo
en LGN-LX3**; donde aplica se indica "apertura no probada en LGN-LX3".

---

## 1. Tabla maestra: pantalla → componente exacto → evidencia → clasificación

### 1.1 HONOR — MagicOS 8/9/10 (paquete `com.hihonor.*`)

| # | Pantalla | Componente exacto | Evidencia | Clase |
|---|---|---|---|---|
| H1 | **Inicio de aplicaciones / App launch** (contiene los 3 toggles: Auto-inicio, **Inicio secundario**, **Ejecutar en segundo plano**) | `com.hihonor.systemmanager/.startupmgr.ui.StartupNormalAppListActivity` | grep.app: `seena98/auto_start_flutter` (L40), `Aliothmoon/MAA-Meow` AutoStartHelper (L81-82), `kyujin-cho/Bada` BatteryOptimizationOemHelper (L203-204), `DrewCyber/yggstack-android` AutostartHelper (L154-156), `qianqianhhh2/jianyin` ManufacturerCompat (L128-130), `wxpusher/wxpusher-app` WxpJumpPageUtils (L129-131). Paquete `com.hihonor.systemmanager` confirmado en MagicOS 10 (crossly/honor-systemmanager-patch) y en MagicOS 9 (LegitKeepAlive `honor.json`, regex `^(MagicOS[ _])?9\..*`) | **VERIFIED** (componente/paquete). Apertura directa en LGN-LX3: **LIKELY** (sin bloqueo de permiso documentado, no probado) |
| H2 | Variante "App control" | `com.hihonor.systemmanager/.appcontrol.activity.StartupAppControlActivity` | `seena98/auto_start_flutter` (L42) | **LIKELY** |
| H3 | Apps protegidas (legacy MagicUI) | `com.hihonor.systemmanager/.optimize.process.ProtectActivity` | `seena98/auto_start_flutter` (L41) | **LIKELY** (puede no existir en MagicOS 9) |
| H4 | Consumo/ahorro por app ("permitir actividad en segundo plano") | `com.hihonor.systemmanager/.power.ui.DetailOfSoftConsumptionActivity` | `ityulong/LegitKeepAlive` `honor.json`, versión específica **MagicOS 9.x** ("耗电详情-启动管理-允许后台活动" = Detalle de consumo → Gestión de inicio → Permitir actividad en segundo plano) | **LIKELY** (única fuente; OSS orientada a deep-link, `is_checkable:false`) |
| H5 | Optimización de batería (estándar Android, funciona en MagicOS) | `Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)` / `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Docs Android + soporte oficial HONOR (`honor.com/mx/.../es-es00428704`, `honor.com/za/.../en-us00428704`) + código actual (`OnboardingActivity.kt:286-293`) | **VERIFIED** |
| H6 | Legacy (lo que usa HOY `VendorSettings.kt:79`) | `com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity` | `kyujin-cho/Bada` lo incluye como fallback de Honor; **no hay confirmación de que `com.huawei.systemmanager` exista en MagicOS 8/9/10** (post-separación HONOR-Huawei) | **UNVERIFIED** en MagicOS reciente (probablemente ausente; si no existe, hoy cae al fallback genérico) |
| H7 | PowerGenie HONOR | paquete `com.hihonor.powergenie` | `PixelCode01/UIBloatwareRegistry` (lista Honor); `crossly/honor-systemmanager-patch` (perfil powerkit en MagicOS 10) | **UNVERIFIED** como UI (paquete existe; **sin Activity pública de ajustes conocida** → no enlazar) |

### 1.2 HUAWEI — EMUI 8–12 / HarmonyOS (paquete `com.huawei.*`)

| # | Pantalla | Componente exacto | Evidencia | Clase |
|---|---|---|---|---|
| W1 | **Inicio de aplicaciones / App launch / Startup manager** (incluye Inicio secundario y Ejecutar en segundo plano) | `com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity` | `traccar/traccar-client-android` BatteryOptimizationHelper.kt (L44), `invertase/notifee` PowerManagerUtils (L207-210), `judemanutd/AutoStarter`, `kyujin-cho/Bada` (rama HUAWEI), SO 68100440 y SO 68710845 | **VERIFIED** (componente). Apertura restringida: desde inicios de 2021 EMUI puede lanzar `SecurityException` exigiendo `com.huawei.permission.external_app_settings.USE_COMPONENT` (permiso de firma; declararlo en el manifest **no** lo concede — SO 68100440) |
| W2 | Apps protegidas ("Protected apps", EMUI antiguo) | `com.huawei.systemmanager/.optimize.process.ProtectActivity` | SO 31638986 y SO 47786535, Traccar (L45), notifee (L205-206), threema PowermanagerUtil, `kawaiiDango/pano-scrobbler` (L37), AutoStarter issue #38 | **VERIFIED** (componente). En EMUI 8+ puede resolver pero ser una pantalla "muerta"/inalcanzable (issue #38) → usar como fallback, no como primario |
| W3 | Variante "App control" (EMUI 9/10) | `com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity` | Traccar (L46), notifee (L212-213), threema, pano-scrobbler (L38), `pppscn/SmsForwarder` (L1253), SO 68710845 | **VERIFIED** |
| W4 | Batería / Power Manager | `com.huawei.systemmanager/.power.ui.HwPowerManagerActivity` | `trah01/Accnotify` KeepAliveHelper.kt (L419-420 y L464-465) | **LIKELY** (fuente única) |
| W5 | Arranque tras boot | `com.huawei.systemmanager/.optimize.bootstart.BootStartActivity` | `pppscn/SmsForwarder` SettingsFragment (L1253) | **LIKELY** |
| W6 | Optimización de batería (estándar) | `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` | dontkillmyapp.com/huawei (pasos EMUI 9+), soporte Huawei `en-us00428704` | **VERIFIED** |
| W7 | PowerGenie HUAWEI | paquete `com.huawei.powergenie` | dontkillmyapp.com/huawei: solo desinstalación por ADB; sin ajustes de usuario | **VERIFIED** (existencia) / **sin Intent público** (no enlazar) |

### 1.3 Genérico (siempre disponible)

| # | Pantalla | Intent | Evidencia | Clase |
|---|---|---|---|---|
| G1 | Detalles de la app | `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` + `package:<app>` | Patrón de fallback de Traccar, Bada, MAA-Meow; código actual (`VendorSettings.kt:141-144`) | **VERIFIED** |
| G2 | Exención Doze | `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Docs Android; ya implementado (`OnboardingActivity.kt:286-293`) | **VERIFIED** |

---

## 2. Pasos manuales exactos (texto para la guía de la app)

### 2.1 MagicOS 8 / 9 (HONOR X7d y similares)

Rutas oficiales HONOR (sirven dos según región/versión; la app debe mostrar ambas):

1. **Optimizador**: `Optimizador > (consumo restante) > Inicio de aplicaciones` → elegir la app →
   desactivar **Gestionar automáticamente** → activar **Inicio automático**, **Inicio secundario**
   y **Ejecutar en segundo plano** → Aceptar.
   Fuente: HONOR UK, "Unable to receive notifications from third-party apps" — `honor.com/uk/support/content/en-us00685514`.
2. **Ajustes**: `Ajustes > Aplicaciones > Gestión del inicio de la aplicación`
   (en algunas versiones `Ajustes > Batería > Inicio de aplicaciones`) → desactivar
   **Gestionar todo automáticamente** → activar **Inicio automático**, **Inicio secundario** y
   **Ejecutar en segundo plano**.
   Fuente: HONOR (CDN oficial hihonor) — `iknow-dl.service.hihonor.com/ctkbfm/applet/simulator/es-us15872455`.
   También: `Ajustes > Batería > Iniciar` / buscar "Iniciar" (`honor.com/mx/.../es-es00428704`).
3. **Batería**: `Ajustes > Batería > Optimización de batería` → triángulo invertido junto a
   "No permitir" → **Todas las aplicaciones** → app → **No permitir**. Y en `Ajustes > Batería`
   desactivar **Modo de ahorro de energía** y **ultra**.
   Fuente: HONOR MX — `honor.com/mx/support/content/es-es00428704`.
4. **Recientes**: abrir la tarjeta de la app y deslizarla hacia abajo para fijarla (candado).
   Fuente: HONOR UK `en-us00685514`; HONOR Global `en-us00409667`.
5. Búsqueda rápida en Ajustes: **"Inicio de aplicaciones"**, **"Launch"**, **"Iniciar"** o
   **"Optimización de batería"**.

Nota: `honor.com/global/support/content/en-us00409667` añade la ruta
`Ajustes > Aplicaciones > Aplicaciones > Permisos > Ver todos los permisos > ejecutar al inicio`.

### 2.2 EMUI 8 / 9 / 10 / 11 (HUAWEI)

1. `Ajustes > Batería > Inicio de aplicaciones` → tocar el control de la app →
   **Gestionar manualmente** → activar **Inicio automático**, **Inicio secundario** y
   **Ejecutar en segundo plano**.
   Fuentes oficiales: Huawei `en-us00428704` (Ajustes > Inicio de aplicaciones) y
   `en-us15850065` (Phone Manager > App launch); español en `consumer.huawei.com/ar/.../es-us00428704`.
2. HarmonyOS 2: `Optimizador > Inicio de aplicaciones` → desactivar el switch →
   **Gestionar manualmente** → activar los tres toggles.
   Fuente: Huawei `en-us15883277`.
3. `Ajustes > Optimización de batería > No permitir` (triángulo invertido → Todas las aplicaciones).
   Fuente: Huawei `es-us00428704`; dontkillmyapp.com/huawei.
4. Desactivar **Modo de ahorro de energía**; fijar la app en Recientes (deslizar hacia abajo).
5. EMUI 9+: dontkillmyapp documenta además desactivar "Smart tune-up" en el Administrador del
   teléfono (`dontkillmyapp.com/huawei`).

### 2.3 Verificación de campo (solo QA, opcional, no forma parte de la app)

Con ADB (sin root, sin appops) y solo para confirmar qué componentes expone la ROM:

```bash
adb shell cmd package resolve-activity --brief com.hihonor.systemmanager/com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity
adb shell am start -n com.hihonor.systemmanager/com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity
adb shell am start -n com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity
```

---

## 3. HONOR vs HUAWEI: son paquetes DISTINTOS

| | HONOR (MagicOS) | HUAWEI (EMUI/HarmonyOS) |
|---|---|---|
| Gestor del sistema | `com.hihonor.systemmanager` | `com.huawei.systemmanager` |
| PowerGenie | `com.hihonor.powergenie` | `com.huawei.powergenie` |
| Pantalla Inicio de aplicaciones | `com.hihonor.systemmanager/.startupmgr.ui.StartupNormalAppListActivity` | `com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity` |
| Evidencia del paquete | crossly/honor-systemmanager-patch (BKQ-AN90, MagicOS 10): lista `com.hihonor.systemmanager.power.service.BgPowerManagerService`, `com.hihonor.powergenie.core...`; UIBloatwareRegistry lista `com.hihonor.powergenie`; LegitKeepAlive mapea MagicOS 9 → `com.hihonor.systemmanager` | dontkillmyapp.com/huawei (`com.huawei.powergenie`); código OSS EMUI (`com.huawei.systemmanager...`) |

Historia y consecuencias:

- Hasta 2020 HONOR era submarca de Huawei: dispositivos antiguos reportan
  `Build.MANUFACTURER = "HUAWEI"` y usan `com.huawei.systemmanager` (por eso AutoStarter y
  notifee todavía mapean Honor al paquete Huawei).
- Tras la separación (nov-2020) MagicOS estrenó paquete propio `com.hihonor.*`. En MagicOS 9/10
  la evidencia pública apunta a que el paquete vigente es `com.hihonor.systemmanager`.
- **Impacto en el código actual**: `VendorSettings.kt:79` usa el componente Huawei para "honor".
  En LGN-LX3 (MagicOS 9) lo más probable es que `com.huawei.systemmanager` no exista → el
  `runCatching` de `OnboardingActivity.kt:186-191` cae al genérico (no crashea, pero el usuario
  no llega a la pantalla correcta).
- La detección actual de vendor es correcta en la práctica (`VendorSettings.kt:29-31`, honor
  evaluado antes que Huawei vía `brand.contains("honor")`), pero el **intent** es legacy.
- dontkillmyapp.com **no tiene página /honor** (404); la única página del fabricante histórico es
  `/huawei` (5/5 💩). Para MagicOS la evidencia fuerte es soporte oficial HONOR + OSS + el parentesco
  EMUI documentado (p. ej. guías de terceros: "Honor runs on MagicOS — a direct descendant of EMUI").

---

## 4. Riesgos técnicos a tener en cuenta

1. **`resolveActivity()` no es garantía** (y en API 30+ puede dar `null` por filtrado de
   visibilidad de paquetes): el manifest de la app **no declara `<queries>`**. Documentación
   oficial Android: una Activity se puede iniciar con intent explícito o implícito aunque el
   paquete no sea visible, y el manejo correcto es `try/catch` de `ActivityNotFoundException`
   (`developer.android.com/training/package-visibility/automatic` y `/use-cases`). No condicionar
   el arranque a `resolveActivity`; usarlo solo como pista.
2. **`SecurityException` en EMUI 2021+**: `com.huawei.systemmanager/.startupmgr...` puede exigir
   `com.huawei.permission.external_app_settings.USE_COMPONENT` (permiso de firma). Declararlo en
   el manifest **no** resuelve (SO 68100440: el usuario lo tenía declarado y aun así fallaba).
   El try/catch debe capturar `SecurityException` además de `ActivityNotFoundException` y seguir
   con el siguiente candidato.
3. **`ProtectActivity` puede ser una pantalla obsoleta**: en EMUI 8+ existe pero puede no llevar
   a ningún ajuste útil (AutoStarter issue #38). Por eso va después de `startupmgr`/`appcontrol`.
4. **Pantallas por app vs por lista**: H1/W1 son pantallas **de lista**; no reciben el paquete.
   La app solo puede abrir la lista; el usuario busca su app. No existe deep-link público
   "directo a mi app" para estas pantallas (los `DetailOfSoftConsumptionActivity` de HONOR
   probablemente requieren extras no documentados → tratarlos como lista genérica).
5. **PowerGenie no es configurable por el usuario** en ninguna de las dos marcas (DKMA: solo ADB
   o root). No hay Intent que ofrecer; la app no debe simular que resuelve ese problema.
6. **No verificable programáticamente**: no hay API pública para leer si autostart/protección
   están activos. La política de la app (guía + prueba de continuidad) sigue siendo la correcta.

---

## 5. Recomendación para `VendorSettings.kt` (especificación; no implementada aquí)

Objetivo: sustituir el intent único por **listas ordenadas de candidatos** con try/catch por
elemento, priorizando `com.hihonor.*` en HONOR y manteniendo los Huawei como fallback.

### 5.1 HONOR (MagicOS)

Orden propuesto para el botón primario "Inicio de aplicaciones":

1. `com.hihonor.systemmanager/.startupmgr.ui.StartupNormalAppListActivity` ← **primario nuevo**
2. `com.hihonor.systemmanager/.appcontrol.activity.StartupAppControlActivity`
3. `com.hihonor.systemmanager/.optimize.process.ProtectActivity` (apps protegidas legacy)
4. `com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity` ← actual, degradar a fallback
5. `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` ← fallback final (ya existe como `appDetailsIntent()`)

Botón secundario (batería) para HONOR:
`com.hihonor.systemmanager/.power.ui.DetailOfSoftConsumptionActivity` (si falla), y mantener la
exención estándar vía `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (ya existe en Onboarding).

### 5.2 HUAWEI (EMUI)

Orden propuesto:

1. `com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity` (capturar `SecurityException`)
2. `com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity`
3. `com.huawei.systemmanager/.optimize.process.ProtectActivity`
4. `com.huawei.systemmanager/.power.ui.HwPowerManagerActivity` (batería; LIKELY)
5. `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`

### 5.3 Notas de implementación

- `runCatching { startActivity(intent) }` por candidato; avanzar al siguiente **solo** si falla.
  Registrar qué componente falló (Sentry/`DiagnosticsReporter`) para cerrar evidencia de campo
  en LGN-LX3 y en próximos Huawei.
- No añadir el permiso `com.huawei.permission.external_app_settings.USE_COMPONENT` como "fix":
  es de firma y no se concede (SO 68100440).
- No añadir `FLAG_ACTIVITY_NEW_TASK` cuando se lanza desde `OnboardingActivity` (es Activity);
  sí añadirlo si algún día se lanza desde un `Context` no-Activity (patrón Bada/MAA-Meow).
- Si se quiere seguir usando `resolveActivity` como filtro, añadir antes `<queries>` con
  `com.hihonor.systemmanager` y `com.huawei.systemmanager`; hoy no es necesario para
  `startActivity` explícito (docs Android) y el manifest no tiene `<queries>`.
- Actualizar los textos de la guía HONOR a la nomenclatura oficial MagicOS:
  "Ajustes → Aplicaciones → **Gestión del inicio de la aplicación**" y "Optimizador → Inicio de
  aplicaciones", además de la ruta actual "Batería → Inicio de aplicaciones".
- Mantener la honestidad de capacidad: no se puede verificar el ajuste; guía + continuidad.

---

## 6. Evidencia (enlaces)

**OSS con componente exacto**
1. Traccar Client — `BatteryOptimizationHelper.kt`: https://github.com/traccar/traccar-client-android/blob/master/app/src/main/java/org/traccar/client/BatteryOptimizationHelper.kt
2. notifee — `PowerManagerUtils.java`: https://github.com/invertase/notifee/blob/main/android/src/main/java/app/notifee/core/utility/PowerManagerUtils.java
3. `seena98/auto_start_flutter` — `AutoStartIntents.kt` (Huawei + Honor `com.hihonor.*`): https://github.com/seena98/auto_start_flutter/blob/master/android/src/main/kotlin/co/techFlow/auto_start_flutter/AutoStartIntents.kt
4. `Aliothmoon/MAA-Meow` — `AutoStartHelper.kt` (Huawei vs Honor separados): https://github.com/Aliothmoon/MAA-Meow/blob/main/app/src/main/java/com/aliothmoon/maameow/schedule/service/AutoStartHelper.kt
5. `kyujin-cho/Bada` — `BatteryOptimizationOemHelper.kt` (candidatos Honor con `com.hihonor.systemmanager` y fallback Huawei): https://github.com/kyujin-cho/Bada/blob/main/app/src/main/kotlin/dev/bluehouse/bada/battery/BatteryOptimizationOemHelper.kt
6. `threema-ch/threema-android` — `PowermanagerUtil.java`: https://github.com/threema-ch/threema-android/blob/main/app/src/main/java/ch/threema/app/utils/PowermanagerUtil.java
7. `judemanutd/AutoStarter` — `AutoStartPermissionHelper.kt` + issue #38 (ProtectActivity obsoleta en EMUI 8): https://github.com/judemanutd/AutoStarter/blob/master/autostarter/src/main/java/com/judemanutd/autostarter/AutoStartPermissionHelper.kt y https://github.com/judemanutd/AutoStarter/issues/38
8. `ityulong/LegitKeepAlive` — `honor.json` (MagicOS 9 → `DetailOfSoftConsumptionActivity`): https://github.com/ityulong/LegitKeepAlive/blob/main/keepalive-core/src/main/assets/legitkeepalive/honor.json
9. `crossly/honor-systemmanager-patch` — paquetes `com.hihonor.systemmanager` y `com.hihonor.powergenie` en MagicOS 10: https://github.com/crossly/honor-systemmanager-patch
10. `PixelCode01/UIBloatwareRegistry` — lista Honor (`com.hihonor.powergenie`): https://github.com/PixelCode01/UIBloatwareRegistry/blob/main/Honor/honor_remover.py
11. `pppscn/SmsForwarder` — `SettingsFragment.kt` (BootStartActivity, lista EMUI): https://github.com/pppscn/SmsForwarder/blob/main/app/src/main/kotlin/cn/ppps/forwarder/fragment/SettingsFragment.kt
12. `trah01/Accnotify` — `KeepAliveHelper.kt` (`HwPowerManagerActivity`): https://github.com/trah01/Accnotify/blob/main/app/src/main/java/com/trah/accnotify/util/KeepAliveHelper.kt
13. `hushd` (descartado: AccessibilityService, no aplica por reglas): https://github.com/Labushuya/hushd

**Stack Overflow**
14. SO 31638986 — "Protected Apps" Huawei y `ProtectActivity`: https://stackoverflow.com/questions/31638986
15. SO 47786535 — `ActivityNotFoundException` de `ProtectActivity`: https://stackoverflow.com/questions/47786535
16. SO 68100440 — `SecurityException` `external_app_settings.USE_COMPONENT` desde 2021: https://stackoverflow.com/questions/68100440
17. SO 68710845 — Honor 10 Lite, `com.huawei.systemmanager` y permiso sugerido: https://stackoverflow.com/questions/68710845

**Fabricantes**
18. dontkillmyapp.com/huawei (PowerGenie, App launch, Protected apps, battery): https://dontkillmyapp.com/huawei (página `/honor` → 404, no existe)
19. HONOR UK — notificaciones y App launch (`Optimizer > App launch`): https://www.honor.com/uk/support/content/en-us00685514
20. HONOR MX — cierre de apps en segundo plano (es): https://www.honor.com/mx/support/content/es-es00428704
21. HONOR CDN hihonor — "Gestión del inicio de la aplicación" (es): https://iknow-dl.service.hihonor.com/ctkbfm/applet/simulator/es-us15872455
22. HONOR Global — alarmas/Auto-launch/Secondary launch/Run in background y "run at startup": https://www.honor.com/global/support/content/en-us00409667
23. Huawei — "Unable to run background apps": https://consumer.huawei.com/en/support/content/en-us00428704
24. Huawei — background protection EMUI (`Phone Manager > App launch`): https://consumer.huawei.com/en/support/content/en-us15850065
25. Huawei — HarmonyOS 2 protegidas (`Optimizer > App launch`): https://consumer.huawei.com/en/support/content/en-us15883277
26. Huawei — "Allowing background activities on Honor phones" (legacy): https://consumer.huawei.com/en/support/content/en-us15848660

**Android (visibilidad de paquetes)**
27. https://developer.android.com/training/package-visibility/automatic
28. https://developer.android.com/training/package-visibility/use-cases

---

## 7. Resumen

1. El componente que usa hoy la app para HONOR (`com.huawei.systemmanager...`) es legacy; en
   MagicOS 8/9/10 la evidencia apunta a `com.hihonor.systemmanager`.
2. Primario recomendado HONOR: `com.hihonor.systemmanager/.startupmgr.ui.StartupNormalAppListActivity`
   (VERIFIED como componente; apertura en LGN-LX3 aún no probada).
3. Fallbacks HONOR: `appcontrol.activity.StartupAppControlActivity` → `optimize.process.ProtectActivity`
   → (legacy) `com.huawei.systemmanager/.startupmgr...` → detalles de la app.
4. Batería HONOR: `power.ui.DetailOfSoftConsumptionActivity` (LIKELY) + exención estándar Android.
5. HUAWEI: `startupmgr` → `appcontrol` → `ProtectActivity` → `power.ui.HwPowerManagerActivity`.
6. EMUI 2021+ puede bloquear `startupmgr` con `SecurityException` (permiso de firma): capturarla
   y continuar; no intentar "arreglarla" con manifest.
7. `resolveActivity` no basta (visibilidad API 30+); el manejo correcto es try/catch por candidato
   y avanzar al siguiente, registrando el fallo para evidencia de campo.
8. No existe intent público para PowerGenie ni deep-link directo "a esta app" en las pantallas de
   lista: la ruta verificable sigue siendo guía al usuario + prueba de continuidad.
9. Pasos manuales oficiales documentados para MagicOS 8/9 (Optimizador/Ajustes → Gestión del inicio
   de la aplicación → 3 toggles + batería) y EMUI (Ajustes → Inicio de aplicaciones → manual).

— Investigación de solo lectura; ningún archivo de código modificado, sin commits.
