# SECURITY_AUDIT.md — Auditoría física de secretos y superficie de ataque (R3)

Fecha: 2026-09-17 · Alcance: árbol completo del monorepo + submódulos (`server/`, `dashboard/`),
historial git (los 3 repos), APK release local, endpoints móviles del server, red.
Método: solo lectura; ningún valor de secreto se reproduce en este documento.
Extiende (no repite) `docs/SECURITY_BUILD.md` (R2) y `docs/SECURITY.md`.

---

## 1. Hallazgos — búsqueda física de secretos en el árbol

| # | Item | Estado | Severidad | Evidencia (sin valores) |
|---|------|--------|-----------|------------------------|
| F1 | `mobile/keystore/release.keystore` en el árbol | PRESENTE, gitignored (check-ignore OK), 0600, 4380 B | **CRÍTICA** | Comprometida en ZIPs públicos hasta el 17/09 (404 verificado por R2). Regenerar antes de migrar firma de flota. Huella: `keystore/release.keystore` |
| F2 | `mobile/keystore/debug.keystore` versionada EN GIT | INTENCIONAL | **ALTA (aceptada, documentada)** | Es la firma OTA activa de la flota ≤1.1.2 (`docs/SECURITY_BUILD.md` §1). Al estar en git público, cualquiera puede firmar APKs que la flota acepte como actualización. Riesgo residual hasta migrar a keystore nueva |
| F3 | `mobile/keystore.properties` / `mobile/secrets.properties` | PRESENTES, gitignored, 0600 | OK (higiénico) | `mobile/.gitignore:4-6`. Contienen store/key passwords y `MOBILE_HTTP_API_KEY` |
| F4 | `mobile/app/google-services.json` | PRESENTE, gitignored (`.gitignore:5`), 0600, 685 B | BAJA | Solo contiene ID de proyecto/API key pública FCM (no es secreto por diseño de Firebase). En el ZIP filtrado del 17/09 estaba incluida |
| F5 | `.env` raíz (4826 B, ~40 vars) | PRESENTE, gitignored (`.gitignore:2`), 0600 | **CRÍTICA** (fue publicada en ZIPs) | Contiene POSTGRES/MQTT/EMQX/DASH/WEB_SECRET_TOKEN/ENCRYPTION_KEY/API keys de mapas. `MOBILE_HTTP_API_KEY_PREVIOUS` ya poblada (1 ocurrencia) → ventana de rotación preparada |
| F6 | `dashboard/.env` | En submódulo dashboard, **TRACKED en el fork dashboard** | MEDIA | `git -C dashboard ls-files` la lista. Valor actual: solo `VITE_APP_VERSION` (inofensivo). Pero en historia dashboard está el commit `f418231b` con contenido mayor (1 var). Sin secretos reales hoy: riesgo bajo, pero patrón peligroso |
| F7 | `.env.example` raíz | Versionado, sin valores | OK | Keys documentadas, valores placeholder. Nota: commit `51794f9` lo quitó una vez y `91ba10b` lo re-agregó — correcto mantener example versionado |
| F8 | `server/conf/traccar-dev.xml` | PRESENTE en árbol | **ALTA** | El README del template dice "Copiar a traccar-dev.xml (gitignored)", pero **el archivo real está en el árbol** (`server/conf/`). ¿Gitignored? Verificar en submódulo server (check-ignore no se pudo correr por submodule pathspec). El contenido escaneado no muestra secretos en claro (user BD visible como `tr***`, password ausente — usa env vars). Contiene `fcm.recovery.*` (cooldown 60s, max 5/h) — config, no secreto |
| F9 | `infrastructure/emqx/auth-file.csv` | Versionado con bootstrap hashes | BAJA-MEDIA | Formato `user_id, password_hash, salt, is_superuser` — **hashes, no claros**. Usuarios: `dmj-consumer`, `juan-001`. Los hashes bcrypt de dev son crackeables offline si la password dev es débil; en prod los usuarios se crean runtime vía `mqtt-users.sh`. En historia git desde `5f29a52`/`6c8e2ad` (app 1.0.4) — siempre hashes |
| F10 | SENTRY_DSN hardcodeado en `mobile/app/build.gradle.kts:44` | PRESENTE, embebido en APK | **INFORMATIVA/BAJA (riesgo aceptable)** | El DSN de Sentry es público por diseño (cualquier APK lo expone en strings; se extrae del dex con `strings`). Riesgo real: spam de eventos falsos a la cuenta Sentry (cuota). Mitigación posible: inbound filter por versión/proyecto en Sentry. No es credencial de autenticación (no da acceso de lectura) |
| F11 | Clave dev `dmj-dev-fallback-key` en historia git | HISTÓRICA | MEDIA | Commit `1b396e9`/`9716728` (app 1.0.15) la embebía en `AppConfig.HTTP_API_KEY`. Hoy eliminada del código (S1 RESUELTO), pero **persiste en el historial público**. Si esa cadena coincide con la API key real de prod (o fue `PREVIOUS`), rotarla la neutraliza. Verificar: docs/CURRENT_ARCHITECTURE.md:108 la calificaba de "valor dev conocido" |
| F12 | Credenciales en docs | LIMPIO | OK | README.md y docs/*.md solo contienen placeholders y referencias a variables de entorno. `RUNBOOK-SANTIAGO.md:192` menciona `dmj-dev-fallback-key` como ejemplo dev documentado (coherente con F11). Ninguna password real en markdown |
| F13 | `infrastructure/scripts/create-collaborator.sh` / `mqtt-users.sh` / `run-server-dev.sh` | LIMPIO | OK | Leen credenciales de `.env`/args; no hardcodean secretos |
| F14 | `backups/dmj-worktree-20260917-1352.tar.gz` (2.4 MB, local) | PRESENTE | **MEDIA** | Contiene keystore.properties, secrets.properties, .env de dashboard, keystores y google-services.json (verificado por listado tar). Riesgo si se copia/comparte/publica en web.path. Recomendación: añadir a empaquetado seguro + borrar cuando no se necesite |

### google-services.json — evaluación
Sin `client_secret` ni private key (escaneo de JSON keys: solo `api_key`/`client_info`/`oauth_client` públicos). Clasificación: NO es un secreto. OK versionarlo fuera; el `.gitignore` actual es conservador y correcto.

---

## 2. Git history — secretos alguna vez versionados

Repos escaneados: monorepo + `server/` + `dashboard/` (submódulos), con `--all`.

| Repositorio | Artefacto | Encontrado | Acción requerida |
|---|---|---|---|
| monorepo | `mobile/keystore/debug.keystore` | **SÍ** — commit `91ba10b` (A, Bin 2618 B), sigue en HEAD a propósito | No rotable (es la firma OTA activa); riesgo aceptado/documentado. Neutralizado al migrar firma (S3) |
| monorepo | `.env`, `mobile/.env`, `mobile/secrets.properties`, `mobile/keystore.properties`, `*.jks`, release.keystore | **NO** en historia (log --all --diff-filter=A vacío para estos) | — |
| monorepo | Cadena `dmj-dev-fallback-key` (S1 antigua) | **SÍ** en historia (`1b396e9`, `9716728` app 1.0.15) | Cubierta por rotación S1 ya preparada (`MOBILE_HTTP_API_KEY_PREVIOUS` en `.env`); confirmar que la clave actual ≠ fallback dev |
| server | `.env`, `*.keystore` | NO | — |
| dashboard | `.env` | **SÍ** versionado (HEAD) y en historia (`f418231b` con 1 var real) | Gitignore en fork dashboard; scrub opcional (reescribir historia del fork) |
| monorepo | `infrastructure/emqx/auth-file.csv` | Versionado desde `5f29a52` — solo hashes/salt, sin claros | OK; mantener hashes de dev débiles fuera de prod |

**Nunca se versionaron en git: `release.keystore`, `secrets.properties`, `keystore.properties`, `.env` raíz.** La vía de fuga fue ZIPs en el servidor (ya mitigada el 17/09), no git — excepto `debug.keystore` (intencional) y `dashboard/.env` (menor).

---

## 3. APK release actual (1.1.5, versionCode 115)

Fuente: `mobile/app/build/outputs/apk/release/app-release.apk` (8.2 MB, 17/09 13:53).

| # | Hallazgo | Severidad | Evidencia |
|---|---|---|---|
| A1 | `MOBILE_HTTP_API_KEY` de flota embebida en BuildConfig → extraíble con `strings classes.dex` | **ALTA (diseño aceptado, documentado en SECURITY.md §1.4)** | La clave compartida está en claro en el dex. Es secreto compartido por diseño; la defensa real es TLS + rotación. No se reproduce el valor |
| A2 | SENTRY_DSN en claro en dex | BAJA | ver F10 |
| A3 | NSC: base `cleartextTrafficPermitted=false` + excepciones a 6 hosts (68.168.20.219, 2 nodos Tailscale, loopback, 10.0.2.2, localhost) | MEDIA (S2 parcial) | Verificado en `res/xml/network_security_config.xml` compilado. Los nodos Tailscale 100.x son red privada VPN → cleartext allí es riesgo menor pero documentable |
| A4 | `tcp://68.168.20.219:1883` como servidor MQTT por defecto en dex | **ALTA** | MQTT sin TLS a internet en claro (ver §5) |
| A5 | URLs dev `http://10.0.2.2:8969/stream` y `http://localhost:8969/stream` en dex | INFORMATIVA | Solo aplican en emulador; sin datos reales |
| A6 | Permisos peligrosos declarados: `ACCESS_BACKGROUND_LOCATION`, `READ_PHONE_STATE`, `REQUEST_INSTALL_PACKAGES`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `USE_FULL_SCREEN_INTENT` + FG service location | MEDIA (justificados, verificar en Play si aplica) | READ_PHONE_STATE es opcional en runtime (TelephonyState.kt: gating correcto). REQUEST_INSTALL_PACKAGES: la app dispara instalador vía FileProvider+ACTION_VIEW (UpdateManager.kt:236) — **no verifica firma/sha256 del APK descargado**; la descarga OTA puede ser por HTTP (UpdateManager prueba http primero) → update-path manipulable en red hostil |
| A7 | Componentes exported: MainActivity (launcher), receiver BOOT_COMPLETED, widget | OK (mínimos esperados) | Ningún provider/service exportado innecesario. `allowBackup=false` ✓ |
| A8 | R8: `isMinifyEnabled=true`, `isShrinkResources=true`, mapping.txt generado local (no versionado) | OK | Proguard rules presentes |
| A9 | Firma del APK | verificar en ventana | `keytool -printcert -jarfile` no disponible en este entorno; `keystore.properties` apunta a firma configurada. Por build gating (R2), un release compilado con keystore.properties presente firma con la clave provisionada — confirmar cuál (`debug` vs `release`) antes de publicar: **la 1.1.5 puede estar firmada con debug (OTA) o release (incompatible con flota)**. Punto crítico del plan de migración S3 |
| A10 | google-services.json dentro del APK (recurso compilado FCM) | OK | Esperado; sin secretos |

**Nota A6 (detalle OTA):** `UpdateManager` cae a `http://host:999/latest.json` y descarga el APK por esa vía si el 999 responde; sin pinning de certificado ni verificación de firma del binario. Con NSC cleartext-permitido a ese host, un MITM puede servir un APK malicioso (el usuario lo instala manualmente). Recomendación: verificar SHA-256 del APK contra `latest.json` firmado, o forzar HTTPS en el canal de updates.

---

## 4. Servidor — `/api/mobile/v1/*`

Recursos con `@PermitAll` (autenticación manual dentro del handler, no por filtro JAAS):

| Endpoint | Auth | Rate limit | deviceId | Observación |
|---|---|---|---|---|
| `mobile/v1/positions` (MobileHttpResource) | X-Api-Key (validator S1 con ventana current+previous, comparación en tiempo constante ✓ MobileApiKeyValidator.java) | NO hay rate limit por IP/dispositivo; sí semáforo de concurrencia (20) + `MAX_BATCH_ITEMS=200` + 503 Retry-After | No aplica (batch) | **Flood posible**: un atacante con la clave (comprometida en ZIPs) puede saturar HikariCP; sin límite de peticiones/segundo por clave |
| `mobile/v1/recovery/*` (MobileRecoveryResource) | X-Api-Key + X-Device-Id → lookup de Device ✓ | FCM policy: cooldown 60s + máx 5/h ✓ (FcmRecoveryPolicy) | Requerido y validado ✓ | deviceId no asociado a la clave (cualquier clave válida consulta cualquier deviceId) |
| `mobile/v1/health` (MobileHealthResource) | X-Api-Key | Ninguno | Header requerido | Misma clave compartida |
| `mobile/v1/config` (MobileConfigResource) | X-Api-Key | Ninguno | Header/query | deviceId del body NO es identidad (bien documentado) |
| `mobile/v1/diagnostics` (MobileDiagnosticsResource) | X-Api-Key | Rate limit en servicio (MobileDiagnosticsService) | Header o query | `X-Device-Id` nunca se toma del body ✓ |

**Evaluación del modelo @PermitAll + X-Api-Key:** razonable para flota pequeña con una clave compartida. Debilidades estructurales:
1. **Una sola clave para toda la flota** — comprometida la clave (incidente ZIPs), todos los dispositivos quedan impersonables hasta rotación. `PREVIOUS` ya preparada ✓.
2. **deviceId no ligado a la clave**: cualquier teléfono con la clave puede reportar posiciones de cualquier deviceId (suplantación intra-flota). Mitigación futura: credencial por dispositivo o JWT firmado.
3. **Flood**: sin rate limiting por clave/IP en positions/health/config; la semántica actual confía en tamaño de batch y semáforo. Con la clave pública (ZIPs), riesgo de DoS de ingesta real.
4. FCM recovery sí tiene rate limit ✓ (cooldown+hourly), no flood por esa vía.

---

## 5. Red

| # | Hallazgo | Severidad | Evidencia |
|---|---|---|---|
| N1 | MQTT `tcp://68.168.20.219:1883` público sin TLS, credenciales en claro en el aire (user/pass de EMQX viajan en CONNECT packet) | **ALTA** | docker-compose.yml:72 expone 1883 a internet (ufw abierto); AppConfig.kt:771 default `tcp://…:1883`; emqx.conf tiene listener ssl:default (8883) habilitado con certs, **pero la app no lo usa** |
| N2 | HTTP cleartext :999 (OTA + fallback posiciones) permitido por NSC a 68.168.20.219 | MEDIA (S2 conocido) | token/posiciones en claro en tránsito; NSC lo restringe a hosts conocidos ✓ |
| N3 | Tailscale 100.83.91.53 / 100.86.171.63 con cleartext permitido | BAJA | Tráfico sobre WireGuard; cleartext dentro del tunnel es aceptable |
| N4 | EMQX 8883 (TLS) disponible y no usado por la app | MEDIA | Cambiar `tcp://`→`ssl://` en AppConfig cuando certs estén en el host; requiere validar CA (self-signed hoy → habría que embeber trust anchor o Let's Encrypt por dominio) |
| N5 | Puertos: 5433 (PG) y 6379 (Redis) bind 127.0.0.1 ✓; 18083 (EMQX dashboard) no publicado en prod ✓; 8083 local | OK | docker-compose.yml:17-37, prod comments |
| N6 | Traccar 5055/5000-5500 (protocolos GPS upstream) | Verificar firewall | En prod compose están comentados; en dev compose el 1883 es el único abierto declarado |

---

## 6. Plan por fases (qué mover/provisionar/rotar y qué NO tocar aún)

### Fase 0 — ya hecho (R2, verificar vigencia)
- ZIPs retirados (404), `.env` rotado 17/09, `pack-project.sh` obligatorio, keystore gating con fallo duro. **OK, no repetir.**

### Fase 1 — inmediato (sin ventana de flota)
1. **NO rotar `MOBILE_HTTP_API_KEY` todavía** — la flota ≤1.1.4 lleva la clave vieja; rotar ahora rompe HTTP. La ventana ya está armada (`PREVIOUS` poblada): ejecutar cuando la flota ≥1.1.5 sea dominante, luego eliminar `PREVIOUS` tras 2-4 semanas.
2. **Regenerar `release.keystore`** (F1): la actual se publicó en ZIP. Generar nueva RSA 4096, provisionar 0600, destruir la vieja de todos los discos/backups (incluido `backups/dmj-worktree-*.tar.gz`, F14). No impacta flota (release.keystore no está en uso).
3. Borrar/asegurar `backups/dmj-worktree-20260917-1352.tar.gz` (contiene secretos en claro).
4. Confirmar el estado gitignore real de `server/conf/traccar-dev.xml` en el submódulo server y, si está trackeado, gitignorarlo (solo debe existir el `.example`).
5. Dashboard fork: gitignore `.env` (aunque hoy sea inofensivo).
6. Sentry: activar filtro/quota anti-spam del DSN público (F10) — sin cambio de código.

### Fase 2 — con ventana de flota (≥1.1.5 desplegada)
7. Rotar `MOBILE_HTTP_API_KEY` (current→previous, nueva→current); publicar APK 1.1.6 con nueva clave.
8. Migración de firma S3: con `release.keystore` **regenerada** (paso 2), coordinar desinstalación/reinstalación por equipo. **No cambiar certificado de la flota antes de regenerar la keystore.**
9. MQTT sobre TLS: apuntar `AppConfig.DEFAULT_SERVER` a `ssl://…:8883`, desplegar certs Let's Encrypt en host, luego cerrar 1883 en ufw (N1). Orden: certs → nueva app → cierre puerto. **No cerrar 1883 antes de que toda la flota use 8883.**

### Fase 3 — endurecimiento (post-migración)
10. OTA: verificar SHA-256 del APK descargado contra manifiesto, y forzar HTTPS en update path (A6).
11. Rate limit por clave/IP en `/api/mobile/v1/positions` y `/health` (flood §4.3).
12. Credencial por dispositivo (o token firmado) para cerrar suplantación intra-flota (§4.2).
13. Scrub de historia git (opcional, bajo valor): `dashboard/.env` en fork; la clave dev en historia del monorepo queda neutralizada por la rotación de la fase 2. `debug.keystore` NO se puede purgar de la historia — se neutraliza al migrar firma (S3).

### Qué NO cambiar aún
- `debug.keystore` versionada: sigue siendo la firma OTA activa; retirarla ahora rompe updates de ≤1.1.2.
- `SENTRY_DSN` en build.gradle.kts: es público por diseño; solo mitigar con cuota.
- `@PermitAll` + X-Api-Key: correcto para el modelo actual; endurecer solo tras estabilizar rotación/TLS.
- `google-services.json` gitignored: conservador pero inofensivo; dejar como está.

---

## Resumen final

1. **Vía de fuga real fue ZIPs en el servidor (17/09, mitigada), no git**: `release.keystore`, `.env`, `secrets.properties`, `keystore.properties` nunca se versionaron.
2. **CRÍTICO pendiente 1:** `release.keystore` comprometida — regenerar ANTES de migrar firma de flota (S3). Sin costo operativo hoy.
3. **CRÍTICO pendiente 2:** `.env` completo (DB/MQTT/EMQX/panel) circuló en ZIPs — rotación planificada en SECURITY_BUILD.md §3, ejecutar en ventana.
4. **ALTA:** `debug.keystore` es pública en git (intencional, firma OTA activa): cualquiera puede firmar updates aceptados por la flota ≤1.1.2. Se cierra con migración de firma.
5. **ALTA:** MQTT 1883 público sin TLS con credenciales en claro en el aire; 8883/TLS existe pero la app usa `tcp://`. Plan fase 2.9.
6. **ALTA (diseño):** `MOBILE_HTTP_API_KEY` de flota embebida en APK (extraíble con strings). Secreto compartido por diseño; rotación con ventana ya preparada (`PREVIOUS` poblada). Ejecutar cuando flota ≥1.1.5 domine.
7. **MEDIA:** APK 1.1.5 verificado: NSC base deny-cleartext ✓, R8 ✓, allowBackup=false ✓, exported mínimos ✓; pero OTA puede descargar APK por HTTP sin verificación de firma/SHA-256 → MITM en update path.
8. **MEDIA:** server móvil: X-Api-Key con comparación en tiempo constante ✓ y ventana de rotación ✓; sin rate limit en positions/health; deviceId no ligado a la clave (suplantación intra-flota posible con clave comprometida). FCM recovery sí tiene cooldown+hourly ✓.
9. **BAJA/OK:** SENTRY_DSN público en dex = riesgo de spam de eventos, no credencial; auth-file.csv solo hashes dev; docs sin credenciales reales; traccar-dev.xml sin secretos (usa env vars) pero verificar gitignore en submódulo server.
10. **HIGIENE:** `backups/dmj-worktree-*.tar.gz` contiene secretos en claro — asegurar/borrar. `dashboard/.env` versionada en el fork (hoy inofensiva).
11. Git history: clave dev antigua `dmj-dev-fallback-key` persiste en historia pública — neutralizada al rotar la clave actual (fase 1-2).
12. Orden de ejecución recomendado: regenerar release.keystore + asegurar backups (ya) → rotar X-Api-Key con ventana (flota ≥1.1.5) → migrar firma → MQTT-TLS + cerrar 1883 → rate limits + verificación OTA.
