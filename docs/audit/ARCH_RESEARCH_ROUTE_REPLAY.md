# ARCH_RESEARCH_ROUTE_REPLAY — Repetición de ruta impecable y honesta

**Investigación técnica (solo fuentes públicas: GitHub + documentación oficial).** 2026-09-19. Alcance: cómo Traccar Web, matchers OSS (GraphHopper/Valhalla/OSRM) y apps masivas segmentan, limpian y dibujan rutas con huecos; sin cambios de código. El estado local se cita con `archivo:línea`. Documento hermano: `docs/audit/ROUTE_SEGMENTATION_AUDIT.md`.

## 1. Traccar Web (traccar/traccar-web)

- **Dibujo**: `MapRoutePath.js:28-44` genera una `LineString` por cada par consecutivo de posiciones y la colorea por velocidad (`getSpeedColor`); no evalúa huecos ni distancias. <https://github.com/traccar/traccar-web/blob/master/src/map/MapRoutePath.js#L28-L44>
- **Coordenadas ya calculadas**: `MapRouteCoordinates.js:56-62` dibuja un único `LineString` tal cual, mismo estilo sin cortes. <https://github.com/traccar/traccar-web/blob/master/src/map/MapRouteCoordinates.js#L56-L62>
- **Replay**: `ReplayPage.jsx` reutiliza `MapRoutePath` sobre `positions` crudas y un slider por índice; no re-segmenta ni hace snap. <https://github.com/traccar/traccar-web/blob/master/src/other/ReplayPage.jsx>
- **Filtros server-side** (config global o atributo de dispositivo): `filter.duplicate` (mismo fixTime), `filter.future` (s), `filter.distance` (m; descarta si está más cerca que X del último), `filter.maxSpeed` (nudos; descarta si dist/Δt supera X), `filter.accuracy` (m), `filter.static`, `filter.zero`, `filter.invalid`. **No existe `filter.speed`**: el equivalente real es `filter.maxSpeed`. <https://www.traccar.org/configuration-file/> · <https://github.com/traccar/traccar/blob/master/src/main/java/org/traccar/handler/FilterHandler.java#L89-L117>
- **Stops/eventos**: el server deriva paradas/viajes con `TripsConfig` (`minimalTripDistance=500 m`, `minimalTripDuration=300 s`, `minimalParkingDuration=300 s`, `minimalNoDataDuration=3600 s`; "gaps in reported positions longer than this value are considered as stops (only in reports)"). La UI los muestra en reportes y `EventsDrawer`, aparte del trazo. <https://www.traccar.org/trips-stops/> · <https://github.com/traccar/traccar/blob/master/src/main/java/org/traccar/reports/common/TripsConfig.java>
- **Lección**: los filtros de Traccar son de *descarte*, no de segmentación; un hueco que pasa filtros se dibuja como recta. Nuestro `shouldCut` + corte en UI ya es más honesto (ver §7).

## 2. Map-matching OSS

### GraphHopper 8 (el que usamos)
- Algoritmo HMM/Viterbi (Newson & Krumm 2009): candidatos por observación + secuencia más probable. <https://github.com/graphhopper/graphhopper/blob/master/map-matching/README.md>
- Endpoint HTTP `/match`: solo GPX y **un solo track** (`MapMatchingResource.java:110`; con 2+ tracks lanza excepción); `gps_accuracy` default 10 → `setMeasurementErrorSigma` (`:104`). <https://github.com/graphhopper/graphhopper/blob/master/web-bundle/src/main/java/com/graphhopper/resources/MapMatchingResource.java#L104-L110> · <https://docs.graphhopper.com/openapi/map-matching>
- Defaults de librería: sigma 10 m, beta 2, uTurn 40 (`MapMatching.java:72-73`); `filterObservations` fusiona observaciones a <2σ del punto conservado (`:398-430`). <https://github.com/graphhopper/graphhopper/blob/master/map-matching/src/main/java/com/graphhopper/matching/MapMatching.java#L72-L73>
- **No segmenta**: si no hay transición lanza `Sequence is broken ... time step N` (`:666-669`); el llamador debe cortar y reintentar (lo hace nuestro `MatchService.resilientMatch`). Sí publica estadísticas (`snapDistances`, `transitionDistances`, `visitedNodes`, `:283-291`) para derivar calidad. No expone `confidence`.

### Valhalla Meili
- `trace_route`/`trace_attributes` (HMM). Defaults en `scripts/valhalla_build_config:292-299`: `search_radius=50`, `gps_accuracy=5`, `interpolation_distance=10`, **`breakage_distance=2000`** (si dos puntos distan más, no intenta conectarlos), `max_search_radius=100`, `turn_penalty_factor=200`. <https://github.com/valhalla/valhalla/blob/master/scripts/valhalla_build_config#L280-L305> · <https://valhalla.github.io/valhalla/api/map-matching/api-reference/>
- `type:"break"` marca cortes explícitos (solo en `map_snap`; no con polyline codificada) y `shape_match` permite `edge_walk` (ruta previa exacta) vs `map_snap` (GPS sucio). La discusión oficial confirma que breakage "automatically break[s] the route there and not try to find a route between those two points". <https://github.com/valhalla/valhalla/discussions/3668>

