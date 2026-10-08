package com.nonosky.carsoc.nevera

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import com.nonosky.carsoc.config.Emparejados
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * El enlace con la refrigeradora Alpicool.
 *
 * Conecta, pregunta, y **suelta**. No se queda con el enlace abierto, y eso
 * es deliberado: mientras alguien esta conectado, la nevera **deja de
 * anunciarse y rechaza a los demas**, o sea que el tablero le quitaria la
 * nevera a la app del movil para siempre. Preguntando cada medio minuto y
 * soltando, los dos conviven. Una nevera cambia de temperatura despacio; no
 * hay nada que ganar sondeandola mas seguido.
 *
 * No hace falta emparejar: la fuente original lo dice explicitamente —"there
 * is no authentication", "pairing to the device is not required"—. El
 * comando de vinculo (0x00) es una cortesia para que el dueño confirme
 * pulsando un boton, y la nevera obedece igual sin el.
 */
class LectorNevera(private val context: Context) {

    /** El aparato asignado al papel de nevera. Vacio = no hay. */
    private val mac: String
        get() = Emparejados.mac(context, Emparejados.Papel.Nevera)

    @Volatile var estado: Alpicool.Estado? = null
        private set

    /** Cuando se leyo lo que hay en [estado]. 0 = nunca. */
    @Volatile var leidoMs: Long = 0L
        private set

    @Volatile var detalle: String? = null
        private set

    var alCambiar: (() -> Unit)? = null

    private val traza = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /**
     * Comandos pendientes de mandar en la proxima conexion.
     *
     * No se abre un enlace por cada toque: la nevera atiende UN cliente y
     * abrir y cerrar por cada boton la deja inservible para el movil. Se
     * encolan y viajan con la siguiente consulta, que ademas trae de vuelta
     * el estado ya cambiado — asi el boton se confirma con la lectura real
     * de la nevera y no con lo que el tablero creia haber mandado.
     */
    private val pendientes = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()

    /** Un toque adelanta el siguiente ciclo: 30 s de espera se notarian. */
    @Volatile private var correPrisa = false

    /** Apaga o enciende. Devuelve false si aun no sabemos como esta. */
    fun encender(on: Boolean): Boolean = mandar { Alpicool.ajustes(it, encendida = on) }

    /** Max (false) o Eco (true). */
    fun modoEco(eco: Boolean): Boolean = mandar { Alpicool.ajustes(it, modoEco = eco) }

    /**
     * Mueve la consigna. El rango lo dicta la NEVERA, no una constante: si
     * pide -20..20, ahi se queda. Escribir fuera de rango lo rechaza ella.
     */
    fun moverConsigna(delta: Int): Boolean = mandar { e ->
        val nueva = (e.consigna + delta).coerceIn(e.minima, e.maxima)
        if (nueva == e.consigna) null else Alpicool.fijarConsigna(nueva)
    }

    private fun mandar(construir: (Alpicool.Estado) -> ByteArray?): Boolean {
        val e = estado ?: return false
        val t = construir(e) ?: return false
        pendientes.offer(t)
        correPrisa = true
        hilo?.interrupt()   // corta la siesta: que salga ya
        anotar("encolado: " + t.joinToString(" ") { "%02x".format(it) })
        return true
    }
    @Volatile private var vivo = false
    private var hilo: Thread? = null

    fun vivoAhora(ahoraMs: Long): Boolean =
        leidoMs > 0L && (ahoraMs - leidoMs) < SIN_VERSE_MS && estado != null

    fun arrancar() {
        if (vivo) return
        vivo = true
        hilo = thread(name = "nevera", isDaemon = true) {
            while (vivo) {
                if (com.nonosky.carsoc.Termometro.permiteBateria()) {
                    // Mismo turno que los bancos: uno a la vez en toda la
                    // radio. Sin esto, los tres lectores se pisan y el que
                    // pierde deja de leer sin que nadie se entere.
                    val antes = leidoMs
                    val hubo = com.nonosky.carsoc.bateria.TurnoBle
                        .conLaRadio("nevera") {
                            runCatching { unaLectura() }
                                .onFailure {
                                    anotar("fallo: ${it.javaClass.simpleName} ${it.message}")
                                }
                            true
                        }
                    if (hubo == null) anotar("sin turno de radio")
                    // Solo cuenta como fallo una vuelta que SI intento leer.
                    else fallosSeguidos = if (leidoMs != antes) 0 else fallosSeguidos + 1
                }
                val espera = if (correPrisa) 1_000L else esperaTras(fallosSeguidos)
                correPrisa = false
                dormir(espera)
            }
        }
    }

