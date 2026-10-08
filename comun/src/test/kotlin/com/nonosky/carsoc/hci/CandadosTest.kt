package com.nonosky.carsoc.hci

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Candados: que nadie se trabe, y que un bloqueo no pare a todo el mundo.
 *
 * ### Por que con plazo
 *
 * Una prueba de bloqueo mutuo que se cuelga no es una prueba que tarda: es
 * una prueba que **fallo** y no lo dice. Aqui todo se espera con `await` y un
 * plazo; si vence, se vuelca la pila de todos los hilos y se falla con ese
 * volcado, que es lo unico que permite ver el ciclo de candados.
 *
 * ### Los candados que hay en este camino
 *
 *  - `DuenoDongle.candado` — `ReentrantLock(true)`, justo, reentrante.
 *  - `BombaHci.cerrojoComando` — serializa los comandos HCI.
 *  - `BombaHci.cerrojosEnvio[handle]` — uno por enlace, para que dos PDU no
 *    intercalen sus trozos.
 *  - `ControlFlujoAcl.cerrojo` — el monitor de los creditos.
 *  - `GestorL2cap` — sus mapas son concurrentes, sin candado propio.
 */
class CandadosTest {

    private val paraParar = mutableListOf<BombaHci>()

    @After
    fun apagarTodo() {
        paraParar.forEach { runCatching { it.detener() } }
        Vigia.esperarAQueMueran(5_000)
    }

    /**
     * GUARDA (hoy pasa): varios hilos tomando los candados en ordenes
     * distintos no se traban.
     *
     * No hay un ciclo de espera conocido en el codigo de hoy —y esta prueba
     * lo confirma en vez de suponerlo—, pero el diseno esta a un descuido de
     * tenerlo: `enviarAcl` mantiene el cerrojo del enlace mientras espera
     * creditos, y esos creditos los entrega OTRO hilo. El dia que alguien
     * llame a `enviarAcl` desde el hilo de la bomba, el ciclo se cierra y el
     * tablero se queda quieto para siempre. Esta prueba es la alarma.
     */
    @Test
    fun varios_hilos_en_ordenes_distintos_no_se_traban() {
        val canal = CanalUsbFalso(demoraMs = 0)
        val bomba = BombaHci(canal)
        paraParar += bomba
        assertTrue(bomba.arrancar())
        bomba.flujo.configurar(15, 27)
        val gestor = GestorL2cap(bomba)
        gestor.arrancar()

        // El controlador de mentira devuelve creditos, que es lo que hace uno
        // de verdad. Sin esto los envios se atascarian por falta de credito y
        // la prueba mediria eso en vez de los candados.
        val corriendo = AtomicBoolean(true)
        val controlador = Thread({
            while (corriendo.get()) {
                for (h in HANDLES) bomba.flujo.devolver(h, 4)
                Thread.sleep(2)
            }
        }, "creditos-de-prueba")
        controlador.isDaemon = true
        controlador.start()

        val listos = CountDownLatch(HILOS)
        val vueltasHechas = AtomicInteger(0)

        for (i in 0 until HILOS) {
            val t = Thread({
                try {
                    val handle = HANDLES[i % HANDLES.size]
                    repeat(VUELTAS) { v ->
                        // Cada hilo toma los candados en un orden distinto.
                        // Es la unica forma de encontrar un ciclo: con todos
                        // pidiendo en el mismo orden nunca hay abrazo mortal.
                        when ((i + v) % 4) {
                            0 -> DuenoDongle.usar("hilo-$i", 200) {
                                bomba.enviarAcl(handle, L2cap.CID_ATT, CARGA, 200)
                            }
                            1 -> {
                                bomba.enviarAcl(handle, L2cap.CID_ATT, CARGA, 200)
                                DuenoDongle.usar("hilo-$i", 200) { bomba.flujo.libres() }
                            }
                            2 -> DuenoDongle.usar("hilo-$i", 200) {
                                bomba.comando(HciUsb.CMD_READ_BD_ADDR, ByteArray(0), 100)
                            }
                            else -> {
                                val c = gestor.canalFijo(handle)
                                c.enviar(CARGA, 200)
                                DuenoDongle.usar("hilo-$i", 200) {
                                    bomba.enviarAcl(handle, L2cap.CID_ATT, CARGA, 200)
                                }
                            }
                        }
                        vueltasHechas.incrementAndGet()
                    }
                } finally {
                    listos.countDown()
                }
            }, "candados-$i")
            t.isDaemon = true
            t.start()
        }

        val acabaron = listos.await(PLAZO_MS, TimeUnit.MILLISECONDS)
        corriendo.set(false)
        controlador.join(2_000)

        assertTrue(
            "Se trabaron: solo ${vueltasHechas.get()} de ${HILOS * VUELTAS} vueltas en\n" +
                "$PLAZO_MS ms. Volcado de TODOS los hilos:\n" + Vigia.volcadoCompleto(),
            acabaron,
        )
    }

