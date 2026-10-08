package com.nonosky.carsoc.obd

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Lee lo que manda el ELM327 hasta su `>`, SIN sondear.
 *
 * ## Por que existe
 *
 * `SppTransport` esperaba la respuesta preguntando `available()` y durmiendo
 * 4 ms entre pregunta y pregunta: hasta 250 despertares y llamadas al sistema
 * por segundo, todo el tiempo que hubiera enlace OBD. Y el OBD es la unica
 * fuente que el guardian termico NO apaga —decision documentada—, asi que era
 * justo el gasto que no se podia quitar bajando el ritmo.
 *
 * Aqui un hilo se queda BLOQUEADO en `read()` —sin gastar nada— y deja cada
 * trozo en una cola; quien espera la respuesta duerme en la cola con su plazo.
 * Llega un byte y se despierta en el acto: la latencia es la misma o mejor que
 * con el sondeo, que añadia hasta 4 ms por respuesta.
 *
 * Aparte del transporte para poder probarlo en la JVM con un flujo cualquiera.
 */
internal class LectorDePrompt(entrada: InputStream, nombreHilo: String) {

    private val cola = LinkedBlockingQueue<ByteArray>()

    /** Lo que llego DESPUES del `>` en el mismo trozo; va primero la proxima vez. */
    private var resto: ByteArray? = null

    init {
        thread(name = nombreHilo, isDaemon = true) {
            val buf = ByteArray(256)
            try {
                while (true) {
                    val n = entrada.read(buf)
                    if (n < 0) break
                    if (n > 0) cola.offer(buf.copyOf(n))
                }
            } catch (_: IOException) {
                // Cerrar el socket es la forma normal de acabar con este hilo.
            }
            cola.offer(FIN)
        }
    }

    /**
     * Devuelve lo recibido hasta el [prompt], sin incluirlo. Si se agota el
     * plazo, devuelve lo que haya: truncado es asunto del parser.
     *
     * @throws IOException si el adaptador cerro la conexion. Sin esto quien
     *   llama creeria que el enlace vive y seguiria sondeando contra nada.
     */
    fun leerHastaPrompt(plazoMs: Long, prompt: Char): String {
        val sb = StringBuilder()
        val fin = System.currentTimeMillis() + plazoMs
        while (true) {
            val trozo = resto?.also { resto = null } ?: run {
                val falta = fin - System.currentTimeMillis()
                if (falta <= 0) return sb.toString()
                cola.poll(falta, TimeUnit.MILLISECONDS) ?: return sb.toString()
            }
            if (trozo === FIN) {
                cola.offer(FIN)   // que lo vea tambien la proxima lectura
                throw IOException("El adaptador cerro la conexion")
            }
            for (i in trozo.indices) {
                val c = (trozo[i].toInt() and 0xFF).toChar()
                if (c == prompt) {
                    if (i + 1 < trozo.size) resto = trozo.copyOfRange(i + 1, trozo.size)
                    return sb.toString()
                }
                sb.append(c)
            }
        }
    }

    /** Tira lo que haya llegado sin pedirlo. El fin de flujo se conserva. */
    fun vaciar() {
        resto = null
        while (true) {
            val t = cola.poll() ?: return
            if (t === FIN) {
                cola.offer(FIN)
                return
            }
        }
    }

    private companion object {
        /** Marca de fin de flujo. Se compara por identidad. */
        val FIN = ByteArray(0)
    }
}
