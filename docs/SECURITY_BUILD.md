# SECURITY_BUILD.md — Provisioning de firma y secretos de build (R2)

## 1. Firma de releases (sin fallback silencioso)

Regla dura: **un release sin keystore provisionado FALLA**; nunca cae a la
firma debug en silencio.

```
mobile/keystore.properties presente  → BUILD (firma la clave configurada)
mobile/keystore.properties ausente   → BUILD FAIL (GradleException "R2: ...")
keystore configurado inexistente     → BUILD FAIL (GradleException "R2: ...")
MOBILE_HTTP_API_KEY ausente          → BUILD FAIL (GradleException "S1: ...")
```

Implementado en `mobile/app/build.gradle.kts` (`preReleaseBuild`) y verificado:
con `keystore.properties` renombrado, `:app:preReleaseBuild` falla con mensaje
claro; con el archivo presente, `:app:assembleRelease` firma correctamente.

### Clave del canal OTA (estado real)

La flota instalada (≤ 1.1.2) está firmada con la **keystore debug compartida**
(`mobile/keystore/debug.keystore`, versionada a propósito). Android rechaza
actualizar si cambia el certificado (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`,
mensaje de usuario "no se instaló la app debido a un conflicto con un paquete"),
así que el canal OTA **debe** seguir firmando con esa clave.

| Clave | Uso | Estado |
|---|---|---|
| `keystore/debug.keystore` (`androiddebugkey`) | Firma OTA activa de la flota | En uso (provisionada en `keystore.properties`) |
| `keystore/release.keystore` (`dmujeres`, RSA 4096) | Migración futura coordinada | Reservada; requiere desinstalar en cada equipo una vez (cola local drenada) |

`mobile/keystore.properties.example` documenta el provisioning. El archivo
real es 0600 y está gitignored (igual que `secrets.properties` y `.env`).

## 2. Provisioning

| Archivo | Contenido | Permisos |
|---|---|---|
| `mobile/secrets.properties` | `MOBILE_HTTP_API_KEY` (canal HTTP móvil S1) | 0600, gitignored |
| `mobile/keystore.properties` | `storeFile/storePassword/keyAlias/keyPassword` | 0600, gitignored |
| `mobile/app/google-services.json` | FCM (opcional; sin él la app degrada) | gitignored |
| `.env` | DB/MQTT/EMQX/dashboard/API keys del server | 0600, gitignored |

Ejemplos versionados: `mobile/secrets.properties.example`,
`mobile/keystore.properties.example`, `.env.example` (sin valores reales).

## 3. Incidente de fuga (2026-09-17) — ZIPs públicos

**Hallazgo:** tres ZIPs servidos por `web.path` (`:999`) contenían `.env`,
`mobile/secrets.properties`, `mobile/keystore.properties`,
`mobile/keystore/release.keystore` y `mobile/keystore/debug.keystore`:

- `DMujeres-Tracking-FULL.zip`, `DMujeres-Tracking-REFACTOR.zip`, `dmujeres.zip`.

**Acción inmediata (hecha):** eliminados de `dashboard/build` y
`dashboard/public` (404 verificado), copias de `/tmp` borradas. Empaquetado
seguro obligatorio: `infrastructure/scripts/pack-project.sh` (excluye secretos
y VERIFICA el contenido antes de publicar).

**Rotación pendiente (no ejecutada: requiere ventana y coordinación):**

| Secreto expuesto | Riesgo | Plan |
|---|---|---|
| `MOBILE_HTTP_API_KEY` (+ PREVIOUS) | API móvil | Rotar cuando la flota esté en ≥1.1.5 (ventana nueva/anterior). No rotar antes o la flota pre-1.1.5 pierde HTTP |
| `POSTGRES_PASSWORD` | DB | Rotar en ventana de mantenimiento (server + compose) |
| `MOBILE_MQTT_PASSWORD` (consumer) | broker | Rotar en `.env` + EMQX y reiniciar server |
| `EMQX_DASHBOARD_PASSWORD` | admin broker | Rotar en EMQX |
| `DASH_ADMIN_PASSWORD` | panel | Rotar en dashboard/DB |
| `WEB_SECRET_TOKEN` | sesiones | Rotar (invalida sesiones) |
| `release.keystore` | firma | **Comprometida**: regenerar ANTES de cualquier migración de firma de la flota |

## 4. Reglas permanentes

1. Nada de secretos en `git`, ZIPs ni APKs (el APK solo lleva la clave de flota
   `MOBILE_HTTP_API_KEY` por diseño: secreto compartido, ver `SECURITY.md`).
2. No imprimir valores sensibles en logs (FCM token solo prefijo hash).
3. `pack-project.sh` es la única vía de empaquetado para compartir.
