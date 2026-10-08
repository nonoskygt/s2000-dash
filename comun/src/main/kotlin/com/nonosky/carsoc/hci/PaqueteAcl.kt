package com.nonosky.carsoc.hci

/**
 * El paquete de datos HCI ACL, bit a bit.
 *
 * Es el sobre en el que viaja TODO lo que no es un comando: L2CAP va dentro
 * de ACL, ATT va dentro de L2CAP, RFCOMM va dentro de L2CAP. Sin este sobre
 * bien puesto no hay bateria ni OBD, y equivocarse en un solo bit de la
 * cabecera no da error: el controlador se calla y nunca contesta.
 *
 * Sin nada de Android a proposito: asi se prueba entero en la JVM con
 * vectores escritos a mano, que es la unica forma de estar seguro de una
 * cabecera que no se puede ver con un analizador.
 *
 * ESTANDAR VERIFICABLE (Core Spec Vol 4 Part E, seccion 5.4.2). En el
 * transporte USB **no va el byte de tipo de paquete**: el tipo lo da el
 * endpoint (control = comando, interrupcion = evento, BULK = ACL). Por eso
 * la cabecera empieza directamente en el handle:
 *
 * ```
 *  byte 0   : Connection_Handle bits 0..7
 *  byte 1   : bits 0..3  Connection_Handle bits 8..11   (handle = 12 bits)
 *             bits 4..5  PB flag  (Packet_Boundary_Flag)
 *             bits 6..7  BC flag  (Broadcast_Flag)
 *  byte 2   : Data_Total_Length bits 0..7    (little endian)
 *  byte 3   : Data_Total_Length bits 8..15
 *  byte 4.. : los datos (un trozo de una PDU L2CAP)
 * ```
 *
 * `Data_Total_Length` cuenta **solo los datos**, no los 4 bytes de cabecera.
 *
 * OJO con el PB. El nombre que se le da en la practica no coincide con lo
 * que uno esperaria por el valor numerico, y confundirlos rompe el enlace en
 * silencio. Los valores son (y coinciden con las constantes ACL_* de BlueZ,
 * que es la implementacion de referencia):
 *
 * ```
 *  0b00  primer trozo NO vaciable automaticamente   (ACL_START_NO_FLUSH)
 *  0b01  trozo de continuacion                      (ACL_CONT)
 *  0b10  primer trozo, vaciable automaticamente     (ACL_START)   <-- el normal
 *  0b11  una PDU L2CAP completa                     (ACL_COMPLETE)
 * ```
 *
 * O sea: el primer trozo lleva **0b10** y los siguientes **0b01**. El valor
 * 0b10 es el correcto, pero NO es "no automatico" —es justo el automatico—;
 * el "no vaciable" es el 0b00. Aqui se manda 0b10 porque es lo que manda
 * cualquier pila real y lo que todo controlador espera.
 */
object PaqueteAcl {

    /** Los cuatro bytes de cabecera ACL. */
    const val CABECERA = 4

    const val PB_PRIMERO_NO_VACIABLE = 0b00
    const val PB_CONTINUACION = 0b01
    const val PB_PRIMERO = 0b10
    const val PB_COMPLETO = 0b11

    /** Punto a punto. Lo unico que se usa en un enlace normal. */
    const val BC_PUNTO_A_PUNTO = 0b00

    /**
     * El handle son 12 bits, pero el rango valido llega a 0x0EFF: de 0x0F00
     * arriba esta reservado por la especificacion. Un handle fuera de rango
     * es un error de programa, no un dato del aire.
     */
    const val HANDLE_MAX = 0x0EFF

    /** Arma un paquete ACL con un trozo de datos. */
    fun armar(
        handle: Int,
        pb: Int,
        datos: ByteArray,
        desde: Int = 0,
        largo: Int = datos.size - desde,
        bc: Int = BC_PUNTO_A_PUNTO,
    ): ByteArray {
        require(handle in 0..HANDLE_MAX) { "handle fuera de rango: $handle" }
        require(pb in 0..3) { "PB fuera de rango: $pb" }
        require(bc in 0..3) { "BC fuera de rango: $bc" }
        require(desde >= 0 && largo >= 0 && desde + largo <= datos.size) {
            "trozo fuera del arreglo: desde=$desde largo=$largo tam=${datos.size}"
        }
        require(largo <= 0xFFFF) { "un paquete ACL no puede llevar $largo bytes" }

        val p = ByteArray(CABECERA + largo)
        p[0] = (handle and 0xFF).toByte()
        p[1] = (((handle shr 8) and 0x0F) or ((pb and 0x03) shl 4) or ((bc and 0x03) shl 6)).toByte()
        p[2] = (largo and 0xFF).toByte()
        p[3] = ((largo shr 8) and 0xFF).toByte()
        datos.copyInto(p, CABECERA, desde, desde + largo)
        return p
    }

