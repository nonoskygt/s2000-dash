package com.nonosky.carsoc.diag

import android.app.Activity
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
 * La luz de la BOLSA DE AIRE: que codigo es y que significa.
 *
 * ## Lo primero que dice esta pantalla, y por que
 *
 * Que el adaptador OBD NO lee esta luz. La computadora de las bolsas de aire
 * es otra unidad, con un protocolo propio de Honda, y el OBD-II generico no
 * llega a ella. Si esto no se dijera, el dueño leeria "SIN AVERIAS" en la
 * pantalla del motor con la luz SRS encendida y concluiria que no pasa nada.
 *
 * El codigo SI se puede sacar sin herramientas: la propia luz lo cuenta en
 * parpadeos con el conector de servicio puenteado. Esta pantalla explica como,
 * y deja escribir lo que se conto para traducirlo a palabras.
 *
 * Vistas nativas y a mano, como la configuracion: es texto largo que se lee
 * con el carro parado, y un Canvas no gana nada aqui.
 */
class BolsasDeAireActivity : Activity() {

    private lateinit var raiz: LinearLayout
    private lateinit var lista: LinearLayout
    private var tabla: TablaSrs.Tabla = TablaSrs.Tabla(emptyList(), emptyList(), emptyList())

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
        tabla = TablaSrs.cargar(applicationContext)
        pintar()
    }

    override fun onDestroy() {
        TablaSrs.soltar()
        super.onDestroy()
    }

    private fun pintar() {
        raiz.removeAllViews()
        raiz.addView(boton("← Volver") { finish() })
        raiz.addView(titulo("Bolsas de aire (SRS)"))
        raiz.addView(nota(Carro.perfil.vehiculo))

        if (tabla.vacia) {
            raiz.addView(parrafo("Todavía no hay tabla de códigos de bolsa de aire para este carro.", TINTA))
            return
        }

        raiz.addView(caja(
            "El adaptador OBD NO lee esta luz. La bolsa de aire tiene su propia " +
                "computadora, distinta de la del motor, y habla un idioma de Honda al " +
                "que el OBD-II normal no llega. Por eso \"LEER CÓDIGOS\" puede decir " +
                "SIN AVERÍAS con la luz SRS encendida: no la está mirando.",
            AMBAR,
        ))

        if (tabla.pasos.isNotEmpty()) {
            raiz.addView(subtitulo("Cómo saber el código con la propia luz"))
            tabla.pasos.forEachIndexed { i, paso ->
                raiz.addView(parrafo("${i + 1}. $paso", TINTA))
            }
        }
        if (tabla.advertencias.isNotEmpty()) {
            raiz.addView(subtitulo("Antes de tocar nada"))
            tabla.advertencias.forEach { raiz.addView(parrafo("• $it", ROJO_SUAVE)) }
        }

        raiz.addView(subtitulo("¿Qué código te dio?"))
        raiz.addView(nota("Escribe lo que contaste (por ejemplo 1-1, 23 o 11-01). La lista se filtra sola."))
        val campo = EditText(this).apply {
            hint = "código"
            setHintTextColor(APAGADO)
            setTextColor(TINTA)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            inputType = InputType.TYPE_CLASS_PHONE
            // El ✓ del teclado lo cierra: abierto, tapaba justo el resultado.
            imeOptions = EditorInfo.IME_ACTION_DONE
            // Las dos formas de decir "listo": el ✓ del teclado en pantalla
            // (IME_ACTION_DONE) y un Enter de teclado fisico o del volante,
            // que llega como tecla y no como accion.
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
        val hay = filtro.any { it.isDigit() }
        val codigos = if (hay) tabla.buscar(filtro) else tabla.codigos
        if (hay && codigos.isEmpty()) {
            lista.addView(caja(
                "Ese código no está en la tabla de este carro. Vuelve a contar: los " +
                    "parpadeos largos son las decenas y los cortos las unidades. Si se " +
                    "repite igual, anótalo: lo que no está aquí lo tiene que leer un " +
                    "escáner que hable el SRS de Honda.",
                AMBAR,
            ))
            return
        }
        if (!hay) lista.addView(subtitulo("Todos los códigos (${codigos.size})"))
        // Si el filtro deja uno o dos, se abren: son los que el dueño busca.
        val abrir = hay && codigos.size <= 2
        codigos.forEach { lista.addView(filaCodigo(it, abrir)) }
    }

    private fun filaCodigo(c: TablaSrs.Codigo, abierto: Boolean): View {
        val clave = c.parpadeo + "|" + c.hds + "|" + c.titulo
        val color = when (c.gravedad) {
            TablaDtc.Gravedad.GRAVE -> ROJO
            TablaDtc.Gravedad.ATENCION -> AMBAR
            TablaDtc.Gravedad.LEVE -> VIVO
        }
        val codigos = listOfNotNull(
            c.parpadeos.takeIf { it.isNotEmpty() }?.let { "luz " + it.joinToString(" · ") },
            c.hds.takeIf { it.isNotEmpty() }?.let { "escáner $it" },
        ).joinToString("  ·  ")
        val visible = abierto || clave in abiertos

        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            setBackgroundColor(TARJETA)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.bottomMargin = dp(8) }
            isClickable = true
        }
        fila.addView(TextView(this).apply {
            text = codigos
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        })
        fila.addView(TextView(this).apply {
            text = "${c.componente}: ${c.titulo}"
            setTextColor(TINTA)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(3), 0, 0)
        })
        val detalle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (visible) View.VISIBLE else View.GONE
        }
        detalle.addView(TextView(this).apply {
            text = c.explicacion
            setTextColor(TINTA)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(8), 0, 0)
        })
        if (c.queRevisar.isNotEmpty()) detalle.addView(TextView(this).apply {
            text = "Qué revisar: ${c.queRevisar}"
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
            if (ver) abiertos += clave else abiertos -= clave
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

    private fun boton(t: String, alTocar: () -> Unit) = TextView(this).apply {
        text = t
        setTextColor(ARENA)
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
        val ROJO_SUAVE = Color.parseColor("#E8A49B")
    }
}
