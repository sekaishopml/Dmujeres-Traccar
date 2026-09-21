# INCIDENTE: secretos en zips públicos (20-sep-2026)

## 1. Resumen

Se publicaron dos zips descargables en el servidor web (puerto 999, sin
autenticación) que contenían `.env` y keystores:

| Archivo público | SHA-256 | Contenido sensible |
|---|---|---|
| `dmj-proyecto-completo-bfc55ab6.zip` | `ae986e98ddd1f28a8e46891edf9c0549bfc02962ee5f1af22140babd59bf7e24` | `.env`, `mobile/keystore/debug.keystore`, `mobile/keystore/release.keystore`, `mobile/keystore.properties` |
| `dmj-datos-joseph-kevin-821416c8.zip` | `0c8e4c65fda306b7fede65ce9caddaa5d372fff2fb3f0b1535f432fd44645ba7` | (no llevaba secretos; se borró por higiene) |

**Acciones tomadas (Paso 1.1):**
- Ambos archivos **borrados** del directorio público; verificado `HTTP 404`.
- Antes de borrar se archivó, **fuera del directorio público**, el listado
  completo de contenidos y los hashes en
  `/var/log/dmj/incident-2026-09-20/` (permisos 700).

**Tercer archivo preexistente**: `dashboard/build/DMujeres-Tracking-src.zip`
(78 MB, creado el 18-sep antes de este trabajo) sigue público. Se auditó su
contenido: **no contiene secretos reales** (solo `keystore.properties.example`,
`.env.example` y un directorio `mobile/keystore/` vacío). Se deja a decisión del
dueño retirarlo por higiene (exponer código fuente, no credenciales).

## 2. Forense de descargas — LIMITADO (sin access log)

`BLOCKED — SIN DATOS`: el servidor Jetty no tiene *request log* habilitado; los
logs de aplicación (`tracker-server.log*`) no registran peticiones HTTP del
puerto 999. **No es posible cuantificar descargas, IPs ni fechas** para esos
enlaces.

Recomendación (pendiente de aprobación): habilitar un access log de Jetty
(`--module=http` + `NCSARequestLog`) o un reverse proxy con log, para tener
forense la próxima vez. No se modificó nada en producción.

## 3. Secretos afectados (por NOMBRE de variable, nunca por valor)

`.env` incluye 43 variables. Agrupadas por impacto:

| Grupo | Variables (nombres) | Dónde se usa | Rotación | Qué se rompe al rotar |
|---|---|---|---|---|
| **Base de datos** | `POSTGRES_PASSWORD`, `POSTGRES_USER`, `POSTGRES_DB`, `DATABASE_URL`, `DATABASE_DRIVER`, `CONFIG_DATABASE_MAXPOOLSIZE` | Contenedor `dmj-db`, server Traccar | `ALTER USER … PASSWORD` + actualizar `.env` + reiniciar server | Ingesta y panel caen hasta reiniciar; **los teléfonos no** (no conocen la BD) |
| **API móvil (llave compartida)** | `MOBILE_HTTP_API_KEY`, `MOBILE_HTTP_API_KEY_PREVIOUS` | App Android (compilada en `AppConfig.HTTP_API_KEY`), endpoints `/api/mobile/v1/*` | Rotar a nuevo valor, mantener el anterior en `PREVIOUS` durante la transición, publicar app con la nueva llave, retirar la vieja | Si se rota sin app nueva: **todos los teléfonos dejan de subir** (401) hasta actualizar. Requiere ventana coordinada |
| **MQTT** | `MQTT_BROKER_USER`, `MQTT_BROKER_PASSWORD`, `MOBILE_MQTT_USERNAME`, `MOBILE_MQTT_PASSWORD`, `MOBILE_MQTT_URL`, `MOBILE_MQTT_*` (topics/lease/queue) | App + consumer del server en EMQX | Regenerar `auth-file.csv`/usuarios EMQX + `.env` + reiniciar server | Presencia/telemetría MQTT; la app reintenta (no pierde posiciones: outbox) |
| **EMQX dashboard/API** | `EMQX_DASHBOARD_PASSWORD`, `EMQX_API_URL`, `EMQX_CERTFILE`, `EMQX_KEYFILE`, `EMQX_CERTS_DIR` | Administración del broker | Cambiar en panel EMQX + `.env` | Solo administración del broker |
| **Redis** | `REDIS_PASSWORD`, `CONFIG_REDIS_URL` | Caché/sesiones del server | `CONFIG SET requirepass` + `.env` + reinicio | Sesiones del panel se invalidan (los usuarios re-loguean) |
| **Sesiones del panel** | `WEB_SECRET_TOKEN` | Firma de sesiones | Generar nuevo token + reinicio | **Todas las sesiones del panel se invalidan** (re-login) |
| **Cuenta admin del panel** | `DASH_ADMIN_EMAIL`, `DASH_ADMIN_PASSWORD`, `DASH_URL`, `DASHBOARD_URL` | Semilla de usuario admin del dashboard | Cambiar contraseña del usuario en el panel | Nada más (el usuario sigue existiendo) |
| **Cifrado app** | `ENCRYPTION_KEY` | (Revisar uso en server) | Si cifra datos en reposo, rotar exige re-cifrado | Depende del uso real — **verificar antes** |
| **Google/Firebase** | `GOOGLE_APPLICATION_CREDENTIALS` (ruta a JSON de cuenta de servicio) | Envío de FCM del server | Rotar clave de la cuenta de servicio en Google Cloud + reemplazar JSON + reiniciar | Despertador FCM deja de enviar hasta reemplazar |
| **Mapas (cuotas)** | `BING_MAPS_KEY`, `GOOGLE_MAPS_KEY`, `MAPTILER_KEY` | Dashboard (mapas) | Rotar en cada proveedor + actualizar `.env` | Mapas dejan de cargar con la clave vieja |
| **Infra** | `BACKUP_DIR`, `BACKUP_RETENTION_DAYS`, `COMPOSE_FILE`, `CONFIG_WEB_EXTERNALADDRESS`, `SERVER_ADDRESS`, `SERVER_WEB_PORT`, `MOBILE_HTTP_ENABLE`, `MOBILE_MQTT_ENABLE` | Scripts/infra | No son secretos (config) | — |

