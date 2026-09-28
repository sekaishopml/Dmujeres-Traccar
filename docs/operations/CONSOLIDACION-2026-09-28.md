# Consolidación de dispositivos y versiones (2026-09-28)

Resuelve los casos de la flota con evidencia de datos y de código. Respaldo
previo verificado: `/home/DMujeres-backups/legado-final/pre-consolidacion-20260928-105023.dump`
(7,7 MB, SHA-256 al lado).

## Fusión ejecutada (una transacción, con ensayo previo)

| Caso | Origen → destino | Posiciones | Eventos | Jornadas | Alertas | Batería |
|---|---|---|---|---|---|---|
| joseph uno solo | 39 "joseph" → 53 "Joseph" | 2.672 | 0 | 0 | 391 | 0 |
| miguel → alejandro | 41 → 60 | 598 | 0 | 0 | 403 | 0 |
| kevin → fernando | 40 → 59 y 52 → 59 | 2.974 + 5.519 | 627 | 2 | 268 + 1.613 | 5.613 |
| jeremy → manzaba | 51 → 58 | 0 | 0 | 0 | 0 | 0 |

Reglas aplicadas: las posiciones/eventos/jornadas/alertas/batería se mueven
al registro vivo; `posicion_actual` se refresca al último fix de cada
objetivo; los tokens viejos se borran (el teléfono ya se registró con la
identidad nueva); las asignaciones cerradas de los viejos caen en cascada
(las vigentes ya existen en los vivos con el mismo periodo); se borran los
registros 39, 40, 41, 51 y 52. Verificación: 0 huérfanos, totales
conservados (59: 11.311 pos, 53: 2.677, 60: 734), 15 dispositivos.

- **Pilay**: ya era uno solo (56, sin duplicados) y no tiene usuario.
  Pendiente decidir si se le crea cuenta como a manzaba/fernando/alejandro.
- **Joseph** quedó unificado en el 53 pero **deshabilitado** (como estaba): su
  app reporta con ese identificador y el servidor descarta esas posiciones.
  Si vuelve a operar, habilitar el 53 (nunca el 39, que es histórico).

## Versiones y cadencia (evidencia de código y de datos)

| Versión | Cadencia | Fuente |
|---|---|---|
| 2.1.35 | 15 s moviendo / 60 s parado | `TrackingController` en tag v2.1.35 |
| 2.1.56–2.1.64 | 15 s / 120 s adaptativa | commit 93cd940, `AdaptiveCadence` en v2.1.64 |
| 2.1.65 | + la velocidad real fuerza la cadencia fina | commit a4827bb (caso Manzaba) |
| 2.1.66 | 10 s / 120 s, sin batching ("trazo impecable") | commit 0b64831 |
| 2.1.67–2.1.72 | **sin cambios de trazo** (solo bienvenida) | `git diff 0b64831..af926a4 -- fallback/` |
| 2.1.73 | = 2.1.72 + bump de versión (build OTA actual) | `ota/latest.json` (vc 283) |

Conclusión: bajar la flota a 2.1.66 **no cambiaría el trazo** (el código de
ubicación es idéntico) y la OTA ni siquiera puede degradar a equipos que ya
están en 283 (`instalado >= publicado` → no ofrece). El "trazo peor" de Pilay
no vino de la versión sino del modo: a las 17:38-17:41 su proceso se reinició
o reenvió la cola (fixes duplicados 17:38:23, 17:40:03, 17:41:05) y desde ahí
quedó en cadencia lenta (~60 s manejando, 90 s-6 min en casa por doze) en vez
del modo denso de la mañana (1-5 s). Los tramos estimados por calles ya
cubren ese caso en el dibujo; la raíz (despertar en Doze) es la arquitectura
2.1.74 pendiente (`docs/app/WAKE-DOZE.md`).
