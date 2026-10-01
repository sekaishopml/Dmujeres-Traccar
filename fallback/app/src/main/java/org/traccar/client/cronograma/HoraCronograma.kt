package org.traccar.client.cronograma

import java.util.Locale

/** Aritmética de horas "HH:mm" del cronograma (pura, testeable en JVM). */
object HoraCronograma {
    fun aMinutos(hora: String): Int {
        val (h, m) = hora.split(":").map { it.toIntOrNull() ?: 0 }
        return (h * 60 + m).coerceIn(0, 23 * 60 + 59)
    }

    fun deMinutos(minutos: Int): String {
        val m = minutos.coerceIn(0, 23 * 60 + 55)
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
    }

    /** Suma minutos sin pasar de 23:55 ni bajar de 00:00. */
    fun sumar(hora: String, minutos: Int): String = deMinutos(aMinutos(hora) + minutos)

    /** Primera hora libre desde [hora] (salta de 5 en 5 si ya está ocupada). */
    fun libreDesde(hora: String, ocupadas: Set<String>): String {
        var m = aMinutos(hora)
        while (deMinutos(m) in ocupadas && m < 23 * 60 + 55) m += 5
        return deMinutos(m)
    }

    /** Resultado de leer lo que escribió la persona: hora en 24 h y si es PM. */
    data class Lectura(val hora24: String, val pm: Boolean)

    /**
     * Lee una hora escrita a mano ("9", "930", "9:30", "9.30", "14:30") con el
     * AM/PM elegido. Si la hora viene en formato 24 h (13 a 23, o 0), el AM/PM
     * se deduce solo: "14:30" es 2:30 PM. Con 1 a 12 manda el AM/PM elegido
     * (12 AM = 00, 12 PM = 12). Null si no se entiende.
     */
    fun interpretar(texto: String, pmElegido: Boolean): Lectura? {
        val limpio = texto.trim().replace('.', ':').replace(' ', ':')
        val (hTexto, mTexto) = when {
            limpio.contains(':') -> limpio.substringBefore(':') to limpio.substringAfter(':')
            limpio.length <= 2 -> limpio to "0"
            limpio.length in 3..4 -> limpio.dropLast(2) to limpio.takeLast(2)
            else -> return null
        }
        val h = hTexto.toIntOrNull() ?: return null
        val m = (mTexto.ifEmpty { "0" }).toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        val hora24 = when {
            h == 0 || h >= 13 -> h
            h == 12 -> if (pmElegido) 12 else 0
            else -> if (pmElegido) h + 12 else h
        }
        return Lectura(String.format(Locale.US, "%02d:%02d", hora24, m), hora24 >= 12)
    }

    /** "14:30" -> "02:30" (para mostrar junto al AM/PM). */
    fun a12(hora24: String): String {
        val m = aMinutos(hora24)
        val h = (m / 60) % 12
        return String.format(Locale.US, "%02d:%02d", if (h == 0) 12 else h, m % 60)
    }

    fun esPm(hora24: String): Boolean = aMinutos(hora24) >= 12 * 60

    /** "14:30" -> "2:30 p. m." para listas y la pantalla principal. */
    fun legible(hora24: String): String {
        val m = aMinutos(hora24)
        val h = (m / 60) % 12
        return String.format(Locale.US, "%d:%02d %s", if (h == 0) 12 else h, m % 60, if (m >= 12 * 60) "p. m." else "a. m.")
    }

    /** Rango legible: "9:00 a. m. – 11:30 a. m." o solo el inicio. */
    fun rango(hora: String, horaFin: String?): String =
        if (horaFin == null) legible(hora) else "${legible(hora)} – ${legible(horaFin)}"

    /**
     * Actividad en curso a la hora [ahora] ("HH:mm"): la que empezó y no ha
     * terminado. Sin hora de fin, dura hasta que empieza la siguiente.
     * Lista ordenada por hora.
     */
    fun <T> enCurso(lista: List<T>, ahora: String, hora: (T) -> String, horaFin: (T) -> String?): T? {
        val m = aMinutos(ahora)
        return lista.withIndex().lastOrNull { (i, a) ->
            val inicio = aMinutos(hora(a))
            val fin = horaFin(a)?.let { aMinutos(it) } ?: lista.getOrNull(i + 1)?.let { aMinutos(hora(it)) } ?: Int.MAX_VALUE
            m in inicio until fin
        }?.value
    }

    /** Próxima actividad que todavía no empezó. */
    fun <T> siguiente(lista: List<T>, ahora: String, hora: (T) -> String): T? =
        lista.firstOrNull { aMinutos(hora(it)) > aMinutos(ahora) }
}
