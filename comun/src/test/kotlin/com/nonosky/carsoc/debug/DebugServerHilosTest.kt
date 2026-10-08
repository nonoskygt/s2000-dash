package com.nonosky.carsoc.debug

import com.nonosky.carsoc.VehicleState
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El puente de diagnostico: un hilo por peticion, sin tope.
 *
 * ### Por que esto importa en este aparato y no en un servidor cualquiera
 *
 * `DebugServer.start()` acepta en bucle y por cada conexion hace
 * `thread(isDaemon = true) { handle(client) }` (DebugServer.kt:~88). No hay
 * pool, no hay tope, y `handle` pone `sock.soTimeout = 40_000` para TODAS las
 * rutas — el plazo largo esta ahi por la ruta `/buscar`, que barre Bluetooth
 * y tarda.
 *
 * Junta las dos cosas: **cada conexion que se abre y no manda nada retiene un
 * hilo durante 40 segundos**. En un rk3326 cada hilo reserva su pila; unos
 * cientos de conexiones tontas —un escaneo de puertos, un cliente que perdio
 * el WiFi y dejo el socket a medias, una pestana del navegador reintentando—
 * y el proceso se queda sin poder crear hilos. Cuando eso pasa, lo que se
 * cae no es solo el puente: se cae el tablero, el TPMS y el actualizador, que
 * viven en el mismo proceso. Ya hubo un cuelgue atribuido al DebugServer una
 * vez, y esta ahi escrito en los comentarios del propio archivo.
 *
 * Y el puente escucha en la red del taller sin autenticacion ninguna.
 *
 * ### Como se arregla
 *
 *  - Un pool acotado (`Executors.newFixedThreadPool(4)`) en vez de un hilo
 *    por conexion, y rechazar limpiamente cuando esta lleno.
 *  - Dos plazos, no uno: uno corto (2-3 s) para LEER la peticion, y el largo
 *    de 40 s solo despues de saber que la ruta pedida es de las lentas.
 */
class DebugServerHilosTest {

    private var server: DebugServer? = null
    private val abiertos = mutableListOf<Socket>()

    @After
    fun cerrarTodo() {
        abiertos.forEach { runCatching { it.close() } }
        abiertos.clear()
        runCatching { server?.stop() }
        // Los hilos de peticion mueren solos al cerrarse su socket; se les da
        // un momento para que no contaminen la siguiente prueba.
        Thread.sleep(300)
    }

    /**
     * CAZA: un hilo por conexion, sin tope, retenido 40 s por conexiones que
     * no mandan nada.
     *
     * FALLA HOY A PROPOSITO.
     */
    @Test
    fun conexiones_que_no_mandan_nada_no_pueden_multiplicar_los_hilos() {
        val puerto = arrancar()
        val antes = hilosTotales()

        // Conexiones mudas: se abren y se quedan calladas. Es exactamente lo
        // que hace un escaneo de puertos o un cliente al que se le cayo la red.
        repeat(CONEXIONES_MUDAS) {
            val s = Socket()
            runCatching { s.connect(InetSocketAddress("127.0.0.1", puerto), 2_000) }
            abiertos += s
        }
        Thread.sleep(500)

        val despues = hilosTotales()
        val crecimiento = despues - antes
        assertTrue(
            "$CONEXIONES_MUDAS conexiones mudas crearon $crecimiento hilos. Cada uno\n" +
                "se queda 40 s esperando una peticion que no va a llegar, y son\n" +
                "hilos del MISMO proceso que dibuja el tablero.",
            crecimiento <= TOPE_HILOS,
        )
    }

    /**
     * GUARDA (hoy pasa): muchas peticiones seguidas y bien formadas no dejan
     * hilos detras.
     *
     * Si esto se pusiera rojo significaria que un hilo de peticion se queda
     * colgado despues de contestar, que es la otra forma de llegar al mismo
     * sitio pero por trafico legitimo.
     */
    @Test
    fun peticiones_normales_no_dejan_hilos_colgados() {
        val puerto = arrancar()
        val antes = hilosTotales()

        repeat(PETICIONES) {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", puerto), 2_000)
                s.soTimeout = 5_000
                s.getOutputStream().write("GET /no-existe HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                s.getOutputStream().flush()
                val respuesta = s.getInputStream().readBytes().decodeToString()
                assertTrue("respuesta rara: " + respuesta.take(80), respuesta.contains("404"))
            }
        }

        Thread.sleep(500)
        val crecimiento = hilosTotales() - antes
        assertTrue(
            "quedaron $crecimiento hilos vivos tras $PETICIONES peticiones cerradas",
            crecimiento <= TOPE_HILOS,
        )
    }

    /**
     * GUARDA (hoy pasa): una peticion sin salto de linea no se come la
     * memoria.
     *
     * `readLineAcotada` existe justo por esto: un `readLine()` a secas crece
     * hasta donde el cliente quiera. La prueba manda 512 KB sin un solo salto
     * de linea y comprueba que el servidor sigue vivo y contestando.
     */
    @Test
    fun una_peticion_sin_salto_de_linea_no_tumba_el_puente() {
        val puerto = arrancar()

        val basura = Socket()
        basura.connect(InetSocketAddress("127.0.0.1", puerto), 2_000)
        abiertos += basura
        runCatching {
            val chorro = ByteArray(8 * 1024) { 'A'.code.toByte() }
            repeat(64) { basura.getOutputStream().write(chorro) }
            basura.getOutputStream().flush()
        }

        // Y despues, el puente tiene que seguir atendiendo a los demas.
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", puerto), 2_000)
            s.soTimeout = 5_000
            s.getOutputStream().write("GET /no-existe HTTP/1.1\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val respuesta = s.getInputStream().readBytes().decodeToString()
            assertTrue(
                "el puente dejo de contestar despues de la peticion basura",
                respuesta.contains("404"),
            )
        }
    }

    // ------------------------------------------------------------------

    private fun arrancar(): Int {
        // Puerto alto y distinto del de produccion (8099) para no chocar con
        // un tablero de verdad corriendo en la misma maquina.
        val puerto = PUERTO_PRUEBA
        val s = DebugServer(
            port = puerto,
            stateProvider = { VehicleState() },
            viewProvider = { null },
            // No se llama nunca: ninguna ruta de estas pruebas lo usa. Si
            // alguien anade una que si, saltara aqui en vez de dar un dato
            // inventado por bueno.
            updaterProvider = { throw IllegalStateException("el actualizador no se usa en esta prueba") },
        )
        s.start()
        server = s
        esperarPuerto(puerto)
        return puerto
    }

    private fun esperarPuerto(puerto: Int) {
        val hasta = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < hasta) {
            val ok = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", puerto), 200) }
                true
            }.getOrDefault(false)
            if (ok) {
                Thread.sleep(100)
                return
            }
            Thread.sleep(50)
        }
        throw AssertionError("el puente no abrio el puerto $puerto en 10 s")
    }

    /** Hilos vivos en toda la JVM. El puente no les pone nombre propio. */
    private fun hilosTotales(): Int {
        var raiz = Thread.currentThread().threadGroup ?: return 0
        while (raiz.parent != null) raiz = raiz.parent
        return raiz.activeCount()
    }

    private companion object {
        const val PUERTO_PRUEBA = 18099
        const val CONEXIONES_MUDAS = 200
        const val PETICIONES = 60

        /**
         * Con un pool acotado el crecimiento seria el tamano del pool. 40 deja
         * sitio de sobra a eso y al ruido de la JVM, y sigue estando muy lejos
         * de los 200 de hoy.
         */
        const val TOPE_HILOS = 40
    }
}
