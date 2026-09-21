# ZTE Z2450 · MyOS 14 — congelados del FGS location en reposo

> Investigación de fuentes públicas + evidencia de campo del proyecto. Fecha: 2026-09-19. Solo lectura, sin código.
> Equipo: ZTE Z2450 (`macias`), MyOS 14.0.8 / Android 14, app `com.dmujeres.traccar` (FGS `location`).
> Síntoma: mediana 9 s en movimiento; p90 897 s parado (bloques de 15–45 min). Guía OEM ya abierta y confirmada (`oemConfirmed=true`).

## 1. ¿Qué es el "ZTE Z2450"?

| Dato | Valor | Fuente |
|---|---|---|
| Modelo comercial | **ZTE Blade A55** (`MODEL_NAME`=ZTE Blade A55; producto P963F65) | http://www.deviceinfohw.ru/devices/item.php?item=116589 |
| Anuncio/venta | Anunciado ene-2024; a la venta jul-2024 (Q3-2024) | https://fo.gsmarena.com/zte_blade_a55-13500.php |
| Chipset | Unisoc SC9863A1 (22 nm), 8×Cortex-A55, PowerVR GE8322; 4 GB RAM; 64/128 GB | https://fo.gsmarena.com/zte_blade_a55-13500.php |
| Pantalla/batería | 6.75" HD+ 1600×720 90 Hz; 5000 mAh; 167.7×77.4×8.5 mm | https://benchmarks.ul.com/hardware/phone/ZTE+Blade+A55+review |
| Región | Un solo `Z2450` multi-región: UE (registro EPREL de ZTE España, en mercado 01-08-2024→31-07-2025), MX/Telcel e internacional `_AS` | https://eprel.ec.europa.eu/fiches/smartphonestablets20231669/Fiche_2232553_PT.pdf · https://www.ztedevices.mx/wp-content/uploads/2024/12/ZTE-Blade-A55-Mexico-Telcel-Espanol-Manual-de-Usuario.pdf |
| Builds observadas | Equipo en MyOS 14.0.8; base MyOS14.0.4 (jul-2024); la más nueva vista para Z2450 es **MyOS14.0.16_Z2450_AS**, parche 2025-10-01 (build 2025-10-11) | https://stock-rom.com/zte-blade-a55-p963f65-z2450-unlocktool-read-file-free-download/ · https://www.gsmzone.com/experience-reports/frp-reset-zte-zte-blade-a55-z2450-eft-pro |
| ¿Última versión? | **Sí, todo apunta a que MyOS 14/Android 14 es la rama final**: no hay build oficial MyOS 15/Android 15 para Z2450; solo intentos de GSI (mar-2026) con bootloader bloqueado | https://xdaforums.com/t/generic-system-image-android-16-on-zte-blade-a55-not-working.4780657/ |

Confianza Q1: ALTA modelo/chipset/regiones; MEDIA-ALTA "rama final" (evidencia = ausencia hasta 2026).

## 2. MyOS 14: mecanismos de ahorro/congelado y nombres en español

