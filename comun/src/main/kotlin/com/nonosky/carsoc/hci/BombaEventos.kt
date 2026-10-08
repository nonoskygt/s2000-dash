package com.nonosky.carsoc.hci

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Un solo hilo lee el endpoint de eventos y reparte a quien se suscriba.
 *
 * Hace falta en cuanto hay mas de un interesado en los eventos HCI, y a
 * partir de aqui siempre los hay: el enlace BR/EDR espera `Connection
 * Complete` mientras la capa ACL necesita `Number Of Completed Packets` para
 * no quedarse sin creditos, y ninguno de los dos puede robarle el evento al
 * otro. Dos hilos leyendo el mismo endpoint se roban paquetes; uno solo que
 * reparte, no.
 *
 * Ventaja del transporte USB que conviene tener presente: los eventos van
 * por el endpoint de INTERRUPCION y los datos ACL por los BULK, o sea que
 * son colas separadas. En un transporte serie (H4) habria que demultiplexar
 * por el byte de tipo de paquete; aqui no hay nada que demultiplexar.
 *
 * Regla para los oyentes: **no bloquear**. Se les llama en el hilo de la
 * bomba; si uno se duerme, el controlador llena su cola de eventos y todo
 * lo demas se para.
 */
class BombaEventos(private val hci: CanalUsbHci) : EventosHci {

    /** Copia al escribir: se suscribe y se da de baja mientras la bomba corre. */
    private val oyentes = CopyOnWriteArrayList<(ByteArray) -> Unit>()

    @Volatile
    private var vivo = false
    private var hilo: Thread? = null

    /** Contador de todo lo que ha entrado. Diagnostico: distingue mudo de sordo. */
    @Volatile
    var recibidos: Long = 0L
        private set

    fun arrancar() {
        if (vivo) return
        vivo = true
        // Envuelto entero a proposito: una excepcion que escapa de un hilo en
        // Android MATA el proceso, y con el se irian el tablero, el TPMS y el
        // actualizador. Ya paso una vez con el DebugServer.
        hilo = thread(name = "bomba-eventos-hci", isDaemon = true) {
            while (vivo) {
                val e = runCatching { hci.leerEvento(LECTURA_MS) }
                    .onFailure { Log.w(TAG, "lectura fallida: ${it.message}") }
                    .getOrNull() ?: continue
                if (e.size < 2) continue
                recibidos++
                for (o in oyentes) {
                    runCatching { o(e) }
                        .onFailure { Log.w(TAG, "oyente fallo: ${it.message}") }
                }
            }
        }
    }

    fun detener() {
        vivo = false
        runCatching { hilo?.join(1_000) }
        hilo = null
        oyentes.clear()
    }

    /** Devuelve la baja: llamarla libera al oyente. */
    override fun suscribir(oyente: (ByteArray) -> Unit): () -> Unit {
        oyentes.add(oyente)
        return { oyentes.remove(oyente) }
    }

    private companion object {
        const val TAG = "BombaEventos"

        /**
         * Plazo de cada lectura. Corto para que `detener()` responda rapido,
         * no tan corto como para quemar CPU en un rk3326.
         */
        const val LECTURA_MS = 400
    }
}
