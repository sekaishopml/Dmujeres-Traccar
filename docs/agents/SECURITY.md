# DMujeres Tracking — SEGURIDAD (FASE 1, estado actual)

Fecha: 2026-09-25. Autor: agente INFRA/SEGURIDAD.
Este documento describe superficies y manejo de secretos por NOMBRE y RUTA.
No contiene valores de secretos. Produccion no fue modificada.

Antecedente: `docs/security/INCIDENT_secretos_zip.md` (repositorio legado
`/DMujeres-Tracking`) documenta la exposicion de zips con `.env` y keystores
(fecha 2026-09-20) y deja pendientes varias rotaciones.

## 1. Superficies de ataque actuales

| Puerto | Servicio | Binding | Internet | Riesgo |
|---|---|---|---|---|
| 999 | Traccar Web/API (Jetty 12.1.8) | 0.0.0.0 | Si, HTTP plano | Critico: login del panel, API, artefactos publicos |
| 5055 | Protocolo OsmAnd (App) | 0.0.0.0 | Si, HTTP plano | Critico: ingesta GPS con API key en URL |
| 1883 | EMQX MQTT | 0.0.0.0 (docker) | Si | Alto: broker sin TLS en el listener publicado |
| 4096 | opencode (agente IA) | 0.0.0.0 | Si | Alto: herramienta de administracion expuesta |
| 5088-5103 | Protocolos Traccar | 0.0.0.0 | No (ufw default deny) | Medio: listeners innecesarios, dependen del firewall |
| 8083/18083 | EMQX WS/dashboard | 127.0.0.1 | No | Bajo |
| 5433 | PostgreSQL/TimescaleDB | 127.0.0.1 | No | Bajo |
| 6379 | Redis | 127.0.0.1 | No | Bajo |
| 8000-8002 | CyHotel | 0.0.0.0 | Si | Fuera de alcance DMujeres, mismo host |

Hechos verificados:
- No hay Nginx ni nada en 80/443; no hay Let's Encrypt ni cloudflared. La web y
  la API se consumen por IP y HTTP plano: `dashboard/build/latest.json` publica
  `http://68.168.20.219:999/...`.
- El servidor Traccar corre con `web.console=true`, `web.address=0.0.0.0` y
  `web.sessionTimeout=604800` (7 dias).
- `dmj-traccar` necesita `CAP_NET_BIND_SERVICE` (drop-in
  `10-net-bind.conf`) solo porque 999 es puerto privilegiado; no corre como root.
- El directorio servido por Jetty (`web.path=../dashboard/build`) es publico y
  sin autenticacion: hoy contiene 98 APKs descargables y el paquete
  `DMujeres-Tracking-20260926-completo.zip` (24 MB, HTTP 200). Se reviso su
  contenido: solo incluye ficheros `*.example` (sin `.env`, sin keystores, sin
  `google-services.json`), pero expone codigo fuente y documentacion.
- La sesion del panel se capturo en un sondeo previo:
  `JSESSIONID` con `HttpOnly`, `Path=/`, `Max-Age=604800`; sin `Secure` y sin
  `SameSite`.

## 2. Manejo de secretos (donde viven y quien los lee)

| Ubicacion | Permisos | Contenido (nombres) | Quien lo lee |
|---|---|---|---|
| `/DMujeres-Tracking/.env` | 600 opencode | `POSTGRES_PASSWORD`, `MOBILE_HTTP_API_KEY`, `MOBILE_HTTP_API_KEY_PREVIOUS`, `WEB_SECRET_TOKEN`, `REDIS_PASSWORD`, `MQTT_BROKER_*`, `MOBILE_MQTT_*`, `EMQX_DASHBOARD_PASSWORD`, `DASH_ADMIN_PASSWORD`, `ENCRYPTION_KEY`, `GOOGLE_APPLICATION_CREDENTIALS`, `BING_MAPS_KEY`, `GOOGLE_MAPS_KEY`, `MAPTILER_KEY` | `traccar-systemd.sh` (usuario opencode) y scripts/compose |
| `/DMujeres-Tracking/dashboard/.env` | 644 | Solo `VITE_APP_VERSION` (no es secreto) | build del dashboard |
| `/DMujeres-Tracking/server/conf/traccar-dev.xml` | 600 opencode | Sin password embebido; usa `config.useEnvironmentVariables=true` y hereda `DATABASE_PASSWORD` | `dmj-traccar.service` |
| `/DMujeres-Tracking/mobile/keystore.properties` | 600 | `storeFile`, `storePassword`, `keyAlias`, `keyPassword` | build Android |
| `/DMujeres-Tracking/mobile/secrets.properties` | 600 | `MOBILE_HTTP_API_KEY` | build Android (se compila dentro del APK) |
| `/DMujeres-Tracking/mobile/keystore/*.keystore` | 600/644 | `debug.keystore` (en uso, material publico de Android), `release.keystore` (sin uso) | firma de APKs |
| `/home/opencode/.config/dmujeres/secrets/firebase-adminsdk.json` | 600 opencode | Cuenta de servicio FCM | envio de notificaciones del server |
| `/DMujeres-Tracking/mobile/app/google-services.json` | 644 | Configuracion Firebase del cliente | build Android |

- Contenedores: los valores de entorno de `dmj-db`, `dmj-redis` y `dmj-mqtt`
  salen del `.env`/compose. En la evidencia estan redactados (`CLAVE=***`).
