package com.nonosky.carsoc.bateria

/**
 * Lo que se sabe de la bateria de litio en este instante.
 *
 * Separado de `VehicleState` y de `EstadoTpms` por la misma razon que ellos
 * entre si: son tres aparatos distintos que fallan por motivos distintos. Si
 * compartieran objeto, la siguiente muestra de cualquiera borraria lo de los
 * otros al publicar su copia.
 *
 * Casi todo es nullable a proposito. Hoy solo se sabe que la bateria **esta
 * ahi**: el barrido BLE por el dongle USB la encuentra y da su MAC, su nombre
 * y su señal. El voltaje, el SoC, la corriente y las temperaturas viven
 * detras de una conexion GATT que todavia no esta escrita, y hasta que lo
 * este esos campos valen null — que en el tablero se pintan como huecos, no
 * como ceros. Un cero en un voltaje es una bateria muerta; un hueco es
 * "todavia no lo se". No son lo mismo.
 */
data class BateriaState(
    /** MAC del BMS. null = no se ha visto en ningun barrido. */
    val mac: String? = null,
    val nombre: String? = null,
    /** Potencia de señal del ultimo anuncio, en dBm. */
    val rssi: Int? = null,
    /** Cuando se vio por ultima vez su anuncio. */
    val vistaMs: Long = 0,

    // --- Todo lo de abajo llega con el GATT. Hoy siempre null. ---
    val voltaje: Float? = null,
    val soc: Int? = null,
    val corrienteA: Float? = null,
    val temperaturaC: Int? = null,
    val celdas: List<Float> = emptyList(),

    val enlace: EnlaceBateria = EnlaceBateria.SinDongle,
    val detalle: String? = null,

    /**
     * Todas las BMS oidas en la ultima ronda, no solo la elegida.
     *
     * Hace falta porque el aire tiene mas de una: junto a la del carro se oye
     * la de una bicicleta, tambien JBD y tambien con servicio 0xFF00. Sin
     * verlas todas, elegir mal es invisible — y se estuvo leyendo la de la
     * bici durante un rato sin que nada lo delatara.
     */
    val candidatas: List<String> = emptyList(),
) {

    /**
     * Potencia en vatios, con signo: positivo entra, negativo sale.
     *
     * Se pide asi porque un amperaje sin voltaje no dice cuanta energia se
     * mueve de verdad. 40 A a 13 V son 520 W; los mismos 40 A a 48 V serian
     * 1920 W. El vatio es la magnitud que se puede comparar con cualquier
     * otra cosa del carro.
     */
    val potenciaW: Float?
        get() {
            val v = voltaje ?: return null
            val a = corrienteA ?: return null
            return v * a
        }

    fun detectada(): Boolean = mac != null

    /**
     * Un anuncio BLE se repite varias veces por segundo, asi que la ausencia
     * durante un minuto ya significa algo: o la bateria se apago, o el dongle
     * dejo de barrer, o el carro esta fuera de alcance del BMS.
     */
    fun rancia(ahoraMs: Long): Boolean =
        vistaMs == 0L || (ahoraMs - vistaMs) > SIN_VERSE_MS

    companion object {
        const val SIN_VERSE_MS = 60_000L

        /**
         * El servicio que delata a un BMS JBD/Xiaoxiang en su anuncio.
         *
         * Medido en vivo: el anuncio de esta bateria trae `0302 00FF`, o sea
         * el UUID de 16 bits 0xFF00. Es el mismo que usa la app Xiaoxiang,
         * que el dueño confirma que funciona con este aparato.
         */
        const val SERVICIO_JBD = 0xFF00
    }
}

/**
 * Estado del camino hacia la bateria.
 *
 * Tiene mas escalones que un simple conectado/desconectado porque el camino
 * es largo y cada tramo falla distinto: hace falta el dongle USB, hace falta
 * que hable HCI, hace falta barrer, hace falta encontrarla y hace falta
 * conectarse. Un solo "sin enlace" para las cinco cosas no permite arreglar
 * ninguna.
 */
enum class EnlaceBateria {
    /** No hay dongle Bluetooth en el USB. */
    SinDongle,

    /** Hay dongle pero no contesta HCI. */
    DongleMudo,

    /** Barriendo, todavia sin encontrarla. */
    Buscando,

    /** Encontrada por su anuncio. Se conocen MAC, nombre y señal. */
    Detectada,

    /**
     * Conectada por GATT y leyendo el BMS.
     *
     * Reservado: el transporte GATT sobre HCI crudo aun no esta implementado.
     */
    Leyendo,

    Fallo,
}
