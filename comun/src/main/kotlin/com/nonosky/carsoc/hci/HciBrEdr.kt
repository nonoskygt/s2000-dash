package com.nonosky.carsoc.hci

/**
 * Opcodes, eventos y armadores de parametros del Bluetooth CLASICO (BR/EDR)
 * sobre HCI crudo. Solo constantes y bytes: ninguna I/O, todo comprobable
 * con una prueba JVM.
 *
 * Existe aparte de [HciUsb] porque [HciUsb] es el TRANSPORTE (control para
 * comandos, interrupcion para eventos, bulk para ACL) y esto es el
 * PROTOCOLO. Mezclarlos obligaria a un dongle real para probar un
 * desplazamiento de bits.
 *
 * ---------------------------------------------------------------------------
 * MEDIDO en este dongle (BCM20702A0, VID 0x0A5C PID 0x21EC), no supuesto:
 *
 *   READ_LOCAL_VERSION      -> HCI v6 = Bluetooth 4.0, fabricante 15 Broadcom
 *   READ_LOCAL_FEATURES     -> BF FE CF FE DB FF 7B 87
 *
 * Ese vector de caracteristicas se lee bit a bit asi (pagina 0 de LMP):
 *
 *   byte 0 = 0xBF -> bit 2  "Encryption"                      = 1  SI
 *                    bit 5  "Role switch"                     = 1  SI
 *   byte 4 = 0xDB -> bit 37 "BR/EDR Not Supported"            = 0  o sea
 *                                                                 BR/EDR SI
 *                    bit 38 "LE Supported (Controller)"       = 1  SI
 *   byte 6 = 0x7B -> bit 48 "Extended Inquiry Response"       = 1  SI
 *                    bit 51 "Secure Simple Pairing"           = 1  SI
 *   byte 7 = 0x87 -> bit 63 "Extended features"               = 1  SI
 *
 * Conclusion que NO es una hipotesis: este controlador trae en firmware el
 * emparejamiento seguro (SSP), el cifrado y BR/EDR. La etapa de
 * emparejamiento no depende de subirle el patchram `BCM20702A1-*.hcd` que
 * normalmente le sube `btusb` — cosa que aqui nadie hizo, porque no hay
 * `btusb`.
 * ---------------------------------------------------------------------------
 */
object HciBrEdr {

    // ---------------------------------------------------------------- comandos
    // opcode = (OGF shl 10) or OCF.  OGF 1 = Link Control, OGF 3 = Controller.

    /** Barrido clasico. No hace falta si ya se sabe la MAC. */
    const val CMD_INQUIRY = 0x0401
    const val CMD_INQUIRY_CANCEL = 0x0402

    /** Abre el enlace ACL con una MAC conocida. 13 bytes de parametros. */
    const val CMD_CREATE_CONNECTION = 0x0405
    const val CMD_DISCONNECT = 0x0406
    const val CMD_CREATE_CONNECTION_CANCEL = 0x0408
    const val CMD_ACCEPT_CONNECTION_REQUEST = 0x0409

    /** Emparejamiento heredado (PIN). Esto es lo que la pila del radio no hacia. */
    const val CMD_LINK_KEY_REQUEST_REPLY = 0x040B
    const val CMD_LINK_KEY_REQUEST_NEGATIVE_REPLY = 0x040C
    const val CMD_PIN_CODE_REQUEST_REPLY = 0x040D
    const val CMD_PIN_CODE_REQUEST_NEGATIVE_REPLY = 0x040E

    const val CMD_AUTHENTICATION_REQUESTED = 0x0411
    const val CMD_SET_CONNECTION_ENCRYPTION = 0x0413
    const val CMD_REMOTE_NAME_REQUEST = 0x0419

    /** Emparejamiento seguro (SSP), Bluetooth 2.1 en adelante. */
    const val CMD_IO_CAPABILITY_REQUEST_REPLY = 0x042B
    const val CMD_USER_CONFIRMATION_REQUEST_REPLY = 0x042C
    const val CMD_USER_CONFIRMATION_REQUEST_NEGATIVE_REPLY = 0x042D
    const val CMD_USER_PASSKEY_REQUEST_REPLY = 0x042E
    const val CMD_USER_PASSKEY_REQUEST_NEGATIVE_REPLY = 0x042F
    const val CMD_IO_CAPABILITY_REQUEST_NEGATIVE_REPLY = 0x0434

