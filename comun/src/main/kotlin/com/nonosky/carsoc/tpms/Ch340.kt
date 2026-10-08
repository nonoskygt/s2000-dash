package com.nonosky.carsoc.tpms

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Driver minimo para los USB-serial CH340/CH341.
 *
 * Se escribe a mano en vez de traer una libreria por una razon practica: el
 * APK se instala solo por HTTP en un radio sin tienda, y cada dependencia
 * nueva es mas superficie que puede romper una actualizacion que nadie
 * puede reparar a mano. Aqui solo hacen falta dos cosas —fijar la velocidad
 * y leer del endpoint BULK— y eso cabe en una clase.
 *
 * El receptor TPMS del radio resulto ser un CH340 (VID 0x1A86, PID 0x7523)
 * con endpoints BULK de 32 bytes, y el permiso USB ya concedido.
 *
 * La secuencia de inicializacion y el calculo del divisor de velocidad
 * siguen al driver `ch341` de Linux, que es la referencia de facto de este
 * chip.
 */
class Ch340(
    private val manager: UsbManager,
    private val device: UsbDevice,
) {

    private var conexion: UsbDeviceConnection? = null
    private var entrada: UsbEndpoint? = null
    private var salida: UsbEndpoint? = null

    /**
     * La interfaz reclamada, para poder soltarla en [cerrar].
     *
     * Antes no se guardaba porque el unico uso era un volcado de ocho
     * segundos y `close()` lo limpia todo de todas formas. Con un lector que
     * vive horas y reabre cada vez que el dongle parpadea, soltar lo que se
     * reclamo deja de ser cosmetico.
     */
    private var interfaz: android.hardware.usb.UsbInterface? = null

    val abierto: Boolean get() = conexion != null

    /** Tamaño del bloque BULK de entrada. 32 bytes en este receptor. */
    val tamPaquete: Int get() = entrada?.maxPacketSize?.coerceAtLeast(32) ?: 32

    /**
     * Abre el aparato y lo deja a [baudios].
     *
     * Devuelve una traza de lo que hizo, no un booleano: cuando esto falla
     * en un radio al que no se puede entrar por shell, saber en QUE paso
     * fallo es la diferencia entre arreglarlo y adivinar.
     */
    fun abrir(baudios: Int): List<String> {
        val traza = mutableListOf<String>()

        // Abrir dos veces sin cerrar dejaria la conexion anterior colgada y
        // sin dueño. Con un lector que reabre en cada reconexion eso son
        // decenas de descriptores perdidos en un viaje largo.
        if (conexion != null) cerrar()

        if (!manager.hasPermission(device)) {
            return listOf("ERROR: sin permiso USB para este aparato")
        }

        val itf = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { i ->
                (0 until i.endpointCount).any { j ->
                    i.getEndpoint(j).type == UsbConstants.USB_ENDPOINT_XFER_BULK
                }
            } ?: return listOf("ERROR: el aparato no expone ninguna interfaz con endpoints BULK")

        val con = manager.openDevice(device)
            ?: return listOf("ERROR: openDevice devolvio null")

        if (!con.claimInterface(itf, true)) {
            con.close()
            return listOf("ERROR: no se pudo reclamar la interfaz (¿la tiene el kernel?)")
        }
        traza += "interfaz reclamada"

        for (j in 0 until itf.endpointCount) {
            val ep = itf.getEndpoint(j)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) entrada = ep else salida = ep
        }
        if (entrada == null) {
            con.releaseInterface(itf)
            con.close()
            return listOf("ERROR: no hay endpoint BULK de ENTRADA; no habria nada que leer")
        }
        traza += "endpoints listos (entrada=${entrada?.address}, salida=${salida?.address})"

        conexion = con
        interfaz = itf
        traza += inicializar(con, baudios)
        return traza
    }

    /**
     * Cambia la velocidad sin cerrar ni volver a reclamar la interfaz.
     *
     * Existe para que el diagnostico pueda probar otra velocidad **sin que
     * nadie mas toque el aparato**: cerrar y reabrir vuelve a lanzar la
     * secuencia de init y a mover DTR/RTS, que es justo lo que puede
     * reiniciar el receptor y perder las tramas que se querian observar.
     */
    fun reconfigurar(baudios: Int): List<String> {
        val con = conexion ?: return listOf("ERROR: no esta abierto")
        return inicializar(con, baudios)
    }

    /** Secuencia del driver `ch341` de Linux, en el mismo orden. */
    private fun inicializar(con: UsbDeviceConnection, baudios: Int): List<String> {
        val traza = mutableListOf<String>()

        fun ctrl(peticion: Int, valor: Int, indice: Int, etiqueta: String): Boolean {
            val r = con.controlTransfer(TIPO_SALIDA_VENDOR, peticion, valor, indice, null, 0, TIMEOUT_MS)
            traza += "$etiqueta -> $r"
            return r >= 0
        }

        ctrl(SERIAL_INIT, 0, 0, "init")

        // Velocidad: el chip no toma baudios, toma un factor y un divisor.
        val (regValor, regIndice) = divisorPara(baudios)
        ctrl(WRITE_REG, REG_PRESCALER, regValor, "prescaler")
        ctrl(WRITE_REG, REG_DIVISOR, regIndice, "divisor")

        // 8 bits, sin paridad, 1 stop.
        ctrl(WRITE_REG, REG_LCR, LCR_8N1, "formato 8N1")

        // Levantar DTR y RTS: hay dongles que no transmiten sin ellas.
        ctrl(MODEM_CTRL, DTR or RTS, 0, "DTR/RTS")

        traza += "velocidad fijada a $baudios baudios"
        return traza
    }

    /**
     * Factor y divisor del CH341 para una velocidad dada.
     *
     * El chip divide un reloj base; hay que bajar el factor a rangos que
     * quepan en 16 bits ajustando el divisor. Es la aritmetica del driver
     * de Linux, replicada tal cual.
     */
    private fun divisorPara(baudios: Int): Pair<Int, Int> {
        var factor = BASE_RELOJ / baudios.coerceAtLeast(1)
        var divisor = DIV_MAX

        while (factor > 0xFFF0 && divisor > 0) {
            factor = factor shr 3
            divisor--
        }
        factor = 0x10000 - factor

        val valor = (factor and 0xFF00) or divisor
        val indice = factor and 0x00FF
        return valor to indice
    }

    /**
     * Lee lo que llegue durante [ms] y lo devuelve crudo.
     *
     * Devuelve los bytes sin interpretar a proposito. El formato de trama
     * de estos receptores TPMS es propietario y cambia por marca; escribir
     * un decodificador sin haber visto un byte real seria inventarselo.
     */
    fun leerCrudo(ms: Long): ByteArray {
        val con = conexion ?: return ByteArray(0)
        val ep = entrada ?: return ByteArray(0)

        val acumulado = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(ep.maxPacketSize.coerceAtLeast(32))
        val hasta = System.currentTimeMillis() + ms

        while (System.currentTimeMillis() < hasta && acumulado.size() < MAX_ACUMULADO) {
            val n = con.bulkTransfer(ep, buffer, buffer.size, LECTURA_TIMEOUT_MS)
            if (n > 0) acumulado.write(buffer, 0, n)
        }
        return acumulado.toByteArray()
    }

    /**
     * Una sola lectura BULK, sin acumular. Devuelve los bytes leidos.
     *
     * Es lo que necesita un lector continuo, y es distinto de [leerCrudo]:
     * aquel bloquea una ventana entera y aloca un `ByteArrayOutputStream` que
     * crece, asi que ni se puede interrumpir a mitad ni sirve para correr
     * horas seguidas.
     *
     * **Un retorno negativo NO es un error.** Es lo que devuelve el timeout
     * cuando no llego nada, y con el carro parado eso es el caso normal: los
     * sensores transmiten cada medio minuto. Quien llame a esto tiene que
     * distinguir "no habia nada que leer" de "el aparato se fue".
     */
    fun leerBloque(buffer: ByteArray, timeoutMs: Int): Int {
        val con = conexion ?: return -1
        val ep = entrada ?: return -1
        return con.bulkTransfer(ep, buffer, buffer.size, timeoutMs)
    }

    /** Manda bytes al aparato, por si hace falta despertarlo o interrogarlo. */
    fun escribir(datos: ByteArray): Int {
        val con = conexion ?: return -1
        val ep = salida ?: return -1
        return con.bulkTransfer(ep, datos, datos.size, TIMEOUT_MS)
    }

    fun cerrar() {
        val con = conexion
        val itf = interfaz
        if (con != null && itf != null) {
            runCatching { con.releaseInterface(itf) }
                .onFailure { Log.w(TAG, "releaseInterface fallo: ${it.message}") }
        }
        runCatching { con?.close() }
            .onFailure { Log.w(TAG, "cerrar fallo: ${it.message}") }
        conexion = null
        interfaz = null
        entrada = null
        salida = null
    }

    companion object {
        private const val TAG = "Ch340"

        const val VID_QINHENG = 0x1A86
        const val PID_CH340 = 0x7523

        /** Peticion de vendedor, direccion salida (host -> aparato). */
        private const val TIPO_SALIDA_VENDOR = 0x40

        private const val SERIAL_INIT = 0xA1
        private const val WRITE_REG = 0x9A
        private const val MODEM_CTRL = 0xA4

        private const val REG_PRESCALER = 0x1312
        private const val REG_DIVISOR = 0x0F2C
        private const val REG_LCR = 0x2518

        /** 8 bits de datos, sin paridad, 1 bit de parada. */
        private const val LCR_8N1 = 0x00C3

        private const val DTR = 0x20
        private const val RTS = 0x40

        private const val BASE_RELOJ = 1_532_620_800
        private const val DIV_MAX = 3

        private const val TIMEOUT_MS = 2_000
        private const val LECTURA_TIMEOUT_MS = 200
        private const val MAX_ACUMULADO = 64 * 1024

        /** Las velocidades tipicas de un receptor TPMS barato. */
        val VELOCIDADES_TIPICAS = listOf(9600, 19200, 38400, 57600, 115200)
    }
}
