# DMujeres Tracking — Orquestador de la reconstrucción

Estado y bitácora del plan maestro (`docs/Plan-Maestro.pdf`). El legado
`/DMujeres-Tracking` fue retirado en FASE 12 (2026-09-27); la plataforma nueva
vive en `/home/DMujeres-Tracking` y su rollback en
`/home/DMujeres-backups/legado-final/`.

## Reglas absolutas vigentes

- No detener ni modificar de forma destructiva la plataforma nueva
  (`dmj-api/tracking/web/recuperacion`, `dmt-db`, Nginx) sin checkpoint.
- No publicar secretos (estos backups viven fuera del árbol de la app).
- Todo cambio peligroso: backup + evidencia + rollback + validación.

## Fases

| Fase | Estado | Evidencia |
|---|---|---|
| 0. Inventario | COMPLETADA | `docs/agents/INVENTARIO.md` |
| 1. Backup verificado | COMPLETADA | `/home/DMujeres-backups/migration/20260925-205714/` + `docs/migration/BACKUP-EVIDENCIA.md` |
| 2. Arquitectura y contratos | COMPLETADA | ADR-001/002, `MAPA-LEGADO-DMT.md`, `database/schema/`, `packages/contracts` |
| 3. PostgreSQL y restauración | COMPLETADA | `dmt-db` (PG 18.6 + Timescale 2.30.1) + `docs/migration/FASE3-MIGRACION.md` |
| 4. Backend/API | COMPLETADA (4a+4b) | `services/api` 24 operaciones, smoke 49 PASS; escritura de usuarios/config |
| 5. Web | COMPLETADA | `apps/web` + `services/web`; gestión de usuarios y equipos activa |
| 6. Replay | entregado con FASE 5 | `apps/web/src/paginas/Replay.tsx` (velocidades 0.5x-16x, huecos, caché) |
| 7. App | matriz y E2E entregados; App nueva pendiente | `docs/app/COMPATIBILIDAD-MATRIZ.md` |
| 8. Infra | COMPLETADA (sin TLS) | systemd `dmj-api/tracking/web` + Nginx :80; TLS pendiente de dominio |
| 9. QA/E2E | COMPLETADA | `scripts/validation/e2e.sh` 20/20 PASS + `docs/operations/FASE9-QA.md` |
| 10. Cutover | COMPLETADA | `docs/migration/CUTOVER-2026-09-26.md`; Traccar detenido, plataforma nueva en 5055/999 |
| 11. Estabilización | COMPLETADA (funcional) | `FASE11-RECUPERACION.md` + `FASE11-ESTABILIZACION.md`; TLS/firewall diferidos a producción |
| 12. Retiro del legado | COMPLETADA | `docs/operations/FASE12-RETIRO.md` |

## Contrato entre agentes

Cada agente entrega: cambios, pruebas, riesgos, archivos tocados y siguiente
dependencia. Ningún subagente detiene producción; el corte solo lo ejecuta
RELEASE y únicamente con todos los gates verdes.

## Datos descubiertos en el reconocimiento inicial

- Producción: `/DMujeres-Tracking` (fuente de datos durante la transición).
- Servicios systemd: `dmj-traccar.service` (activo), `dmj-match.service`
  (activo), `dmj-traccar-watchdog.service` (inactivo).
- Contenedores: `dmj-db` (TimescaleDB/PostgreSQL 17.10), `dmj-redis`,
  `dmj-mqtt` (EMQX).
- Puertos: 999 (Web/API actual), 5055 (protocolo OsmAnd de la App),
  5433 (PostgreSQL en 127.0.0.1), 1883/8083/18083 (MQTT), 6379 (Redis).
- Sin Nginx instalado.
- Base actual: `traccar` (~61 MB, 26.413 posiciones, 9 dispositivos, 5 usuarios).
- Almacenamiento: 4.5 GB libres al inicio (95% usado) — vigilar durante la
  restauración de prueba.

## Bitácora

- 2026-09-25: reconocimiento, creación de `/home/DMujeres-Tracking` con la
  estructura objetivo, `/home/DMujeres-backups/migration/<ts>/` y arranque de
  FASE 0 (auditoría) y FASE 1 (backup + restauración de prueba).
- 2026-09-25: FASE 0 completada (`INVENTARIO.md`, 741 líneas). FASE 1
  completada y verificada: dump custom (3.892.173 B, 652 objetos) + globals,
  SHA-256 OK, restauración de prueba con igualdad exacta contra la foto del
  dump (usuarios 5, dispositivos 9, posiciones 26.417, eventos 5.076,
  permisos 20), backup **válido**. Producción intacta (sigue activa y
  recibiendo posiciones). Config/secretos/servicios respaldados fuera del
  árbol (49 archivos, SHA OK) con `INFRA.md` y `SECURITY.md`.
- 2026-09-25: FASE 2 completada. **ADR-001** fija la nomenclatura propia
  `dmt_*` en español para la base nueva (producción no se renombra; contrato
  de la App congelado). Entregado: `MAPA-LEGADO-DMT.md` (inventario real de
  57 tablas de producción + 53 de `traccar_qa` y su mapeo), esquema SQL nuevo
  en `database/schema/` (9 archivos, 22 tablas lógicas, 92 índices,
  particiones 2026-09/10, `system.uuidv7()` de respaldo PG17) verificado en
  una base scratch (`dmt_schema_test`, aplicado sin errores y eliminado),
  contrato `/api/v1` (`packages/contracts/openapi.json`, 20 rutas, validado),
  tipos TS, `API-V1.md`, `COMPATIBILIDAD-APP.md`, `ARQUITECTURA.md`,
  `ADR-002` y `.env.example`. Producción intacta (26.631 posiciones).
