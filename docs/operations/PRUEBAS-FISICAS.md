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

## Plantilla para próximas sesiones

Fecha / equipo / Android / OEM / app (versión+código) / batería inicial /
jornada (abierta/cerrada, duración) / objetivo / comandos con hora /
resultado (fixes antes/después, hueco máximo, duplicados, recovery) /
conclusión (1 línea).