| Mecanismo | Nombre ES (pantalla) | Ruta | Evidencia |
|---|---|---|---|
| **Gestión inteligente** (AppSmartOptimize; "control de IA"): asigna política por app y alimenta el freezer | "Gestión inteligente" | Ajustes → **Batería** → **Gestión inteligente** → app → **"Sin control"** | ZTE oficial: "el sistema identifica apps y escenarios y **controla los recursos** de las apps back-end" https://support.ztedevices.com/es-es/what-is-function-of-intelligent-battery-management/ · ruta/etiqueta en guía del proyecto `mobile/.../VendorSettings.kt:215-231` |
| **Optimización de batería** (whitelist AOSP) | "Optimización de batería" → **No optimizar** | Ajustes → Batería (menú 3 puntos) → Optimización de batería | ZTE oficial EN: "Settings–Battery–Battery optimization … not to optimize" https://support.ztedevices.com/en-uk/why-do-background-programs-always-close/ · ES: "Ajustes > Batería > Optimización de la batería" https://fallascelular.org/zte/zte-me-saca-aplicaciones/ |
| **Pausar actividad si no se usa** (hibernación AOSP) | "Pausar actividad en la app si no se usa" | Ajustes → Aplicaciones → DMujeres (ficha de la app) | AOSP/A14, sin acción propia: https://mundobytes.com/como-evitar-que-android-desactive-las-apps/ |
| **"Control manual"** por app (auto-inicio/segundo plano) | "Control manual" (con "Inicio secundario") | Ajustes → Batería → Control manual | MyOS 14 en A55: https://tinhte.vn/thread/tren-tay-zte-blade-a55-tinh-nang-thong-bao-live-island-pin-lon-5000-mah.3959682/ (en Z2450 no verificado) |
| **Ahorro de energía** (power-saver global) | "Ahorro de energía" | Ajustes → Batería → Ahorro de energía | puede pisar las exenciones: https://www.traccar.org/forums/topic/unreliable-tracking-missing-points/ |
| **Auto-inicio** (variante "Disallow auto-start") | (no visto en Z2450) | Power Manager → gestión por app | guía del vendor Zombies, Run! para ZTE, recogida en `docs/audit/OEM_RESEARCH_ZTE_XIAOMI.md:51-53` |

Nota: `dontkillmyapp.com/zte` **sigue 404** (verificado 2026-09-19): no hay ficha federada de ZTE.

## 3. Qué pantallas se pueden abrir por Intent (y cuáles están "bloqueadas")

| # | Pantalla | Intent | Evidencia / estado |
|---|---|---|---|
| Z1 | Gestión inteligente (lista) | `ComponentName("com.zte.powersavemode","com.zte.powersavemode.appsmartoptimizer.AppSmartOptimizeActivity")` | **Abrible VERIFIED (campo Z2450)**: el botón de la app abre la pantalla (`VendorSettings.kt:227-231`, `oemConfirmed=true`). También lanzada por ADB en ZTE/nubia: https://github.com/DisplayXR/displayxr-runtime/issues/523 |
| Z2 | Detalle/control por app | `...appsmartoptimizer.AppSmartOptimizeDetailActivity` | **Abrible VERIFIED (OSS)**: app normal (F-Droid) la lanza y luego cae a la whitelist AOSP: https://github.com/TheRealCrazyfuy/GameSpaceReplacer/blob/main/app/src/main/java/com/abeja/gamecenterreplacer/ViewModel.kt · único hit de clase del paquete en grep.app: https://grep.app/search?q=com.zte.powersavemode |
| Z3 | Optimización de batería (whitelist real) | `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` | Pública AOSP 14 (`HighPowerApplicationsActivity` exportada); ya usada por GameSpaceReplacer |
| Z4 | Página de la app (pausar actividad, permisos) | `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` + `package:com.dmujeres.traccar` | Pública AOSP 14 |
| Z5 | Otras `com.zte.powersavemode/*` | — | **UNVERIFIED**: no hay más clases en OSS/foros; enumerar en dispositivo con `<queries>`+`queryIntentActivities` antes de hardcodear |
| Z6 | ¿Pantallas bloqueadas? | — | **Ninguna bloqueada con evidencia.** La afirmación previa "protegida por permiso de sistema" **no tiene respaldo** y contradice Z1/Z2 (campo+OSS); el toggle interno sí es solo-UI (no legible/escribible por API pública): https://github.com/DisplayXR/displayxr-runtime/issues/523 |

## 4. ¿Puede "Gestión inteligente"/control de IA congelar un FGS location aunque haya exención de batería?

