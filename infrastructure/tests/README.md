# Pruebas de integración — FASE 1 (server baseline)

Pruebas de la API REST + WebSocket de Traccar v6.14.5 (baseline upstream, sin
modificaciones al core). Son la evidencia ejecutable del hito Fase 1.

## Requisitos

- Server corriendo en `localhost:8082` (`../scripts/run-server-dev.sh start`)
- Usuario admin: por defecto `admin@dmj.local` / `Admin123!` (primer usuario creado
  en Fase 0; se puede sobreescribir con variables de entorno)
- `npm ci` (dependencia: `ws`)

## Ejecución

```bash
cd infrastructure/tests
npm ci

# Suite completa
node ws-test.js      # PT-101/102: auth (cookie/token/Basic) + WebSocket realtime
node crud-test.js    # PT-103: CRUD + aislamiento de permisos multi-usuario
node event-test.js   # PT-104a: eventos de estado (deviceOnline) por WebSocket
```

## Variables de entorno (todas opcionales)

| Variable | Default | Descripción |
|---|---|---|
| `TEST_SERVER_URL` | `http://localhost:8082` | URL del server |
| `TEST_WS_URL` | `ws://localhost:8082/api/socket` | URL WebSocket |
| `TEST_ADMIN_EMAIL` | `admin@dmj.local` | Email del admin |
| `TEST_ADMIN_PASSWORD` | `Admin123!` | Password del admin (dev) |

## Notas importantes

1. **`event-test.js` requiere transición de estado del dispositivo**: el evento
   `deviceOnline` solo se emite cuando el dispositivo cambia de estado (ej. el server
   se reinició y el device pasa de `unknown`→`online`). En una BD donde el device ya
   está `online`, primero ejecutar:
   ```bash
   ./scripts/dev.sh psql -c "UPDATE tc_devices SET status='unknown';"
   ./scripts/run-server-dev.sh restart   # reinicia cache
   ```
2. **Notificaciones web**: el evento llega por WS solo si el usuario tiene una
   notificación tipo `web` con `always=true` y vinculada (permiso). El test la crea;
   si falla, ver `clean-notifs` manual:
   ```bash
   ./scripts/dev.sh psql -c "DELETE FROM tc_user_notification; DELETE FROM tc_notifications;"
   ```
3. **Keepalive**: el server envía `{}` cada 55s; `ws-test.js` espera hasta 60s.
4. Los tests crean datos de demostración (grupos, dispositivos, usuarios, geocercas,
   notificaciones). Se recomienda BD de desarrollo.

## Resultados registrados (2026-08-12)

- `ws-test.js`: **9/10 PASS** (WS-4 cubierto por event-test.js — requiere transición)
- `crud-test.js`: **10/10 PASS**
- `event-test.js`: **PASS** (evento deviceOnline recibido en tiempo real)