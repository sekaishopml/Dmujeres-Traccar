# Auditoría de campo — Sanchez Pilay, Manzaba y Fernando (29–30/09/2026)

Fuente: consultas de solo lectura a `tracking.dmt_posicion`, `operations.dmt_jornada`, `tracking.dmt_dispositivo.atributos`, `iam.dmt_sesion` y al replay del panel, más la revisión del código de la app. Las horas son de Ecuador (UTC−5).

## 1. Resumen por persona

| Persona (equipo) | Teléfono | Qué pasó | Causa |
|---|---|---|---|
| **Sanchez Pilay (65)**, cuenta nueva del 30/09 15:05 | Honor LGN-LX3, Android 15, app 2.4.0 | Es Pilay en otra cuenta. El equipo Pilay (56) dejó de reportar a las 15:02. Sus 1405 puntos del 30/09 (00:04–15:02) llegaron juntos a las 15:06: en vivo estuvo ciego todo el día. | Pilay seguía con la app vieja (protocolo osmand, sin versión registrada). Desde el 29/09 21:02 guardó en el teléfono y no envió. Al iniciar sesión con la cuenta nueva se vació la cola. **Decisión del usuario: se dejan separados.** |
| **Manzaba (58)** | Infinix X6531, Android 14, app 2.4.0 | Jornada abierta desde el 29/09 17:37 (más de 26 h). La jornada anterior (06:37–17:37) no tiene puntos. Batería mínima 17 %. 143 recuperaciones de GPS. Problemas del 30/09 en el mapa: ver la sección 3. | Olvidó finalizar la jornada (no hay cierre automático). Infinix duerme el GPS. |
| **Fernando (59)** | Samsung SM-A175F, Android 16, app 2.4.0 | El 30/09 envió 9741 puntos (2700/hora, el 82 % a menos de 1,5 s entre sí) yendo a 44–51 km/h. Su batería bajó del 60 % al 15 % en 5 h. El 29/09 el 54 % de sus puntos fueron de antena y llegaron con hasta 10 h de retraso. Hueco de 16:02 a 16:07: ver la sección 2. | Captura sin límite en vehículo: el GPS a 1 Hz y el filtro de 10 m (`PositionProvider.processLocation`) guardan cada lectura por encima de 36 km/h. |

Otros hallazgos:
- **Alejandro (60)** sigue con la app **2.1.73**: no recibió ninguna OTA reciente.
- **Retrasos de envío sin aviso:** el panel no alerta cuando un equipo guarda puntos y no los envía.
- **Actualizaciones:** 7 versiones por OTA en ~6 h a toda la flota (29/09 23:01 → 30/09 05:05).
- **Puntos duplicados:** a veces llegan dos puntos con el mismo segundo (por ejemplo, Manzaba 09:46:44, 09:47:59).

## 2. Fernando 16:02–16:07: Metro de Quito (subterráneo)

- 16:02:58: último punto en la estación **Quitumbe** (5 m de precisión), tras 9 min de espera (parada 10).
- 16:07:24: primer punto al salir en **Morán Valverde** (19 m de precisión, recibido 5 s después).
- Recorrido: 1821 m en 266 s = **24,6 km/h**, la velocidad del metro.
- **La app no falló.** Bajo tierra no hay señal de satélites, y el GPS volvió en cuanto salió a la superficie. La recta punteada del panel es un tramo **sin observación**, no una ruta inventada.
- Acelerómetro y giroscopio **no pueden dar la posición**: integrar aceleraciones da errores de cientos de metros en minutos, y en el metro la vibración y los motores lo empeoran. Sí sirven para saber **que** se movía.
- Sin red (sin datos) ya funciona: la app guarda y sube después (Pilay subió un día entero).

