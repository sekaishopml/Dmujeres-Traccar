# OS_DEVICE_INFINIX_X6531 — Investigación OS/dispositivo (Hot 50i, XOS, Android 14)

> Fecha 2026-09-19 · solo lectura (no se tocó código de la app) · fuentes: Infinix/foros/OSS/Pithus/AOSP + evidencia interna del repo.
> Contexto medido en `santiago` (app 1.1.10): mediana 12 s en marcha, p90 929 s quieto, exención de batería y auto-inicio activos.

## 0. Resumen ejecutivo

1. **X6531 = Infinix Hot 50i (2024)**, Android 14 + XOS 14.x; **no** es Smart 9 (X6532) ni Hot 50 5G (X6720). Sin OTA oficial a XOS 15 confirmada.
2. El p90 ≈ 929 s **no** corresponde a Doze profundo AOSP (ventanas de mantenimiento 1 h que duplican hasta 6 h): encaja con el techo de *light doze* (15 min) y, sobre todo, con **Hiber de Transsion**, que congela y **proxya toda alarma** (incluso `setAlarmClock`/exactas) pese a exención AOSP y FGS.
3. Con exención de batería, el FGS location de Android no debería ser tocado por Doze estándar; los cortes observados apuntan a capa OEM (Hiber + Phone Master/Power Manager + Freezer), no a Doze “de fábrica”.
4. **No hay API pública** para whitelistear Hiber ni para blindar alarmas en XOS. `setExactAndAllowWhileIdle` + exención es **necesario pero no suficiente** en XOS.
5. Mejora realista: refuerzo de app (keeper 2 min ya existe; MQTT keepalive corto + reconexión + outbox HTTP) y guía exacta de pantallas XOS; el resto es comportamiento del OEM no evitable sin root/ADB.

## 1. Identidad del dispositivo

| Dato | Valor | Evidencia | Confianza |
|---|---|---|---|
| Modelo comercial | **Infinix HOT 50i** (X6531/X6531B), 2024 | https://soft9.us/devices/Infinix/Infinix-X6531 · https://www.deviceinfohw.ru/devices/item.php?item=104727 (MODEL_NAME = “Infinix HOT 50i”) | Alta |
| No es | Smart 9 = **X6532**; Hot 50 5G = **X6720** | https://www.gsmchoice.com/en/catalogue/infinix/smart-9/?variant=43863 · kits con pantalla X6720/X6531/X6532: https://www.jumia.com.ng/slp/infinix-x6531-hot-50i | Alta |
| SoC / RAM | MediaTek Helio G81 (mt6768), 4–6 GB | https://www.deviceinfohw.ru/devices/item.php?item=104727 · https://mobile2000.com/products/infinix-hot-50i-128gb-6gb-ram-6-7-inch-black | Alta |
| OS de fábrica | **Android 14 (API 34) + XOS 14.0/14.x** | https://infinixcompare.com/infinix-hot-50i-complete-2025-review-guide/ · Smart 9 (mismo baseline X653x) sale con XOS 14.0: https://www.gsmchoice.com/en/catalogue/infinix/smart-9/?variant=43863 | Alta |
| XOS 15/Android 15 | **No confirmado**; listas lo excluyen y en 2026 la serie Hot 50 figura EOL | https://infinixmob.com/xos-15-and-infinix-android-15-update-list/ (“HOT 50i won’t get Android 15”) | Media |
| Firmware | Rama `X6531-…-U-OP`; build público 251212V3214 (dic-2025); el tuyo `…260713V3301` (jul-2026) es más nuevo, misma rama → XOS 14.x | https://www.clansoft.net/dl/index.php?a=downloads&b=file&id=21094 | Media |
| “Última versión” | No hay canal público de release notes de Infinix; usar **Ajustes → Acerca del teléfono → Versión de XOS** como fuente de verdad | https://www.codewithkarani.com/blog/oem-autostart-battery-optimisation-android-deep-links | Baja |

Nota honesta: las cadenas exactas de “versión XOS” para X6531 no están publicadas por Infinix; se infieren del baseline XOS 14.0 de la familia y de la rama de firmware.

## 2. Nombres y rutas exactas (XOS en español)

> Regla práctica: si un literal no aparece, usar la lupa de **Ajustes** con: “Auto-inicio”, “Batería”, “Ahorro”, “Congelar”, “Pantalla apagada”.

