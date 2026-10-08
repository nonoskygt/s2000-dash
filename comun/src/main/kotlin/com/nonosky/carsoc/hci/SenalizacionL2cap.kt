package com.nonosky.carsoc.hci

/**
 * La senalizacion L2CAP: lo que hace falta para ABRIR un canal dinamico.
 *
 * Aqui esta la diferencia entre los dos objetivos del tablero:
 *
 *  - **La bateria (ATT sobre LE)** no necesita NADA de este archivo. El CID
 *    0x0004 esta abierto desde que hay enlace. Eso es lo que hace que leer la
 *    bateria sea alcanzable.
 *  - **El OBD (RFCOMM sobre clasico)** necesita todo esto: RFCOMM vive en un
 *    canal dinamico, y un canal dinamico hay que pedirlo (CONNECTION) y
 *    configurarlo (CONFIGURATION) por el CID 0x0001 antes de poder mandar un
 *    solo byte por el.
 *
 * ESTANDAR VERIFICABLE (Core Spec Vol 3 Part A, seccion 4). Cabecera de cada
 * comando de senalizacion, dentro de la carga de una PDU L2CAP con CID
 * 0x0001:
 *
 * ```
 *  byte 0   : Code        (que comando es)
 *  byte 1   : Identifier  (para casar peticion con respuesta; NUNCA 0)
 *  byte 2-3 : Length      (little endian) = bytes que siguen, sin contar estos 4
 *  byte 4.. : los datos del comando
 * ```
 *
 * En un enlace clasico **pueden ir varios comandos en una sola PDU**, asi que
 * [desarmar] devuelve una lista y no un comando. En LE (CID 0x0005) va uno
 * solo, pero tratarlos igual no rompe nada.
 *
 * Dos trampas que rompen en silencio y estan resueltas aqui:
 *
 *  1. El `Identifier` no puede ser 0 y no puede repetir el de una peticion
 *    sin contestar. [siguienteId] se salta el 0 al dar la vuelta.
 *  2. La configuracion es **en las dos direcciones**. El canal no queda
 *    abierto hasta que YO he configurado el suyo Y el ha configurado el mio.
 *    Quien manda su CONFIGURATION REQUEST, recibe la respuesta y empieza a
 *    hablar sin contestar el REQUEST del otro, deja al otro esperando para
 *    siempre — y el sintoma es "mando datos y no contesta", tres capas por
 *    encima de la causa.
 *
 * Sin Android: se prueba entero en la JVM.
 */
object SenalizacionL2cap {

    const val CABECERA = 4

    // --- Codigos de comando ---
    const val COD_RECHAZO = 0x01
    const val COD_CONEXION_PET = 0x02
    const val COD_CONEXION_RSP = 0x03
    const val COD_CONFIG_PET = 0x04
    const val COD_CONFIG_RSP = 0x05
    const val COD_DESCONEXION_PET = 0x06
    const val COD_DESCONEXION_RSP = 0x07
    const val COD_ECO_PET = 0x08
    const val COD_ECO_RSP = 0x09
    const val COD_INFO_PET = 0x0A
    const val COD_INFO_RSP = 0x0B

    // --- Resultado de CONNECTION RESPONSE ---
    const val RES_CONEXION_OK = 0x0000
    const val RES_CONEXION_PENDIENTE = 0x0001
    const val RES_CONEXION_PSM_NO_SOPORTADO = 0x0002
    const val RES_CONEXION_SEGURIDAD = 0x0003
    const val RES_CONEXION_SIN_RECURSOS = 0x0004
    const val RES_CONEXION_CID_INVALIDO = 0x0006
    const val RES_CONEXION_CID_YA_USADO = 0x0007

    // --- Resultado de CONFIGURATION RESPONSE ---
    const val RES_CONFIG_OK = 0x0000
    const val RES_CONFIG_PARAMETROS_INACEPTABLES = 0x0001
    const val RES_CONFIG_RECHAZADA = 0x0002
    const val RES_CONFIG_OPCIONES_DESCONOCIDAS = 0x0003
    const val RES_CONFIG_PENDIENTE = 0x0004
    const val RES_CONFIG_FLUJO_RECHAZADO = 0x0005

