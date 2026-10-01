# 2026-10-01 — Inicio para 50, Asistencia semana/mes, Configuración vigente, Grupos y Usuarios (panel 1.18.1)

- `dominio/datos.ts`: usuarios y grupos con `tamano=200` (antes 25 por página). **Se tocó `dominio`.**
- Grupos: una persona en un solo grupo y sin dados de baja (API `grupos.js` 409 y diálogo filtra); `cuentas.js` rechaza >1 grupo.
- Usuarios: grupo con selector único; corregido el valor inicial (ids numéricos vs públicos → salía "Sin grupo" y al guardar lo sacaba); ajustes muestran lo que rige y si lo fija el equipo.
- Tracking `/api/mobile/v1/config`: orden equipo → persona (configApp de la asignación activa) → defecto. Antes se ignoraba la persona.
- API esquema: `porDefecto` por clave; contrato `EntradaEsquemaAjustes.porDefecto` y tipo `entero`.
- Configuración: lista con resumen y "Personalizado", panel de configuración vigente con origen (equipo/persona/sistema).
- Asistencia: semana/mes con navegación; jornadas repartidas por día (en curso y de días anteriores visibles).
- Inicio: filtros, búsqueda y tabla con scroll; "En jornada" limitado a 8 + "ver en la tabla".
