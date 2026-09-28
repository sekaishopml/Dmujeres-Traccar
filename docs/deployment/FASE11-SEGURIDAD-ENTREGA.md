# FASE 11 — Gate de seguridad para entrega (sin activar nada)

Alcance: checklist accionable antes de salir a producción. No cambia
configuración ni habilita TLS: solo define el criterio y cómo verificarlo.
Origen TLS actual: `infrastructure/production/nginx/dmj-tracking-tls.example`
(sin habilitar). Estado actual: HTTP plano, `DMJ_TLS=0`, cookie sin `Secure`.

## Gate: no entregar a producción sin los 5 en PASS

| # | Criterio | Verificación (solo lectura) |
|---|---|---|
| 1 | TLS: `https://<dominio>` 200 con cert válido, `:80` redirige a 301 salvo `/.well-known/acme-challenge/` | `curl -sSI https://<dominio>/ \| head -n 5` · `curl -sSI http://<dominio>/ \| grep -i '^location:'` · `echo \| openssl s_client -connect <dominio>:443 -servername <dominio> 2>/dev/null \| openssl x509 -noout -dates -issuer` |
| 2 | HSTS en el server `:443` | `curl -skSI https://<dominio>/ \| grep -i '^strict-transport-security:'` (esperado `max-age>=15552000; includeSubDomains`) |
| 3 | CSP básica + cabeceras duras en Nginx (sin `unsafe-inline` en producción) | `curl -skSI https://<dominio>/ \| grep -iE '^(content-security-policy\|x-content-type-options\|x-frame-options\|referrer-policy):'` · `sudo nginx -T \| grep -E 'add_header|content_security|default-src'` |
| 4 | Cookies `dmj_sesion`: `HttpOnly; SameSite=Lax; Path=/; Max-Age` y `Secure` solo con TLS (`DMJ_TLS=1` en `services/api/src/sesiones.js:40-55`, `entorno.js:71`) | `curl -skD - -o /dev/null -H 'Content-Type: application/json' --data-binary '{"usuario":"<u>","clave":"<p>"}' https://<dominio>/api/v1/auth/login \| grep -i '^set-cookie:'` (debe traer `HttpOnly`, `SameSite=Lax` y `Secure`) |
| 5 | Límites y rate-limit en Nginx (`client_max_body_size`, `limit_req_zone`/`limit_req` en login y canal móvil) | `sudo nginx -T \| grep -E 'client_max_body_size|limit_req_zone|limit_req|limit_conn'` · `curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1/api/v1/health` (el 200 no debe cambiar; el abuso debe dar 429/503 tras el límite) |

## Notas

- Hoy solo existe `X-Content-Type-Options: nosniff` en la API
  (`services/api/src/http.js:15`, `errores.js:50`) y `client_max_body_size 10m`
  en Nginx. No hay CSP, HSTS ni `limit_req`: por eso son gate, no estado.
- `Secure` sin HTTPS rompe el login del navegador; solo se pone `DMJ_TLS=1`
  cuando el `:443` ya responde (ver `docs/api/FASE4-API.md` §4.3).
- Comandos de referencia para habilitar (NO ejecutar en esta fase):
  `sudo apt-get install -y certbot python3-certbot-nginx`,
  `sudo certbot --nginx -d tracking.<dominio-real>`,
  `sudo ufw allow 443/tcp`, `sudo nginx -t && sudo systemctl reload nginx`.
