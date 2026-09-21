# Auditoría gráfica de arquitectura — DMujeres Tracking

Rama de auditoría: `audit/arquitectura-grafica` (base: `feat/f1-capture`, sep-2026).
Todos los diagramas son **Mermaid**: GitHub los renderiza directo al abrir los `.md`.

## Índice

| Documento | Qué audita |
|---|---|
| [APP.md](APP.md) | Capas de la app Android, reglas de dependencia y flujo de captura |
| [SERVER.md](SERVER.md) | Módulos del servidor (fork Traccar 6.14.5), ingesta y modelo de datos |
| [FLUJOS.md](FLUJOS.md) | Secuencias: jornada manual, posición/ack, embudo de salud, rescate, OTA |

## Cómo auditar en 5 minutos

1. **Reglas de capas (se verifican solas)**: `docs/DEPENDENCY_RULES.md` describe las
   aristas prohibidas y `mobile/app/src/test/java/com/dmujeres/traccar/architecture/ArchitectureDependencyTest.kt`
   las hace cumplir en cada `./gradlew :app:testDebugUnitTest`. Si un import viola una
   regla, el test falla con el archivo y el paquete culpable.
2. **Contratos de protocolo**: `core/MobileProtocol.kt` (topics MQTT y endpoints HTTP)
   tiene test de contrato; los paths del servidor viven en `org.traccar.mobile`.
3. **Cada afirmación del sistema es rastreable a un test puro** (políticas en JVM, sin
   Android): filtros, políticas de ventana, embudo, rollout OTA, alertas, reportes.
4. **Datos crudos**: `tc_positions` es la fuente de verdad; nada se interpola como
   "medido" (ver `docs/audit/COBERTURA30_METRIC.md`).

## Invariantes del sistema (no negociables)

- **Sin coordenadas sintéticas**: ninguna capa fabrica o interpola posiciones; los
  sensores solo cambian la *estrategia de captura*.
- **Jornada 100% manual**: iniciar/detener solo por acción del usuario; no hay
  auto-jornada (eliminada por riesgo de auditoría).
- **Trazabilidad honesta**: estados de error explícitos (`NO_FRESH_FIX:<motivo>`,
  `NETWORK_OFFLINE`, `PENDING_ACK_TIMEOUT`, …) y motivo de cada rechazo en el embudo.
- **Sin secretos en git**: claves de firma y `.env` fuera del repositorio.
- **OEM**: prohibido declarar inmunidad; si la meta no se cumple en un equipo se
  documenta `OEM_LIMITATION` con la acción recomendada.
