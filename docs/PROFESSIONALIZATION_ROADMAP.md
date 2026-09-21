# Ruta a producto profesional (nivel Google / OSS maduro)

Auditoría del estado actual (2026-09-19). Base: cómo trabajan Traccar, Home
Assistant, Signal, OwnTracks y apps de Google: **nunca pierden datos, se
enteran antes que la usuaria, actualizan sin romper, y el producto se explica
solo**. Lo que ya tenemos en ese nivel: 676/840/143 tests, OTA con sha256 y
anti-downgrade, FCM recovery con auditoría end-to-end, reglas de arquitectura
congeladas por test, observabilidad (device_health, diagnósticos, Sentry),
watchdog de servidor, guías OEM, docs internas.

## Fase 0 — Confiabilidad de producción (P0, YA)

1. **Respaldo automático de BD** (hoy: último dump 12-sep manual; riesgo real).
   `backup.sh` ya existe y verifica el dump → cron diario 03:00 + retención 14
   días + prueba de restore mensual documentada.
2. **Alertas al admin** (hoy: nadie se entera hasta mirar el panel):
   - Servidor caído / reiniciado por watchdog.
   - Dispositivo en jornada sin reportar > 10 min.
   - `PROCESS_FREEZE_DETECTED` o caída de `RECOVERY_SOFT_SUCCESS`.
   - Falla de instalación OTA repetida.
   Canal propuesto: bot de Telegram (gratis, inmediato, sin depender de correo).
3. **Purga/retención**: política de posiciones (Traccar `scheduled purge`),
   alerta de disco > 80%.

## Fase 1 — Actualizaciones que no rompen (P1)

4. **OTA gradual**: `latest.json` por porcentaje (hash de device) — el primero
   en recibir una versión no son los 40; pausa automática si `crashes24h`
   sube; kill-switch (`minVersionCode`) ya existe.
5. **Pruebas instrumentadas/UI** de flujos críticos (arranque/parada de
   jornada, permisos, instalación OTA) — hoy solo 2 tests de esquema.
6. **CI continua**: los workflows existen (server+mobile); verificar que corren
   (remoto o runner local), + ktlint/detekt.

## Fase 2 — Producto nivel Google (P1/P2)

7. **Jornada automática** (el salto más "profesional"): detectar viaje por
   movimiento sostenido (significant motion + velocidad) y abrir jornada sin
   que la colaboradora toque nada; sugerir cierre al estar parada Z min.
   Patrón de Google Timeline/Strava.
8. **Historial en la app**: mis recorridos (hoy solo el panel web).
9. **Acciones en la notificación persistente** ("Publicar ahora", "Pausar") y
   agrupación de avisos.
10. **Novedades / ayuda in-app** (changelog por versión, centro de ayuda).

## Fase 3 — Seguridad y cumplimiento (P2)

11. **HTTPS real** (dominio + Let's Encrypt; eliminar excepción HTTP legado).
12. **Credenciales cifradas** en el teléfono (EncryptedSharedPreferences) y
    gestión formal de la clave de firma de flota.
13. **Privacidad**: política publicada, retención definida, borrado a pedido
    (LOPDP Ecuador).

## Fase 4 — Escala y soporte

14. Monitoreo visual (Uptime Kuma) + tablero de flota (salud, congelados,
    cumplimiento por equipo).
15. Runbooks + simulacro trimestral de restore; plan de capacidad del server.

## Recomendación de arranque

Fase 0 completa hoy/mañana (backup + alertas + purga): es lo que separa un
piloto de un producto en producción. Luego Fase 1.4 (OTA gradual) y Fase 2.7
(jornada automática) que son las de mayor valor visible.


---

## Estado de ejecución (2026-09-19, alcance hasta Fase 2)

**Fase 0 ✅**
- Respaldo diario cron 03:00 + verificación semanal (`verify-backup.sh`) +
  retención 30 días (ya en `backup.sh`) + backup de prueba ejecutado.
- **Alertas SOLO en panel web**: `GET /api/admin/alerts` (auth admin) con
  equipos en jornada sin reportar >10 min, `PROCESS_FREEZE_DETECTED`,
  `CRITICAL` 24 h, `RECOVERY_TIMEOUT/BLOCKED` 24 h, y estado del sistema
  (respaldo, disco, arranque del server, reinicios del watchdog). Sección
  "Alertas" en el dashboard (menú Reportes, solo admin) con severidades y
  "Todo en orden" si no hay nada.
- Watchdog escribe `/var/log/dmj/watchdog.log`; el panel lo muestra.
- Purga de datos: NO automatizada (decisión del dueño pendiente; el disco se
  vigila en el panel).

**Fase 1 ✅ (parcial, honesto)**
- OTA gradual real: `GET /api/mobile/v1/ota` decide por equipo
  (`OtaRolloutPolicy`: FORCED si `installedCode < minVersionCode`, pausa,
  bucket SHA-256 estable), gobernado por `dashboard/public/rollout.json`
  (`{"percent":0-100,"paused":bool}`) editable en caliente; `publish-ota.sh`
  lo escribe si se pasa `OTA_ROLLOUT_PERCENT`. App 1.1.14 consulta el
  endpoint con fallback a `latest.json`. Publicado 1.1.14 con percent=100.
- Pausa AUTOMÁTICA por crashes: no implementada (documentada como pendiente).
- Pruebas instrumentadas/UI: pendientes (requieren emulador/dispositivo).
- CI: workflows existen; falta correrlos en un remoto (repo sin push).

**Fase 2 ✅ (parcial, honesto)**
- **Jornada automática**: `AutoJourneyPolicy` pura + integración en
  TrackingService + interruptor visible (default OFF) + notificación de
  inicio automático y sugerencia de cierre; configurable por config remota
  (`autoJourney`).
- Pendiente: acciones en la notificación persistente y pantalla de
  "Novedades/ayuda" (menores).

Tests: server 862/0 · mobile 693/0 · dashboard OK + build.
Desplegado: server jar + dashboard build (endpoints verificados:
`/api/admin/alerts` 401 sin sesión, `/api/mobile/v1/ota` sirve 1.1.14).


---

## Cambio de alcance por decisión del negocio (2026-09-19 noche)

- **Jornada automática RETIRADA por completo** (código, interruptor, config
  remota y tests): es una app de **validación laboral (CCTV)** donde la jornada
  la inician y detienen **manualmente** las colaboradoras — un automatismo
  tendría implicaciones disciplinarias/legales. Queda solo el botón manual.
- Evidencia auditable intacta: el servidor registra `mobileJourneyStarted` /
  `mobileJourneyEnded` (equipo + hora) y el replay dibuja la ruta real
  (map-matching + tramos medidos/estimados marcados).
- **Publicado 1.1.16 (vc126)** "Mejoras de estabilidad" — OTA verificada
  (sha256 `97064409…`). Supersede a 1.1.15.
- Tests: móvil **695/0** + lint.
