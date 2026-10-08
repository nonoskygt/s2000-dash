package com.nonosky.carsoc.descubrimiento

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import com.nonosky.carsoc.tpms.Ch340
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Descubrimiento crudo de las fuentes de datos que NO son el OBD por SPP.
 *
 * Existe por una razon muy concreta: el radio no tiene root, sus Ajustes
 * estan recortados y `dumpsys` esta vetado, asi que la unica manera de saber
 * QUE hay conectado —una bateria de litio por BLE, un receptor TPMS por USB—
 * es que la propia app lo mire y lo cuente por HTTP.
 *
 * Todo lo que se devuelve es **crudo y en hexadecimal**. Es deliberado: no
 * se puede escribir un decodificador para un aparato cuyo formato no se ha
 * visto. Primero se mira el dato real, despues se decodifica. Inventar el
 * formato y presentarlo como hecho es la peor manera de perder un dia.
 */
object Descubridor {

    private const val TAG = "Descubridor"

    // --- Bluetooth LE -------------------------------------------------------

    /**
     * Barre por Bluetooth LE y devuelve el anuncio crudo de cada hallazgo.
     *
     * El barrido que ya tenia la app es CLASICO (`startDiscovery`), y un
     * barrido clasico **no ve dispositivos BLE**: son dos radios distintas
     * dentro del mismo chip. Por eso la bateria nunca aparecio.
     *
     * Bloquea [segundos] y devuelve. Lo llama el puente HTTP, cuyo socket
     * tiene un timeout generoso justo para esto.
     */
    @SuppressLint("MissingPermission")
    fun barrerBle(context: Context, adapter: BluetoothAdapter?, segundos: Int): List<String> {
        val problemas = revisarRequisitosBle(context, adapter)
        if (problemas.isNotEmpty()) return problemas

        val scanner = adapter?.bluetoothLeScanner
            ?: return listOf("ERROR: no hay BluetoothLeScanner (¿Bluetooth apagado?)")

        // Se acumula por MAC y no en una lista: un mismo aparato se anuncia
        // muchas veces por segundo y la salida seria ilegible.
        val vistos = ConcurrentHashMap<String, String>()
        val listo = CountDownLatch(1)

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result ?: return
                runCatching { vistos[result.device.address] = describir(result) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { onScanResult(0, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                vistos["ERROR"] = "ERROR: onScanFailed codigo=$errorCode (${motivoFallo(errorCode)})"
                listo.countDown()
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        return try {
            // Sin filtros a proposito: no sabemos que buscamos todavia.
            scanner.startScan(emptyList(), settings, callback)
            listo.await(segundos.toLong().coerceIn(3, 30), TimeUnit.SECONDS)
            runCatching { scanner.stopScan(callback) }
            if (vistos.isEmpty()) {
                listOf(
                    "Sin hallazgos BLE en ${segundos}s.",
                    "Ojo: esto NO prueba que no haya nada. Un aparato ya conectado " +
                        "a otro telefono deja de anunciarse.",
                )
            } else {
                vistos.values.sorted()
            }
        } catch (e: Exception) {
            Log.w(TAG, "barrerBle fallo: ${e.message}")
            listOf("ERROR: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Las dos trampas clasicas del barrido BLE en API 30.
     *
     * Sin permiso de ubicacion, o con la ubicacion del sistema apagada, el
     * barrido devuelve **lista vacia y ningun error**. Parece que no hay
     * nada en el aire cuando en realidad ni se miro. Se comprueba antes y se
     * dice, en vez de devolver un vacio que miente.
     */
    private fun revisarRequisitosBle(context: Context, adapter: BluetoothAdapter?): List<String> {
        val fallos = mutableListOf<String>()

        if (adapter == null) return listOf("ERROR: este aparato no tiene Bluetooth")
        if (!adapter.isEnabled) return listOf("ERROR: Bluetooth apagado (usa /bluetooth?on=1)")

        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            fallos += "ERROR: el radio declara NO tener Bluetooth LE"
        }

        // En API 30 el barrido BLE exige ubicacion fina concedida en ejecucion.
        if (Build.VERSION.SDK_INT <= 30) {
            val concedido = context.checkSelfPermission(
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!concedido) fallos += "ERROR: falta ACCESS_FINE_LOCATION concedido en ejecucion"
        }

        // Y ademas exige que la ubicacion del SISTEMA este encendida.
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val ubicacionOn = when {
            lm == null -> true
            Build.VERSION.SDK_INT >= 28 -> runCatching { lm.isLocationEnabled }.getOrDefault(true)
            else -> runCatching {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(true)
        }
        if (!ubicacionOn) {
            fallos += "ERROR: la ubicacion del sistema esta APAGADA; " +
                "el barrido BLE devolveria vacio sin avisar"
        }

        return fallos
    }

    private fun motivoFallo(codigo: Int): String = when (codigo) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "ya habia un barrido en curso"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "no se pudo registrar la app"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "error interno de la pila Bluetooth"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "la pila no soporta este barrido"
        else -> "desconocido"
    }

    /**
     * Todo lo que el anuncio trae, en crudo.
     *
     * El nombre y el RSSI orientan, pero lo que de verdad identifica al
     * aparato es el `manufacturer data` con su company id y los UUID de
     * servicio. Un BMS JBD/Xiaoxiang, por ejemplo, se delata por el servicio
     * ff00; un monitor tipo BM2 publica el voltaje en el propio anuncio.
     */
    @SuppressLint("MissingPermission")
    private fun describir(result: ScanResult): String {
        val d = result.device
        val nombre = runCatching { d.name }.getOrNull() ?: result.scanRecord?.deviceName ?: "(sin nombre)"
        val sb = StringBuilder()
        sb.append(d.address).append("  ").append(nombre)
        sb.append("  rssi=").append(result.rssi)
        sb.append("  tipo=").append(tipoDe(d.type))

        val rec = result.scanRecord
        if (rec == null) {
            sb.append("  (sin scanRecord)")
            return sb.toString()
        }

        rec.serviceUuids?.takeIf { it.isNotEmpty() }?.let { uuids ->
            sb.append("\n    servicios=").append(uuids.joinToString(",") { it.uuid.toString() })
        }

        val fab = rec.manufacturerSpecificData
        if (fab != null && fab.size() > 0) {
            for (i in 0 until fab.size()) {
                val companyId = fab.keyAt(i)
                sb.append("\n    fabricante id=0x")
                    .append(String.format("%04X", companyId))
                    .append(" datos=").append(hex(fab.valueAt(i)))
            }
        }

        rec.serviceData?.forEach { (uuid, datos) ->
            sb.append("\n    serviceData ").append(uuid.uuid).append("=").append(hex(datos))
        }

        // El anuncio entero, por si algo se escapa de los campos parseados.
        rec.bytes?.let { sb.append("\n    crudo=").append(hex(it)) }

        return sb.toString()
    }

    private fun tipoDe(t: Int): String = when (t) {
        1 -> "CLASICO"
        2 -> "BLE"
        3 -> "DOBLE"
        else -> "desconocido($t)"
    }

    // --- GATT ---------------------------------------------------------------

    /**
     * Se conecta por GATT y vuelca servicios y caracteristicas.
     *
     * Es el paso siguiente al barrido: el anuncio dice QUIEN es el aparato,
     * y esto dice COMO se le habla. Sirve igual para un BMS JBD que para un
     * ELM327 que resulte ser BLE en vez de SPP.
     */
    @SuppressLint("MissingPermission")
    fun volcarGatt(context: Context, adapter: BluetoothAdapter?, mac: String, segundos: Int): List<String> {
        adapter ?: return listOf("ERROR: no hay Bluetooth")
        if (!adapter.isEnabled) return listOf("ERROR: Bluetooth apagado (usa /bluetooth?on=1)")

        val device = runCatching { adapter.getRemoteDevice(mac) }.getOrNull()
            ?: return listOf("ERROR: MAC invalida: $mac")

        val salida = java.util.concurrent.CopyOnWriteArrayList<String>()
        val listo = CountDownLatch(1)
        var gatt: BluetoothGatt? = null

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                salida += "conexion status=$status estado=${estadoGatt(newState)}"
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!g.discoverServices()) {
                        salida += "ERROR: discoverServices() devolvio false"
                        listo.countDown()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    listo.countDown()
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    salida += "ERROR: onServicesDiscovered status=$status"
                    listo.countDown()
                    return
                }
                g.services.forEach { s ->
                    salida += "servicio ${s.uuid}"
                    s.characteristics.forEach { c ->
                        salida += "    caracteristica ${c.uuid}  [${propiedades(c)}]"
                    }
                }
                listo.countDown()
            }
        }

        return try {
            // TRANSPORT_LE explicito: sin esto la pila puede intentar BR/EDR
            // contra un aparato que solo habla LE y fallar con un status 133
            // que no dice nada.
            gatt = if (Build.VERSION.SDK_INT >= 23) {
                device.connectGatt(context, false, cb, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, cb)
            }
            if (gatt == null) return listOf("ERROR: connectGatt devolvio null")
            listo.await(segundos.toLong().coerceIn(3, 30), TimeUnit.SECONDS)
            if (salida.isEmpty()) listOf("Sin respuesta del GATT en ${segundos}s") else salida.toList()
        } catch (e: Exception) {
            listOf("ERROR: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
        }
    }

    private fun estadoGatt(s: Int): String = when (s) {
        BluetoothProfile.STATE_CONNECTED -> "CONECTADO"
        BluetoothProfile.STATE_CONNECTING -> "CONECTANDO"
        BluetoothProfile.STATE_DISCONNECTED -> "DESCONECTADO"
        BluetoothProfile.STATE_DISCONNECTING -> "DESCONECTANDO"
        else -> "desconocido($s)"
    }

    private fun propiedades(c: BluetoothGattCharacteristic): String {
        val p = c.properties
        val partes = mutableListOf<String>()
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) partes += "leer"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) partes += "escribir"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) partes += "escribir-sin-respuesta"
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) partes += "notificar"
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) partes += "indicar"
        return if (partes.isEmpty()) "sin propiedades" else partes.joinToString(",")
    }

    // --- USB ----------------------------------------------------------------

    /**
     * Enumera lo que hay colgado del USB, con sus interfaces y endpoints.
     *
     * Enumerar NO requiere permiso; solo ABRIR el aparato lo requiere. Por
     * eso esto contesta siempre, sin dialogos en pantalla — que en este radio
     * son un problema porque casi no se puede tocar.
     *
     * El VID y el PID son lo que identifica al receptor TPMS sin tener que
     * buscarle una etiqueta al aparato.
     */
    fun listarUsb(context: Context): List<String> {
        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return listOf("ERROR: este radio no expone UsbManager")

        val dispositivos = runCatching { um.deviceList }.getOrNull()
            ?: return listOf("ERROR: no se pudo leer la lista de USB")

        if (dispositivos.isEmpty()) {
            return listOf(
                "Sin dispositivos USB.",
                "Ojo: si el kernel del radio ya lo tomo con un driver propio " +
                    "(cdc_acm, ch341), puede no aparecer aqui y vivir como " +
                    "/dev/ttyUSB0, inaccesible sin root.",
            )
        }

        return dispositivos.values.map { describirUsb(um, it) }
    }

    private fun describirUsb(um: UsbManager, d: UsbDevice): String {
        val sb = StringBuilder()
        sb.append("VID=0x").append(String.format("%04X", d.vendorId))
        sb.append(" PID=0x").append(String.format("%04X", d.productId))
        sb.append("  ").append(nombreChip(d.vendorId, d.productId))
        sb.append("\n    ruta=").append(d.deviceName)
        sb.append("  permiso=").append(runCatching { um.hasPermission(d) }.getOrDefault(false))

        if (Build.VERSION.SDK_INT >= 21) {
            runCatching { d.manufacturerName }.getOrNull()?.let { sb.append("\n    fabricante=").append(it) }
            runCatching { d.productName }.getOrNull()?.let { sb.append("\n    producto=").append(it) }
        }
        // serialNumber lanza SecurityException sin permiso en API 29+.
        runCatching { d.serialNumber }.getOrNull()?.let { sb.append("\n    serie=").append(it) }

        sb.append("\n    clase=").append(claseUsb(d.deviceClass))

        for (i in 0 until d.interfaceCount) {
            val itf = d.getInterface(i)
            sb.append("\n    interfaz $i: clase=").append(claseUsb(itf.interfaceClass))
                .append(" subclase=").append(itf.interfaceSubclass)
                .append(" protocolo=").append(itf.interfaceProtocol)
            for (j in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(j)
                sb.append("\n        endpoint $j: tipo=").append(tipoEndpoint(ep.type))
                    .append(" dir=").append(if (ep.direction == UsbConstants.USB_DIR_IN) "ENTRADA" else "SALIDA")
                    .append(" maxPaquete=").append(ep.maxPacketSize)
            }
        }
        return sb.toString()
    }

    /** Los chips USB-serial mas comunes en dongles baratos, por VID/PID. */
    private fun nombreChip(vid: Int, pid: Int): String = when {
        vid == 0x1A86 && pid == 0x7523 -> "CH340/CH341 (USB-serial)"
        vid == 0x1A86 && pid == 0x5523 -> "CH341 (USB-serial)"
        vid == 0x1A86 -> "QinHeng (USB-serial probable)"
        vid == 0x10C4 && pid == 0xEA60 -> "CP2102 (USB-serial)"
        vid == 0x10C4 -> "Silicon Labs (USB-serial probable)"
        vid == 0x0403 -> "FTDI (USB-serial)"
        vid == 0x067B -> "Prolific PL2303 (USB-serial)"
        else -> "chip no reconocido — hay que buscar este VID/PID"
    }

    private fun claseUsb(c: Int): String = when (c) {
        UsbConstants.USB_CLASS_APP_SPEC -> "especifica-de-app"
        UsbConstants.USB_CLASS_AUDIO -> "audio"
        UsbConstants.USB_CLASS_CDC_DATA -> "CDC-datos"
        UsbConstants.USB_CLASS_COMM -> "comunicaciones(CDC)"
        UsbConstants.USB_CLASS_HID -> "HID"
        UsbConstants.USB_CLASS_HUB -> "hub"
        UsbConstants.USB_CLASS_MASS_STORAGE -> "almacenamiento"
        UsbConstants.USB_CLASS_VENDOR_SPEC -> "propietaria-del-fabricante"
        0 -> "definida-en-la-interfaz"
        else -> "clase($c)"
    }

    private fun tipoEndpoint(t: Int): String = when (t) {
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPCION"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCRONO"
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
        else -> "tipo($t)"
    }

    /**
     * Abre el USB-serial a [baudios] y vuelca lo que llegue, en hex y en
     * ASCII imprimible a la vez.
     *
     * Dos vistas del mismo dato a proposito: hay receptores TPMS que hablan
     * en texto y otros en binario con cabecera y checksum. Verlas juntas
     * ahorra un despliegue entero.
     *
     * Si no llega nada casi siempre es la velocidad equivocada, y el mensaje
     * lo dice y sugiere las otras en vez de dejar un vacio mudo.
     */
    fun volcarUsbSerial(context: Context, baudios: Int, segundos: Int): List<String> {
        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return listOf("ERROR: este radio no expone UsbManager")

        val device = runCatching { um.deviceList.values }.getOrNull()
            ?.firstOrNull { esSerial(it.vendorId) }
            ?: return listOf("ERROR: no encuentro ningun USB-serial conectado")

        val ch = Ch340(um, device)
        val salida = mutableListOf<String>()
        salida += "aparato VID=0x" + String.format("%04X", device.vendorId) +
            " PID=0x" + String.format("%04X", device.productId) + " a " + baudios + " baudios"

        return try {
            val traza = ch.abrir(baudios)
            salida += traza
            if (traza.any { it.startsWith("ERROR") }) return salida

            val datos = ch.leerCrudo(segundos * 1000L)
            if (datos.isEmpty()) {
                salida += "Nada llego en " + segundos + "s a " + baudios + " baudios."
                salida += "Lo mas probable es que sea otra velocidad. Prueba: " +
                    Ch340.VELOCIDADES_TIPICAS.filter { it != baudios }.joinToString(", ")
                return salida
            }

            salida += "--- " + datos.size + " bytes ---"
            salida += "HEX:   " + datos.joinToString("") { String.format("%02X", it) }
            salida += "ASCII: " + datos.map { b ->
                val c = b.toInt() and 0xFF
                if (c in 32..126) c.toChar() else 46.toChar()
            }.joinToString("")
            salida
        } catch (e: Exception) {
            salida += "ERROR: " + e.javaClass.simpleName + ": " + e.message
            salida
        } finally {
            runCatching { ch.cerrar() }
        }
    }

    /** Los VID de los USB-serial baratos mas comunes. */
    private fun esSerial(vid: Int): Boolean =
        vid == 0x1A86 || vid == 0x10C4 || vid == 0x0403 || vid == 0x067B

    // --- Bluetooth: encender ------------------------------------------------

    /**
     * Enciende la radio Bluetooth sin que nadie toque la pantalla.
     *
     * Hace falta por dos motivos reales, los dos vistos en vivo: tras
     * reiniciar el carro el Bluetooth queda apagado, y la pila de este radio
     * se apaga sola despues de varios emparejamientos fallidos. Sin esto hay
     * que ir fisicamente al carro, que es justo lo que el puente evita.
     *
     * `enable()` esta obsoleto desde API 33 y ahi ya no funciona, pero este
     * radio es API 30 y ahi sigue siendo valido.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun encenderBluetooth(adapter: BluetoothAdapter?, encender: Boolean): String {
        adapter ?: return "ERROR: este aparato no tiene Bluetooth"
        if (Build.VERSION.SDK_INT >= 33) {
            return "ERROR: desde Android 13 una app no puede encender el Bluetooth sola"
        }
        return runCatching {
            if (encender) {
                if (adapter.isEnabled) "ya estaba encendido"
                else if (adapter.enable()) "encendiendo (tarda unos segundos)"
                else "enable() devolvio false"
            } else {
                if (!adapter.isEnabled) "ya estaba apagado"
                else if (adapter.disable()) "apagando" else "disable() devolvio false"
            }
        }.getOrElse { "ERROR: ${it.javaClass.simpleName}: ${it.message}" }
    }

    // --- Utilidades ---------------------------------------------------------

    private fun hex(b: ByteArray): String =
        b.joinToString("") { String.format("%02X", it) }
}
