# Cronograma de actividades (app 2.3.0, panel 1.6.0)

- Base: `operations.dmt_actividad` (migración 005). Canal móvil `GET/POST /api/mobile/v1/actividades`
  (tracking); la coordenada solo se guarda si había jornada abierta al registrar. API panel
  `GET /api/v1/cronograma` con `enHora` (fix ±20 min y parada ±5 min a la hora declarada).
- Panel: Reportes con pestañas Cronograma (semana persona/equipo, mes, CSV) y Recorridos.
- App: `cronograma/Actividades.kt` (almacén local + cola, sugerencias de lugares) y
  `cronograma/CronogramaActivity.kt` (mes sin jornada, día con jornada; tipos, lugar, nota, hora;
  atajos de día completo). Botón en el home. `SplashActivity` 5 s con logo completo.
- Debug: vistas previas del asistente ya no tocan sesión ni ONBOARDED; MainActivity repara la marca.
