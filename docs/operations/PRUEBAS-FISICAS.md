# Pruebas físicas — registro de sesiones ADB

Formato por sesión: fecha, equipo, app, objetivo, comandos, resultado,
evidencia. Solo hechos observados; sin extrapolar a otros OEM.

## Sesión 2026-09-28 — ZTE Z2450 (Android 14), app 2.1.73 (vc 283)

Conexión: ADB por Tailscale (`adb connect 100.86.171.63:46641`), latencia
~130-450 ms. Equipo identificado como **macias** (id 50) por batería (83%) y
cadencia coincidente con el panel de estado del teléfono.

Línea base (sin tocar nada):
- Batería 83%, sin cargador; exento de optimización (`deviceidle whitelist`);
  FINE+BACKGROUND+notificaciones concedidos.
- `TrackingService` en foreground (`isForeground=true`, tipo LOCATION 0x08).
- Jornada local abierta de ~72 h; buffer 0 pendientes; EN LÍNEA.
- Cadencia real en parado: fixes cada ~4-24 min (base 120 s estirada por Doze).

### TEST-8 Doze forzado (corto, 4 min)

Comandos: `dumpsys deviceidle force-idle` (14:18:04), espera 240 s, lectura
de `dmt_posicion`, luego `unforce`.
Resultado: el equipo siguió reportando cada ~4 min (14:18:04, 14:22:03) con
la misma cadencia previa. Sin degradación observable en 4 min de Doze forzado
en este ZTE con exención activa. Sin errores en logcat del servicio.
Conclusión parcial (no definitiva): el FGS + exención aguanta Doze corto en
este equipo. Repetir con 30+ min en campaña larga.

### TEST-6 Recreación de proceso (interrumpido)

Comando: `am kill com.dmujeres.traccar` (14:23:00). El canal ADB cayó al mismo
tiempo (puerto 46641 cerrado; ping OK): `adbd` dejó de escuchar en el
teléfono. Causa por determinar (depuración inalámbrica desactivada, reinicio
del equipo o timeout de autorización). Pendiente: revisar pantalla del
teléfono, reactivar depuración inalámbrica (el puerto puede haber cambiado) y
repetir. Último fix registrado antes del corte: 14:22:03.

### Hallazgo: duplicado real en producción (BUG-003)

Dos filas idénticas de macias con la misma hora GPS 14:22:03, mismas
coordenadas y velocidad 0 (ids 93992 y 93993, recibidas con 10 s de
diferencia): reintento del cliente tras POST lento, sin dedupe en servidor
(la app 2.1.73 no envía `boot_id`/secuencia; la migración 002 ya está
aplicada y el dedupe actuará con la 2.1.74).

### TEST-7 Reboot (en curso)

Comando: `adb reboot` (15:28:11). El equipo volvió (boot 15:28) y
`BOOT_COMPLETED` se procesó (15:31), pero la app no arrancó: sin proceso, sin
alarma programada, sin rastro de `AutostartReceiver`/`DozeAlarmReceiver` en
logcat y sin posiciones nuevas. A las 15:33 el sistema congeló el paquete
(`CpuFreezerManagerServiceV2 ... com.dmujeres.traccar, freezeType=2`): este
ZTE congela apps de terceros aunque estén exentas de Doze.
Confusor honesto: el `am force-stop` previo y el posible "Cerrar app" del
diálogo de crash dejan a la app en estado detenido, que bloquea
`BOOT_COMPLETED` hasta que el usuario la abre. Para una prueba limpia hace
falta abrir la app con un toque real y repetir el reboot.

## Sesión 2026-09-28 (continuación) — 2.1.74 instalada

Se instaló la 2.1.74 (vc 284, misma firma de flota, `adb install -r` sin
borrar datos) sobre la 2.1.73 del equipo macias.

### Crash de arranque encontrado y corregido en caliente

La primera 2.1.74 entraba en bucle ("continúa fallando"): al actualizar, el
`onDestroy` llamaba a `unregisterReceiver` de un receiver nunca registrado.
Causa raíz doble: (1) la 2.1.73 de calle ya traía `DATABASE_VERSION = 5` con
otro esquema (sin tabla `meta`), así que el upgrade a la 2.1.74 (también 5)
nunca corría; (2) `stop()` no era idempotente. Corrección aplicada y
reinstalada: versión 6 con migración idempotente por PRAGMA,
`ensureSchemaV6()` en cada apertura, `getMeta`/`putMeta` tolerantes, `stop()`
y `onDestroy` que nunca lanzan, y arranque en modo seguro si la restauración
falla. Desde entonces: 0 FATALs.

### Kill + force-stop + reapertura (TEST-6)

`am kill` no mata un FGS (protegido); `am force-stop` sí. Al reabrir con un
toque: proceso nuevo, FGS activo, sin FATALs, posiciones reanudadas
(15:39:03) sin duplicados en la reanudación. La alarma de 9 min quedó
programada (`DOZE_RECOVER` visible en `dumpsys alarm`).

### Doze forzado 11 min con 2.1.74 (TEST-8)

`force-idle` 15:42:38 → 15:53:38. La alarma `DOZE_RECOVER` se entregó en Doze
profundo, el receiver corrió en 59 ms (`despertar de recuperación
(servicio_activo=true)`) y se rearmó solo. Durante la ventana llegaron fixes
con la **misma** `boot_id` (`6b400aa6-…`) y secuencia **monótona** 11→15: la
identidad funciona de extremo a extremo y no hubo duplicados. Cadencia en
Doze ~1 min (FGS + alarma sostienen la captura).

### Estado al cierre

App 2.1.74 corriendo en el equipo, jornada abierta, buffer en 0, sin crash
loop. Pendiente (teléfono en mano): reboot limpio con la app ya abierta para
validar `AutostartReceiver` sin el confusor del force-stop, y campaña larga.

## Plantilla para próximas sesiones

Fecha / equipo / Android / OEM / app (versión+código) / batería inicial /
jornada (abierta/cerrada, duración) / objetivo / comandos con hora /
resultado (fixes antes/después, hueco máximo, duplicados, recovery) /
conclusión (1 línea).

## Build 2.1.74 listo para instalar (2026-09-28)

APK `fallback/app/build/outputs/apk/google/release/app-google-release.apk`
(9,1 MB, `com.dmujeres.traccar`, vc **284**, 2.1.74), firmado con la misma
clave de flota que producción (SHA-256 idéntico al 2.1.73) → se instala
encima con `adb install -r` **sin borrar datos ni configuración**. Incluye el
motor nuevo (máquina de movimiento, store v5, cola con backoff, recovery con
alarma, JourneyManager, diagnóstico extendido). Claves fuera del repo:
`/opt/dmj-keys/` (600) + symlinks gitignorados en `mobile/` y
`fallback/app/google-services.json` (temporal para el build).

## Sesión 2026-09-28 (tarde) — 2.1.75 instalada

`adb install -r` OK (puerto 42007). vc 285/2.1.75 confirmada, proceso vivo,
FGS activo, sin FATALs, posiciones fluyendo (94347 17:14:10). Trae
`DuplicateFixGuard` + servidor con MATCHED denso. Pendiente: ruta de campo
para verificar giros ajustados y ausencia de dobles capturas.
