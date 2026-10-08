package com.nonosky.carsoc.hci

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manda un comando HCI y espera SU respuesta, sin robarle eventos a nadie.
 *
 * Existe por dos razones concretas:
 *
 * 1. **Control de flujo de comandos.** HCI le da al anfitrion un numero de
 *    creditos de comando (`Num_HCI_Command_Packets`, casi siempre 1). Mandar
 *    un comando sin haber recibido el `Command Complete` del anterior es
 *    pedirle al controlador que descarte uno de los dos, y el sintoma es una
 *    respuesta que no llega jamas. Esta clase serializa.
 *
 * 2. **Emparejar la respuesta con la pregunta.** El emparejamiento genera
 *    eventos espontaneos (PIN Code Request llega cuando al otro extremo le
 *    da la gana) entremezclados con las respuestas. Leer "el siguiente
 *    evento" y darlo por bueno —que es lo que hacia [SondaHci], y estaba
 *    bien para una sonda secuencial— aqui devuelve la respuesta equivocada.
 *
 * No bloquea a la [BombaEventos]: se suscribe a ella y espera en su propia
 * cola, asi que se puede llamar desde cualquier hilo **menos** desde dentro
 * de un oyente de la bomba.
 */
class CanalComandos(
    private val hci: HciUsb,
    bomba: EventosHci,
) : ComandosHci {

    private val candado = ReentrantLock()

    /** Uno solo: solo hay un comando en vuelo a la vez, por construccion. */
    private val respuestas = ArrayBlockingQueue<ByteArray>(4)

    @Volatile
    private var esperandoOpcode: Int = -1

    private val baja: () -> Unit = bomba.suscribir { e -> quiza(e) }

    private fun quiza(e: ByteArray) {
        val esperado = esperandoOpcode
        if (esperado < 0) return
        val op = opcodeDe(e)
        if (op != esperado) return
        respuestas.offer(e)
    }

    /**
     * Manda [opcode] y devuelve el evento que lo cierra, o `null` si vencio.
     *
     * El evento devuelto es un `Command Complete` (0x0E) o un `Command
     * Status` (0x0F): son las dos formas que tiene HCI de cerrar un comando,
     * y cual de las dos llega depende del comando. Los que arrancan algo que
     * tarda —Create Connection, Authentication Requested— contestan con
     * Command Status y **el resultado de verdad llega mucho despues** en otro
     * evento. Confundirlos es creer que ya hay conexion cuando solo hay una
     * promesa.
     */
    override fun ejecutar(opcode: Int, parametros: ByteArray, timeoutMs: Long): ByteArray? =
        candado.withLock {
            respuestas.clear()
            esperandoOpcode = opcode
            try {
                if (hci.mandarComando(opcode, parametros) < 0) return null
                respuestas.poll(timeoutMs, TimeUnit.MILLISECONDS)
            } finally {
                esperandoOpcode = -1
            }
        }

    fun cerrar() {
        runCatching { baja() }
    }

    companion object {

        /** El opcode que cierra un Command Complete o un Command Status. */
        fun opcodeDe(e: ByteArray): Int = when {
            e.size >= 5 && (e[0].toInt() and 0xFF) == HciBrEdr.EVT_COMMAND_COMPLETE ->
                ((e[4].toInt() and 0xFF) shl 8) or (e[3].toInt() and 0xFF)
            e.size >= 6 && (e[0].toInt() and 0xFF) == HciBrEdr.EVT_COMMAND_STATUS ->
                ((e[5].toInt() and 0xFF) shl 8) or (e[4].toInt() and 0xFF)
            else -> -1
        }

        /**
         * El byte de estado, que esta en sitios distintos segun el evento.
         *
         * En Command Complete va detras del opcode (byte 5); en Command
         * Status va **antes** (byte 2). Leerlo del sitio equivocado da un
         * exito donde hubo un fallo.
         */
        fun estadoDe(e: ByteArray): Int = when {
            e.size >= 6 && (e[0].toInt() and 0xFF) == HciBrEdr.EVT_COMMAND_COMPLETE ->
                e[5].toInt() and 0xFF
            e.size >= 3 && (e[0].toInt() and 0xFF) == HciBrEdr.EVT_COMMAND_STATUS ->
                e[2].toInt() and 0xFF
            else -> -1
        }

        /** Parametros de retorno de un Command Complete, sin cabecera. */
        fun retornoDe(e: ByteArray): ByteArray =
            if (e.size > 6 && (e[0].toInt() and 0xFF) == HciBrEdr.EVT_COMMAND_COMPLETE) {
                e.copyOfRange(6, e.size)
            } else {
                ByteArray(0)
            }
    }
}
