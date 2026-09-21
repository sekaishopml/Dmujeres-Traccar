# R7 — Qué falta para santiago, joseph, kevin, macias (2026-09-19)

Datos: `tc_positions`/`tc_device_health` (48 h) + `lastDiagnostics` + informes
`OS_DEVICE_*.md` (OS de cada modelo, verificados con fuentes).

## Estado medido por equipo

| Equipo | Modelo / OS / app | Moviendo (mediana) | Quieto (p90) | Diagnóstico |
|---|---|---|---|---|
| santiago (2) | Infinix Hot 50i X6531 · XOS 14 / A14 · **1.1.10** | **13 s** ✓ | 929 s | exempt ✓ oem ✓ readiness ready ✓; MQTT 5 recon. Sano |
| joseph (39) | HONOR X7d LGN-LX3 · MagicOS 9 / A15 · 1.1.9 | **891 s** ✗ | 930 s | exempt ✓ pero congelado hasta moviendo; mqtt 20 recon; 1 crash; oemConfirmed ✗; degraded |
| kevin (40) | Samsung A52 SM-A525M · One UI 6.1 / A14 · 1.1.7 | **5 s** ✓ | 215 s | mejor trazador; mqtt **163 recon/24h**; crash 1; auto-revoke pendiente |
| macias (50) | ZTE Blade A55 Z2450 · MyOS 14 / A14 · 1.1.7 | **9 s** ✓ | 897 s | congelados quieto pese a exempt+oem ✓; mqtt 80 recon; falta "Sin control" real |

## Causa raíz por equipo (de la investigación de OS)

- **joseph**: MagicOS 9 tiene PowerGenie/PowerKit y en **1.1.9 no existía alarma
  exacta**; el tope Doze de 1 alarm/15 min explica la cadencia de 891 s. Con
  batería exenta, **1.1.10 usa `setExactAndAllowWhileIdle`** → necesita
  actualizar. Además MagicOS 8/9 movió el menú a *Ajustes > Aplicaciones >
  Gestión del inicio de aplicaciones* (y refuerza el motor AI); nuestros
  deeplinks de inicio pueden estar protegidos por firma → la guía debe ser
  manual actualizada + "Mantener conexión al dormir" ON + candado.
- **kevin**: Doze corta el PINGREQ con pantalla apagada; nuestro
  `keepAliveInterval = 45 s` es frágil (broker corta a ~67 s). Subir a ~300 s
  con reconexión automática. "Aplicaciones sin autosuspensión" ya es abrible
  (activity_type=2) ✓. Falta guiar auto-revoke (API `getUnusedAppRestrictionsStatus`).
- **macias**: en ZTE el cfreezer **ignora** whitelist AOSP/appops: solo
  "Gestión inteligente → Sin control" des-congela. oemConfirmed=true no
  garantiza que el toggle esté puesto; 1.1.10 (freeze evidence + alarma
  exacta) permitirá confirmarlo con `PROCESS_FREEZE_DETECTED`.
- **santiago**: sin problemas; vigilar quieto (Hiber de Transsion proxya
  alarmas; Sleep Mode/ahorro de datos deben quedar off según su guía).

## Faltantes de arquitectura (transversales)

1. **P0 · Supervisión del servidor**: `dmj-traccar.service` está *dead* desde
   el 14-sep y hay un jar manual (PID 3398181) sin auto-reinicio ni alerta.
   Fix: systemd `Restart=always` + watchdog + aviso; cutover controlado.
2. **P0 · Adopción de OTA**: la app solo busca updates abierta (ticker 2 min
   en MainActivity). kevin/macias/joseph siguen viejos. Fix: enganchar el
   chequeo al `TrackingRecoveryWorker` (15 min) → descarga + notificación.
3. **P1 · MQTT keepalive**: 45 s → ~300 s con backoff (evidencia Samsung A52s:
   sin PINGREQ en Doze); KPI de reconexiones ya existe en diagnósticos.
4. **P1 · Guía HONOR MagicOS 8/9**: texto con la ruta nueva + los 3 toggles +
   "Mantener conexión al dormir"; asumir que los deeplinks pueden estar
   protegidos (fallback a ficha de app ya funciona).
5. **P2 · Wakelock parcial con jornada** (recomendación ZTE):
   mantiene CPU en tramos de fix bajo freezers; medir batería antes/después.
6. **P2 · Auto-revoke Samsung**: detectar y abrir la pantalla de gestión
   (`createManageUnusedAppRestrictionsIntent`) en la puesta a punto.
7. **P3 · Freeze evidence → monitoreo**: query periódica de
   `PROCESS_FREEZE_DETECTED` en `tc_device_health` (ya se envía en 1.1.10).

