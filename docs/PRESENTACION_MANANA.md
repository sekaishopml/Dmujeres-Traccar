# Presentación de mañana — guion y checklist

## Enlaces a tener a mano

| Qué | Enlace |
|---|---|
| Panel web (demo) | http://68.168.20.219:999 |
| Repositorio (código) | https://github.com/sekaishopml/Dmujeres-Traccar |
| Release con APK | https://github.com/sekaishopml/Dmujeres-Traccar/releases/latest |
| APK directo | http://68.168.20.219:999/DMujeres-Tracking-2.1.7.apk |
| Código del cliente (zip) | http://68.168.20.219:999/dmujeres-fallback-src.zip |
| Guía de desarrollo | `docs/DEV_WORKFLOW.md` |

## Guion (10–15 minutos)

1. **Qué es** (1 min): plataforma DMujeres para control de jornada y ruta de la
   flota: app Android + servidor Traccar + panel web con mapa, jornadas, alertas
   y replay.
2. **Arquitectura** (2 min): la app está construida sobre el **cliente oficial de
   Traccar** (años de campo, compatible con el servidor al 100 %); el servidor es
   Traccar 6.14.5 con módulo propio (ingesta, jornadas, alertas, OTA, FCM);
   el panel es el dashboard web con la identidad DMujeres.
   Puntos fuertes: buffer offline, recuperación por FCM, actualización OTA con
   piloto controlado, auditoría de consultas de actualización, capturador de
   crashes visible en el panel.
3. **Demo en vivo** (7 min):
   a. Teléfono: abrir la app → pantalla principal (logo, estado, cuadros de
      Pendientes/Batería/Duración) → **INICIAR JORNADA** → el estado pasa a
      **JORNADA ACTIVA** (verde) y la duración empieza a contar.
   b. Caminar/rodar unos minutos → en la app suben los puntos; en el panel se ve
      la ruta en el mapa.
   c. Panel → **Jornadas**: jornada en curso; **Replay**: recorrido del día con
      la marca de "medido"; **Alertas**: tarjetas operativas.
   d. Botón **ACTUALIZAR** de la app → canal de actualización (si ya está al
      día, muestra "Ya tienes la última versión").
   e. Consola de estado (arriba a la derecha) → mensajes del servicio.
   f. 5 toques en la versión (footer) → modo avanzado (configuración).
4. **Indicadores** (2 min): Cobertura30 (fidelidad del trazo), embudo de salud,
   alertas de silencio/recuperación; qué está validado y qué sigue en campo.
5. **Continuidad** (1 min): código en GitHub, releases con APK firmado, plan de
   emergencia documentado, próximos pasos (rotación de llaves, más pilotos).

## Checklist para HOY (antes de dormir)

- [ ] **Teléfono de macias**: ubicación (GPS) **ENCENDIDA** (es lo único que
      falta verificar para que haya ruta en el mapa).
- [ ] Batería del teléfono cargada (>50 %) y **sin restricción de batería**
      para la app.
- [ ] App actualizada (2.1.7) — al abrir sale el aviso o el botón ACTUALIZAR.
- [ ] **Iniciar jornada** en la app y dejarla activa.
- [ ] Verificar en el panel: posición reciente + jornada activa + latido de
      diagnóstico (`lastDiagnostics` con `gps.enabled: true`).
- [ ] Abrir el panel en tu computador y probar el mapa/replay con los datos del
      día (deja la pestaña lista).

## Qué mostrar si el teléfono no logra fijar posición

- La app igual demuestra: onboarding, permisos, estado, jornada, OTA, consola y
  modo avanzado.
- En el panel: jornadas registradas, alertas, replay del historial y la
  arquitectura.
- Explicar el diagnóstico honesto: sin GPS del sistema encendido no hay trazo;
  el latido de diagnóstico de la app lo reporta al panel (esta misma noche).