- 2026-09-25: FASE 3 completada. Base nueva `dmt-db` (contenedor aparte,
  127.0.0.1:5443) con **PostgreSQL 18.6 + TimescaleDB 2.30.1** y 26 tablas
  `dmt_*`; histórico unificado cargado: **80.809 posiciones** (14.207 de
  agosto + 66.602 de septiembre) desde producción + `tc_positions_bak_20260903`
  + `traccar_qa` + dumps de `/var/backups/dmj` (incluido pre-purga). Migrados
  además: 5 usuarios (ids/credenciales conservadas), 19 dispositivos (9 vivos
  + 10 históricos), 20 asignaciones, 5.134 eventos, 28 jornadas, 26.888
  muestras de batería, FCM y claves. Validación: `unique_id` sin duplicados,
  última posición 9/9 idéntica, 0 huérfanos, ETL idempotente. Producción
  intacta (26.883 posiciones, 57 tablas, servicios activos). Evidencia en
  `docs/migration/FASE3-MIGRACION.md` y `docs/migration/evidence/`.
- 2026-09-25: FASE 4 y 5 completadas. **FASE 4**: `services/api` (Node 24,
  20 rutas `/api/v1`, sesión propia, PBKDF2 compatible, smoke 26/26 PASS) y
  `services/tracking` (OsmAnd + compat móvil, smoke PASS con limpieza).
  **FASE 5**: `apps/web` (React 19.3, Vite 8.3, MapLibre 6.11, Chart.js,
  TanStack Query, Zustand) con login, inicio, en vivo, detalle, historial,
  replay (0.5x–16x con huecos), batería, reportes, usuarios, configuración y
  sistema; `services/web` sirve el build y proxya /api en **:25565**.
  Correcciones de integración: estado operativo calculado en SQL
  (`DESHABILITADO/SIN_SENAL/SENAL_DEBIL/DETENIDO/EN_LINEA`), enum del contrato
  y login del store alineados a `{usuario, clave}`. Verificado end-to-end por
  el proxy (login real 200 y flota con estado). Producción intacta.
  **Pendiente 4b**: escritura (crear/editar usuarios y config de equipos) y
  FCM/OTA propios. **Pendiente FASE 8**: systemd para api/tracking/web y TLS.
- 2026-09-25/26: FASE 4b y 8 completadas. **4b**: `POST/PUT/DELETE
  /api/v1/users` (con asignación de equipos y auditoría), `PUT
  /api/v1/fleet/{id}` con whitelist de configuración, DTOs ampliados
  (`dispositivoIds`, `configuracion`), OpenAPI 24 operaciones y smoke 49/49
  PASS (admin temporal creado y eliminado en las pruebas). **8**: unidades
  systemd `dmj-api/tracking/web` activas y habilitadas, Nginx delante en :80,
  instalación idempotente y documentada (`docs/deployment/FASE8-INFRA.md`);
  firewall sin tocar (propuestas documentadas). **Integración corregida**:
  `/auth/me` devolvía el DTO incompleto por doble conversión (ahora completo),
  el store web esperaba `{usuario}` y ya usa el Usuario directo, el contador
  de Inicio ya no cuenta DESHABILITADO como sin señal, y el smoke usa rango de
  7 días (antes "hoy", que falla de madrugada). Verificado con navegador real
  (Playwright): login, Inicio, En vivo (mapa OSM con marcador) y Sistema.
  Producción intacta; panel viejo y App siguen en :999/:5055.
- 2026-09-26: **rediseño del panel** a estilo panel de flota real: barra
  lateral navy con iconos propios y logo, barra superior compacta, tipografía
  13px y densidad de operación (sin aspecto de plantilla). El mapa usa
  **Google sin clave** (Mapa/Satélite/Híbrido) más OSM, con selector de capa;
  Replay pasa a **pantalla completa** con el menú lateral recogido, panel
  flotante a la izquierda y línea de tiempo abajo. Logo en login y pestaña.
  Verificado con navegador real (capturas: login, inicio, en vivo con Google,
  replay con recorrido y velocidades 0.5x–16x). **FASE 9 cerrada**:
  `scripts/validation/e2e.sh` con **20/20 PASS** (checks, smokes 49+tracking,
  build, systemd, puertas :80/:25565, login real, conteos producción vs nueva)
  y evidencia en `docs/operations/FASE9-QA.md`. **FASE 7** entrega la matriz
  App↔plataforma (`docs/app/COMPATIBILIDAD-MATRIZ.md`) con los huecos para el
  cutover: publicar :999/:5055 hacia lo nuevo, OTA con rollout, FCM real y
  atajos/eventos `mobile.*`.
- 2026-09-26: segunda iteración de diseño pedida por el dueño. **Replay**:
  rango por defecto **ayer→hoy**, sin "Ver historial", **flechas continuas**
  de sentido sobre la ruta (capa symbol con imagen de canvas), **lista de
  detenciones** derivada de las posiciones (≥3 min a <2 km/h) con salto de la
  reproducción al punto y marcadores naranjas en el mapa, y resumen en tira de
  datos. **Branding**: bloque de marca con lema, menú agrupado en Operación /
  Administración, iconografía propia y lenguaje de secciones (sobrelíneas,
  tiras de datos, herramientas en fila). **Secciones útiles** (no volcados):
  Inicio con resumen del día (o última semana, indicado) y "Requieren
  atención"; En vivo con filtros por estado y contadores; Historial con
  resumen y export CSV; Batería con tendencia real (%/h); Reportes con tira de
  totales y unidades con más recorrido; Sistema con estado por dependencia y
  significado. Verificado con capturas y **E2E 20/20 PASS** tras el rediseño.
- 2026-09-26: ajuste de Replay pedido por el dueño: **flechas centradas**
  (canvas 24×24 simétrico, `addImage` sin pixelRatio, `icon-anchor: center`),
  **zoom suave** (`fitBounds` maxZoom 14, sin animación; rueda del mapa a
  1/450), **menú simple estilo Traccar** (equipo, fechas ayer→hoy, una línea de
  resumen, detenciones plegables) y **gráfico de batería** en la barra
  inferior con marcador que sigue la reproducción. Se quitó el resto de
  adornos (tira de 5 celdas, lecturas sueltas, contadores duplicados). E2E
  20/20 PASS.
