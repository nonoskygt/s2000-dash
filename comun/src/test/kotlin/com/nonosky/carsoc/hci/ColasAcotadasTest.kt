package com.nonosky.carsoc.hci

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las colas tienen que tener tope. Sin tope, el radio se queda sin memoria.
 *
 * ### Por que importa aqui mas que en un servidor
 *
 * Un `LinkedBlockingQueue()` sin capacidad crece hasta donde llegue el
 * monton. En un servidor eso es una alarma; en un rk3326 con 1 GB
 * compartido con Android es el `lowmemorykiller` matando procesos, o el
 * proceso entero atascado en GC — que desde fuera se ve exactamente igual
 * que un cuelgue: el tablero congelado y el radio sin contestar.
 *
 * En este archivo hay dos casos y son distintos:
 *
 *  - La cola de **reparto** SI esta acotada (`ArrayBlockingQueue(256)`) y
 *    cuenta lo que descarta. Eso es lo correcto y hay una prueba que lo
 *    sujeta para que nadie lo deshaga.
 *  - La cola de **salida** (`LinkedBlockingQueue()` sin capacidad, BombaHci.kt:74)
 *    no tiene tope, y hay un camino real por el que crece sin fin.
 */
class ColasAcotadasTest {

    private val paraParar = mutableListOf<BombaHci>()

    @After
    fun apagarTodo() {
        paraParar.forEach { runCatching { it.detener() } }
        Vigia.esperarAQueMueran(5_000)
    }

    /**
     * GUARDA (hoy pasa): la cola de reparto no pasa de su tope y anota lo que
     * tira.
     *
     * El diseno es deliberado y correcto: la bomba **no puede bloquearse**
     * encolando, porque si la bomba para, para tambien el control de flujo y
     * el enlace se cae en cadena. Preferir perder una notificacion y contarla
     * es la decision buena. Lo que esta prueba impide es que alguien
     * "arregle" el descarte cambiando la cola por una sin capacidad.
     *
     * Nota sobre la politica de descarte: `ArrayBlockingQueue.offer` tira **lo
     * NUEVO**, no lo viejo. Para eventos HCI eso es discutible —lo fresco vale
     * mas que lo rancio— pero cambiarlo es una decision de diseno, no un bug,
     * y esta prueba documenta lo que hace hoy en vez de fingir otra cosa.
     */
    @Test
    fun la_cola_de_reparto_tiene_tope_y_cuenta_lo_que_descarta() {
        val canal = CanalUsbFalso(demoraMs = 0)
        val bomba = BombaHci(canal)
        paraParar += bomba

        // Un oyente lento: es el caso que llena la cola. En produccion lo es
        // GestorL2cap cuando contesta una configuracion L2CAP y se queda sin
        // creditos.
        bomba.suscribir(object : BombaHci.Oyente {
            override fun alEvento(evento: ByteArray) {
                Thread.sleep(20)
            }
        })

        // Mucho mas de lo que cabe: 4000 eventos contra una cola de 256.
        repeat(4_000) { canal.entregarEvento(byteArrayOf(0x0E, 0x04, 0x01, 0x03, 0x0C, 0x00)) }

        assertTrue(bomba.arrancar())

        var maxCola = 0
        val hasta = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < hasta) {
            val n = colaDe(bomba, "cola de reparto")
            if (n > maxCola) maxCola = n
            Thread.sleep(5)
        }