**Propuestas, sin implementar:**
1. Tramo de metro reconocido (servidor): si el hueco empieza y termina junto a estaciones y la velocidad está entre 15 y 60 km/h, dibujarlo sobre la línea del metro, punteado, con la etiqueta "Metro (bajo tierra, sin GPS)". La línea y las estaciones salen de `/opt/graphhopper/ecuador-latest.osm.pbf`.
2. Causa del hueco (app): al perder el GPS, anotar si se movía (acelerómetro), cuántos satélites veía y si tenía red. El panel diría, por ejemplo, "Sin señal de satélites, en movimiento, 4 min".
3. Reconocimiento de actividad de Google ("en vehículo" aunque no haya GPS).

## 3. Manzaba 30/09: problemas en el mapa y arreglo

| Síntoma | Causa | Arreglo (panel 1.12.0 / API) |
|---|---|---|
| A las **09:29:10** el panel la ubicaba junto al TIA Acacias; ella dice que no estuvo ahí. **Tenía razón.** | Su GPS (9 m de precisión) estaba en la parada 2. El panel repartía la hora a lo largo de un tramo ajustado a calles donde estuvo quieta y luego caminó 100 m de ida y vuelta, y la hora caía unos 35 m corrida. | El punto se muestra en el **centro de su parada** si cae dentro de una. Si no, en el **punto de la línea más cercano a su GPS real**, buscando solo cerca de su hora (`puntoCercanoEnLineas`). |
| **Zigzag** de 09:51 a 09:57. | Con ella caminando, el ajuste a calles (GraphHopper) pegó los puntos de forma alternada a las calzadas paralelas de la avenida (saltos de 30–50 m). | Las ventanas **a pie** (avance menor a 8 km/h) ya no se ajustan a calles; se dibuja el GPS, que sigue la acera (`esVentanaAPie`, `ruteo.js`). |
| **Telaraña con flechas** y muchas paradas dentro de un edificio (10:09–20:41). | Bajo techo el GPS salta 100–244 m y vuelve en segundos. La estancia quedaba en 5 paradas, y los saltos se dibujaban como líneas con flechas. | **Servidor** (`paradas.js`): dos paradas seguidas son una sola estancia si las separan 15 min o menos, sus centros están a 100 m o menos, y no hubo una salida real (más de 200 m durante 2 min seguidos). **Panel:** dentro de una parada no se dibujan líneas ni flechas, solo el halo. Resultado: **una parada de 10:09 a 20:41 (10 h 31 min)**. |

Pruebas: `services/api/test/paradas.test.mjs` (estancia con saltos sueltos = 1 parada; salida real de 4 min a 300 m = 2 paradas) y `test/precision-trazo.test.mjs` (ventana a pie). Pasan 27 de 27.

## 4. ¿Llegamos al tope de la arquitectura?

**No.** La base es sólida:
- servicio en primer plano con ubicación combinada de Google;
- cola en el teléfono y envío por lotes idempotente;
- GraphHopper local;
- Postgres particionado;
- reconstrucción honesta de la ruta.

Lo que falta es ajuste de captura, higiene de jornada y que el panel vea los problemas en el momento.

| # | Mejora | Estado |
|---|---|---|
| 1 | Captura según la velocidad en vehículo (unas 3 veces menos puntos y batería) | Pendiente |
| 2 | Cierre automático de jornada (16 h, o 3 h sin puntos) y aviso de jornada sin registro | Pendiente |
| 3 | Aviso en vivo de equipo ciego (sin envíos en 10 min con jornada abierta) | Pendiente |
| 4 | Historial de salud por equipo (`telemetry.dmt_salud_dispositivo`, tarjeta en Sistema) | **Hecho** (panel 1.11.0) |
| 5 | Tramos de metro y causa de los huecos (sección 2) | Propuesto |
| 6 | Una OTA por día como máximo, primero a un equipo de prueba (`ota/rollout.json`) | Proceso |
| 7 | Revisar en persona la app de Alejandro (2.1.73) | Operación |