### OSRM match
- `/match/v1/{profile}` corta ante gaps de timestamp >60 s (`gaps=split` default); `radiuses` = error estándar (accuracy) por punto; `tidy=true` mejora trazas ruidosas; los outliers se eliminan y quedan `tracepoints=null`; cada sub-ruta trae `confidence` 0–1. <https://github.com/Project-OSRM/osrm-backend/blob/master/docs/http.md#match-service>

### Uso en trackers reales
- GPSLog Labs (de un co-autor de GraphHopper) usa matcheo para limpiar logs y detectar ruta/actividad. <https://gpsloglabs.com/>
- OwnTracks **no** matchea: filtra en el cliente por precisión (`ignoreInaccurateLocations`, metros; 0=off) y publica crudo. <https://github.com/owntracks/android/issues/409>
- Patrón común: **segmentar antes de matchear** y no cruzar nunca el breakage.

## 3. Suavizado y limpieza (proyectos reales)

- **Kalman (pre-render, nunca para distancias)**: `mad-location-manager` (fusión GPS+IMU; "excludes sharp «jumps»", "filters errors due to the short-term loss of GPS-signal"); `HCKalmanFilter` iOS (`rValue=29` default, más alto = más redondeo); `trackmaker` (MapLibre + Kalman 6D). <https://github.com/maddevsio/mad-location-manager> · <https://github.com/Hypercubesoft/HCKalmanFilter> · <https://github.com/Anson2251/trackmaker>
- **Filtro de precisión**: OwnTracks `ignoreInaccurateLocations`; Traccar `filter.accuracy`; GPSBabel `discard,hdop=10,vdop=20,sat=3`. <https://github.com/owntracks/android/issues/409> · <https://www.traccar.org/configuration-file/> · <https://www.gpsbabel.org/htmldoc-development/filter_discard.html>
- **Velocidad imposible**: Traccar `filter.maxSpeed` (nudos, derivado de distancia/tiempo; configs de comunidad usan 230); nosotros `TELEPORT_MAX_SPEED_MPS=35` con corroboración Doppler (`pathDecimation.js:910-978`). <https://www.traccar.org/configuration-file/> · `/DMujeres-Tracking/dashboard/src/map/util/pathDecimation.js:910`
- **Douglas-Peucker**: Leaflet `smoothFactor` (default 1.0, simplifica por zoom; 0 lo desactiva); `simplify-js` (DP + radial, extraído de Leaflet); `turf.simplify` (RDP, `tolerance=1`); GPSBabel `simplify` con error crosstrack/length/relative (relative pondera por HDOP). <https://leafletjs.com/reference.html#polyline-smoothfactor> · <http://mourner.github.io/simplify-js/> · <https://github.com/Turfjs/turf/tree/master/packages/turf-simplify> · <https://www.gpsbabel.org/htmldoc-development/filter_simplify.html>
- **Bezier**: solo estético; Turf `bezierSpline` advierte "visual-only smoothing … do not use it for measurement or topology". <https://turfjs.org/docs/api/bezierSpline>
- **Gap detection — umbrales reales**: GPSBabel `track,pack,sdistance=0.3k,split=5m` corta solo si el hueco supera tiempo **y** distancia ("filter out gaps from the tracklog"); OSRM >60 s; Valhalla >2000 m; Traccar `minimalNoDataDuration=3600 s` (solo reportes); Strava no corta (recta, §5). <https://www.gpsbabel.org/htmldoc-development/filter_track.html> · <https://github.com/Project-OSRM/osrm-backend/blob/master/docs/http.md#match-service> · <https://www.traccar.org/trips-stops/>
- **Nuestro caso**: `shouldCut` ya combina 45 s + 200 m (fast-gap), 5 min (max-gap), same-place 100 m y teleport >35 m/s (`pathDecimation.js:817-990`). Banda ciega 45 s–5 min con 100<dist≤200 m documentada en `docs/audit/ROUTE_SEGMENTATION_AUDIT.md` §1.3 (propuesta: bajarla a `SAME_PLACE_M=100` y registrar el motivo del corte).

## 4. Interpolación honesta (medido vs estimado)