## 4. Keystore de firma — hallazgo CRÍTICO

**La firma activa de la flota es `mobile/keystore/debug.keystore`**
(`keystore.properties`: `storeFile=../keystore/debug.keystore`), con:

- Alias `androiddebugkey`, sujeto `C=US, O=Android, CN=Android Debug`,
  SHA-256 `86:7B:A1:29:…:22` — es el **keystore de depuración estándar del
  SDK de Android**, cuyo material de clave es **público y conocido**.
- El archivo **está trackeado en git** (`git ls-files` lo lista) y aparece en el
  historial (commit `91ba10b`).
- `release.keystore` existe localmente pero **no está trackeado ni en uso**.
- `keystore.properties` sí está ignorado (`mobile/.gitignore:4`) ✓.

**Riesgo real:** cualquier persona con el keystore de depuración estándar puede
firmar un APK que el sistema acepte como **actualización legítima** en los
equipos de la flota (misma firma). No requiere acceso a nuestros zips.

**Evaluación de rotación con APK Signature Scheme v3 (proof-of-rotation):**

| Punto | Hallazgo |
|---|---|
| Versiones de la flota | santiago A14 · joseph A15 · kevin A14 · miguel **A16** · david A15 · macias A14 → **todas ≥ Android 9**: soportan v3 |
| `minSdk` del proyecto | 26 (Android 8) — un equipo 8.x hipotético **no** soportaría la rotación v3 y quedaría atado a la clave vieja |
| Mecanismo | `apksigner rotate --out lineage.bin --old-signer <debug> --new-signer <release>` y firmar con `--lineage`; los equipos Android 9+ aceptan la actualización sin desinstalar |
| Si no se rota | la flota seguirá aceptando APKs firmados con la clave de depuración pública |

**Decisión recomendada (requiere tu OK):** rotar a una clave de release propia
con linaje v3, probando primero en un equipo QA la actualización 1.1.21 → 1.1.22
firmada con el linaje. No ejecutar sin aprobación.

## 5. Orden de rotación recomendado

1. **Keystore de firma** (mayor impacto, requiere ventana con app nueva).
2. **`WEB_SECRET_TOKEN`** (invalida sesiones del panel; hacer en horario bajo).
3. **`REDIS_PASSWORD`** (idem).
4. **`MOBILE_HTTP_API_KEY`** (ventana coordinada: `PREVIOUS` + app nueva).
5. **MQTT** (app reintenta; sin pérdida por outbox).
6. **Cuenta de servicio de Google/Firebase** (FCM se pausa minutos).
7. **Claves de mapas** (solo dashboard).
8. **`DASH_ADMIN_PASSWORD`** (cambio de contraseña normal).
9. **`ENCRYPTION_KEY`** — **solo tras verificar qué cifra**; no tocar a ciegas.

**Verificación posterior (cada rotación):** dispositivos reportando (ingesta y
presencia), sesiones del panel reconocidas, respaldos del día OK, y ausencia de
usuarios/dispositivos desconocidos en `tc_users` / `tc_devices` / logs.

## 6. Checklist pendiente de tu OK (por secreto)

- [ ] Keystore: generar clave propia + linaje v3 (probado en QA)
- [ ] `WEB_SECRET_TOKEN`
- [ ] `REDIS_PASSWORD`
- [ ] `MOBILE_HTTP_API_KEY` (+ `PREVIOUS` y release de app)
- [ ] MQTT (broker + consumer)
- [ ] Cuenta de servicio Google (FCM)
- [ ] Claves de mapas
- [ ] `DASH_ADMIN_PASSWORD`
- [ ] `ENCRYPTION_KEY` (previo: auditar uso)
- [ ] Habilitar access log del servidor web
- [ ] Retirar `DMujeres-Tracking-src.zip` del directorio público
