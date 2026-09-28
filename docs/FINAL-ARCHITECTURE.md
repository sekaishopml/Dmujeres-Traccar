# Arquitectura final — DMujeres Tracking

Estado: **CERRADA** (2026-09-28). Este documento sustituye a
`docs/app/ARQUITECTURA-2.1.74.md`, que queda como borrador histórico.
La implementación puede estar pendiente (IA-2), pero la arquitectura no se
vuelve a debatir: las decisiones están en `docs/ADR/` y los pendientes en
`docs/audit/IMPLEMENTATION-BACKLOG.md`.

Regla de oro: **nunca fabricar datos**. Cuando haya dos alternativas —(A)
parecer que funcionó, (B) mostrar qué se registró, qué se reconstruyó y qué
está degradado— se elige B.

---

## 1. Resumen ejecutivo

La plataforma ya opera (receptor, API, panel, recuperación, ruteo, respaldo) y
el corte del legado está hecho. Lo que falta para considerarla final es
**cerrar los huecos de confiabilidad de datos y de continuidad**:

- La app captura bien con pantalla encendida y cadencia densa, pero el
  movimiento lo decide el **acelerómetro** (con el GPS como señal secundaria):
  un viaje en marcha constante puede quedar en cadencia lenta (caso Pilay).
- El almacén local no tiene **identidad de evento** (boot_id + secuencia):
  una retransmisión puede duplicar posiciones (no hay UNIQUE en el servidor).
- La **posición actual puede retroceder** cuando llega un paquete atrasado
  (el upsert no compara tiempos), y `ultima_conexion_en` también.
- La cola de subida **no clasifica errores HTTP** (reintenta un 404 para
  siempre) y usa 30 s fijos sin backoff ni jitter.
- El replay mezcla tramos **reales y estimados** con el mismo estilo.
- Faltan: particiones proactivas, validación de fechas absurdas, reconciliación
  de jornada cliente↔servidor, y diagnóstico de salud con causa en el panel.

La arquitectura final (secciones 3-15) define exactamente cómo se resuelve
cada punto, qué se conserva y qué se descarta. Los 16 casos de prueba y la
matriz Android/OEM quedan para la fase física (IA-2), con procedimiento
reproducible en `docs/AI-HANDOFF.md`.

---

## 2. Alcance implementado vs documentado (discrepancia resuelta)

| Pieza | Estado real | Evidencia |
|---|---|---|
| Plataforma nueva (tracking/api/web/recuperación/ruteo) | **Implementada y operando** | servicios systemd, E2E 26/26 |
| Tramos estimados por calles | **Implementada** (routing + API + web) | `services/routing/`, `services/api/src/ruteo.js` |
| Retiro del legado (FASE 12) | **Implementada** | `docs/operations/FASE12-RETIRO.md` |
| Consolidación de equipos | **Implementada** | `docs/operations/CONSOLIDACION-2026-09-28.md` |
| `ARQUITECTURA-2.1.74.md` | **Solo diseño** (borrador; este documento lo sustituye) | cabecera del propio archivo |
| Alarmas de recuperación en app | **No implementado** | no hay `AlarmManager` en `fallback/` |
| Idempotencia (boot_id/secuencia) | **No implementado** | `services/tracking/src/db.js:168`, SQLite sin columnas |
| Guarda de posición actual | **No implementado** (bug) | `services/tracking/src/db.js:191-222` |
| Jornada cliente↔servidor (GET/reconciliar) | **No implementado** | solo `POST /api/mobile/v1/journey` |
| Replay REAL/MATCHED/ESTIMATED | **Parcial**: real y estimado; falta map-matching y separación visual | `apps/web/src/paginas/operacion/replay.ts` |

La versión distribuida por OTA es **2.1.73 (versionCode 283)**; el repo tiene
**2.1.72 (282)**: la release no está íntegramente en git. IA-2 debe bumper y
commitear el source exacto (ver backlog P1-REL-001).

---

## 3. Arquitectura de componentes

