# ADR-002 MQTT MVP

El consumidor está desactivado por defecto. Para habilitarlo se requiere
`mobile.mqtt.enable=true` y `mobile.mqtt.url`; las credenciales pueden venir de
`mobile.mqtt.username`/`mobile.mqtt.password` o de variables de entorno cuando
`CONFIG_USE_ENVIRONMENT_VARIABLES=true`. También se pueden configurar
`mobile.mqtt.topic`, `mobile.mqtt.ackTopic`, `mobile.mqtt.clientId`,
`mobile.mqtt.maxPayload` y `mobile.mqtt.workerQueue`.

El envelope `payload.speed` se interpreta como km/h y se convierte a nudos con
`UnitsConverter.knotsFromKph`, porque `Position` almacena velocidad en nudos.
El MQTT QoS 1 usa acknowledgement manual y el ACK de aplicación se publica
después de `PositionPipeline` y su persistencia.

El consumidor serializa mensajes por `deviceId`, limita la admisión mediante una cola
y confirma el PUBACK MQTT sólo después de que el publish del ACK de aplicación completa
sin error. Los mensajes en `processing` permanecen pendientes para redelivery; todavía
no existe lease/recovery automático.

`Storage` no permite una transacción atómica entre `tc_positions` y
`tc_mobile_messages`. Por eso la reserva queda en `processing`: si la posición
se guarda y falla la finalización, el mensaje se reentrega y puede producir un
duplicado físico. No se afirma exactly-once; el siguiente incremento debe mover
la reserva y la persistencia a una operación transaccional compatible con
PostgreSQL/TimescaleDB y añadir lease/retry para estados `processing`.

## Estado operativo

El E2E local validado devuelve `accepted` y una redelivery con el mismo payload devuelve
`duplicate`, con una sola posición física. El modo MQTT sigue siendo experimental:

- `mobile.mqtt.enable=false` por defecto;
- autenticación/ACL de EMQX y TLS de producción son obligatorios antes de producción;
- hay que resolver la atomicidad posición+dedupe y la recuperación de reservas antiguas;
- el broker dev permite anónimo y sólo está ligado a localhost.
