# OEM_COMPATIBILITY.md — Matriz OEM basada en evidencia (FASE 9/11)

Sin pruebas reales no hay PASS. Esta matriz solo declara lo observado; el resto
es `NOT_TESTED` o `UNKNOWN`. La compatibilidad es **basada en capacidades**
(§46): la app detecta, diagnostica, recupera y guía; nunca promete vencer al
sistema operativo.

## 1. Estados objetivo (§47)

`TRACKING_CONTINUITY_PASS/FAIL`, `RECOVERY_PASS/FAIL`, `SCREEN_OFF_PASS/FAIL`,
`DOZE_PASS/FAIL`, `OUTBOX_PASS/FAIL` — se registran por dispositivo con
evidencia (positions, health snapshots, recovery events).

## 2. Perfil de capacidad

`DeviceCapabilityProfile` (mobile) clasifica:

| Estado | Condición |
|---|---|
| `UNSUPPORTED` | sin GNSS |
| `DEGRADED` | background-restricted, bucket RESTRICTED o fabricante con freezer confirmado |
| `SUPPORTED_WITH_GUIDANCE` | guía OEM mapeada o batería no exenta |
| `SUPPORTED` | sin gate detectado |

`OemGuidanceProvider` decide la acción (`NONE/CONFIGURE/VERIFY/MANUAL`) sin
`if Xiaomi/Samsung/ZTE` dispersos. Guías vigentes en `VendorSettings`:
Xiaomi/Redmi/POCO, Samsung, Honor/Huawei, Infinix, Tecno, ZTE.

## 3. Matriz real (2026-09-16)

| Fabricante | Modelo real | ROM/Android | Evidencia | Continuidad | Recovery | Screen-off | OEM status |
|---|---|---|---|---|---|---|---|
| ZTE | Z2450 (`qa-f0`) | MyOS 14 | cfreezer confirmado (frozen-list, unfreeze `reason=screen_on`); gap 10h29 en jornada real; outbox 747 replay 100% | FAIL (9.02%) | RECOVERY_SENT/TIMEOUT y SUCCESS en eventos | FAIL (freezer) | **OEM_LIMITATION (cfreezer)** |
| Infinix | X6531 (`santiago`) | XOS (app 1.0.101) | sin token FCM registrado; app desactualizada | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN (requiere actualización) |
| Samsung | — | — | sin equipo físico | NOT_TESTED | NOT_TESTED | NOT_TESTED | UNKNOWN |
| Xiaomi/Redmi/POCO | — | — | guía implementada, sin equipo | NOT_TESTED | NOT_TESTED | NOT_TESTED | UNKNOWN |
| Honor/Huawei | — | — | guía implementada, sin equipo | NOT_TESTED | NOT_TESTED | NOT_TESTED | UNKNOWN |
| OPPO/Realme/Vivo | — | — | sin guía específica (no hay evidencia de gate) | NOT_TESTED | NOT_TESTED | NOT_TESTED | UNKNOWN |
| Motorola/Pixel/Nokia | — | — | sin evidencia de gate | NOT_TESTED | NOT_TESTED | NOT_TESTED | UNKNOWN |
| Tecno | — | — | guía implementada, sin equipo | NOT_TESTED | NOT_TESTED | NOT_TESTED | UNKNOWN |

## 4. ZTE cfreezer: qué hace el producto

1. **Detectar**: `DeviceCapabilityProfile.knownFreezerBehavior=true`; huellas en
   el servidor (gap + recovery events + health snapshots).
2. **Diagnosticar**: `MobileSilenceMonitor` + continuidad + timeline (causa
   probable `OEM_PROCESS_FREEZE`, confianza según evidencia).
3. **Recuperar sin hacks**: alarma SessionKeeper (2/15 min) y FCM recovery;
   si Android bloquea el arranque en segundo plano → notificación **"Toca para
   reanudar"** (acción de usuario, nivel 5 de la escalera).
4. **Guiar**: deep link confirmado
   `com.zte.powersavemode/.appsmartoptimizer.AppSmartOptimizeActivity`
   con fallback a Ajustes; doble botón (pantalla OEM + página de la app).
5. **Ser honesto**: el estado del equipo es `DEGRADED`/`OEM_LIMITATION`, no
   "compatible universal".

## 5. Reglas para OEM futuros (§77/§78)

- Primero `capability detection` (`DeviceCapabilityProfile.read`).
- Solo se crea lógica específica con evidencia real de diferencia.
- Un Android nuevo entra por defecto en `SUPPORTED`/`UNKNOWN` con diagnóstico
  claro; nunca se marca `UNIVERSALLY SUPPORTED`.
