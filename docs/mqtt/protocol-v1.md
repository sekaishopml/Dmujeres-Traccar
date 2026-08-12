# Protocolo móvil DMujeres v1

## Estado

Contrato inicial para simulador y adapter de servidor. No es todavía un contrato
público para la aplicación Android.

## Transporte

- Primario: MQTT 5, QoS 1, TLS en producción.
- Topic de subida: `dmj/v1/devices/{deviceId}/telemetry`.
- Topic de ACK: `dmj/v1/devices/{deviceId}/ack`.
- Fallback: `POST /api/mobile/v1/positions:batch` sobre HTTPS.
- Ambos transportes usan el mismo JSON y las mismas reglas de idempotencia.

## Validación de envelope

Requeridos:

- `schema`: entero `1`.
- `type`: inicialmente `position`.
- `messageId`: ULID/UUID, longitud limitada.
- `deviceId`: coincide con identidad autenticada del cliente.
- `sequence`: entero positivo monotónico por dispositivo.
- `sentAt`, `observedAt`: timestamps ISO-8601 válidos.
- `payload.latitude`: `[-90, 90]`.
- `payload.longitude`: `[-180, 180]`.

Opcionales iniciales: `accuracy`, `speed`, `bearing`, `altitude`.

El servidor genera `receivedAt`; nunca confía en un `receivedAt` enviado por el cliente.

## Estados

```text
accepted   persistido por primera vez
duplicate  ya persistido idempotentemente
rejected   autenticado pero rechazado por política
invalid    envelope o coordenadas inválidas
expired    fuera de la ventana de aceptación
```

## Semántica

1. Validar autenticación, topic, tamaño y schema.
2. Resolver el dispositivo.
3. Aplicar idempotencia `(deviceId, sequence)` + `messageId`.
4. Convertir a `org.traccar.model.Position`.
5. Ejecutar el pipeline común de Traccar.
6. Persistir la deduplicación y la posición en una transacción adecuada.
7. Publicar ACK de aplicación.

El PUBACK MQTT no es el ACK de negocio. El cliente sólo considera confirmada una
posición cuando recibe `accepted` o `duplicate`.

## Límites iniciales de seguridad

- Payload máximo: definir en la implementación antes de producción.
- Frecuencia máxima por dispositivo: definir tras baseline de carga.
- Sin retained messages.
- Sin wildcard ACL para clientes móviles.
- Sin credenciales compartidas en producción.