- **Sí, el freezer del vendor manda sobre las exenciones AOSP.** Test empírico en ZTE/nubia (jun-2026): con `deviceidle whitelist +pkg`, `appops RUN_ANY_IN_BACKGROUND allow` y "pausar actividad si no usas" **desactivado**, el proceso siguió congelado (~63 s) por `CpuFreezerManagerServiceV2`/`com.zte.performance.cfreezer`, en silencio (D-state, `frozen-list`, sin log `ActivityManager: froze`). **La única whitelist que funcionó** fue la pantalla del vendor: `com.zte.powersavemode/.appsmartoptimizer.AppSmartOptimizeActivity` → "always allow background activity". https://github.com/DisplayXR/displayxr-runtime/issues/523 · https://github.com/DisplayXR/displayxr-runtime/blob/main/docs/roadmap/android-demo-ports-plan.md
- **Matiz FGS:** en ese test el proceso con ventana (y, según su conclusión, los uids con FGS activo) **no** fue congelado; el congelado era un servicio *bound* sin ventana. No hay test público de FGS `location` congelado en Z2450; pero el libro de campo del proyecto sí registra congelados ZTE con `frozen-list` y unfreeze `reason=screen_on` en `qa-f0/macias` (`docs/GAP_ANALYSIS.md:41`), y `KNOWN_FREEZER_VENDORS={zte}` (`docs/audit/OEM_BACKGROUND_AUDIT.md:68`). Interpretación honesta: **la exención AOSP es necesaria y NO suficiente; el FGS protege "como clase" pero no garantiza nada si el proceso muere/pierde el FGS o la ROM lo demota.**
- **El patrón "parado se congela, en marcha no" encaja con 3 causas acumulables:** (a) cfreezer al quedar estático/sin ventana ~60–150 s; (b) Doze profundo: sin red, GPS/Wi-Fi suspendidos y alarmas diferidas (la exención levanta red y wakelocks, no el resto) https://source.android.com/docs/core/power/platform_mgmt?hl=es-419; (c) al recuperar, el backlog se entrega en lote (p.ej. 36 592 s de delay real en `macias`: `docs/audit/ROUTE_SEGMENTATION_AUDIT.md:161-163`).

## 5. Candado en Recientes en MyOS

- **No está documentado en el manual oficial del Blade A55**: solo "Fijar apps" (screen pinning) y el bloqueo de apps de Mi Bóveda (privacidad), que no mantienen procesos vivos: https://www.ztedevices.mx/wp-content/uploads/2024/12/ZTE-Blade-A55-Mexico-Telcel-Espanol-Manual-de-Usuario.pdf · https://manuales.vodafone.es/zte/blade-a55-android-14
- **Hay reportes de usuario ZTE** de que fijar la app en Recientes "tiende a mantenerla abierta un poco más" (modelos 2021-22, MyOS previo): https://www.reddit.com/r/ZTE/comments/st50t3/tasker_killed_by_axon_10_pro/
- Para **MyOS 14/Z2450 es UNVERIFIED**; no hay intent ni API. Paso opcional de baja prioridad frente a §2/§3. No confundir con "Fijar apps" (pinning de pantalla).

## 6. Qué hicieron otras apps de tracking en ZTE y si la alarma exacta sobrevive

- **Life360 en ZTE Blade A3 Prime**: "solo se actualiza cuando abrimos la app": https://www.reddit.com/r/Visible/comments/j8rie1/life_360/
- **Tasker/Life360 en Blade A7 Prime**: la optimización de batería **se revierte sola** y las apps dejan de funcionar; otro usuario usa el equipo para **CellMapper** con el mismo problema: https://www.reddit.com/r/Visible/comments/pcu459/battery_optimization_settings_dont_save/
- **ZTE reconoce** que su política cierra apps en segundo plano (incluidas apps de GPS): https://fallascelular.org/zte/zte-me-saca-aplicaciones/ · https://support.ztedevices.com/en-uk/why-do-background-programs-always-close/
- **DisplayXR** resolvió el congelado promoviendo a **FGS** (no con trucos ADB): https://github.com/DisplayXR/displayxr-runtime/issues/523
- **Alarma exacta (`setExactAndAllowWhileIdle`)**: AOSP confirma que **sí dispara en Doze**, pero limitada (~1/15 min por app; doc Android ~1/9 min), y la exención de batería **no quita esa cuota** https://source.android.com/docs/core/power/platform_mgmt?hl=es-419 · https://developer.android.com/develop/background-work/services/alarms/schedule. `setAlarmClock()` no se difiere y saca de Doze (misma fuente). En Android 14, `SCHEDULE_EXACT_ALARM` **no se concede por defecto** (target ≥33; consultar `canScheduleExactAlarms()`): https://developer.android.com/about/versions/14/changes/schedule-exact-alarms. **En ZTE:** si el proceso está en `frozen-list` la alarma no llega a ejecutarse (evidencia cfreezer arriba) → la alarma exacta es red de seguridad, no garantía. Patrón equivalente en otras apps: en Motorola solo quedó estable concediendo appops por ADB (no admisible en producción): https://www.traccar.org/forums/topic/solution-traccar-client-not-sending-locations-in-background-on-motorola-android-15/

