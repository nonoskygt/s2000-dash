package com.nonosky.carsoc.hci

/**
 * ATT sobre L2CAP: lo justo para descubrir el BMS, activar sus notificaciones
 * y pedirle datos. No es una pila GATT.
 *
 * ============================================================================
 * QUE ES VERIFICABLE Y QUE ES HIPOTESIS
 * ============================================================================
 *
 * TODO lo de este archivo es VERIFICABLE: sale de la especificacion Bluetooth
 * Core, Vol 3 Parte F (protocolo ATT) y Parte G (perfil GATT). Ni un campo
 * depende de este BMS concreto. Cualquiera puede releer la especificacion y
 * comprobar byte a byte lo que hay aqui.
 *
 * Lo que es hipotesis vive en [com.nonosky.carsoc.bateria.BmsJbd]: que
 * caracteristica usa este aparato para escribir y cual para notificar. Aqui no
 * se supone nada de eso — se DESCUBRE, que es precisamente el motivo de
 * implementar el descubrimiento en vez de codificar handles a mano.
 *
 * ----------------------------------------------------------------------------
 * EL ERROR CLASICO, Y POR QUE ESTA CLASE EXISTE
 * ----------------------------------------------------------------------------
 * Un BMS JBD no manda absolutamente nada hasta que el cliente escribe 0x0001
 * en el descriptor CCCD (UUID 0x2902) de su caracteristica de notificacion.
 * Sin ese paso la conexion se abre, el descubrimiento funciona, la peticion se
 * escribe sin error... y no llega ni un byte. Parece un aparato muerto y no lo
 * esta. Ver [valorCccdNotificar].
 *
 * ----------------------------------------------------------------------------
 * EL MTU, Y POR QUE HAY QUE REENSAMBLAR AUNQUE SE NEGOCIE
 * ----------------------------------------------------------------------------
 * El ATT_MTU por omision son 23 bytes. Una notificacion cabe en MTU-3 = 20
 * bytes de valor (1 de opcode + 2 de handle). La respuesta del registro 0x03
 * del BMS mide 34 bytes, asi que llega en DOS notificaciones de 20 y 14.
 *
 * Se pide MTU mayor con [peticionMtu], pero el reensamblado NO es opcional
 * aunque el par conceda 517: el par puede contestar 23, puede no contestar (y
 * entonces se queda en 23 por especificacion), y ademas el registro 0x04 de una
 * bateria de 32 celdas mide 71 bytes, que no cabe ni con MTU 64.
 *
 * Sin nada de Android a proposito: se prueba entero en la JVM.
 */
object Att {

    // ========================================================================
    // L2CAP. ATT vive siempre en un canal fijo, no hay que negociarlo.
    //
    // El armado y desarmado de la trama L2CAP NO esta aqui: vive en [L2cap] y
    // en [EnsambladorAcl], que es donde tiene que estar. Tener dos copias de un
    // framing en dos capas es como se acaba con dos capas que discrepan sobre
    // si el campo de largo incluye la cabecera.
    // ========================================================================

    /** Canal fijo de ATT. Es el mismo valor que [L2cap.CID_ATT]. */
    const val CID_ATT = L2cap.CID_ATT

    // ========================================================================
    // Opcodes ATT. Solo los que se usan; los demas no se reconocen a proposito.
    // ========================================================================

    const val OP_ERROR = 0x01
    const val OP_MTU_PETICION = 0x02
    const val OP_MTU_RESPUESTA = 0x03
    const val OP_LEER_POR_TIPO_PETICION = 0x08
    const val OP_LEER_POR_TIPO_RESPUESTA = 0x09
    const val OP_LEER_POR_GRUPO_PETICION = 0x10
    const val OP_LEER_POR_GRUPO_RESPUESTA = 0x11
    const val OP_ESCRIBIR_PETICION = 0x12
    const val OP_ESCRIBIR_RESPUESTA = 0x13
    const val OP_ESCRIBIR_COMANDO = 0x52
    const val OP_NOTIFICACION = 0x1B
    const val OP_INDICACION = 0x1D
    const val OP_CONFIRMACION = 0x1E

