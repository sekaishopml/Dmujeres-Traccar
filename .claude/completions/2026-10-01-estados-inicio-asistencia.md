# 2026-10-01 — Estados por contacto, Inicio útil, Asistencia y GMaps (panel 1.16.0)

- API `flota.js`: estado por contacto (GREATEST(ultima_conexion_en, lastDiagnosticsAt)): EN_LINEA (<3 min con velocidad), DETENIDO (responde <15 min), SENAL_DEBIL (15–60 min), SIN_SENAL (>60 min). `dto.js`: `ultimaConexion` = contacto. 28/28 pruebas. Documentado en la auditoría §7.
- Inicio: sin km; "Horas en jornada hoy", "Paradas hoy"; listas con scroll de marca y entrada animada (`componentes/inicio/inicio.css`); esqueletos de carga; leyenda de estados con definición.
- Historial → **Asistencia** (planilla persona × día con entrada–salida, horas, sin cerrar >16 h, totales y entrada media; celda abre la repetición de ruta). **Se tocó `componentes/marco/navegacion.ts`** (texto y descripción). `componentes/historial/BarraDia.tsx` quedó sin uso.
- `componentes/mapa/MapaBase.tsx`: capa "Mapa" → "GMaps", Replay abre en GMaps, píldora deslizante en el selector de capas.