    const val CMD_SET_EVENT_MASK = 0x0C01
    const val CMD_WRITE_PAGE_TIMEOUT = 0x0C18
    const val CMD_WRITE_SIMPLE_PAIRING_MODE = 0x0C56

    /**
     * Tamano y numero de buffers ACL de BR/EDR.
     *
     * OJO: **no** sirve `LE_READ_BUFFER_SIZE`. En este dongle ese devolvio
     * 27 bytes / 15 buffers, y siendo distinto de cero significa que LE
     * tiene su propia reserva: los numeros de BR/EDR son otros y hay que
     * preguntarlos con ESTE comando. Usar los de LE para contar creditos de
     * ACL clasico es la forma segura de desbordar al controlador.
     */
    const val CMD_READ_BUFFER_SIZE = 0x1005

    // ----------------------------------------------------------------- eventos
    const val EVT_INQUIRY_COMPLETE = 0x01
    const val EVT_INQUIRY_RESULT = 0x02
    const val EVT_CONNECTION_COMPLETE = 0x03
    const val EVT_CONNECTION_REQUEST = 0x04
    const val EVT_DISCONNECTION_COMPLETE = 0x05
    const val EVT_AUTHENTICATION_COMPLETE = 0x06
    const val EVT_REMOTE_NAME_REQUEST_COMPLETE = 0x07
    const val EVT_ENCRYPTION_CHANGE = 0x08
    const val EVT_COMMAND_COMPLETE = 0x0E
    const val EVT_COMMAND_STATUS = 0x0F
    const val EVT_ROLE_CHANGE = 0x12

    /** Devuelve creditos de ACL. Sin atenderlo, el envio se para en seco. */
    const val EVT_NUMBER_OF_COMPLETED_PACKETS = 0x13

    const val EVT_PIN_CODE_REQUEST = 0x16
    const val EVT_LINK_KEY_REQUEST = 0x17
    const val EVT_LINK_KEY_NOTIFICATION = 0x18
    const val EVT_MAX_SLOTS_CHANGE = 0x20
    const val EVT_INQUIRY_RESULT_WITH_RSSI = 0x22
    const val EVT_EXTENDED_INQUIRY_RESULT = 0x2F
    const val EVT_IO_CAPABILITY_REQUEST = 0x31
    const val EVT_IO_CAPABILITY_RESPONSE = 0x32
    const val EVT_USER_CONFIRMATION_REQUEST = 0x33
    const val EVT_USER_PASSKEY_REQUEST = 0x34
    const val EVT_SIMPLE_PAIRING_COMPLETE = 0x36

    /**
     * Mascara de eventos que hay que fijar ANTES de emparejar.
     *
     * La mascara por defecto de la especificacion es 0x00001FFFFFFFFFFF, o
     * sea bits 0..44. Los eventos del emparejamiento seguro son los bits
     * 48..53 — **fuera de la mascara por defecto**. Sin este comando, un
     * `IO Capability Request` (0x31) nunca llega, el emparejamiento se queda
     * colgado y no hay ni un solo sintoma: exactamente el mismo silencio que
     * daba la pila del radio.
     *
     * Los bytes van en little endian. Bit a bit:
     *   byte 0 = 0xFF  bits  0..7   todo (Inquiry, Connection Complete, ...)
     *   byte 1 = 0xFF  bits  8..15
     *   byte 2 = 0xFB  bits 16..23  el 18 es reservado y va en 0;
     *                               aqui viven PIN Code Request (21),
     *                               Link Key Request (22) y
     *                               Link Key Notification (23)
     *   byte 3 = 0xFF  bits 24..31
     *   byte 4 = 0x07  bits 32,33,34 Flow Spec, Inquiry+RSSI, Ext Features
     *   byte 5 = 0x18  bits 43,44    conexiones sincronas
     *   byte 6 = 0x3F  bits 48..53   IO Capability Req/Resp, User
     *                                Confirmation, User Passkey, OOB,
     *                                Simple Pairing Complete
     *   byte 7 = 0x3C  bits 58,59,60,61  Passkey Notification, Keypress,
     *                                Remote Host Features y **LE Meta**
     *
     * El bit 61 (LE Meta) se mantiene a proposito: el barrido BLE de la
     * bateria ya funciona y esta mascara no lo debe romper.
     */
    val MASCARA_EVENTOS: ByteArray = byteArrayOf(
        0xFF.toByte(), 0xFF.toByte(), 0xFB.toByte(), 0xFF.toByte(),
        0x07, 0x18, 0x3F, 0x3C,
    )