    fun detener() {
        vivo = false
        hilo?.interrupt()
        hilo = null
    }

    /**
     * Intenta YA, sin esperar los 30 s del ciclo. Lo usa el menu de
     * emparejamiento al cambiar de nevera: que el dueño vea en segundos si
     * conecta, en vez de quedarse mirando una tarjeta vacia sin saber.
     */
    fun despertar() {
        macVista = null
        fallosSeguidos = 0
        correPrisa = true
        hilo?.interrupt()
    }

    /**
     * Vueltas seguidas sin lectura. Con la nevera apagada, lejos o tomada por
     * el movil, cada vuelta era un barrido de 8 s y un intento de conexion de
     * 12 s, cada medio minuto, para siempre. Ver [esperaTras].
     */
    @Volatile private var fallosSeguidos = 0

    /**
     * La MAC que ya se vio anunciarse en este arranque. Mientras no cambie,
     * no hace falta volver a barrer antes de conectar.
     */
    @Volatile private var macVista: String? = null

    fun diagnostico(): List<String> {
        val e = estado
        val edad = if (leidoMs > 0) "${(System.currentTimeMillis() - leidoMs) / 1000}s" else "nunca"
        return listOf(
            "mac=$mac  leido=$edad  ${detalle ?: ""}",
            if (e == null) "sin estado" else
                "encendida=${e.encendida}  actual=${e.actual}  consigna=${e.consigna}  " +
                    "histeresis=${e.histeresis}  unidad=${if (e.unidadCelsius) "C" else "F"}  " +
                    "voltaje=${e.voltaje ?: "--"}  compresor(deducido)=${e.compresorEnMarcha()}",
        ) + traza.toList().takeLast(12)
    }

    private fun dormir(ms: Long) { runCatching { Thread.sleep(ms) } }

    private fun anotar(t: String) {
        traza += t
        while (traza.size > 40) traza.poll()
        Log.i(TAG, t)
    }

    private fun adaptador(): BluetoothAdapter? = runCatching {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }.getOrNull()

