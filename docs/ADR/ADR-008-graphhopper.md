# ADR-008 — GraphHopper y reconstrucción geográfica

Estado: **CERRADO** (2026-09-28).

## Contexto

`dmj-routing` (nuevo) expone `/route` reutilizando el grafo de Ecuador en
`/opt/graphhopper/graph-cache-8` y el jar sombreado del matcher retirado
(`/opt/route-service/matchservice.jar`, GraphHopper 8). El matcher original
(`MatchService.java`, en el respaldo) ya sabía hacer map matching pero se
retiró en FASE 12. Hoy solo se usa ruta A→B para huecos.

## Decisión

GraphHopper es el único motor GIS. Tres operaciones:
1. `/match`: map matching de fixes reales (reconstrucción fiel cuando hay
   observaciones).
2. `/route`: ruta A→B para huecos **sin** observaciones (estimación).
3. Reconstrucción de hueco: usa `/match` si el hueco contiene ≥2 fixes
   (poco probable), si no `/route`.

Umbrales actuales se conservan (≥45 s y ≥150 m para tramos a reconstruir;
huecos formales >10 min; topes 4 h, 200 tramos, presupuesto 3 s). Cache por
endpoints + perfil + versión de grafo + versión de algoritmo. Sin respuesta →
recta punteada (nunca inventar).

## Alternativas descartadas

- OSRM/Valhalla: reimportar el planeta/país sin necesidad.
- APIs públicas: dependencia externa y privacidad.
- Quitar GraphHopper: perdería la única fuente de reconstrucción honesta.

## Consecuencias

- IA-2 reincorpora `MatchService` al servicio (`services/routing/`) y
  versiona el grafo (hash del pbf) en cada respuesta.
