# Seguridad — estado Fase 0 y reglas

## Estado actual (dev)

- Todos los puertos de infraestructura bindean `127.0.0.1` (solo alcanzables en el host):
  `5433` (PostgreSQL/TimescaleDB), `6379` (Redis), `1883`/`8083`/`18083` (EMQX).
- EMQX permite anónimo SOLO en dev y SOLO porque no es alcanzable fuera del host.
  En Fase 2 se añade autenticación de clientes antes de abrir a la red.
- El dashboard EMQX (`18083`) usa `public` como password por defecto en dev — nunca
  exponer este puerto en producción (restricción por firewall al IP de admin).
- `server/conf/` está en `.gitignore` del fork: no se versionan configs con secretos.
- Secretos viven únicamente en `.env` (gitignored) y en `restore/env` del backup (600).
- El server expone `web.console=true` en dev (consola de debug) — deshabilitar en prod.

## Pendiente para Fases 1 y 5 (no bloqueante en Fase 0)

- [ ] CORS del server (`web.cors*`) — revisar restricciones
- [ ] TLS para MQTT (8883) y WebSocket (WSS) — vía dominios estables y certificados
- [ ] `web.console=false` en producción
- [ ] Sesiones: rotación del token de firma (`WEB_SECRET_TOKEN`), 2FA en usuarios
- [ ] Rate limiting en API (Fase 5)
- [ ] Auditoría de dependencias: `npm audit`, `gradle dependencies` (SBOM) en CI
- [ ] RBAC + aislamiento multi-tenant con tests de seguridad (requisito del prompt)

## Reglas permanentes

1. Nunca hardcodear API keys, passwords o tokens.
2. Nunca versionar secretos (`.env*` ignorado; `conf/` ignorado en el fork).
3. No exponer puertos de infraestructura fuera del host sin autenticación.
4. Backups de BD con permisos 600.
5. Antes de producción: auditoría de seguridad completa (Fase 5).