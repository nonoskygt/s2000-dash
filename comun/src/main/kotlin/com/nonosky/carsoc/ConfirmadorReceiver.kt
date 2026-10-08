package com.nonosky.carsoc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Recoge lo que el confirmador cuenta de las ventanas que ve.
 *
 * El confirmador no tiene pantalla ni sus logs se pueden leer sin root, asi
 * que sin este canal su unico sintoma posible es "no pasa nada". Aqui se
 * guarda lo que dice y el puente lo expone.
 */
class ConfirmadorReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val t = intent.getStringExtra("texto") ?: return
        Log.i("ConfirmadorDice", t)
        EstadoActual.anotarConfirmador(t)
    }
}
