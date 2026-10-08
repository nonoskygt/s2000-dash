package com.nonosky.carsoc.config

import android.content.Context
import org.json.JSONObject

/**
 * Como dejo el dueño los cuadros del tablero: cuales oculto, en que orden y
 * de que tamaño. Lo escribe el tablero HTML (sosteniendo el dedo sobre un
 * cuadro) por el Puente; aqui solo se guarda y se lee.
 *
 * Vive en las preferencias de la app y no en el WebView: el almacenamiento
 * del WebView esta apagado a proposito, y asi Ajustes puede deshacerlo.
 */
object CuadrosTablero {

    private const val PREFS = "tablero"
    private const val CLAVE = "cuadros"

    /** Mas que de sobra para seis cuadros. Lo que venga mas largo no es nuestro. */
    private const val TOPE = 4_000

    fun leer(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CLAVE, "").orEmpty()

    /** Guarda lo que manda el tablero, solo si tiene forma de lo que el tablero manda. */
    fun guardar(context: Context, json: String): Boolean {
        if (json.length > TOPE) return false
        if (runCatching { JSONObject(json) }.isFailure) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CLAVE, json).apply()
        return true
    }

    /** Todos a la vista, en su orden y a su tamaño de siempre. */
    fun restaurar(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(CLAVE).apply()
    }

    /** Cuantos cuadros estan ocultos. 0 si no hay nada guardado. */
    fun ocultos(context: Context): Int = runCatching {
        JSONObject(leer(context)).optJSONArray("ocultos")?.length() ?: 0
    }.getOrDefault(0)

    /** ¿Cambio algo el dueño? Orden, tamaño u ocultos. */
    fun hayCambios(context: Context): Boolean = leer(context).isNotBlank()
}
