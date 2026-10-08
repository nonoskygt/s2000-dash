package com.nonosky.carsoc.hci

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Habla HCI directamente con un dongle Bluetooth por USB, sin el kernel.
 *
 * Existe porque la pila Bluetooth de este radio no sirve —no ve adaptadores
 * que cualquier telefono ve, se apaga sola, y un barrido BLE de 25 segundos
 * no encuentra nada— y porque el kernel de esta ROM **no trae `btusb`**:
 * `/sys/class/bluetooth/` esta vacio y el dongle quedo enganchado al driver
 * USB generico. Android nunca lo va a adoptar como su radio.
 *
 * Pero Android SI nos concede permiso sobre el aparato USB. Y un dongle
 * Bluetooth es, por especificacion, un transporte HCI muy simple:
 *
 *   - comandos HCI  -> transferencia de CONTROL (bmRequestType 0x20)
 *   - eventos HCI   -> endpoint de INTERRUPCION de entrada
 *   - datos ACL     -> endpoints BULK
 *
 * O sea que se le puede hablar desde el espacio de usuario, saltandose la
 * pila rota por completo. Eso es lo que hace esta clase.
 *
 * NO es una pila Bluetooth: es lo justo para preguntarle quien es y para
 * barrer por BLE, que es lo que hace falta para encontrar la bateria.
 */
