package com.nonosky.carsoc.hci

import android.content.Context
import android.hardware.usb.UsbManager

/**
 * Interroga al dongle Bluetooth por HCI crudo y barre por BLE sin el kernel.
 *
 * El objetivo concreto: encontrar la bateria de litio, que habla BLE y que
 * la pila del radio nunca vio.
 */
object SondaHci {

    /**
     * "¿Estas ahi?" en HCI.
     *
     * Manda Reset, Read Local Version y Read BD_ADDR. Si el dongle contesta
     * los tres, hablarle desde el espacio de usuario funciona y el resto es
     * cuestion de escribir mas comandos. Si no contesta al Reset, esta via
     * esta cerrada y hay que decirlo sin rodeos.
     */
    fun interrogar(context: Context, vid: Int? = null, pid: Int? = null): List<String> =
        DuenoDongle.usar("sonda-hci", esperaMs = 4_000) { interrogarConDongle(context, vid, pid) }
            ?: listOf("el dongle lo tiene " + (DuenoDongle.ocupadoPor() ?: "otro"))

    private fun interrogarConDongle(context: Context, vid: Int?, pid: Int?): List<String> {
        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return listOf("ERROR: este radio no expone UsbManager")

        val salida = mutableListOf<String>()
        salida += "--- candidatos en el USB ---"
        salida += HciUsb.listarCandidatos(um)
        salida += "---"

        val dongle = HciUsb.buscarDongle(um, vid, pid)
            ?: return (salida + "ERROR: no encuentro ningun dongle HCI en el USB")

        salida += "elegido: VID=0x${String.format("%04X", dongle.vendorId)} " +
            "PID=0x${String.format("%04X", dongle.productId)} " +
            (runCatching { dongle.productName }.getOrNull() ?: "")

        val hci = HciUsb(um, dongle)
        return try {
            val traza = hci.abrir()
            salida += traza
            if (traza.any { it.startsWith("ERROR") }) return salida

            // Vaciar cualquier evento viejo antes de preguntar nada.
            while (hci.leerEvento(200) != null) { /* descartar */ }

            salida += ejecutar(hci, "RESET", HciUsb.CMD_RESET)
            salida += ejecutar(hci, "READ_LOCAL_VERSION", HciUsb.CMD_READ_LOCAL_VERSION)
            salida += ejecutar(hci, "READ_BD_ADDR", HciUsb.CMD_READ_BD_ADDR)
            // La pregunta que decide todo: ¿este dongle soporta LE de verdad?
            // Los Broadcom suelen necesitar que les suban su firmware para
            // que el LE funcione, y sin btusb nadie se lo subio.
            salida += ejecutar(hci, "READ_LOCAL_FEATURES", HciUsb.CMD_READ_LOCAL_FEATURES)
            salida += ejecutar(hci, "LE_READ_BUFFER_SIZE", HciUsb.CMD_LE_READ_BUFFER_SIZE)
            salida += ejecutar(hci, "LE_READ_LOCAL_FEATURES", HciUsb.CMD_LE_READ_LOCAL_FEATURES)
            salida
        } catch (e: Exception) {
            salida += "ERROR: ${e.javaClass.simpleName}: ${e.message}"
            salida
        } finally {
            runCatching { hci.cerrar() }
        }
    }

    private fun ejecutar(hci: HciUsb, nombre: String, opcode: Int): List<String> {
        val r = mutableListOf<String>()
        val escrito = hci.mandarComando(opcode)
        r += "$nombre (0x${String.format("%04X", opcode)}): controlTransfer -> $escrito"
        if (escrito < 0) {
            r += "   el comando NO se pudo mandar"
            return r
        }

        val evento = hci.leerEvento(2_000)
        if (evento == null) {
            r += "   SIN RESPUESTA"
            return r
        }
        r += "   evento: ${HciUsb.hex(evento)}"
        r += "   " + interpretar(evento)
        return r
    }

