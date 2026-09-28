# API propia DMujeres Tracking `/api/v1`

Contrato congelado en FASE 2 (ver [ADR-002](../architecture/adr/ADR-002-api-v1.md)).
Fuente legible por máquina: `packages/contracts/openapi.json` (OpenAPI 3.1).
Tipos TypeScript espejo: `packages/shared-types/src/`.

## 1. Autenticación

La Web usa una **sesión propia** de DMujeres Tracking. La credencial interna
del servidor (hash, token del motor de tracking) **nunca llega al navegador**.

- `POST /api/v1/auth/login` valida usuario/correo y clave existentes
  (los del legado, conservados en la migración) y responde `Set-Cookie:
  dmj_sesion=...; HttpOnly; Secure; SameSite=Lax`.
- La cookie viaja en cada petición; el navegador no puede leerla desde
  JavaScript.
- `POST /api/v1/auth/logout` invalida la sesión en el servidor y expira la
  cookie.
- `GET /api/v1/auth/me` devuelve el usuario de la sesión (`Usuario`).
- Expiración: por defecto `DMJ_SESION_HORAS` (ver `.env.example`); al expirar,
  la API responde `401 NO_AUTENTICADO` y la Web vuelve al login.
- Permisos: usuario no habilitado o sin permiso sobre el recurso recibe
  `403 SIN_PERMISO`. Un recurso inexistente o no visible responde `404`
  (no se filtra su existencia).
- `health`, `ready` y `version` no requieren sesión (`security: []`).

### Errores normalizados

Todas las respuestas de error tienen el mismo cuerpo:

```json
{
  "error": {
    "codigo": "NO_AUTENTICADO",
    "mensaje": "La sesión no existe o expiró."
  }
}
```

Códigos en MAYÚSCULAS: `NO_AUTENTICADO` (401), `SIN_PERMISO` (403),
`NO_ENCONTRADO` (404), `DATOS_INVALIDOS` (400), `ERROR_INTERNO` (500),
`SERVICIO_NO_DISPONIBLE` (503). `mensaje` es legible para operador y nunca
incluye SQL, nombres de tablas ni trazas internas.

## 2. Convenciones

- **Rangos**: `desde` y `hasta` en ISO-8601 con zona
  (`2026-09-25T00:00:00-03:00`) o UTC (`Z`). Si se omiten, cada endpoint
  aplica su ventana por defecto (documentada en el OpenAPI).
- **Paginación**: `pagina` (1-based, por defecto 1) y `tamano` (por defecto 25,
  máximo 200). La respuesta es `Pagina<T>`: `{ "datos", "total", "pagina",
  "tamano" }`.
- **Orden**: `orden` con el nombre del campo y prefijo `-` para descendente
  (`-ultimaConexion`).
- **Dispositivos**: `dispositivoId` en consulta y `{id}`/`{deviceId}` en la
  ruta aceptan el `idPublico` (UUID) y, durante la transición, el id legado
  numérico.
- **Campos en español**: los DTOs son propios (`latitud`, `velocidadKmh`,
  `registradoEn`) y no exponen el esquema interno del motor de tracking.
- **Sondeo en vivo**: `GET /api/v1/positions/live` cada ~5 s
  (`intervaloRefrescoSegundos` en `GET /api/v1/config`). La Web implementa
  `AbortController`, control de solapamiento, reintento con backoff y pausa
  cuando la pestaña no necesita actualizaciones. El contrato queda preparado
  para SSE/WebSocket sin cambiar la forma de los datos.

## 3. Rutas

| Método | Ruta | Propósito |
|---|---|---|
| POST | `/api/v1/auth/login` | Inicia sesión y emite la cookie `dmj_sesion`. |
| POST | `/api/v1/auth/logout` | Cierra la sesión y expira la cookie. |
| GET | `/api/v1/auth/me` | Usuario de la sesión actual. |
| GET | `/api/v1/fleet` | Lista paginada de dispositivos con estado operativo. |
| GET | `/api/v1/fleet/{id}` | Detalle de un dispositivo. |
| GET | `/api/v1/fleet/{id}/position` | Última posición conocida del dispositivo. |
| GET | `/api/v1/fleet/{id}/journeys` | Jornadas (encendido/apagado) del equipo en un rango. |
| GET | `/api/v1/journeys` | Jornadas de toda la flota visible en un rango (auditoría). |
| GET | `/api/v1/positions/live` | Posiciones en vivo de la flota (sondeo del panel). |
| GET | `/api/v1/replay` | Dispositivos con recorrido disponible en un rango. |
| GET | `/api/v1/replay/{deviceId}` | Recorrido histórico del dispositivo con huecos y resumen. |
| GET | `/api/v1/reports/trips` | Reporte paginado de viajes. |
| GET | `/api/v1/reports/stops` | Reporte paginado de paradas. |
| GET | `/api/v1/reports/summary` | Resumen operativo del rango, global y por dispositivo. |
| GET | `/api/v1/battery` | Estado de batería de la flota (resumen por dispositivo). |
| GET | `/api/v1/battery/{deviceId}` | Batería actual y muestras del dispositivo. |
| GET | `/api/v1/users` | Lista paginada de usuarios (administradores). |
| GET | `/api/v1/users/{id}` | Detalle de un usuario. |
| GET | `/api/v1/config` | Configuración de la Web (mapa, refresco, capacidades). |
| GET | `/api/v1/health` | Salud del proceso (liveness, sin sesión). |
| GET | `/api/v1/ready` | Disponibilidad de dependencias (readiness, sin sesión). |
| GET | `/api/v1/version` | Versión de API, esquema y build (sin sesión). |