```
┌──────────────────────────── ANDROID (flavor google) ────────────────────────────┐
│  MainActivity/Onboarding ── Readiness Gate ──► JourneyManager                   │
│        (acción visible del usuario para abrir jornada)                          │
│                                                                                 │
│  TrackingService (FGS type=location, notificación persistente)                  │
│    └─ TrackingController  ← única autoridad del tracking                        │
│         ├─ MovementStateMachine (GPS+distancia+tiempo+IMU+historial)            │
│         ├─ PositionProvider (FusedLocationProviderClient / LocationManager)     │
│         ├─ LocalPositionStore (SQLite: posición + meta de secuencia/boot)       │
│         ├─ UploadQueue (serial, backoff+jitter, clasifica 2xx/4xx/retry)        │
│         ├─ RecoveryEngine (watchdog + AlarmManager 9 min + boot/update)         │
│         ├─ DiagnosticsReporter (latido 10 min)                                  │
│         └─ NetworkManager (registerDefaultNetworkCallback)                      │
│                                                                                 │
│  FCM (dormant) ──► RecoveryEngine.despertar()   AutostartReceiver (boot/update) │
└─────────────────────────────────────────────────────────────────────────────────┘
              │ HTTPS (captura local primero; subida después)
              ▼
┌──────────────────────────── SERVIDOR (Node 24) ────────────────────────────────┐
│  dmj-tracking :5055   OsmAnd legacy (1 fix/req) + /api/mobile/v1/*              │
│    ├─ /config  /journey (GET+POST)  /diagnostics  /ota  /fcm-token              │
│    ├─ /positions (batch nuevo, idempotente)  /recovery-ack                      │
│    └─ Ingesta: validar → dedupe (device,boot,seq) → insertar → actualizar viva  │
│  dmj-api :8081        /api/v1 (panel) + replay + jornadas + reportes            │
│  dmj-routing :8992    GraphHopper: /route (A→B) · /match (map matching)         │
│  dmj-recuperacion     probes FCM + auditoría de silencio                        │
│  dmj-web :25565       panel React (vite build)                                  │
│  dmt-db               PostgreSQL 18.6 + TimescaleDB (particiones mensuales)     │
└─────────────────────────────────────────────────────────────────────────────────┘
```

Separación estricta de responsabilidades: **TRACKING** (captura y persistencia
local), **SYNC** (subida), **RECOVERY** (recuperar continuidad), **DIAGNOSTICS**
(observar y explicar). Ningún componente fuera de `TrackingController` manda
sobre la cadencia.

### Flujo Android → Servidor

```
fix Fused ──► filtros (accuracy/tiempo/distancia/ángulo)
   └─ aceptado ──► LocalPositionStore (seq++, status=PENDING)
        └─► UploadQueue: POST (1 fijo o lote de N)
              ├─ 2xx ──► marcar CONFIRMED ──► borrar del store
              ├─ 404/400/413 ──► DEAD (no reintentar; reportar en diagnóstico)
              ├─ 401 ──► pausar cola y avisar (clave móvil inválida)
              ├─ 408/429/5xx/red ──► backoff exp + jitter ──► reintentar
              └─ servidor: dedupe (device,boot,seq) → almacenar → viva (si es más nueva)
```

---

## 4. Principios de continuidad (lo que Android garantiza y lo que no)

- **Garantizable**: FGS location con notificación visible mantiene la captura
  con pantalla apagada y fuera de Doze mientras el sistema respete el FGS.
- **No garantizable**: OEM agresivos (Infinix/Xiaomi/Huawei/…) pueden matar el
  proceso, congelar el GPS o negar el arranque en segundo plano. La app **no
  promete continuidad absoluta**: la maximiza y **reporta la degradación**.
- **UNKNOWN ≠ STATIONARY**: sin evidencia suficiente se captura en cadencia
  fina (ACTIVE-seguro). STATIONARY exige evidencia consistente.
- La captura **nunca depende de la red**: primero disco, después subida.
- La alarma de recuperación **no es el reloj de tracking**: es el rescate.

---

## 5. Estrategia GPS y cadencia

| Estado | Cadencia de captura | Precisión | Notas |
|---|---|---|---|
| ACTIVE | 5-10 s (config `mobile.intervalSeconds`, 10 por defecto) | HIGH | sin batching |
| STATIONARY | 30-120 s (120 por defecto) | BALANCED | sin batching |
| RECOVERING | 5-10 s temporal (hold 3 min) | HIGH | tras rescate o evidencia de movimiento |
| DEGRADED | la cadencia vigente + diagnóstico | según estado | no cambia por sí sola |

