# Arquitectura de DMujeres Tracking

Raíz nueva: `/home/DMujeres-Tracking`. La instalación actual
`/DMujeres-Tracking` queda como fuente de datos, compatibilidad y respaldo
hasta el cutover (no se modifica). Decisiones: `docs/architecture/adr/`.

## 1. Componentes y límites

| Componente | Ruta | Responsabilidad | Límite (no hace) |
|---|---|---|---|
| Web | `apps/web` | Panel de operación y administración (React 19.3 + TypeScript + Vite 8.1, MapLibre GL JS 6.x, Chart.js, TanStack Query, Zustand) | No conoce tablas ni el motor de tracking; **cero lógica de negocio**; solo consume `/api/v1` |
| App mobile | `apps/mobile` | Captura y transmite posición/telemetría | Compatibilidad primero; evoluciona después; contrato congelado de la App actual |
| API | `services/api` | Fachada: autenticación, permisos, contratos, BFF y agregación (Node 24 LTS) | No contiene SQL de negocio disperso; no conoce el esquema interno del motor; no sirve Vite |
| Tracking | `services/tracking` | Recepción (OsmAnd/HTTP/MQTT), validación, procesamiento y persistencia de posiciones, eventos y telemetría | Servicio estable; no expone su esquema hacia afuera; no se rompe en el primer corte |
| Contratos y tipos | `packages/contracts` (OpenAPI 3.1), `packages/shared-types` (TypeScript), `packages/config` | Contrato único `/api/v1`, tipos compartidos y variables de entorno | Sin dependencias externas en `shared-types`; sin secretos en el repo |
| Base de datos | `database` | Esquemas `iam`, `tracking`, `telemetry`, `operations`, `audit`, `system` con tablas `dmt_*`, particiones, índices y migraciones (PostgreSQL 18.6) | Sin SQL manual fuera de migración; sin `tc_*` en la plataforma nueva |
| Infraestructura | `infrastructure` | systemd, Nginx, TLS, firewall, entornos y backups | Puertos internos solo en `127.0.0.1`/red privada; solo Nginx expone HTTPS |
| Pruebas | `tests` | unit, integration, smoke y E2E | Nada toca producción |

El detalle de tablas y nomenclatura está en `database/NOMENCLATURA.md`
(ADR-001). El contrato HTTP está en `packages/contracts/openapi.json` y
`docs/api/API-V1.md`.

## 2. Flujo de datos

```
App actual (OsmAnd :5055) ──┐
App nativa (MQTT/HTTP)  ────┼──> services/tracking ──> PostgreSQL dmt_*
Canal /api/mobile/v1/*  ────┘         (validación,        (position particionada,
                                       deduplicación,       current_position,
                                       eventos, salud)      telemetry)
                                                              │
                                                              v
Navegador ──> Nginx/TLS ──> apps/web (estático) ──> services/api (Node 24)
                                                       │  fachada /api/v1
                                                       │  (sesión, permisos,
                                                       │   DTOs en español)
                                                       └──> PostgreSQL (lectura)
```

Reglas del flujo:

1. La App reporta al servidor de tracking con el contrato congelado
   (`docs/api/COMPATIBILIDAD-APP.md`). Nunca habla con `services/api` de la Web.
2. `services/tracking` valida y persiste; mantiene `current_position` (una
   fila por dispositivo) para el panel en vivo y `position` particionada por
   tiempo para replay/reportes.
3. `services/api` no ejecuta `MAX(recorded_at)` sobre el histórico: lee
   `current_position` para el vivo y `position` por ventanas para
   replay/reportes.
4. La Web solo pide DTOs a `/api/v1` y renderiza. El estado de mapa y el
   estado de datos están separados; el sondeo es cada ~5 s con
   `AbortController`, control de solapamiento y backoff.
5. El replay se carga por ventana temporal y se dibuja como geometría
   (MapLibre), no como miles de nodos DOM; los eventos van en capas aparte.

## 3. Transición y adaptadores hacia el legado

Mientras convivan las dos instalaciones:

- **Adaptador de datos legado (solo lectura).** `services/api` y
  `services/tracking` leen el esquema `tc_*` de producción a través de vistas
  o de un repositorio de solo lectura, y traducen a los DTOs propios. Nada
  escribe en `/DMujeres-Tracking`.
- **Adaptador de protocolo.** El decodificador OsmAnd y el canal
  `/api/mobile/v1/*` se implementan contra el contrato congelado y persisten
  en las tablas `dmt_*`; la identidad (`uniqueId`, `X-Api-Key`, tokens FCM)
  se conserva desde el mapa de migración.
- **Mapa de migración.** `system.dmt_migracion_mapa` registra cada fila
  migrada (`tabla_origen`, `id_origen`, `id_destino`); los ids legados se
  conservan en `id_legado` (ver `database/NOMENCLATURA.md`).
- **Retiro gradual.** Cuando el tráfico esté íntegramente en la plataforma
  nueva, el legado queda como respaldo de solo lectura. El retiro es por
  fases (12 en el plan maestro), nunca en caliente.

## 4. Despliegue (objetivo)

```
Internet
   |
Cloudflare / TLS
   |
Nginx (HTTPS, único expuesto)
   +---------------------------+
   |                           |
   v                           v
apps/web (estático)      services/api (Node 24 LTS)
                               |
                               v
                     services/tracking (OsmAnd/MQTT)
                               |
                               v
                     PostgreSQL 18.6 (127.0.0.1)
```

Los puertos internos (`DMJ_WEB_PORT`, `DMJ_API_PORT`, `DMJ_TRACKING_PORT`,
`DMJ_DB_URL`) se enlazan a `127.0.0.1` o red privada y se configuran por
entorno con `packages/config` y `.env` (ver `.env.example`).