    // ========================================================================
    // UUID de declaracion de GATT. Vol 3 Parte G, seccion 3.
    // ========================================================================

    /** Declaracion de servicio primario. */
    const val UUID_SERVICIO_PRIMARIO = 0x2800

    /** Declaracion de caracteristica. */
    const val UUID_CARACTERISTICA = 0x2803

    /**
     * Client Characteristic Configuration Descriptor.
     *
     * El descriptor que enciende las notificaciones. Escribirle 0x0001 es el
     * paso que separa "el BMS no dice nada" de "el BMS habla".
     */
    const val UUID_CCCD = 0x2902

    /** Rango completo de handles de ATT. Un descubrimiento empieza aqui. */
    const val HANDLE_PRIMERO = 0x0001
    const val HANDLE_ULTIMO = 0xFFFF

    // ========================================================================
    // MTU
    // ========================================================================

    /** ATT_MTU por omision. Si nadie negocia nada, es esto y no hay mas. */
    const val MTU_POR_DEFECTO = 23

    /** Minimo legal. Un par que conteste menos de 23 esta incumpliendo. */
    const val MTU_MINIMO = 23

    /**
     * Lo que se pide. 247 = 244 de datos utiles, el tope tipico de un movil.
     *
     * Pedir mas no rompe nada (el resultado es el minimo de los dos), pero
     * tampoco gana nada: el limite real lo pone el buffer LE del controlador,
     * que en este dongle mide 27 bytes por paquete — o sea que un ATT_MTU
     * grande se va a fragmentar en L2CAP de todas formas.
     */
    const val MTU_DESEADO = 247

    /** Cuanto valor cabe en una notificacion: opcode(1) + handle(2). */
    fun cargaMaxima(mtu: Int): Int = (mtu.coerceAtLeast(MTU_MINIMO)) - 3

    // ========================================================================
    // Propiedades de caracteristica. Vol 3 Parte G, tabla 3.5.
    // ========================================================================

    const val PROP_DIFUSION = 0x01
    const val PROP_LEER = 0x02
    const val PROP_ESCRIBIR_SIN_RESPUESTA = 0x04
    const val PROP_ESCRIBIR = 0x08
    const val PROP_NOTIFICAR = 0x10
    const val PROP_INDICAR = 0x20
    const val PROP_ESCRITURA_FIRMADA = 0x40
    const val PROP_EXTENDIDAS = 0x80

    fun describirPropiedades(p: Int): String {
        val n = mutableListOf<String>()
        if (p and PROP_DIFUSION != 0) n += "difusion"
        if (p and PROP_LEER != 0) n += "leer"
        if (p and PROP_ESCRIBIR_SIN_RESPUESTA != 0) n += "escribir-sin-respuesta"
        if (p and PROP_ESCRIBIR != 0) n += "escribir"
        if (p and PROP_NOTIFICAR != 0) n += "notificar"
        if (p and PROP_INDICAR != 0) n += "indicar"
        if (p and PROP_ESCRITURA_FIRMADA != 0) n += "escritura-firmada"
        if (p and PROP_EXTENDIDAS != 0) n += "extendidas"
        return if (n.isEmpty()) "ninguna" else n.joinToString("+")
    }

    // ========================================================================
    // Constructores de PDU
    // ========================================================================

    /**
     * `Exchange MTU Request`: opcode 0x02 + MTU de recepcion del cliente (2 LE).
     *
     * Se manda PRIMERO, antes de cualquier descubrimiento. Por especificacion
     * solo se puede intercambiar MTU una vez por conexion, y hacerlo despues de
     * empezar a descubrir es un error de protocolo.
     */
    fun peticionMtu(mtu: Int = MTU_DESEADO): ByteArray {
        val m = mtu.coerceIn(MTU_MINIMO, 0xFFFF)
        return byteArrayOf(OP_MTU_PETICION.toByte(), (m and 0xFF).toByte(), ((m shr 8) and 0xFF).toByte())
    }

