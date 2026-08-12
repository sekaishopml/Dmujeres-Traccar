# ADR-002 — Canal de ingesta GPS móvil

**Fecha**: 2026-08-12  
**Estado**: ACEPTADA para Fase 2.1  
**Decisor**: arquitectura DMujeres Traccar

## Contexto

La aplicación Android debe enviar posiciones frecuentemente desde redes móviles
inestables. La entrega debe tolerar reconexiones, duplicados, almacenamiento offline
y cambios entre Wi-Fi/4G/5G. Traccar ya contiene:

- `BaseMqttProtocolDecoder` para algunos protocolos MQTT de dispositivos;
- `PositionForwarderMqtt` para forwarding server -> broker;
- WebSocket `/api/socket` para realtime server -> dashboard;
- el pipeline común `ProcessingHandler` -> `DatabaseHandler` -> `PostProcessHandler`.

Ninguno de esos componentes, sin extensión, ofrece un ACK de negocio posterior a la
persistencia ni deduplicación fuerte por mensaje de aplicación.

## Decisión

Adoptar una arquitectura híbrida:

1. **Canal primario móvil**: MQTT 5 sobre TLS, QoS 1, hacia EMQX.
2. **Confirmación de negocio**: ACK propio después de validar, deduplicar y persistir.
3. **Fallback**: HTTPS batch usando exactamente el mismo envelope e idempotencia.
4. **Salida dashboard**: WebSocket existente `/api/socket`; no usarlo como uplink móvil.
5. **Persistencia**: PostgreSQL/TimescaleDB como autoridad de deduplicación; Redis no
   será la única garantía de idempotencia.
6. **Integración Traccar**: adapter de ingesta dentro del monolito que convierta el
   envelope en `Position` y entre al pipeline común. No duplicar `DatabaseHandler`,
   `PostProcessHandler` ni los handlers de eventos.

La tabla de deduplicación conserva `positionid` como referencia lógica, pero no crea
un FK PostgreSQL hacia `tc_positions`: en la auditoría real de TimescaleDB 2.29.1,
`tc_positions` es hypertable y su `id` no satisface una constraint única compatible
con una FK externa. La integridad se comprobará dentro del servicio transaccional.

La semántica es **at-least-once con deduplicación**, no exactly-once real. No se
promete pérdida cero. La garantía operativa será válida mientras el mensaje exista
en la cola local/broker y haya almacenamiento disponible.

## Comparativa

| Criterio | MQTT QoS1 | WebSocket | HTTP batch | Híbrido |
|---|---|---|---|---|
| Redes móviles/NAT | Bueno | Requiere lógica propia | Muy compatible | Mejor cobertura |
| Offline queue | Room propia | Totalmente propia | Room propia | Compartida |
| ACK de negocio | Hay que añadirlo | Hay que diseñarlo | HTTP 2xx no basta | Común |
| Duplicados | Posibles | Posibles | Posibles | Dedupe común |
| Batería | Buena | Variable | Peor con requests frecuentes | MQTT primario |
| Integración upstream | Adapter nuevo | Endpoint nuevo | Endpoint nuevo | Adapter + fallback |
| Coste operativo | Medio | Alto | Medio | Alto inicial, menor riesgo |

## Envelope v1

Topic primario:

```text
dmj/v1/devices/{deviceId}/telemetry
```

ACK:

```text
dmj/v1/devices/{deviceId}/ack
```

Payload mínimo:

```json
{
  "schema": 1,
  "type": "position",
  "messageId": "01J...ULID",
  "deviceId": "device-123",
  "sequence": 18452,
  "sentAt": "2026-08-12T14:30:00.123Z",
  "observedAt": "2026-08-12T14:29:58.900Z",
  "payload": {
    "latitude": -33.45,
    "longitude": -70.67,
    "accuracy": 8.2,
    "speed": 12.4,
    "bearing": 180.0,
    "altitude": 520.0
  }
}
```

Reglas:

- `messageId` se conserva durante todos los reintentos.
- `sequence` es monotónica por dispositivo y persistida en Room.
- `observedAt` es el tiempo GPS; `receivedAt` lo genera el servidor.
- No ordenar únicamente por el reloj del dispositivo.
- El cliente comienza con un máximo de 1 mensaje en vuelo; se podrá elevar a 4 tras
  medir latencia y memoria.
- No usar MQTT retained para posiciones históricas.

ACK de aplicación:

```json
{
  "schema": 1,
  "type": "ack",
  "deviceId": "device-123",
  "messageId": "01J...ULID",
  "sequence": 18452,
  "status": "accepted",
  "serverReceivedAt": "2026-08-12T14:30:02.500Z"
}
```

Estados de ACK: `accepted`, `duplicate`, `rejected`, `invalid`, `expired`.
El cliente elimina de Room `accepted` y `duplicate`; conserva los demás para
diagnóstico y política de reintento.

## Deduplicación

La clave lógica primaria será `(deviceId, sequence)` y `messageId` tendrá un índice
único adicional. El servidor debe:

- insertar una posición una sola vez;
- devolver `duplicate` para el mismo mensaje repetido;
- registrar anomalía si un `sequence` ya existente llega con payload distinto;
- conservar posiciones tardías con su `observedAt`;
- medir saltos de secuencia sin bloquear indefinidamente por mensajes ausentes.

La implementación de la garantía será PostgreSQL/TimescaleDB, no sólo memoria o Redis.

## Criterios de carga

Supuesto inicial: una posición cada 10 segundos y pico de reconexión 5x.

| Dispositivos | Nominal | Pico 5x | Objetivo de primera medición |
|---:|---:|---:|---|
| 1 | 0.1 msg/s | 0.5 msg/s | funcionalidad completa |
| 10 | 1 msg/s | 5 msg/s | reconexión y duplicados |
| 100 | 10 msg/s | 50 msg/s | latencia/backpressure |
| 1000 | 100 msg/s | 500 msg/s | límites broker/consumidor/BD |

Estos son objetivos de prueba, no resultados. Los resultados deben registrar latencia
p50/p95/p99, throughput, mensajes aceptados/duplicados/rechazados, backlog, CPU, RAM,
escrituras de BD y errores.

## Seguridad

Producción exigirá TLS 8883, ACL por dispositivo, credencial única o certificado por
dispositivo, rotación/revocación, límites de tamaño/frecuencia y conexiones anónimas
desactivadas. El dashboard de EMQX no se expondrá públicamente.

## Alternativas descartadas

- **WebSocket como uplink**: el endpoint actual es dashboard server -> cliente y no
  tiene cola, ACK de negocio ni reanudación.
- **HTTP como canal único**: es robusto y útil como fallback, pero menos eficiente para
  tracking continuo y reconexiones frecuentes.
- **Reutilizar `PositionForwarderMqtt` como ingestión**: es server -> broker, no
  subscribe/consume; además confirma broker, no persistencia downstream.
- **Exactly-once**: no es una garantía real extremo a extremo en redes móviles; se
  implementará at-least-once + idempotencia observable.

## Consecuencias

Se requiere implementar un consumidor/adapter MQTT, contrato de ACK, tabla o extensión
de deduplicación, endpoint HTTP batch, métricas y simulador. La decisión se puede
revisar después de las pruebas de carga y reconexión; no se construye Android hasta que
el contrato y el pipeline servidor tengan pruebas de integración.