- 2026-09-26: cuarta iteración de Replay y branding. **API**: geocodificación
  inversa propia (`/api/v1/geocode/reverse`, Nominatim con caché y ritmo 1/s;
  smoke 51 PASS) y **filtro real por dispositivo en `/reports/stops` y
  `/reports/trips`** (antes se ignoraba el parámetro). **Replay**: reproducción
  fluida con `requestAnimationFrame` fiel a la velocidad, barra inferior
  compacta centrada (~420 px), **puntos seleccionables** con capa de acierto
  de 12 px y ficha del punto (hora, dirección real, coordenadas, velocidad
  reportada y derivada, precisión, recepción, batería, "Ir a este punto"),
  **paradas precisas del servidor** (`/reports/stops`) con dirección bajo
  demanda, marcador por estado (movimiento/detenido/sin señal) con leyenda,
  etiquetas de Inicio/Fin y **franja de estados** bajo el slider. **Branding**:
  navy más oscuro (`#04121f`), iconos del menú a 20 px, menú lateral pulido y
  **  pantalla de carga** con marca y giro (estilo Traccar). E2E 20/20 PASS.
- 2026-09-26: **corrección de zona horaria** (bug real): los scripts de
  migración guardaron la hora local de Ecuador como si fuera UTC, así que
  todas las marcas migradas quedaban 5 h antes (una ruta de las 22:23 se leía
  17:23). Se aplicó `database/migrations/001_ajuste_zona_horaria.sql`
  (idempotente, marcador en `system.dmt_configuracion`) y se corrigieron los
  ETL (`AT TIME ZONE 'America/Guayaquil'`) para futuras recargas; verificado
  contra producción (22:23 en ambas). **Replay**: fuera los círculos (se
  selecciona pulsando la ruta o las flechas, con radio de 80 m), botones
  rápidos Hoy/Ayer/Hoy y ayer, panel de 250 px **sin scroll** con Recorrido,
  **Jornada (inició/finalizó)**, Punto seleccionado y Paradas (6 + "y N más"),
  marcador GPS de 22 px por estado (verde/azul/naranja) y marcadores de
  inicio/fin de jornada. **API**: `GET /api/v1/fleet/{id}/journeys` (smoke 54
  PASS) y **pantalla de carga blanca** con logo y giro. E2E 20/20 PASS.
- 2026-09-26: **auditoría y limpieza de usuarios/equipos** (confirmada por el
  dueño; detalle en `docs/migration/LIMPIEZA-2026-09-26.md`). Producción:
  `test` eliminado (quedan admin, cctv, Manzaba, Fernando, Alejandro; 0
  duplicados) y los equipos kevin/qa-f0/joseph/jeremy/david **deshabilitados
  conservando historial**. Base nueva: mismas bajas, asignaciones cerradas,
  23 sesiones revocadas y **Alejandro migrado** (usuario, equipo, 9 posiciones
  y asignaciones). Sesiones del panel viejo cortadas reiniciando
  `dmj-traccar`. Respaldo de las filas afectadas con SHA-256. E2E 20/20 PASS.
- 2026-09-26: **CUTOVER EJECUTADO** (`docs/migration/CUTOVER-2026-09-26.md`).
  Compatibilidad final en `services/tracking`: OTA propia con política
  (pausa/allowlist/minVersionCode/porcentaje), token FCM completo, acks de
  recuperación y eventos de jornada; equipos deshabilitados no almacenan.
  Respaldo final verificado, OTA copiada a `/home/DMujeres-Tracking/ota`,
  Nginx enrutando :999 (`/api/mobile/*` → tracking, OTA/APK, panel nuevo) y
  `dmj-tracking` escuchando en 0.0.0.0:5055. **`dmj-traccar` detenido** (panel
  viejo muerto) y watchdog detenido. Verificado: config/OTA/APK por :999,
  panel externo, OsmAnd sintético y **posiciones reales de la flota
  almacenándose** (Pilay/macias; 80.848 y creciendo), deshabilitados ignorados
  y **E2E 24/24 PASS**. Rollback documentado. Pendiente de estabilización:
  push FCM real, TLS/dominio y firewall.
- 2026-09-26: FASE 11 (estabilización funcional) completada. **Recuperación
  FCM propia** (`dmj-recuperacion`, systemd activo): política cooldown/rate,
  FCM HTTP v1 con JWT RS256 sin SDK, auditoría y atributos `mobile.recovery*`;
  en el primer ciclo real Manzaba y Fernando recibieron el probe y **acusaron
  en ~1 s**. **Respaldos de la base nueva** (dump verificado + `.env` + SHA,
  retención 14 días, cron 03:30). E2E **24/24 PASS**. TLS y firewall quedan
  **diferidos a producción** por decisión del dueño; `dmj-match` sigue activo
  (autocontenido) y su retiro se decide en FASE 12.
- 2026-09-26: tercera iteración de Replay (pedido del dueño). **Play
  arreglado**: el reloj avanzaba en tiempo real (14 h a 1x, parecía muerto);
  ahora hay factor base adaptativo (1x ≈ ruta completa en ~60 s, tope 300×).
  **Detenciones corregidas**: se descartan las rachas que contienen un hueco
  de señal (el caso falso de 1 h 54 min desapareció; 19 → 12 en la ruta de
  prueba) y se usa velocidad implícita cuando falta la reportada.
  **Flechas estilo Traccar**: chevrones densos de 5 colores por banda de
  velocidad (teal/verde/amarillo/naranja/rojo) con rumbo real, y la línea del
  recorrido coloreada por tramos. **Branding**: navy más oscuro (`#061b33`)
  con acentos rojos. **Más funciones**: lectura del punto actual (hora ·
  velocidad · batería), botón "Seguir", clic en el gráfico de batería para
  saltar y export CSV del recorrido. E2E 20/20 PASS.