    // --- Tipos de opcion de configuracion ---
    const val OPC_MTU = 0x01
    const val OPC_VACIADO = 0x02
    const val OPC_QOS = 0x03
    const val OPC_RETRANSMISION = 0x04

    /**
     * Bit 7 del tipo de opcion: "hint".
     *
     * Una opcion con el hint puesto se puede ignorar sin protestar. Una sin
     * el hint que no se entienda OBLIGA a contestar
     * RES_CONFIG_OPCIONES_DESCONOCIDAS listandola; contestar OK a algo que no
     * se entendio es prometer un comportamiento que no se va a cumplir.
     */
    const val BIT_HINT = 0x80

    // --- Motivos de COMMAND REJECT ---
    const val RECHAZO_NO_ENTENDIDO = 0x0000
    const val RECHAZO_MTU_EXCEDIDA = 0x0001
    const val RECHAZO_CID_INVALIDO = 0x0002

    /** Tipo de INFORMATION REQUEST. */
    const val INFO_MTU_SIN_CONEXION = 0x0001
    const val INFO_CANALES_FIJOS = 0x0003
    const val INFO_RES_OK = 0x0000
    const val INFO_RES_NO_SOPORTADO = 0x0001

    class Mensaje(val codigo: Int, val id: Int, val datos: ByteArray) {
        override fun toString(): String =
            "${nombreCodigo(codigo)} id=$id datos=${datos.joinToString("") { "%02X".format(it) }}"
    }

    fun armar(codigo: Int, id: Int, datos: ByteArray): ByteArray {
        require(id in 1..255) { "el Identifier no puede ser 0 ni pasar de 255: $id" }
        val m = ByteArray(CABECERA + datos.size)
        m[0] = (codigo and 0xFF).toByte()
        m[1] = (id and 0xFF).toByte()
        m[2] = (datos.size and 0xFF).toByte()
        m[3] = ((datos.size shr 8) and 0xFF).toByte()
        datos.copyInto(m, CABECERA)
        return m
    }

    /**
     * Desarma la carga de una PDU de senalizacion en los comandos que trae.
     *
     * Tolerante a proposito: lo que no cuadra se para y se devuelve lo que si
     * se entendio. Un comando mal formado no puede tumbar el proceso.
     */
    fun desarmar(carga: ByteArray): List<Mensaje> {
        val salida = ArrayList<Mensaje>(1)
        var i = 0
        while (i + CABECERA <= carga.size) {
            val codigo = carga[i].toInt() and 0xFF
            val id = carga[i + 1].toInt() and 0xFF
            val largo = (carga[i + 2].toInt() and 0xFF) or ((carga[i + 3].toInt() and 0xFF) shl 8)
            if (i + CABECERA + largo > carga.size) break
            salida += Mensaje(codigo, id, carga.copyOfRange(i + CABECERA, i + CABECERA + largo))
            i += CABECERA + largo
            // Codigo 0 no existe: si aparece, el flujo esta desalineado y
            // seguir leyendo solo produciria basura con pinta de comando.
            if (codigo == 0) break
        }
        return salida
    }

    // ------------------------------------------------------------------
    // CONNECTION: pedir un canal para un PSM
    //   peticion : PSM(2) | Source CID(2)
    //   respuesta: Destination CID(2) | Source CID(2) | Result(2) | Status(2)
    // "Source" es siempre el del que MANDA el mensaje. Confundirlo hace que el
    // otro lado no reconozca el canal y conteste CID invalido.
    // ------------------------------------------------------------------

    fun peticionConexion(id: Int, psm: Int, cidOrigen: Int): ByteArray =
        armar(COD_CONEXION_PET, id, le16(psm) + le16(cidOrigen))

    class ConexionRsp(
        val cidDestino: Int,
        val cidOrigen: Int,
        val resultado: Int,
        val estado: Int,
    ) {
        val ok: Boolean get() = resultado == RES_CONEXION_OK
        val pendiente: Boolean get() = resultado == RES_CONEXION_PENDIENTE
    }

    fun leerConexionRsp(datos: ByteArray): ConexionRsp? {
        if (datos.size < 8) return null
        return ConexionRsp(u16(datos, 0), u16(datos, 2), u16(datos, 4), u16(datos, 6))
    }

