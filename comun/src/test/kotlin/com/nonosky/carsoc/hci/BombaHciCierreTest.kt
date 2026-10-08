package com.nonosky.carsoc.hci

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La prueba que habria cazado el cuelgue del radio.
 *
 * ### El defecto, en tres lineas
 *
 * `RadioBt.soltar()` (RadioBt.kt:113) hace, en este orden:
 *
 * ```
 *   gestor?.detener()
 *   bomba?.detener()
 *   hci?.cerrar()          // releaseInterface() + close()
 * ```
 *
 * Y `BombaHci.detener()` (BombaHci.kt:174) hace:
 *
 * ```
 *   vivo = false
 *   hiloBomba?.interrupt()
 *   hiloBomba = null       // <-- y vuelve. NO espera a nadie.
 * ```
 *
 * No hay `join()`. Y `interrupt()` **no aborta un `bulkTransfer`**, que es
 * una llamada nativa: la bandera de interrupcion de Java no la mira nadie
 * dentro del driver. Asi que `hci.cerrar()` libera el descriptor USB
 * MIENTRAS el hilo `bomba-hci` puede seguir dentro de una transferencia sobre
 * ese mismo descriptor. Uso despues de cerrar, sobre un recurso nativo.
 *
 * ### Por que encaja con lo que se vio en el carro
 *
 * Con SOLO el TPMS (un CH340, un unico dueno, sin ciclos de apertura y
 * cierre) el tablero corrio horas sin fallar. Los cuelgues empezaron con el
 * dongle Bluetooth, que es el unico aparato que `RadioBt` abre y cierra por
 * conteo de referencias — o sea, el unico que pasa por esta secuencia, y lo
 * hace cada vez que el vigilante de la bateria o el lector de OBD sueltan su
 * referencia. Tambien explica por que bajar a 5 fps y enfriar el radio no
 * cambiaron nada: no era carga, era una carrera.
 *
 * Y el contraste dentro del propio proyecto refuerza la sospecha:
 * `BombaEventos.detener()` (BombaEventos.kt:63) **si** hace `hilo?.join(1_000)`,
 * y `CanalRfcomm` tambien. La unica que no espera a su hilo es justo la que
 * bombea el dongle.
 *
 * ### QUE PASA HOY AL CORRER ESTO
 *
 * Las dos primeras pruebas de esta clase **FALLAN con el codigo actual, y
 * deben fallar**. Son la descripcion en rojo del defecto. Se ponen en verde
 * cambiando `BombaHci.detener()` a esperar a sus hilos:
 *
 * ```
 *   fun detener() {
 *       vivo = false
 *       val b = hiloBomba
 *       val r = hiloReparto
 *       hiloBomba = null
 *       hiloReparto = null
 *       // Al hilo de reparto SI se le interrumpe: espera en colas y la
 *       // interrupcion si lo despierta. Al de la bomba NO: interrupt() no
 *       // aborta un bulkTransfer y solo deja la bandera puesta.
 *       runCatching { r?.interrupt() }
 *       runCatching { b?.join(PLAZO_PARADA_MS) }
 *       runCatching { r?.join(PLAZO_PARADA_MS) }
 *       ...
 *   }
 * ```
 *
 * El plazo tiene que ser mayor que una vuelta entera de la bomba
 * (MAX_POR_VUELTA x (ESPERA_EVENTO_MS + ESPERA_ACL_MS), unos 250 ms, mas el
 * plazo de una escritura), o sea del orden de 2 s. Y `detener()` no se puede
 * llamar desde el propio hilo de la bomba: se estaria esperando a si mismo.
 */
class BombaHciCierreTest {

    private var hilosDeBase = 0

    @Before
    fun apuntarLaBase() {
        // Otras pruebas dejan hilos agonizando; se espera a que se vayan para
        // que el conteo mida lo de ESTA prueba y no la resaca de la anterior.
        Vigia.esperarAQueMueran(3_000)
        hilosDeBase = Vigia.cuantos()
    }

    @After
    fun noDejarNadaCorriendo() {
        Vigia.esperarAQueMueran(3_000)
    }

    /**
     * CAZA: uso-despues-de-cerrar del descriptor USB. Es el cuelgue del radio.
     *
     * FALLA HOY A PROPOSITO. Ver el comentario de la clase.
     */
    @Test
    fun cerrar_la_radio_no_puede_pillar_a_la_bomba_dentro_de_una_transferencia() {
        val fallos = mutableListOf<String>()

        // Se repite: una carrera que salga una vez de cada diez seria una
        // prueba intermitente, y una prueba intermitente se acaba ignorando.
        // Con demora de 25 ms la bomba esta dentro del cable casi todo el
        // tiempo, asi que tiene que salir en TODAS las vueltas.
        repeat(REPETICIONES) { vuelta ->
            val canal = CanalUsbFalso(demoraMs = 25)
            val bomba = BombaHci(canal)
            val gestor = GestorL2cap(bomba)

            assertTrue("la bomba no arranco", bomba.arrancar())
            gestor.arrancar()

            // Esperar a que la bomba este de verdad girando: si se cerrara
            // antes de que empiece no habria carrera que observar, y la
            // prueba pasaria por el motivo equivocado.
            esperarAQue("la bomba empiece a transferir", 2_000) {
                canal.transferencias.get() >= 2
            }

            // El orden EXACTO de RadioBt.soltar(), y desde otro hilo, que es
            // como pasa de verdad: el vigilante de la bateria suelta su
            // referencia mientras la bomba sigue girando.
            val soltador = Thread({
                gestor.detener()
                bomba.detener()
                canal.cerrar()
            }, "soltador-radio")
            soltador.start()
            soltador.join(5_000)

            if (canal.delitosContados.get() > 0) {
                fallos += "vuelta $vuelta: " + canal.resumen()
            }
            Vigia.esperarAQueMueran(2_000)
        }

        assertEquals(
            "Se transfirio por USB sobre una conexion ya cerrada, o se cerro la\n" +
                "conexion con transferencias todavia dentro. Eso es un descriptor\n" +
                "nativo liberado bajo los pies de un bulkTransfer en curso.\n" +
                "Detalle:\n" + fallos.joinToString("\n"),
            emptyList<String>(), fallos,
        )
    }

