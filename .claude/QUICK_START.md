# Quick Start

## Panel (`apps/panel`)
```bash
cd apps/panel
npm run dev          # Vite en 127.0.0.1:5174, proxya /api -> 127.0.0.1:8081
npm run typecheck    # tsc -b --noEmit
npm run build        # tsc -b && vite build -> dist/ (lo sirve services/web)
```
Compilar como usuario opencode (Node 24):
`su opencode -c "export PATH=/opt/node24/bin:\$PATH; cd /home/DMujeres-Tracking/apps/panel && npx tsc -b --noEmit"`

## API
`services/api` (`npm test` / `smoke.mjs`). E2E: `scripts/validation/e2e.sh`.
