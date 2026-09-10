# Handoff: fix rutas incompletas (santiago) — 2026-09-08

## Diagnóstico confirmado (datos reales de dmj-db y dmj-mqtt)
- Dispositivo santiago (id=2). Ayer 1799 mensajes `accepted` (server OK, no hay rejects).
- Trayecto B→A 20:24–20:49 UTC: SOLO 5 puntos en 25 min, todos `speed=0`.
- Clusters de 100+ posiciones IDÉNTICAS en lugares quietos (fix FLP cacheado de red wifi).
- Atributos device: `fixReceived=986, fixRejected=0, fixEnqueued=986`, `gnssUsed=0`,
  `gnssTotal=1`, `simPresent=false`, `bufferMax=500` (empujado por remote config),
  appVersion 1.0.67, intervalSeconds=10.
- EMQX: 18× `authentication_failure` (bad_username_or_password) de clientid
  `dmj-test-santiago-*` (test de DiagnosticsActivity) — credenciales desfasadas.
- CAUSA RAÍZ: el P8 nunca fija GNSS; todo son re-entregas de red cacheada. FixFilter
  regla `non_monotonic` (FixFilter.kt ~158) ACEPTA duplicados si acc<20 → clusters.
  En carretera no hay wifi conocido → no hay fixes → sin ruta. "Fuera de conexión" es
  real (sin SIM). Servidor no deduplica coords repetidas.

## Contrato entre areas
- Envelope position añade `provider`: "gps"|"network"|"fused"|"unknown" y `fixAgeSec` (int).
- Server: si provider=="network" → attributes.network=true; guarda attributes.provider.
- Dedupe server: coords exactas + speed 0 + <120 s → no insertar posición, ACK `accepted`
  (la app drena cola solo con accepted — verificar en MqttManager.kt ~401).
- Dashboard: LineString de ruta filtra attributes.network===true Y colapsa duplicados
  consecutivos exactos (datos históricos sin provider); fallback si quedan <2 puntos.

## Tareas asignadas (sub-agentes)
1. MOBILE (/DMujeres-Tracking/mobile): stale_relay en FixFilter; no encolar coords
   idénticas <5 min sin avanzar lastFixAt; provider/fixAgeSec en Envelope; forzar GNSS
   (setForceLocationProviderEnabled API31+, fallback GPS_PROVIDER si >60 s sin fix);
   aviso "Sin señal GPS" (notificación + DiagnosticsActivity scan 30 s); clamp
   bufferMax remote >=1000; versionName 1.0.68; tests `./gradlew testDebugUnitTest` +
   `assembleDebug`.
2. SERVER (/DMujeres-Tracking/server): parsear provider; dedup handler (buscar
   Position.setInsertable); evento deviceStalled (patrón mobileNetworkLost); tests
   org.traccar.mobile.*; revisar auth-file.csv/ACL para santiago (NO rotar pass sin
   confirmar); actualizar RUNBOOK-SANTIAGO.md con evidencia de hoy.
3. DASHBOARD (/DMujeres-Tracking/dashboard): helper routeFilter puro + aplicarlo solo a
   geometría de ruta (mapa + replay); eslint + npm run build.

## Servidor VPS
- opencode corre como systemd `opencode.service` (puerto 4096, pid variable).
  Reinicio seguro SOLO desde otra terminal: `sudo systemctl restart opencode`
  (mata esta sesión; reanudar leyendo este handoff).
- Si vuelve a "detenerse" a mitad: revisar `journalctl -u opencode.service -n 100`
  y /var/log/syslog por OOM. RSS ~960 MB, RAM 10 GB, swap 0 — vigilar swap.
- Próximos pasos QA: build APK en mobile (./gradlew assembleDebug o build-apk.sh),
  deploy jar server, rebuild dashboard, y prueba de campo santiago al aire libre
  (si gnssUsados sigue en 0 → hardware GPS muerto, cambiar dispositivo).
