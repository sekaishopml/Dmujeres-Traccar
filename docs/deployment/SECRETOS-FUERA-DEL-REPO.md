# Secretos externos — qué va fuera del repo, dónde y quién lo custodia

Principio: ningún valor secreto vive en git, en docs ni en scripts. Solo
nombres y rutas. El `.env` está ignorado (`*.env` en `.gitignore`) y
`git ls-files` no trackea ningún `.env`, keystore ni `google-services.json`.

| Secreto / variable | Dónde vive (fuera de git) | Quién lo custodia |
|---|---|---|
| `/home/DMujeres-Tracking/.env` (600): `POSTGRES_USER/PASSWORD/DB`, `DMJ_DB_*`, `DMJ_CLAVE_MOVIL` + `_ANTERIOR`, `DMJ_CANAL_MOVIL`, `DMJ_TLS`, `DMJ_ENTORNO`, `DMJ_SESION_HORAS` | Solo disco del servidor + `EnvironmentFile` de systemd (leído por root); copia de respaldo 600 | Dueño / operador del servidor |
| `mobile/secrets.properties`: `MOBILE_HTTP_API_KEY` (se compila en `BuildConfig`) | Solo máquina de build Android, 600, ignorado por git | Responsable build Android |
| `mobile/keystore.properties`: `storePassword`, `keyAlias`, `keyPassword` + `mobile/keystore/*.keystore` (firma de flota) | Solo custodio de clave, por canal seguro (USB sellado o vault); NUNCA en el repo | Custodio clave de flota (dueño) |
| `google-services.json` (cliente Firebase, `fallback/app/` y `mobile/app/`) | Solo máquina de build + consola Firebase, ignorado por git | Responsable Firebase |
| Cuenta de servicio FCM (`GOOGLE_APPLICATION_CREDENTIALS` → `firebase-adminsdk.json`, p. ej. `/home/opencode/.config/dmujeres/secrets/`, 600) | Solo servidor + consola IAM de Firebase | Dueño proyecto Firebase |
| `DMJ_TEST_PASSWORD`, `DMJ_TEST_MOVIL_KEY` (obligatorias, sin default desde FASE 11), `DMJ_TEST_EMAIL`, `DMJ_TEST_ADMIN_*` | Solo entorno local del QA (`export` en shell o vault); jamás en código ni docs | QA / dueño |
| Legado (no tocar en plataforma nueva): `WEB_SECRET_TOKEN`, `ENCRYPTION_KEY`, `REDIS_PASSWORD`, `MQTT_*`, `EMQX_*`, `DASH_ADMIN_*`, `BING/GOOGLE/MAPTILER_KEY` | `/DMujeres-Tracking/.env` y respaldos 600 del legado | Dueño legado |

## Estado FASE 11

- Ya fuera: `.env`, `secrets.properties`, `keystore.*`, `google-services.json`,
  `firebase-adminsdk.json` (todos ignorados o fuera del árbol; verificado con
  `git ls-files` y `.gitignore`).
- Recién sacado: defaults de prueba en `scripts/validation/e2e.sh`
  (`DMJ_TEST_PASSWORD` y `DMJ_TEST_MOVIL_KEY` ahora obligatorias, abortan con
  error claro si faltan).
- Pendiente (fuera del alcance permitido en esta fase — NO tocar `services/`):
  `services/api/smoke.mjs` aún trae default de la clave de prueba
  (`DMJ_TEST_PASSWORD ?? ...`); debe pasar a obligatorio en el Sprint 1 del
  servidor, con el mismo patrón fail-fast del `e2e.sh`.
