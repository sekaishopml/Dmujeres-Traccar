# Plan: Puesta a punto total por dispositivo (permisos + pantallas OEM)

Estado: FASE 1 IMPLEMENTADA en 1.1.10 (vc120, OTA publicada con notas
"Arreglo de permisos android 16"). Base: `PERMS_RESEARCH_ANDROID.md`,
`PERMS_RESEARCH_INFINIX_HONOR.md`, `PERMS_RESEARCH_SAMSUNG_XIAOMI.md`,
`OEM_RESEARCH_*.md`.

Fase 1 entregada: cadenas OEM corregidas y ampliadas (Xiaomi con extras y
fallbacks por acción; Samsung con activity_type=2 y legacy; Honor con
HwPowerManagerActivity; Infinix/Tecno con PowerSava corregido y
PowerManagerActivity); guardián con alarma EXACTA cuando hay exención de
batería (`SessionKeeperAlarmPolicy`); evidencia de congelado
(`FreezeEvidence` → evento `PROCESS_FREEZE_DETECTED` en tc_device_health);
instalador OTA abre "apps desconocidas" si falta (`canRequestPackageInstalls`);
`SetupChecklistPolicy` pura con tests (664/0). Pendiente fase 2 (opcional,
requiere OK): SYSTEM_ALERT_WINDOW como último recurso y UI de chips del
asistente fuera del onboarding.

## 1. Respuesta corta: ¿faltan permisos?

**A nivel Android, no.** El manifest ya declara y el onboarding ya pide lo
necesario: `ACCESS_FINE/COARSE/BACKGROUND_LOCATION`, `FOREGROUND_SERVICE(_LOCATION)`,
`POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
`USE_FULL_SCREEN_INTENT`, `REQUEST_INSTALL_PACKAGES`.

**Lo que falta no son permisos: son pantallas OEM** (autostart, batería por app,
ejecución en segundo plano) que no tienen API — solo se pueden abrir, nunca
leer ni conceder (evidencia en los informes; p.ej. SO 38995469, SO 56378810).
Y faltan 3 capacidades Android que sí son automatizables y hoy no se explotan:

1. **Alarmas exactas por exención de batería** (API 31+): una app exenta de
   optimización de batería puede usar `setExactAndAllowWhileIdle()` **sin**
   `SCHEDULE_EXACT_ALARM`. Hoy el keeper depende de alarmas inexactas.
   (Fuente: developer.android.com/reference/android/app/AlarmManager)
2. **Detección post-hoc de congelado**: `ApplicationExitInfo.REASON_FREEZER`
   (API 33+) avisa si la ROM congeló el proceso; hoy no se lee.
3. **Chequeos de estado reales** ya disponibles pero no usados en el wizard:
   `ActivityManager.isBackgroundRestricted()`, `UsageStatsManager.getAppStandbyBucket()`
   (app propia), `PackageManager.canRequestPackageInstalls()`,
   `AlarmManager.canScheduleExactAlarms()`.

## 2. Lo que NO se puede (regla dura, sin hacks)

| Objetivo | Veredicto | Evidencia |
|---|---|---|
| Activar autostart OEM por código | Imposible (solo abrir pantalla) | SO 38995469 / SO 56378810 |
| Leer toggles OEM (autostart, bg running) | Imposible sin Accessibility | Informes OEM |
| Conceder permisos runtime por código | Solo Device Owner/ADB | DPM.setPermissionGrantState |
| Activar Accessibility | Solo la usuaria, y sideload 13+ exige "ajustes restringidos" | AOSP + SO 72217216 |
| AppOps / APIs ocultas para escribir | SecurityException / non-SDK | AOSP AppOps |
| Candado en Recientes | Sin API (gesto manual) | DKMA / informes OEM |
| Añadirse a Doze allowlist sin diálogo | Imposible (diálogo o Ajustes) | doze-standby |

Nota Android 16: BAL no se relaja (abrir Ajustes desde background sigue
bloqueado salvo notificación/ventana/SAW); `dataSync` FGS tiene timeout 6h/24h
(no usar para subidas); jobs junto a FGS con cuota; `location` sigue exento y
sin timeout. Fuente: behavior-changes-16 + fgs/timeout.

## 3. Plan: asistente "Puesta a punto" con detección por dispositivo

### 3.1 Detección
- `Build.MANUFACTURER`/`BRAND` + API level (ya existe `VendorSettings.currentVendor()`).
- Fase 2 (opcional): distinguir MIUI vs HyperOS por `ro.mi.os.version.name`
  (reflection, hoy UNKNOWN) — **no bloquea**: las cadenas de fallback ya cubren.

### 3.2 Checklist ordenada (una pantalla, estado por paso)

| # | Paso | Acción | Verificación |
|---|---|---|---|
| 1 | Ubicación precisa | runtime FINE+COARSE | API (granted) |
| 2 | Ubicación en segundo plano | API 30+: deeplink a Ajustes > Ubicación > Permitir siempre (el diálogo ya no ofrece "todo el tiempo"); API 29: request runtime | API |
| 3 | Notificaciones (33+) | runtime POST_NOTIFICATIONS | API |
| 4 | Batería sin restricciones | diálogo `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (funciona 12–16 en sideload; en MIUI suele fallar → fallback a lista AOSP) | `isIgnoringBatteryOptimizations` |
| 5 | Alarmas exactas | si exención OK: habilitar keeper exacto; si no, `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` (31–33) o queda inexacto | `canScheduleExactAlarms()` |
| 6 | Pantallas OEM (cadena por fabricante) | intents con try/catch y fallback a Ajustes de la app | **NO verificable** → guiado |
| 7 | Instalar actualizaciones OTA | `canRequestPackageInstalls()` → `ACTION_MANAGE_UNKNOWN_APP_SOURCES` | API |
| 8 | Congelado post-hoc | `ApplicationExitInfo.REASON_FREEZER` → si aparece, re-abrir paso 4/6 y avisar | API (33+) |

