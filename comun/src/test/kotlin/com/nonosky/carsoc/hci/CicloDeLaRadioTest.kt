package com.nonosky.carsoc.hci

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Mil ciclos de abrir y soltar la radio: ni un descriptor ni un hilo de mas.
 *
 * ### Por que mil y no uno
 *
 * Porque asi es como se usa. `RadioBt` abre el dongle por conteo de
 * referencias y lo cierra cuando el ultimo consumidor lo suelta, y hay tres
 * consumidores con ritmos distintos: el vigilante de la bateria barre cada
 * 30 s, el lector de OBD abre y cierra por turnos, y las rutas de diagnostico
 * por HTTP lo piden cuando alguien mira. En una tarde de manejo eso son
 * cientos de ciclos de apertura y cierre. Un descriptor que se fuga una vez
 * de cada cien no se ve en una prueba manual; se ve a la hora y media, en la
 * carretera, cuando el radio deja de contestar al ping.
 *
 * Es exactamente la diferencia entre este aparato y el receptor TPMS: el
 * CH340 tiene UN dueno y no se abre ni se cierra nunca, y con solo el TPMS el
 * tablero corrio horas sin fallar.
 *
 * ### Limitacion honesta de esta prueba
 *
 * `RadioBt` es un `object` que necesita un `Context` y un `UsbManager` de
 * Android, asi que no se puede llamar desde la JVM. Lo que se ejercita aqui
 * es la **misma secuencia de ciclo de vida sobre las mismas clases de
 * produccion** (`BombaHci`, `GestorL2cap`, y el cable), en el orden exacto de
 * `RadioBt.soltar()`. Lo que NO queda cubierto es el contador de usuarios de
 * `RadioBt`; para eso haria falta una costura (una fabrica de piezas
 * sustituible) que aqui no se ha metido.
 */
class CicloDeLaRadioTest {

    private var hilosDeBase = 0

    @Before
    fun apuntarLaBase() {
        Vigia.esperarAQueMueran(3_000)
        hilosDeBase = Vigia.cuantos()
    }

    @After
    fun noDejarNadaCorriendo() {
        Vigia.esperarAQueMueran(5_000)
    }

    /**
     * GUARDA (hoy pasa): tras mil ciclos, cero conexiones abiertas, cero
     * hilos vivos, y los hilos no se acumularon por el camino.
     *
     * Caza la fuga de descriptores y la de hilos: un `detener()` que dejara
     * de poner `vivo = false`, un `cerrar()` que se saltara una rama, o un
     * hilo que se creara dentro del bucle sin morirse. Cualquiera de las tres
     * se come el radio en minutos.
     */
    @Test
    fun mil_ciclos_no_dejan_descriptores_abiertos_ni_hilos_vivos() {
        val canales = ArrayList<CanalUsbFalso>(CICLOS)
        var picoHilos = 0

        repeat(CICLOS) { i ->
            val canal = CanalUsbFalso(demoraMs = 1)
            val bomba = BombaHci(canal)
            val gestor = GestorL2cap(bomba)
            assertTrue("ciclo $i: la bomba no arranco", bomba.arrancar())
            gestor.arrancar()
            esperarAQue("ciclo $i: la bomba gire una vuelta", 2_000) {
                canal.transferencias.get() >= 1
            }
            // El orden EXACTO de RadioBt.soltar().
            gestor.detener()
            bomba.detener()
            canal.cerrar()

            canales += canal
            // Muestrear cuesta; cada 20 ciclos basta para ver una acumulacion.
            if (i % 20 == 0) {
                val vivos = Vigia.cuantos() - hilosDeBase
                if (vivos > picoHilos) picoHilos = vivos
            }
        }

        val abiertos = canales.count { it.abierto }
        assertEquals("quedaron $abiertos cables sin cerrar de $CICLOS", 0, abiertos)

        val quedan = Vigia.esperarAQueMueran(10_000) - hilosDeBase
        assertEquals(
            "quedaron $quedan hilos de la radio vivos tras $CICLOS ciclos:\n" + Vigia.volcado(),
            0, quedan,
        )

        // Con un `detener()` que espera a sus hilos el pico seria 2 (los de la
        // radio en curso). Sin esperar, los moribundos se solapan. El tope es
        // generoso a proposito: lo que se quiere cazar es la acumulacion sin
        // fin, no un solape de dos ciclos.
        assertTrue(
            "los hilos de la bomba se acumularon: pico de $picoHilos vivos a la vez",
            picoHilos <= PICO_TOLERADO,
        )
    }

    /**
     * CAZA: uso-despues-de-cerrar acumulado en mil ciclos.
     *
     * FALLA HOY A PROPOSITO. Es el mismo defecto de
     * [BombaHciCierreTest.cerrar_la_radio_no_puede_pillar_a_la_bomba_dentro_de_una_transferencia],
     * medido a la escala a la que ocurre de verdad: cada ciclo es una
     * oportunidad, y en una tarde de manejo hay cientos.
     */
    @Test
    fun mil_ciclos_no_transfieren_ni_una_vez_sobre_un_cable_cerrado() {
        var delitos = 0L
        var ciclosSucios = 0
        var ejemplo = ""

        repeat(CICLOS) { i ->
            val canal = CanalUsbFalso(demoraMs = 2)
            val bomba = BombaHci(canal)
            val gestor = GestorL2cap(bomba)
            assertTrue(bomba.arrancar())
            gestor.arrancar()
            esperarAQue("ciclo $i: la bomba gire una vuelta", 2_000) {
                canal.transferencias.get() >= 1
            }
            gestor.detener()
            bomba.detener()
            canal.cerrar()

            val d = canal.delitosContados.get()
            if (d > 0) {
                delitos += d
                ciclosSucios++
                if (ejemplo.isEmpty()) ejemplo = canal.resumen()
            }
        }

        assertEquals(
            "$ciclosSucios de $CICLOS ciclos tocaron el USB con el descriptor ya\n" +
                "cerrado (o lo cerraron con una transferencia dentro): $delitos veces.\n" +
                "Ejemplo:\n" + ejemplo,
            0L, delitos,
        )
    }

    // ------------------------------------------------------------------

    private fun esperarAQue(que: String, plazoMs: Long, cond: () -> Boolean) {
        val hasta = System.currentTimeMillis() + plazoMs
        while (System.currentTimeMillis() < hasta) {
            if (cond()) return
            Thread.sleep(1)
        }
        throw AssertionError("no se cumplio en $plazoMs ms: $que")
    }

    private companion object {
        const val CICLOS = 1_000
        const val PICO_TOLERADO = 24
    }
}