    /**
     * Tipos de paquete ACL permitidos en Create Connection.
     *
     * 0xCC18 = DM1|DH1|DM3|DH3|DM5|DH5. Es el valor que usa `hcitool cc`.
     *
     * Cuidado con la logica invertida de esta mascara: los bits de tasa
     * basica (DM/DH) valen 1 para **permitir**, pero los bits de EDR
     * (2-DHx, 3-DHx) valen 1 para **prohibir**. Al dejarlos en cero se
     * permite EDR. O sea que 0xCC18 significa "todo permitido", no "solo
     * tasa basica" — que es lo que uno leeria del nombre.
     */
    const val TIPOS_PAQUETE_ACL = 0xCC18

    /** Ninguna entrada ni salida: no hay teclado ni pantalla que ofrecer. */
    const val IO_NO_INPUT_NO_OUTPUT = 0x03

    /** Sin proteccion contra intermediario (imposible sin IO) y con vinculo. */
    const val AUTH_SIN_MITM_VINCULO_GENERAL = 0x04

    /** El otro extremo cerro por su cuenta. Es el motivo cortes de Disconnect. */
    const val MOTIVO_TERMINADO_POR_USUARIO = 0x13

    // ------------------------------------------------------------- direcciones

    /**
     * "00:1D:A5:68:98:8B" -> los 6 bytes en el orden del aire (little endian).
     *
     * HCI manda las BD_ADDR al reves de como se escriben. Confundirlo da un
     * Page Timeout eterno contra una MAC que no existe, y el sintoma es
     * indistinguible de "el adaptador no contesta".
     */
    fun mac(texto: String): ByteArray {
        val partes = texto.trim().split(':', '-')
        require(partes.size == 6) { "MAC mal formada: $texto" }
        val b = ByteArray(6)
        for (i in 0 until 6) {
            b[5 - i] = partes[i].toInt(16).toByte()
        }
        return b
    }

    /** Los 6 bytes little endian de vuelta a "00:1D:A5:68:98:8B". */
    fun macTexto(datos: ByteArray, desde: Int = 0): String {
        if (desde + 6 > datos.size) return "??:??:??:??:??:??"
        return (0 until 6).map { datos[desde + 5 - it] }
            .joinToString(":") { String.format("%02X", it) }
    }

    // ------------------------------------------------- armadores de parametros

