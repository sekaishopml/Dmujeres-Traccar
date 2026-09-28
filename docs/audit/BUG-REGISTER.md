# Bug Register — DMujeres Tracking

Clasificación: `CONFIRMADO` (verificado en código/datos),
`RIESGO` (arquitectónico), `HIPÓTESIS` (evidencia indirecta),
`PENDIENTE` (requiere prueba física).

| ID | Comp. | Bug/Riesgo | Causa | Impacto | Evidencia | Solución | Prioridad | Estado |
|---|---|---|---|---|---|---|---|---|
| BUG-001 | Servidor | `dmt_posicion_actual` retrocede con paquetes atrasados | Upsert sin comparar tiempo | Panel/`/position` muestra posición vieja | `services/tracking/src/db.js:191-222` | Guarda `EXCLUDED.registrado_en > actual` | P0 | CONFIRMADO |
| BUG-002 | Servidor | `ultima_conexion_en`/`ultima_posicion_id` retroceden | Update sin `GREATEST` | Estado de flota incorrecto | `db.js:225-236` | `GREATEST(ultima_conexion_en, EXCLUDED)` | P0 | CONFIRMADO |
| BUG-003 | Servidor | Retransmisión duplica posiciones | Sin UNIQUE ni dedupe | Métricas y replay inflados | `db.js:168`; sin constraint en `database/schema/03_tracking.sql` | Unique `(dispositivo,boot,seq)` + `ON CONFLICT DO NOTHING` | P0 | CONFIRMADO |
| BUG-004 | App | Cola bloqueada por error permanente (404/400) | Todo fallo se trata igual y no se borra | Buffer crece, se pierde lo viejo, nunca se recupera | `fallback/.../RequestManager.kt:29-47`, `TrackingController.kt:326-353` | Clasificar HTTP; 4xx→DEAD con reporte | P0 | CONFIRMADO |
| BUG-005 | App | Retry fijo 30 s sin backoff/jitter | `RETRY_DELAY` constante | Golpeteo al servidor caído; sin escalado | `TrackingController.kt:346-357` | Backoff exp + jitter | P1 | CONFIRMADO |
| BUG-006 | App | Buffer 5000 descarta el más viejo en silencio | Cap sin telemetría | Pérdida de datos en offline largo sin aviso | `DatabaseHelper.kt:116-124` | Reportar `buffer_overflow` | P1 | CONFIRMADO |
| BUG-007 | App | Migración SQLite hace `DROP TABLE` | `onUpgrade` destructivo | Actualizar la app borra el buffer pendiente | `DatabaseHelper.kt:81-89` | Migración aditiva v5 | P1 | CONFIRMADO |
| BUG-008 | App | Movimiento decidido por acelerómetro; UNKNOWN→parado | `MotionMonitor` std + `motionTick` | Cadencia lenta en marcha constante; deadlock (caso Pilay) | `MotionMonitor.kt:37-38`, `TrackingController.kt:81-98`; datos 27/09 | ADR-006 (máquina multi-fuente) | P0 | CONFIRMADO |
| BUG-009 | Ambos | Sin identidad de evento (`boot_id`/secuencia) | Nunca se implementó | Sin idempotencia ni orden | `Position.kt`, `DatabaseHelper.kt:64-79`, schema | ADR-005 | P0 | CONFIRMADO |
| BUG-010 | Servidor | Acepta fechas absurdas | Sin validación de rango | Partición basura `dmt_posicion_2037_10`; polución | Partición existe en `dmt-db` | Validar rango y purgar | P1 | CONFIRMADO |
| BUG-011 | Entrega | APK 2.1.73 (283) no está en git (repo en 282/2.1.72) | Bump sin commit | Release no reproducible/auditable | `ota/latest.json` vs `fallback/app/build.gradle:26` | Commitear 283/284 con source | P0 | CONFIRMADO |
| BUG-012 | Entrega | Keystore fuera del repo; app firmada con `debug.keystore` | Firma heredada | Custodia/rotación frágil; no apto para distribución empresarial formal | `fallback/app/build.gradle:57-70` | Custodia documentada; evaluar clave propia | P1 | RIESGO |
| BUG-013 | Seguridad | Credenciales de prueba en repo y scripts | Defaults en E2E/docs | Fuga si el repo es público | `scripts/validation/e2e.sh:39,234,244` | Variables sin default; limpiar docs | P1 | CONFIRMADO |
| BUG-014 | Web | Tramo estimado indistinguible del real | `BANDA_ESTIMADA` en el mismo estilo | Incumple regla de oro (auditoría) | `apps/web/src/paginas/operacion/replay.ts` | ADR-007 (REAL/MATCHED/ESTIMATED) | P0 | CONFIRMADO |
| BUG-015 | Ambos | Sin reconciliación de jornada cliente↔servidor | Solo `POST /journey` | Tras reboot la app puede quedar sin estado de jornada | `services/tracking/src/servidor.js:50-52` | `GET /journey` + JourneyManager | P1 | CONFIRMADO |
| BUG-016 | App | APIs de red deprecadas (`activeNetworkInfo`, `CONNECTIVITY_ACTION`) | Código heredado | En Android 12+ el evento puede no llegar | `NetworkManager.kt:29-47` | `registerDefaultNetworkCallback` | P2 | RIESGO |
| BUG-017 | Seguridad | HTTP plano en 999/5055 sin TLS | Diferido por el dueño | Tráfico y claves móviles en claro | nginx/`docs/deployment/FASE8-INFRA.md` | TLS+HSTS antes de producción | P1 | RIESGO |
| BUG-018 | App | `getLastKnownLocation(PASSIVE)` como "fix fresco" en flavor regular | Implementación AOSP | Fix viejo reportado como nuevo | `AndroidPositionProvider.kt:75-79` | `getCurrentLocation` fresco | P2 | CONFIRMADO |
| HIP-001 | App | Doze congela el `Handler` del servicio (gaps de 90 s-6 min con jornada abierta) | Doze/OEM | Cadencia cae sin aviso | Datos Pilay 27/09 18:21+; Alejandro 15 min | ADR-003 + diagnóstico | P0 | HIPÓTESIS |
| PEND-001 | App | Supervivencia real del FGS por OEM | — | — | — | TESTS 8/9/10 de la fase física | P0 | PENDIENTE |

Sin bugs inventados: todo lo anterior se apoya en archivo:línea o en datos de
`dmt-db` verificados durante la auditoría.