`CAPTURE ≠ UPLOAD`: la subida puede ir en lote y con retraso; la captura no se
detiene por red ausente. Sin batching que degrade la fidelidad (decisión ya
vigente desde 2.1.66: `STATIONARY_MAX_UPDATE_DELAY_MS = 0`).

---

## 6. Máquina de estados (movimiento y recuperación)

Estados: `STOPPED | STARTING | ACTIVE | STATIONARY | DEGRADED | RECOVERING`.

```
STOPPED ──(jornada abierta por usuario)──► STARTING
STARTING ──(primer fix aceptado)──► ACTIVE          (cadencia fina)
ACTIVE ──(≥3 min sin desplazamiento real y sin IMU)──► STATIONARY
STATIONARY ──(velocidad ≥3 kn · distancia ≥150 m · IMU significant · giro)──► ACTIVE
ACTIVE|STATIONARY ──(sin fixes > umbral · GPS apagado · sin permiso)──► DEGRADED
DEGRADED ──(alarma/FCM/boot/red)──► RECOVERING
RECOVERING ──(fix fresco almacenado y cola fluyendo)──► ACTIVE|STATIONARY
cualquiera ──(fin de jornada)──► STOPPED
```

Evidencia de movimiento (prioridad):
1. **GPS speed** (≥ 3 kn ≈ 5,5 km/h) — manda siempre.
2. **Desplazamiento acumulado** entre fixes (≥ 150 m) — suple velocidad nula.
3. **Significant motion** (Android 14+) y giroscopio en movimiento.
4. **Acelerómetro** (`MotionMonitor`) — solo como último indicio, y nunca por
   sí solo para volver a STATIONARY si el GPS dice que hay desplazamiento.
5. **Historial inmediato** persistido: al recrear el proceso, el estado se
   reconstruye de los últimos fixes (nunca "en memoria = válido").

Regla anti-deadlock (caso Pilay): si no hay fix nuevo, el estado **no se
degrada a lento por el sensor**; se mantiene la cadencia fina hasta tener
evidencia; y a los 4 min sin fix entra `RECOVERING` (re-solicitar, GPS del
sistema, alarma).

Tras **process recreation**: leer meta + últimos fixes → estado; arrancar en
ACTIVE-seguro durante 3 min. Tras **reboot**: `AutostartReceiver` rearma
alarma; si había jornada abierta persistida, `RECOVERING` y reconciliación con
el servidor (`GET /journey`).

---

## 7. Identidad de evento e idempotencia

Identidad estable: `(device_id, boot_id, local_sequence)`.

- `boot_id`: UUID aleatorio por arranque **de proceso** (no por boot del
  teléfono), guardado en `meta` del SQLite; sobrevive process recreation.
- `local_sequence`: entero **persistente** que solo incrementa (no se reinicia
  ni en reboot), guardado en `meta`.
- Nunca se usa el timestamp como identidad.

Servidor: `tracking.dmt_posicion` gana `boot_id text`, `local_sequence bigint`
y `UNIQUE (dispositivo_id, registrado_en, boot_id, local_sequence)` parcial
(`WHERE boot_id IS NOT NULL`; el `registrado_en` lo exige el particionado y
no debilita el dedupe porque la retransmisión trae el mismo captured_at).
Ingesta con `ON CONFLICT DO NOTHING RETURNING` →
clasifica `accepted | duplicate`. Reintentos del mismo evento no duplican.
Eventos atrasados se aceptan; `captured_at` (`fijado_en/registrado_en`) y
`received_at` (`recibido_en`) se conservan separados (ya es así).

**Regla de posición viva** (corrige bug confirmado): el upsert de
`dmt_posicion_actual` y `ultima_conexion_en`/`ultima_posicion_id` solo se
aplica si `EXCLUDED.registrado_en > actual.registrado_en` (comparación por
tiempo de captura). Un paquete atrasado nunca retrocede la posición viva.

---

