# OS/Device: HONOR LGN-LX3 — freeze de FGS de ubicación en MagicOS 9

- **Caso**: `com.dmujeres.traccar` (usuario "joseph"), FGS location + guardián por alarma, cadencia ~15 min incluso en movimiento, `isIgnoringBatteryOptimizations=true`.
- **ROM observada**: `LGN-L33 9.0.0.217(C605E10R3P1)` · Android 15 · fabricante HONOR.
- **Método**: revisión de foros (r/Honor, XDA), soporte oficial HONOR/HUAWEI, OSS (crossly, hushd, AutoStarter), docs AOSP/Android 15. Fecha: 2026-09-19.

## 1. Identificación del equipo

- **LGN-LX3 = HONOR X7d 4G**: 6.77" LCD 120 Hz, Snapdragon 685, 6500 mAh, Android 15 + MagicOS 9; anunciado 2025-08-25, liberado 2025-09-15. [GSMArena](https://www.gsmarena.com/honor_x7d-14093.php) · [DeviceAtlas](https://deviceatlas.com/device-data/devices/honor/lgn-lx3/88654964) · [HONOR MX (X7d)](https://www.honor.com/mx/phones/honor-x7d/). Confianza: **VERIFIED**.
- **"LGN-L33"** en el build: designación interna/variante de la misma familia LGN; no encontré documento oficial que la defina por separado. El modelo comercial asociado a `LGN-LX3` es X7d 4G. Confianza: **LIKELY**.
- **C605** = cust de América Latina en firmwares Huawei/HONOR (mismo patrón `C605E...R...P...` en equipos LATAM). [Ministry of Solutions (C605 Latin America)](https://ministryofsolutions.com/huawei-p30-pro-vog-l04-firmware-c605). Confianza: **LIKELY**.
- **MagicOS 9.0** = skin sobre Android 15; HONOR anunció mejoras del "motor AI de ahorro inteligente" en 9.0.0.157. [HuaweiCentral 9.0.0.157](https://www.huaweicentral.com/magicos-9-0-0-157-feature-update-rolling-out-for-honor-magic-7-6-series) · [HONOR MagicOS 9.0](https://www.honor.com/global/magic-os-9). Confianza: **VERIFIED**.

## 2. Mecanismos de congelado en MagicOS 9

- **PowerGenie/PowerKit (hibernación)**: componentes reales `com.hihonor.powergenie.core.hibernation.PGASHStateService`, `com.hihonor.android.powerkit.PowerKitService`, `PowerCheckerKitService`; un módulo **root/KernelSU** los desactiva porque no hay control de usuario. [crossly/honor-systemmanager-patch](https://github.com/crossly/honor-systemmanager-patch). Confianza: **VERIFIED** (existencia); efecto sobre FGS: **LIKELY**.
- **System Manager (limpieza de fondo)**: `com.hihonor.systemmanager.power.service.BgPowerManagerService` y `SavePowerManagerService`; mismo módulo root. [crossly](https://github.com/crossly/honor-systemmanager-patch). Confianza: **VERIFIED** (existencia).
- **PowerGenie historical**: mata apps fuera de su whitelist fija (Google/Facebook/…), sin opciones de usuario; sólo ADB/root lo quita. [dontkillmyapp/huawei](https://dontkillmyapp.com/huawei) · [XDA PowerGenie](https://xdaforums.com/t/remove-powergenie-to-allow-background-apps-to-receive-push-notifications.3890409/page-13) · [r/Honor "Aggressive background app killing"](https://www.reddit.com/r/Honor/comments/1qydm50/aggressive_background_app_killing). Confianza: **LIKELY** en MagicOS 9 (evidencia directa en 8/9 por reportes de usuarios).
- **Cached app freezer AOSP**: congela procesos *cached*; una app con FGS no entra en estado cached y no se congela por este mecanismo; en Android 14+ se congela 10 s tras pasar a cached. [source.android.com/cached-apps-freezer](https://source.android.com/docs/core/perf/cached-apps-freezer) · [Pushy (FGS no se congela)](https://support.pushy.me/hc/en-us/articles/44515682336653). Confianza: **VERIFIED** (AOSP); con overlay OEM puede no aplicar igual: **UNKNOWN**.
- **Gates de usuario**: "Optimización de batería" (lista Doze) e "Inicio de aplicaciones" con `Gestionar automáticamente` OFF + `Inicio automático`/`Inicio secundario`/`Ejecutar en segundo plano`, y **Smart tune-up** en Gestor/Optimizador. [HONOR EN 00428704](https://www.honor.com/za/support/content/en-us00428704) · [r/Honor 1qydm50](https://www.reddit.com/r/Honor/comments/1qydm50/aggressive_background_app_killing). Confianza: **VERIFIED**.
- **Ahorro de energía / ultra / alerta de consumo**: HONOR reconoce que "el mecanismo de reposo de Android y el ahorro de energía de la app trabajan juntos" para detener background; si el usuario acepta una "alerta de consumo", la app se cierra. [HONOR EN 00428704](https://www.honor.com/za/support/content/en-us00428704) · [HONOR UK 00685514](https://www.honor.com/uk/support/content/en-us00685514). Confianza: **VERIFIED**.
- **¿Cuál congela un FGS exento?** La evidencia apunta a **hibernación PowerGenie + limpieza de System Manager** (no al freezer AOSP): HONOR no garantiza background ni con FGS/exención, y usuarios reportan kills de apps con FGS (MacroDroid/Tasker) en MagicOS 9. [r/Honor 1oilu1z](https://www.reddit.com/r/Honor/comments/1oilu1z/honor_magicos_9_aggressive_battery_optimiser). Confianza: **LIKELY** (no hay API/documentación OEM).

## 3. Pasos EXACTOS (nombres ES) para MagicOS 9

1. **Inicio de aplicaciones (CRÍTICO)**: `Ajustes > Aplicaciones > Gestión del inicio de aplicaciones > [app]` → desactivar **"Gestionar todo automáticamente"** (o "Gestionar automáticamente") → activar **Inicio automático**, **Inicio secundario**, **Ejecutar en segundo plano**. [HONOR simulator ES us-es15872455](https://iknow-dl.service.hihonor.com/ctkbfm/applet/simulator/es-us15872455/index.html) · [SportsTrackLive (HONOR)](https://docs.sportstracklive.com/android-battery-saving/honor) · [express.ms (MagicOS 8/9)](https://express.ms/en/faq/background-work-fix). Confianza: **VERIFIED**.
   - Ruta alternativa: `Ajustes > Batería > [app] > Consumo de batería > Inicio de aplicaciones`. [SportsTrackLive](https://docs.sportstracklive.com/android-battery-saving/honor). Confianza: **LIKELY** (varía por modelo).
2. **Optimización de batería**: `Ajustes > Optimización de batería > triángulo invertido > Todas las aplicaciones > [app] > "No permitir"`. [HONOR MX ES 00428704](https://www.honor.com/mx/support/content/es-es00428704). Confianza: **VERIFIED**.
3. **Ahorro de energía OFF**: `Ajustes > Batería` → desactivar **Modo de ahorro de energía** y **Modo de ahorro de energía ultra**. [HONOR MX ES 00428704](https://www.honor.com/mx/support/content/es-es00428704). Confianza: **VERIFIED**.
4. **Mantener conexión al dormir**: `Ajustes > Batería > Más ajustes de batería` → **"Mantener conexión al dormir"** / *Stay connected while asleep* ON. [HONOR EN 00409806](https://www.honor.com/global/support/content/en-us00409806) · [HONOR tips 15866535](https://iknow-dl.service.hihonor.com/ctkbfm/applet/simulator/en-us15866535/index.html). Confianza: **VERIFIED**.
5. **Candado en Recientes**: abrir Recientes → deslizar la tarjeta hacia abajo hasta el candado. [HONOR EN 00406916](https://www.honor.com/global/support/content/en-us00406916) · [HUAWEI EN 15848669](https://consumer.huawei.com/en/support/content/en-us15848669). Confianza: **VERIFIED**.
   - **Caveat**: HONOR advierte que en **gama de entrada** las apps bloqueadas pueden limpiarse igual ("hardware resource limitations"). [HONOR EN 00406916](https://www.honor.com/global/support/content/en-us00406916). Confianza: **VERIFIED**.
6. **Notificaciones ON + Inicio secundario ON** (necesario para push/FCM). [HONOR UK 00685514](https://www.honor.com/uk/support/content/en-us00685514). Confianza: **VERIFIED**.
7. **"Apps protegidas"/"Protected apps"**: no encontré esa pantalla en MagicOS 9; es de EMUI 5–8 (si existiera, marcar la app ahí). [dontkillmyapp/huawei](https://dontkillmyapp.com/huawei) · [ProZvonki](https://pro-zvonki.ru/en/phone-setup/huawei). Confianza: **LIKELY** (no aplica en 9).
8. **Smart tune-up**: HONOR lo recomienda ON, pero usuarios de MagicOS 9 lo apagaron para que apps de fondo dejen de cerrarse. Decisión usuario según evidencia. [HONOR EN 00428704](https://www.honor.com/za/support/content/en-us00428704) vs [r/Honor 1qydm50](https://www.reddit.com/r/Honor/comments/1qydm50/aggressive_background_app_killing). Confianza: **LIKELY** (conflicto de fuentes).

## 4. MagicOS 9 vs MagicOS 7/8 (menús)

- MagicOS 7.1: ruta antigua `Ajustes > Batería > Inicio de aplicaciones`. [DriveQuant PDF MagicOS 7.1](http://drivequant.com/hubfs/Tuto%20Smartphones%20-%20EN/Honor/HONOR%20-%20MagicOS%207.1%20-%20%20EN.pdf). Confianza: **VERIFIED**.
- MagicOS 8/9: guías independientes ubican `Ajustes > Aplicaciones > Inicio de aplicaciones` y afirman que las rutas "coinciden con MagicOS 8 y 9". [express.ms](https://express.ms/en/faq/background-work-fix) · [SportsTrackLive](https://docs.sportstracklive.com/android-battery-saving/honor) · [JSP SOFT](https://jspsoft.co.kr/board_view.php?id=26). Confianza: **LIKELY**.
- MagicOS 9 refuerza el motor AI de batería (9.0.0.157) y hay reportes de comportamiento **más agresivo** en 9 que en 8, incluyendo kills de apps de sistema/automatización. [HuaweiCentral](https://www.huaweicentral.com/magicos-9-0-0-157-feature-update-rolling-out-for-honor-magic-7-6-series) · [r/Honor 1oilu1z](https://www.reddit.com/r/Honor/comments/1oilu1z/honor_magicos_9_aggressive_battery_optimiser). Confianza: **LIKELY**.
- **"Stay connected when device sleeps"** aparece en guías 8/9 como palanca de red en reposo. [HONOR 00409806](https://www.honor.com/global/support/content/en-us00409806). Confianza: **VERIFIED**.

## 5. Intents para "permitir actividad en segundo plano"

- Firma histórica Huawei/HONOR: `com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity`, `.optimize.process.ProtectActivity`, `.appcontrol.activity.StartupAppControlActivity`. [SO 68710845](https://stackoverflow.com/questions/68710845/how-to-go-auto-start-permission-page-in-honor-10-lite) · [AutoStarter](https://github.com/judemanutd/AutoStarter/blob/master/autostarter/src/main/java/com/judemanutd/autostarter/AutoStartPermissionHelper.kt). Confianza: **VERIFIED** (existieron).
- Desde 2021 la actividad de App Launch **no es abrible por terceros**: exige `com.huawei.permission.external_app_settings.USE_COMPONENT` (firma) → `SecurityException`. [SO 68100440](https://stackoverflow.com/questions/68100440/what-is-the-latest-auto-start-activity-intent-for-huaweis-system-settings) · [SO 76212870](https://stackoverflow.com/questions/76212870/security-exception-while-accessing-the-startupnormalapplistactivity-huawei-pho). Confianza: **VERIFIED** (Huawei/EMUI); en MagicOS 9: **LIKELY** igual.
- Paquete moderno del gestor: `com.hihonor.systemmanager` (MagicOS 10 confirmado; sin Activities públicas nuevas documentadas). [crossly](https://github.com/crossly/honor-systemmanager-patch). Confianza: **VERIFIED** (paquete).
- **Para MagicOS 9 lo documentado y portable es lo estándar Android**: `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (sujeto a política Play), `ACTION_APPLICATION_DETAILS_SETTINGS`; chequeos `isIgnoringBatteryOptimizations`, `isBackgroundRestricted`, bucket de App Standby. [developer.android.com/doze](https://developer.android.com/training/monitoring-device-state/doze-standby). Confianza: **VERIFIED**.
- **No encontré** una pantalla/Intent nuevo público específico de MagicOS 9 más allá de los ya conocidos (`StartupNormalAppListActivity`, `HwPowerManagerActivity`; `DetailOfSoftConsumptionActivity` protegida). Confianza: **UNKNOWN / NO DOCUMENTADO**.

## 6. Experiencias de otras apps GPS/tracker en HONOR

- **SportsTrackLive (GPS deportivo)**: "los HONOR están entre los más agresivos cerrando apps de fondo"; su fix = App launch manual (3 toggles) + exención + candado, y afirma que **"Run in background es lo que evita el freeze con pantalla apagada"**. [SportsTrackLive](https://docs.sportstracklive.com/android-battery-saving/honor). Confianza: **VERIFIED** (guía del vendor); resultado universal: **LIKELY**.
- **Traccar Client (Android 15, Motorola)**: background confiable sólo tras `cmd appops set ... RUN_ANY_IN_BACKGROUND allow` + `dumpsys deviceidle whitelist +pkg` (por ADB). Evidencia de que aun en AOSP hay gates ocultos. [Traccar forum](https://www.traccar.org/forums/topic/solution-traccar-client-not-sending-locations-in-background-on-motorola-android-15). Confianza: **VERIFIED** (caso puntual; no HONOR).
- **MacroDroid/Tasker en MagicOS 9**: la optimización AI los mata aun con FGS; workaround del usuario = repetidores periódicos para "entrenar" al AI + control manual de batería + delays. [r/Honor 1oilu1z](https://www.reddit.com/r/Honor/comments/1oilu1z/honor_magicos_9_aggressive_battery_optimiser). Confianza: **LIKELY** (reporte de usuario).
- **Stack típico que funciona en EMUI/HONOR**: FGS con notificación persistente + wakelock parcial; en EMUI 4 Huawei **no mataba** si el wakelock usaba tags whitelist (`AudioMix`, `AudioIn`, `AudioDup`, `AudioDirectOut`, `AudioOffload`, `LocationManagerService`) — útil como pista histórica, no documentado en MagicOS 9. [dontkillmyapp/huawei](https://dontkillmyapp.com/huawei). Confianza: **UNKNOWN** en MagicOS 9.
- **Candado en Recientes**: HONOR lo documenta como medida para evitar cierres y usuarios reportan éxito combinándolo con "Battery Optimization → Don't allow"; pero HONOR advierte límites en gama de entrada (X7d lo es). [HONOR 00406916](https://www.honor.com/global/support/content/en-us00406916) · [r/Honor 1qydm50](https://www.reddit.com/r/Honor/comments/1qydm50/aggressive_background_app_killing). Confianza: **LIKELY**.
- **hushd (OSS, accesibilidad)**: automatizaba los toggles de autostart en MagicOS 7/8; archivado y **no soporta 9**; requiere Accessibility (excluido por requisito). [Labushuya/hushd](https://github.com/Labushuya/hushd). Confianza: **VERIFIED**.

## 7. MQTT / conexiones persistentes en background

- **No hay prohibición documentada de MQTT keepalive en MagicOS 9**: búsquedas en foros/XDA/GitHub no arrojaron una restricción específica OEM. Confianza: **UNKNOWN / SIN EVIDENCIA** (no confundir con "no ocurre").
- Lo que sí aplica: Doze **suspende red** y difiere alarmas; una app exenta de batería puede usar red y wakelocks parciales en Doze (parcialmente exenta). [developer.android.com/doze](https://developer.android.com/training/monitoring-device-state/doze-standby). Confianza: **VERIFIED**.
- Doze ligero se activa al apagar pantalla **incluso en movimiento**, y `setAndAllowWhileIdle` permite **1 alarma/15 min en Doze** → consistente con la cadencia ~15 min observada. [source.android.com/platform_mgmt](https://source.android.com/docs/core/power/platform_mgmt). Confianza: **LIKELY** (explica el síntoma; no prueba causa OEM).
- HONOR ofrece "Stay connected while asleep" para mantener conexión en reposo, y documenta que Wi-Fi puede desconectarse con pantalla apagada si está desactivado. [HONOR 00409806](https://www.honor.com/global/support/content/en-us00409806). Confianza: **VERIFIED**.
- Android 15 limita FGS `dataSync`/`mediaProcessing` (timeout), **no** el tipo `location`. [Android 15 FGS changes](https://developer.android.com/about/versions/15/changes/foreground-service-types). Confianza: **VERIFIED**.

## 8. Recomendación final priorizada

**Usuaria (en el teléfono, en orden):**
1. `Ajustes > Aplicaciones > Gestión del inicio de aplicaciones > [app]`: Gestionar automáticamente OFF + los **3 toggles ON** ([HONOR ES](https://iknow-dl.service.hihonor.com/ctkbfm/applet/simulator/es-us15872455/index.html)).
2. `Ajustes > Optimización de batería`: la app en **"No permitir"** aunque ya reporte exención ([HONOR MX ES](https://www.honor.com/mx/support/content/es-es00428704)).
3. `Ajustes > Batería > Más ajustes de batería`: **Mantener conexión al dormir ON**; ahorro/ultra OFF ([HONOR](https://www.honor.com/global/support/content/en-us00409806)).
4. Candado en Recientes ([HONOR](https://www.honor.com/global/support/content/en-us00406916)) — con expectativa limitada por ser gama de entrada.
5. No aceptar "cerrar" si aparece alerta de consumo alto; mantener notificaciones ON ([HONOR UK](https://www.honor.com/uk/support/content/en-us00685514)).
6. Verificación real: caminar 10 min con pantalla apagada y confirmar fixes en el panel antes de dar por resuelto ([SportsTrackLive](https://docs.sportstracklive.com/android-battery-saving/honor)).

**App (solo APIs/intents públicos, sin Accessibility/root):**
1. Mantener FGS location **continuo** como fuente de captura; no depender de alarmas para posicionar (en Doze hay tope de 1/15 min) ([AOSP](https://source.android.com/docs/core/power/platform_mgmt)).
2. Deep links estándar de batería (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` con política Play, fallback a `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`) + guía OEM HONOR en la app ([Android](https://developer.android.com/training/monitoring-device-state/doze-standby)).
3. Diagnóstico `isIgnoringBatteryOptimizations` / `isBackgroundRestricted` / bucket; degradar a `SUPPORTED_WITH_GUIDANCE` en HONOR ([repo: OEM_COMPATIBILITY.md](../OEM_COMPATIBILITY.md)).
4. FCM recovery + notificación "Toca para reanudar" si el sistema bloquea arranque en background (patrón ya previsto en el producto) ([repo: OEM_COMPATIBILITY.md](../OEM_COMPATIBILITY.md)); HONOR exige **Inicio secundario** para push ([HONOR UK](https://www.honor.com/uk/support/content/en-us00685514)).
5. Outbox/cola local con replay tras huecos (ya existe) para no perder jornada ([repo: INCIDENT_DIAGNOSIS.md](../INCIDENT_DIAGNOSIS.md)).
6. MQTT: keepalive + watchdog de liveness con reconexión y LWT; no asumir que el socket sobrevive al freeze ([Doze](https://developer.android.com/training/monitoring-device-state/doze-standby)).
7. **No** intentar `StartupNormalAppListActivity` en MagicOS 9 sin `resolveActivity` + try/catch; está protegida ([SO](https://stackoverflow.com/questions/68100440/what-is-the-latest-auto-start-activity-intent-for-huaweis-system-settings)).
8. Registrar en el repo el estado de este equipo como `DEGRADED/OEM_LIMITATION` hasta tener evidencia de continuidad en jornada ([repo: OEM_COMPATIBILITY.md](../OEM_COMPATIBILITY.md)).

## 9. Tabla final: acción → quién → evidencia → confianza

| Acción | Quién | Evidencia | Confianza |
|---|---|---|---|
| App launch manual (3 toggles) | Usuaria | [HONOR ES](https://iknow-dl.service.hihonor.com/ctkbfm/applet/simulator/es-us15872455/index.html), [SportsTrackLive](https://docs.sportstracklive.com/android-battery-saving/honor) | VERIFIED |
| Optimización de batería "No permitir" | Usuaria | [HONOR MX ES](https://www.honor.com/mx/support/content/es-es00428704) | VERIFIED |
| Mantener conexión al dormir ON | Usuaria | [HONOR 00409806](https://www.honor.com/global/support/content/en-us00409806) | VERIFIED |
| Batería: ahorro/ultra OFF | Usuaria | [HONOR MX ES](https://www.honor.com/mx/support/content/es-es00428704) | VERIFIED |
| Candado en Recientes | Usuaria | [HONOR 00406916](https://www.honor.com/global/support/content/en-us00406916), [r/Honor](https://www.reddit.com/r/Honor/comments/1qydm50/aggressive_background_app_killing) | LIKELY (límite en gama baja) |
| Notificaciones ON | Usuaria | [HONOR UK](https://www.honor.com/uk/support/content/en-us00685514) | VERIFIED |
| No aceptar alerta de consumo (cierre) | Usuaria | [HONOR UK](https://www.honor.com/uk/support/content/en-us00685514) | VERIFIED |
| Smart tune-up OFF (si sigue fallando) | Usuaria | [r/Honor](https://www.reddit.com/r/Honor/comments/1qydm50/aggressive_background_app_killing) vs [HONOR](https://www.honor.com/za/support/content/en-us00428704) | LIKELY (conflicto) |
| FGS location continuo + no depender de alarmas | App | [AOSP](https://source.android.com/docs/core/power/platform_mgmt) | VERIFIED (diseño) |
| Deep links estándar de batería + fallback | App | [Android](https://developer.android.com/training/monitoring-device-state/doze-standby) | VERIFIED |
| FCM recovery + "Toca para reanudar" | App | [HONOR UK](https://www.honor.com/uk/support/content/en-us00685514) | LIKELY |
| Outbox + replay post-hueco | App | [repo](../INCIDENT_DIAGNOSIS.md) | VERIFIED (implementado) |
| MQTT keepalive + watchdog/reconexión | App | [Doze](https://developer.android.com/training/monitoring-device-state/doze-standby) | LIKELY |
| No usar `StartupNormalAppListActivity` sin try/catch | App | [SO 68100440](https://stackoverflow.com/questions/68100440/what-is-the-latest-auto-start-activity-intent-for-huaweis-system-settings) | VERIFIED |
| Intent nuevo oculto de MagicOS 9 | — | no encontrado | UNKNOWN |
| Prohibición específica de MQTT en MagicOS 9 | — | no encontrada | UNKNOWN |

## 10. Vacíos de evidencia

- No hay documentación OEM sobre PowerGenie/hibernación en MagicOS 9 ni API pública: toda afirmación de efecto es **LIKELY** y debe validarse con datos del producto (continuidad/health snapshots).
- No se encontró hilo de HONOR Community (LATAM/global) específico para X7d/MagicOS 9 con GPS en background; la evidencia fuerte es soporte oficial + guías de apps GPS + reportes r/Honor.
- La relación exacta entre `LGN-LX3` (market) y `LGN-L33` (build) no está documentada oficialmente.
