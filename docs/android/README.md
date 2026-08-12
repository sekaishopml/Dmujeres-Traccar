# App Android — DMujeres Tracking

Aplicación Kotlin (minSdk 26 / targetSdk 34) para teléfonos corporativos de trabajadores
de campo. Envía la ubicación del teléfono al server usando el canal MQTT QoS1 + ACK +
cola offline definido en `docs/mqtt/protocol-v1.md`.

## Qué hace

- Captura ubicación con **Fused Location Provider** (intervalo configurable, 10 s por defecto).
- **Servicio en primer plano** (tipo `location`) que sigue enviando con la pantalla apagada.
- Publica en `dmj/v1/devices/{deviceId}/telemetry` (QoS 1) y espera el **ACK de aplicación**
  (`accepted`/`duplicate`); solo entonces borra la posición de la cola.
- **Cola offline en Room**: sin conexión guarda todo y reenvía por orden con **backoff
  exponencial** (5 s → … → techo, hasta 30 intentos, sin bloquear el resto de la cola).
- **Watchdog** con estados diferenciados: activo, GPS apagado/sin señal, sin Internet,
  MQTT desconectado, batería baja, permiso faltante, desactivado por el usuario.
- **Reinicio tras boot** (`BOOT_COMPLETED` y actualización de la app) solo si el usuario
  dejó el tracking activo.
- Botón para **activar/desactivar** tracking (decisión del usuario, estado persistente).

## Estructura

```
app/src/main/java/com/dmujeres/traccar/
├── MainActivity.kt            UI: servidor, ID, toggle, estado
├── DmujeresApp.kt             Application (Room)
├── config/AppConfig.kt        configuración persistida + secuencia
├── location/TrackingService.kt servicio foreground + watchdog
├── location/TrackingState.kt  estados del watchdog
├── mqtt/MqttManager.kt        Paho MQTT QoS1 + ACK + cola
├── mqtt/Envelope.kt           envelope v1 (orden de campos fijo)
├── db/                        Room: cola offline
├── receiver/BootReceiver.kt   arranque tras reboot
└── util/Notifications.kt      canal + notificación foreground
```

## Compilar

Requisitos: JDK 17+ y Android SDK (platform 34 + build-tools 34).

```bash
cd mobile
# opción A: Android Studio (abrir la carpeta mobile/)
# opción B: línea de comandos
export ANDROID_HOME=/opt/android-sdk   # ruta a tu SDK
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Configurar en el teléfono

1. Instalar el APK.
2. Abrir la app → escribir la dirección del servidor (`mqtt://IP_servidor:1883`) y el
   **ID del dispositivo** (debe existir en el server, p. ej. `demo-001`).
3. Guardar y pulsar **Activar tracking** → conceder permisos de ubicación y notificaciones.
4. El estado y el número de pendientes se muestran en la notificación permanente.

> En dev, el broker EMQX escucha en `127.0.0.1`; para probar con un teléfono físico hay
> que exponer el puerto 1883 con firewall (documentado en `docs/mqtt/emqx-security.md`).
> Para MQTT autenticado, rellenar usuario/contraseña en la app y configurar auth/ACL
> (override `docker-compose.emqx-auth.yml`).

## Políticas Android/Play (cumplidas)

- Foreground service de tipo `location` declarado y arrancado con `ServiceCompat` (obligatorio
  en Android 14); sin `ACCESS_BACKGROUND_LOCATION` (innecesario con FGS location activo).
- Permisos runtime (ubicación, notificaciones Android 13+).
- Boot receiver solo con tracking activo por el usuario.
- Pendiente para Play Store: subir `targetSdk` a 35 antes de publicar.

## Limitaciones (MVP)

- Tráfico MQTT sin cifrar en dev (`usesCleartextTraffic`); para producción usar `mqtts://`
  con TLS (Paho soporta SSL; el server/EMQX lo expone en 8883).
- `rejected/invalid/expired` se descartan tras registrarse (decisión documentada en DECISIONS).
- La verificación real del GPS requiere un teléfono físico (no emulable).
