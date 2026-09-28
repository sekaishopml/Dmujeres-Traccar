# `packages/config` — Variables de entorno

Lectura centralizada de la configuración de DMujeres Tracking. **Aquí se
documentan los nombres y su significado; los valores nunca se versionan.**

## Cómo se lee por entorno

| Entorno | Origen de valores |
|---|---|
| Desarrollo | `.env` local en la raíz (no versionado), cargado con `node --env-file=.env` |
| Pruebas | Variables del runner de pruebas; nunca apunta a la base de producción |
| Producción | `EnvironmentFile=` de systemd (por ejemplo `/etc/dmj/api.env`, permisos `0600`) y secretos fuera del árbol del proyecto |

Reglas:

1. El código importa la configuración desde `packages/config` y valida al
   arrancar; si falta una variable obligatoria, el servicio no inicia y el
   error dice cuál falta (sin imprimir su valor).
2. Ningún servicio lee `process.env` directamente ni hardcodea puertos, DSN,
   rutas o credenciales.
3. `.env.example` en la raíz lista solo nombres y valores de ejemplo.
   `.env` está ignorado por git y jamás se copia a un backup del repositorio.
4. `DMJ_ENTORNO` (`desarrollo` | `pruebas` | `produccion`) decide los valores
   por defecto de lo no especificado: puertos locales, nivel de log y si el
   adaptador del legado se activa.
5. `DMJ_LEGADO_TRACCAR_URL` solo existe para la transición (lectura del
   sistema actual); al retirar el legado la variable se elimina y la
   configuración falla si el código aún la requiere.

## Variables

| Nombre | Obligatoria | Descripción |
|---|---|---|
| `DMJ_ENTORNO` | Sí | `desarrollo`, `pruebas` o `produccion`. |
| `DMJ_WEB_PORT` | Sí (web) | Puerto local del frontend estático/servidor de desarrollo. |
| `DMJ_API_PORT` | Sí (api) | Puerto local de `services/api`; Nginx lo expone por HTTPS. |
| `DMJ_TRACKING_PORT` | Sí (tracking) | Puerto del servidor de tracking (OsmAnd/HTTP). |
| `DMJ_DB_URL` | Sí (api/tracking) | URI PostgreSQL de la base nueva (`postgresql://host:puerto/base`). |
| `DMJ_DB_USUARIO` | Sí | Usuario de aplicación con mínimo privilegio. |
| `DMJ_DB_CLAVE` | Sí | Clave del usuario de aplicación; solo por entorno/secretos. |
| `DMJ_SESION_HORAS` | Sí | Vigencia de la cookie de sesión `dmj_sesion`. |
| `DMJ_TLS_CERT` | Sí en producción | Ruta del certificado TLS de Nginx. |
| `DMJ_TLS_KEY` | Sí en producción | Ruta de la clave privada TLS; permisos `0600`. |
| `DMJ_TLS_DOMINIO` | Sí en producción | Dominio público cubierto por el certificado. |
| `DMJ_LEGADO_TRACCAR_URL` | Solo transición | Base URL de solo lectura del sistema actual. |

Sin valores reales en el repositorio. Ver `.env.example` en la raíz.
