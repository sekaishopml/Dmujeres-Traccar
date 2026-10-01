# CLAUDE.md — DMujeres Tracking

Plataforma de auditoría de jornadas de personas (no vehículos): app Android + servidor + panel web. Rama de trabajo: `plataforma`. Idioma del código, UI y docs: español.

## Dónde trabajar
- **Panel/dash (activo)**: `apps/panel` — React 19 + Vite 8 + TS estricto + Tailwind 4 + TanStack Query + React Router 7 + MapLibre + Chart.js. Guía obligatoria: `apps/panel/DISENO.md`.
- `apps/web`: panel anterior (reemplazado por `apps/panel`; solo referencia).
- API `/api/v1`: `services/api`. Tracking: `services/tracking`. Contratos: `packages/shared-types`.
- App Android: `fallback/`. Decisiones vinculantes: `docs/ADR/`.

## Al empezar
Lee `.claude/COMMON_MISTAKES.md`, `.claude/QUICK_START.md`, `.claude/ARCHITECTURE_MAP.md`. Todo lo demás en `docs/` se lee solo si la tarea lo pide (índice en `docs/AI-HANDOFF.md`).

## Reglas
- No reescribir la plataforma ni tocar `services/tracking/src/osmand.js`.
- En el panel no modificar `componentes/ui`, `componentes/marco`, `dominio`, `lib`, `estilos` sin avisarlo.
- Sin datos inventados: campo ausente = `GUION` ("—").
- Subir `version` de `apps/panel/package.json` en cada entrega.

Al terminar una tarea, deja nota en `.claude/completions/YYYY-MM-DD-tarea.md` (no se autocarga).
