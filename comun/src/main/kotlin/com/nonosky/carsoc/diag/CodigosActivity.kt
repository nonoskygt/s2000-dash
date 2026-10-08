package com.nonosky.carsoc.diag

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nonosky.carsoc.Carro

/**
 * ESCANEAR CODIGO: leer los del carro y consultar la base de codigos.
 *
 * Arriba, el boton que lee la computadora del motor, y la de la caja si es
 * automatica: la pantalla de diagnostico de siempre. Debajo, la base entera:
 * cada codigo que la tabla de
 * este carro explica, agrupado por sistema y con buscador. Un codigo que no
 * esta en la tabla igual dice de que trata ([GrupoDtc]).
 *
 * Vistas nativas, como las bolsas de aire: es texto largo que se lee parado.
 */
class CodigosActivity : Activity() {

    private lateinit var raiz: LinearLayout
    private lateinit var lista: LinearLayout
    private var entradas: List<TablaDtc.Entrada> = emptyList()

    /** Los codigos abiertos (mostrando su explicacion). */
    private val abiertos = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(FONDO)
            setPadding(dp(18), dp(14), dp(18), dp(24))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(FONDO)
            addView(raiz, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
        // Copia propia, no la de TablaDtc: el diagnostico la suelta al cerrarse.
        entradas = TablaDtc.leer(applicationContext).values.sortedBy { it.codigo }
        pintar()
    }