- 2026-09-27: **replanteo del panel** a herramienta de **auditoría de ruta**
  para personal de oficina. Lenguaje visual v2 (secciones con borde navy,
  tira de datos plana, ficha dt/dd, cronología con eje de tiempo y puntos por
  estado, cero adornos). **Página Auditoría** (`/historial`): expedientes por
  unidad/día con **cronología** (inicio con batería, paradas precisas del
  servidor con dirección bajo demanda, huecos como "sin señal", fin con
  batería) e "Ir a Replay". **API nueva** `GET /api/v1/journeys` (jornadas de
  la flota por rango; smoke 58 PASS). Inicio pasa a cola de auditoría del
  día; En vivo/Detalle/Batería/Reportes/Sistema/Usuarios/Configuración
  alineados al mismo lenguaje (menos elementos, reboques fuera). Verificado
  en navegador real (expediente de Fernando 25/09 con cronología completa:
  inicio, paradas con dirección, sin señal, fin). E2E 24/24 PASS.
- 2026-09-27: **incidente Alejandro resuelto**. Su app sí funcionaba (23
  posiciones guardadas), pero su jornada se inició a las 17:40 del 26/09,
  **antes del cutover** (22:02), así que quedó registrada solo en el servidor
  viejo y el panel nuevo no tenía jornada que auditar. Se añadió
  **reconciliación de jornadas** en `services/tracking` (al arrancar y cada
  hora): crea la jornada abierta desde `mobile.journeyId` (que es el epoch ms
  del inicio) y cierra por timeout las abandonadas >12 h; verificado con la
  jornada de Alejandro (creada a las 17:40, expediente con paradas, huecos,
  distancia 2,5 km y batería 38%→75%). Además se corrigió el expediente para
  **jornadas abiertas** (el rango usaba desde==hasta y la API lo rechazaba).
  E2E 24/24 PASS.
- 2026-09-27: **trazado y replay afinados**. Ruta con casing blanco, línea
  fina (2→4 px por zoom) y paleta suavizada; chevrones pequeños **adaptativos
  al zoom** (0 flechas a z<9 y 1/24, 1/8, 1/3, todas según el nivel). Panel de
  Replay **anclado arriba-izquierda**, 336 px, sin sombra flotante y plegable.
  **Sin barra azul**: slider discreto (pista gris, pulgar navy) y gráfico de
  batería en navy; franja de estados más fina. Repintado de UI a 5 Hz (marcador
  por `setLngLat` a 60 fps) para una reproducción más fluida. Verificado en
  navegador real (Fernando 25/09: 42,5 km, 4 h 10, 1 hueco). E2E 24/24 PASS.
- 2026-09-27: **FASE 12 preparada (12a)**. Riesgo de reinicio detectado y
  neutralizado: `dmj-traccar.service` y `dmj-traccar-watchdog.timer` seguían
  `enabled` (un reinicio habría levantado el panel viejo peleando 5055/999, y
  el watchdog podía reiniciarlo al no responder el receptor nuevo); ambos
  quedaron `disabled` sin tocar el rollback manual. Plan de retiro en
  `docs/operations/FASE12-RETIRO.md` (inventario, pasos 12b/12c con rollback,
  ahorro estimado ~7 GB y decisiones pendientes del dueño).
- 2026-09-27: **FASE 12b ejecutada** (autorizada por el dueño). `dmj-match`
  detenido y deshabilitado, cron viejo eliminado y contenedores `dmj-db`,
  `dmj-redis` y `dmj-mqtt` detenidos (5433/6379/1883/8991 cerrados; MQTT ya no
  expuesto a Internet). Antes: dump final de la base vieja (4,2 MB, verificado,
  SHA-256), copia fría del árbol (3,2 GB, 107.207 entradas, SHA-256) y copia de
  cron/units en `/home/DMujeres-backups/legado-final/`. El paso de conteos del
  E2E se adaptó al legado retirado; **E2E 24/24 PASS**. Queda solo 12c (borrar
  árbol, imagen pg17 y volúmenes) a la espera de autorización.
- 2026-09-27: **FASE 12c ejecutada; FASE 12 COMPLETADA**. Contenedores,
  volúmenes e imágenes del legado eliminados; árbol `/DMujeres-Tracking`,
  `/opt/matchservice`, backups y logs viejos y units de systemd borrados
  (`daemon-reload`). Antes se guardó en `legado-final/` el drop-in del unit,
  `/opt/matchservice` (26 MB), backups (36 MB) y logs. Verificado: nada del
  legado existe ni escucha, **E2E 24/24 PASS**, disco 94% → **71% (24 GB
  libres)**. Rollback documentado en `docs/operations/FASE12-RETIRO.md`.
- 2026-09-27: **franja inferior de Replay rediseñada** (pedido del dueño:
  "quita esa línea azul inferior"). Se eliminó la tira de estados que iba bajo
  el slider (componente, canvas, utilidad `franjaEstados`, `TramoEstado` y su
  CSS). La barra queda en tres filas: gráfico de batería con la lectura del
  punto en curso a su derecha (hora · velocidad · batería), una sola fila de
  mandos (transporte a la izquierda, velocidades a la derecha) y slider al
  final. Verificado en navegador real: reproducción avanza (21:01 → 21:16 en
  3 s), velocidad 4× activa, pausa, sin errores de consola; la barra bajó de
  141 a 109 px. E2E 24/24 PASS.
- 2026-09-27: **barra de progreso real y traza visible** (pedido del dueño).
  **Slider**: el tramo transcurrido se pinta en navy sobre la pista (variable
  `--progreso` escrita por frame desde `ReproductorReplay`); la entrada pasó a
  no controlada y el avance se ve continuo a 60 fps, no a saltos del repintado
  de React (5 Hz). Arrastre, saltos de hueco, selección y fin de recorrido
  sincronizan el slider; al terminar, reproducir arranca de nuevo desde 0.
  **Ruta**: fuera el casing blanco (sobre mapas claros lavaba la traza); línea
  sólida de 3 a 5 px según zoom y chevrones con cuerpo del color de la banda y
  halo blanco fino (antes eran blancos con borde de color). Huecos punteados de
  2,5 a 4 px. Verificado en navegador real: arrastre al 75% (lectura y relleno
  concordando), fin al 100% y reinicio al reproducir; sin errores de consola.
  E2E 24/24 PASS.