## 8. Almacén local y cola de subida

SQLite local (migración v5):

```sql
position(id INTEGER PK AUTOINCREMENT, device_id TEXT, boot_id TEXT,
         local_sequence INTEGER, journey_id TEXT, captured_at INTEGER,
         received_at INTEGER, lat REAL, lon REAL, alt REAL, speed REAL,
         course REAL, accuracy REAL, battery REAL, charging INT, mock INT,
         provider TEXT, movement_state TEXT, status TEXT, attempts INT)
meta(clave TEXT PK, valor TEXT)   -- boot_id, ultima_secuencia, journey activo
```

- Cada fix aceptado se **persiste antes de intentar subir**.
- Cap del buffer: 5000 eventos; al superarse se descarta el más viejo **y se
  reporta** (`buffer_overflow` en diagnóstico) — nunca en silencio.
- Subida serial estricta `read → send → ack → delete → read` (sin carreras).
- Clasificación HTTP: `2xx` confirmado · `400/404/413` DEAD (no reintenta,
  reporta) · `401` pausa y avisa · `408/429/5xx/red` retry.
- Backoff exponencial con jitter: base 5 s, ×2, tope 5 min.
- Wake lock **solo** durante el envío, con timeout de 60 s (ya implementado,
  se conserva).

---

## 9. Recovery Engine

Cuatro disparadores, una sola autoridad (`RecoveryEngine`):

1. **Watchdog interno** (Handler, 60 s): sin fix 4 min → re-solicitar; 2
   intentos → GPS del sistema. (ya existe; se conserva)
2. **Alarma** `setAndAllowWhileIdle` a 9 min: rearma en servicio,
   `BOOT_COMPLETED` y `MY_PACKAGE_REPLACED`. No es reloj: es rescate.
3. **FCM high priority**: servidor detecta silencio → probe → app despierta,
   pide fix fresco y vacía cola (ya existe en el servidor, ack auditado).
4. **Boot/replace**: rearma alarma y, si hay jornada abierta persistida,
   reconcilia con `GET /journey`.

Al recuperar: fix fresco (`getCurrentLocation`, nunca `getLastKnown`), flush de
cola, y transición a RECOVERING→ACTIVE/STATIONARY. Cada recuperación cuenta
(`recovery_count`) y se reporta.

---

## 10. Jornada

`JourneyManager` persiste `journey_id` + `started_at` en `meta` y los eventos
`Started/Ended` en el servidor (ya existen). Nuevo: `GET /api/mobile/v1/journey`
que devuelve el estado del servidor (abierta/cerrada, inicio) para que tras
recrear proceso/reboot el cliente **reconcilie** (continuar o cerrar) sin
depender de RAM. El servidor sigue con su reconciliación horaria actual.

---

## 11. PostgreSQL

- Se conserva **PostgreSQL 18.6 + TimescaleDB**; **no se añade PostGIS** (no
  hay consultas espaciales que lo justifiquen; distancias por haversine y
  geocercas no se usan).
- Particiones mensuales de `dmt_posicion` y `dmt_evento`: creación automática
  on-write ya existe (`services/tracking/src/db.js` `#asegurarParticion`); se
  añade **precreación proactiva** (mes actual + 2 siguientes al arrancar y
  job diario) y **retención** definida con el dueño.
- Validación de fechas: `captured_at` > ahora+24 h o < ahora−30 d → inválida
  (evita la partición basura 2037_10 detectada en producción).
- Índices actuales suficientes (device+time, pkey, BRIN por tiempo); añadir
  índice único de idempotencia y `(journey_id, time)` cuando exista.
- `VACUUM/ANALYZE`: autovacuum por defecto + job semanal de `ANALYZE`.
- Monitoring: `pg_stat_user_tables` + tamaño de particiones en `/api/v1/sistema`.

---

## 12. Ingesta y API móvil

- OsmAnd legacy: se mantiene intacto (contrato congelado de la APK en calle).
- Nuevo `POST /api/mobile/v1/positions` (lote JSON, clave móvil): idempotente,
  responde por evento (`accepted/duplicate/invalid/dead`), hasta 500 eventos.