    /**
     * `Read By Group Type Request`: descubre SERVICIOS.
     *
     * opcode 0x10 + handle inicial (2 LE) + handle final (2 LE) + UUID de grupo
     * (2 LE = 0x2800).
     *
     * Se llama en bucle: la respuesta trae los servicios que caben en un MTU, y
     * hay que volver a preguntar desde el handle siguiente al ultimo devuelto
     * hasta que conteste `Error Response` con 0x0A (Attribute Not Found). Ese
     * error NO es un fallo: es como termina el descubrimiento.
     */
    fun peticionServicios(desde: Int = HANDLE_PRIMERO, hasta: Int = HANDLE_ULTIMO): ByteArray =
        byteArrayOf(
            OP_LEER_POR_GRUPO_PETICION.toByte(),
            (desde and 0xFF).toByte(), ((desde shr 8) and 0xFF).toByte(),
            (hasta and 0xFF).toByte(), ((hasta shr 8) and 0xFF).toByte(),
            (UUID_SERVICIO_PRIMARIO and 0xFF).toByte(),
            ((UUID_SERVICIO_PRIMARIO shr 8) and 0xFF).toByte(),
        )

    /**
     * `Read By Type Request`: descubre CARACTERISTICAS (o cualquier tipo).
     *
     * opcode 0x08 + handle inicial (2 LE) + handle final (2 LE) + UUID (2 LE).
     *
     * Con UUID 0x2803 lista las caracteristicas de un rango; con 0x2902
     * encuentra el CCCD dentro del rango de UNA caracteristica, que es
     * exactamente lo que hace falta para activar notificaciones sin adivinar
     * "el handle de la caracteristica mas uno".
     */
    fun peticionPorTipo(desde: Int, hasta: Int, uuid16: Int): ByteArray =
        byteArrayOf(
            OP_LEER_POR_TIPO_PETICION.toByte(),
            (desde and 0xFF).toByte(), ((desde shr 8) and 0xFF).toByte(),
            (hasta and 0xFF).toByte(), ((hasta shr 8) and 0xFF).toByte(),
            (uuid16 and 0xFF).toByte(), ((uuid16 shr 8) and 0xFF).toByte(),
        )

    /**
     * `Write Request`: opcode 0x12 + handle (2 LE) + valor.
     *
     * Con acuse: el par contesta `Write Response` (0x13) o `Error Response`. Es
     * lo que hay que usar para el CCCD — si la escritura del CCCD falla en
     * silencio, el sintoma es "el BMS no contesta" y se busca el fallo donde no
     * esta.
     */
    fun escrituraConAcuse(handle: Int, valor: ByteArray): ByteArray {
        val p = ByteArray(3 + valor.size)
        p[0] = OP_ESCRIBIR_PETICION.toByte()
        p[1] = (handle and 0xFF).toByte()
        p[2] = ((handle shr 8) and 0xFF).toByte()
        valor.copyInto(p, 3)
        return p
    }

    /**
     * `Write Command`: opcode 0x52 + handle (2 LE) + valor. SIN acuse.
     *
     * Es lo que espera la caracteristica de escritura de un BMS JBD, que suele
     * declarar solo "escribir-sin-respuesta". Si se le manda 0x12 a una
     * caracteristica que no soporta escritura con acuse, contesta
     * `Error Response` 0x03 (Write Not Permitted) — y ese error se ve, que es
     * mejor que no verlo. Por eso [com.nonosky.carsoc.bateria.LectorBmsGatt]
     * elige el opcode segun las propiedades DESCUBIERTAS y no por costumbre.
     */
    fun escrituraSinAcuse(handle: Int, valor: ByteArray): ByteArray {
        val p = ByteArray(3 + valor.size)
        p[0] = OP_ESCRIBIR_COMANDO.toByte()
        p[1] = (handle and 0xFF).toByte()
        p[2] = ((handle shr 8) and 0xFF).toByte()
        valor.copyInto(p, 3)
        return p
    }

    /** El valor del CCCD que enciende notificaciones: 0x0001, LSB primero. */
    fun valorCccdNotificar(): ByteArray = byteArrayOf(0x01, 0x00)

    /** El valor del CCCD que enciende indicaciones (con acuse): 0x0002. */
    fun valorCccdIndicar(): ByteArray = byteArrayOf(0x02, 0x00)

