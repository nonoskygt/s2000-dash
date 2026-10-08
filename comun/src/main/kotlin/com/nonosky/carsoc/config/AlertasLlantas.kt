package com.nonosky.carsoc.config

import android.content.Context
import com.nonosky.carsoc.tpms.TramaTpms

/**
 * Cuando avisa una llanta: presion BAJA, presion ALTA o temperatura ALTA.
 *
 * Los tres umbrales los pone el dueño en Ajustes, en la app de su carro. Antes
 * solo habia aviso de presion baja, con un umbral fijo escrito en el codigo
 * comun —el 75 % de la presion de placa de OTRO carro—, y la notificacion lo
 * comparaba contra la presion SIN calibrar.
 *
 * Una sola regla para todo: la notificacion del servicio, el tablero HTML y
 * el Canvas preguntan aqui, y siempre con la presion ya calibrada. Si cada
 * uno decidiera por su cuenta, una rueda podria sonar en el radio y salir
 * tranquila en la pantalla.
 */
object AlertasLlantas {

    enum class Tipo(val texto: String) { Baja("baja"), Alta("alta"), Caliente("caliente") }

    private const val PREFS = "alertas_llantas"
    private const val K_BAJA = "psi_baja"
    private const val K_ALTA = "psi_alta"
    private const val K_TEMP = "temp_alta"

    /** Lo que habia antes de poder configurarlo: el aviso de baja salia en 24. */
    const val PSI_BAJA_POR_OMISION = 24f
    const val PSI_ALTA_POR_OMISION = 40f
    /** El rojo de temperatura del tablero de antes. */
    const val TEMP_ALTA_POR_OMISION = 80

    // Los rangos de baja y alta se tocan en 35 y no se cruzan: con la baja
    // por encima de la alta, una llanta perfecta saldria "baja" y "alta" a
    // la vez. Asi el deslizador no deja llegar ahi.
    val RANGO_BAJA = 10f..35f
    val RANGO_ALTA = 35f..60f
    val RANGO_TEMP = 50..110
    const val PASO_PSI = 0.5f

    fun psiBaja(context: Context?): Float =
        prefs(context)?.getFloat(K_BAJA, PSI_BAJA_POR_OMISION) ?: PSI_BAJA_POR_OMISION

    fun psiAlta(context: Context?): Float =
        prefs(context)?.getFloat(K_ALTA, PSI_ALTA_POR_OMISION) ?: PSI_ALTA_POR_OMISION

    fun tempAlta(context: Context?): Int =
        prefs(context)?.getInt(K_TEMP, TEMP_ALTA_POR_OMISION) ?: TEMP_ALTA_POR_OMISION

    fun ponerPsiBaja(context: Context, v: Float) {
        prefs(context)?.edit()?.putFloat(K_BAJA, v.coerceIn(RANGO_BAJA))?.apply()
    }

    fun ponerPsiAlta(context: Context, v: Float) {
        prefs(context)?.edit()?.putFloat(K_ALTA, v.coerceIn(RANGO_ALTA))?.apply()
    }

    fun ponerTempAlta(context: Context, v: Int) {
        prefs(context)?.edit()?.putInt(K_TEMP, v.coerceIn(RANGO_TEMP))?.apply()
    }

    /**
     * Que le pasa a la rueda [rueda], o null si nada.
     *
     * La presion se compara YA CALIBRADA. Una lectura fuera de lo fisicamente
     * posible no dispara nada: casi siempre es el decodificador, no la llanta,
     * y una alarma falsa gasta la credibilidad de la siguiente. Si hay dos
     * cosas a la vez manda la presion, que es la que revienta.
     */
    fun evaluar(
        context: Context?,
        rueda: Int,
        trama: TramaTpms?,
        reglas: Reglas = reglas(context),
    ): Tipo? {
        if (trama == null) return null
        val psi = if (trama.presionFueraDeRango) null else {
            if (context == null) trama.presionPsi
            else CalibracionLlantas.corregir(context, rueda, trama.presionPsi)
        }
        val temp = if (trama.temperaturaFueraDeRango) null else trama.temperaturaC
        return reglas.evaluar(psi, temp)
    }

    /** Los tres umbrales de Ajustes, leidos una vez para las cuatro ruedas. */
    fun reglas(context: Context?): Reglas =
        Reglas(psiBaja(context), psiAlta(context), tempAlta(context))

    /** Los tres umbrales juntos; aparte para poder probarlo sin Android. */
    data class Reglas(val psiBaja: Float, val psiAlta: Float, val tempAlta: Int) {
        fun evaluar(psi: Float?, tempC: Int?): Tipo? = when {
            psi != null && psi < psiBaja -> Tipo.Baja
            psi != null && psi > psiAlta -> Tipo.Alta
            tempC != null && tempC >= tempAlta -> Tipo.Caliente
            else -> null
        }
    }

    private fun prefs(context: Context?) =
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
