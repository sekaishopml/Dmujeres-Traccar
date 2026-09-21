# R6_ROUTE_DENSITY.md — Más puntos de ruta (1.1.8, versionCode 118)

## Evidencia que originó el cambio (2026-09-18)

| Device | Puntos | Cadencia en viaje | Proceso |
|---|---|---|---|
| kevin (Samsung A52) | 940 | 5 s (doppler real) | vivo |
| macias (ZTE) | 332 | 5-15 s en ventanas | vivo con huecos |
| miguel (Infinix) | 49 | **15 min exactos** | congelado OEM |
| joseph (HONOR) | 68 | **15 min exactos** | congelado OEM |

Los fixes de joseph/miguel llegan cada 15 min EXACTOS con speed 0.0: es el
`SessionKeeper` despertando el proceso (los despertares funcionan; el OEM lo
congela entre medio). La cadena anti-OEM quedaba en 15 min porque el modo
adaptativo nunca cambia a MOVING sin proceso vivo. Ese era el techo real de
puntos, no el GPS.

## Cambios (mínimos, sobre mecanismos existentes)

1. **Keeper 2 min con jornada activa** (`ForegroundGuardPolicy.keeperPeriodMs`):
   antes 2 min solo en MOVING; ahora siempre con jornada. Efecto medido y
   predecible: ~4 pts/h → ~30 pts/h por dispositivo congelado. La cadena
   re-arma con el mismo periodo (`SessionKeeperReceiver`), antes degradaba a 15.
2. **Keeper rápido por desplazamiento real** (`keeperFastByDisplacement`): un fix
   ralo (≥90 s) con ≥100 m de desplazamiento real mantiene la cadena a 2 min;
   quieto vuelve a 15 min (batería).
3. **Acelerómetro cableado a `MovementStartPolicy`** (antes
   `sensorMovement = false`): con sensor MOVING (histéresis propia) el motor
   pasa a captura densa (5 s) aunque la velocidad GNSS sea 0 (tráfico lento,
   fixes fused). Nunca produce coordenadas.
4. **Acelerómetro vivo con GPS caído** (watchdog): con jornada activa y sin fix
   fresco NO se pausa el sensor — es la única evidencia de movimiento para el
   rescate R5. Vuelve a pausarse cuando hay fix y quietud real.
5. **Ancla estacionaria solo con fix fresco** (bug): un tick del watchdog con
   fix ralo reseteaba el ancla y el desplazamiento nunca acumulaba.

## Invariantes preservados
Sin coordenadas sintéticas, sin dead reckoning, sin tocar Room/Outbox/HTTP/
MQTT/FCM/Replay/segmentación. El sensor solo cambia la ESTRATEGIA de captura.

## Tests
- `ForegroundGuardPolicyTest`: keeper 2 min con jornada, 15 sin jornada,
  desplazamiento real → rápido, dt corto/quieto → no.
- `MovementStartPolicyTest` (11) y R5 (17) siguen verdes.
- Suites: móvil 646/0, server 839/0, dashboard 142/0.

## Limitaciones
Si el OEM bloquea también las alarmas inexactas, ningún cambio local lo evita;
la guía OEM (auto-inicio/restricciones) sigue siendo necesaria para cadencia
de 5-10 s como kevin. El costo de batería sube solo durante jornada activa.

---

## Anexo 1.1.9 — Configuración automática por fabricante (3 subagentes)

Investigación paralela (Infinex/Tecno, HONOR/Huawei, ZTE/Xiaomi) con fuentes
verificadas en `docs/audit/OEM_RESEARCH_*.md`. Implementado en `VendorSettings.kt`:

| OEM | Primario (VERIFIED) | Fallbacks en cadena |
|---|---|---|
| HONOR (MagicOS) | `com.hihonor.systemmanager/...StartupNormalAppListActivity` | appcontrol → ProtectActivity → huawei legacy (3) |
| Infinix/Tecno | `com.transsion.phonemaster/...AutoStartActivity` | batterylab → PowerSavaMain → phonemanager → MediaTek |
| ZTE | `com.zte.powersavemode/...AppSmartOptimizeActivity` | detail → HighPowerApplications |
| Xiaomi | `com.miui.securitycenter/...AutoStartManagementActivity` | PermissionsEditor → powerkeeper |
| Samsung | Never sleeping apps (lool) | app details (ya existía) |

- `OnboardingActivity` recorre la cadena completa con try/catch y termina en
  Ajustes de la app; nunca declara un ajuste como aplicado (solo abre la pantalla).
- `<queries>` añadido al manifest (Android 11+ exige visibilidad de paquetes).
- Guías de pasos actualizadas con rutas verificadas (incluye candado en Recientes).
- Test nuevo: `VendorIntentChainTest` (6) congela los componentes.
- Hallazgo honesto: "Gestión inteligente" ZTE SÍ es abrible (la nota previa de
  "protegida por permiso de sistema" no tenía evidencia); si la ROM la bloquea,
  la cadena cae a detalle/alto consumo/Ajustes.
- No verificable por API: estado real del autostart, candado de recientes;
  se mantiene la regla DETECT→GUIDE→REPORT (sin prometer inmunidad OEM).


---

## ERRATA (20-sep-2026, revisión F0 del prompt ≥90 %)

1. **Cadencia**: este documento mencionaba **5 s** en movimiento; el código
   vigente en 1.1.21 usa **`MOVING_INTERVAL_SECONDS = 10`** y el clamp remoto
   `coerceIn(10, 600)`. La fase F1 del plan alineará app y doc a **5 s en
   movimiento** (E5). Hasta entonces, la referencia real es 10 s.
2. **`GPS_DISABLED`**: NO significa "fallo del proveedor fused" ni "GPS
   apagado". `TrackingStatePolicy` lo emite con "sin fix fresco" (el proveedor
   puede estar sano y el fix simplemente no llegar: congelamiento OEM, quietud,
   cielo tapado). Renombrado a **`NO_FRESH_FIX`** (alias legacy al leer) con
   etiqueta honesta "Sin fix fresco de GPS" (E7).
