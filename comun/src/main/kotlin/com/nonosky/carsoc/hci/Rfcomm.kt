package com.nonosky.carsoc.hci

/**
 * RFCOMM: el multiplexor que convierte un canal L2CAP en un puerto serie.
 *
 * Es la ultima capa que separa al tablero del adaptador OBD. Debajo ya esta
 * todo: [HciUsb] habla con el dongle, [BombaHci] reparte, [EnlaceBrEdr]
 * empareja —que es justo lo que la pila rota del radio no sabia hacer, porque
 * ahi `ACTION_PAIRING_REQUEST` no se disparaba nunca— y [GestorL2cap] abre el
 * canal dinamico al PSM 3. Aqui encima va el dialogo AT del ELM327.
 *
 * Se implementa lo MINIMO que hace falta para un puerto de datos, y se dice
 * cual es ese minimo para que nadie busque lo que no esta:
 *
 *   - SABM / UA para abrir, DISC / UA para cerrar, DM para el rechazo.
 *   - UIH para los datos.
 *   - Control de flujo por CREDITOS, que es lo que usan los ELM327 modernos.
 *   - MSC, porque muchos modulos no mandan un byte hasta recibirlo.
 *
 * NO se implementa: negociacion de parametros completa (PN mas alla de lo
 * imprescindible), multiples sesiones, ni test/flow-control heredado. Un
 * ELM327 no los necesita y cada uno seria mas superficie que puede fallar en
 * un radio donde no hay shell para depurar.
 *
 * ----------------------------------------------------------------------------
 * EL FORMATO DE TRAMA
 * ----------------------------------------------------------------------------
 *
 *     direccion(1)  control(1)  longitud(1 o 2)  [creditos(1)]  datos  fcs(1)
 *
 *   direccion: EA(bit0) | C/R(bit1) | DLCI(bits 2-7)
 *   longitud:  si cabe en 7 bits -> (largo shl 1) or 1, un solo byte
 *   fcs:       CRC-8 de los DOS primeros bytes en las UIH, y de los TRES
 *              primeros en el resto. Esa asimetria no es un error de nadie:
 *              esta asi en la norma, y equivocarse hace que el otro extremo
 *              descarte todo en silencio.
 */
object Rfcomm {

    // --- Campos de control ---------------------------------------------------

    // Los valores que se MANDAN, con el bit P/F ya puesto donde toca.
    const val SABM = 0x3F
    const val UA = 0x73
    const val DM = 0x0F
    const val DISC = 0x53
    const val UIH = 0xEF

    /** Bit de sondeo/final, que va sumado al campo de control. */
    const val PF = 0x10

    /**
     * Los mismos campos SIN el bit P/F, que es como hay que compararlos.
     *
     * Existen porque no tenerlos costo una tarde. Comparar
     * `(control and 0xEF) == UA` es una condicion **imposible**: la mascara
     * 0xEF borra justo el bit 0x10 que UA (0x73) lleva puesto, asi que el lado
     * izquierdo vale 0x63 y nunca puede igualar 0x73.
     *
     * El sintoma fue de los peores: el Steren contestaba el UA correctamente
     * —se vio en el volcado crudo, `03 73 01 D7`— y el codigo lo tiraba. El
     * mensaje decia "el multiplexor no contesto al SABM", que apunta al otro
     * extremo cuando el fallo estaba aqui.
     */
    private const val SABM_BASE = SABM and 0xEF
    private const val UA_BASE = UA and 0xEF
    private const val DM_BASE = DM and 0xEF
    private const val DISC_BASE = DISC and 0xEF
    private const val UIH_BASE = UIH and 0xEF

    /** El canal 0 es el de control del multiplexor, no lleva datos. */
    const val DLCI_CONTROL = 0

    // --- Mensajes del canal de control --------------------------------------

    /** Modem Status Command: dice "estoy listo, mandame". */
    const val MSC = 0xE3

    /** Parameter Negotiation. */
    const val PN = 0x83

    /**
     * DLCI de un canal de servidor.
     *
     * El bit 0 es la direccion. Como aqui siempre iniciamos nosotros, sale
     * `canal shl 1`. Un DLCI mal formado se traduce en un DM y en horas
     * buscando el fallo donde no esta.
     */
    fun dlciDe(canal: Int): Int = (canal shl 1) and 0x3F

    // --- FCS -----------------------------------------------------------------