    /** Una vuelta completa: conectar, suscribir, preguntar, cerrar. */
    private fun unaLectura() {
        val adapter = adaptador() ?: run { detalle = "sin BluetoothAdapter"; return }
        val mac = this.mac
        if (mac.isBlank()) { detalle = "sin nevera asignada"; return }
        val dev: BluetoothDevice = runCatching { adapter.getRemoteDevice(mac) }.getOrNull()
            ?: run { detalle = "MAC invalida"; return }

        // VERLA ANTES DE LLAMARLA.
        //
        // La Alpicool anuncia una direccion ALEATORIA (`ED:67:...`: los dos
        // bits altos a 1 son "aleatoria estatica"). Android 9 no tiene forma
        // de decirle a connectGatt el tipo de direccion: si el aparato no ha
        // salido en un barrido desde que arranco la radio, la pila lo llama
        // como si fuera PUBLICA y la conexion no llega nunca — se queda en
        // "no conecto" para siempre, que es justo el sintoma. Los BMS si
        // conectaban porque sus direcciones son publicas.
        //
        // Un barrido corto filtrado por esta MAC le enseña a la pila el tipo.
        // Y de paso distingue "no esta anunciandose" (apagada, lejos, o la
        // tiene tomada el movil) de "se anuncia pero no acepta".
        if (macVista != mac) {
            val vista = verAnunciarse(adapter, mac)
            anotar(if (vista) "vista anunciandose: $mac" else "NO se anuncia: $mac")
            if (vista) { macVista = mac; detalle = null }
            else detalle = "no se anuncia (¿apagada, lejos, o la tiene el movil?)"
            // Se intenta conectar IGUAL aunque no se viera: sin permiso de
            // ubicacion el barrido devuelve cero en silencio, y no barrer no
            // puede ser motivo para dejar de intentar lo que antes se hacia.
        }

        val conectado = CountDownLatch(1)
        val descubierto = CountDownLatch(1)
        val suscrito = CountDownLatch(1)
        val recibidas = LinkedBlockingQueue<ByteArray>()
        val muerto = AtomicBoolean(false)
        var gatt: BluetoothGatt? = null

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, nuevo: Int) {
                runCatching {
                    if (nuevo == BluetoothGatt.STATE_CONNECTED) {
                        // LIMPIAR LA CACHE ANTES DE DESCUBRIR. Android guarda
                        // la tabla de handles por aparato entre reinicios; si
                        // esta rancia, el CCCD se escribe contra un handle que
                        // ya no es, contesta status=0 tan contento, y las
                        // notificaciones no llegan NUNCA. Es la leccion que
                        // costo la mitad de una sesion con el BMS.
                        runCatching { g.javaClass.getMethod("refresh").invoke(g) }
                        if (!g.discoverServices()) descubierto.countDown()
                        conectado.countDown()
                    } else {
                        muerto.set(true)
                        conectado.countDown(); descubierto.countDown(); suscrito.countDown()
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                runCatching { descubierto.countDown() }
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int,
            ) {
                runCatching { if (d.uuid == CCCD) suscrito.countDown() }
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt, c: BluetoothGattCharacteristic,
            ) {
                runCatching { c.value?.let { recibidas.offer(it.copyOf()) } }
            }
        }

        try {
            // TRANSPORT_LE explicito. Sin el, la pila puede intentar la
            // conexion por Bluetooth CLASICO contra un aparato que solo habla
            // BLE, y falla. Los BMS, que si conectan, ya lo pasaban.
            gatt = if (Build.VERSION.SDK_INT >= 23) {
                dev.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
            } else {
                dev.connectGatt(context, false, cb)
            }
            if (!conectado.await(MS_CONECTAR, TimeUnit.MILLISECONDS) || muerto.get()) {
                // La proxima vuelta barre otra vez: quiza cambio de direccion
                // o la pila olvido el tipo.
                macVista = null
                if (detalle == null || !detalle!!.startsWith("no se anuncia")) detalle = "no conecto"
                anotar("no conecto con $mac"); return
            }
            if (!descubierto.await(MS_DESCUBRIR, TimeUnit.MILLISECONDS)) {
                detalle = "no descubrio servicios"; return
            }

            // El servicio puede anunciarse como 1234 o como fff0 segun la
            // unidad; las caracteristicas son las mismas en los dos casos.
            val svc = gatt.getService(SERVICIO) ?: gatt.getService(SERVICIO_ALT)
                ?: run { detalle = "sin el servicio 1234 ni fff0"; return }
            val escribir = svc.getCharacteristic(CAR_ESCRIBIR)
                ?: run { detalle = "sin la caracteristica 1235"; return }
            val notificar = svc.getCharacteristic(CAR_NOTIFICAR)
                ?: run { detalle = "sin la caracteristica 1236"; return }

            gatt.setCharacteristicNotification(notificar, true)
            val cccd = notificar.getDescriptor(CCCD)
                ?: run { detalle = "sin CCCD en 1236"; return }
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)
            if (!suscrito.await(MS_SUSCRIBIR, TimeUnit.MILLISECONDS)) {
                detalle = "no acepto las notificaciones"; return
            }

            // Escritura sin acuse si se puede: es lo que hacen las
            // implementaciones que funcionan.
            escribir.writeType =
                if (escribir.properties and
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            // Primero lo que el dueño pidio, y despues la consulta: la
            // respuesta traera el estado YA cambiado, asi que el boton se
            // confirma contra la nevera y no contra lo que creimos mandar.
            while (true) {
                val cmd = pendientes.poll() ?: break
                escribir.value = cmd
                gatt.writeCharacteristic(escribir)
                anotar("mandado: " + cmd.joinToString(" ") { "%02x".format(it) })
                runCatching { Thread.sleep(220) }
            }
            escribir.value = Alpicool.consulta()
            gatt.writeCharacteristic(escribir)

            // Acumular hasta tener una trama de estado entera. Llegan
            // partidas y a veces pegadas; el troceo lo hace Alpicool.partir.
            val acc = ByteArray(512)
            var usado = 0
            val hasta = System.currentTimeMillis() + MS_RESPUESTA
            while (System.currentTimeMillis() < hasta) {
                val trozo = recibidas.poll(400, TimeUnit.MILLISECONDS) ?: continue
                if (usado + trozo.size > acc.size) usado = 0   // reinicio defensivo
                trozo.copyInto(acc, usado); usado += trozo.size
                val (tramas, consumidos) = Alpicool.partir(acc, usado)
                if (consumidos > 0) {
                    acc.copyInto(acc, 0, consumidos, usado); usado -= consumidos
                }
                val e = tramas.firstNotNullOfOrNull { Alpicool.decodificar(it) }
                if (e != null) {
                    estado = e
                    leidoMs = System.currentTimeMillis()
                    detalle = null
                    anotar("estado: actual=${e.actual} consigna=${e.consigna} " +
                        "encendida=${e.encendida} v=${e.voltaje}")
                    runCatching { alCambiar?.invoke() }
                    return
                }
            }
            detalle = "conecto pero no contesto una trama valida"
        } finally {
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
        }
    }

