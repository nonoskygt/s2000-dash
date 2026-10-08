package com.nonosky.carsoc

/**
 * Ver §9 del diseño: la rueda de estados de la conexion.
 *
 * Los dos primeros no son fallos de enlace sino de configuracion, y se
 * distinguen a proposito: el tablero decia "SIN ENLACE" tanto si el
 * adaptador no respondia como si nunca se habia elegido uno, y no habia
 * manera de saber cual de las dos cosas pasaba ni que hacer al respecto.
 */
enum class ConnectionState {
    /** No se ha elegido adaptador todavia. Hace falta tocar el tablero. */
    SinAdaptador,

    /** El Bluetooth del radio esta apagado. */
    BluetoothApagado,

    Disconnected,
    Connecting,
    Initializing,
    Polling,
}

/**
 * Lo que el tablero sabe del carro en este instante.
 *
 * Inmutable y con un timestamp por campo: al perder la conexion no se borra
 * la pantalla, se marcan los valores viejos en gris (§9). Un tablero en
 * blanco a media curva es peor que un dato viejo señalado como viejo.
 */
data class VehicleState(
    val rpm: Int? = null,
    val rpmAtMs: Long = 0,
    val speedKmh: Int? = null,
    val speedAtMs: Long = 0,
    val coolantC: Int? = null,
    val coolantAtMs: Long = 0,
    val iatC: Int? = null,
    val iatAtMs: Long = 0,
    val loadPct: Int? = null,
    val loadAtMs: Long = 0,
    val batteryV: Float? = null,
    val batteryAtMs: Long = 0,
    /**
     * Relacion de equivalencia (lambda) de la sonda de banda ancha, PID 0134.
     * Solo se pide en el carro cuyo perfil dice `tieneAfrReal`.
     */
    val lambda: Float? = null,
    val lambdaAtMs: Long = 0,
    /** Presion absoluta del colector, kPa. En atmosferico, el vacuometro. */
    val mapKpa: Int? = null,
    val mapAtMs: Long = 0,
    val aceleradorPct: Int? = null,
    val aceleradorAtMs: Long = 0,
    /** Avance de encendido, grados. */
    val avanceGrados: Int? = null,
    val avanceAtMs: Long = 0,
    /** Voltaje de la sonda de oxigeno. Banda estrecha: solo rica/pobre. */
    /** Ajustes de combustible. Cero es perfecto; el signo dice hacia donde. */
    val trimCortoPct: Int? = null,
    val trimCortoAtMs: Long = 0,
    val trimLargoPct: Int? = null,
    val trimLargoAtMs: Long = 0,

    /** Luz de averia y codigos guardados, del PID 0101. */
    val milEncendida: Boolean = false,
    val codigosGuardados: Int = 0,
    val estadoAtMs: Long = 0,

    // `o2Voltaje` y `o2AtMs` se fueron con el resto de la cadena del O2:
    // se escribian en cada ronda y no los leia nadie desde que MEZCLA pasa a
    // salir de los ajustes de combustible.
    val connection: ConnectionState = ConnectionState.Disconnected,
    val sessionMaxRpm: Int = 0,
    /** Lo que reporto `ATDP`, para mostrar que se negocio de verdad. */
    val protocol: String? = null,
) {
    /** No consume lecturas: se deriva del rpm y la carga que ya se leen. */
    val vtecActive: Boolean
        get() = Carro.motor.vtecActive(rpm, loadPct)

    fun isStale(atMs: Long, nowMs: Long): Boolean =
        atMs == 0L || (nowMs - atMs) > Carro.motor.staleAfterMs
}