    /**
     * Tabla del CRC-8 de RFCOMM, polinomio x8+x2+x+1 reflejado (0xE0).
     *
     * Se calcula al cargar en vez de escribirla a mano: 256 constantes
     * copiadas de una referencia son 256 oportunidades de una errata que
     * nadie encontraria despues.
     */
    private val TABLA = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i
            for (b in 0 until 8) {
                c = if ((c and 1) != 0) (c shr 1) xor 0xE0 else c shr 1
            }
            t[i] = c
        }
    }

    fun fcs(bytes: ByteArray, cuantos: Int): Int {
        var f = 0xFF
        for (i in 0 until cuantos) f = TABLA[(f xor (bytes[i].toInt() and 0xFF)) and 0xFF]
        return (0xFF - f) and 0xFF
    }

    /**
     * Arma una trama.
     *
     * [comando] distingue quien manda: en RFCOMM el bit C/R depende a la vez
     * de si es comando o respuesta y de quien inicio la sesion, y aqui la
     * iniciamos nosotros siempre.
     */
    fun trama(
        dlci: Int,
        control: Int,
        datos: ByteArray = ByteArray(0),
        creditos: Int = -1,
        comando: Boolean = true,
    ): ByteArray {
        val direccion = 0x01 or (if (comando) 0x02 else 0x00) or ((dlci and 0x3F) shl 2)
        val conCreditos = creditos >= 0
        val largo = datos.size

        val cabeza = ArrayList<Byte>(4)
        cabeza += direccion.toByte()
        cabeza += (control or if (conCreditos) PF else 0).toByte()

        // Longitud: un byte si cabe en 7 bits, dos si no.
        if (largo < 128) {
            cabeza += (((largo shl 1) or 1) and 0xFF).toByte()
        } else {
            cabeza += ((largo shl 1) and 0xFE).toByte()
            cabeza += ((largo shr 7) and 0xFF).toByte()
        }
        if (conCreditos) cabeza += (creditos and 0xFF).toByte()

        val cabezaArr = cabeza.toByteArray()
        // El FCS cubre 2 bytes en UIH y 3 en el resto. Esta en la norma.
        val cubre = if ((control and 0xEF) == UIH_BASE) 2 else 3
        val f = fcs(cabezaArr, minOf(cubre, cabezaArr.size))

        val salida = ByteArray(cabezaArr.size + largo + 1)
        cabezaArr.copyInto(salida, 0)
        datos.copyInto(salida, cabezaArr.size)
        salida[salida.size - 1] = f.toByte()
        return salida
    }

    /** Lo que se saca de una trama recibida. */
    data class Recibida(
        val dlci: Int,
        val control: Int,
        val datos: ByteArray,
        val creditos: Int,
    ) {
        val esUih: Boolean get() = (control and 0xEF) == UIH_BASE
        val esUa: Boolean get() = (control and 0xEF) == UA_BASE
        val esDm: Boolean get() = (control and 0xEF) == DM_BASE
        val esDisc: Boolean get() = (control and 0xEF) == DISC_BASE
        val esSabm: Boolean get() = (control and 0xEF) == SABM_BASE
    }

    /**
     * Desarma una trama. Devuelve null si no cuadra.
     *
     * No se intenta recuperar nada de una trama con FCS malo. Es la misma
     * regla dura que el XOR del TPMS y el checksum del BMS: en un enlace
     * serie, media trama aceptada envenena todas las siguientes.
     */
    fun interpretar(b: ByteArray): Recibida? {
        if (b.size < 4) return null
        val direccion = b[0].toInt() and 0xFF
        if ((direccion and 0x01) == 0) return null // sin EA no es RFCOMM
        val dlci = (direccion shr 2) and 0x3F
        val control = b[1].toInt() and 0xFF

        var i = 2
        val primerLargo = b[i].toInt() and 0xFF
        var largo: Int
        if ((primerLargo and 0x01) == 1) {
            largo = primerLargo shr 1
            i++
        } else {
            if (b.size < 5) return null
            largo = (primerLargo shr 1) or ((b[i + 1].toInt() and 0xFF) shl 7)
            i += 2
        }

        val esUih = (control and 0xEF) == UIH_BASE
        var creditos = -1
        if (esUih && (control and PF) != 0) {
            if (i >= b.size) return null
            creditos = b[i].toInt() and 0xFF
            i++
        }

        if (i + largo + 1 > b.size) return null
        val datos = b.copyOfRange(i, i + largo)

        val cubre = if (esUih) 2 else 3
        val esperado = fcs(b, minOf(cubre, b.size))
        val traido = b[i + largo].toInt() and 0xFF
        if (esperado != traido) return null

        return Recibida(dlci, control, datos, creditos)
    }

    // --- Mensajes del canal de control --------------------------------------

    /**
     * Modem Status: le dice al otro extremo que estamos listos.
     *
     * Muchos modulos serie no mandan un solo byte hasta recibir esto, y
     * entonces el canal parece abierto y mudo — el sintoma mas desconcertante
     * de RFCOMM, porque todo lo anterior dio UA y parece correcto.
     */
    fun mensajeMsc(dlci: Int, comando: Boolean = true): ByteArray {
        val tipo = MSC or (if (comando) 0x02 else 0x00)
        // V.24: RTC, RTR y DV puestos = "listo para hablar".
        val senales = 0x8D
        val cuerpo = byteArrayOf(
            tipo.toByte(),
            ((2 shl 1) or 1).toByte(),               // largo del valor = 2
            (((dlci and 0x3F) shl 2) or 0x03).toByte(),
            senales.toByte(),
        )
        return trama(DLCI_CONTROL, UIH, cuerpo, comando = true)
    }

    /** Reconoce un MSC entrante para poder contestarlo. */
    fun esMsc(datos: ByteArray): Boolean =
        datos.isNotEmpty() && (datos[0].toInt() and 0xEF) == MSC
}
