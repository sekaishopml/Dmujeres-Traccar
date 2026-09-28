# FASE 11 — Procedimiento de release reproducible (2.1.74 / 284)

Reproducible y sin tocar producción. En esta fase solo se deja el
procedimiento y el bump en `fallback/app/build.gradle`; NO se publica nada
en `ota/` y NO se firma nada (no hay keystore en el repo).

Estado vigente: OTA `2.1.73 (283)` en `ota/latest.json`. La `2.1.74 (284)`
entra solo en la fase física, con teléfono y ventana de release.

## Pasos

1. **Bump (único cambio de código en esta fase).** En rama nueva desde
   `plataforma`, en `fallback/app/build.gradle`:
   `versionCode 284` / `versionName '2.1.74'`. Nada más. Verificar con
   `git diff -- fallback/app/build.gradle`.
2. **Build debug local (solo compilación).**
   `cd fallback && ./gradlew assembleRegularDebug` (variante `regular` no
   exige `google-services.json`). Debe terminar sin errores.
3. **Build release firmado (solo en ventana de release, NUNCA en esta fase).**
   El keystore llega por canal seguro (p. ej. USB sellado o vault del dueño),
   se coloca fuera del repo (`../mobile/keystore.properties` +
   `../../mobile/keystore/*.keystore`, ambos ignorados por git) y jamás se
   commitea. Comando: `./gradlew assembleGoogleRelease`. Sin keystore, no hay
   release: abortar.
4. **sha256.** `sha256sum app/build/outputs/apk/google/release/*.apk`
   y guardar el hash junto a `versionCode`, `versionName` y nombre de archivo.
5. **Preparar `ota/latest.json` (plantilla, NO aplicar aún).** Ejemplo futuro:
   `{"version":"2.1.74","versionCode":284,"url":"http://<host>:999/DMujeres-Tracking-2.1.74.apk","notes":"Actualizar a la versión 2.1.74","sha256":"<hash-del-paso-4>"}`.
   NO sobrescribir el `latest.json` vigente (2.1.73) hasta la fase física.
6. **Publicar OTA (solo en ventana).** Copiar el APK + `latest.json` +
   `rollout.json` al `otaDir` del tracking, verificar
   `curl -s http://127.0.0.1:999/api/mobile/v1/ota?deviceId=<id>&versionCode=283`
   y descarga del APK con `sha256sum` coincidente.
7. **Verificación E2E.** Con `DMJ_TEST_PASSWORD` y `DMJ_TEST_MOVIL_KEY`
   obligatorias por entorno (sin defaults):
   `npm ci && npm run lint && npm run build` en `apps/web`;
   `bash scripts/validation/e2e.sh` (prohibido con credenciales por defecto).
   Criterio: E2E PASS + instalación OTA en teléfono de prueba.

## Prohibido en esta fase

Firmar releases, publicar en `ota/`, commitear/pushear, reiniciar servicios
o correr el E2E completo con credenciales por defecto.