    /** El valor del CCCD que apaga todo. Se manda al soltar el enlace. */
    fun valorCccdApagar(): ByteArray = byteArrayOf(0x00, 0x00)

    /** `Confirmation`: la respuesta obligada a una indicacion (0x1D). */
    fun confirmacion(): ByteArray = byteArrayOf(OP_CONFIRMACION.toByte())

    // ========================================================================
    // UUID: 16 bits o 128 bits, y no se pueden confundir.
    // ========================================================================

    /**
     * Un UUID tal como viene en una respuesta ATT.
     *
     * Se guarda el crudo SIEMPRE, ademas del corto. Misma razon que en
     * TramaTpms: si un modulo distinto usa UUID de 128 bits, el volcado sigue
     * sirviendo para identificarlo en vez de perderse.
     */
    class Uuid(val crudo: ByteArray) {

        /** El valor de 16 bits, o null si es un UUID de 128 bits ajeno. */
        val corto: Int?
            get() = when {
                crudo.size == 2 -> (crudo[0].toInt() and 0xFF) or ((crudo[1].toInt() and 0xFF) shl 8)
                // Un UUID de 16 bits promovido a 128 lleva la base de Bluetooth
                // 0000xxxx-0000-1000-8000-00805F9B34FB. En orden LE los 12
                // ultimos bytes son fijos y los cortos van en las posiciones
                // 12 y 13. Reconocerlo importa: hay modulos que contestan el
                // servicio 0xFF00 en su forma larga y si no se detecta, el
                // descubrimiento no encuentra el servicio que SI esta ahi.
                crudo.size == 16 && esBaseBluetooth() ->
                    (crudo[12].toInt() and 0xFF) or ((crudo[13].toInt() and 0xFF) shl 8)
                else -> null
            }

        private fun esBaseBluetooth(): Boolean {
            val base = byteArrayOf(
                0xFB.toByte(), 0x34, 0x9B.toByte(), 0x5F, 0x80.toByte(), 0x00,
                0x00, 0x80.toByte(), 0x00, 0x10, 0x00, 0x00,
            )
            for (i in base.indices) if (crudo[i] != base[i]) return false
            return crudo[14] == 0.toByte() && crudo[15] == 0.toByte()
        }

        override fun toString(): String =
            corto?.let { "0x%04X".format(it) }
                ?: crudo.reversed().joinToString("") { "%02X".format(it) }

        override fun equals(other: Any?): Boolean =
            other is Uuid && crudo.contentEquals(other.crudo)

        override fun hashCode(): Int = crudo.contentHashCode()
    }

    // ========================================================================
    // PDU recibidas
    // ========================================================================

    /** Un servicio primario descubierto: rango de handles + UUID. */
    data class Servicio(val handleInicio: Int, val handleFin: Int, val uuid: Uuid)

    /** Una caracteristica descubierta. */
    data class Caracteristica(
        /** Handle de la DECLARACION. No es donde se lee ni se escribe. */
        val handleDeclaracion: Int,
        val propiedades: Int,
        /** Handle del VALOR. Este si es el que se usa para leer/escribir. */
        val handleValor: Int,
        val uuid: Uuid,
    ) {
        fun notifica(): Boolean = propiedades and PROP_NOTIFICAR != 0
        fun indica(): Boolean = propiedades and PROP_INDICAR != 0
        fun escribible(): Boolean =
            propiedades and (PROP_ESCRIBIR or PROP_ESCRIBIR_SIN_RESPUESTA) != 0

        /**
         * El opcode correcto para escribirle: con acuse si lo soporta.
         *
         * Se prefiere el acuse porque una escritura de CCCD que falla en
         * silencio es el error mas caro de este camino.
         */
        fun conAcuse(): Boolean = propiedades and PROP_ESCRIBIR != 0

        fun describir(): String =
            "decl=0x%04X valor=0x%04X uuid=%s [%s]".format(
                handleDeclaracion, handleValor, uuid, describirPropiedades(propiedades),
            )
    }

