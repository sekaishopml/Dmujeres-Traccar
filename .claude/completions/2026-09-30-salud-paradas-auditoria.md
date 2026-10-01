# 2026-09-30 — Historial de salud, estancias bajo techo y auditoría de campo

- Auditoría documentada en `docs/audit/2026-09-30-auditoria-campo-sanchez-manzaba-fernando.md` (Sanchez Pilay/Pilay, Manzaba, Fernando; metro de Quito; mejoras pendientes).
- Historial de salud: `services/tracking/src/salud.js` (fila por diagnóstico, cada 10 min) + `db.registrarDiagnostico` inserta en `telemetry.dmt_salud_dispositivo`; prueba `services/tracking/test/salud.test.mjs`. API `GET /api/v1/salud/historial?horas=`; panel `componentes/sistema/HistorialSalud.tsx` en Sistema (1.11.0).
- Paradas (`services/api/src/paradas.js`): fusión de estancia (pausa <=15 min, centros <=100 m, sin salida sostenida >200 m por 2 min). Manzaba 30/09: 5 paradas del edificio → 1 (10:09–20:41).
- Ruteo (`ruteo.js`): `esVentanaAPie` (<8 km/h) no va a /match (zigzag entre calzadas).
- Panel 1.12.0: sin líneas/flechas dentro de paradas; el punto elegido va al centro de su parada o al punto de la línea más cercano a su GPS (`puntoCercanoEnLineas`).
- Pilay y Sanchez Pilay quedan separados (decisión del usuario).