    /** Traduce los eventos que nos interesan a algo legible. */
    private fun interpretar(e: ByteArray): String {
        if (e.isEmpty()) return "evento vacio"
        val codigo = e[0].toInt() and 0xFF
        return when (codigo) {
            HciUsb.EVT_COMMAND_COMPLETE -> {
                if (e.size < 6) return "Command Complete truncado"
                val opcode = ((e[4].toInt() and 0xFF) shl 8) or (e[3].toInt() and 0xFF)
                val estado = e[5].toInt() and 0xFF
                val cola = if (e.size > 6) e.copyOfRange(6, e.size) else ByteArray(0)
                val extra = when (opcode) {
                    HciUsb.CMD_READ_BD_ADDR -> if (cola.size >= 6) {
                        " | MAC del dongle: " + cola.take(6).reversed()
                            .joinToString(":") { String.format("%02X", it) }
                    } else ""
                    HciUsb.CMD_READ_LOCAL_FEATURES -> if (cola.size >= 8) {
                        // Byte 4, bit 6: "LE Supported (Controller)".
                        val le = (cola[4].toInt() shr 6) and 1
                        val brEdrNoSoportado = (cola[4].toInt() shr 5) and 1
                        " | LE soportado=${le == 1} | BR/EDR-no-soportado=${brEdrNoSoportado == 1}" +
                            if (le == 0) "  <<< SIN LE: el chip necesita su firmware >>>" else ""
                    } else ""
                    HciUsb.CMD_READ_LOCAL_VERSION -> if (cola.size >= 8) {
                        val fabricante = ((cola[5].toInt() and 0xFF) shl 8) or (cola[4].toInt() and 0xFF)
                        " | HCI version=${cola[0].toInt() and 0xFF} fabricante=$fabricante " +
                            "(${nombreFabricante(fabricante)})"
                    } else ""
                    else -> ""
                }
                "Command Complete opcode=0x${String.format("%04X", opcode)} " +
                    "estado=$estado (${if (estado == 0) "EXITO" else "fallo"})$extra"
            }
            HciUsb.EVT_COMMAND_STATUS -> {
                val estado = if (e.size > 2) e[2].toInt() and 0xFF else -1
                "Command Status estado=$estado (${if (estado == 0) "aceptado" else "rechazado"})"
            }
            HciUsb.EVT_LE_META -> "LE Meta (subevento ${if (e.size > 2) e[2].toInt() and 0xFF else -1})"
            else -> "evento codigo=0x${String.format("%02X", codigo)}"
        }
    }

    private fun nombreFabricante(id: Int): String = when (id) {
        15 -> "Broadcom"
        2 -> "Intel"
        10 -> "Cambridge Silicon Radio"
        13 -> "Texas Instruments"
        70 -> "Realtek"
        else -> "id $id"
    }

    /**
     * Barrido BLE hablando HCI directamente, sin la pila de Android.
     *
     * Esto es lo que la pila del radio no pudo hacer: 25 segundos de barrido
     * BLE por la via normal no encontraron ni un solo aparato.
     *
     * Se pide barrido PASIVO: para encontrar un BMS que se anuncia, escuchar
     * basta, y ademas no se molesta al resto del aire.
     */
    fun barrerBle(
        context: Context,
        segundos: Int,
        vid: Int? = null,
        pid: Int? = null,
        activo: Boolean = false,
        crudo: Boolean = false,
        /**
         * Se llama con cada anuncio en cuanto llega (mac, nombre, rssi, UUID
         * de servicio). Es lo que usa el selector Bluetooth para ir pintando
         * la lista mientras barre, en vez de esperar al final.
         */
        alVer: ((String, String?, Int, List<String>) -> Unit)? = null,
    ): List<String> = DuenoDongle.usar("barrido-hci", esperaMs = 4_000) {
        barrerBleConDongle(context, segundos, vid, pid, activo, crudo, alVer)
    } ?: listOf("el dongle lo tiene " + (DuenoDongle.ocupadoPor() ?: "otro"))

    private fun barrerBleConDongle(
        context: Context,
        segundos: Int,
        vid: Int?,
        pid: Int?,
        activo: Boolean,
        crudo: Boolean,
        alVer: ((String, String?, Int, List<String>) -> Unit)?,
    ): List<String> {
        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return listOf("ERROR: este radio no expone UsbManager")

        val dongle = HciUsb.buscarDongle(um, vid, pid)
            ?: return listOf("ERROR: no encuentro ningun dongle HCI en el USB")

        val hci = HciUsb(um, dongle)
        val salida = mutableListOf<String>()
        val vistos = linkedMapOf<String, String>()

        return try {
            val traza = hci.abrir()
            salida += traza
            if (traza.any { it.startsWith("ERROR") }) return salida

            while (hci.leerEvento(200) != null) { /* vaciar */ }

            hci.mandarComando(HciUsb.CMD_RESET)
            hci.leerEvento(2_000)

            // Barrido pasivo: intervalo y ventana iguales = escucha continua.
            val params = byteArrayOf(
                if (activo) 0x01 else 0x00,
                0x10, 0x00,        // intervalo
                0x10, 0x00,        // ventana
                0x00,              // direccion propia publica
                0x00,              // sin filtro: no sabemos que buscamos
            )
            hci.mandarComando(HciUsb.CMD_LE_SET_SCAN_PARAMS, params)
            val evParams = hci.leerEvento(2_000)
            salida += "SET_SCAN_PARAMS: ${evParams?.let { HciUsb.hex(it) } ?: "SIN RESPUESTA"}"

            hci.mandarComando(HciUsb.CMD_LE_SET_SCAN_ENABLE, byteArrayOf(0x01, 0x00))
            val evEnable = hci.leerEvento(2_000)
            salida += "SET_SCAN_ENABLE: ${evEnable?.let { HciUsb.hex(it) } ?: "SIN RESPUESTA"}"

            // Contar TODO lo que llega, no solo lo que esperamos: si el
            // dongle emitiera eventos y el parseo los descartara, un "sin
            // anuncios" seria una mentira que costaria horas de buscar donde
            // no es.
            var totalEventos = 0
            val muestrasCrudas = mutableListOf<String>()
            val hasta = System.currentTimeMillis() + segundos.coerceIn(3, 30) * 1000L
            while (System.currentTimeMillis() < hasta) {
                val e = hci.leerEvento(500) ?: continue
                totalEventos++
                if (crudo && muestrasCrudas.size < 30) muestrasCrudas += HciUsb.hex(e)
                if ((e[0].toInt() and 0xFF) != HciUsb.EVT_LE_META) continue
                if (e.size < 3 || (e[2].toInt() and 0xFF) != HciUsb.SUBEVT_LE_ADVERTISING_REPORT) continue
                anotarAnuncio(e, vistos, alVer)
            }
            salida += "eventos HCI recibidos durante el barrido: $totalEventos"
            if (crudo && muestrasCrudas.isNotEmpty()) {
                salida += "--- eventos crudos ---"
                salida += muestrasCrudas
            }

            hci.mandarComando(HciUsb.CMD_LE_SET_SCAN_ENABLE, byteArrayOf(0x00, 0x00))

            if (vistos.isEmpty()) {
                salida += "Sin anuncios BLE en ${segundos}s."
            } else {
                salida += "--- ${vistos.size} aparatos ---"
                salida += vistos.values
            }
            salida
        } catch (e: Exception) {
            salida += "ERROR: ${e.javaClass.simpleName}: ${e.message}"
            salida
        } finally {
            runCatching { hci.cerrar() }
        }
    }