    /**
     * Los 13 bytes de Create Connection (0x0405).
     *
     * @param mac           MAC destino en texto.
     * @param tiposPaquete  ver [TIPOS_PAQUETE_ACL].
     * @param modoPageScan  repeticion de barrido de pagina del OTRO extremo:
     *                      R0=0x00, R1=0x01, R2=0x02. Si venimos de un
     *                      Inquiry Result, se usa el que reporto ese evento.
     *                      Sin Inquiry se manda **R2 (0x02)**, que es lo que
     *                      hace `hcitool`: R2 asume la ventana de barrido mas
     *                      larga, o sea que el controlador pagina mas tiempo.
     *                      Suponer R0 con un modulo que en realidad es R2
     *                      produce Page Timeout aunque el aparato este ahi.
     * @param desfaseReloj  bit 15 = "el valor es valido". Sin Inquiry no se
     *                      sabe, y va 0x0000: el controlador pagina a ciegas,
     *                      que tarda mas pero funciona.
     * @param permitirCambioDeRol 0x00 = nos quedamos de maestro.
     *                      **Se manda 0x00 a proposito.** Ceder el rol deja
     *                      que un modulo chino barato decida la
     *                      temporizacion del enlace, y varios clones de
     *                      ELM327 gestionan mal el cambio de rol justo
     *                      durante el emparejamiento. Siendo maestro,
     *                      mandamos nosotros.
     */
    fun crearConexion(
        mac: String,
        tiposPaquete: Int = TIPOS_PAQUETE_ACL,
        modoPageScan: Int = 0x02,
        desfaseReloj: Int = 0x0000,
        permitirCambioDeRol: Int = 0x00,
    ): ByteArray {
        val p = ByteArray(13)
        mac(mac).copyInto(p, 0)
        p[6] = (tiposPaquete and 0xFF).toByte()
        p[7] = ((tiposPaquete shr 8) and 0xFF).toByte()
        p[8] = modoPageScan.toByte()
        p[9] = 0x00 // reservado desde la version 1.2; antes era Page Scan Mode
        p[10] = (desfaseReloj and 0xFF).toByte()
        p[11] = ((desfaseReloj shr 8) and 0xFF).toByte()
        p[12] = permitirCambioDeRol.toByte()
        return p
    }

    /** handle + motivo, para Disconnect (0x0406). */
    fun desconectar(handle: Int, motivo: Int = MOTIVO_TERMINADO_POR_USUARIO): ByteArray =
        byteArrayOf(
            (handle and 0xFF).toByte(),
            ((handle shr 8) and 0xFF).toByte(),
            motivo.toByte(),
        )

    /**
     * PIN Code Request Reply (0x040D): MAC + longitud + 16 bytes de PIN.
     *
     * El PIN va en ASCII, alineado a la izquierda y **relleno de ceros hasta
     * 16**, con su longitud real aparte. Mandar solo los 4 bytes del "1234"
     * es un error de longitud de parametros y el controlador lo rechaza.
     */
    fun pinReply(mac: String, pin: String): ByteArray {
        val ascii = pin.toByteArray(Charsets.US_ASCII)
        require(ascii.size in 1..16) { "PIN de longitud imposible: ${ascii.size}" }
        val p = ByteArray(23)
        mac(mac).copyInto(p, 0)
        p[6] = ascii.size.toByte()
        ascii.copyInto(p, 7)
        return p
    }

    /** Link Key Request Reply (0x040B): MAC + 16 bytes de llave. */
    fun linkKeyReply(mac: String, llave: ByteArray): ByteArray {
        require(llave.size == 16) { "una link key son 16 bytes, no ${llave.size}" }
        val p = ByteArray(22)
        mac(mac).copyInto(p, 0)
        llave.copyInto(p, 6)
        return p
    }

    /**
     * IO Capability Request Reply (0x042B).
     *
     * NoInputNoOutput + sin OOB + "sin MITM, vinculo general" es la
     * combinacion que produce el emparejamiento "Just Works": ni PIN ni
     * confirmacion humana, solo un `User Confirmation Request` que se
     * contesta solo. Es lo mas parecido a lo que hace un telefono cuando
     * empareja un manos libres sin preguntar nada.
     */
    fun ioCapabilityReply(
        mac: String,
        io: Int = IO_NO_INPUT_NO_OUTPUT,
        oob: Int = 0x00,
        requisitos: Int = AUTH_SIN_MITM_VINCULO_GENERAL,
    ): ByteArray {
        val p = ByteArray(9)
        mac(mac).copyInto(p, 0)
        p[6] = io.toByte()
        p[7] = oob.toByte()
        p[8] = requisitos.toByte()
        return p
    }

    /** Los comandos que solo llevan la MAC (negativas, confirmaciones). */
    fun soloMac(mac: String): ByteArray = mac(mac)