        assertTrue(
            "la cola de reparto llego a $maxCola, por encima de su tope de $REPARTO_MAX",
            maxCola in 0..REPARTO_MAX,
        )
        assertTrue(
            "no se conto ni un descarte, asi que o no se lleno o los descartes\n" +
                "son invisibles; las dos cosas son malas: " + bomba.diagnostico().joinToString(" | "),
            bomba.repartosPerdidos > 0,
        )
    }

    /**
     * CAZA: la cola de SALIDA crece sin fin cuando se cae un enlace con
     * paquetes ya encolados.
     *
     * FALLA HOY A PROPOSITO.
     *
     * ### El camino exacto
     *
     * 1. `enviarAcl` reserva UN credito por trozo y luego hace
     *    `salida.offer(t)` (BombaHci.kt:452). O sea: el credito se consume al
     *    ENCOLAR, no al escribir.
     * 2. Cuando llega un `Disconnection Complete` (0x05), la bomba llama a
     *    `flujo.olvidar(handle)` (BombaHci.kt:277), que devuelve TODOS los
     *    creditos de ese enlace de golpe — porque el controlador tira sus
     *    paquetes pendientes y no va a mandar el 0x13.
     * 3. Pero los trozos de ese enlace **siguen en `salida`**. Nadie los
     *    quita. Y con los creditos ya devueltos, quien envia puede encolar
     *    otros tantos.
     *
     * Cada caida de enlace con envios en vuelo deja basura permanente en una
     * cola sin tope. Y las caidas de enlace no son raras: el ELM327 se cae
     * solo, el BMS se duerme, y el lector de OBD reconecta por turnos.
     *
     * ### Como se arregla
     *
     * Dos cosas, y las dos hacen falta:
     *
     *  - Al olvidar un handle, purgar de `salida` los trozos de ese handle
     *    (`PaqueteAcl.handleDe(t)` ya existe y se usa dos lineas mas arriba).
     *  - Poner tope a `salida`: `LinkedBlockingQueue(TOPE)` y contar lo que se
     *    rechaza, igual que hace la cola de reparto.
     */
    @Test
    fun la_cola_de_salida_no_puede_crecer_sin_fin() {
        val canal = CanalUsbFalso(demoraMs = 5)
        val bomba = BombaHci(canal)
        paraParar += bomba
        assertTrue(bomba.arrancar())

        // Un pool normal de LE: 15 buffers de 27 bytes, medido en este dongle.
        bomba.flujo.configurar(15, 27)

        // AtomicBoolean y no una variable suelta: Kotlin no deja marcar
        // @Volatile una local, y sin barrera de memoria el hilo emisor podria
        // no ver nunca el cambio y dejar la prueba colgada.
        val enviando = java.util.concurrent.atomic.AtomicBoolean(true)
        val emisor = Thread({
            while (enviando.get()) {
                // Una notificacion ATT tipica del BMS: cabe en un solo trozo.
                bomba.enviarAcl(HANDLE, L2cap.CID_ATT, ByteArray(20), 1_000)
            }
        }, "emisor-de-prueba")
        emisor.isDaemon = true
        emisor.start()

        // El enlace se cae una y otra vez, que es lo que hace un ELM327 clon
        // o un BMS que se duerme. Cada caida devuelve todos los creditos.
        val caidas = Thread({
            while (enviando.get()) {
                canal.entregarEvento(desconexion(HANDLE))
                Thread.sleep(5)
            }
        }, "caidas-de-enlace")
        caidas.isDaemon = true
        caidas.start()

        var maxCola = 0
        val hasta = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < hasta) {
            val n = colaDe(bomba, "cola de salida")
            if (n > maxCola) maxCola = n
            Thread.sleep(10)
        }
        enviando.set(false)
        emisor.join(3_000)
        caidas.join(3_000)

        assertTrue(
            "la cola de salida llego a $maxCola trozos con un pool de solo 15\n" +
                "buffers. No tiene tope y nadie la purga cuando se cae el enlace:\n" +
                bomba.diagnostico().joinToString("\n"),
            maxCola <= TOPE_RAZONABLE_SALIDA,
        )
    }

    /**
     * GUARDA (hoy pasa): tras parar la bomba, las colas quedan vacias.
     *
     * `detener()` limpia `salida` y `reparto`. Sin eso, una bomba parada y
     * guardada en algun sitio seguiria reteniendo memoria de un enlace que ya
     * no existe.
     */
    @Test
    fun al_detener_las_colas_quedan_vacias() {
        val canal = CanalUsbFalso(demoraMs = 0)
        val bomba = BombaHci(canal)
        paraParar += bomba
        repeat(1_000) { canal.entregarEvento(byteArrayOf(0x0E, 0x04, 0x01, 0x03, 0x0C, 0x00)) }
        assertTrue(bomba.arrancar())
        Thread.sleep(200)
        bomba.detener()
        Vigia.esperarAQueMueran(3_000)

        assertEquals("la cola de salida no se vacio", 0, colaDe(bomba, "cola de salida"))
        assertEquals("la cola de reparto no se vacio", 0, colaDe(bomba, "cola de reparto"))
    }

    // ------------------------------------------------------------------

    /** Extrae un contador de la linea de diagnostico, que es la unica ventana. */
    private fun colaDe(bomba: BombaHci, cual: String): Int {
        val linea = bomba.diagnostico().firstOrNull { it.contains(cual) } ?: return -1
        val m = Regex(Regex.escape(cual) + ": (\\d+)").find(linea) ?: return -1
        return m.groupValues[1].toInt()
    }

    /** `05 | largo | estado | handle(2) | razon` — Disconnection Complete. */
    private fun desconexion(handle: Int): ByteArray = byteArrayOf(
        0x05, 0x04, 0x00,
        (handle and 0xFF).toByte(), ((handle shr 8) and 0x0F).toByte(),
        0x13,
    )

    private companion object {
        const val HANDLE = 0x0C

        /** El mismo REPARTO_MAX privado de BombaHci. */
        const val REPARTO_MAX = 256

        /**
         * Con 15 buffers en el pool, mas de 4 veces eso en la cola significa
         * que la contabilidad de creditos ya no sujeta nada.
         */
        const val TOPE_RAZONABLE_SALIDA = 64
    }
}