- **GPX 1.1**: "start a new Track Segment for each continuous span of track data" cuando se pierde recepción o se apaga el receptor: el corte es la representación canónica del hueco. <https://www.topografix.com/gpx/1/1/#type_trksegType>
- **Strava**: no interpola ni hace snap en actividades: "the pre-and post-signal-loss points … are connected with a straight line". El "GPS bounce" además infla distancia ("each zig and zag … has to be accounted for"). <https://support.strava.com/en-us/articles/15402062-troubleshooting-android-gps-issues> · <https://communityhub.strava.com/developers-api-7/gpx-import-ignores-trkseg-xml-tags-thus-violating-gpx-specification-and-counting-wrong-distance-1679>
- **Google Timeline (export)**: cada tramo trae `confidence` HIGH/MEDIUM/LOW y `waypointPath.source="BACKFILLED"` cuando Google reconstruyó el recorrido después en vez de medirlo; la ayuda oficial avisa de errores en zonas densas. <https://dawarich.app/blog/whats-inside-your-google-timeline-export/> · <https://support.google.com/maps/answer/14169818>
- **Visores**: Timeline Visualizer dibuja el hueco punteado y lo etiqueta "a visual cue, not proof that the missing movement followed that exact path"; la app iOS `GPS-location-app` marca puntos de relleno estimados y guarda un score de calidad 0-100. <https://timelinevisualizer.online/> · <https://github.com/yu314-coder/GPS-location-app>
- **GPSBabel `interpolate,time=10`**: rellena en recta/gran círculo solo si se pide explícitamente; nunca por defecto. <https://www.gpsbabel.org/htmldoc-development/filter_interpolate.html>
- Conclusión: **sólido = medido** (crudo o pegado a vía); **punteado = inferido**; nunca unir cortes por defecto.

## 5. Strava / Life360 / Google (solo público)

- **Strava**: soporte afirma que Strava "does not do this [snap to roads] and just use GPS" (a diferencia de apps de navegación); su única corrección pública es la recta entre fixes válidos. No hay blog de ingeniería de matching/smoothing de actividades. <https://support.strava.com/en-us/articles/15402062-troubleshooting-android-gps-issues>
- **Life360**: sin documentación de pipeline. `Daily History` aclara que los puntos indican "when the app updated its location" (no traza continua). Forense: guarda `horizontalAccuracy` pero no lo muestra, calcula velocidad entre posiciones y tiene `isLocationClusteringEnabled`; nada público de matching/smoothing. <https://support.life360.com/hc/en-us/articles/23829025907095-Daily-History> · <https://www.zkservices.net/post/life-360-app-cell-phone-forensics>
- **Google**: además de Timeline, el producto público de matcheo es Roads API `snapToRoads` (con `interpolate=true`), el mismo enfoque que usan las apps de navegación. <https://developers.google.com/maps/documentation/roads/snap>

## 6. Rendering en el navegador

- **Leaflet**: `smoothFactor` simplifica por zoom (DP) y `dashArray` permite puntear por polilínea; `leaflet-gpx` dibuja cada `trkseg` como polilínea separada con `gpx_options.joinTrackSegments=false` (huecos sin línea = GPX correcto). <https://leafletjs.com/reference.html#polyline-dasharray> · <https://github.com/mpetazzoni/leaflet-gpx>
- **MapLibre GL**: `line-join:'round'` y `line-cap:'round'` (ya en `MapRouteMatch.jsx:40-43`) evitan picos; `line-dasharray` es propiedad **no data-driven**, así que los tramos inferidos necesitan capa/filtro aparte; no hay bezier en el estilo GL: la polilínea recta es lo honesto. <https://maplibre.org/maplibre-style-spec/layers/#line-dasharray>
- **Zigzag por ruido**: se resuelve antes de dibujar (simplify + filtro de accuracy + snap), no con curvas (§3). <http://mourner.github.io/simplify-js/>
- **Nuestro estado**: `MapRouteMatch.jsx:61-102` dibuja matched (color ruta) y fallback crudo (color velocidad) en la misma capa sólida; `linkTracksFor` respeta `shouldCut` (`replayAudit.js:364-376`), o sea B-1 del audit R3 ya está cerrado. Falta distinción/leyenda y calidad visible.

## 7. Recomendaciones para nuestro caso (aprovechando `dmj-match.service`)

