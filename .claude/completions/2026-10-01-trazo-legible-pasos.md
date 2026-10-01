# 2026-10-01 — Trazo legible y paso a paso real (panel 1.13.0)

- Núcleo de parada (3+ lecturas seguidas a >30 m del centro = llegada/salida, conservan coordenada): `services/api/src/paradas.js` (RADIO_NUCLEO_M, MIN_FIXES_BORDE) y `apps/panel/src/dominio/depuracion.ts` (recortarNucleo, antes del recorte por velocidad). Además `sinRepetidos` (un fix por segundo). **Se tocó `dominio`.**
- `componentes/replay/trazo.ts`: color por hora (`COLOR_POR_HORA`, propiedad `f` en trazo y flechas), `suavizarCaminata` (solo dibujo), `recortarEnParadas` (25 m).
- Replay.tsx: caminata con borde y más gruesa; flechas = disco de color (`replay-flechas-disco`) + punta blanca; leyenda `.replay-leyenda-hora`.
- Verificado con jiti sobre Manzaba 30/09: "siguiente punto" avanza 1–10 m en la caminata de 20:41 (antes 60 m).
- Documentado en `docs/audit/2026-09-30-auditoria-campo-sanchez-manzaba-fernando.md` §5.