    /** User Passkey Request Reply (0x042E): MAC + numero de 6 digitos. */
    fun passkeyReply(mac: String, numero: Int): ByteArray {
        val p = ByteArray(10)
        mac(mac).copyInto(p, 0)
        p[6] = (numero and 0xFF).toByte()
        p[7] = ((numero shr 8) and 0xFF).toByte()
        p[8] = ((numero shr 16) and 0xFF).toByte()
        p[9] = ((numero shr 24) and 0xFF).toByte()
        return p
    }

    /**
     * Write Page Timeout (0x0C18), en unidades de 0.625 ms.
     *
     * El defecto son 0x2000 = 5.12 s. Se sube porque un clon de ELM327
     * dormido tarda en aparecer en su ventana de barrido de pagina, y un
     * Page Timeout se parece demasiado a "el aparato no existe".
     */
    fun pageTimeout(unidades: Int = 0x4000): ByteArray = byteArrayOf(
        (unidades and 0xFF).toByte(),
        ((unidades shr 8) and 0xFF).toByte(),
    )

    /** Inquiry (0x0401): LAP general + duracion en unidades de 1.28 s. */
    fun inquiry(segundosAprox: Int = 10, maxRespuestas: Int = 0x00): ByteArray {
        val unidades = (segundosAprox * 100 / 128).coerceIn(1, 0x30)
        // LAP general de acceso a la investigacion: 0x9E8B33, little endian.
        return byteArrayOf(0x33, 0x8B.toByte(), 0x9E.toByte(), unidades.toByte(), maxRespuestas.toByte())
    }

    // ---------------------------------------------------------- diagnosticable

    /**
     * Traduce un codigo de estado de HCI a algo que sirva a las 11 de la
     * noche en un estacionamiento.
     *
     * No es decoracion: la diferencia entre 0x04 y 0x05 es la diferencia
     * entre "apaga el Bluetooth del telefono" y "el PIN esta mal".
     */
    fun motivo(estado: Int): String = when (estado) {
        0x00 -> "EXITO"
        0x02 -> "0x02 handle desconocido"
        0x04 -> "0x04 PAGE TIMEOUT: el adaptador no contesto la pagina. " +
            "Suele ser que YA esta conectado a otra cosa (¿el telefono?), " +
            "que no tiene corriente (¿switch en contacto?), o que esta fuera de alcance"
        0x05 -> "0x05 fallo de autenticacion: el PIN o la link key no le cuadran"
        0x06 -> "0x06 falta PIN o llave: pidio autenticacion y no la hubo"
        0x07 -> "0x07 sin memoria en el controlador"
        0x08 -> "0x08 se agoto el plazo de la conexion"
        0x09 -> "0x09 limite de conexiones alcanzado"
        0x0B -> "0x0B YA existe una conexion ACL con esa MAC"
        0x0C -> "0x0C comando no permitido en este estado"
        0x0D -> "0x0D conexion rechazada: recursos limitados"
        0x0E -> "0x0E conexion rechazada por SEGURIDAD"
        0x0F -> "0x0F conexion rechazada: BD_ADDR inaceptable"
        0x10 -> "0x10 se agoto el plazo de aceptacion del otro extremo"
        0x11 -> "0x11 parametro no soportado"
        0x12 -> "0x12 parametro invalido"
        0x13 -> "0x13 el otro extremo termino la conexion"
        0x14 -> "0x14 el otro extremo se apago (bateria)"
        0x15 -> "0x15 el otro extremo termino por fin de servicio"
        0x16 -> "0x16 lo terminamos nosotros"
        0x17 -> "0x17 emparejamiento repetido demasiado pronto"
        0x18 -> "0x18 par de llaves no permitido"
        0x1A -> "0x1A el otro extremo no soporta esa funcion"
        0x1F -> "0x1F error no especificado"
        0x22 -> "0x22 se agoto el plazo de una transaccion LMP"
        0x23 -> "0x23 colision de transaccion LMP"
        0x25 -> "0x25 cifrado no permitido"
        0x28 -> "0x28 se agoto el plazo de una instancia"
        0x29 -> "0x29 el emparejamiento con unidad de llave no esta soportado"
        0x37 -> "0x37 el otro extremo no permite SSP"
        else -> "0x${String.format("%02X", estado)} sin traduccion"
    }
}
