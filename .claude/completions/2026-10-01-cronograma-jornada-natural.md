# Cronograma: pantalla de actividad, día de hoy y lógica de jornada natural (app, API, panel 1.20.2)

## App (sin publicar por OTA; la última publicada es 2.4.1 vc 302)
- `CronogramaActivity`: abre SIEMPRE en hoy. Tira de semana L–D (punto = hay
  actividades, flechas pasan de semana), cabecera "Hoy · miércoles 1 de
  octubre" con total registrado e "Ir a hoy". El ícono de calendario cambia
  al mes. Lista del día con:
  - huecos sin registrar (≥ 15 min) entre el inicio de jornada de hoy y ahora
    (días pasados: entre actividades). Tocar un hueco abre el formulario con
    esas horas: así se carga a las 13:00 lo de las 8:00;
  - línea "Ahora · 1:05 p. m."; la actividad en curso con "En curso · termina
    …" y botón "Terminé" (cierra a la hora real). Se repinta cada minuto.
- `ActividadActivity` (nuevo, pantalla completa en vez de popup): tipo en 2×3,
  Desde → Hasta con AM/PM, resumen en palabras ("1 h 30 min · 9:00 – 10:30
  a. m."), "Todo el día" para ausencias, lugares frecuentes a un toque, nota,
  Guardar fijo sobre el teclado, Eliminar, y "¿Salir sin guardar?".
  - AM/PM natural (`HoraCronograma.interpretarNatural`): 6–11 mañana, 12 y 1–5
    tarde; Hasta toma el primero que quede después de Desde ("de 11 a 2").
    El AM/PM tocado a mano manda. Hasta acompaña a Desde mientras no se toque.
- `AgendaDia` (pura, con pruebas): no hay dos actividades a la vez; manda lo
  último escrito y la vecina se acomoda (recortar fin, correr inicio o partir
  en dos). Si tapa otra por completo, no se guarda hasta decidir (botón
  eliminar esa). El aviso muestra antes de guardar qué pasará y ofrece "Mejor
  mover esta". Actividades sin hora de fin (antes de 2.4.1) no entran.
- Fallas corregidas en `Actividades`:
  - `sincronizar` con subida en curso llamaba al callback enseguida (la
    pantalla quedaba "Por enviar"); ahora espera y sube también lo guardado
    durante la subida;
  - mezcla con el servidor: lo subido mientras se consultaba ya no desaparece
    ni revive (borradas).
- Refresco del servidor cubre el mes y la semana visible (cruza meses).

## Servidor
- tracking `movil.js`: `horaFin` debe ser posterior a `hora`.
- api `cronograma.js`: `paradaDeActividad` — con rango, la parada que más
  comparte con él y `enHora.coberturaPct`; sin rango, como antes (±5 min).
  Prueba `test/cronograma.test.mjs`.
- api `eventos.js`: "Subió visita de 09:00 a 10:00".

## Panel 1.20.2
- Reportes › Cronograma: "Detenida 9:05–10:40 (79 % del horario)" y en la
  línea de carga "planificada" o "X después" (más de 1 h después = aviso).
- `dominio/cronograma.ts` (protegido, avisado): `enHora.coberturaPct`.

## Verificación
- App: `testGoogleReleaseUnitTest` OK (AgendaDiaTest 7, HoraNaturalTest 3,
  HoraInterpretarTest 5, HoraCronogramaTest 2 y el resto). No se vio en un
  teléfono (no hay emulador utilizable en el servidor).
- API 31/31, tracking 4/4. Panel: Playwright en Reportes (mantilla), sin errores.
