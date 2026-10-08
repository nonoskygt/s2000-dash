package com.nonosky.carsoc.bateria

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lee el BMS por el Bluetooth del PROPIO radio, sin dongle.
 *
 * ## Por que no se reusa [LectorBmsGatt]
 *
 * [CanalGatt] es un tubo de **PDUs ATT crudas**, y con razon: sobre el dongle
 * hay que armarlas a mano porque ahi no hay pila de Android que ayude. Pero
 * la API `BluetoothGatt` trabaja un piso mas arriba —caracteristicas, no
 * PDUs— y no expone ATT. Negociar MTU y descubrir servicios los hace ella por
 * dentro, asi que fingir esas PDUs para colarlas por [CanalGatt] seria
 * inventar un protocolo por debajo de otro que ya lo resolvio.
 *
 * Lo que SI se reusa es lo que de verdad cuesta: [BmsJbd] entero —el formato
 * de trama, los checksums y la decodificacion— y [EnsambladorBms], que ya
 * sabe juntar una respuesta partida en varias notificaciones. Eso es donde
 * vivia el conocimiento ganado a golpes; la capa de transporte es la parte
 * barata.
 *
 * ## El BMS JBD por GATT
 *
 * Servicio `ff00`, y dentro dos caracteristicas:
 *
 *   - `ff02` — se ESCRIBE la peticion (sin respuesta)
 *   - `ff01` — NOTIFICA la respuesta, casi siempre partida en dos
 *
 * Se comprobo en vivo en este radio antes de escribir nada: el volcado GATT
 * por la radio interna listo `ff00` con `ff01 [leer,notificar]` y
 * `ff02 [leer,escribir-sin-respuesta]`.
 */
@SuppressLint("MissingPermission")
object LectorBmsAndroid {

    private val SERVICIO = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
    private val NOTIFICA = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    private val ESCRIBE = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")

    /** El descriptor estandar que enciende las notificaciones. */
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private const val MS_CONECTAR = 12_000L
    private const val MS_DESCUBRIR = 12_000L
    private const val MS_RESPUESTA = 4_000L

