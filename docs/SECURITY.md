# SECURITY.md — Seguridad (FASE 10)

## 1. Estado de los bloqueantes S1/S2/S3

| ID | Riesgo | Estado | Evidencia |
|---|---|---|---|
| S1 | static X-Api-Key fallback | **RESUELTO (rotación con ventana)** | clave `dmj-dev-fallback-key` eliminada del código; clave de flota inyectada en build desde `mobile/secrets.properties` (0600, gitignored); el server acepta actual+anterior (`MOBILE_HTTP_API_KEY_PREVIOUS`) hasta migrar la flota; `.env` rotada hoy |
| S2 | HTTP cleartext | **RESUELTO PARCIAL** | `network_security_config.xml`: base `cleartextTrafficPermitted=false`; excepción SOLO a hosts conocidos (`68.168.20.219`, nodo Tailscale, loopback, emulador). TLS del despliegue sigue pendiente (infra) |
| S3 | debug signing | **RESUELTO (no activo en OTA)** | keystore de release generado (RSA 4096, 10 000 días) + `keystore.properties` (0600, gitignored) y respaldo obligatorio; el canal OTA de la flota sigue firmado con la keystore debug compartida: cambiar el certificado rompe la actualización (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`); migración coordinada en `docs/PRODUCTION_RUNBOOK.md` §4 |

## 2. Procedimiento de rotación de la clave móvil (S1)

1. Generar nueva: `openssl rand -hex 32`.
2. En `.env` del server: `MOBILE_HTTP_API_KEY=<nueva>` y
   `MOBILE_HTTP_API_KEY_PREVIOUS=<actual>`.
3. Reiniciar server (`run-server-dev.sh restart`); verificar 200 con clave
   nueva y con la anterior (ventana), y 401 con una clave ajena.
4. Generar release con `mobile/secrets.properties` conteniendo la nueva clave.
5. Distribuir la app; cuando toda la flota reporte con la nueva (ver
   `mobile.http.apiKeyPrevious` en logs/atributos), **vaciar
   `MOBILE_HTTP_API_KEY_PREVIOUS`** y reiniciar.

## 3. Manejo de secretos

- `~/.config/dmujeres/secrets/firebase-adminsdk.json` (0600, fuera del repo):
  solo lo lee el server vía `GOOGLE_APPLICATION_CREDENTIALS`.
- `mobile/secrets.properties` y `mobile/keystore.properties`:
  0600 + gitignored (`!mobile/keystore/debug.keystore` se conserva para el
  keystore de desarrollo compartido del equipo).
- Nunca se imprimen tokens completos/keys/passwords. El token FCM se
  diagnostica por prefijo hash.
- **Backup obligatorio del keystore de release** fuera de esta máquina: si se
  pierde, no habrá más actualizaciones instalables para la flota.

## 4. Verificaciones realizadas

- APK release NO contiene el fallback dev (`strings classes.dex` → 0).
- La clave de flota SÍ vive en el APK (secreto compartido): riesgo residual
  aceptado y documentado; siguiente paso recomendado: token por dispositivo.
- Lint release sin errores.
- Server: `MobileApiKeyValidator` compara longitudes y contenido en tiempo
  constante y falla cerrado sin clave configurada.

## 5. Pendientes de producción

- TLS para web/API y MQTT (certificados; `EMQX_CERTS_DIR` preparado).
- Rotar cualquier service-account key usada en pruebas si estuvo expuesta.
- Revisar permisos de `tc_*` y usuario de BD (mínimo privilegio) al desplegar.
- `dataAtRest`: evaluar cifrado de la BD de producción.
