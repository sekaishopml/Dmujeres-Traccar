# 2026-10-01 — App 2.4.1: cronograma "de tal hora a tal hora" (panel 1.20.1)

- Migración `007_actividad_hora_fin.sql` (aplicada): `operations.dmt_actividad.hora_fin` opcional.
- Tracking (`movil.js`, `db.js`): acepta y devuelve `horaFin`; API `cronograma.js` la expone; panel muestra "a HH:MM" y el CSV el rango.
- App: `Actividad.horaFin`; formulario con Desde/Hasta (campo + AM/PM; "14:30" se corrige a 02:30 PM). Desde = hora actual, Hasta = +1 h. Sin reloj ni botones ±15. Valida hasta > desde. Lista del día muestra el rango en 12 h. "Marcar todo el día" = 08:00–17:00.
- `HoraCronograma`: `interpretar`, `a12`, `legible`, `rango`, `enCurso`, `siguiente` (prueba `HoraInterpretarTest`).
- Pantalla principal: pie con "En curso: Tipo · Lugar · hasta …" o "Siguiente: hora · Tipo" (se actualiza cada 5 s). Bloque central centrado en vertical con dos espacios flexibles; logo del arranque subido 32 dp (centro óptico).
- OTA 2.4.1 (versionCode 302) publicada a toda la flota.