## 7. Recomendación final priorizada (Z2450, usuaria + app, solo APIs públicas)

| # | Acción | Quién | Evidencia | Confianza |
|---|---|---|---|---|
| 1 | **Batería → Gestión inteligente → DMujeres → "Sin control"** (botón de guía ya existente). Es la whitelist que el freezer respeta | usuaria (2 toques) | DisplayXR #523 (única efectiva) + ZTE ES | ALTA |
| 2 | **Batería → Optimización de batería → DMujeres → No optimizar** (mantener; ya en onboarding) | usuaria | ZTE oficial + AOSP 14 | ALTA (necesaria) |
| 3 | **App DMujeres → desactivar "Pausar actividad en la app si no se usa"** | usuaria | AOSP/Mundobytes | ALTA (necesaria) |
| 4 | Mantener **ahorro de energía apagado** y sin modo programado | usuaria | Traccar forum (pisa exenciones) | MEDIA-ALTA |
| 5 | Actualizar a **MyOS 14.0.16+** (parche 2025-10) si está disponible | usuaria | gsmzone build dump | MEDIA |
| 6 | **FGS + promoción inmediata + `PARTIAL_WAKE_LOCK`**; alarma del keeper **exacta condicional** (`canScheduleExactAlarms()` → `setExactAndAllowWhileIdle`/`setAlarmClock`) | app | AOSP alarmas + DisplayXR (FGS como fix) + gap P0 del proyecto (`docs/audit/R3_AGENT_REPORT.md:8`) | ALTA |
| 7 | **Medir continuidad post-guía** y distinguir freeze vs Doze (prueba conductual, `ApplicationExitInfo.REASON_FREEZER`, drift `elapsedRealtime`); reportar por ROM | app | API 33 + docs del proyecto | MEDIA |
| 8 | Candado en Recientes **si la ROM lo muestra** (bajar tarjeta y tocar candado) | usuaria | r/ZTE (modelos antiguos) | BAJA |
| 9 | **No hacer:** desinstalar/congelar `com.zte.powersavemode`, root/Shizuku, desactivar freezer AOSP por ADB | nadie | debloat "Not Safe to Delete" + DisplayXR (ignorado) | ALTA |

## Fuentes adicionales (campo del proyecto)
- `docs/audit/OEM_RESEARCH_ZTE_XIAOMI.md` (§1.1 y §3), `docs/audit/OEM_BACKGROUND_AUDIT.md:68,95`, `docs/GAP_ANALYSIS.md:41`, `docs/audit/R4_REAL_WORLD_VALIDATION.md:173`, `docs/audit/ROUTE_SEGMENTATION_AUDIT.md:161-163`, `mobile/app/src/main/java/com/dmujeres/traccar/oem/VendorSettings.kt:215-231`.
- Datos de campo del síntoma (mediana 9 s / p90 897 s; bloques 15–45 min) aportados por la operación del proyecto (dispositivo `macias`), sin URL pública.
- `dontkillmyapp.com/zte` → 404 (verificado 2026-09-19).
