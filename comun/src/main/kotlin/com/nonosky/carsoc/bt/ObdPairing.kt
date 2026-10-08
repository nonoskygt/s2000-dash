package com.nonosky.carsoc.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * Busca y empareja el adaptador OBD desde dentro de la app.
 *
 * Existe para no mandar al usuario a Ajustes de Bluetooth: en el carro, con
 * el motor prendido, irse a otra app y volver es justo lo que no queremos.
 *
 * Tambien contesta solo el PIN. Los clones de ELM327 usan siempre uno de
 * tres, y son emparejamiento legado (PIN numerico), no comparacion numerica
 * — asi que [BluetoothDevice.setPin] alcanza y no hace falta ser sistema.
 */
@SuppressLint("MissingPermission")
class ObdPairing(
    private val context: Context,
    private val adapter: BluetoothAdapter,
) {

    interface Listener {
        /** Lista viva de candidatos: emparejados primero, luego encontrados. */
        fun onDevices(devices: List<BluetoothDevice>)
        fun onBonded(device: BluetoothDevice)
        fun onBondFailed(device: BluetoothDevice)
        fun onScanFinished()
    }

    private var listener: Listener? = null
    private val found = LinkedHashMap<String, BluetoothDevice>()
    private var pinAttempt = 0
    private var registered = false

    /**
     * El dispositivo que el usuario eligio de la lista.
     *
     * Sin esto, CUALQUIER emparejamiento del radio se tomaba por el
     * adaptador OBD: bastaba emparejar un telefono para escuchar musica
     * para que su MAC quedara guardada como el adaptador, sobreviviendo a
     * todos los reinicios y dejando el tablero en "sin enlace" para
     * siempre, sin manera de saber por que.
     */
    private var objetivo: String? = null

    /**
     * Traza de lo ultimo que hizo el emparejamiento.
     *
     * Sin esto, un emparejamiento que no cuaja es una caja negra: no se
     * sabe si el sistema pidio un PIN, una comparacion numerica, o si
     * createBond ni siquiera arranco.
     */
    val traza = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun trazar(t: String) {
        if (traza.size > 40) traza.removeAt(0)
        traza.add(t)
        Log.i(TAG, t)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val device: BluetoothDevice? =
                intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    device ?: return
                    found[device.address] = device
                    emit()
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> listener?.onScanFinished()

                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    device ?: return
                    val variant = intent.getIntExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT, -1
                    )
                    // Solo el emparejamiento con PIN se puede contestar sin
                    // permisos de sistema. Si es otra variante, dejamos que
                    // salga el dialogo de Android en vez de estorbar.
                    trazar("PAIRING_REQUEST variante=$variant de ${device.address}")
                    if (variant != PAIRING_VARIANT_PIN) {
                        trazar("variante $variant no es PIN: la contesta el sistema, no nosotros")
                        return
                    }
                    val pin = COMMON_PINS.getOrNull(pinAttempt) ?: return
                    trazar("contestando PIN '$pin'")
                    runCatching {
                        val ok = device.setPin(pin.toByteArray(Charsets.US_ASCII))
                        trazar("setPin devolvio $ok")
                        // Silenciar el dialogo del sistema si el broadcast es
                        // ordenado; si no lo es, esto tira y no pasa nada.
                        abortBroadcast()
                    }.onFailure { trazar("setPin fallo: ${it.message}") }
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    device ?: return
                    // Solo nos importa el que el usuario eligio.
                    if (objetivo != null && device.address != objetivo) return
                    val bond = intent.getIntExtra(
                        BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR
                    )
                    trazar("BOND_STATE=$bond para ${device.address}")
                    when (bond) {
                        BluetoothDevice.BOND_BONDED -> {
                            pinAttempt = 0
                            listener?.onBonded(device)
                        }
                        BluetoothDevice.BOND_NONE -> {
                            // Volvio a cero: o el PIN estaba mal o lo rechazo.
                            pinAttempt++
                            if (pinAttempt < COMMON_PINS.size) {
                                Log.i(TAG, "PIN rechazado, probando el siguiente")
                                bond(device)
                            } else {
                                pinAttempt = 0
                                listener?.onBondFailed(device)
                            }
                        }
                    }
                }
            }
        }
    }

    fun start(listener: Listener) {
        this.listener = listener
        if (!registered) {
            context.registerReceiver(receiver, IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            })
            registered = true
        }
        emit()
    }

    /** Arranca el barrido. Requiere permiso de ubicacion en API 30 o menor. */
    fun scan() {
        runCatching {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            adapter.startDiscovery()
        }.onFailure { Log.w(TAG, "No se pudo barrer: ${it.message}") }
    }

    /**
     * Cancela un emparejamiento colgado.
     *
     * Un vinculo que se queda en BONDING bloquea todo reintento —
     * `createBond` devuelve false sin hacer nada— y no hay API publica para
     * abortarlo. La reflexion es fea pero es lo unico que hay.
     */
    fun cancelarVinculoColgado(device: BluetoothDevice): Boolean = runCatching {
        if (device.bondState != BluetoothDevice.BOND_BONDING) return false
        val m = device.javaClass.getMethod("cancelBondProcess")
        val ok = m.invoke(device) as? Boolean ?: false
        trazar("cancelBondProcess devolvio $ok")
        ok
    }.getOrElse {
        trazar("cancelBondProcess fallo: ${it.message}")
        false
    }

    fun bond(device: BluetoothDevice) {
        objetivo = device.address
        // El barrido activo destroza el throughput y el emparejamiento.
        runCatching { adapter.cancelDiscovery() }
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            listener?.onBonded(device)
            return
        }
        traza.clear()
        // Si quedo uno colgado de un intento anterior, abortarlo: si no,
        // createBond devuelve false para siempre y nada avanza.
        if (device.bondState == BluetoothDevice.BOND_BONDING) {
            cancelarVinculoColgado(device)
            Thread.sleep(1_500)
        }
        trazar("createBond() sobre ${device.address}, estado previo=${device.bondState}")
        runCatching {
            val ok = device.createBond()
            trazar("createBond devolvio $ok")
            if (!ok) listener?.onBondFailed(device)
        }.onFailure {
            trazar("createBond lanzo: ${it.message}")
            listener?.onBondFailed(device)
        }
    }

    fun stop() {
        objetivo = null
        runCatching { adapter.cancelDiscovery() }
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
        listener = null
    }

    private fun emit() {
        val bonded = runCatching { adapter.bondedDevices.orEmpty().toList() }
            .getOrDefault(emptyList())
        val bondedAddrs = bonded.map { it.address }.toSet()
        val todos = bonded + found.values.filter { it.address !in bondedAddrs }
        // Los que parecen adaptadores OBD van arriba: en una pantalla de
        // carro, la primera fila es la que se toca sin pensar.
        listener?.onDevices(todos.sortedByDescending { looksLikeObd(it) })
    }

    companion object {
        private const val TAG = "ObdPairing"

        /** Constante publica de la plataforma, replicada para no depender de API. */
        private const val PAIRING_VARIANT_PIN = 0

        /** Los tres PIN que traen practicamente todos los clones de ELM327. */
        val COMMON_PINS = listOf("1234", "6789", "0000")

        /**
         * Nombres que delatan un adaptador OBD-II.
         *
         * Lista ANCHA a proposito: el dueño va a comprar un generico
         * cualquiera, y de nada sirve una heuristica que solo reconoce las
         * ocho marcas que ya teniamos. Los clones chinos se anuncian con
         * media docena de nombres distintos y varios no llevan ni "OBD".
         *
         * Aun asi el nombre es solo una PISTA: la prueba de verdad es que el
         * aparato ofrezca SPP y conteste `ATI`. Ver [pareceObdPorServicio].
         */
        private val OBD_HINTS = listOf(
            "OBD", "ELM", "VLINK", "V-LINK", "VGATE", "KONNWEI",
            "STEREN", "SCAN", "ICAR", "VEEPEAK",
            "OBDII", "OBD2", "ELM327", "OBDLINK", "STN",
            "BAFX", "PANLONG", "LELINK", "CARISTA", "AUTEL",
            "THINKDIAG", "ANCEL", "FOXWELL", "TOPDON", "LAUNCH",
            "TONWON", "KWP", "CANOBD", "MINIVCI", "VIECAR",
            "DIAGNOSTIC", "SCANNER", "CARSCAN", "TORQUE",
        )

        /** El UUID de Serial Port Profile: por ahi hablan todos los ELM327. */
        private val UUID_SPP =
            java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        @SuppressLint("MissingPermission")
        fun looksLikeObd(device: BluetoothDevice): Boolean =
            nombrePareceObd(runCatching { device.name }.getOrNull())

        /** Lo mismo que [looksLikeObd] pero sobre el nombre a secas, para probarlo sin radio. */
        fun nombrePareceObd(nombre: String?): Boolean {
            val name = nombre?.uppercase() ?: return false
            return OBD_HINTS.any { name.replace(" ", "").replace("_", "").contains(it) }
        }

        /**
         * Ofrece Serial Port Profile, o sea que PODRIA ser un ELM327.
         *
         * Mas fiable que el nombre y mas laxo: un teclado o unos auriculares
         * no ofrecen SPP. Se usa junto con [looksLikeObd] para ordenar los
         * candidatos, nunca para elegir a ciegas — el proyecto ya guardo una
         * vez como "adaptador OBD" cualquier aparato emparejado, y bastaba
         * emparejar un telefono para romperlo.
         */
        @SuppressLint("MissingPermission")
        fun pareceObdPorServicio(device: BluetoothDevice): Boolean = runCatching {
            device.uuids?.any { it.uuid == UUID_SPP } == true
        }.getOrDefault(false)

        /**
         * Ordena candidatos: primero los que suenan a OBD Y ofrecen SPP,
         * despues los que solo suenan, y al final los que solo ofrecen SPP.
         * Devuelve lista vacia si no hay ninguno plausible.
         */
        @SuppressLint("MissingPermission")
        fun candidatos(aparatos: List<BluetoothDevice>): List<BluetoothDevice> =
            aparatos.mapNotNull { d ->
                val porNombre = looksLikeObd(d)
                val porServicio = pareceObdPorServicio(d)
                val nota = when {
                    porNombre && porServicio -> 3
                    porNombre -> 2
                    porServicio -> 1
                    else -> 0
                }
                if (nota == 0) null else d to nota
            }.sortedByDescending { it.second }.map { it.first }
    }
}
