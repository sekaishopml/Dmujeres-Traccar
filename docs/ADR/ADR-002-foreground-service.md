# ADR-002 — Foreground Service tipo LOCATION

Estado: **CERRADO** (2026-09-28).

## Contexto

`TrackingService` ya es un foreground service con
`android:foregroundServiceType="location"` y permisos
`FOREGROUND_SERVICE_LOCATION` + `POST_NOTIFICATIONS`
(`fallback/app/src/main/AndroidManifest.xml:14,16,100-101`).

## Decisión

El tracking vive en un **FGS tipo location** con notificación persistente
mientras haya jornada abierta. El arranque inicial **siempre** nace de una
acción visible del usuario (abrir/confirmar jornada en la app), cumpliendo las
restricciones de Android 12+ para arranques en segundo plano. Las
recuperaciones desde background usan FCM/alarma/boot, nunca arranque
silencioso del FGS.

## Alternativas descartadas

- Arrancar el FGS desde el boot sin interacción: prohibido/rechazado en
  Android 12+ y mata el estado del servicio.
- WorkManager para captura: pierde continuidad y no es para alta frecuencia.

## Consecuencias

- `AutostartReceiver` solo rearma alarma y, si había jornada persistida, marca
  RECOVERING (no fuerza arranque del FGS).
- El usuario ve siempre el estado del servicio (notificación + diagnóstico).