    /** Lo que puede llegar por el canal ATT. */
    sealed class Pdu {
        /** `Error Response`. 0x0A al descubrir NO es un fallo: es el final. */
        data class Error(val opcodePeticion: Int, val handle: Int, val codigo: Int) : Pdu() {
            /** Attribute Not Found: asi termina un bucle de descubrimiento. */
            val finDeDescubrimiento: Boolean get() = codigo == 0x0A
            fun describir(): String =
                "Error a la peticion 0x%02X en handle 0x%04X: 0x%02X (%s)".format(
                    opcodePeticion, handle, codigo, nombreError(codigo),
                )
        }

        data class Mtu(val mtu: Int) : Pdu()
        data class Servicios(val lista: List<Servicio>) : Pdu()
        data class Caracteristicas(val lista: List<Caracteristica>) : Pdu()

        /** Read By Type de un tipo que no es 0x2803: handles y valores crudos. */
        data class Atributos(val lista: List<Pair<Int, ByteArray>>) : Pdu()

        /** `Write Response`: la escritura con acuse llego. No trae datos. */
        object EscrituraConfirmada : Pdu()

        /** Asi contesta el BMS. El valor es un TROZO de trama, no una trama. */
        data class Notificacion(val handle: Int, val valor: ByteArray) : Pdu() {
            override fun equals(other: Any?): Boolean =
                other is Notificacion && handle == other.handle && valor.contentEquals(other.valor)

            override fun hashCode(): Int = handle * 31 + valor.contentHashCode()
        }

        /** Como una notificacion pero exige [confirmacion]. */
        data class Indicacion(val handle: Int, val valor: ByteArray) : Pdu() {
            override fun equals(other: Any?): Boolean =
                other is Indicacion && handle == other.handle && valor.contentEquals(other.valor)

            override fun hashCode(): Int = handle * 31 + valor.contentHashCode()
        }

        /** Reconocida como PDU pero no interpretada. Se vuelca cruda. */
        data class Desconocida(val opcode: Int, val crudo: ByteArray) : Pdu()
    }

    /**
     * Desarma una PDU ATT. Nunca lanza.
     *
     * Le puede entrar cualquier cosa: un trozo, una PDU de un opcode que no se
     * implementa, o basura. Devuelve null solo si esta vacia.
     */
    fun interpretar(pdu: ByteArray?): Pdu? {
        if (pdu == null || pdu.isEmpty()) return null
        val op = pdu[0].toInt() and 0xFF
        return when (op) {
            OP_ERROR -> {
                if (pdu.size < 5) Pdu.Desconocida(op, pdu.copyOf())
                else Pdu.Error(
                    opcodePeticion = u(pdu, 1),
                    handle = u(pdu, 2) or (u(pdu, 3) shl 8),
                    codigo = u(pdu, 4),
                )
            }
            OP_MTU_RESPUESTA, OP_MTU_PETICION -> {
                if (pdu.size < 3) Pdu.Desconocida(op, pdu.copyOf())
                // Un par no puede bajar del minimo legal. Si contesta menos,
                // se usa 23: seguir su numero produciria PDU mas cortas de lo
                // que la especificacion garantiza y fallos raros mas adelante.
                else Pdu.Mtu((u(pdu, 1) or (u(pdu, 2) shl 8)).coerceAtLeast(MTU_MINIMO))
            }
            OP_LEER_POR_GRUPO_RESPUESTA -> interpretarGrupos(pdu)
            OP_LEER_POR_TIPO_RESPUESTA -> interpretarPorTipo(pdu)
            OP_ESCRIBIR_RESPUESTA -> Pdu.EscrituraConfirmada
            OP_NOTIFICACION -> {
                if (pdu.size < 3) Pdu.Desconocida(op, pdu.copyOf())
                else Pdu.Notificacion(u(pdu, 1) or (u(pdu, 2) shl 8), pdu.copyOfRange(3, pdu.size))
            }
            OP_INDICACION -> {
                if (pdu.size < 3) Pdu.Desconocida(op, pdu.copyOf())
                else Pdu.Indicacion(u(pdu, 1) or (u(pdu, 2) shl 8), pdu.copyOfRange(3, pdu.size))
            }
            else -> Pdu.Desconocida(op, pdu.copyOf())
        }
    }

