package com.nonosky.carsoc

/**
 * El carro de ESTA aplicacion. Lo instala la `Application` de cada app antes
 * de que arranque nada mas.
 *
 * ## Por que existe
 *
 * Son DOS aplicaciones, cada una en su modulo y con su propio paquete. Lo que
 * comparten (Bluetooth, OBD, baterias, llantas, tablero) vive en esta
 * libreria, que NO sabe en que carro corre. Todo lo que es de un carro
 * concreto entra por aqui, y solo por aqui.
 *
 * Antes las dos salian del mismo modulo con dos sabores, y las cosas de un
 * carro se colaban en el otro sin que nada lo impidiera: una app llamaba
 * toda su vida a un adaptador OBD que no era el suyo, su notificacion llevaba
 * el nombre de la otra app y su auto-actualizacion buscaba un servidor ajeno.
 * Con el carro como dato que la app tiene que entregar, la libreria ya no
 * puede traer uno de fabrica: si falta, revienta al arrancar en vez de usar
 * el del otro carro.
 */
object Carro {

    @Volatile private var p: PerfilVehiculo? = null
    @Volatile private var m: Motor? = null

    val perfil: PerfilVehiculo
        get() = p ?: error("Carro sin instalar: la Application de la app debe llamar a Carro.instalar()")

    val motor: Motor
        get() = m ?: error("Carro sin instalar: la Application de la app debe llamar a Carro.instalar()")

    fun instalar(perfil: PerfilVehiculo, motor: Motor) {
        p = perfil
        m = motor
    }
}

/** Quien es el carro y que hardware lleva. */
interface PerfilVehiculo {
    /** Clave corta del carro, en minusculas. */
    val clave: String

    /** El nombre de la app, tal cual se enseña: notificacion, puente HTTP. */
    val nombre: String
    val vehiculo: String
    val motor: String
    val protocoloEsperado: String
    val esCasaRodante: Boolean
    val tieneBancoVivienda: Boolean
    val tieneAfrReal: Boolean
    val tieneNevera: Boolean
    val tieneTpms: Boolean
    val vtecEsAcontecimiento: Boolean

    // Aqui NO hay aparatos (MAC de bateria, nevera ni adaptador OBD): los
    // elige el dueño en el menu de emparejamiento, y si cambia uno, elige el
    // nuevo. Con un aparato de fabrica escrito en el codigo, una app acabo
    // llamando durante meses a un adaptador OBD que no era el suyo.

    /**
     * Con que palabra se busca el servidor de actualizaciones por UDP. Cada
     * carro la suya: con la misma, un radio se bajaria el APK del otro.
     */
    val tokenDescubrimiento: String

    /** `R.raw` de la tabla de averias del motor de ESTE carro. */
    val tablaDtc: Int

    /** `R.raw` de la tabla de la bolsa de aire. Null = no hay tabla. */
    val tablaSrs: Int?

    val tema: String
}

/** Las cifras de UN motor. Cada app trae las suyas. */
interface Motor {
    val rpmMax: Int
    val rpmVtec: Int
    val rpmVtecSuelta: Int
    val rpmRedline: Int
    val rpmFuelCut: Int
    val rpmShiftAmber: Int
    val vtecMinLoadPct: Int

    /**
     * Carga minima (%) para ENGANCHAR el VTEC. Para SEGUIR enganchado basta
     * [vtecMinLoadPct]. Hay motores que entran con mucha mas carga de la que
     * necesitan para mantenerse: con una sola cifra, el tablero lo daba por
     * enganchado antes de tiempo. Por omision, la misma que para seguir.
     */
    val vtecCargaEnganche: Int get() = vtecMinLoadPct
    val staleAfterMs: Long
    val coolantHighC: Int
    val coolantTibioC: Int
    val coolantAvisoC: Int
    val afrEstequiometrica: Float
    val afrMin: Float
    val afrMax: Float

    /**
     * ¿Esta enganchado el VTEC? Con histeresis de revoluciones Y de carga:
     * para entrar hacen falta [rpmVtec] y [vtecCargaEnganche]; ya enganchado,
     * sigue mientras haya [rpmVtecSuelta] y [vtecMinLoadPct].
     * Sin dato de carga se asume que no: una lampara encendida cuando no lo
     * esta enseña a desconfiar del tablero.
     */
    fun vtecActive(rpm: Int?, loadPct: Int?, enganchadoAntes: Boolean = false): Boolean {
        if (rpm == null || loadPct == null) return false
        return if (enganchadoAntes) rpm >= rpmVtecSuelta && loadPct >= vtecMinLoadPct
        else rpm >= rpmVtec && loadPct >= vtecCargaEnganche
    }
}
