# 2026-10-01 — Inicio centrado en jornada y eventos; skills instaladas (panel 1.17.0)

- Skills instaladas en el proyecto: `.claude/skills/ui-ux-pro-max` (nextlevelbuilder/ui-ux-pro-max-skill; scripts solo leen CSV locales, sin red) y `.claude/skills/copywriting` (coreyhaines31/marketingskills). Se quitaron `tests/` y `evals/`.
- API `services/api/src/eventos.js`: `GET /api/v1/eventos?desde&hasta` → eventos de la app (jornada, GPS, batería crítica, red, encendido) + actividades subidas, con `conteo` por categoría.
- Inicio: arriba solo "En jornada ahora" (número + quién y desde cuándo, enlace a su recorrido) y "Eventos de hoy" (un contador con desglose: inicios, cierres, actividades subidas, alertas). Se quitaron horas, paradas registradas, "para revisar" como cifra y la barra de estados. "Últimas actividades" → "Línea de tiempo de hoy" con color por severidad, entrada animada y scroll.