    /**
     * Desarma un LE Advertising Report.
     *
     * Formato: 3E len 02 num_reportes, y por cada reporte tipo(1),
     * tipo_direccion(1), direccion(6), longitud(1), datos(N), rssi(1).
     */
    private fun anotarAnuncio(
        e: ByteArray,
        vistos: MutableMap<String, String>,
        alVer: ((String, String?, Int, List<String>) -> Unit)? = null,
    ) {
        if (e.size < 12) return
        var i = 4 // saltar 3E, len, 02, num_reportes
        val numReportes = e[3].toInt() and 0xFF

        repeat(numReportes) {
            if (i + 8 > e.size) return
            i++ // tipo de evento
            i++ // tipo de direccion
            val mac = (0 until 6).map { e[i + it] }.reversed()
                .joinToString(":") { String.format("%02X", it) }
            i += 6
            if (i >= e.size) return
            val largo = e[i].toInt() and 0xFF
            i++
            if (i + largo > e.size) return
            val datos = e.copyOfRange(i, i + largo)
            i += largo
            val rssi = if (i < e.size) e[i].toInt() else 0
            i++

            vistos[mac] = "$mac  rssi=$rssi${nombreDe(datos)}\n    crudo=${HciUsb.hex(datos)}"
            if (alVer != null) {
                val (nombre, uuids) = leerAnuncio(datos)
                runCatching { alVer(mac, nombre, rssi, uuids) }
            }
        }
    }

    /** Saca el nombre del anuncio, que es lo que identifica al aparato. */
    /**
     * El nombre y los UUID de servicio de 16 bits de un anuncio BLE, ya en su
     * forma larga (`0000ff00-0000-1000-8000-00805f9b34fb`), que es como los da
     * la pila de Android. Asi el selector reconoce un BMS o una nevera venga
     * el anuncio por la radio interna o por el dongle.
     */
    fun leerAnuncio(datos: ByteArray): Pair<String?, List<String>> {
        var nombre: String? = null
        val uuids = mutableListOf<String>()
        var i = 0
        while (i + 1 < datos.size) {
            val largo = datos[i].toInt() and 0xFF
            if (largo == 0 || i + largo >= datos.size + 1) break
            val tipo = datos[i + 1].toInt() and 0xFF
            val fin = (i + 1 + largo).coerceAtMost(datos.size)
            when (tipo) {
                // 0x08 nombre corto, 0x09 nombre completo.
                0x08, 0x09 -> nombre = String(datos, i + 2, fin - i - 2, Charsets.UTF_8)
                // 0x02 lista incompleta, 0x03 completa: UUID de 16 bits, little endian.
                0x02, 0x03 -> {
                    var k = i + 2
                    while (k + 1 < fin) {
                        val v = (datos[k].toInt() and 0xFF) or ((datos[k + 1].toInt() and 0xFF) shl 8)
                        uuids += "0000%04x-0000-1000-8000-00805f9b34fb".format(v)
                        k += 2
                    }
                }
            }
            i += largo + 1
        }
        return nombre to uuids
    }

    private fun nombreDe(datos: ByteArray): String {
        var i = 0
        while (i < datos.size) {
            val largo = datos[i].toInt() and 0xFF
            if (largo == 0 || i + largo >= datos.size + 1) break
            if (i + 1 >= datos.size) break
            val tipo = datos[i + 1].toInt() and 0xFF
            // 0x08 nombre corto, 0x09 nombre completo.
            if (tipo == 0x08 || tipo == 0x09) {
                val fin = (i + 1 + largo).coerceAtMost(datos.size)
                return "  nombre=" + String(datos, i + 2, fin - i - 2, Charsets.UTF_8)
            }
            i += largo + 1
        }
        return ""
    }
}
