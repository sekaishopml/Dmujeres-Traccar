# FINAL_PRODUCTION_AUDIT.md — Auditoría final DMujeres-Tracking

Fecha: 2026-09-16. Versión evaluada: **1.1.3** (versionCode 113).
Método: ejecución real de suites, validación E2E contra servidor y BD de
desarrollo (mismo esquema que producción), sin hacks ni resultados inventados.

## 1. Resumen ejecutivo

- **Fases ejecutadas**: F0 (auditoría), F1 (baseline), F2 (caracterización),
  F3 (arquitectura de tracking), F4 (health engine), F5 (continuidad),
  F6 (recovery con acción de usuario), F7 (observabilidad server + salud E2E),
  F8 (sensores/fusión), F9 (OEM por capacidades), F10 (seguridad S1/S2/S3),
  F13 (release 1.1.3 + OTA).
- **Fases bloqueadas por hardware/credenciales**: F11 (QA real multi-OEM) y
  F12 (endurance) → `BLOCKED — REQUIRES REAL-WORLD VALIDATION` con lo exacto
  faltante (ver §5).
- **Resultado**: sistema observable, recuperable, offline-first y OEM-aware,
  con evidencia real de continuidad sobre el incidente qa-f0.

## 2. Estado por área

| ÁREA | STATUS | EVIDENCIA |
|---|---|---|
| Architecture | PASS | `docs/ARCHITECTURE_FINAL.md`; TrackingService 2819→2021 líneas; 6 componentes extraídos; 549/0 móviles |
| Tracking | PASS (unit+real parcial) | suites FixFilter/Quality/Outbox 549/0; jornada real qa-f0 procesada |
| GPS | PASS (unit) | `FixFilterTest`, `FixRobustnessTest`, `LocationSensorFusionTest`, `GnssFallbackPolicyTest` |
| GNSS fallback | PASS | fallback con main looper (NPE "invalid null looper" corregido), tests de estado |
| Sensors | PASS (unit) | fusión pura; sin dead reckoning; sensores opcionales no bloquean |
| Outbox | PASS | buffer/replay/seq/dead letter/retention; 0 pérdidas en gap real (replay reportado íntegro) |
| MQTT | PASS (unit) | presencia + LWT + reconnect gate; EMQX dev sano |
| HTTP | PASS + E2E | lotes con ACK; health E2E `accepted:2`; idempotencia y throttle verificados por HTTP real |
| FCM | PARTIAL | SEND real (messageId), RECEIVE real (ZTE), ACK por etapas (server tests); E2E recovery `PENDING_VALIDATION` |
| Recovery | PASS (implementación+unit) | escalera LVL0-5; SessionKeeper; "tap to resume"; `RecoveryJournal` honesto |
| Health | **PASS (E2E)** | `tc_device_health` escribiendo (3 filas E2E), atributos `mobile.health*`, snapshots HEARTBEAT/STATE_CHANGE/CRITICAL |
| OEM | PARTIAL | ZTE `OEM_LIMITATION` (cfreezer real); Infinix X6531 desactualizado; resto `NOT_TESTED` |
| Security | PASS (S1 rotado, S2 parcial, S3 resuelto) | `docs/SECURITY.md`; apksigner; fallback dev ausente del APK; NSC restrictiva |
| QA | PASS (automatizada) | 821/0 server, 549/0 mobile, 80/0 dashboard, lint release OK |
| Endurance | NOT_TESTED | 6/12/24 h pendientes de dispositivo accesible |
| Dashboard | PASS (nuevo) | `/reports/dmujeres` + `dmujeresFleet` (80 tests node) + build Vite OK |
| Deployment | PASS (dev) | server healthy; migración 6.14.6 aplicada; OTA HTTP 200 |
| Rollback | PASS (procedimiento) | APK 1.1.2 conservado; `latest.json` revertible documentado |
| Known limitations | Documentadas | §4 de este informe |

## 3. Qué cambió (por fase)

- **F0**: `docs/CURRENT_ARCHITECTURE.md`, `docs/GAP_ANALYSIS.md`.
- **F1**: baseline registrado (`docs/BASELINE.md`): server 805/0, mobile 507/0,
  dashboard 73/0.
