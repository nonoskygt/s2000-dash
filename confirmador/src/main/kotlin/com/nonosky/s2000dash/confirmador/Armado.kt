package com.nonosky.s2000dash.confirmador

/** Ventana de tiempo en la que el tablero espera confirmar una instalacion. */
object Armado {

    @Volatile
    private var hastaMs: Long = 0

    @Volatile
    var versionEsperada: Int = -1
        private set

    fun armar(versionCode: Int, duracionMs: Long) {
        versionEsperada = versionCode
        hastaMs = System.currentTimeMillis() + duracionMs.coerceIn(1_000, MAX_MS)
    }

    fun desarmar() {
        hastaMs = 0
        versionEsperada = -1
    }

    // --- Emparejamiento Bluetooth ------------------------------------------

    @Volatile
    private var pinHastaMs: Long = 0

    /**
     * PIN a teclear en el dialogo de emparejamiento.
     *
     * Hace falta porque la ROM de este radio no deja escribir en ese
     * dialogo: sale, no acepta teclado, y el emparejamiento muere de
     * tiempo. Un servicio de accesibilidad si puede rellenarlo.
     */
    @Volatile
    var pin: String? = null
        private set

    fun armarPin(valor: String, duracionMs: Long) {
        pin = valor
        pinHastaMs = System.currentTimeMillis() + duracionMs.coerceIn(1_000, MAX_MS)
    }

    fun desarmarPin() {
        pin = null
        pinHastaMs = 0
    }

    fun pinActivo(): Boolean = pin != null && System.currentTimeMillis() < pinHastaMs

    fun activo(): Boolean = System.currentTimeMillis() < hastaMs

    /** Tope duro: una ventana abierta de mas es una ventana de ataque. */
    private const val MAX_MS = 180_000L
}