- **(a) Outliers**: mantener `cleanRoutePositions` + `isTeleport` con Doppler; no enviar al matcher fixes con accuracy peor que el umbral del filtro (hoy `ReplayPage.jsx:265-275` usa `hideInaccurate:false`), o marcar ese track como crudo; mostrar cuántos se ocultaron.
- **(b) Segmentación**: conservar `shouldCut`; cerrar la banda 100–200 m; persistir motivo del corte (max-gap / fast-gap / teleport / same-place) para UI y métrica.
- **(c) Matching por segmento**: ya existe (`MatchResource.java:54-62,109-178`: chunks 300, overlap 4, decimación 12 m/120 s; `MatchService.java:148-209`: `resilientMatch`, `SNAP_TRUST_M=60`). Mejoras: `MatchService` lee `accuracy` pero **no la usa** (`MatchService.java:84`; sigma fijo 10 en `:62`) — aplicar la peor accuracy del chunk; devolver `snappedRatio` (se calcula en `MatchService.java:111` y `MatchResource` lo descarta) y `snapDistances` por punto.
- **(d) Fallback crudo**: ya existe (`MatchResource.java:139-148` devuelve `null` → track crudo); mantener y etiquetar como "sin match".
- **(e) Render honesto**: sólido = medido (crudo), color de ruta = pegado a vía; punteado = solo conectores inferidos (hoy no se dibuja ninguno: corte seco estilo GPX); capa separada para dasharray; leyenda fija "+ medido / ~ estimado / ⚠ sin señal".
- **(f) Calidad (%)**: por replay `fixes_usados/total`, `% fixes casados` (snappedRatio), `% distancia matched`, nº de cortes por causa, máx. snap distance; badge "Calidad 92% · 2 cortes · 1 tramo sin match" + tag Sentry. Precedentes: `confidence` OSRM, `snapDistances` GraphHopper, score 0-100 de la app iOS.

## 8. Tabla final: pipeline → referencia → propuesta

| Paso | Cómo lo hacen otros (URL) | Propuesta para nosotros |
|---|---|---|
| Orden + dedupe + accuracy | OwnTracks `ignoreInaccurateLocations` <https://github.com/owntracks/android/issues/409>; Traccar `filter.accuracy` <https://www.traccar.org/configuration-file/>; GPSBabel `discard` <https://www.gpsbabel.org/htmldoc-development/filter_discard.html> | Mantener `canonicalRouteGeometry`; excluir del match accuracy alta (hoy `ReplayPage.jsx:265-275`); contar ocultos |
| Outliers de velocidad | Traccar `filter.maxSpeed` <https://www.traccar.org/configuration-file/>; teleport propio 35 m/s `pathDecimation.js:910-978` | Mantener + corroboración Doppler/accuracy ya implementada; registrar motivo |
| Corte por huecos | GPSBabel `split+sdistance` <https://www.gpsbabel.org/htmldoc-development/filter_track.html>; OSRM 60 s <https://github.com/Project-OSRM/osrm-backend/blob/master/docs/http.md#match-service>; Valhalla 2000 m <https://github.com/valhalla/valhalla/blob/master/scripts/valhalla_build_config#L292-L299>; GPX `trkseg` <https://www.topografix.com/gpx/1/1/#type_trksegType> | Mantener `shouldCut`; cerrar banda 100–200 m; motivo visible |
| Map-matching por segmento | Valhalla `breakage_distance` + `type:break` <https://valhalla.github.io/valhalla/api/map-matching/api-reference/>; OSRM `gaps=split` <https://github.com/Project-OSRM/osrm-backend/blob/master/docs/http.md#match-service>; GH no segmenta <https://github.com/graphhopper/graphhopper/blob/master/map-matching/src/main/java/com/graphhopper/matching/MapMatching.java#L666-L669> | Ya: chunks + `resilientMatch` (`MatchResource.java:109-178`, `MatchService.java:148-209`); aplicar accuracy real del chunk |
| Fallback crudo | OSRM `tracepoints=null` <https://github.com/Project-OSRM/osrm-backend/blob/master/docs/http.md#match-service>; leaflet-gpx separa trkseg <https://github.com/mpetazzoni/leaflet-gpx> | Ya: `null`→raw (`MatchResource.java:139-148`); etiquetar "sin match" |
| Render honesto | Timeline Visualizer punteado <https://timelinevisualizer.online/>; Strava recta (evitar) <https://support.strava.com/en-us/articles/15402062-troubleshooting-android-gps-issues>; MapLibre dasharray <https://maplibre.org/maplibre-style-spec/layers/#line-dasharray> | Sólido medido / color vía matched / punteado solo inferido; leyenda |
| Métrica de calidad | OSRM `confidence` <https://github.com/Project-OSRM/osrm-backend/blob/master/docs/http.md#match-service>; GH `snapDistances` <https://github.com/graphhopper/graphhopper/blob/master/map-matching/src/main/java/com/graphhopper/matching/MapMatching.java#L283-L291>; app iOS score <https://github.com/yu314-coder/GPS-location-app> | `snappedRatio` + cortes + % distancia; badge y tag Sentry |

**Qué ya resuelven otros por nosotros**: Viterbi y thinning de observaciones (GraphHopper), modelo de transición y breakage (Valhalla/OSRM), patrón de corte GPX (spec), simplificación (Leaflet/simplify-js), dasharray (MapLibre). **Qué toca reimplementar (poco)**: política per-point accuracy, estado visual por tramo, métrica de calidad y cierre de la banda de huecos.