| Pantalla buscada | Ruta en español (literal más probable) | Componente/paquete | Evidencia | Confianza |
|---|---|---|---|---|
| Auto-inicio | **Phone Master → Caja de herramientas → Gestión de auto-inicio** (variantes: “Administración de inicio automático”, “Gestión de inicio automático”) | `com.transsion.phonemaster/com.cyin.himgr.autostart.AutoStartActivity` (exported) | https://tispy.net/es/configuracion/ · https://skedit.zendesk.com/hc/es/articles/360014688579 · Pithus exported: https://beta.pithus.org/report/bb8131a2b891ccd39295f1df157f7d2f30d161dba318392f692c8fe8b7bce69d · repo: docs/audit/OEM_RESEARCH_INFINIX_TECNO.md | Alta en ruta; media en literal |
| Ahorro de energía por app | **Ajustes → Apps → DMujeres → Batería → Sin restricciones** / **Phone Master → Gestión de apps → permitir actividad en segundo plano** | AOSP `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`; per-app XOS | https://tiktask.ai/es/guides/infinix-tecno-power-manager-tiktask · https://tiktask.ai/es/blog/keep-automation-alive-xiaomi-infinix-tecno-checklist | Alta |
| Battery Lab / ahorro por apps | **Ajustes → Batería → Battery Lab → Power Saving Management for apps = OFF** (ES: “Ahorro de energía de las aplicaciones”) | `com.transsion.batterylab/...BatteryLab$TranAppSavingActivity` | https://dontkillmyapp.com/tecno · Pithus: https://beta.pithus.org/report/46710cf43493f970c8e33be0deb04ff5559aea0bd0a0b3e620b35d6e3eee3a26 · https://github.com/MuntashirAkon/android-debloat-list/issues/73 | Alta componente; media literal ES |
| Ajustes → Batería | **Ajustes → Batería** (también “Administrador de energía” en guías) | `com.android.settings` | https://tiktask.ai/es/blog/keep-automation-alive-xiaomi-infinix-tecno-checklist | Alta |
| Power Manager | Pantalla dentro de **Phone Master** (no paquete aparte): Power Boost, Ultra Power Saving | `com.transsion.phonemaster/com.cyin.himgr.powermanager.views.activity.PowerManagerActivity` | https://dontkillmyapp.com/tecno · repo: docs/audit/OEM_RESEARCH_INFINIX_TECNO.md | Alta componente; media pantalla |
| “Bloqueo con pantalla apagada” | Cancelos de **Phone Master → Avanzado**: *Screen off sleep*, *Screen off push block*, *Screen off scheduled push* (ES: “Suspensión/notificaciones con pantalla apagada”) | Sin componente público | https://dontkillmyapp.com/tecno | Media (literal ES no verificado) |
| Congelar aplicaciones | **Phone Master → Herramientas → Freezer/Congelar** y **XOS Launcher → Freezer** | `com.cyin.himgr.applicationmanager.view.activities.AddFreezeAppActivity` (exported) | https://play.google.com/store/apps/details?id=com.transsion.XOSLauncher&hl=en · https://ugtechmag.com/freeze-apps-on-android/ · repo: docs/audit/OEM_RESEARCH_INFINIX_TECNO.md | Alta componente; **NO usar** (efecto contrario) |
| Candado en Recientes | **Recientes → icono de la app → 🔒 Bloquear** | No existe API pública | https://dontkillmyapp.com/tecno · repo: docs/audit/OEM_RESEARCH_INFINIX_TECNO.md | Alta (solo manual) |

## 3. Componentes Transsion que pueden congelar/matar el FGS location

