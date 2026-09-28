# ADR-002 — API propia `/api/v1` y contrato de la Web

- Fecha: 2026-09-25
- Estado: aceptada
- Ámbito: contratos HTTP, sesión Web y tipos compartidos de la plataforma nueva

## Contexto

La Web nueva no puede depender del esquema interno del motor de tracking ni de
las rutas del panel anterior. El plan maestro fija las rutas `/api/v1`, una
sesión propia en el navegador y DTOs propios. A la vez, la App actual debe
seguir operando con su contrato (OsmAnd `:5055`, atributos `mobile.*`, canal
`/api/mobile/v1/*`), congelado en `docs/api/COMPATIBILIDAD-APP.md`.

## Decisión

1. **Versionado en la ruta.** Toda la API de la Web vive bajo `/api/v1`. Un
   cambio incompatible futuro abre `/api/v2` en paralelo; nunca se rompe una
   versión publicada. El contrato se versiona en
   `packages/contracts/openapi.json` (OpenAPI 3.1) y es la fuente de verdad.
2. **DTOs propios en español.** `Usuario`, `Dispositivo`, `Posicion`,
   `Replay`, `Bateria`, `ReporteViaje`, `ReporteParada`, `ResumenReporte` y
   `Pagina<T>` usan campos en español (`latitud`, `velocidadKmh`,
   `registradoEn`). No se devuelven estructuras internas del motor ni nombres
   de tablas del legado. Los tipos espejo viven en `packages/shared-types`,
   sin dependencias externas.
3. **Cookie de sesión propia.** El login emite `dmj_sesion` con `HttpOnly`,
   `Secure` y `SameSite=Lax`. La credencial interna (hash del legado, token
   del motor) nunca llega al navegador. Expiración configurable
   (`DMJ_SESION_HORAS`); `logout` invalida en el servidor y expira la cookie.
   El esquema OpenAPI `cookieSesion` aplica a toda la API salvo login, health,
   ready y version.
4. **Errores normalizados.** Todo error responde
   `{"error":{"codigo":"...","mensaje":"..."}}` con códigos en MAYÚSCULAS:
   `NO_AUTENTICADO` (401), `SIN_PERMISO` (403), `NO_ENCONTRADO` (404),
   `DATOS_INVALIDOS` (400), `ERROR_INTERNO` (500),
   `SERVICIO_NO_DISPONIBLE` (503). El mensaje nunca expone SQL, tablas ni
   trazas; el detalle técnico queda en el log del servidor.
5. **Vivo por sondeo, listo para empujar.** La primera versión consulta
   `GET /api/v1/positions/live` cada ~5 s (`intervaloRefrescoSegundos` en
   `/api/v1/config`). La Web implementa `AbortController`, control de
   solapamiento, reintento con backoff y pausa con pestaña inactiva; se
   recupera sola tras un error. SSE/WebSocket se añadirán como **canal
   adicional** que emite los mismos DTOs, sin tocar la forma del contrato de
   sondeo.
6. **La Web no conoce tablas.** Ningún componente React, hook o store contiene
   SQL, nombres de tablas/columnas ni lógica de negocio. Toda agregación,
   permiso y traducción vive en `services/api`; la Web solo pinta DTOs.
7. **Parámetros de consulta uniformes.** `desde`/`hasta` en ISO-8601,
   `dispositivoId`, `pagina`, `tamano` y `orden`; respuestas de listado
   siempre `Pagina<T>`.
8. **Convivencia con el contrato de la App.** `/api/v1` es solo para la Web.
   El canal móvil y el protocolo OsmAnd no se tocan; cualquier cambio ahí
   exige capa de compatibilidad (regla del documento de compatibilidad).

## Alternativas descartadas

- **Reutilizar las rutas del motor/legado:** exponía nombres internos y
  acoplaba la Web al esquema del motor; descartado.
- **JWT en `localStorage`:** la credencial quedaría accesible a JavaScript;
  descartado frente a cookie `HttpOnly`.
- **WebSocket desde el día uno:** mayor complejidad operativa sin cambiar la
  UX; se pospone manteniendo el contrato listo.
- **Campos en inglés espejo del motor:** rompe la identidad propia
  (ADR-001) y filtra terminología interna; descartado.

## Consecuencias

- `packages/contracts` y `packages/shared-types` son artefactos de FASE 2 y
  deben evolucionar de forma aditiva; los tests de contrato comparan las rutas
  del OpenAPI con la implementación.
- El frontend puede reemplazarse o crecer sin tocar la base ni el tracking.
- La sesión y los permisos se resuelven en un único punto (`services/api`),
  auditable y testeable.
- Todo error de la API es uniforme, lo que simplifica el manejo en la Web y
  los smoke tests.
