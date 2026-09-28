# Doze y wake locks en los teléfonos de la flota

Nota de referencia para cuando se diseñe la arquitectura de la app (2.1.74).
Explica por qué un teléfono deja de reportar aunque la app esté instalada y
con permisos, y qué defensas ya existen.

## Doze (el "sueño" de Android)

Cuando la pantalla lleva un rato apagada y el teléfono no se mueve, Android
entra en **Doze**: congela las apps en segundo plano para ahorrar batería.

- Los temporizadores internos de la app (`Handler`, `postDelayed`) **no se
  ejecutan** o se ejecutan cada muchos minutos.
- El chip GPS puede apagarse; el proveedor fused deja de entregar fixes y
  queda `stale` (sin novedades).
- Las peticiones de red se agrupan y salen en ráfagas cada 15 min o más.
- Cada fabricante suma su propio ahorro aparte del de Android (Infinix,
  Xiaomi y Huawei son los más agresivos): ahí ni el servicio en primer plano
  garantiza que la app siga viva.

Síntomas que ya vimos en producción: fixes cada 1-5 min en vez de cada 10 s,
ráfagas de fixes duplicados al despertar, y huecos de 15-30 min sin datos
mientras el vehículo se movía.

## Wake lock (mantener despierto)

Un **wake lock** es un permiso que la app le pide al sistema para que la CPU
no se duerma mientras hace algo. La app ya lo usa al **enviar** cada lote
(`WakefulBroadcastReceiver`/`SendWakeLock`) y al arrancar desde el boot
(`AutostartReceiver`), y pide la exención de optimización de batería en el
onboarding. El costo de un wake lock permanente es batería: por eso no se
mantiene todo el tiempo, solo en los momentos críticos.

## Defensas que ya existen

- Cadencia adaptativa (10 s en movimiento, 120 s parado, sin batching) para
  no depender de ráfagas agrupadas por Doze.
- Vigilante de GPS (`LocationWatchdog`): a los 4 min sin fix re-solicita
  actualizaciones y luego cambia al GPS del sistema.
- Buffer local con reintentos (5.000 fixes, descarta lo viejo) y reenvío al
  reconectar: por eso a veces llegan fixes duplicados con la misma hora.
- Latido de diagnóstico cada 10 min (`lastDiagnostics`) para ver el estado del
  teléfono sin adb.
- Recuperación por push (FCM): el servidor despierta la app cuando un equipo
  queda en silencio y audita cada intento.

## Pendiente para la arquitectura 2.1.74 (sin implementar)

- Despertar con `AlarmManager.setAndAllowWhileIdle` cada ~9 min: es la única
  alarma que Doze respeta sin permiso especial, y sirve para re-solicitar el
  fix y vaciar el buffer aunque el `Handler` esté congelado.
- Wake lock parcial solo mientras la jornada está abierta (con su costo de
  batería documentado en el panel).
- Pedir un fix fresco (`getCurrentLocation`) en cada despertar en vez de
  esperar al callback pasivo del proveedor.