## Evidencia OS por equipo
- `OS_DEVICE_HONOR_LGNLX3.md` (X7d, MagicOS 9: PowerKit/PowerGenie, menús nuevos).
- `OS_DEVICE_SAMSUNG_A52.md` (One UI 6.1: sleeping/deep, keepalive, auto-revoke).
- `OS_DEVICE_ZTE_Z2450.md` (Blade A55, MyOS 14: cfreezer ignora AOSP; Sin control).
- `OS_DEVICE_INFINIX_X6531.md` (Hot 50i, XOS 14: Hiber Transsion proxya alarmas).


---

## Estado tras 1.1.11 (2026-09-19)

- **P0 servidor ✅**: `dmj-traccar.service` ahora `Restart=always` + watchdog
  `dmj-traccar-watchdog.timer` (cada 5 min, prueba HTTP real). Cutover hecho:
  jar manual reemplazado por systemd (11:33), ingesta verificada.
- **P0 OTA ✅**: `UpdateChecker` (worker 15 min) ya existía; ahora usa
  `OtaVersionPolicy` (versionCode) igual que MainActivity → badge/notificación
  consistente para kevin/macias/joseph/david.
- **P1 MQTT ✅**: keepalive 45 s → 300 s (`MqttManager.KEEP_ALIVE_SECONDS`),
  reconexión/backoff intactos; 75 tests de transport verdes.
- **P1 guías ✅**: HONOR (MagicOS 8/9 + mantener conexión al dormir),
  Samsung (auto-revoke + sin autosuspensión), ZTE (Sin control), Infinix
  (Hiber/Freezer/Sleep Mode). Test `VendorGuideContentTest`.
- **Nuevo ✅**: `ActivationGuideActivity` (estado verificado por API + pasos del
  fabricante + botones) accesible desde Diagnóstico; `DeviceFactsCollector` en
  `readiness` (regla de arquitectura respetada).
- **Nuevo ✅**: acceso oculto QA — 5 toques en la versión dentro de Diagnóstico
  → `DebugDesignActivity` (splash/onboarding/dash + preview de la guía).
- **Pendiente**: wakelock parcial (P2, requiere medición de batería), fusión de
  `PROCESS_FREEZE_DETECTED` en alertas del dashboard.


---

## R7-FCM desplegado (2026-09-19, servidor + app 1.1.12)

- **Umbrales del despertador FCM**: quieto 15 min → **6 min**; moviéndose
  5 min → **4 min** (base real de la app es 120 s desde 1.1.8). Cooldown de
  evento 15 → 6 min. Rate limit 5/h y cooldown 60 s ya existían.
- **Éxito suave**: `RECOVERY_SOFT_SUCCESS` (posición real ≤5 min tras el
  probe) — mide efectividad real sin fabricar el SUCCESS end-to-end
  (`FcmRecoveryPolicy.SOFT_SUCCESS_WINDOW_MS`, 840 tests server ✓).
- **Anti-degradación FCM** (investigación): Google degrada HIGH→normal si en
  7 días los mensajes no generan notificación visible → la app 1.1.12 actualiza
  la notificación persistente ("Recuperando la jornada…") al recibir el probe.
- Desplegado: jar nuevo + `systemctl restart` + ingesta verificada.
- Investigación completa en `ARCH_RESEARCH_OSS_APPS.md`,
  `ARCH_RESEARCH_PLATFORM_LIMITS.md`, `ARCH_RESEARCH_FCM_LIMITS.md`.
- **Pendiente de decisión**: wakelock parcial acotado (P2), sensor
  `TYPE_SIGNIFICANT_MOTION`, `setAlarmClock` como modo emergencia visible,
  acciones en la notificación ("publicar ahora"), `direct_boot_ok` FCM.


---

## 1.1.13 + replay impecable (2026-09-19 tarde)

- **App 1.1.13**: sensor de MOVIMIENTO SIGNIFICATIVO (patrón OwnTracks; pide fix
  al reanudar tras quietud — ataca el salto de kevin) + wakelock de jornada
  ACOTADO detrás de interruptor oculto (menú de diseño → "Wakelock de jornada").
  Android 14+: disponible; ≤13 se desactiva solo.
- **Replay** (`ARCH_RESEARCH_ROUTE_REPLAY.md`): accuracy real al matcher
  GraphHopper, `snappedRatio` en la respuesta, banda 100–200 m cerrada,
  leyenda "Medido/Estimado" + badge de calidad en Repetición Ruta.
- **Desplegado**: matchservice (:8991) + server jar + dashboard build.
- Tests: mobile 676/0 · server 840/0 · dashboard 143/0.
- Pendiente: medir wakelock en UN teléfono (batería vs densidad) y kevin/miguel
  actualizar (siguen en 1.1.7).