    /**
     * Conecta, pregunta y devuelve lo leido. Nunca lanza.
     *
     * Corre en el hilo del vigilante y las respuestas llegan en un hilo de
     * binder, asi que la sincronizacion va con cerrojos y una cola: es la
     * misma leccion que dejo el canal del dongle —entre que el lector procesa
     * una notificacion y vuelve a esperar hay una ventana ciega, y las dos
     * mitades de la respuesta del registro 0x03 salen con microsegundos de
     * diferencia—. Con una cola permanente no hay ventana.
     */
    fun leer(
        context: Context,
        adaptador: BluetoothAdapter?,
        mac: String,
        pedirCeldas: Boolean = true,
        /**
         * Sondas de diagnostico. APAGADAS por omision, y con motivo: una
         * lectura que expira deja la operacion en vuelo y la cola de GATT
         * rechaza TODO lo que venga detras — o sea que la sonda tumbaba la
         * peticion que venia a ayudar a depurar. Se enciende a mano cuando
         * hay algo que investigar.
         */
        sondas: Boolean = false,
        /** Pedir MTU grande. Apagado: en este radio deja el ATT inservible. */
        pedirMtu: Boolean = false,
    ): LectorBmsGatt.Lectura {
        val traza = mutableListOf<String>()
        val problemas = mutableListOf<String>()

        val adapter = adaptador ?: return LectorBmsGatt.Lectura(
            problemas = listOf("este radio no expone BluetoothAdapter"),
        )
        val dev: BluetoothDevice = runCatching { adapter.getRemoteDevice(mac) }.getOrNull()
            ?: return LectorBmsGatt.Lectura(problemas = listOf("MAC invalida: $mac"))

        val recibidas = LinkedBlockingQueue<ByteArray>()
        val conectado = CountDownLatch(1)
        val descubierto = CountDownLatch(1)
        val listoNotificar = CountDownLatch(1)
        val mtuListo = CountDownLatch(1)
        val escrito = LinkedBlockingQueue<Int>()
        val leido = LinkedBlockingQueue<Pair<Int, ByteArray>>()

        val muerto = AtomicBoolean(false)
        var gatt: BluetoothGatt? = null

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, nuevo: Int) {
                runCatching {
                    traza += "conexion status=$status estado=$nuevo"
                    if (nuevo == BluetoothGatt.STATE_CONNECTED) {
                        // El descubrimiento se lanza DESDE AQUI, en el hilo
                        // del callback. Se probo moverlo al hilo principal
                        // tras un respiro y esta pila no contesto nunca:
                        // `onServicesDiscovered` no llegaba en 12 s. El
                        // volcado GATT que si funciona lo hace exactamente
                        // asi, y no habia razon para separarse de el.
                        // LIMPIAR LA CACHE ANTES DE DESCUBRIR.
                        //
                        // Android guarda la tabla de servicios y handles por
                        // aparato, entre reinicios de la app incluso. Si esa
                        // copia esta rancia, `writeDescriptor` escribe contra
                        // un handle que ya no es el del CCCD, contesta
                        // status=0 tan contento, y las notificaciones no
                        // llegan nunca. Es exactamente el sintoma que se
                        // midio: escrituras con acuse correcto y cero
                        // notificaciones, mientras el mismo BMS leido por el
                        // dongle daba 13,09 V sin despeinarse.
                        //
                        // `refresh()` es API oculta y no hay alternativa
                        // publica. Si un dia desaparece, se sigue sin ella.
                        runCatching {
                            val ok = g.javaClass.getMethod("refresh").invoke(g) as? Boolean
                            traza += "cache GATT limpiada: $ok"
                        }.onFailure { traza += "no se pudo limpiar la cache: ${it.message}" }

                        if (!g.discoverServices()) {
                            traza += "discoverServices devolvio false"
                            descubierto.countDown()
                        }
                        conectado.countDown()
                    } else {
                        muerto.set(true)
                        // Desbloquear a quien espere: si el enlace se cae, que
                        // el vigilante se entere ya y no al agotar el plazo.
                        conectado.countDown()
                        descubierto.countDown()
                        listoNotificar.countDown()
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                runCatching {
                    traza += "descubrimiento status=$status"
                    descubierto.countDown()
                }
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int,
            ) {
                runCatching {
                    if (d.uuid == CCCD) {
                        traza += "notificaciones activadas status=$status"
                        listoNotificar.countDown()
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                runCatching {
                    traza += "MTU acordado=$mtu status=$status"
                    mtuListo.countDown()
                }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int,
            ) {
                runCatching { leido.offer(status to (c.value ?: ByteArray(0))) }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicWrite(
                g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int,
            ) {
                runCatching { escrito.offer(status) }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                g: BluetoothGatt, c: BluetoothGattCharacteristic,
            ) {
                // Envuelto entero: una excepcion que escapa de un hilo en
                // Android mata el proceso, y esto corre en un hilo de binder.
                runCatching {
                    val v = c.value ?: return@runCatching
                    // Se apunta TODA notificacion, venga por donde venga. Si
                    // este BMS contestara por otra caracteristica, filtrar por
                    // ff01 lo haria invisible y el sintoma seria identico a
                    // que no contesta.
                    traza += "notifica ${c.uuid}: ${v.size} bytes"
                    if (c.uuid == NOTIFICA) recibidas.offer(v.copyOf())
                }
            }
        }

        try {
            gatt = if (Build.VERSION.SDK_INT >= 23) {
                dev.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
            } else {
                dev.connectGatt(context, false, cb)
            }
            if (gatt == null) {
                return LectorBmsGatt.Lectura(
                    problemas = listOf("connectGatt devolvio null"), traza = traza,
                )
            }

            if (!conectado.await(MS_CONECTAR, TimeUnit.MILLISECONDS) || muerto.get()) {
                return LectorBmsGatt.Lectura(
                    problemas = listOf("no conecto con $mac"), traza = traza,
                )
            }

            // De aqui en adelante, UNA OPERACION CADA VEZ. El fallo que
            // costo esta version fue pedir el MTU nada mas conectar, encima
            // del descubrimiento que ya iba en vuelo: se pisaron, el CCCD
            // nunca se escribio, y el sintoma fue "el BMS no contesta al
            // 0x03" — que apunta al BMS y no a la secuencia.
            if (!descubierto.await(MS_DESCUBRIR, TimeUnit.MILLISECONDS) || muerto.get()) {
                return LectorBmsGatt.Lectura(
                    problemas = listOf("no se descubrieron los servicios"), traza = traza,
                )
            }

            val servicio = gatt.getService(SERVICIO)
                ?: return LectorBmsGatt.Lectura(
                    problemas = listOf(
                        "este aparato no publica el servicio ff00; " +
                            "no parece un BMS JBD",
                    ),
                    traza = traza,
                )
            val notifica = servicio.getCharacteristic(NOTIFICA)
            val escribe = servicio.getCharacteristic(ESCRIBE)
            if (notifica == null || escribe == null) {
                return LectorBmsGatt.Lectura(
                    problemas = listOf("faltan ff01 o ff02 dentro de ff00"), traza = traza,
                )
            }

            // SIN peticion de MTU, y esto no es un descuido.
            //
            // El descubrimiento de servicios funciona, y eso exige mucho
            // trafico ATT ENTRANTE — o sea que la recepcion no esta rota. Lo
            // que fallaba venia despues: ni notificaciones ni lecturas. Entre
            // medias solo habia una operacion, el intercambio de MTU, que en
            // este controlador deja el canal ATT inservible aunque conteste
            // status=0. El ensamblador ya sabe juntar respuestas partidas, asi
            // que el MTU por omision no cuesta nada.
            if (pedirMtu) {
                runCatching { gatt.requestMtu(64) }
                mtuListo.await(3_000, TimeUnit.MILLISECONDS)
            }

            if (!activarNotificaciones(gatt, notifica, traza, problemas)) {
                return LectorBmsGatt.Lectura(problemas = problemas, traza = traza)
            }
            if (!listoNotificar.await(5_000, TimeUnit.MILLISECONDS)) {
                traza += "el CCCD no confirmo; se pregunta igual"
            }
            // Respiro. Varios JBD ignoran la primera peticion si llega pegada
            // a la activacion de notificaciones.
            runCatching { Thread.sleep(400) }

            // SONDA: leer ff01 directamente, y de control el nombre del
            // aparato (2a00, del servicio generico 1800).
            //
            // Es la prueba que separa dos causas que dan el mismo sintoma. Si
            // ff01 se deja leer, el BMS esta despierto y atendiendo, y lo roto
            // es la entrega de notificaciones. Si no, el BMS no esta contestando
            // a nadie y no hay codigo que lo arregle. La lectura de control dice
            // si el camino de lectura funciona siquiera.
            //
            // El CONTROL va primero, y esto importa: una lectura que no
            // completa deja la operacion en vuelo y la cola de GATT rechaza
            // todo lo que venga detras. Sondeando ff01 primero, su atasco
            // hacia fallar al control y a las peticiones, y el resultado no
            // distinguia nada. Primero se pregunta algo que TIENE que
            // contestar; solo si eso contesta tiene sentido probar ff01.
            if (sondas) sondearTodo(gatt, notifica, leido, traza)

            val ensamblador = EnsambladorBms()
            val sueltas = mutableListOf<BmsJbd.Respuesta>()

            val basico = pedir(
                gatt, escribe, recibidas, escrito, ensamblador, sueltas,
                BmsJbd.REG_BASICO, traza, problemas,
            ) as? BmsJbd.EstadoBasico

            val celdas = if (!pedirCeldas) null else pedir(
                gatt, escribe, recibidas, escrito, ensamblador, sueltas,
                BmsJbd.REG_CELDAS, traza, problemas,
            ) as? BmsJbd.VoltajesCelda

            traza += "tramas buenas=${ensamblador.tramasBuenas} " +
                "checksum malo=${ensamblador.tramasChecksumMalo} " +
                "bytes tirados=${ensamblador.bytesDescartados}"

            return LectorBmsGatt.Lectura(
                basico = basico,
                celdas = celdas,
                otras = sueltas.filter {
                    it !is BmsJbd.EstadoBasico && it !is BmsJbd.VoltajesCelda
                },
                problemas = problemas,
                traza = traza,
            )
        } catch (e: Exception) {
            return LectorBmsGatt.Lectura(
                problemas = problemas + "${e.javaClass.simpleName}: ${e.message}",
                traza = traza,
            )
        } finally {
            // SIEMPRE. Un GATT sin cerrar deja el enlace LE tomado y el
            // siguiente intento recibe 133 sin explicacion — el error mas
            // inutil de toda la API.
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
        }
    }

    /**
     * Las dos sondas, en el orden que si distingue algo.
     *
     * El CONTROL va primero: `2a00` es el nombre del aparato en el servicio
     * generico, la lectura mas basica de BLE y la que TODO periferico
     * contesta. Si eso no contesta, el aparato esta mudo a nivel ATT y
     * probar `ff01` no aporta nada — solo atasca la cola.
     */
    private fun sondearTodo(
        gatt: BluetoothGatt,
        notifica: BluetoothGattCharacteristic,
        leido: LinkedBlockingQueue<Pair<Int, ByteArray>>,
        traza: MutableList<String>,
    ) {
        val control = runCatching {
            gatt.getService(UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"))
                ?.getCharacteristic(UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"))
        }.getOrNull()
        val contesta = if (control == null) {
            traza += "sonda de control: el aparato no publica 2a00"
            true
        } else {
            sondear(gatt, control, leido, "2a00 (nombre)", traza)
        }
        if (contesta) sondear(gatt, notifica, leido, "ff01", traza)
        else traza += "el aparato no contesta ni su nombre: esta mudo a nivel ATT"
    }

    /** Lee una caracteristica y apunta lo que salga. Solo diagnostico. */
    @Suppress("DEPRECATION")
    private fun sondear(
        gatt: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        leido: LinkedBlockingQueue<Pair<Int, ByteArray>>,
        nombre: String,
        traza: MutableList<String>,
    ): Boolean {
        leido.clear()
        if (!gatt.readCharacteristic(c)) {
            traza += "sonda $nombre: readCharacteristic devolvio false"
            return false
        }
        val r = leido.poll(3_000, TimeUnit.MILLISECONDS)
        if (r == null) {
            traza += "sonda $nombre: sin respuesta en 3 s"
            return false
        }
        traza += "sonda $nombre: status=${r.first} ${r.second.size} bytes " +
            r.second.joinToString("") { "%02X".format(it) }
        return true
    }

    @Suppress("DEPRECATION")
    private fun activarNotificaciones(
        gatt: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        traza: MutableList<String>,
        problemas: MutableList<String>,
    ): Boolean {
        if (!gatt.setCharacteristicNotification(c, true)) {
            problemas += "setCharacteristicNotification devolvio false"
            return false
        }
        val cccd = c.getDescriptor(CCCD)
        if (cccd == null) {
            // Algunos clones no publican el CCCD y notifican igual. No es
            // motivo para rendirse: se avisa y se sigue.
            traza += "sin CCCD; se intenta igual"
            return true
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!gatt.writeDescriptor(cccd)) {
            problemas += "no se pudo escribir el CCCD"
            return false
        }
        return true
    }

    /**
     * Escribe una peticion y espera su respuesta.
     *
     * Se descarta lo que quede en la cola ANTES de preguntar: si el BMS venia
     * notificando por su cuenta, esos bytes contestarian a la pregunta
     * anterior y se tomaria una respuesta vieja por nueva.
     */
    @Suppress("DEPRECATION")
    private fun pedir(
        gatt: BluetoothGatt,
        escribe: BluetoothGattCharacteristic,
        recibidas: LinkedBlockingQueue<ByteArray>,
        escrito: LinkedBlockingQueue<Int>,
        ensamblador: EnsambladorBms,
        sueltas: MutableList<BmsJbd.Respuesta>,
        registro: Int,
        traza: MutableList<String>,
        problemas: MutableList<String>,
    ): BmsJbd.Respuesta? {
        recibidas.clear()
        escrito.clear()
        ensamblador.reiniciar()

        // El tipo de escritura lo dicta la caracteristica, no una suposicion:
        // este ff02 se anuncia como "escribir-sin-respuesta", pero un clon que
        // solo admita escritura con respuesta rechazaria la nuestra en
        // silencio y el sintoma volveria a ser "el BMS no contesta".
        val sinRespuesta = (escribe.properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        escribe.writeType = if (sinRespuesta) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val peticion = BmsJbd.peticion(registro)
        escribe.value = peticion
        val salio = gatt.writeCharacteristic(escribe)
        traza += "peticion 0x%02X (%s) tipo=%s -> writeCharacteristic=%s".format(
            registro,
            peticion.joinToString("") { "%02X".format(it) },
            if (sinRespuesta) "sin-respuesta" else "con-respuesta",
            salio,
        )
        if (!salio) {
            problemas += "no se pudo escribir la peticion 0x%02X".format(registro)
            return null
        }
        if (!sinRespuesta) {
            // Con respuesta hay que esperar el eco antes de seguir, o la
            // siguiente operacion se solapa con esta.
            escrito.poll(2_000, TimeUnit.MILLISECONDS)
        }

        val hasta = System.currentTimeMillis() + MS_RESPUESTA
        while (System.currentTimeMillis() < hasta) {
            val trozo = recibidas.poll(300, TimeUnit.MILLISECONDS) ?: continue
            val salidas = ensamblador.alimentar(trozo)
            for (r in salidas) {
                sueltas += r
                if (r.registro == registro) {
                    traza += "0x%02X contestado".format(registro)
                    return r
                }
            }
        }
        // Segundo intento CON ACUSE.
        //
        // La escritura sin respuesta no confirma nada: `writeCharacteristic`
        // devuelve true en cuanto la encola y jamas sabremos si llego. Con
        // acuse, el propio BMS contesta con un status — y eso distingue "no
        // recibe" de "recibe y no quiere contestar", que se arreglan distinto.
        if (sinRespuesta) {
            traza += "reintento 0x%02X con acuse".format(registro)
            recibidas.clear()
            escrito.clear()
            ensamblador.reiniciar()
            escribe.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            escribe.value = peticion
            val salio2 = gatt.writeCharacteristic(escribe)
            val acuse = if (salio2) escrito.poll(3_000, TimeUnit.MILLISECONDS) else null
            traza += "con acuse: writeCharacteristic=$salio2 status=${acuse ?: "sin acuse"}"
            if (salio2) {
                val hasta2 = System.currentTimeMillis() + MS_RESPUESTA
                while (System.currentTimeMillis() < hasta2) {
                    val trozo = recibidas.poll(300, TimeUnit.MILLISECONDS) ?: continue
                    for (r in ensamblador.alimentar(trozo)) {
                        sueltas += r
                        if (r.registro == registro) {
                            traza += "0x%02X contestado al reintento".format(registro)
                            return r
                        }
                    }
                }
            }
        }

        problemas += "0x%02X sin respuesta en %d ms".format(registro, MS_RESPUESTA)
        return null
    }

    /**
     * Barrido BLE por la radio interna, para encontrar el BMS la primera vez.
     *
     * Devuelve mac, nombre y RSSI. El vigilante ya sabe cual elegir.
     */
    fun barrer(adaptador: BluetoothAdapter?, segundos: Int): List<Triple<String, String?, Int>> {
        val scanner = runCatching { adaptador?.bluetoothLeScanner }.getOrNull() ?: return emptyList()
        val oidas = linkedMapOf<String, Triple<String, String?, Int>>()

        val cb = object : ScanCallback() {
            override fun onScanResult(tipo: Int, r: ScanResult) {
                runCatching {
                    val mac = r.device?.address ?: return@runCatching
                    val nombre = runCatching { r.device?.name }.getOrNull()
                        ?: r.scanRecord?.deviceName
                    synchronized(oidas) { oidas[mac] = Triple(mac, nombre, r.rssi) }
                }
            }
        }

        return runCatching {
            val ajustes = ScanSettings.Builder()
                // BALANCED y no LOW_LATENCY: el receptor a tope es el modo de
                // mayor consumo de radio que existe, y este aparato ya se
                // apago tres veces por calor. Un BMS se anuncia varias veces
                // por segundo; se encuentra igual.
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build()
            scanner.startScan(null, ajustes, cb)
            try {
                Thread.sleep(segundos.coerceIn(1, 30) * 1000L)
            } finally {
                runCatching { scanner.stopScan(cb) }
            }
            synchronized(oidas) { oidas.values.toList() }
        }.getOrDefault(emptyList())
    }
}

/**
 * Donde se enchufa la lectura del BMS cuando NO se usa el dongle.
 *
 * Mientras [leer] valga null, el vigilante sigue por [CanalGattDisponible] y
 * el dongle, que es como nacio. En cuanto el servicio lo cablea a la radio
 * interna, el dongle deja de hacer falta para la bateria.
 */
object LectorBmsDirecto {
    @Volatile
    var leer: ((String) -> LectorBmsGatt.Lectura?)? = null

    /** Barrido por la radio interna. Null = barrer con el dongle. */
    @Volatile
    var barrer: ((Int) -> List<Triple<String, String?, Int>>)? = null

    fun hay(): Boolean = leer != null
}