    /**
     * CAZA: `detener()` que vuelve antes de que sus hilos hayan muerto.
     *
     * Es la misma raiz dicha como contrato: quien llama a `detener()` cree que
     * despues de esa linea ya nadie toca el aparato, y por eso la linea
     * siguiente cierra el USB. Si no es verdad, todo lo que venga despues de
     * `detener()` es inseguro.
     *
     * FALLA HOY A PROPOSITO.
     */
    @Test
    fun detener_tiene_que_dejar_muertos_sus_hilos_antes_de_volver() {
        val canal = CanalUsbFalso(demoraMs = 25)
        val bomba = BombaHci(canal)
        assertTrue(bomba.arrancar())
        esperarAQue("la bomba empiece a transferir", 2_000) { canal.transferencias.get() >= 2 }

        assertEquals(
            "deberian estar vivos los dos hilos de la bomba",
            hilosDeBase + 2, Vigia.cuantos(),
        )

        bomba.detener()

        val vivos = Vigia.cuantos() - hilosDeBase
        assertEquals(
            "detener() volvio con " + vivos + " hilo(s) de la bomba todavia vivos,\n" +
                "y quien llama cierra el USB en la linea siguiente:\n" +
                Vigia.volcado(),
            0, vivos,
        )
    }

    /**
     * GUARDA (hoy pasa): los hilos al menos acaban muriendo.
     *
     * No cubre el fallo —el problema es CUANDO vuelve `detener()`, no si el
     * hilo muere alguna vez— pero cierra la puerta a la regresion contraria:
     * un `vivo` mal puesto que dejara el hilo girando para siempre, quemando
     * CPU en un rk3326 mientras el dueno maneja.
     */
    @Test
    fun los_hilos_de_la_bomba_acaban_muriendo_dentro_del_plazo() {
        val canal = CanalUsbFalso(demoraMs = 25)
        val bomba = BombaHci(canal)
        assertTrue(bomba.arrancar())
        esperarAQue("la bomba empiece a transferir", 2_000) { canal.transferencias.get() >= 2 }

        bomba.detener()

        val quedan = Vigia.esperarAQueMueran(PLAZO_MUERTE_MS) - hilosDeBase
        assertEquals(
            "quedaron " + quedan + " hilos vivos " + PLAZO_MUERTE_MS +
                " ms despues de detener():\n" + Vigia.volcado(),
            0, quedan,
        )
    }

    /**
     * GUARDA (hoy pasa): una vez parada y cerrada, la bomba no vuelve sola.
     *
     * Nota honesta: `RadioBt` no se puede instanciar en la JVM (necesita un
     * `Context` y un `UsbManager` de Android), asi que aqui se reproduce su
     * secuencia sobre las mismas clases de produccion —`GestorL2cap`,
     * `BombaHci`, el cable— en vez de llamar a `RadioBt` en persona. Lo que NO
     * queda cubierto es su contador de usuarios; eso necesitaria una costura
     * en `RadioBt` que aqui no se ha tocado.
     */
    @Test
    fun la_bomba_parada_no_vuelve_a_arrancar_sola() {
        val canal = CanalUsbFalso(demoraMs = 5)
        val bomba = BombaHci(canal)
        assertTrue(bomba.arrancar())
        esperarAQue("arranque", 2_000) { canal.transferencias.get() >= 2 }
        bomba.detener()
        Vigia.esperarAQueMueran(PLAZO_MUERTE_MS)
        canal.cerrar()

        val antes = canal.transferencias.get()
        Thread.sleep(200)
        assertEquals(
            "la bomba siguio transfiriendo despues de parada y cerrada",
            antes, canal.transferencias.get(),
        )
    }

    // ------------------------------------------------------------------

    private fun esperarAQue(que: String, plazoMs: Long, cond: () -> Boolean) {
        val hasta = System.currentTimeMillis() + plazoMs
        while (System.currentTimeMillis() < hasta) {
            if (cond()) return
            Thread.sleep(5)
        }
        throw AssertionError("no se cumplio en " + plazoMs + " ms: " + que)
    }

    private companion object {
        const val REPETICIONES = 5

        /**
         * Una vuelta entera de la bomba puede costar
         * MAX_POR_VUELTA x (ESPERA_EVENTO_MS + ESPERA_ACL_MS) mas una
         * escritura. 3 s deja margen de sobra en una maquina cargada.
         */
        const val PLAZO_MUERTE_MS = 3_000L
    }
}