    fun respuestaConexion(id: Int, cidDestino: Int, cidOrigen: Int, resultado: Int, estado: Int = 0):
        ByteArray = armar(
        COD_CONEXION_RSP, id,
        le16(cidDestino) + le16(cidOrigen) + le16(resultado) + le16(estado),
    )

    // ------------------------------------------------------------------
    // CONFIGURATION
    //   peticion : Destination CID(2) | Flags(2) | opciones
    //   respuesta: Source CID(2)      | Flags(2) | Result(2) | opciones
    // Flags bit 0 = continuacion (mas opciones en otro mensaje). Aqui se manda
    // siempre 0: una sola MTU cabe de sobra en la MTU de senalizacion de 48.
    // ------------------------------------------------------------------

    fun peticionConfig(id: Int, cidDestino: Int, mtu: Int?): ByteArray {
        val opciones = if (mtu == null) ByteArray(0)
        else byteArrayOf(OPC_MTU.toByte(), 2) + le16(mtu)
        return armar(COD_CONFIG_PET, id, le16(cidDestino) + le16(0) + opciones)
    }

    class ConfigPet(val cidDestino: Int, val banderas: Int, val opciones: ByteArray) {
        val continua: Boolean get() = (banderas and 0x01) != 0
    }

    fun leerConfigPet(datos: ByteArray): ConfigPet? {
        if (datos.size < 4) return null
        return ConfigPet(u16(datos, 0), u16(datos, 2), datos.copyOfRange(4, datos.size))
    }

    class ConfigRsp(
        val cidOrigen: Int,
        val banderas: Int,
        val resultado: Int,
        val opciones: ByteArray,
    ) {
        val ok: Boolean get() = resultado == RES_CONFIG_OK
        val continua: Boolean get() = (banderas and 0x01) != 0
    }

    fun leerConfigRsp(datos: ByteArray): ConfigRsp? {
        if (datos.size < 6) return null
        return ConfigRsp(u16(datos, 0), u16(datos, 2), u16(datos, 4), datos.copyOfRange(6, datos.size))
    }

    fun respuestaConfig(id: Int, cidOrigen: Int, resultado: Int, opciones: ByteArray): ByteArray =
        armar(COD_CONFIG_RSP, id, le16(cidOrigen) + le16(0) + le16(resultado) + opciones)

    // ------------------------------------------------------------------
    // DESCONEXION: Destination CID(2) | Source CID(2), en los dos sentidos.
    // ------------------------------------------------------------------

    fun peticionDesconexion(id: Int, cidDestino: Int, cidOrigen: Int): ByteArray =
        armar(COD_DESCONEXION_PET, id, le16(cidDestino) + le16(cidOrigen))

    fun respuestaDesconexion(id: Int, cidDestino: Int, cidOrigen: Int): ByteArray =
        armar(COD_DESCONEXION_RSP, id, le16(cidDestino) + le16(cidOrigen))

    /** Los dos CID de una peticion o respuesta de desconexion. */
    fun leerDesconexion(datos: ByteArray): Pair<Int, Int>? {
        if (datos.size < 4) return null
        return u16(datos, 0) to u16(datos, 2)
    }

    // ------------------------------------------------------------------
    // Lo que hay que contestar aunque no interese, para no dejar colgado al otro.
    // ------------------------------------------------------------------

    fun rechazo(id: Int, motivo: Int, datos: ByteArray = ByteArray(0)): ByteArray =
        armar(COD_RECHAZO, id, le16(motivo) + datos)

    fun respuestaInfo(id: Int, tipo: Int, resultado: Int, datos: ByteArray = ByteArray(0)): ByteArray =
        armar(COD_INFO_RSP, id, le16(tipo) + le16(resultado) + datos)

    fun respuestaEco(id: Int, datos: ByteArray = ByteArray(0)): ByteArray =
        armar(COD_ECO_RSP, id, datos)

    // ------------------------------------------------------------------
    // Opciones de configuracion
    //   tipo(1) | largo(1) | valor(largo)
    // ------------------------------------------------------------------

    class Opcion(val tipo: Int, val valor: ByteArray) {
        val hint: Boolean get() = (tipo and BIT_HINT) != 0
        val tipoLimpio: Int get() = tipo and 0x7F
    }

