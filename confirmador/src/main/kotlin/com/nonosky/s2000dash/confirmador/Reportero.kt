package com.nonosky.s2000dash.confirmador

import android.content.Context
import android.content.Intent

/**
 * Canal de vuelta hacia el tablero.
 *
 * El confirmador no tiene pantalla y sus logs no se pueden leer sin root, asi
 * que sin esto su unico sintoma posible es "no pasa nada" — que no distingue
 * entre accesibilidad apagada, mando mal escrito y servicio caido.
 *
 * Vive aparte del servicio porque tambien lo necesita el receptor de mandos,
 * que corre cuando el servicio puede no estar.
 */
object Reportero {

    fun decir(context: Context, texto: String) {
        runCatching {
            context.sendBroadcast(
                Intent(ACCION)
                    .setPackage(PAQUETE_TABLERO)
                    .putExtra("texto", texto)
            )
        }
    }

    const val ACCION = "com.nonosky.s2000dash.CONFIRMADOR_DICE"
    const val PAQUETE_TABLERO = "com.nonosky.s2000dash"
}