- `GET /api/mobile/v1/journey` (nuevo) para reconciliación.
- Todo lo demás (`config`, `diagnostics`, `ota`, `fcm-token`, `recovery-ack`)
  se conserva.

---

## 13. Replay, routing y reconstrucción

Tres semánticas **separadas y visibles** (ADR-007):

| Capa | Cuándo | Estilo |
|---|---|---|
| **REAL** | fixes registrados | línea sólida coloreada por velocidad + chevrones (actual) |
| **MATCHED** | hueco con ≥2 fixes suficientes para map matching | línea continua fina con patrón propio + etiqueta "ajustado a vía" |
| **ESTIMATED** | hueco sin observaciones (ruta A→B) | **punteado gris + etiqueta "tramo estimado"** |

La decisión estética previa ("que parezca ruta normal") queda **revocada** por
la regla de oro: un tramo estimado jamás se presenta como GPS registrado.
El API renombra `estimados` → `reconstruidos` con `metodo` y
`mapaVersion`/`algVersion`; el replay dibuja según método y muestra leyenda.

GraphHopper (`dmj-routing`): tres operaciones —`/route` (A→B), `/match` (map
matching; código fuente y grafo existen), `gap reconstruction` (usa `/match`
si hay fixes, `/route` si no). Cache por endpoints + perfil + versión de grafo
+ versión de algoritmo. Sin respuesta → se dibuja recta punteada (nunca se
inventa).

---

## 14. Diagnóstico y observabilidad

La app reporta (latido 10 min y en eventos): versión, Android, fabricante,
modelo, batería/carga, GPS, permisos (fino/fondo/notificaciones), optimización
de batería, FGS, jornada, buffer (pendientes, overflow), último fix, red,
`boot_id`, `recovery_count`, `movement_state`.

El servidor calcula: `last_fix_age`, `capture_gap`, `upload_lag`,
`buffer_depth`, `offline_duration`, cadencia esperada vs efectiva, y deriva:

`HEALTHY · DEGRADED · OFFLINE · RECOVERING · MISCONFIGURED`

El panel muestra el estado **con la causa** ("Último GPS hace 8 min",
"Optimización de batería activa", "Fondo sin permiso"), nunca un OFFLINE mudo.

---

## 15. Seguridad y entrega

- Producción solo HTTPS/TLS + HSTS; cookies `HttpOnly; Secure; SameSite=Lax`
  (pendiente de dominio, diferido por el dueño; queda como gate de entrega).
- CSP básica en Nginx; límites de tamaño y rate limit en Nginx.
- Quitar credenciales de prueba del repo (`cctv2026` en
  `scripts/validation/e2e.sh` y docs) y usar variables de entorno en E2E.
- Keystore de release **fuera del repo** (ya es así): entregarlo por canal
  seguro y documentar su custodia; evaluar migrar de `debug.keystore` a clave
  de flota propia si la empresa lo exige (requiere reinstalación).
- El release (APK+source) debe commitearse: versionCode 284 / 2.1.74 con el
  source exacto y el manifiesto OTA actualizado.

---

## 16. Riesgos que quedan tras cerrar la arquitectura

1. OEM que mate el FGS: mitigado (alarma/FCM/diagnóstico), **no eliminable**.
2. GPS sin cielo/indoor: huecos inevitables → reconstrucción honesta.
3. Reloj del teléfono alterado: validación de fechas evita polución, pero un
   reloj adelantado puede retrasar la aceptación hasta corrección.
4. Keystore fuera de git: riesgo de pérdida (documentar custodia).
5. TLS pendiente: no salir a producción sin él.
6. Doze profundo >9 min: hueco máximo asumido 9-11 min con cobertura; se
   documenta y se mide (no se promete menos).

## 17. Pruebas de la fase física (IA-2)

Los 16 TESTS del encargo (8 h, 12 h, sin cobertura, 30 min offline, 2 h
offline, process recreation, reboot, doze ADB, battery optimization, OEM,
duplicado, fuera de orden, replay huecos/continuo/paradas/jitter) y la matriz
Android 10-16 × fabricantes quedan **pospuestos**: requieren dispositivos
reales. El procedimiento reproducible está en `docs/AI-HANDOFF.md` (§ Testing).
