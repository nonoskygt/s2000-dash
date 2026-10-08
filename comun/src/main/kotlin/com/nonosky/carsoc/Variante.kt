package com.nonosky.carsoc

import android.content.Context

/**
 * QUE TABLERO SE PINTA: el de HTML o el de Canvas.
 *
 * Las dos variantes dibujan lo mismo con los mismos datos (ver
 * [EstadoDelTablero]); lo que cambia es quien las pinta y lo que cuestan.
 *
 * - **html** — un `WebView` sobre `assets/tablero.html`. Es lo que hay hoy en
 *   el carro, se itera sin recompilar, y repinta a lo que quiera el navegador.
 * - **lienzo** — `TableroLienzo`, un `View` con `Canvas`. Repinta al ritmo que
 *   manda `Termometro.msEntreCuadros()` —5 cuadros por segundo con el radio
 *   fresco, UNO con el radio caliente— que es media razon de ser de la
 *   variante: este head unit ya se apago dos veces por calor.
 *
 * ## Por que la omision es "html"
 *
 * Porque es lo que funciona en el carro hoy. Un tablero no cambia de piel por
 * sorpresa: el dueño arranca el carro y ve lo de siempre, y la variante nueva
 * la elige el, o desde el menu de ajustes o desde el propio tablero. Si un dia
 * el Canvas demuestra en el carro que gasta menos, se cambia la omision de esta
 * linea y no de cinco sitios.
 */
object Variante {

    private const val PREFS = "tablero"
    private const val CLAVE = "variante"

    /** El tablero HTML sobre WebView. La omision. */
    const val HTML = "html"

    /** El tablero Canvas nativo. */
    const val LIENZO = "lienzo"

    /**
     * Cual esta elegida. Cualquier cosa que no sea [LIENZO] es [HTML]: ante una
     * preferencia corrompida o de una version futura, se cae del lado que se
     * sabe que funciona en el carro.
     */
    fun actual(context: Context): String =
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(CLAVE, HTML) == LIENZO
        ) LIENZO else HTML

    /** ¿Vale ese nombre? Se pregunta ANTES de guardar lo que venga de fuera. */
    fun valida(cual: String?): Boolean = cual == HTML || cual == LIENZO

    /**
     * Guarda la eleccion. Devuelve false si el nombre no existe, sin tocar
     * nada: el JavaScript del tablero puede llamar con cualquier cadena, y
     * guardar basura dejaria el arranque decidiendose por un `else`.
     *
     * Se escribe con `commit()` y no con `apply()` a proposito: quien llama a
     * esto reinicia la pantalla acto seguido, y con `apply()` la escritura
     * podria no haber llegado al disco cuando la Activity nueva la lee.
     */
    fun poner(context: Context, cual: String): Boolean {
        if (!valida(cual)) return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(CLAVE, cual).commit()
    }

    /** La otra. Para el interruptor de una sola fila del menu de ajustes. */
    fun contraria(cual: String): String = if (cual == LIENZO) HTML else LIENZO

    /** Como se llama en pantalla, que "lienzo" no le dice nada a nadie. */
    fun rotulo(cual: String): String =
        if (cual == LIENZO) "Canvas nativo" else "HTML sobre WebView"
}
