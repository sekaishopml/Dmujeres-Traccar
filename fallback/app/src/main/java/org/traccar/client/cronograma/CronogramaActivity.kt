package org.traccar.client.cronograma

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import org.traccar.client.DmujeresApi
import org.traccar.client.PositionProvider
import org.traccar.client.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Cronograma de actividades (reemplaza el Excel de "ruta semanal").
 *
 * - Sin jornada abre el MES; con jornada iniciada abre el DÍA de hoy. Se puede
 *   ir hacia atrás y adelante por mes o por día.
 * - Cada actividad: hora, tipo (visita, almuerzo, permiso médico, vacaciones,
 *   permiso, novedad), lugar y nota. La ubicación solo se adjunta si la
 *   jornada está iniciada y la actividad es de hoy: cargada más tarde (p. ej.
 *   desde la casa) no describiría el lugar declarado. El servidor vuelve a
 *   comprobarlo.
 * - Todo se guarda al instante en el teléfono y se sube al haber conexión.
 */
class CronogramaActivity : AppCompatActivity() {

    private val zona = TimeZone.getTimeZone("America/Guayaquil")
    private val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zona }
    private val es = Locale("es", "EC")

    private var enMes = true
    private lateinit var dia: Calendar
    private lateinit var mes: Calendar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cronograma)
        org.traccar.client.Responsivo.raiz(this)?.let {
            org.traccar.client.Responsivo.centrarHijos(it, org.traccar.client.Responsivo.ANCHO_PANEL_DP)
        }
        dia = Calendar.getInstance(zona)
        // Al girar la tablet se conserva el día y la vista (mes o día).
        savedInstanceState?.getLong(KEY_DIA, 0L)?.takeIf { it > 0 }?.let { dia.timeInMillis = it }
        mes = primeroDelMes(dia)
        // Con jornada abierta lo útil es el día en curso; sin jornada, el mes.
        enMes = savedInstanceState?.getBoolean(KEY_EN_MES) ?: !DmujeresApi.isJourneyOpen(this)
        savedInstanceState?.getLong(KEY_MES, 0L)?.takeIf { it > 0 }?.let { mes.timeInMillis = it }

        findViewById<View>(R.id.crono_back).setOnClickListener { finish() }
        findViewById<View>(R.id.crono_prev).setOnClickListener { mover(-1) }
        findViewById<View>(R.id.crono_next).setOnClickListener { mover(1) }
        findViewById<View>(R.id.crono_modo).setOnClickListener {
            enMes = !enMes
            if (enMes) mes = primeroDelMes(dia)
            pintar()
        }
        findViewById<View>(R.id.crono_agregar).setOnClickListener { abrirFormulario(null) }
        cabeceraSemana()
        pintar()
    }

    override fun onResume() {
        super.onResume()
        refrescarDelServidor()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_EN_MES, enMes)
        outState.putLong(KEY_DIA, dia.timeInMillis)
        outState.putLong(KEY_MES, mes.timeInMillis)
    }

    // ── Navegación ─────────────────────────────────────────────────────────

    private fun primeroDelMes(c: Calendar): Calendar = (c.clone() as Calendar).apply {
        set(Calendar.DAY_OF_MONTH, 1)
    }

    private fun mover(paso: Int) {
        if (enMes) {
            mes.add(Calendar.MONTH, paso)
            refrescarDelServidor()
        } else {
            dia.add(Calendar.DAY_OF_MONTH, paso)
            if (dia.get(Calendar.MONTH) != mes.get(Calendar.MONTH) || dia.get(Calendar.YEAR) != mes.get(Calendar.YEAR)) {
                mes = primeroDelMes(dia)
                refrescarDelServidor()
            }
        }
        pintar()
    }

    private fun fechaDe(c: Calendar): String = iso.format(c.time)
    private fun hoy(): String = iso.format(Calendar.getInstance(zona).time)

    private fun refrescarDelServidor() {
        val desde = fechaDe(mes)
        val fin = (mes.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH)) }
        val hasta = fechaDe(fin)
        Actividades.sincronizar(this) {
            Actividades.actualizar(this, desde, hasta) { ok ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    pintar()
                    estadoSincronizacion(ok)
                }
            }
        }
    }

    private fun estadoSincronizacion(conectado: Boolean) {
        val pendientes = Actividades.pendientes(this)
        val sync = Actividades.sincronizadoEn(this)
        findViewById<TextView>(R.id.crono_sync).text = when {
            pendientes > 0 -> getString(R.string.crono_sync_pend_fmt, pendientes)
            !conectado && sync <= 0L -> getString(R.string.crono_sync_offline)
            sync > 0L -> getString(
                R.string.crono_sync_at_fmt,
                SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = zona }.format(java.util.Date(sync)),
            )
            else -> getString(R.string.crono_sync_nunca)
        }
    }

    // ── Pintado ────────────────────────────────────────────────────────────

    private fun formato(patron: String, c: Calendar): String =
        SimpleDateFormat(patron, es).apply { timeZone = zona }.format(c.time).replaceFirstChar { it.uppercase() }

    private fun pintar() {
        val titulo = findViewById<TextView>(R.id.crono_titulo)
        val modo = findViewById<TextView>(R.id.crono_modo)
        findViewById<View>(R.id.crono_mes).visibility = if (enMes) View.VISIBLE else View.GONE
        findViewById<View>(R.id.crono_dia).visibility = if (enMes) View.GONE else View.VISIBLE
        if (enMes) {
            titulo.text = formato("LLLL 'de' yyyy", mes)
            modo.text = getString(R.string.crono_ver_dia_fmt, formato("d 'de' MMMM", dia).lowercase())
            pintarMes()
        } else {
            titulo.text = formato("EEEE d 'de' MMMM", dia)
            modo.text = getString(R.string.crono_ver_mes)
            pintarDia()
        }
        estadoSincronizacion(true)
    }

    private fun cabeceraSemana() {
        val cab = findViewById<LinearLayout>(R.id.crono_semana_cab)
        listOf("L", "M", "M", "J", "V", "S", "D").forEach { letra ->
            cab.addView(TextView(this).apply {
                text = letra
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.text_tertiary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, dp(24), 1f)
            })
        }
    }

    private fun pintarMes() {
        val grilla = findViewById<LinearLayout>(R.id.crono_grilla)
        grilla.removeAllViews()
        val prefijoMes = fechaDe(mes).substring(0, 7)
        val delMes = Actividades.delMes(this, prefijoMes).groupBy { it.fecha }
        val cursor = (mes.clone() as Calendar)
        // Semana de lunes a domingo.
        val desfase = (cursor.get(Calendar.DAY_OF_WEEK) + 5) % 7
        cursor.add(Calendar.DAY_OF_MONTH, -desfase)
        val hoy = hoy()
        val seleccion = fechaDe(dia)
        repeat(6) {
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            repeat(7) {
                val fecha = fechaDe(cursor)
                val delDia = delMes[fecha].orEmpty()
                val esDelMes = fecha.startsWith(prefijoMes)
                val celda = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(4), 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    setBackgroundResource(R.drawable.ds_celda_dia)
                    isSelected = fecha == seleccion
                    alpha = if (esDelMes) 1f else 0.35f
                }
                celda.addView(TextView(this).apply {
                    text = cursor.get(Calendar.DAY_OF_MONTH).toString()
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    if (fecha == hoy) {
                        setBackgroundResource(R.drawable.ds_hoy)
                        setTextColor(getColor(R.color.white))
                        setTypeface(typeface, Typeface.BOLD)
                    } else {
                        setTextColor(getColor(R.color.text_primary))
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
                })
                if (delDia.isNotEmpty()) {
                    val (color, fondo) = colorTipo(delDia.first().tipo)
                    celda.addView(TextView(this).apply {
                        text = delDia.size.toString()
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(getColor(color))
                        setBackgroundResource(fondo)
                        setPadding(dp(6), 0, dp(6), 0)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(2) }
                    })
                }
                val c = cursor.clone() as Calendar
                celda.setOnClickListener {
                    dia = c
                    enMes = false
                    pintar()
                }
                fila.addView(celda)
                cursor.add(Calendar.DAY_OF_MONTH, 1)
            }
            grilla.addView(fila)
        }
    }

    private fun pintarDia() {
        val lista = findViewById<LinearLayout>(R.id.crono_lista)
        lista.removeAllViews()
        val delDia = Actividades.delDia(this, fechaDe(dia))
        if (delDia.isEmpty()) {
            lista.addView(TextView(this).apply {
                setText(R.string.crono_vacio)
                setTextColor(getColor(R.color.text_tertiary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(18), 0, dp(8))
            })
            // Atajos: marcar el día entero sin escribir nada.
            lista.addView(TextView(this).apply {
                setText(R.string.crono_todo_dia)
                setTextColor(getColor(R.color.text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(4), 0, dp(6))
            })
            val atajos = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(14))
            }
            listOf(TipoActividad.VACACIONES, TipoActividad.PERMISO_MEDICO, TipoActividad.PERMISO).forEach { tipo ->
                atajos.addView(chip(tipo.etiqueta, false) {
                    Actividades.guardar(this, nueva(tipo, "08:00", "17:00", null, null))
                    pintar()
                })
            }
            lista.addView(atajos)
            return
        }
        delDia.forEachIndexed { i, a ->
            if (i > 0) {
                lista.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(getColor(R.color.line))
                })
            }
            lista.addView(filaActividad(a))
        }
    }

    private fun filaActividad(a: Actividad): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { abrirFormulario(a) }
        }
        // De tal hora a tal hora, en 12 h (inicio arriba, fin debajo).
        fila.addView(TextView(this).apply {
            text = if (a.horaFin != null) "${HoraCronograma.legible(a.hora)}\n${HoraCronograma.legible(a.horaFin)}" else HoraCronograma.legible(a.hora)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(0f, 1.1f)
            layoutParams = LinearLayout.LayoutParams(dp(78), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        val texto = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val (color, fondo) = colorTipo(a.tipo)
        val cabecera = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cabecera.addView(TextView(this).apply {
            text = a.tipo.etiqueta
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(color))
            setBackgroundResource(fondo)
            setPadding(dp(8), dp(1), dp(8), dp(1))
        })
        if (!a.lugar.isNullOrBlank()) {
            cabecera.addView(TextView(this).apply {
                text = a.lugar
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(R.color.text_primary))
                setPadding(dp(8), 0, 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        texto.addView(cabecera)
        if (!a.nota.isNullOrBlank()) {
            texto.addView(TextView(this).apply {
                text = a.nota
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(3), 0, 0)
            })
        }
        val estado = listOfNotNull(
            if (a.conUbicacion || (a.lat != null && a.pendiente)) getString(R.string.crono_con_ubicacion) else null,
            if (a.pendiente) getString(R.string.crono_pendiente) else null,
        ).joinToString(" · ")
        if (estado.isNotEmpty()) {
            texto.addView(TextView(this).apply {
                text = estado
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(getColor(if (a.pendiente) R.color.warn else R.color.text_tertiary))
                setPadding(0, dp(3), 0, 0)
            })
        }
        fila.addView(texto)
        return fila
    }

    private fun colorTipo(tipo: TipoActividad): Pair<Int, Int> = when (tipo) {
        TipoActividad.VISITA -> R.color.navy_700 to R.drawable.ds_chip_neutral
        TipoActividad.ALMUERZO -> R.color.warn to R.drawable.ds_chip_pending
        TipoActividad.PERMISO_MEDICO -> R.color.primary to R.drawable.ds_chip_danger
        TipoActividad.VACACIONES -> R.color.ok to R.drawable.ds_chip_ok
        TipoActividad.PERMISO -> R.color.navy_700 to R.drawable.ds_chip_neutral
        TipoActividad.NOVEDAD -> R.color.primary to R.drawable.ds_chip_magenta
    }

    // ── Ubicación ──────────────────────────────────────────────────────────

    /** Último fix de la captura si es reciente (≤ 5 min). */
    private fun ubicacionActual(): Triple<Double, Double, Float>? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val at = prefs.getLong(PositionProvider.KEY_LAST_FIX_AT, 0L)
        if (at <= 0L || System.currentTimeMillis() - at > 5 * 60_000L) return null
        val lat = prefs.getString(PositionProvider.KEY_LAST_FIX_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString(PositionProvider.KEY_LAST_FIX_LON, null)?.toDoubleOrNull() ?: return null
        return Triple(lat, lon, prefs.getFloat(PositionProvider.KEY_LAST_FIX_ACC, -1f))
    }

    // ── Formulario ─────────────────────────────────────────────────────────


    private fun nueva(tipo: TipoActividad, hora: String, horaFin: String?, lugar: String?, nota: String?): Actividad {
        val fecha = fechaDe(dia)
        val conUbicacion = DmujeresApi.isJourneyOpen(this) && fecha == hoy()
        val ubic = if (conUbicacion) ubicacionActual() else null
        return Actividad(
            clientId = UUID.randomUUID().toString(),
            fecha = fecha,
            hora = hora,
            horaFin = horaFin,
            tipo = tipo,
            lugar = lugar,
            nota = nota,
            registradoEn = System.currentTimeMillis(),
            lat = ubic?.first,
            lon = ubic?.second,
            precision = ubic?.third?.takeIf { it >= 0 },
        )
    }

    private fun abrirFormulario(existente: Actividad?) {
        if (enMes) {
            // Desde el mes se agrega al día seleccionado (hoy por defecto).
            enMes = false
            pintar()
        }
        val vista = LayoutInflater.from(this).inflate(R.layout.dialog_actividad, null)
        val titulo = vista.findViewById<TextView>(R.id.act_titulo)
        val campoDesde = CampoHora(vista, R.id.act_desde, R.id.act_desde_am, R.id.act_desde_pm)
        val campoHasta = CampoHora(vista, R.id.act_hasta, R.id.act_hasta_am, R.id.act_hasta_pm)
        val lugar = vista.findViewById<AutoCompleteTextView>(R.id.act_lugar)
        val lugarLabel = vista.findViewById<TextView>(R.id.act_lugar_label)
        val nota = vista.findViewById<EditText>(R.id.act_nota)
        val notaLabel = vista.findViewById<TextView>(R.id.act_nota_label)
        val error = vista.findViewById<TextView>(R.id.act_error)

        titulo.setText(if (existente == null) R.string.crono_nueva else R.string.crono_editar)
        // Nueva: desde la hora actual (modificable) hasta una hora después.
        val inicio = existente?.hora ?: horaActual()
        campoDesde.poner(inicio)
        campoHasta.poner(existente?.horaFin ?: HoraCronograma.sumar(inicio, 60))
        lugar.setText(existente?.lugar.orEmpty())
        nota.setText(existente?.nota.orEmpty())
        lugar.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, Actividades.lugaresFrecuentes(this)))

        var tipo = existente?.tipo ?: TipoActividad.VISITA
        val chips = mutableMapOf<TipoActividad, TextView>()
        fun aplicarTipo() {
            chips.forEach { (t, v) ->
                v.isSelected = t == tipo
                v.setTextColor(getColor(if (t == tipo) R.color.white else R.color.text_primary))
            }
            val conLugar = tipo == TipoActividad.VISITA || tipo == TipoActividad.NOVEDAD
            lugarLabel.visibility = if (conLugar) View.VISIBLE else View.GONE
            lugar.visibility = lugarLabel.visibility
            notaLabel.setText(if (tipo == TipoActividad.NOVEDAD) R.string.crono_nota_obligatoria else R.string.crono_nota)
        }
        val fila1 = vista.findViewById<LinearLayout>(R.id.act_tipos_1)
        val fila2 = vista.findViewById<LinearLayout>(R.id.act_tipos_2)
        TipoActividad.entries.forEachIndexed { i, t ->
            val v = chip(t.etiqueta, t == tipo) {
                tipo = t
                aplicarTipo()
            }
            chips[t] = v
            (if (i < 3) fila1 else fila2).addView(v)
        }
        aplicarTipo()

        val builder = AlertDialog.Builder(this)
            .setView(vista)
            .setPositiveButton(R.string.crono_guardar, null)
            .setNegativeButton(R.string.crono_cancelar, null)
        if (existente != null) {
            builder.setNeutralButton(R.string.crono_eliminar) { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage(R.string.crono_confirmar_eliminar)
                    .setPositiveButton(R.string.crono_eliminar) { _, _ ->
                        Actividades.eliminar(this, existente)
                        pintar()
                    }
                    .setNegativeButton(R.string.crono_cancelar, null)
                    .show()
            }
        }
        val dialogo = builder.create()
        dialogo.setOnShowListener {
            dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val textoLugar = lugar.text.toString().trim().takeIf { it.isNotEmpty() && lugar.visibility == View.VISIBLE }
                val textoNota = nota.text.toString().trim().takeIf { it.isNotEmpty() }
                val falta = when {
                    tipo == TipoActividad.VISITA && textoLugar == null -> R.string.crono_error_lugar
                    tipo == TipoActividad.NOVEDAD && textoNota == null -> R.string.crono_error_nota
                    else -> null
                }
                if (falta != null) {
                    error.setText(falta)
                    error.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                val desde = campoDesde.leer()
                val hasta = campoHasta.leer()
                if (desde == null || hasta == null) {
                    error.setText(R.string.crono_error_hora)
                    error.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                if (HoraCronograma.aMinutos(hasta) <= HoraCronograma.aMinutos(desde)) {
                    error.setText(R.string.crono_error_rango)
                    error.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                val guardada = existente?.copy(
                    hora = desde,
                    horaFin = hasta,
                    tipo = tipo,
                    lugar = textoLugar,
                    nota = textoNota,
                ) ?: nueva(tipo, desde, hasta, textoLugar, textoNota)
                Actividades.guardar(this, guardada)
                dialogo.dismiss()
                pintar()
                Actividades.sincronizar(this) { runOnUiThread { if (!isFinishing && !isDestroyed) pintar() } }
            }
        }
        dialogo.show()
    }

    /** Hora actual exacta "HH:mm" (zona de Ecuador). */
    private fun horaActual(): String {
        val c = Calendar.getInstance(zona)
        return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    /**
     * Campo de hora con AM/PM: se escribe la hora y se elige AM o PM. Si se
     * escribe en 24 h ("14:30") se corrige solo a "02:30" con PM al salir del
     * campo. Devuelve la hora en 24 h para guardar.
     */
    private inner class CampoHora(vista: View, idTexto: Int, idAm: Int, idPm: Int) {
        private val texto = vista.findViewById<EditText>(idTexto)
        private val am = vista.findViewById<TextView>(idAm)
        private val pm = vista.findViewById<TextView>(idPm)
        private var esPm = false

        init {
            am.setOnClickListener { elegir(false) }
            pm.setOnClickListener { elegir(true) }
            texto.setOnFocusChangeListener { _, conFoco -> if (!conFoco) normalizar() }
        }

        private fun elegir(valor: Boolean) {
            esPm = valor
            am.isSelected = !valor
            pm.isSelected = valor
            am.setTextColor(getColor(if (!valor) R.color.white else R.color.text_primary))
            pm.setTextColor(getColor(if (valor) R.color.white else R.color.text_primary))
        }

        fun poner(hora24: String) {
            texto.setText(HoraCronograma.a12(hora24))
            elegir(HoraCronograma.esPm(hora24))
        }

        private fun normalizar() {
            val lectura = HoraCronograma.interpretar(texto.text.toString(), esPm) ?: return
            poner(lectura.hora24)
        }

        fun leer(): String? = HoraCronograma.interpretar(texto.text.toString(), esPm)?.hora24
    }

    private fun chip(texto: String, seleccionado: Boolean, alTocar: () -> Unit): TextView = TextView(this).apply {
        text = texto
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setTypeface(typeface, Typeface.BOLD)
        setBackgroundResource(R.drawable.ds_chip_tipo)
        isSelected = seleccionado
        setTextColor(getColor(if (seleccionado) R.color.white else R.color.text_primary))
        setPadding(dp(6), dp(9), dp(6), dp(9))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(6)
        }
        setOnClickListener { alTocar() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val KEY_EN_MES = "crono_en_mes"
        const val KEY_DIA = "crono_dia"
        const val KEY_MES = "crono_mes"
    }
}