### 3.3 Cadenas OEM a ampliar (gaps vs 1.1.9)

**Xiaomi/Redmi** (hoy: Autostart→PermissionsEditor→powerkeeper):
- Añadir extras: `extra_pkgname`/`extra_package_uid` (editor) y `package_name`+`packageName` (powerkeeper).
- Añadir fallbacks: action `miui.intent.action.OP_AUTO_START`; action `APP_PERM_EDITOR`+setPackage; `AppPermissionsEditorActivity` (MIUI v6/7);
  `HiddenAppsContainerManagementActivity`; action `POWER_HIDE_MODE_APP_LIST`.
- "Otros permisos" contiene "Inicio automático en bg" (MIUI14+) — la pantalla es A3, ya en cadena.

**Samsung** (hoy: `ACTION_OPEN_CHECKABLE_LISTACTIVITY` sin extras):
- Añadir `activity_type=2` ("Never sleeping apps"); variantes 0/1 como fallback.
- Fallbacks legacy: `com.samsung.android.sm.ui.battery.BatteryActivity`, `sm_cn`.
- "Allow background activity" no tiene deeplink → guiar en ficha de app; verificar con `isBackgroundRestricted()`.

**Honor** (hoy: startupmgr→appcontrol→Protect→huawei×3):
- Añadir `HwPowerManagerActivity` (público, VERIFIED en campo) antes de la ficha de app.
- `DetailOfSoftConsumptionActivity` está protegida: no incluir como intent directo.

**Infinix/Tecno** (hoy: autostart→batterylab→PowerSava(paquete mal)→phonemanager→MTK):
- Corregir `PowerSavaMainActivity` → `com.transsion.batterylab/com.transsion.powersave.activity.PowerSavaMainActivity` (+Launcher).
- Añadir `PowerManagerActivity` (`com.cyin.himgr.powermanager.views.activity.PowerManagerActivity`).
- Confirmado: no existe "otros permisos" en Transsion; el toggle Autostart vive en la ficha de app.

### 3.4 Lo "extremo" legítimo (con decisión tuya)
- **Exención de batería + alarmas exactas**: pura ganancia, sin riesgo, ya en plan.
- **SYSTEM_ALERT_WINDOW**: exime el arranque de FGS desde background (15+: con
  overlay visible). Es la única palanca "extrema" estándar que queda.
  Costo: permiso intrusivo y en sideload 13+ puede requerir "ajustes
  restringidos". Propuesta: **fase 2, solo si un device sigue congelándose**,
  con texto claro a la usuaria. Necesita tu OK explícito.
- Nada de Accessibility/root/AppOps (rompe la regla del proyecto y es frágil).

### 3.5 UI y honestidad
- Chips por paso: `✓ verificado` (API) · `⚠ abrir pantalla` (OEM, no leíble) ·
  `pendiente`. Re-evaluar en `onResume`.
- Nunca afirmar que un ajuste OEM quedó activo; el informe de salud sigue
  usando continuidad real (keeper/fix) como prueba indirecta.

## 4. Orden de implementación propuesto (cuando apruebes)
1. `SetupChecklistPolicy` (puro, testeable): pasos por vendor+API y estados.
2. Verificadores (envueltos en interfaces para test JVM).
3. Ampliar `VendorSettings` cadenas/extras (§3.3) + tests de specs.
4. Pantalla "Puesta a punto" en onboarding (reusa `OnboardingActivity`).
5. Keeper exacto cuando `canScheduleExactAlarms()` + `ApplicationExitInfo`.
6. Suite + lint + release 1.2.0 (o 1.1.10) y OTA.

Riesgo principal: cadenas OEM cambian entre versiones de ROM → todo con
try/catch y fallback final a Ajustes; los tests congelan specs, no promesas.
