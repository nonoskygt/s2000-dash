package com.nonosky.carsoc.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Transporte sobre Bluetooth Clasico (RFCOMM / SPP).
 *
 * El permiso `BLUETOOTH_CONNECT` lo pide y verifica [com.nonosky.carsoc.DashActivity]
 * antes de construir esta clase; por eso los `SuppressLint`.
 */
@SuppressLint("MissingPermission")
class SppTransport(
    private val device: BluetoothDevice,
    private val adapter: BluetoothAdapter?,
) : ObdTransport {

    private var socket: BluetoothSocket? = null
    private var lector: LectorDePrompt? = null
    private var output: OutputStream? = null

    override val isConnected: Boolean
        get() = socket?.isConnected == true

    override fun connect() {
        // El descubrimiento activo mata el throughput de RFCOMM.
        runCatching { adapter?.cancelDiscovery() }

        // Se prueban varias formas, en este orden y por una razon concreta:
        //
        // 1. INSEGURO al UUID de SPP. Es la que funciona con los clones de
        //    ELM327, porque NO exige emparejamiento previo. Muchos de estos
        //    adaptadores no completan el emparejamiento seguro de Android
        //    —se quedan en "vinculando" para siempre— y con el socket seguro
        //    no hay manera de hablarles.
        // 2. SEGURO al UUID de SPP, para los adaptadores que si se emparejan.
        // 3. Canal 1 por reflexion, seguro e inseguro: el ultimo recurso de
        //    los clones que ni siquiera publican el servicio SPP.
        val intentos = listOf<Pair<String, () -> BluetoothSocket>>(
            "inseguro-SPP" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            "seguro-SPP" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            "inseguro-canal1" to { canalPorReflexion("createInsecureRfcommSocket") },
            "seguro-canal1" to { canalPorReflexion("createRfcommSocket") },
        )

        val fallos = StringBuilder()
        for ((nombre, crear) in intentos) {
            var s: BluetoothSocket? = null
            try {
                s = crear()
                s.connect()
                Log.i(TAG, "Conectado por $nombre")
                attach(s)
                return
            } catch (e: Exception) {
                // Cerrar SIEMPRE el socket que no conecto: si no, cada
                // reintento fuga un canal RFCOMM y acaban agotandose.
                runCatching { s?.close() }
                Log.w(TAG, "$nombre fallo: ${e.message}")
                fallos.append(nombre).append("=").append(e.message).append("; ")
            }
        }
        throw IOException("RFCOMM fallo por todas las vias: $fallos")
    }

    /** Canal 1 por reflexion, para clones sin registro SDP. */
    private fun canalPorReflexion(metodo: String): BluetoothSocket {
        val m = device.javaClass.getMethod(metodo, Int::class.javaPrimitiveType)
        return m.invoke(device, 1) as BluetoothSocket
    }

    private fun attach(s: BluetoothSocket) {
        socket = s
        lector = LectorDePrompt(s.inputStream, "obd-spp-lector")
        output = s.outputStream
    }

    override fun write(bytes: ByteArray) {
        val out = output ?: throw IOException("Transporte no conectado")
        out.write(bytes)
        out.flush()
    }

    override fun readUntilPrompt(timeoutMs: Long): String {
        val l = lector ?: throw IOException("Transporte no conectado")
        // Sin sondeo: el hilo del lector esta bloqueado en read() y esto duerme
        // en su cola hasta que llega un byte o se agota el plazo. Ver
        // LectorDePrompt: antes eran hasta 250 despertares por segundo.
        return l.leerHastaPrompt(timeoutMs, PROMPT)
    }

    override fun drain() {
        lector?.vaciar()
    }

    override fun close() {
        runCatching { output?.close() }
        // Cerrar el socket desbloquea el read() del lector y su hilo termina.
        runCatching { socket?.close() }
        lector = null
        output = null
        socket = null
        Log.d(TAG, "Transporte cerrado")
    }

    companion object {
        private const val TAG = "SppTransport"
        private const val PROMPT = '>'
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
