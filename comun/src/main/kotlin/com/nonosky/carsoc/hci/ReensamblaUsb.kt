package com.nonosky.carsoc.hci

/**
 * Junta los trozos que devuelve una lectura USB hasta formar paquetes HCI
 * completos.
 *
 * Hay **dos** reensamblados distintos en este camino y confundirlos es un
 * error caro:
 *
 *  1. **Este**, del nivel USB: el endpoint tiene un `maxPacketSize` y una
 *     lectura devuelve como mucho ese tamano. El endpoint de eventos de este
 *     dongle mide **16 bytes**, asi que un informe de anuncio BLE de 40 llega
 *     en tres pedazos. Ya paso: la primera version leia un pedazo y lo
 *     trataba como evento entero, y el resultado eran MAC inventadas a partir
 *     de bytes que en realidad eran datos de anuncio.
 *  2. El del nivel L2CAP, en [EnsambladorAcl]: una PDU L2CAP grande se
 *     trocea en VARIOS paquetes ACL por el propio protocolo, y eso pasa
 *     aunque el USB no partiera nada.
 *
 * Esta clase solo hace el (1): recibe bytes crudos y devuelve paquetes HCI
 * enteros, guiandose por el campo de longitud de la cabecera. Sirve para los
 * dos endpoints porque la unica diferencia es donde esta ese campo:
 *
 * ```
 *  evento HCI : codigo(1) largo(1) parametros(largo)          -> cabecera 2
 *  ACL HCI    : handle+flags(2) largo(2, LE) datos(largo)     -> cabecera 4
 * ```
 *
 * Sin Android: se prueba entera en la JVM troceando a mano, que es la unica
 * forma de cubrir el caso que de verdad rompe —el corte justo en medio de la
 * cabecera, cuando todavia no se sabe cuanto falta—.
 */
class ReensamblaUsb(
    private val cabecera: Int,
    private val posLargo: Int,
    private val bytesLargo: Int,
    private val maxPaquete: Int,
) {

    private var buf = ByteArray(maxPaquete.coerceAtLeast(64))
    private var n = 0

    var completos = 0L
        private set

    /** Bytes tirados por declarar una longitud imposible. */
    var descartados = 0L
        private set

    /** Veces que se tuvo que tirar el buffer por una longitud imposible. */
    var longitudesImposibles = 0L
        private set

    fun pendientes(): Int = n

    fun reiniciar() {
        n = 0
    }

    /**
     * Mete un trozo crudo y devuelve los paquetes que ya esten completos.
     *
     * Puede devolver cero (falta cola), uno, o varios (venian pegados). No
     * lanza nunca: el aparato puede escupir cualquier cosa y esto tiene que
     * seguir en pie, igual que el decodificador del TPMS.
     */
    fun alimentar(trozo: ByteArray, largo: Int = trozo.size): List<ByteArray> {
        val cuantos = largo.coerceIn(0, trozo.size)
        if (cuantos <= 0) return emptyList()

        asegurar(n + cuantos)
        trozo.copyInto(buf, n, 0, cuantos)
        n += cuantos

        val salida = ArrayList<ByteArray>(2)
        while (n >= cabecera) {
            val total = cabecera + largoDeclarado()
            if (total > maxPaquete || total < cabecera) {
                // Una longitud imposible significa que el flujo esta
                // desalineado: no hay forma honesta de saber donde empieza el
                // siguiente paquete, asi que se tira todo y se cuenta. Callarse
                // esto seria lo peor: el sintoma seria "no llegan datos" y la
                // causa estaria tres capas mas abajo.
                descartados += n
                longitudesImposibles++
                n = 0
                break
            }
            if (n < total) break

            salida += buf.copyOfRange(0, total)
            completos++
            buf.copyInto(buf, 0, total, n)
            n -= total
        }
        return salida
    }

    private fun largoDeclarado(): Int {
        var v = 0
        for (i in 0 until bytesLargo) {
            v = v or ((buf[posLargo + i].toInt() and 0xFF) shl (8 * i))
        }
        return v
    }

    private fun asegurar(tam: Int) {
        if (buf.size >= tam) return
        // Crece al doble para no realocar en cada trozo. El tope real lo pone
        // maxPaquete: por encima de eso se tira el buffer, asi que esto no
        // puede crecer sin freno por mucha basura que llegue.
        var nuevo = buf.size * 2
        while (nuevo < tam) nuevo *= 2
        buf = buf.copyOf(nuevo)
    }

    companion object {
        /**
         * Un evento HCI: codigo(1) + largo(1) + hasta 255 parametros.
         * El maximo por especificacion es 257 bytes y no puede ser mas.
         */
        fun paraEventos(): ReensamblaUsb =
            ReensamblaUsb(cabecera = 2, posLargo = 1, bytesLargo = 1, maxPaquete = 257)

        /**
         * Un paquete ACL: cabecera(4) + datos.
         *
         * El tope se pone con lo que el controlador dijo que puede mandar
         * (`ACL_Data_Packet_Length`), con un poco de aire: un paquete mas
         * grande que eso no puede venir del controlador, y si viene es que el
         * flujo esta desalineado.
         */
        fun paraAcl(maxDatosControlador: Int): ReensamblaUsb =
            ReensamblaUsb(
                cabecera = PaqueteAcl.CABECERA,
                posLargo = 2,
                bytesLargo = 2,
                maxPaquete = PaqueteAcl.CABECERA + maxDatosControlador.coerceIn(27, 2048),
            )
    }
}