    fun handleDe(p: ByteArray): Int =
        if (p.size < 2) -1 else (p[0].toInt() and 0xFF) or ((p[1].toInt() and 0x0F) shl 8)

    fun pbDe(p: ByteArray): Int = if (p.size < 2) -1 else (p[1].toInt() shr 4) and 0x03

    fun bcDe(p: ByteArray): Int = if (p.size < 2) -1 else (p[1].toInt() shr 6) and 0x03

    /** El largo DECLARADO en la cabecera, que puede no ser el que llego. */
    fun largoDeclarado(p: ByteArray): Int =
        if (p.size < CABECERA) -1
        else (p[2].toInt() and 0xFF) or ((p[3].toInt() and 0xFF) shl 8)

    /** Los datos que de verdad llegaron, recortados a lo que declara la cabecera. */
    fun datosDe(p: ByteArray): ByteArray {
        if (p.size <= CABECERA) return ByteArray(0)
        val declarado = largoDeclarado(p)
        val hay = p.size - CABECERA
        val n = if (declarado in 0..hay) declarado else hay
        return p.copyOfRange(CABECERA, CABECERA + n)
    }

    /**
     * Trocea una PDU L2CAP en paquetes ACL del tamano que aguanta el
     * controlador.
     *
     * El primer trozo va con PB=0b10 y el resto con PB=0b01. Si toda la PDU
     * cabe en un paquete tambien se manda como PB=0b10 y no como 0b11: un
     * "primer trozo" que ademas resulta ser el ultimo es correcto en todo
     * controlador, mientras que 0b11 tiene mas historia detras (AMP, ISO) y
     * no vale la pena arriesgarla por nada.
     */
    fun trocear(handle: Int, pdu: ByteArray, maxDatos: Int): List<ByteArray> {
        require(pdu.isNotEmpty()) { "una PDU L2CAP vacia no existe" }
        require(maxDatos > 0) { "maxDatos debe ser positivo" }

        val trozos = ArrayList<ByteArray>((pdu.size + maxDatos - 1) / maxDatos)
        var i = 0
        while (i < pdu.size) {
            val n = minOf(maxDatos, pdu.size - i)
            val pb = if (i == 0) PB_PRIMERO else PB_CONTINUACION
            trozos += armar(handle, pb, pdu, i, n)
            i += n
        }
        return trozos
    }

    /**
     * El tamano de trozo mas grande que se puede mandar SIN colgar el dongle.
     *
     * Aqui hay una trampa del USB que no tiene nada que ver con Bluetooth y
     * que cuesta un dia entero de "el controlador dejo de contestar":
     *
     * En USB una transferencia BULK termina cuando llega un paquete **corto**
     * (mas chico que `maxPacketSize`). Si la transferencia mide exactamente un
     * multiplo de `maxPacketSize` —64 en este dongle— no hay paquete corto, y
     * el aparato se queda esperando el resto de una transferencia que ya
     * termino. El driver `btusb` de Linux resuelve esto marcando la URB con
     * `URB_ZERO_PACKET` para que el host mande un paquete de longitud cero.
     *
     * **`UsbDeviceConnection.bulkTransfer` de Android no expone esa bandera.**
     * (ESTANDAR VERIFICABLE: la regla del paquete corto, USB 2.0 seccion
     * 5.8.3, y el `URB_ZERO_PACKET` de btusb. INCERTIDUMBRE: si el usbfs de
     * ESTE kernel manda el paquete cero por su cuenta. No se ha medido.)
     *
     * Por si acaso, se evita el caso: se elige un tamano de trozo tal que
     * `4 + trozo` nunca sea multiplo del tamano de paquete USB. Basta bajar
     * uno, porque dos enteros seguidos no pueden ser los dos multiplos del
     * mismo numero. Cuesta un byte por paquete y quita de encima un fallo
     * imposible de diagnosticar desde un radio sin shell.
     */
    fun maxDatosSeguro(maxControlador: Int, tamPaqueteUsb: Int): Int {
        var n = maxControlador.coerceAtLeast(1)
        if (tamPaqueteUsb > CABECERA && (CABECERA + n) % tamPaqueteUsb == 0) n -= 1
        return n.coerceAtLeast(1)
    }

    fun describir(p: ByteArray): String {
        if (p.size < CABECERA) return "ACL truncado (${p.size} bytes)"
        val pb = when (pbDe(p)) {
            PB_PRIMERO_NO_VACIABLE -> "primero-no-vaciable"
            PB_CONTINUACION -> "continuacion"
            PB_PRIMERO -> "primero"
            else -> "completo"
        }
        return "ACL handle=0x${"%03X".format(handleDe(p))} pb=$pb bc=${bcDe(p)} " +
            "largo=${largoDeclarado(p)} (llegaron ${p.size - CABECERA})"
    }
}
