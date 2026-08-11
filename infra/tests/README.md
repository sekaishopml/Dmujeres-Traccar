# Pruebas e2e del backend (desarrollo)

Verificaciones rápidas contra un backend en ejecución (`http://localhost:8082`, OsmAnd en `:5055`).

## Requisitos previos
- Servicios e infra levantados: `bash ../scripts/start-services.sh`
- Backend corriendo: `cd ../../server && java -jar target/tracker-server.jar dmujeres.xml`
- Un usuario admin creado (`admin@dmujeres.local` / `admin123`) y un dispositivo con `uniqueId=860123456789`.

## Instalar dependencias
```bash
cd infra/tests && npm install
```

## Prueba de tiempo real (WebSocket)
Conecta al WebSocket, envía un ping GPS por OsmAnd y comprueba que la posición
llega **en vivo** por el socket:
```bash
npm run ws-realtime
```
Salida esperada: `SUCCESS: la posicion llego por WebSocket en tiempo real.`