class HciUsb(
    private val manager: UsbManager,
    private val device: UsbDevice,
) : CanalUsbHci {

    private var conexion: UsbDeviceConnection? = null
    private var itf: UsbInterface? = null
    private var eventos: UsbEndpoint? = null

    /**
     * Los dos BULK: por aqui van los DATOS.
     *
     * Sin ellos solo se puede preguntar y escuchar respuestas (comandos y
     * eventos), que es justo donde se quedo la primera version: se podia
     * barrer BLE y encontrar la bateria, pero no leerla. Los datos ACL —y por
     * tanto L2CAP, y por tanto ATT y RFCOMM— viajan por estos endpoints y por
     * ningun otro.
     */
    private var aclEntrada: UsbEndpoint? = null
    private var aclSalida: UsbEndpoint? = null

    override val abierto: Boolean get() = conexion != null

    /** Hay camino de datos, no solo de comandos. */
    override val tieneAcl: Boolean get() = aclEntrada != null && aclSalida != null

    /**
     * Tamano de bloque del BULK de salida. 64 bytes en este dongle.
     *
     * Lo necesita [PaqueteAcl.maxDatosSeguro] para no mandar nunca una
     * transferencia que sea multiplo exacto de este numero.
     */
    override val tamBloqueSalida: Int get() = aclSalida?.maxPacketSize ?: 64

    override val tamBloqueEntrada: Int get() = aclEntrada?.maxPacketSize ?: 64

    /**
     * Reclama la interfaz HCI del dongle.
     *
     * La interfaz 0 es la del transporte HCI: trae un endpoint de
     * interrupcion (eventos) y dos BULK (datos ACL). Las demas interfaces de
     * un dongle son isocronas, para audio SCO, y no nos sirven.
     */
    fun abrir(): List<String> {
        val traza = mutableListOf<String>()

        if (!manager.hasPermission(device)) return listOf("ERROR: sin permiso USB")

        val candidata = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { i ->
                (0 until i.endpointCount).any { j ->
                    val e = i.getEndpoint(j)
                    e.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                        e.direction == UsbConstants.USB_DIR_IN
                }
            } ?: return listOf("ERROR: no hay interfaz con endpoint de interrupcion (¿no es un dongle HCI?)")

        val con = manager.openDevice(device) ?: return listOf("ERROR: openDevice devolvio null")
        if (!con.claimInterface(candidata, true)) {
            con.close()
            return listOf("ERROR: no se pudo reclamar la interfaz HCI")
        }

        for (j in 0 until candidata.endpointCount) {
            val e = candidata.getEndpoint(j)
            when {
                e.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                    e.direction == UsbConstants.USB_DIR_IN -> eventos = e
                e.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    e.direction == UsbConstants.USB_DIR_IN -> aclEntrada = e
                e.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    e.direction == UsbConstants.USB_DIR_OUT -> aclSalida = e
            }
        }
        if (eventos == null) {
            con.releaseInterface(candidata)
            con.close()
            return listOf("ERROR: la interfaz no expone endpoint de eventos")
        }

        conexion = con
        itf = candidata
        traza += "interfaz HCI reclamada (eventos en endpoint ${eventos?.address}, " +
            "maxPacket=${eventos?.maxPacketSize})"
        // Que falten los BULK no es motivo para no abrir: interrogar y barrer
        // BLE funcionan solo con comandos y eventos, y eso debe seguir
        // funcionando aunque este dongle no sirviera para datos.
        traza += if (tieneAcl) {
            "camino ACL listo (entrada=${aclEntrada?.address} maxPacket=$tamBloqueEntrada, " +
                "salida=${aclSalida?.address} maxPacket=$tamBloqueSalida)"
        } else {
            "AVISO: sin endpoints BULK completos; hay comandos y eventos pero NO hay datos ACL"
        }
        return traza
    }

    /**
     * Manda un paquete HCI ACL ya armado por el BULK de salida.
     *
     * Devuelve los bytes escritos, o negativo si fallo. **No trocea y no
     * cuenta creditos**: eso es de capas de mas arriba ([PaqueteAcl.trocear] y
     * [ControlFlujoAcl]). Esta clase es el cable, nada mas.
     */
    override fun escribirAclCrudo(paquete: ByteArray, timeoutMs: Int): Int {
        val con = conexion ?: return -1
        val ep = aclSalida ?: return -1
        return con.bulkTransfer(ep, paquete, paquete.size, timeoutMs)
    }

    /**
     * Una lectura del BULK de entrada, sin interpretar.
     *
     * Devuelve el trozo tal cual: **puede ser un paquete ACL a medias**,
     * porque el endpoint entrega como mucho `maxPacketSize` bytes por lectura.
     * Juntarlos es tarea de [ReensamblaUsb], igual que con los eventos.
     *
     * Un retorno nulo NO es un error: es lo normal cuando no hay trafico.
     */
    override fun leerAclCrudo(buffer: ByteArray, timeoutMs: Int): Int {
        val con = conexion ?: return -1
        val ep = aclEntrada ?: return -1
        return con.bulkTransfer(ep, buffer, buffer.size, timeoutMs)
    }

    /**
     * Manda un comando HCI por el endpoint de control.
     *
     * En el transporte USB de Bluetooth los comandos NO llevan el byte de
     * tipo de paquete: el tipo lo da el propio endpoint. Por eso aqui va
     * solo opcode y parametros.
     */
    override fun mandarComando(opcode: Int, parametros: ByteArray): Int {
        val con = conexion ?: return -1
        val paquete = ByteArray(3 + parametros.size)
        paquete[0] = (opcode and 0xFF).toByte()
        paquete[1] = ((opcode shr 8) and 0xFF).toByte()
        paquete[2] = parametros.size.toByte()
        parametros.copyInto(paquete, 3)
        return con.controlTransfer(
            TIPO_CONTROL_HCI, 0x00, 0, 0, paquete, paquete.size, TIMEOUT_MS
        )
    }

    /**
     * Lee un evento HCI **completo**, o `null` si no llego nada.
     *
     * Reensambla a proposito, y no es un detalle: el endpoint de eventos de
     * este dongle tiene `maxPacketSize` de **16 bytes**, asi que un informe
     * de anuncio BLE —que ronda los 40— llega partido en tres trozos. La
     * primera version leia un solo trozo y lo trataba como evento entero:
     * el resultado eran MAC inventadas a partir de bytes que en realidad
     * eran datos de anuncio, y ni un aparato reconocido aunque el aire
     * estuviera lleno.
     *
     * La cabecera HCI dice cuanto falta: byte 0 el codigo, byte 1 la
     * longitud de los parametros. Se lee hasta completar `2 + longitud`.
     */
    override fun leerEvento(timeoutMs: Int): ByteArray? {
        val con = conexion ?: return null
        val ep = eventos ?: return null
        val paquete = ep.maxPacketSize.coerceAtLeast(16)
        val buf = ByteArray(paquete)

        val primero = con.bulkTransfer(ep, buf, paquete, timeoutMs)
        if (primero <= 0) return null

        val acumulado = java.io.ByteArrayOutputStream()
        acumulado.write(buf, 0, primero)

        // Sin los dos bytes de cabecera no se sabe cuanto falta.
        if (acumulado.size() < 2) return acumulado.toByteArray()
        val total = 2 + (acumulado.toByteArray()[1].toInt() and 0xFF)

        // Los trozos que faltan ya estan en el pipe: un plazo corto basta y
        // evita quedarse esperando por un evento que ya llego entero.
        var vacios = 0
        while (acumulado.size() < total && vacios < 4) {
            val n = con.bulkTransfer(ep, buf, paquete, CONTINUACION_MS)
            if (n > 0) acumulado.write(buf, 0, n) else vacios++
        }
        return acumulado.toByteArray()
    }

    override fun cerrar() {
        runCatching {
            itf?.let { conexion?.releaseInterface(it) }
            conexion?.close()
        }.onFailure { Log.w(TAG, "cerrar fallo: ${it.message}") }
        conexion = null
        itf = null
        eventos = null
        aclEntrada = null
        aclSalida = null
    }

    companion object {
        private const val TAG = "HciUsb"

        /**
         * Clase + salida + destinatario aparato. Es lo que fija el
         * transporte USB de Bluetooth para los comandos HCI.
         */
        private const val TIPO_CONTROL_HCI = 0x20

        private const val TIMEOUT_MS = 1_500

        /** Plazo para los trozos que continuan un evento ya empezado. */
        private const val CONTINUACION_MS = 250

        // --- Opcodes. opcode = (OGF shl 10) or OCF ---
        /** Reinicia el controlador. El "¿estas ahi?" de HCI. */
        const val CMD_RESET = 0x0C03
        /** Version del controlador: delata fabricante y revision. */
        const val CMD_READ_LOCAL_VERSION = 0x1001
        /** Direccion Bluetooth propia del dongle. */
        const val CMD_READ_BD_ADDR = 0x1009

        /** Bit 6 del byte 4 de estas features dice si el chip soporta LE. */
        const val CMD_READ_LOCAL_FEATURES = 0x1003
        const val CMD_LE_READ_BUFFER_SIZE = 0x2002
        const val CMD_LE_READ_LOCAL_FEATURES = 0x2003

        const val CMD_LE_SET_SCAN_PARAMS = 0x200B
        const val CMD_LE_SET_SCAN_ENABLE = 0x200C

        /**
         * Tamano y numero de buffers ACL del pool BR/EDR.
         *
         * Hace falta ANTES de mandar un solo paquete de datos: sin saber
         * cuantos caben no hay control de flujo, y sin control de flujo el
         * controlador se desborda y deja de contestar.
         */
        const val CMD_READ_BUFFER_SIZE = 0x1005

        // --- Enlace LE: lo que hace falta para llegar a la bateria ---
        const val CMD_LE_CREATE_CONNECTION = 0x200D
        const val CMD_LE_CREATE_CONNECTION_CANCEL = 0x200E
        const val CMD_LE_SET_EVENT_MASK = 0x2001

        /** Cierra un enlace. Motivo tipico: 0x13, "terminado por el usuario". */
        const val CMD_DISCONNECT = 0x0406

        // --- Enlace clasico: lo que hace falta para llegar al ELM327 ---
        const val CMD_CREATE_CONNECTION = 0x0405
        const val CMD_ACCEPT_CONNECTION = 0x0409
        const val CMD_LINK_KEY_REQUEST_REPLY = 0x040B
        const val CMD_LINK_KEY_REQUEST_NEG_REPLY = 0x040C
        const val CMD_PIN_CODE_REQUEST_REPLY = 0x040D
        const val CMD_PIN_CODE_REQUEST_NEG_REPLY = 0x040E
        const val CMD_AUTH_REQUESTED = 0x0411
        const val CMD_IO_CAPABILITY_REPLY = 0x042B
        const val CMD_USER_CONFIRMATION_REPLY = 0x042C
        const val CMD_WRITE_SCAN_ENABLE = 0x0C1A
        const val CMD_SET_EVENT_MASK = 0x0C01
        const val CMD_WRITE_SIMPLE_PAIRING_MODE = 0x0C56
        const val CMD_REMOTE_NAME_REQUEST = 0x0419

        // --- Codigos de evento ---
        const val EVT_COMMAND_COMPLETE = 0x0E
        const val EVT_COMMAND_STATUS = 0x0F
        const val EVT_LE_META = 0x3E
        const val SUBEVT_LE_ADVERTISING_REPORT = 0x02

        /** LE Connection Complete. Trae el handle con el que se habla ACL. */
        const val SUBEVT_LE_CONNECTION_COMPLETE = 0x01

        /** LE Enhanced Connection Complete: mismo papel, con mas campos. */
        const val SUBEVT_LE_ENHANCED_CONNECTION_COMPLETE = 0x0A

        /** Connection Complete de un enlace clasico. */
        const val EVT_CONNECTION_COMPLETE = 0x03

        const val EVT_DISCONNECTION_COMPLETE = 0x05
        const val EVT_AUTH_COMPLETE = 0x06
        const val EVT_ENCRYPTION_CHANGE = 0x08

        /**
         * Number Of Completed Packets: el UNICO permiso para seguir enviando.
         *
         * Sin atender este evento el pool de buffers se agota y el
         * controlador se queda mudo. Ver [ControlFlujoAcl].
         */
        const val EVT_NUM_COMPLETED_PACKETS = 0x13

        const val EVT_PIN_CODE_REQUEST = 0x16
        const val EVT_LINK_KEY_REQUEST = 0x17
        const val EVT_LINK_KEY_NOTIFICATION = 0x18
        const val EVT_MAX_SLOTS_CHANGE = 0x1B
        const val EVT_IO_CAPABILITY_REQUEST = 0x31
        const val EVT_USER_CONFIRMATION_REQUEST = 0x33
        const val EVT_SIMPLE_PAIRING_COMPLETE = 0x36

        /** Motivo de desconexion "terminado por el usuario local". */
        const val RAZON_TERMINADO_LOCAL = 0x13

        /** Un dongle Bluetooth se declara con esta clase en su descriptor. */
        const val CLASE_INALAMBRICA = 0xE0

        fun hex(b: ByteArray): String = b.joinToString("") { String.format("%02X", it) }

        /** VID de los USB-serial baratos. NO son dongles Bluetooth. */
        private val VID_SERIAL = setOf(0x1A86, 0x10C4, 0x0403, 0x067B)

        /**
         * Localiza el dongle Bluetooth entre lo que haya en el USB.
         *
         * El primer intento buscaba solo por forma —interrupcion de entrada
         * mas dos BULK— y eligio el receptor TPMS: un CH340 tiene exactamente
         * esos tres endpoints. Buscar por forma no basta cuando dos aparatos
         * distintos tienen la misma silueta.
         *
         * Ahora manda el descriptor: la especificacion USB reserva la clase
         * 0xE0 subclase 0x01 protocolo 0x01 para el transporte Bluetooth, y
         * eso no lo tiene un conversor serial. Los VID de los serial conocidos
         * se descartan de entrada por si algun clon miente en su clase.
         */
        /**
         * ¿Hay un dongle Bluetooth USB enchufado? En algunos radios es la
         * unica radio BLE que recibe algo; en otros no hace falta.
         */
        fun hayDongle(context: android.content.Context): Boolean = runCatching {
            val um = context.getSystemService(android.content.Context.USB_SERVICE) as? UsbManager
            um != null && buscarDongle(um) != null
        }.getOrDefault(false)

        fun buscarDongle(um: UsbManager, vid: Int? = null, pid: Int? = null): UsbDevice? {
            val todos = runCatching { um.deviceList.values.toList() }.getOrNull() ?: return null

            // Si nos dicen cual, no adivinamos.
            if (vid != null) {
                return todos.firstOrNull { it.vendorId == vid && (pid == null || it.productId == pid) }
            }

            // 1. Por descriptor: es la respuesta correcta cuando existe.
            todos.firstOrNull { d -> (0 until d.interfaceCount).any { esInterfazBluetooth(d.getInterface(it)) } }
                ?.let { return it }

            // 2. Por forma, pero descartando los serial conocidos.
            return todos.firstOrNull { d ->
                d.vendorId !in VID_SERIAL && (0 until d.interfaceCount).any { i ->
                    tieneFormaHci(d.getInterface(i))
                }
            }
        }

        private fun esInterfazBluetooth(itf: UsbInterface): Boolean =
            itf.interfaceClass == CLASE_INALAMBRICA &&
                itf.interfaceSubclass == 0x01 &&
                itf.interfaceProtocol == 0x01

        private fun tieneFormaHci(itf: UsbInterface): Boolean {
            var interrupcion = false
            var bulkEntrada = false
            var bulkSalida = false
            for (j in 0 until itf.endpointCount) {
                val e = itf.getEndpoint(j)
                when {
                    e.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                        e.direction == UsbConstants.USB_DIR_IN -> interrupcion = true
                    e.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        e.direction == UsbConstants.USB_DIR_IN -> bulkEntrada = true
                    e.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        e.direction == UsbConstants.USB_DIR_OUT -> bulkSalida = true
                }
            }
            return interrupcion && bulkEntrada && bulkSalida
        }

        /** Todos los candidatos con su motivo, para poder diagnosticar la eleccion. */
        fun listarCandidatos(um: UsbManager): List<String> =
            runCatching { um.deviceList.values.toList() }.getOrNull().orEmpty().map { d ->
                val porClase = (0 until d.interfaceCount).any { esInterfazBluetooth(d.getInterface(it)) }
                val porForma = (0 until d.interfaceCount).any { tieneFormaHci(d.getInterface(it)) }
                "VID=0x${String.format("%04X", d.vendorId)} " +
                    "PID=0x${String.format("%04X", d.productId)} " +
                    (runCatching { d.productName }.getOrNull() ?: "") +
                    " | claseBluetooth=$porClase formaHci=$porForma " +
                    "esSerial=${d.vendorId in VID_SERIAL}"
            }
    }
}
