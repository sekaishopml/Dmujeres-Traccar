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
}