## 4. Ejemplos

### 4.1 Login

Petición:

```http
POST /api/v1/auth/login
Content-Type: application/json

{ "usuario": "operadora", "clave": "clave-ejemplo" }
```

Respuesta `200`:

```http
Set-Cookie: dmj_sesion=...; HttpOnly; Secure; SameSite=Lax
Content-Type: application/json
```

```json
{
  "usuario": {
    "id": 4,
    "idPublico": "01932f6e-7a10-7000-8000-000000000010",
    "nombre": "operadora",
    "correo": "operadora@ejemplo.invalid",
    "administrador": false,
    "soloLectura": false,
    "habilitado": true
  },
  "expiraEn": "2026-09-26T08:00:00Z"
}
```

Credenciales incorrectas: `401` con `{"error":{"codigo":"NO_AUTENTICADO","mensaje":"Usuario o clave incorrectos."}}`.

### 4.2 Flota

Petición: `GET /api/v1/fleet?pagina=1&tamano=25&orden=-ultimaConexion`

Respuesta `200`:

```json
{
  "datos": [
    {
      "id": 47,
      "idPublico": "01932f6e-7a10-7000-8000-000000000047",
      "nombre": "Móvil 12",
      "identificadorUnico": "macias",
      "habilitado": true,
      "estado": "EN_LINEA",
      "ultimaConexion": "2026-09-25T18:42:11Z",
      "versionApp": "1.1.22",
      "jornadaActiva": true,
      "bateriaPct": 78.0,
      "cargando": false,
      "pendientes": 0
    }
  ],
  "total": 1,
  "pagina": 1,
  "tamano": 25
}
```

### 4.3 Replay

Petición:
`GET /api/v1/replay/01932f6e-7a10-7000-8000-000000000047?desde=2026-09-25T00:00:00-03:00&hasta=2026-09-25T23:59:59-03:00`

Respuesta `200` (recortada):

```json
{
  "dispositivo": {
    "id": 47,
    "idPublico": "01932f6e-7a10-7000-8000-000000000047",
    "nombre": "Móvil 12",
    "identificadorUnico": "macias",
    "habilitado": true,
    "estado": "DETENIDO",
    "ultimaConexion": "2026-09-25T21:10:00Z",
    "versionApp": "1.1.22",
    "jornadaActiva": false,
    "bateriaPct": 54.0,
    "cargando": true,
    "pendientes": 0
  },
  "desde": "2026-09-25T03:00:00Z",
  "hasta": "2026-09-26T02:59:59Z",
  "posiciones": [
    {
      "id": 26410,
      "dispositivoId": 47,
      "latitud": -31.4167,
      "longitud": -64.1833,
      "altitudM": 390.0,
      "velocidadKmh": 24.5,
      "rumboGrados": 187.0,
      "precisionM": 8.0,
      "bateriaPct": 78.0,
      "registradoEn": "2026-09-25T18:40:05Z",
      "recibidoEn": "2026-09-25T18:40:07Z",
      "valida": true
    }
  ],
  "huecos": [
    {
      "desde": "2026-09-25T19:10:00Z",
      "hasta": "2026-09-25T19:24:30Z",
      "duracionSegundos": 870,
      "motivo": "SIN_SENAL"
    }
  ],
  "resumen": {
    "inicio": "2026-09-25T18:40:05Z",
    "fin": "2026-09-25T21:10:00Z",
    "totalPosiciones": 1842,
    "totalHuecos": 3,
    "distanciaKm": 47.3,
    "duracionMin": 150.0,
    "velocidadPromedioKmh": 18.9,
    "velocidadMaximaKmh": 52.4,
    "bateriaInicialPct": 78.0,
    "bateriaFinalPct": 54.0
  },
  "generadoEn": "2026-09-25T21:12:00Z"
}
```

### 4.4 Journeys de la flota (auditoría)

Petición: `GET /api/v1/journeys?desde=2026-09-25T00:00:00-05:00&hasta=2026-09-26T00:00:00-05:00&dispositivoId=01932f6e-7a10-7000-8000-000000000047`

Respuesta `200`:

```json
{
  "datos": [
    {
      "id": 12,
      "dispositivoId": 59,
      "idPublico": "01932f6e-7a10-7000-8000-000000000059",
      "nombre": "Fernando",
      "inicioEn": "2026-09-25T08:04:39-05:00",
      "finEn": "2026-09-25T18:38:24-05:00",
      "duracionMin": 633.8,
      "abierta": false
    }
  ],
  "total": 1,
  "pagina": 1,
  "tamano": 25
}
```

Sin `desde`/`hasta` la ventana es el día actual en la zona horaria de la API
(máximo 366 días). Respuesta con `total: 0` y `datos: []` no es error: el día
no tuvo jornadas.

## 4. La Web no conoce el motor interno

La Web **nunca** llama al motor de tracking, al protocolo OsmAnd (`:5055`), al
canal móvil `/api/mobile/v1/*` ni a la base de datos. Solo consume `/api/v1`.
El servicio `services/api` es la única fachada: autentica, autoriza, adapta y
traduce los datos al modelo de negocio de DMujeres Tracking (DTOs en español).
Ningún componente React conoce nombres de tablas, columnas ni estructuras del
motor de tracking.