## 5. Manzaba 20:41: línea falsa entre paradas, "fideo" y saltos de "siguiente punto" (panel 1.13.0)

| Síntoma | Causa | Arreglo |
|---|---|---|
| Recta larga con flechas entre la parada del edificio y la de Mi Comisariato. Al elegir cualquier flecha volvía a la parada y "siguiente punto" saltaba 60 m. | Salió caminando despacio (unos 100 m a 4–5 km/h). Esos pasos quedaban dentro del radio de 60 m de las dos paradas y se movían a sus centros, así que la caminata quedaba en dos puntos. | **Núcleo de parada** (servidor `paradas.js` y panel `dominio/depuracion.ts`): si en un extremo hay al menos 3 puntos seguidos a más de 30 m del centro, son llegada o salida y conservan su coordenada. Ahora "siguiente punto" avanza de 1 a 10 m por paso en esa caminata. |
| Puntos repetidos | La app a veces envía dos puntos en el mismo segundo. | El panel deja uno por segundo (`sinRepetidos`). |
| Línea vertical que cruzaba el círculo de la parada | El ajuste a calles terminaba en el callejón de atrás del edificio. | La línea se recorta en el borde de la parada, a 25 m del centro (`componentes/replay/trazo.ts`, `recortarEnParadas`). |
| "Fideo": trazo a pie fino y ondulado, flechas de pasadas distintas mezcladas | El GPS a pie oscila de 5 a 15 m, y la caminata se dibujaba como un hilo fino. | La caminata se suaviza para dibujar (promedio con sus 2 vecinas, sin tocar los datos) y se dibuja más gruesa, con borde blanco. **El color va según la hora:** claro temprano y oscuro tarde, en las líneas y en el disco de las flechas, con una leyenda en el mapa. Las pasadas de la mañana y de la noche por la misma calle se distinguen. |

Pruebas: `services/api/test/paradas.test.mjs` incluye "salir caminando despacio no queda dentro de la parada". Pasan 28 de 28.

## 6. Paradas unidas a la línea, paradas partidas en un mismo lugar y nuevo Inicio (panel 1.15.0)

- **Sanchez Pilay 17:16, "sobrante" detrás de la primera flecha.** El ajuste a calles (17:15:29–17:20) empezaba dentro de la parada y pegaba a la calle los minutos en que seguía quieta. Ahora los tramos ajustados se cortan en el primer punto al salir (y en el último al llegar), y la línea se une **desde el centro de la parada** con el punto donde empieza o termina (`cortarTramosEnParadas`, `conectarParadas` en `componentes/replay/trazo.ts`). Toda línea empieza con una flecha a 14 m, visible a cualquier zoom.
- **Fernando, varias paradas en el mismo lugar.** Sin jornada, su teléfono manda un punto cada 5–10 min y casi todos de antena (100 m de error). Ahora un punto aproximado cercano también confirma que sigue en la parada (`ultimoVisto` en `paradas.js`). Su noche del 30/09 pasó de 6 paradas a 3, incluida su salida real de 20:25.
- **Ficha desplegable de parada** (desde, hasta, duración y dirección) y área de toque más grande en las insignias.
- **Inicio rediseñado**, compacto y orientado a la operación:
  - Arriba: en jornada ahora, recorrido de hoy (km, trayectos y paradas), actividades de hoy y cuántas hay para revisar, más una barra de estados de la flota.
  - Una fila por persona: estado, jornada (desde cuándo está abierta), km y paradas, actividades con las nuevas, batería y versión de la app (marcada si está desactualizada).
  - **Para revisar:** sin señal, batería baja, puntos sin enviar, app desactualizada y **jornada abierta hace más de 16 h**. El 01/10 marca a Alejandro (abierta desde el 26/09, 102 h) y a mantilla (25 h).
  - **Últimas actividades** del cronograma.
- Ícono de pestaña y logo del menú plegado: labios en negro (blanco en modo nocturno).
