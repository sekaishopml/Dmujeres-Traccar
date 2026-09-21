# PRODUCTION_RUNBOOK.md — Operación y release (FASE 13/§54/§55)

## 1. Despliegue del servidor (dev/staging actual)

```bash
bash infrastructure/scripts/run-server-dev.sh make-config   # 1ª vez
bash infrastructure/scripts/run-server-dev.sh restart       # arranca/actualiza
curl -s http://localhost:999/api/health                     # esperado: OK
```

- Liquibase se aplica al arrancar. Migración nueva: `server/schema/changelog-6.14.6.xml`
  (columnas de salud + índice único). **Nunca** editar changelogs ya aplicados.
- Variables nuevas: `MOBILE_HTTP_API_KEY` (sin default) y
  `MOBILE_HTTP_API_KEY_PREVIOUS` (ventana de rotación).

## 2. Base de datos

```bash
docker exec dmj-db psql -U traccar -d traccar -c "SELECT count(*) FROM tc_device_health;"
bash infrastructure/scripts/backup.sh
bash infrastructure/scripts/restore.sh <archivo>
```
- Retención de `tc_device_health`: 90 días (poda horaria automática en la ingesta).
- Outbox Room móvil: retención dura 100 000 filas / 7 días; snapshots 24 h.
- Timescale en `tc_positions` (hypertable).

## 3. Release móvil

1. `mobile/secrets.properties` (0600) con `MOBILE_HTTP_API_KEY` de flota.
2. `mobile/keystore.properties` (0600) + `mobile/keystore/release.keystore`
   (respaldar FUERA de la máquina).
3. Bump `versionCode`/`versionName` en `mobile/app/build.gradle.kts`.
4. `cd mobile && ./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease`
5. Verificar firma y hash:
   `apksigner verify --print-certs app-release.apk` + `sha256sum`.
6. Publicar OTA (URL pública, nunca Tailscale/LAN):
   `bash infrastructure/scripts/publish-ota.sh \
      mobile/app/build/outputs/apk/release/app-release.apk <v> "<notas>"`
   (copia el APK + escribe `latest.json` en `dashboard/build` y
   `dashboard/public`, y verifica `http://<host>:999/latest.json` y el APK).
7. Rollback: volver `latest.json` a la versión anterior (el APK anterior se
   conserva en `dashboard/build/`). Nunca borrar el APK previo.

## 4. Firma y actualizaciones OTA

La flota instalada (≤1.1.2) está firmada con la keystore debug compartida
(`mobile/keystore/debug.keystore`, versionada). Android rechaza actualizar una
app si cambia el certificado de firma (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`:
"no se instaló la app debido a un conflicto con un paquete"), y esta app se
actualiza sola por OTA, así que el canal sigue firmando con ESA clave
(1.1.5 incluida).

La `release.keystore` (RSA 4096, `CN=DMujeres Tracking`) queda reservada para
una migración futura coordinada: exige **desinstalar y reinstalar UNA vez** en
cada equipo (se pierde el outbox pendiente; hacerlo con la cola drenada
`mobile.pending`=0 y sin jornada activa) y reactivar la firma de release en
`mobile/app/build.gradle.kts` (nota en `buildTypes.release`).

## 5. Checklist de producción (§54)

- [x] Server tests 821/0 · Mobile 549/0 · Dashboard 80/0
- [x] lint release (Android) sin errores · dashboard lint de archivos nuevos
- [x] R8/ProGuard activos · release sin debug signing
- [x] APK: SHA256, versionCode/Name, firma, permisos/servicios/receivers
- [x] Sin secretos en git; `mobile/secrets.properties` y keystore gitignored
- [x] OTA verificada por HTTP
- [x] Migración de BD aplicada y verificada
- [ ] TLS web/API + MQTT (pendiente de infraestructura)
- [ ] Backup/restore probado en este ciclo (scripts existentes; última prueba
      documentada en `infrastructure/scripts/backup.sh`/`restore.sh`)
- [ ] Endurance 6/12/24 h (pendiente de dispositivos)
- [ ] FCM E2E con ventana real (pendiente)

## 6. Observabilidad mínima en operación

- `tc_device_health`: últimas 24 h por device (¿hay snapshots cada 5 min?).
- `tc_recovery_event`: `RECOVERY_SENT` vs `SUCCESS/TIMEOUT/BLOCKED`.
- Atributos: `mobile.healthState`, `mobile.pending`, `mobile.quarantinedTotal`.
- Página `/reports/dmujeres` del dashboard: flota + continuidad.

## 7. Diagnóstico avanzado (función oculta para soporte)

En la pantalla principal del colaborador NO hay botón de Diagnóstico.

Acceso: pulsar **5 veces consecutivas** el texto **"Actualización · vX.Y.Z"**
(pie de la pantalla). Ventana: máximo 1,2 s entre toques y 4 s en total; una
secuencia lenta o un toque aislado se descarta. Al quinto toque aparece
"Diagnóstico avanzado" y se abre `DiagnosticsActivity`.

No mostrar esta instrucción al usuario normal (regla UX).