- **F2**: tests de caracterización de motor/notificaciones/salud/outbox.
- **F3**: `LocationEngine`(+policy+timeout), `TrackingNotificationController`
  (+policy), `OutboxCoordinator`, `TrackingHealthMonitor`, `SensorCoordinator`,
  `TrackingSessionController`; servicio adelgazado sin cambio de conducta.
- **F4**: health engine móvil (`TrackingHealthPolicy` conectada, snapshots con
  estado derivado, transiciones y eventos críticos).
- **F5**: continuidad server (`MobileContinuityPolicy/Service`, endpoint,
  `journeyId` aditivo en posiciones).
- **F6**: recuperación nivel usuario (notificación "Toca para reanudar"),
  vigilancia de `ACCESS_BACKGROUND_LOCATION`, deep link ZTE.
- **F7**: ingesta/persistencia/consulta de salud (endpoint, store JDBC,
  retención 90 d, perfil de capacidad en atributos).
- **F8**: fusionador de sensores (sin coordenadas artificiales).
- **F9**: `DeviceCapabilityProfile`, `OemGuidanceProvider`, matriz OEM.
- **F10**: rotación de clave con ventana, Network Security Config, keystore de
  release y build firmado.
- **F13**: 1.1.3 firmada, SHA256, OTA publicada y verificada.

## 4. Limitaciones conocidas (honestas)

1. **ZTE cfreezer** = OEM_LIMITATION: los huecos con pantalla apagada no se
   eliminan por software; se detectan, diagnostican, recuperan (FCM/alarma) o
   se pide acción al usuario. No se promete "no puede ser matada".
2. **Firma nueva**: equipos con 1.1.2 (debug-signed) requieren reinstalación
   manual única; desinstalar borra el outbox pendiente (drenar antes).
3. **TLS pendiente**: HTTP cleartext permitido SOLO a hosts conocidos; un
   servidor nuevo requiere TLS o rebuild.
4. **Clave de flota** viaja en el APK (secreto compartido). Mitigado con
   rotación y ventana; siguiente paso: token por dispositivo.
5. **Endurance** y matriz multi-OEM sin datos: no se declara soporte.
6. **`dataIntegrity`** no se reporta como 100% cuando no es medible
   server-side (solo cuarentena/accepted del cliente).
7. **Santiago (Infinix X6531)**: app 1.0.101 y 561 `RECOVERY_SKIPPED_NO_TOKEN`;
   requiere actualizar y registrar token FCM.
8. **FCM E2E**: pendiente ventana real (ver `docs/FCM_RECOVERY.md`).

## 5. Pending exactos

| ID | Falta | Cómo validarlo |
|---|---|---|
| P1 | FCM E2E real | inducir silencio real → probe → nueva posición → `tc_recovery_event RECOVERY_SUCCESS` con `positionid` |
| P2 | Endurance 6/12/24 h | jornada real con `tc_device_health` + continuidad + batería/térmica |
| P3 | QA multi-OEM | al menos Samsung/Xiaomi/Infinix reales con `SCREEN_OFF_PASS/DOZE_PASS` |
| P4 | ZTE qa-f0 ADB | `adb connect 100.86.171.63:5555` → hoy `connection refused`; repetir con equipo despierto |
| P5 | TLS | certificados web + MQTT; retirar excepciones cleartext |
| P6 | Backup/restore probado en ciclo | `backup.sh` + `restore.sh` con evidencia |
| P7 | Instrumented Room 8→9 | `connectedDebugAndroidTest` en dispositivo |
| P8 | Token por dispositivo | reemplazar clave de flota embebida |

## 6. Veredicto

El sistema está listo para operación controlada con la flota actual
(HTTPS pendiente a nivel infraestructura y limitaciones OEM documentadas).
No se declara `FULLY VALIDATED` global: la parte que depende de hardware real
(F11/F12/FCM E2E) queda **BLOCKED — REQUIRES REAL-WORLD VALIDATION**, mientras
que el resto queda implementado, probado y con evidencia reproducible.