- Git: `.gitignore` cubre `.env`, `.env.*`, `google-services.json`,
  `firebase-adminsdk*.json` y `*service*account*.json`. No hay commits de
  `.env`, `traccar-dev.xml` ni `google-services.json` en el historial del
  repositorio legado. El `debug.keystore` si estuvo trackeado historicamente
  (incidente 2026-09-20), por lo que su material sigue en el historial de git.
- Nunca se copiaron secretos dentro de `/home/DMujeres-Tracking`; las copias de
  esta fase viven en `/home/DMujeres-backups/migration/20260925-205714/config/`
  con permisos 600 y hashes SHA-256.

## 3. Cookies: web actual vs requisito de la web nueva

| Atributo | Web actual (Traccar/Jetty) | Requisito web nueva |
|---|---|---|
| `HttpOnly` | Si (`WebServer.java` lo fuerza) | Si |
| `Secure` | No (solo se activa con `web.sameSiteCookie=none`, que no esta configurado, y no hay HTTPS) | Si, obligatorio |
| `SameSite` | No (clave `web.sameSiteCookie` sin definir en `traccar-dev.xml`) | `Lax` por defecto |
| Vigencia | 604800 s (7 dias) | Reducir (p. ej. 8-12 h con renovacion) |
| Renovacion en login | No rota el identificador de sesion | Regenerar sesion al autenticar |

Logout del panel disponible por API; no se observo proteccion CSRF adicional en
los endpoints mutadores. La web nueva debe fijar `Secure` + `HttpOnly` +
`SameSite=Lax`, rotacion de sesion y una vigencia menor.

## 4. Credenciales moviles y FCM

- API keys moviles (solo nombres): `MOBILE_HTTP_API_KEY` (activa) y
  `MOBILE_HTTP_API_KEY_PREVIOUS` (transicion). La activa viaja compilada dentro
  de cada APK; hoy hay 98 APKs descargables sin autenticacion, por lo que la
  clave debe considerarse publica y rotar con ventana `PREVIOUS`.
- Credenciales MQTT de la app (solo nombres): `MOBILE_MQTT_USERNAME`,
  `MOBILE_MQTT_PASSWORD`, `MQTT_BROKER_USER`, `MQTT_BROKER_PASSWORD`.
- FCM: tabla `tc_fcm_tokens`, 9 registros (2 con `previous_token`). Prefijos
  (solo primeros 8 caracteres, no son valores utilizables):
  `dCK_XFXm...`, `di0LWLTS...`, `doITS6Hy...`, `eEaDDCZ2...`, `eMnQUngH...`,
  `eencKZtW...`, `f4IYGUcC...`, `f7pvwzHM...`, `fXc-KLPU...`.
  Credenciales de envio: cuenta de servicio en
  `GOOGLE_APPLICATION_CREDENTIALS` (ruta en la tabla de la seccion 2) +
  `google-services.json` en el cliente.

## 5. Recomendaciones ordenadas por riesgo

1. Despublicar de `dashboard/build` el zip del proyecto y los APKs antiguos
   (o moverlos a una ruta autenticada). Es la misma clase de incidente del
   2026-09-20 y hoy hay 98 APKs + 1 zip con HTTP 200.
2. Rotar `MOBILE_HTTP_API_KEY` (clave en APKs publicos) con transicion
   `MOBILE_HTTP_API_KEY_PREVIOUS` y publicar app nueva; en paralelo, ejecutar la
   rotacion del keystore de firma con linaje v3 aprobada en el incidente.
3. Cerrar a Internet 999 y 5055: denegar en ufw, enlazar a 127.0.0.1 y publicar
   solo a traves de Nginx con TLS. Deshabilitar `web.console`.
4. Cookies: activar `web.sameSiteCookie=lax` (o `strict`), servir solo HTTPS
   para obtener `Secure` y bajar `web.sessionTimeout`.
5. Rotar secretos pendientes del incidente: `WEB_SECRET_TOKEN`,
   `REDIS_PASSWORD`, `DASH_ADMIN_PASSWORD`, `EMQX_DASHBOARD_PASSWORD` y la
   cuenta de servicio FCM; documentar cada rotacion y su verificacion.
6. MQTT: retirar la publicacion `0.0.0.0:1883` y usar 8883/TLS o red privada;
   revisar que la regla ufw de 1883 no sea necesaria.
7. Cerrar el puerto 4096 (opencode) y restringir 8000-8002 si no son publicos.
8. Migrar secretos a `EnvironmentFile` 600 fuera del repositorio (o systemd
   credentials) y dejar de hacer `source .env` en scripts.
9. Habilitar access log (Nginx o Jetty) para tener forense; hoy no hay registro
   de peticiones HTTP en 999.
10. Revisar 5088-5103: limitar los protocolos Traccar a los realmente usados y
    enlazarlos a 127.0.0.1.

## 6. Dudas

- Confirmar si el APK vigente (2.1.73) sigue admitiendo la clave rotada via
  `PREVIOUS` y cuantos equipos quedarian fuera de la ventana de transicion.
- Confirmar si `ENCRYPTION_KEY` cifra datos en reposo antes de planear su
  rotacion (el incidente la deja en espera de auditoria).
- Confirmar si 5055 debe seguir abierto para clientes OsmAnd de respaldo o
  puede cerrarse al migrar la App.
