# 2026-09-30 — Cronograma: horas en orden, home marino, planilla en el panel

## App 2.3.1 (OTA publicado, versionCode 298)
- Hora de nueva actividad: continúa tras la última del día (+30 min) aunque sea futura; nunca repite hora (`HoraCronograma.libreDesde`, pasos de 5 min). Chips −15/+15/+30/+1 h.
- Lista del día ordenada por hora y luego por registro.
- Home: tarjeta marina del cronograma con resumen (siguiente actividad / total) y "Sincronizado a las HH:MM"; pie marino con Iniciar/Finalizar jornada, Actualizar y versión al fondo.
- Cronograma: cabecera marina con estado de sincronización; se quitó "Se guardará tu ubicación actual…".
- Permisos: fila de batería corregida ("Batería sin restricción"), sin badge "Recomendado".
- Test: `HoraCronogramaTest`.

## Panel 1.7.0
- Reportes › Cronograma semanal como planilla tipo Excel (columnas HORA | PLANIFICACIÓN por día, color por día, almuerzo en gris, auditoría corta por celda). "Todas" = una planilla por persona.