| Componente | Qué hace | Evidencia | Confianza |
|---|---|---|---|
| **Hiber** (`com.android.server.am.hiber`, dentro de `system_server`) | Estado propietario de hibernación: al congelar, **elimina del `AlarmStore` todas las alarmas del UID** y las guarda en `mProxyedAlarms` hasta “descongelar”. **No exime tipos**: RTC_WAKEUP, exactas y `setAlarmClock`. El whitelist es JSON en `/system/product/hiber/config/hiber.json`, actualizable en la nube (TranCare/THub); terceros no están whitelisteados salvo casos puntuales (WhatsApp, GMS…). FGS “puede retrasar la hibernación, pero las alarmas se eliminan igual” | https://github.com/urbandroid-team/dont-kill-my-app/issues/3800 | Alta (decompilación de `services.jar` Transsion) |
| **Phone Master** (`com.transsion.phonemaster`, procesos `com.cyin.himgr.*`) | UI y política: AutoStart, **Power Manager**, **Network Manager** (`NetworkManagerService`), **Freezer** (`AddFreezeAppActivity`), limpieza | https://beta.pithus.org/report/bb8131a2b891ccd39295f1df157f7d2f30d161dba318392f692c8fe8b7bce69d | Alta |
| **Battery Lab / Power Saving** (`com.transsion.batterylab`, `com.transsion.powersave`) | “Power Saving Management for apps” y modo de ahorro: “detiene apps tras bloquear”, “stage frozen” según DKMA | https://dontkillmyapp.com/tecno · https://github.com/urbandroid-team/dont-kill-my-app/issues/474 | Alta |
| **Freezer de XOS Launcher** | Hibernación de apps desde el launcher (toggle Freezer) | https://play.google.com/store/apps/details?id=com.transsion.XOSLauncher&hl=en | Alta |
| **Sleep Mode Optimization** | “Activa modo avión por la noche” (rompe conexiones persistentes) | https://dontkillmyapp.com/tecno | Alta |
| “powergenie” | **No es Transsion**: es Huawei (EMUI/PowerGenie `com.huawei.powergenie`). No se encontró equivalente con ese nombre en XOS | https://xdaforums.com/t/power-genie-and-foreground-services.4436003/ · https://inthesk.net/help-help-with-android-huawei/?lang=en | Alta |

Evidencia interna: el 1.1.8 ya documentó X6531 “congelado OEM” con fixes **cada 15 min exactos** y despertares que sí funcionan (`docs/audit/R6_ROUTE_DENSITY.md`, líneas 9-16 y 42-50).

## 4. ¿Los ~15 min quieto son Doze estándar o Transsion?

- **Light doze (AOSP):** pantalla apagada 5 min → light idle 5 min; luego ventanas de mantenimiento con light idle de 10→15 min (tope 15 min). **Deep doze:** inactivo 30 min + “idle_after_inactive” 30 min → idle inicial **1 h**, duplicando hasta **6 h**. Fuentes: constantes de `DeviceIdleController` https://github.com/aosp-mirror/platform_frameworks_base/blob/nougat-release/services/core/java/com/android/server/DeviceIdleController.java · patrón 5/10/15: https://gitlab.e.foundation/e/os/android_frameworks_base/-/commit/953fc94599698f4b8690fb69aec70d377a468af8 · versión moderna (Android 10): https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/services/core/java/com/android/server/DeviceIdleController.java
- Con **exención de optimización de batería** el sistema no aplica Doze a la app (red y wake locks permitidos); sin exención, `setExactAndAllowWhileIdle` tiene suelo de **1 disparo cada 9 min** por app. Doc oficial: https://developer.android.com/training/monitoring-device-state/doze-standby · administración de energía (ES): https://source.android.google.cn/docs/core/power/mgmt?hl=es-419
- Por tanto: **929 s no es firma de deep doze AOSP** (serían ventanas ≥1 h) y con exención el FGS no debería cortarse. La firma sí coincide con **Hiber** (proxy de alarmas y congelación) y con el cap de light doze mientras el terminal sigue “quieto” pero no ha entrado a deep idle.
- **¿Sobrevive un guardián por alarma?** `setExactAndAllowWhileIdle` + exención **puede ser suficiente en AOSP, no en XOS**: Hiber elimina/proxya exactas y `setAlarmClock` (issue 3800). No existe API pública de whitelist; el único control es la UI de Phone Master (o `dumpsys activity hiber set_app_mode <uid> 1` por ADB, inviable en producción). La alternativa que sí ve evidencia de funcionar es la **cadena de `setExactAndAllowWhileIdle` re-armada desde receptor** (los despertares XOS a veces sí llegan; R6) + `startForegroundService` de recuperación, aceptando jitter.
- Conclusión de atribución: **más probable OEM (Hiber/Power Manager) que Doze estándar**; para cerrarlo al 100 % hace falta instrumentar en campo (`dumpsys deviceidle`, `dumpsys activity hiber`, Battery Historian). Confianza: media-alta.

## 5. MQTT / conexiones persistentes en XOS