    /**
     * Barre hasta [MS_BARRIDO] buscando SOLO esta MAC. Vuelve en cuanto la ve.
     * Devuelve false si no la vio (o si no se pudo barrer).
     */
    private fun verAnunciarse(adapter: BluetoothAdapter, mac: String): Boolean {
        val scanner = runCatching { adapter.bluetoothLeScanner }.getOrNull() ?: return false
        val vista = CountDownLatch(1)
        val cb = object : ScanCallback() {
            override fun onScanResult(tipo: Int, r: ScanResult) {
                runCatching {
                    if (r.device?.address.equals(mac, ignoreCase = true)) vista.countDown()
                }
            }
        }
        return runCatching {
            val filtro = ScanFilter.Builder().setDeviceAddress(mac.uppercase()).build()
            val ajustes = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build()
            scanner.startScan(listOf(filtro), ajustes, cb)
            try {
                vista.await(MS_BARRIDO, TimeUnit.MILLISECONDS)
            } finally {
                runCatching { scanner.stopScan(cb) }
            }
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "LectorNevera"

        /**
         * Cuanto esperar segun las vueltas seguidas sin lectura: 30 s, 30, 60,
         * 120, 240 y 300 como techo. Vuelve a 30 s con la primera lectura buena
         * o al tocar la nevera en el menu ([despertar]). La tarjeta ya sale
         * en "--" a los 150 s sin lectura: esperar mas no esconde nada.
         */
        internal fun esperaTras(fallos: Int): Long =
            if (fallos <= 1) PERIODO_MS
            else minOf(PERIODO_MS shl (fallos - 1).coerceAtMost(8), MAX_ESPERA_MS)

        const val MAX_ESPERA_MS = 300_000L

        /** Cuanto se barre buscando la nevera antes de llamarla. */
        const val MS_BARRIDO = 8_000L

        /**
         * La MAC ya NO va escrita aqui: la elige el dueño en el menu de
         * emparejamiento. Se midio `ED:67:39:96:50:9B  A1-4XXXXXXXXXXX
         * uuids=00001234` en el carro y eso es el valor de fabrica del
         * perfil, pero cambiar de nevera no debe obligar a recompilar.
         */

        val SERVICIO: UUID = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb")
        /** Algunas unidades anuncian fff0; las caracteristicas no cambian. */
        val SERVICIO_ALT: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val CAR_ESCRIBIR: UUID = UUID.fromString("00001235-0000-1000-8000-00805f9b34fb")
        val CAR_NOTIFICAR: UUID = UUID.fromString("00001236-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * Cada 30 s, como la integracion de Home Assistant. La app de fabrica
         * pregunta cada 2 s, que para un tablero es gastar radio sin ganar
         * nada: una nevera no cambia de temperatura en dos segundos.
         */
        const val PERIODO_MS = 30_000L
        const val SIN_VERSE_MS = 150_000L

        const val MS_CONECTAR = 12_000L
        const val MS_DESCUBRIR = 12_000L
        const val MS_SUSCRIBIR = 5_000L
        const val MS_RESPUESTA = 6_000L
    }
}
