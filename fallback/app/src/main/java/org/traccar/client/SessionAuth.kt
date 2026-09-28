package org.traccar.client

/**
 * Lógica PURA de la sesión (JVM, sin Android ni org.json).
 *
 * Contrato del servidor (POST /api/mobile/v1/sesion {usuario, clave}):
 * - 200 {token, expiraEn, usuario:{nombre}, configuracion:{...}}
 * - 401 credenciales inválidas.
 * Los endpoints móviles aceptan `Authorization: Bearer <token>` además de la
 * clave compartida actual (compatibilidad con la flota instalada).
 *
 * Todo lo que necesita Context/SharedPreferences vive en [SessionStore] y en
 * [DmujeresApi]; aquí solo hay funciones puras y testeables sin framework.
 */
object SessionAuth {

    /** Ruta del endpoint de sesión (contrato). */
    const val PATH_SESION = "/api/mobile/v1/sesion"

    /** Normaliza el usuario: sin espacios y en minúsculas (como el login actual). */
    fun normalizeUser(raw: String): String = raw.trim().lowercase()

    /** Cuerpo JSON del POST /sesion (escapado mínimo, sin dependencias). */
    fun loginRequestJson(usuario: String, clave: String): String =
        "{\"usuario\":\"${escape(usuario)}\",\"clave\":\"${escape(clave)}\"}"

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Extrae el token de un cuerpo 200. null = respuesta sin token utilizable. */
    fun extractToken(body: String): String? =
        Regex("\"token\"\\s*:\\s*\"([^\"]+)\"")
            .find(body)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    /** Segundos de vigencia (`expiraEn`). null = ausente o no numérico. */
    fun extractExpiresInSeconds(body: String): Long? =
        Regex("\"expiraEn\"\\s*:\\s*(\\d+)")
            .find(body)?.groupValues?.getOrNull(1)?.toLongOrNull()

    /** Nombre visible (`usuario.nombre`). Vacío si no viene. */
    fun extractDisplayName(body: String): String =
        Regex("\"usuario\"\\s*:\\s*\\{[^}]*\"nombre\"\\s*:\\s*\"([^\"]*)\"")
            .find(body)?.groupValues?.getOrNull(1).orEmpty()

    /**
     * Bloque `configuracion` del cuerpo 200 (con llaves incluidas).
     * null = ausente o mal formado (se ignora, no tumba el login).
     */
    fun extractConfigBlock(body: String): String? {
        val match = Regex("\"configuracion\"\\s*:\\s*\\{").find(body) ?: return null
        var start = -1
        var cursor = match.range.first
        while (cursor < body.length) {
            if (body[cursor] == '{') {
                start = cursor
                break
            }
            cursor++
        }
        if (start < 0) return null
        var depth = 0
        var index = start
        while (index < body.length) {
            when (body[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return body.substring(start, index + 1)
                }
            }
            index++
        }
        return null
    }

    /**
     * Configuración validada: SOLO claves conocidas, con las mismas reglas que
     * la config remota (`RemoteConfig`): intervalo >= 3 s, distancia >= 0 m,
     * ángulo 0..180, precisión en {high, medium, low} y búfer booleano.
     * Todo lo demás se ignora.
     */
    data class ValidConfig(
        val interval: String?,
        val distance: String?,
        val angle: String?,
        val accuracy: String?,
        val buffer: Boolean?,
    )

    fun parseConfigBlock(block: String): ValidConfig {
        fun long(key: String): Long? =
            Regex("\"$key\"\\s*:\\s*(-?\\d+)")
                .find(block)?.groupValues?.getOrNull(1)?.toLongOrNull()

        fun text(key: String): String? =
            Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
                .find(block)?.groupValues?.getOrNull(1)

        fun flag(key: String): Boolean? =
            when (Regex("\"$key\"\\s*:\\s*(true|false)").find(block)?.groupValues?.getOrNull(1)) {
                "true" -> true
                "false" -> false
                else -> null
            }

        return ValidConfig(
            interval = long("intervalSeconds")?.takeIf { it >= 3 }?.toString(),
            distance = long("distanceMeters")?.takeIf { it >= 0 }?.toString(),
            angle = long("angleDegrees")?.takeIf { it in 0..180 }?.toString(),
            accuracy = text("accuracy")?.takeIf { it in setOf("high", "medium", "low") },
            buffer = flag("bufferEnabled"),
        )
    }

    /**
     * Cabeceras de autenticación: con token se envía `Authorization: Bearer` y
     * SIN la clave compartida; sin token se usa la clave actual (fallback para
     * la flota instalada sin sesión). Vacío si no hay ni token ni clave.
     */
    fun authHeaders(token: String?, apiKey: String): Map<String, String> {
        if (!token.isNullOrBlank()) return mapOf("Authorization" to "Bearer ${token.trim()}")
        if (apiKey.isNotBlank()) return mapOf("X-Api-Key" to apiKey)
        return emptyMap()
    }

    /**
     * ¿Hay que limpiar la sesión? Solo ante 401 CON token (revocado o usuario
     * deshabilitado). Sin token el 401 es de la clave compartida (no hay sesión
     * que limpiar) y el resto de códigos nunca la tocan.
     */
    fun shouldClearSession(httpCode: Int, hadToken: Boolean): Boolean =
        hadToken && httpCode == 401
}
