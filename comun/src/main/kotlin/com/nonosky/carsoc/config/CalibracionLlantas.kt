package com.nonosky.carsoc.config

import android.content.Context

/**
 * Corrección de la presión que reportan los sensores TPMS.
 *
 * Estos sensores son baratos y **imprecisos**: dos ruedas infladas al mismo
 * valor con el mismo manómetro pueden reportar una libra de diferencia. La
 * calibración es la resta entre lo que dice el sensor y lo que dice el
 * manómetro bueno.
 *
 * ⚠️ SE APLICA EN LA CAPA DE DATOS, NO AL PINTAR. Si solo corrigiera lo que
 * se dibuja, el **detector de pinchazo y el aviso de presión baja seguirían
 * mirando el valor sin corregir** — y una rueda calibrada a −3 PSI daría la
 * alarma tres libras antes de tiempo, todos los días, hasta que el dueño
 * aprendiera a ignorarla. Corregido aquí, todo el sistema ve el mismo
 * número.
 *
 * La corrección es un DESPLAZAMIENTO, no un factor. Un sensor barato se
 * desvía por una constante de fábrica, no proporcionalmente, y un factor
 * multiplicativo se comportaría distinto a 20 y a 40 PSI.
 */
object CalibracionLlantas {

    private const val PREFS = "calibracion_llantas"
    private const val CLAVE_TODAS = "aplicar_a_todas"

    /**
     * Cuánto mueve cada toque.
     *
     * Media libra: los sensores reportan en pasos de aproximadamente una
     * libra, así que afinar más fino sería fingir una precisión que el
     * aparato no tiene. Y un manómetro de taller tampoco distingue menos.
     */
    const val PASO_PSI = 0.5f

    /**
     * Tope de corrección, arriba y abajo.
     *
     * ⚠️ Existe como GUARDA, no como comodidad. Si alguien necesita corregir
     * más de 10 PSI, lo que tiene no es un sensor descalibrado: es un sensor
     * roto, la rueda equivocada emparejada, o una fuga. Dejar corregir 20
     * libras convertiría el tablero en una forma de esconder una avería.
     */
    const val TOPE_PSI = 10f

    /** Los cuatro índices, en el mismo orden que el resto del proyecto. */
    const val RUEDAS = 4

    /**
     * ¿Un toque corrige las cuatro ruedas a la vez?
     *
     * Encendido por omisión, y con motivo: lo normal es que el desvío venga
     * del juego entero de sensores o de comparar contra un manómetro
     * distinto, así que corregir las cuatro es lo que acierta casi siempre.
     * Quien tenga una rueda concreta desviada lo apaga y la corrige sola.
     */
    fun aplicarATodas(context: Context): Boolean =
        prefs(context).getBoolean(CLAVE_TODAS, true)

    fun ponerAplicarATodas(context: Context, valor: Boolean) {
        prefs(context).edit().putBoolean(CLAVE_TODAS, valor).apply()
    }

    /** La corrección guardada para esa rueda, en PSI. Cero si no hay. */
    fun ajuste(context: Context, rueda: Int): Float {
        if (rueda !in 0 until RUEDAS) return 0f
        return prefs(context).getFloat(clave(rueda), 0f)
    }

    /**
     * Mueve la corrección de [rueda] en [pasos] de [PASO_PSI].
     *
     * Si [aplicarATodas] está encendido, mueve las cuatro. Devuelve la
     * corrección resultante de la rueda tocada.
     */
    fun mover(context: Context, rueda: Int, pasos: Int): Float {
        val e = prefs(context).edit()
        val cuales = if (aplicarATodas(context)) 0 until RUEDAS else rueda..rueda
        var resultado = 0f
        for (r in cuales) {
            if (r !in 0 until RUEDAS) continue
            val nuevo = (ajuste(context, r) + pasos * PASO_PSI)
                .coerceIn(-TOPE_PSI, TOPE_PSI)
            e.putFloat(clave(r), nuevo)
            if (r == rueda) resultado = nuevo
        }
        e.apply()
        return if (rueda in 0 until RUEDAS) resultado else 0f
    }

    /** Deja esa rueda —o las cuatro, si está puesto— sin corrección. */
    fun poner(context: Context, rueda: Int, psi: Float) {
        val e = prefs(context).edit()
        val v = psi.coerceIn(-TOPE_PSI, TOPE_PSI)
        val cuales = if (aplicarATodas(context)) 0 until RUEDAS else rueda..rueda
        for (r in cuales) if (r in 0 until RUEDAS) e.putFloat(clave(r), v)
        e.apply()
    }

    /**
     * La correccion que tienen las cuatro si es la MISMA, o null si cada una
     * tiene la suya. Lo usa el deslizador de Ajustes para arrancar donde esta.
     */
    fun ajusteComun(context: Context): Float? {
        val a = (0 until RUEDAS).map { ajuste(context, it) }.distinct()
        return a.singleOrNull()
    }

    /**
     * La misma correccion en las cuatro ruedas, haya o no "aplicar a todas"
     * encendido: es lo que hace el deslizador unico de Ajustes.
     */
    fun ponerEnTodas(context: Context, psi: Float) {
        val v = psi.coerceIn(-TOPE_PSI, TOPE_PSI)
        val e = prefs(context).edit()
        for (r in 0 until RUEDAS) e.putFloat(clave(r), v)
        e.apply()
    }

    fun olvidarTodo(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /**
     * Aplica la corrección a una lectura.
     *
     * `null` entra y `null` sale: **una rueda sin lectura no se convierte en
     * una rueda con lectura porque haya una corrección guardada.** Sumarle
     * el ajuste a un hueco inventaría una presión.
     *
     * El resultado no baja de cero: una presión negativa no existe, y
     * pintarla haría dudar del tablero entero antes que de la calibración.
     */
    fun corregir(context: Context, rueda: Int, psi: Float?): Float? {
        if (psi == null) return null
        val a = ajuste(context, rueda)
        if (a == 0f) return psi
        return (psi + a).coerceAtLeast(0f)
    }

    /** Para el puente HTTP y la pantalla de configuración. */
    fun resumen(context: Context): List<String> {
        val nombres = listOf("DI", "DD", "TI", "TD")
        val cabecera = "aplicar a todas: " +
            (if (aplicarATodas(context)) "sí" else "no") +
            "   paso: $PASO_PSI PSI   tope: ±$TOPE_PSI PSI"
        return listOf(cabecera) + (0 until RUEDAS).map { r ->
            val a = ajuste(context, r)
            "  ${nombres[r]}  " + (if (a == 0f) "sin corregir" else "%+.1f PSI".format(a))
        }
    }

    private fun clave(rueda: Int) = "ajuste_$rueda"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