    /**
     * CAZA: un oyente que se bloquea al enviar para el reparto de TODOS.
     *
     * FALLA HOY A PROPOSITO.
     *
     * ### El camino, que no es hipotetico
     *
     * `BombaHci.repartir` (BombaHci.kt:~300) recorre los oyentes en serie, en
     * el hilo `reparto-hci`. Y uno de esos oyentes es `GestorL2cap`, que al
     * recibir un `CONFIGURATION REQUEST` contesta con
     * `responder(...)` -> `bomba.enviarAcl(handle, cid, mensaje, 2_000)`.
     *
     * `enviarAcl` **bloquea hasta 2 segundos** esperando credito ACL. Durante
     * esos 2 segundos el hilo de reparto esta parado, y con el se paran:
     *
     *  - las notificaciones ATT del BMS (la bateria deja de actualizarse),
     *  - los bytes del ELM327 (`HciObdTransport.alPdu`, el motor se congela),
     *  - y los avisos de caida de enlace (`alCaerEnlace`), que es lo peor:
     *    todo el mundo sigue creyendo que su enlace vive.
     *
     * El comentario de cabecera de `BombaHci` dice que el hilo de reparto "si
     * se puede bloquear sin parar el mundo". Para el USB, cierto. Para los
     * demas oyentes, no: los para a todos.
     *
     * ### Como se arregla
     *
     * Una cola de salida por oyente, o —mas simple y suficiente— que
     * `GestorL2cap.responder` no bloquee: encolar la respuesta de
     * senalizacion en vez de esperar credito dentro del reparto.
     */
    @Test
    fun un_oyente_bloqueado_no_puede_parar_el_reparto_a_los_demas() {
        val canal = CanalUsbFalso(demoraMs = 0)
        val bomba = BombaHci(canal)
        paraParar += bomba
        assertTrue(bomba.arrancar())

        // Un solo credito y nadie que lo devuelva: es lo que pasa cuando el
        // controlador esta saturado, que es justo cuando mas trafico hay.
        bomba.flujo.configurar(1, 27)

        // Oyente 1: el que contesta, como hace GestorL2cap. Se registra
        // primero porque el reparto va en orden de suscripcion.
        bomba.suscribir(object : BombaHci.Oyente {
            override fun alEvento(evento: ByteArray) {
                // 200 bytes son varios trozos: el primero se lleva el unico
                // credito y el segundo se queda esperando los 2 s completos.
                bomba.enviarAcl(HANDLES[0], L2cap.CID_SENAL_CLASICO, ByteArray(200), 2_000)
            }
        })

        // Oyente 2: el que necesita enterarse. En produccion es el vigilante
        // de la bateria, o el transporte del OBD.
        val recibidos = AtomicInteger(0)
        bomba.suscribir(object : BombaHci.Oyente {
            override fun alEvento(evento: ByteArray) {
                recibidos.incrementAndGet()
            }
        })

        repeat(EVENTOS) { canal.entregarEvento(byteArrayOf(0x0E, 0x04, 0x01, 0x03, 0x0C, 0x00)) }

        Thread.sleep(VENTANA_MS)

        assertTrue(
            "En $VENTANA_MS ms solo llegaron ${recibidos.get()} de $EVENTOS eventos al\n" +
                "segundo oyente: el primero, bloqueado enviando, paro el reparto entero.\n" +
                "Asi es como la bateria y el motor dejan de actualizarse a la vez\n" +
                "sin que nada de en el log.\n" + bomba.diagnostico().joinToString("\n"),
            recibidos.get() >= MINIMO_ESPERADO,
        )
    }

    private companion object {
        const val HILOS = 8
        const val VUELTAS = 60
        const val PLAZO_MS = 30_000L
        val HANDLES = intArrayOf(0x0B, 0x0C, 0x0D)
        val CARGA = ByteArray(16)

        const val EVENTOS = 40
        const val VENTANA_MS = 1_000L

        /**
         * Con el reparto sano, 40 eventos ya encolados se entregan en
         * milisegundos. Se pide la mitad para no ser fragil en una maquina
         * cargada; hoy no llega ni a uno por segundo.
         */
        const val MINIMO_ESPERADO = 20
    }
}
