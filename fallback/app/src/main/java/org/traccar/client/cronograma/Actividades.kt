package org.traccar.client.cronograma

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import org.traccar.client.DmujeresApi
import org.traccar.client.StatusActivity

/** Tipos de actividad del cronograma (mismos códigos que el servidor). */
enum class TipoActividad(val codigo: String, val etiqueta: String) {
    VISITA("visita", "Visita"),
    ALMUERZO("almuerzo", "Almuerzo"),
    PERMISO_MEDICO("permiso_medico", "Permiso médico"),
    VACACIONES("vacaciones", "Vacaciones"),
    PERMISO("permiso", "Permiso"),
    NOVEDAD("novedad", "Novedad");

    companion object {
        fun de(codigo: String?): TipoActividad = entries.firstOrNull { it.codigo == codigo } ?: VISITA
    }
}

data class Actividad(
    val clientId: String,
    val fecha: String,
    val hora: String,
    val tipo: TipoActividad,
    val lugar: String?,
    val nota: String?,
    val registradoEn: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val precision: Float? = null,
    val conUbicacion: Boolean = false,
    val pendiente: Boolean = false,
    val eliminada: Boolean = false,
) {
    fun aJson(): JSONObject = JSONObject()
        .put("clientId", clientId).put("fecha", fecha).put("hora", hora).put("tipo", tipo.codigo)
        .put("lugar", lugar ?: JSONObject.NULL).put("nota", nota ?: JSONObject.NULL)
        .put("at", registradoEn).put("lat", lat ?: JSONObject.NULL).put("lon", lon ?: JSONObject.NULL)
        .put("accuracy", precision?.toDouble() ?: JSONObject.NULL)
        .put("conUbicacion", conUbicacion).put("pendiente", pendiente).put("deleted", eliminada)

    companion object {
        fun deJson(o: JSONObject): Actividad = Actividad(
            clientId = o.getString("clientId"),
            fecha = o.getString("fecha"),
            hora = o.getString("hora"),
            tipo = TipoActividad.de(o.optString("tipo")),
            lugar = o.optString("lugar").takeIf { !o.isNull("lugar") && it.isNotBlank() },
            nota = o.optString("nota").takeIf { !o.isNull("nota") && it.isNotBlank() },
            registradoEn = o.optLong("registradoEn", o.optLong("at", 0L)),
            lat = if (o.isNull("lat") || !o.has("lat")) null else o.optDouble("lat"),
            lon = if (o.isNull("lon") || !o.has("lon")) null else o.optDouble("lon"),
            precision = if (o.isNull("accuracy") || !o.has("accuracy")) null else o.optDouble("accuracy").toFloat(),
            conUbicacion = o.optBoolean("conUbicacion", false),
            pendiente = o.optBoolean("pendiente", false),
            eliminada = o.optBoolean("deleted", false),
        )
    }
}

/**
 * Almacén del cronograma en el teléfono: copia de lo que hay en el servidor y
 * lo cargado sin conexión (pendiente de subir). Todo se guarda al instante y se
 * sube en segundo plano; así se puede completar la ruta sin señal.
 */
object Actividades {
    private const val KEY = "cronogramaLocal"
    private const val KEY_SYNC_AT = "cronogramaSincronizadoEn"
    private const val MAX = 800