- Doze (sin exención) **suspende la red** aunque haya FGS: el broker pierde el keepalive y publica LWT; la exención es la única solución y no se puede autoconceder (caso FreeKiosk): https://github.com/RushB-fr/freekiosk/issues/234
- El ping de MQTT basado en `TimerTask` **no corre con pantalla apagada** si el proceso no despierta; se necesita FGS/alarma y `automaticReconnect`: https://stackoverflow.com/questions/79835732/paho-android-mqtt-client-does-not-send-pingreq-to-broker-when-phone-screen-is-of
- XOS añade: **Sleep Mode** (modo avión nocturno programado), *Screen off push block/sleep*, y **restricción de datos por app** (Phone Master/`NetworkManagerService`); “los mensajes llegan al abrir la app” típico de datos en segundo plano restringidos: https://dontkillmyapp.com/tecno · https://www.sebertech.com/how-to-fix-infinix-gt-50-pro-with-background-apps-closing/
- Buenas prácticas del lado app: keepalive MQTT 30–120 s, reconexión con backoff + sesión limpia, y **fallback HTTP/outbox** (ya existe: `docs/audit/TRANSPORT_AUDIT.md`, outbox persistente). La posiciones del contrato DMJ hoy van por HTTP, no MQTT (TRANSPORT_AUDIT.md §3.1), lo que reduce exposición a LWT, pero no al socket de control.

## 6. Recomendación final priorizada

| # | Acción | Quién | Evidencia | Confianza |
|---|---|---|---|---|
| P1 | **Phone Master → Caja de herramientas → Gestión de auto-inicio: activar DMujeres** (ya hecho; reverificar tras actualización/OTA) | Usuario | https://dontkillmyapp.com/tecno · Pithus: https://beta.pithus.org/report/bb8131a2b891ccd39295f1df157f7d2f30d161dba318392f692c8fe8b7bce69d | Alta |
| P2 | **Ajustes → Apps → DMujeres → Batería → “Sin restricciones”** + exención AOSP (ya activa) | Usuario | https://tiktask.ai/es/guides/infinix-tecno-power-manager-tiktask · https://developer.android.com/training/monitoring-device-state/doze-standby | Alta |
| P3 | **Battery Lab → Power Saving Management for apps = OFF** para DMujeres; **Power Boost/Power Marathon = OFF** | Usuario | https://dontkillmyapp.com/tecno | Alta |
| P4 | **NO agregar DMujeres a Freezer/Congelar** y revisar que no esté en listas de congelación/hibernación | Usuario | https://play.google.com/store/apps/details?id=com.transsion.XOSLauncher&hl=en · repo docs/audit/OEM_RESEARCH_INFINIX_TECNO.md | Alta |
| P5 | **Candado 🔒 en Recientes** para DMujeres | Usuario | https://dontkillmyapp.com/tecno | Alta (manual, sin API) |
| P6 | **Desactivar Sleep Mode / modo avión programado** y *Screen off push block/sleep* | Usuario | https://dontkillmyapp.com/tecno | Media |
| P7 | **Datos en segundo plano permitidos** para DMujeres (sin Data Saver ni restricción por app) | Usuario | https://www.sebertech.com/how-to-fix-infinix-gt-50-pro-with-background-apps-closing/ | Media-alta |
| P8 | Mantener **keeper `setExactAndAllowWhileIdle` 2 min** con jornada + receiver de re-armado + `startForegroundService` de rescate; **no confiar en exactitud perfecta** en XOS | App | `docs/audit/R6_ROUTE_DENSITY.md` · https://github.com/urbandroid-team/dont-kill-my-app/issues/3800 | Alta |
| P9 | Endurecer transporte: keepalive MQTT corto, `automaticReconnect`, backoff y outbox HTTP como verdad de posiciones | App | https://github.com/RushB-fr/freekiosk/issues/234 · https://stackoverflow.com/questions/79835732/… | Media-alta |
| P10 | Instrumentar en campo retardos de keeper (`dt` de fixes) y, solo para diagnóstico puntual, `dumpsys activity hiber get_app_mode`; no usar ADB en producción | App/Operación | https://github.com/urbandroid-team/dont-kill-my-app/issues/3800 · docs/audit/R6_ROUTE_DENSITY.md | Media |

**Qué NO es posible (API pública):** leer/activar auto-inicio, whitelistear Hiber, abrir pantallas no exportadas (apps protegidas), candado de Recientes (repo docs/audit/OEM_RESEARCH_INFINIX_TECNO.md §3). Sin Accessibility/root no hay más blindaje que P1–P7 + P8/P9.

## Limitaciones de esta evidencia

- Componentes Pithus verificados **en APK**, no en la ROM X6531 real; el literal ES exacto de algunas pantallas puede variar por versión de XOS/región.
- La atribución 15 min = Hiber (y no otro mecanismo XOS) requiere `dumpsys`/Historrian en `santiago`; aquí se descarta deep Doze AOSP por constantes, no por traza.
- `dontkillmyapp.com/infinix` da **404** (marca sin página propia; se usa la de Tecno, mismo grupo): https://dontkillmyapp.com/infinix