    private fun pintar() {
        raiz.removeAllViews()
        raiz.addView(boton("← Volver", ARENA) { finish() })
        // Solo se habla de "caja" si la tabla del carro trae codigos de caja:
        // un carro de caja manual no tiene computadora de transmision.
        val conCaja = entradas.any { GrupoDtc.de(it.codigo)?.titulo == "Caja de cambios" }
        val que = if (conCaja) "motor y caja" else "motor"
        raiz.addView(titulo("Códigos de $que"))
        raiz.addView(nota(Carro.perfil.vehiculo))

        raiz.addView(boton("ESCANEAR EL CARRO AHORA", VIVO) {
            startActivity(Intent(this, DiagnosticoActivity::class.java))
        }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.topMargin = dp(8) }
            gravity = Gravity.CENTER
        })
        raiz.addView(nota(
            "Lee los códigos guardados y los pendientes de la computadora del " +
                (if (conCaja) "motor y la caja" else "motor") + ", con el adaptador OBD."
        ))

        raiz.addView(subtitulo("Base de códigos"))
        if (entradas.isEmpty()) {
            raiz.addView(parrafo("Todavía no hay tabla de códigos para este carro.", TINTA))
            return
        }
        raiz.addView(nota(
            "${entradas.size} códigos explicados para este carro. Escribe un código " +
                "(P0300) o palabras (" + (if (conCaja) "caja, " else "") + "sensor, " +
                "aceite). Rojo es grave, ámbar pide atención, verde es leve."
        ))

        val campo = EditText(this).apply {
            hint = "código o palabra"
            setHintTextColor(APAGADO)
            setTextColor(TINTA)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            // El ✓ del teclado o un Enter cierran el teclado: abierto, tapa
            // justo el resultado.
            setOnEditorActionListener { v, accion, tecla ->
                val enter = tecla?.keyCode == KeyEvent.KEYCODE_ENTER &&
                    tecla.action == KeyEvent.ACTION_DOWN
                if (accion == EditorInfo.IME_ACTION_DONE || enter) {
                    (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                        ?.hideSoftInputFromWindow(v.windowToken, 0)
                    v.clearFocus()
                    true
                } else {
                    false
                }
            }
            setBackgroundColor(TARJETA)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.bottomMargin = dp(10) }
        }
        raiz.addView(campo)

        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        raiz.addView(lista)
        pintarLista("")
        campo.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) { pintarLista(s?.toString().orEmpty()) }
        })
    }

    private fun pintarLista(filtro: String) {
        lista.removeAllViews()
        val q = filtro.trim()
        if (q.isEmpty()) {
            // Sin filtro, toda la base agrupada por sistema, en orden de codigo.
            val porGrupo = LinkedHashMap<String, MutableList<TablaDtc.Entrada>>()
            entradas.forEach { e ->
                val g = GrupoDtc.de(e.codigo)?.titulo ?: "Otros"
                porGrupo.getOrPut(g) { mutableListOf() } += e
            }
            porGrupo.forEach { (grupo, es) ->
                lista.addView(subtitulo("$grupo (${es.size})"))
                es.forEach { lista.addView(filaCodigo(it, false)) }
            }
            return
        }

        val hallados = BuscarDtc.filtrar(entradas, q)
        if (hallados.isEmpty()) {
            val g = GrupoDtc.de(q)
            if (g != null) {
                lista.addView(caja(
                    "${q.uppercase()} no tiene ficha propia en la tabla de este carro. " +
                        "Esto es lo que dice su grupo:",
                    AMBAR,
                ))
                lista.addView(parrafo(g.titulo, TINTA).apply { typeface = Typeface.DEFAULT_BOLD })
                lista.addView(parrafo(g.explicacion, TINTA))
                lista.addView(parrafo(g.queHacer, ARENA))
            } else {
                lista.addView(caja("Ningún código de la base tiene \"$q\".", AMBAR))
            }
            return
        }
        lista.addView(nota("${hallados.size} encontrado(s)"))
        // El codigo escrito tal cual se abre solo: es el que el dueño busca. Los
        // demas son los que lo citan, y van cerrados debajo. Sin codigo exacto,
        // si quedan uno o dos se abren.
        val exacto = hallados.firstOrNull { it.codigo.equals(q, ignoreCase = true) }
        hallados.forEach { e ->
            val abrir = if (exacto != null) e === exacto else hallados.size <= 2
            lista.addView(filaCodigo(e, abrir))
        }
    }

    private fun filaCodigo(e: TablaDtc.Entrada, abierto: Boolean): View {
        val color = when (e.gravedad) {
            TablaDtc.Gravedad.GRAVE -> ROJO
            TablaDtc.Gravedad.ATENCION -> AMBAR
            TablaDtc.Gravedad.LEVE -> VIVO
        }
        val visible = abierto || e.codigo in abiertos

        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            setBackgroundColor(TARJETA)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.bottomMargin = dp(8) }
            isClickable = true
        }
        fila.addView(TextView(this).apply {
            text = e.codigo
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        })
        fila.addView(TextView(this).apply {
            text = e.titulo
            setTextColor(TINTA)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(3), 0, 0)
        })
        val detalle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (visible) View.VISIBLE else View.GONE
        }
        detalle.addView(TextView(this).apply {
            text = e.explicacion
            setTextColor(TINTA)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(8), 0, 0)
        })
        detalle.addView(TextView(this).apply {
            text = "Causas más comunes: ${e.causas}"
            setTextColor(ARENA)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(6), 0, 0)
        })
        fila.addView(detalle)
        val pista = TextView(this).apply {
            text = "toca para ver qué significa"
            setTextColor(APAGADO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, 0)
            visibility = if (visible) View.GONE else View.VISIBLE
        }
        fila.addView(pista)
        fila.setOnClickListener {
            val ver = detalle.visibility != View.VISIBLE
            detalle.visibility = if (ver) View.VISIBLE else View.GONE
            pista.visibility = if (ver) View.GONE else View.VISIBLE
            if (ver) abiertos += e.codigo else abiertos -= e.codigo
        }
        return fila
    }

    // ------------------------------------------------------------- piezas UI

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    private fun titulo(t: String) = TextView(this).apply {
        text = t
        setTextColor(TINTA)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun subtitulo(t: String) = TextView(this).apply {
        text = t
        setTextColor(ARENA)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun nota(t: String) = TextView(this).apply {
        text = t
        setTextColor(APAGADO)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(2), 0, dp(8))
    }

    private fun parrafo(t: String, color: Int) = TextView(this).apply {
        text = t
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setLineSpacing(0f, 1.15f)
        setPadding(0, dp(3), 0, dp(5))
    }

    private fun caja(t: String, color: Int) = TextView(this).apply {
        text = t
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setLineSpacing(0f, 1.15f)
        setBackgroundColor(TARJETA_AVISO)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            .also { it.topMargin = dp(8); it.bottomMargin = dp(8) }
    }

    private fun boton(t: String, color: Int, alTocar: () -> Unit) = TextView(this).apply {
        text = t
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(46)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setBackgroundColor(TARJETA)
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        isClickable = true
        setOnClickListener { alTocar() }
    }

    private companion object {
        val FONDO = Color.parseColor("#131715")
        val TARJETA = Color.parseColor("#1A201C")
        val TARJETA_AVISO = Color.parseColor("#2A2A1E")
        val TINTA = Color.parseColor("#EDE4D3")
        val ARENA = Color.parseColor("#BEB39A")
        val APAGADO = Color.parseColor("#8E968A")
        val VIVO = Color.parseColor("#9CBE7A")
        val AMBAR = Color.parseColor("#D9A55B")
        val ROJO = Color.parseColor("#E5675A")
    }
}