    @Synchronized
    fun todas(context: Context): List<Actividad> {
        val raw = PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            List(a.length()) { Actividad.deJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun guardarTodas(context: Context, lista: List<Actividad>) {
        val a = JSONArray()
        lista.sortedWith(compareBy({ it.fecha }, { it.hora })).takeLast(MAX).forEach { a.put(it.aJson()) }
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(KEY, a.toString()).commit()
    }

    // Orden estable: por hora y, a igual hora, por orden de carga.
    fun delDia(context: Context, fecha: String): List<Actividad> =
        todas(context).filter { it.fecha == fecha && !it.eliminada }
            .sortedWith(compareBy({ it.hora }, { it.registradoEn }))

    fun delMes(context: Context, mes: String): List<Actividad> =
        todas(context).filter { it.fecha.startsWith(mes) && !it.eliminada }

    fun pendientes(context: Context): Int = todas(context).count { it.pendiente }

    /** Última vez que el teléfono quedó al día con el servidor (0 = nunca). */
    fun sincronizadoEn(context: Context): Long =
        PreferenceManager.getDefaultSharedPreferences(context).getLong(KEY_SYNC_AT, 0L)

    private fun marcarSincronizado(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putLong(KEY_SYNC_AT, System.currentTimeMillis()).apply()
    }

    /**
     * Hora sugerida para una actividad nueva del día: continúa después de la
     * última cargada (+30 min) aunque esté en el futuro; así, si ya se planificó
     * algo a las 17:00, la siguiente sale a las 17:30 y no a la hora actual,
     * que la dejaba desordenada. Sin actividades, la hora actual redondeada.
     */
    fun horaSugerida(delDia: List<Actividad>, horaActual: String): String {
        val ultima = delDia.maxOfOrNull { it.hora } ?: return horaActual
        return HoraCronograma.sumar(ultima, 30)
    }

    /** Alta o edición: queda guardada y pendiente de subir. */
    @Synchronized
    fun guardar(context: Context, actividad: Actividad) {
        val lista = todas(context).filter { it.clientId != actividad.clientId }.toMutableList()
        lista.add(actividad.copy(pendiente = true))
        guardarTodas(context, lista)
        sincronizar(context)
    }

    @Synchronized
    fun eliminar(context: Context, actividad: Actividad) {
        guardar(context, actividad.copy(eliminada = true))
    }

    /**
     * Lugares escritos antes, del más usado al menos usado: la app los sugiere
     * al escribir para no repetir y mantener los nombres iguales en el panel.
     */
    fun lugaresFrecuentes(context: Context): List<String> =
        todas(context).mapNotNull { it.lugar?.trim()?.takeIf { l -> l.isNotEmpty() } }
            .groupingBy { it.lowercase() }.eachCount().entries
            .sortedByDescending { it.value }
            .mapNotNull { e -> todas(context).firstOrNull { it.lugar?.trim()?.lowercase() == e.key }?.lugar?.trim() }

    @Volatile
    private var sincronizando = false

    /** Sube lo pendiente en orden; se reintenta al abrir, al guardar y con el latido. */
    fun sincronizar(context: Context, alTerminar: (() -> Unit)? = null) {
        if (sincronizando) {
            // Ya hay una subida en curso: se sigue con lo que pidió quien llama.
            alTerminar?.invoke()
            return
        }
        val app = context.applicationContext
        sincronizando = true
        Thread {
            try {
                var todoSubido = true
                for (a in todas(app).filter { it.pendiente }) {
                    if (!DmujeresApi.postActividad(app, a.aJson())) {
                        todoSubido = false
                        break
                    }
                    marcarSubida(app, a)
                    StatusActivity.addMessage("Cronograma: ${a.tipo.etiqueta.lowercase()} de las ${a.hora} sincronizada")
                }
                if (todoSubido && pendientes(app) == 0) marcarSincronizado(app)
            } finally {
                sincronizando = false
                alTerminar?.invoke()
            }
        }.start()
    }

    @Synchronized
    private fun marcarSubida(context: Context, subida: Actividad) {
        val lista = todas(context).mapNotNull {
            when {
                it.clientId != subida.clientId -> it
                // Si se editó de nuevo mientras subía, sigue pendiente.
                it.registradoEn != subida.registradoEn || it != subida -> it
                it.eliminada -> null
                else -> it.copy(pendiente = false)
            }
        }
        guardarTodas(context, lista)
    }

    /**
     * Trae del servidor un rango y lo mezcla con lo local: lo pendiente del
     * teléfono manda sobre lo del servidor (aún no llegó); lo demás se toma
     * del servidor, que es la fuente de verdad.
     */
    fun actualizar(context: Context, desde: String, hasta: String, alTerminar: (Boolean) -> Unit) {
        val app = context.applicationContext
        Thread {
            val remoto = DmujeresApi.fetchActividades(app, desde, hasta)
            if (remoto != null) {
                synchronized(this) {
                    val locales = todas(app)
                    val pendientes = locales.filter { it.pendiente }.associateBy { it.clientId }
                    val fuera = locales.filter { it.fecha < desde || it.fecha > hasta || it.pendiente }
                    val delServidor = List(remoto.length()) { Actividad.deJson(remoto.getJSONObject(it)) }
                        .filter { it.clientId !in pendientes }
                    guardarTodas(app, fuera + delServidor)
                }
                if (pendientes(app) == 0) marcarSincronizado(app)
            }
            alTerminar(remoto != null)
        }.start()
    }
}