    /**
     * `Read By Group Type Response`: 0x11 | largoEntrada | entradas.
     *
     * Cada entrada mide `largoEntrada` bytes: inicio(2) fin(2) valor(resto).
     * Todas las entradas de UNA respuesta tienen el mismo largo — si en el
     * rango hay servicios con UUID corto y largo mezclados, el par manda dos
     * respuestas, no una con entradas de distinto tamaño.
     */
    private fun interpretarGrupos(pdu: ByteArray): Pdu {
        if (pdu.size < 2) return Pdu.Desconocida(u(pdu, 0), pdu.copyOf())
        val largo = u(pdu, 1)
        // Un largo que no permita al menos inicio+fin+UUID corto es basura.
        if (largo < 6) return Pdu.Desconocida(u(pdu, 0), pdu.copyOf())

        val lista = mutableListOf<Servicio>()
        var i = 2
        // Solo entradas COMPLETAS. Una entrada a medias significa que la PDU
        // llego cortada, y media entrada da un UUID inventado.
        while (i + largo <= pdu.size) {
            lista += Servicio(
                handleInicio = u(pdu, i) or (u(pdu, i + 1) shl 8),
                handleFin = u(pdu, i + 2) or (u(pdu, i + 3) shl 8),
                uuid = Uuid(pdu.copyOfRange(i + 4, i + largo)),
            )
            i += largo
        }
        return Pdu.Servicios(lista)
    }

    /**
     * `Read By Type Response`: 0x09 | largoEntrada | entradas.
     *
     * Cada entrada: handle(2) + valor(largo-2). Si el valor tiene forma de
     * declaracion de caracteristica —propiedades(1) + handleValor(2) + UUID—
     * se devuelve como [Pdu.Caracteristicas]; si no, como [Pdu.Atributos] con
     * el valor crudo, que es lo que hace falta cuando se busca un CCCD.
     */
    private fun interpretarPorTipo(pdu: ByteArray): Pdu {
        if (pdu.size < 2) return Pdu.Desconocida(u(pdu, 0), pdu.copyOf())
        val largo = u(pdu, 1)
        if (largo < 3) return Pdu.Desconocida(u(pdu, 0), pdu.copyOf())

        val crudos = mutableListOf<Pair<Int, ByteArray>>()
        var i = 2
        while (i + largo <= pdu.size) {
            crudos += (u(pdu, i) or (u(pdu, i + 1) shl 8)) to pdu.copyOfRange(i + 2, i + largo)
            i += largo
        }
        if (crudos.isEmpty()) return Pdu.Atributos(emptyList())

        // Una declaracion de caracteristica mide 1+2+2 = 5 o 1+2+16 = 19.
        val valorLargo = largo - 2
        if (valorLargo != 5 && valorLargo != 19) return Pdu.Atributos(crudos)

        return Pdu.Caracteristicas(
            crudos.map { (handle, v) ->
                Caracteristica(
                    handleDeclaracion = handle,
                    propiedades = v[0].toInt() and 0xFF,
                    handleValor = (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8),
                    uuid = Uuid(v.copyOfRange(3, v.size)),
                )
            }
        )
    }

    /** Los codigos de error de ATT que de verdad aparecen en este camino. */
    fun nombreError(codigo: Int): String = when (codigo) {
        0x01 -> "handle invalido"
        0x02 -> "lectura no permitida"
        0x03 -> "escritura no permitida"
        0x04 -> "PDU invalida"
        0x05 -> "hace falta autenticacion"
        0x06 -> "peticion no soportada"
        0x07 -> "desplazamiento invalido"
        0x08 -> "hace falta autorizacion"
        0x09 -> "cola de escrituras llena"
        0x0A -> "atributo no encontrado (asi TERMINA un descubrimiento: no es un fallo)"
        0x0B -> "el atributo no es largo"
        0x0C -> "clave de cifrado demasiado corta"
        0x0D -> "largo de valor invalido"
        0x0E -> "error inesperado"
        0x0F -> "hace falta cifrado"
        0x11 -> "recursos insuficientes"
        else -> "codigo 0x%02X".format(codigo)
    }

    private fun u(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
}
