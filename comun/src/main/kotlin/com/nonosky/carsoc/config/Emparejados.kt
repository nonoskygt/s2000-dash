package com.nonosky.carsoc.config

import android.content.Context
import com.nonosky.carsoc.Carro

/**
 * Que aparato hace cada papel en ESTE carro.
 *
 * Antes de esto, las MAC vivian escritas en el codigo —`BancosBateria` y
 * `LectorNevera` las traian como constantes— y cambiar una bateria obligaba
 * a recompilar. Peor: el vigilante viejo ni siquiera las tenia, barria el
 * aire y se quedaba con el primer BMS que veia, que con dos bancos iguales
 * significa que cual te toca es cuestion de suerte. El dueño vio la bateria
 * de arranque bajo el rotulo de la de vivienda.
 *
 * Aqui cada papel tiene UN aparato, elegido a mano y guardado. **No hay valores
 * de fabrica**: si el dueño cambia el adaptador OBD, una bateria o la nevera,
 * elige el nuevo en el menu y funciona, sin recompilar nada. Los de fabrica
 * que hubo —escritos en el perfil de cada carro— son los que dejaron a una
 * app llamando durante meses a un adaptador OBD que no era el suyo.
 */
object Emparejados {

    /**
     * Los papeles que un aparato puede desempeñar.
     *
     * No todos existen en todos los carros: hay carros sin banco de
     * vivienda ni nevera. [papelesDeEsteCarro] filtra por perfil para que el
     * menu no ofrezca emparejar algo que este carro no lleva.
     */
    enum class Papel(val clave: String, val rotulo: String, val esBle: Boolean) {
        BancoArranque("banco_arranque", "Batería de arranque", true),
        BancoVivienda("banco_vivienda", "Batería de vivienda", true),
        Nevera("nevera", "Refrigeradora", true),
        AdaptadorObd("obd", "Adaptador OBD-II", false),
    }

    private const val PREFS = "emparejados"

    fun papelesDeEsteCarro(): List<Papel> = buildList {
        add(Papel.BancoArranque)
        if (Carro.perfil.tieneBancoVivienda) add(Papel.BancoVivienda)
        if (Carro.perfil.tieneNevera) add(Papel.Nevera)
        add(Papel.AdaptadorObd)
    }

    /**
     * La MAC que eligio el dueño, o cadena vacia si no eligio ninguna. Quien
     * llame DEBE tratar el vacio como "sin aparato", y no inventarse uno.
     */
    fun mac(context: Context, p: Papel): String =
        prefs(context).getString("${p.clave}_mac", null)?.takeIf { it.isNotBlank() }.orEmpty()

    fun nombre(context: Context, p: Papel): String =
        prefs(context).getString("${p.clave}_nombre", null).orEmpty()

    /** ¿Hay aparato asignado a este papel? */
    fun hay(context: Context, p: Papel): Boolean = mac(context, p).isNotBlank()

    fun asignar(context: Context, p: Papel, mac: String, nombre: String?) {
        prefs(context).edit()
            .putString("${p.clave}_mac", mac.trim().uppercase())
            .putString("${p.clave}_nombre", nombre?.trim().orEmpty())
            .apply()
    }

    /** Olvida el aparato de este papel: queda sin asignar hasta que se elija otro. */
    fun olvidar(context: Context, p: Papel) {
        prefs(context).edit()
            .remove("${p.clave}_mac")
            .remove("${p.clave}_nombre")
            .apply()
    }

    /** Para el puente HTTP y para la pantalla de configuracion. */
    fun resumen(context: Context): List<String> = papelesDeEsteCarro().map { p ->
        val mac = mac(context, p)
        if (mac.isBlank()) "${p.rotulo}: SIN ASIGNAR"
        else "${p.rotulo}: $mac  ${nombre(context, p)}"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
