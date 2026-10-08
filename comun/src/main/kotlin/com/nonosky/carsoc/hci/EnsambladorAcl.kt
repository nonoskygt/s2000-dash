package com.nonosky.carsoc.hci

/**
 * Recompone PDU L2CAP a partir de paquetes ACL, por enlace.
 *
 * Es el segundo reensamblado del camino (el primero, del nivel USB, lo hace
 * [ReensamblaUsb]). Aqui los paquetes ACL ya llegan enteros, pero una PDU
 * L2CAP puede venir repartida en varios de ellos: el controlador solo puede
 * mandar `ACL_Data_Packet_Length` bytes por paquete —27 en el pool LE de este
 * dongle— y una respuesta ATT o una trama RFCOMM puede medir mas.
 *
 * ESTANDAR VERIFICABLE (Core Spec Vol 3 Part A, seccion 3.1): el primer
 * paquete de una PDU trae PB=0b10 (o 0b00, o 0b11 si viene completa) y los
 * siguientes PB=0b01. Los dos primeros bytes de la PDU son su longitud, que
 * NO incluye los 4 bytes de cabecera L2CAP. O sea que en cuanto llegan esos
 * dos bytes ya se sabe cuanto falta.
 *
 * El estado va por handle porque puede haber dos enlaces a la vez (la bateria
 * por LE y el adaptador OBD por clasico) y sus trozos llegan intercalados.
 * Un solo buffer compartido mezclaria las dos conversaciones y produciria
 * PDU que no existen.
 *
 * Sin Android: se prueba entera en la JVM.
 */
class EnsambladorAcl(private val maxPdu: Int = MAX_PDU) {

    /** Una PDU L2CAP completa, con el enlace del que vino. */
    class Pdu(val handle: Int, val datos: ByteArray)

    private class Parcial {
        var buf = ByteArray(64)
        var n = 0
        /** 0 = todavia no se sabe (faltan los 2 bytes de longitud). */
        var total = 0
    }

    private val porHandle = HashMap<Int, Parcial>()

    var completas = 0L
        private set

    /** Continuaciones que llegaron sin un primero delante. */
    var huerfanas = 0L
        private set

    /** Primeros que llegaron pisando una PDU a medias: se perdio un trozo. */
    var interrumpidas = 0L
        private set

    /** PDU que declararon una longitud imposible. */
    var absurdas = 0L
        private set

    /**
     * Veces que el ultimo trozo traia bytes de MAS alla de la PDU declarada.
     *
     * INCERTIDUMBRE: no se ha visto pasar. Un controlador no deberia meter
     * dos PDU en un paquete ACL, pero el sobrante se trata como el arranque
     * de la siguiente en vez de tirarlo, y se cuenta aparte para poder
     * detectarlo si algun dia ocurre de verdad.
     */
    var pegadas = 0L
        private set

    fun alimentar(paquete: ByteArray): List<Pdu> {
        if (paquete.size < PaqueteAcl.CABECERA) return emptyList()

        val handle = PaqueteAcl.handleDe(paquete)
        val pb = PaqueteAcl.pbDe(paquete)
        val datos = PaqueteAcl.datosDe(paquete)
        if (datos.isEmpty()) return emptyList()

        val salida = ArrayList<Pdu>(1)

        if (pb == PaqueteAcl.PB_CONTINUACION) {
            val p = porHandle[handle]
            if (p == null || p.n == 0) {
                // Una continuacion sin principio no se puede colocar en
                // ningun sitio. Pasa al conectar sobre un enlace que ya venia
                // hablando, igual que abrir el puerto del TPMS a media trama.
                huerfanas++
                return salida
            }
            anadir(p, datos)
        } else {
            val p = porHandle.getOrPut(handle) { Parcial() }
            if (p.n > 0) {
                interrumpidas++
                p.n = 0
                p.total = 0
            }
            anadir(p, datos)
        }

        val p = porHandle[handle] ?: return salida
        while (true) {
            if (p.total == 0 && p.n >= L2cap.CABECERA) {
                p.total = L2cap.CABECERA +
                    ((p.buf[0].toInt() and 0xFF) or ((p.buf[1].toInt() and 0xFF) shl 8))
                if (p.total > maxPdu) {
                    absurdas++
                    p.n = 0
                    p.total = 0
                    return salida
                }
            }
            if (p.total == 0 || p.n < p.total) return salida

            salida += Pdu(handle, p.buf.copyOfRange(0, p.total))
            completas++

            val sobra = p.n - p.total
            if (sobra <= 0) {
                p.n = 0
                p.total = 0
                return salida
            }
            pegadas++
            p.buf.copyInto(p.buf, 0, p.total, p.n)
            p.n = sobra
            p.total = 0
        }
    }

    private fun anadir(p: Parcial, datos: ByteArray) {
        if (p.n + datos.size > p.buf.size) {
            var nuevo = p.buf.size * 2
            val quiere = p.n + datos.size
            while (nuevo < quiere) nuevo *= 2
            // Tope duro: el buffer no puede crecer mas que la PDU mas grande
            // que se admite. Sin este tope, un largo mentido haria crecer la
            // memoria hasta tumbar el proceso.
            p.buf = p.buf.copyOf(nuevo.coerceAtMost(maxPdu + L2cap.CABECERA))
        }
        val cabe = (p.buf.size - p.n).coerceAtLeast(0)
        val n = minOf(cabe, datos.size)
        if (n > 0) datos.copyInto(p.buf, p.n, 0, n)
        p.n += n
    }

    /**
     * Olvida lo que hubiera a medias de un enlace que se cayo.
     *
     * Hace falta de verdad: al reconectar, el handle se reutiliza, y media
     * PDU vieja pegada a una nueva daria una PDU que nadie mando.
     */
    fun olvidar(handle: Int) {
        porHandle.remove(handle)
    }

    fun reiniciar() {
        porHandle.clear()
    }

    fun pendientesDe(handle: Int): Int = porHandle[handle]?.n ?: 0

    fun diagnostico(): List<String> = listOf(
        "PDU completas: $completas",
        "continuaciones huerfanas: $huerfanas",
        "PDU interrumpidas por un primero: $interrumpidas",
        "longitudes absurdas: $absurdas",
        "PDU pegadas en un paquete: $pegadas",
        "enlaces con trozos a medias: " +
            porHandle.entries.filter { it.value.n > 0 }
                .joinToString(", ") { "0x${"%03X".format(it.key)}=${it.value.n}B" }
                .ifEmpty { "ninguno" },
    )

    companion object {
        /**
         * Tope de PDU L2CAP que se acepta.
         *
         * No es el maximo del protocolo (65535+4): es lo que tiene sentido en
         * este tablero. ATT con MTU 517 y RFCOMM con MTU 330 caben de sobra, y
         * cualquier cosa mayor es un largo mentido o un flujo desalineado.
         */
        const val MAX_PDU = 2048
    }
}