    fun leerOpciones(opciones: ByteArray): List<Opcion> {
        val salida = ArrayList<Opcion>(2)
        var i = 0
        while (i + 2 <= opciones.size) {
            val tipo = opciones[i].toInt() and 0xFF
            val largo = opciones[i + 1].toInt() and 0xFF
            if (i + 2 + largo > opciones.size) break
            salida += Opcion(tipo, opciones.copyOfRange(i + 2, i + 2 + largo))
            i += 2 + largo
        }
        return salida
    }

    /** La MTU que pide el otro lado, si la pide. */
    fun mtuDeOpciones(opciones: ByteArray): Int? =
        leerOpciones(opciones).firstOrNull { it.tipoLimpio == OPC_MTU && it.valor.size >= 2 }
            ?.let { u16(it.valor, 0) }

    /**
     * Decide que contestar a las opciones que pide el otro lado.
     *
     * Se aceptan MTU y tiempo de vaciado, y la opcion de retransmision solo
     * si pide modo basico (primer byte 0x00). Todo lo demas sin hint se
     * rechaza por su tipo: es lo que manda la especificacion y ademas es lo
     * honesto — decir "de acuerdo" a un modo de retransmision que no esta
     * implementado da un canal que parece abierto y pierde datos.
     */
    fun revisarOpciones(opciones: ByteArray): Veredicto {
        val desconocidas = ArrayList<Int>(1)
        for (o in leerOpciones(opciones)) {
            val conocida = when (o.tipoLimpio) {
                OPC_MTU, OPC_VACIADO -> true
                OPC_RETRANSMISION -> o.valor.isNotEmpty() && o.valor[0].toInt() == 0
                else -> false
            }
            if (!conocida && !o.hint) desconocidas += o.tipoLimpio
        }
        return if (desconocidas.isEmpty()) Veredicto(RES_CONFIG_OK, ByteArray(0))
        else Veredicto(
            RES_CONFIG_OPCIONES_DESCONOCIDAS,
            // En la respuesta se listan los TIPOS que no se entendieron, con
            // largo 0. Asi el otro lado sabe cual quitar y reintentar.
            desconocidas.fold(ByteArray(0)) { acc, t -> acc + byteArrayOf(t.toByte(), 0) },
        )
    }

    class Veredicto(val resultado: Int, val opciones: ByteArray) {
        val ok: Boolean get() = resultado == RES_CONFIG_OK
    }

    /**
     * Siguiente Identifier valido.
     *
     * Da la vuelta en 255 y **se salta el 0**, que la especificacion reserva
     * para "ninguno". Un id 0 en una peticion es un mensaje que el otro lado
     * puede rechazar sin decir por que.
     */
    fun siguienteId(actual: Int): Int = if (actual >= 255) 1 else actual + 1

    fun nombreCodigo(c: Int): String = when (c) {
        COD_RECHAZO -> "COMMAND REJECT"
        COD_CONEXION_PET -> "CONNECTION REQUEST"
        COD_CONEXION_RSP -> "CONNECTION RESPONSE"
        COD_CONFIG_PET -> "CONFIGURATION REQUEST"
        COD_CONFIG_RSP -> "CONFIGURATION RESPONSE"
        COD_DESCONEXION_PET -> "DISCONNECTION REQUEST"
        COD_DESCONEXION_RSP -> "DISCONNECTION RESPONSE"
        COD_ECO_PET -> "ECHO REQUEST"
        COD_ECO_RSP -> "ECHO RESPONSE"
        COD_INFO_PET -> "INFORMATION REQUEST"
        COD_INFO_RSP -> "INFORMATION RESPONSE"
        else -> "codigo 0x${"%02X".format(c)}"
    }

    fun nombreResultadoConexion(r: Int): String = when (r) {
        RES_CONEXION_OK -> "OK"
        RES_CONEXION_PENDIENTE -> "pendiente"
        RES_CONEXION_PSM_NO_SOPORTADO -> "PSM no soportado"
        RES_CONEXION_SEGURIDAD -> "bloqueado por seguridad (hace falta emparejar)"
        RES_CONEXION_SIN_RECURSOS -> "sin recursos"
        RES_CONEXION_CID_INVALIDO -> "CID de origen invalido"
        RES_CONEXION_CID_YA_USADO -> "CID de origen ya en uso"
        else -> "resultado 0x${"%04X".format(r)}"
    }

    private fun le16(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun u16(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
}
