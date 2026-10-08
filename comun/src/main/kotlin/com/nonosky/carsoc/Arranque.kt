package com.nonosky.carsoc

import android.content.Context

/**
 * ¿Se abre el tablero solo al encender el radio?
 *
 * Lo decide el dueño en Ajustes. Por omision SI, que es como funcionaba.
 *
 * Solo manda sobre la PANTALLA. El servicio arranca igual con el sistema: las
 * alertas de llanta baja, las baterias y el contador del aceite no dependen de
 * que el tablero este a la vista, y apagarlos con este interruptor seria
 * esconder un pinchazo para quitarse una pantalla de encima.
 */
object Arranque {

    private const val PREFS = "tablero"
    private const val CLAVE = "abrir_al_encender"

    fun abrirAlEncender(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CLAVE, true)

    fun poner(context: Context, abrir: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(CLAVE, abrir).apply()
    }
}