- 2026-09-27: **ajustes de panel y datos** (pedido del dueño: "diseños muy
  pequeños", "pantalla de carga minúscula", "hamburguesa no funciona",
  "detalles sencillos", "paradas de Pilay", "blanco sobre blanco", "zoom
  progresivo"). **Pantalla de carga** a pantalla completa estilo panel clásico
  (logo 140 px, nombre grande, giro de 44 px). **Hamburguesa** arreglada: en
  Replay la lateral se recoge al entrar pero el botón la despliega y pliega
  (antes la página forzaba el plegado y parecía roto). **Detalle del punto**
  simplificado (Hora, Dirección, Velocidad, Batería) + **Estado** (En
  movimiento / Detenido / Sin señal) y **Equipo** (Habilitado/Deshabilitado);
  fuera coordenadas, precisión, recepción y velocidad derivada. **Radio de
  acierto de la ruta** 80 → 250 m: era más estrecho que la capa pulsable y
  seleccionar un punto exigía puntería de pocos píxeles. **Paradas**: la
  segmentación ahora absorbe los tramos "en movimiento" de menos de 300 m
  (jitter del GPS con picos de velocidad reportada) antes de agrupar; con los
  datos reales de Pilay del 24/09 pasó de 28 paradas a 5 (las reales: casa,
  parada corta, dos en zona de trabajo y casa; verificado contra la base y por
  API). **Contraste**: contorno navy bajo la traza (fuera el halo blanco),
  línea de 3,5 a 6 px, panel de 348 px con bordes más marcados y barra inferior
  con sombra suave. **Zoom progresivo**: pasos finos de decimación (32→1) y
  tamaño de chevrón interpolado 0,4→0,85. Textos del panel y fichas más
  grandes. Verificado en navegador real (Fernando 24/09: 4 paradas, ficha
  completa, selección de punto, progreso, zoom). E2E 24/24 PASS.
- 2026-09-27: **panel de Replay reorganizado** (pedido del dueño). Fuera la
  sección **Recorrido** (Distancia/Duración/Huecos); "Punto seleccionado" pasa
  a llamarse **Detalles de ruta**. Los **mandos** (play/pausa, seguir, saltos de
  hueco y velocidades) se movieron de la franja inferior central al **panel,
  arriba junto a los filtros**; la franja conserva solo gráfico, lectura y
  slider. **Paradas**: al pulsar una fila el mapa **se centra en la parada**,
  pausa y selecciona el fix (misma sensación que elegir un colaborador en En
  vivo) y se listan todas (el cuerpo del panel ahora tiene scroll propio, la
  cabecera queda fija). Letras del panel, ficha, filtros y paradas más grandes
  (panel de 372 px). Verificado en navegador real: controles en el panel,
  play/pausa avanza el slider, salto a parada centra el mapa y muestra la
  ficha, sin errores. E2E 24/24 PASS.
- 2026-09-27: **mandos de vuelta a la franja inferior** (corrección del dueño
  sobre el punto anterior): play/pausa, seguir, huecos y velocidades vuelven a
  la franja inferior central, como estaban; el panel conserva "Detalles de
  ruta", las paradas con salto automático al punto y el scroll propio. E2E
  24/24 PASS. Además se empaquetó el proyecto completo (sin `.env`) en
  `/home/DMujeres-Tracking-completo-20260927.zip` (49 MB, 4.736 archivos).
  Descarga temporal habilitada en `:999` con nombre tokenizado
  (`proyecto-dmujeres-de249163669347817abb23f2.zip`, ruta `location =` en
  `sites-enabled/dmj-tracking-999`): **retirar el archivo y la ruta** cuando el
  dueño confirme la descarga.
- 2026-09-27: **pasada de diseño responsive** (login, carga, lateral, tablas,
  replay). Login y carga usan `100dvh` con respaldo de `100vh`; el login
  centra con alineación segura, hace scroll interno (teclado móvil), respeta
  `env(safe-area-inset-*)`, caja fluida `min(360px, 100% - 32px)` y casillas
  con clases propias (área de toque de 40 px en móvil). La carga baja el logo
  a 92 px y el nombre a 18 px por debajo de 420 px. La lateral gana el tercer
  estado: capa off-canvas con velo en ≤640 px (abre con la hamburguesa, cierra
  con el velo o al navegar), riel de íconos en 641-860 px y expandida en
  escritorio. Auditoría de tablas a 360 px: todas envueltas en
  `.tabla-envoltura`, sin desbordes del documento (Historial, Reportes,
  Batería, Configuración con scroll horizontal contenido). Replay en ≤420 px
  de alto: panel a 45% y franja compacta, con banda de mapa visible entre
  ambos (medido: 59 px). Verificado en navegador a 320/375/768 px y E2E 24/24
  PASS.
- 2026-09-27: **salto de Alejandro en Replay aclarado** (consulta del dueño).
  No es un fallo del trazado: el 27/09 su equipo (id 60) tiene 35 posiciones,
  parado de 00:22 a 08:52 y otra vez de 09:20 a 11:50, con **28 min sin
  reportar** entre ambas (salto real de 2,4 km). Su propio diagnóstico dice
  `gps: stale` y `fixAgeSec: 901` (15 min sin fix del proveedor fused): el
  teléfono (Infinix X6876, Android 16) deja de producir fixes al estar
  detenido. La app está al día (2.1.73, permisos y batería exentos, buffer
  vacío) y el servicio de recuperación ya le envió probes FCM (08:53, 09:22,
  09:37) que el teléfono acusó. Para que el hueco no se lea como ruta, el
  tramo sin datos se dibuja **gris punteado** (antes usaba el mismo rojo de la
  banda más rápida) y la leyenda suma "Tramo sin datos". E2E 24/24 PASS.
- 2026-09-28: **tramos estimados por calles** (pedido del dueño: que
  cualquier teléfono muestre una ruta continua). Nuevo servicio
  `dmj-routing` (`services/routing/RouteService.java`, systemd
  `dmj-routing.service`, 127.0.0.1:8992, 148 MB de RAM): reutiliza el grafo
  GraphHopper de Ecuador que ya estaba en `/opt/graphhopper` y expone
  `POST /route`; la API (`services/api/src/ruteo.js`) estima los tramos con
  fixes a ≥ 45 s y ≥ 150 m (huecos o cadencia lenta), con caché, topes (4 h,
  200 tramos, presupuesto 3 s) y degradación a la línea recta si no responde.
  El replay devuelve `estimados: [{desde, hasta, trazado}]` y la web los pinta
  con la banda de crucero (línea de 3,5 a 6 px + chevrones) como un tramo más,
  según elección del dueño ("que parezca ruta normal"); el CSV y las posiciones
  registradas no cambian. Verificado con datos reales: la tarde de Pilay del
  27/09 (26 tramos entre 17:41 y 18:17) queda pegada a las calles y el replay
  de Fernando del 27/09 se ve continuo. E2E **26/26 PASS** (dos pasos nuevos:
  ruteo vivo y `estimados` en el replay).
- 2026-09-28: **casos Pilay y Alejandro** (consulta del dueño). Pilay **no se
  actualizó**: sigue sin claves `mobile.appVersion`/diagnóstico y nadie bajó el
  APK (log de nginx solo con curls del E2E). Su trazo "peor" de la tarde se
  debe a que el teléfono mantuvo la cadencia de parado (~60 s) **mientras
  conducía** a casa (17:41-18:21, velocidades de 40-60 km/h); la mañana tenía
  fixes cada 1-5 s. Los tramos estimados por calles ahora cubren ese hueco sin
  tocar el teléfono. Alejandro (2.1.73, gps stale, fixAge 15 min) sigue
  reportando cada ~15 min y casi sin movimiento: el 27/09 solo hizo un salto
  real de 2,4 km (09:20) y el servicio de recuperación   le mandó 166 probes FCM
  desde el 27/09, todas acusadas.
- 2026-09-28: **Pilay 17:25 ampliado** (el dueño insiste en que actualizó). En
  el servidor sigue sin haber rastro de actualización (sin descargas del APK,
  sin `mobile.appVersion`, sin `lastOta*` y sin cambios en la auditoría, que
  solo muestra sus `maxRetries` del 25-26/09). El dato nuevo: a las
  17:38-17:41 la app se reinició o reenvió el buffer (fixes duplicados con
  seg=0 en 17:38:23, 17:40:03 y 17:41:05) y desde ahí el modo denso de la
  mañana no volvió: esa tarde y la madrugada siguiente quedaron en cadencia
  de parado/doze (60 s fijos con ráfagas cada 2 min antes; 90 s-6 min
  después). Conclusión para el dueño: algo tocó la app hacia las 17:35-17:40
  (reinicio, reinstalación manual o el sistema la recicló), pero con la
  evidencia actual no se puede confirmar qué versión corre; se pidió
  verificar la versión instalada en el teléfono y llevarla a 2.1.73 para
  tener diagnóstico.
- 2026-09-28: **auditoría y arquitectura final CERRADA** (encargo del dueño).
  Auditoría código vs documentación con evidencia archivo:línea y datos:
  18 hallazgos confirmados/riesgo y 1 hipótesis (ver
  `docs/audit/BUG-REGISTER.md`), destacando: `posicion_actual` retrocede,
  sin idempotencia, cola bloqueable por 4xx, movimiento por acelerómetro,
  partición basura 2037, APK 2.1.73 fuera de git y replay estimado
  indistinguible. Entregables: `docs/FINAL-ARCHITECTURE.md` (maestro),
  `docs/AI-HANDOFF.md` (plan para la IA implementadora),
  `docs/audit/IMPLEMENTATION-BACKLOG.md`, `docs/audit/DECISION-MATRIX.md` y
  `docs/ADR/ADR-001..010`. `docs/app/ARQUITECTURA-2.1.74.md` queda marcado
  como borrador histórico. Los 16 tests y la matriz Android/OEM quedan
  pospuestos (fase física, IA-2).
- 2026-09-28: **primera sesión ADB física** (ZTE Z2450, Android 14, app 2.1.73,
  equipo macias, por Tailscale). Doze forzado 4 min: sin degradación (fixes
  cada ~4 min). Kill del proceso cortó el canal ADB (puerto cerrado, ping OK);
  pendiente revisar el teléfono. Hallazgo: duplicado real en producción
  (fixes 93992/93993 idénticos, reintento sin dedupe). Registro en
  `docs/operations/PRUEBAS-FISICAS.md`.
- 2026-09-28: **2.1.74 en teléfono real + crash corregido**. La primera 2.1.74
  entraba en bucle por colisión de `DATABASE_VERSION = 5` (la 2.1.73 de calle
  ya la traía con otro esquema: upgrade nunca corría, sin tabla `meta`) más
  `stop()` no idempotente. Fix: versión 6 idempotente por PRAGMA,
  `ensureSchemaV6()` en cada apertura, meta tolerante, `stop()`/`onDestroy`
  que nunca lanzan y arranque en modo seguro. Reinstalada: 0 FATALs, FGS
  activo, posiciones con `boot_id`+secuencia monótona, alarma de 9 min
  entregada en Doze profundo con re-arme solo. Kill/force-stop + reapertura
  limpios; reboot pendiente sin el confusor del force-stop.
- 2026-09-28: **APK 2.1.74 compilado y listo para instalar** (pedido del
  dueño: verdadera prueba con la arquitectura pulida). Build
  `assembleGoogleRelease` en 52 s con el SDK local; claves restauradas de la
  copia fría a `/opt/dmj-keys/` (600, fuera de git) y `google-services.json`
  temporal para el build. APK 9,1 MB, vc 284, misma firma de flota que 2.1.73
  (SHA-256 idéntico) → actualiza sin borrar datos. Pendiente: puerto ADB
  nuevo del teléfono (el 46641 dejó de responder) para instalar y probar.
- 2026-09-28: **flujo app→servidor + giros en calle** (3 subagentes, ruta
  16:42–16:49 desalineada). App: `DuplicateFixGuard` en `write()` (descarta
  doble entrega mismo ms/coords y desplazamiento 0 con dt<5 s; causa raíz:
  `getCurrentLocation` compite con el callback sin pasar el filtro) + 5 tests
  nuevos (74 total: 69 ok, 1 omitido, 5 fallos preexistentes verificados en
  árbol limpio). Servidor: `ruteo.js` ajusta a vía ventanas densas (≤100
  pts/5 min, cortes en huecos y paradas, presupuesto 3 s + caché; jornada 101:
  1 MATCHED, pico 53,9 m → 14,1 m a vía, desviación máx 20,1 m). Web: capa
  MATCHED densa con chevrones + ficha + saneo (tsc + vite ok). E2E 26/26 con
  el ruteo nuevo. APK **2.1.75 (vc 285)** firmada lista para instalar y probar
  en campo (misma firma de flota).
- 2026-09-28: **match en giros + ruido parado + trazado corporativo** (ruta
  17:17–17:20, 3 subagentes). Servidor: el interior de paradas prolongadas ya
  no entra a /match y los teleports se apartan del ajuste (crudo intacto);
  jornada 101: de 2 ventanas (1 basura) a 1 MATCHED limpio (maxSnap 10,9 m),
  hueco clásico sigue ESTIMATED. App: colapso en parado (≥15 m o latido 5
  min), captura en giro (≥30°), modo caminata (2–8,5 km/h, `caminando` en
  diagnóstico) y guardia de teleport (>200 km/h + incoherencia T2); 60/60
  tests nuevos, suite 118 con 5 fallos preexistentes, debug compila. Web:
  corredor corporativo (casing+núcleo+chevrones), caminata fina, quieto como
  halo+nube sin espagueti, paradas con insignia numerada y duración, pines
  inicio/fin, leyenda de 8 entradas (tsc + vite ok). E2E 26/26. APK **2.1.76
  (vc 286)** firmada lista para campo.
- 2026-09-28: **implementación ejecutada con subagentes** (encargo del dueño,
  FASE 1-11). Servidor: migración 002 aplicada (idempotencia, guardas de viva,
  validación de fechas, purga 2037), lote `/positions`, `GET /journey`,
  particiones mes+2, `/match` + `mapaVersion` en routing, `/api/v1/salud`.
  App: máquina de movimiento, store v5, cola con backoff, recovery con
  alarma, JourneyManager, diagnóstico extendido (build + 69 tests en verde).
  Web: replay REAL/MATCHED/ESTIMATED (`estimados` eliminado), 401 SPA, salud
  con causa. Release: sin credenciales en repo, 284/2.1.74 en código (APK sin
  firmar), gate TLS documentado. Servicios reiniciados, E2E 26/26 PASS,
  detalle en `docs/IMPLEMENTATION-COMPLETE.md`. Producción condicionada a
  pruebas físicas.
- 2026-09-28: **consolidación de flota ejecutada** (pedido del dueño):
  joseph 39→53, miguel 41→60, kevin 40+52→59, jeremy 51→58, con respaldo
  previo verificado y ensayo con rollback. 0 huérfanos, 15 dispositivos.
  Pilay ya era uno solo (56) y no tiene usuario (pendiente crearlo). Joseph
  unificado en el 53 pero deshabilitado (si vuelve, habilitar el 53). Detalle
  en `docs/operations/CONSOLIDACION-2026-09-28.md`.
- 2026-09-28: **veredicto 2.1.66**: el diff 2.1.66→actual en la app no toca
  nada de trazo/ubicación (solo bienvenida), así que degradar la flota no
  cambiaría el dibujo y la OTA ni siquiera puede degradar a equipos en 283.
  Se recomienda NO degradar y mantener 2.1.73 + tramos estimados.
- 2026-09-28: **caso Joseph** (el dueño dio su historial: 2.1.35 → 2.1.6x →
  2.1.73). Hay **dos registros**: 39 "joseph" (`historico-qa-39-joseph`, con
  todo septiembre 11-22/09) y 53 "Joseph" (identificador `joseph`, solo 5
  fixes el 22/09 17:04-17:07); **ambos deshabilitados**. La versión por
  reporte: 2.1.35 = 15 s moviendo/60 s parado; 2.1.56-64 = 15 s/120 s
  adaptativa; 2.1.65 = arreglo de movimiento; 2.1.66 = 10 s/120 s ("trazo
  impecable"); 2.1.67-72 = solo bienvenida; 2.1.73 = build OTA actual. Su
  comportamiento de septiembre (denso 7 s manejando el 13/09, disperso
  100-900 s parado, huecos de hasta 60 h el fin de semana) es normal para
  esas versiones. **Crítico**: el identificador que manda su app (`joseph`)
  resuelve al 53, que está deshabilitado, y el servidor **descarta** esas
  posiciones (200 OK, nada guardado). Si vuelve a operar, hay que habilitar
  el 53, no el 39.
- 2026-09-28: **ahorro de batería en Pilay** (hipótesis del dueño, confirmada
  en lo esencial). La batería descarta el ahorro automático (82% a las 17:00,
  cargó 08-10h, 61% a las 23:00; el saver automático salta al 15-20%), pero un
  ajuste **manual** cuadra perfecto: a las 17:38-17:41 hay 3 fixes duplicados
  (misma hora dos veces: 17:38:23, 17:40:03, 17:41:05), firma de que el proceso
  se mató y reinició reenviando la cola; desde ese reinicio quedó en cadencia
  lenta (60 s manejando, 90 s-6 min en casa). Cambiar el modo de batería de la
  app mata el proceso en muchos fabricantes. Se pidió revisar en el teléfono:
  Batería de la app en "Sin restricciones", ahorro del sistema apagado y
  versión instalada.
- 2026-09-28: **plataforma subida a GitHub** (pedido del dueño). El repo
  `sekaishopml/Dmujeres-Traccar` ya tenía 231 commits del monorepo viejo (con
  `docs/` e `infrastructure/` que chocaban), así que la plataforma se subió a
  la rama nueva **`plataforma`** (227 archivos, sin `.env`): main quedó
  intacto. Ojo: el repo es público y el árbol lleva defaults de prueba
  (`cctv2026` en `scripts/validation/e2e.sh`) y documentos internos; valorar
  pasarlo a privado. El token usado para el push se eliminó del disco y se
  desactivó el `credential.helper` global que lo había guardado solo.
- 2026-09-28: **cadencia por versión** (caso Pilay). La cadencia está **idéntica
  desde la 2.1.66 hasta la 2.1.72** (10 s moviendo / 120 s parado / 10 m; esas
  versiones solo tocaron la bienvenida): lo que instaló a las 17:00 no cambió
  la cadencia por constantes. Lo que cambió es que la app se quedó en modo
  lento durante ese viaje (o el doze la congeló); por eso la tarde quedó a
  ~60 s por fix. Sigue pendiente confirmar la versión instalada en el teléfono
  (Ajustes → Apps).
- 2026-09-28: **llegada/salida de Pilay y corrección de doble trazo** (pedido
  del dueño). La llegada (densa, 1-5 s) y la salida (dispersa, 60 s) se ven
  distintas porque son dos cadencias reales del teléfono, no un fallo del
  dibujo; lo que instaló a mano a las ~17:00 no deja rastro en el servidor (sin
  descarga OTA, sin `mobile.appVersion`, sin diagnóstico), así que sigue sin
  poder confirmarse qué versión corre. Fallo propio corregido: la recta amarilla
  y la ruta por calles se dibujaban **encima una de otra**; ahora el tramo
  estimado reemplaza a la recta y usa la **banda de velocidad real del par**
  para verse como un tramo normal. Documentado Doze/wake lock en
  `docs/app/WAKE-DOZE.md`; la arquitectura 2.1.74 queda pendiente. E2E 26/26.

## Decisiones tomadas por el dueño del producto

- Recuperar **todo** el histórico fragmentado (17/08 → hoy).
- Motor de la base nueva: **PostgreSQL + TimescaleDB** (imagen
  `timescale/timescaledb:2.30.1-pg18`; la extensión va en 2.x, no existe “3”).
- Liberados 9 GB de caché de Docker; disco al 84%.

## Decisiones menores pendientes (no bloquean FASE 4)

- Dispositivos históricos de QA/dumps: se migraron 10 deshabilitados con
  identificador `historico-*` (uniqueid chocaba entre fuentes). ¿Se aceptan o
  se remapean sus posiciones a los equipos vivos?
- `tc_mobile_messages` (cola técnica MQTT), `tc_groups` (6), `tc_actions`,
  `tc_device_health`: migrar en una pasada 3b o dejar fuera (`mobile_messages`
  se propone no migrar).
- 3 `mobileJourneyEnded` sin `Started` no derivables (omitidos).

## Hallazgos que condicionan las siguientes fases

1. **Histórico fragmentado (riesgo de continuidad).** Producción solo tiene
   2026-09-22 → hoy. El tramo 2026-08-17 → 09-03 está en
   `tc_positions_bak_20260903`, hasta 09-12 en `traccar_qa`, y 09-12 → 09-22
   solo podría estar en `traccar-pre-purga-recorridos.dump` (sin verificar).
   Antes de FASE 3 hay que decidir si se recupera todo el histórico.
2. **Objetivo PostgreSQL**: el plan fija 18.6; el actual es PostgreSQL 17.10
   con TimescaleDB 2.29.1 (hypertables en `tc_positions`, `tc_events`,
   `tc_actions`). Decidir si la instalación nueva usa Postgres 18.6 sin
   Timescale y cómo se conserva el histórico.
3. **Seguridad**: 999 y 5055 abiertos a Internet en HTTP plano, sin Nginx ni
   TLS; la cookie del panel actual no es `Secure`; el directorio público sirve
   APKs con la API key móvil compilada. La Web nueva exige cookie propia
   HttpOnly+Secure+SameSite y el plan contempla Nginx/TLS.
4. **Backups previos defectuosos**: dos dumps del 20 y 21 de septiembre
   quedaron en 0 bytes; el respaldo de hoy corrige esa brecha.

## Decisiones pendientes (no bloquean FASE 2)

- Alcance de recuperación del histórico fragmentado (FASE 3).
- PG 18.6 propio vs mantener TimescaleDB (FASE 3).
- Terminación TLS: Nginx + Let's Encrypt o Cloudflare (FASE 8).
- 2026-09-28: **fin del doble trazo + cambio de equipo seguro**. Web: la capa
  MATCHED densa ya no se superpone a la cruda (se suprime el par crudo
  interior a la ventana; solo una línea por parte, ADR-007 intacto). App:
  cola con identidad por fila (`device_id` del momento de captura, esquema v7)
  y subida agrupada por equipo; al entrar con OTRA cuenta se ejecuta el cierre
  limpio (flush + fin de jornada del equipo anterior) antes de adoptar el
  equipo nuevo. 180 tests google en verde (fallos preexistentes de Firebase
  corregidos con @Config). APK 2.1.83.
- 2026-09-28: **match urbano afinado** (Fernando 20:03-21:50, 318 fixes):
  puente de huecos cortos (<=150 s y <150 m: tráfico lento, semáforos y
  paradas cortas unen la ventana), umbral de desplazamiento 50->25 m, filtro
  de picos 30/15->25/13 y `accuracy` (precisión mediana de la ventana) al
  `/match` del ruteo (snap trust = 4*precision acotado 25..60 m). Cobertura
  ajustada a vía 39 % -> 72 % de los puntos (el resto es tiempo parado), sin
  desvíos inventados. Sin cambios en la app: sin OTA.
