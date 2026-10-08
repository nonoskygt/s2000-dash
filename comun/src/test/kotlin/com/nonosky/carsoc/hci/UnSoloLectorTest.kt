package com.nonosky.carsoc.hci

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UN SOLO hilo puede tocar el endpoint de eventos.
 *
 * El proyecto ya documentaba dos veces que dos lectores del mismo endpoint se
 * roban los paquetes —y el autor se habia quemado con ello: "RESET -> SIN
 * RESPUESTA", porque los eventos llegaban a la bomba que no preguntaba—. Aun
 * asi el defecto volvio a entrar por otra puerta al migrar el vigilante de la
 * bateria a la radio compartida: pedia las piezas a `RadioBt`, que ya tenia
 * `bomba-hci` leyendo en bucle, y montaba encima SU PROPIO bucle de
 * `leerEvento(400)` durante seis segundos, cada diez.
 *
 * Dos hilos dentro de un bulkTransfer sobre el mismo descriptor no es un bug
 * de aplicacion: es acceso concurrente a un recurso del kernel. Y ocurria
 * durante el barrido — justo cuando el dueño veia reiniciarse el radio "al
 * intentar conectar por Bluetooth".
 */
class UnSoloLectorTest {

    /**
     * La bomba sola nunca solapa transferencias.
     *
     * Es la prueba de regresion de verdad: si alguien vuelve a meter un
     * segundo lector dentro de la bomba, o si el bucle empieza a leer eventos
     * y ACL en paralelo sobre el mismo cable, esto se pone en rojo.
     */
    @Test
    fun la_bomba_sola_nunca_solapa_transferencias() {
        val cable = CanalUsbFalso(demoraMs = 5)
        repeat(300) { cable.entregarEvento(byteArrayOf(0x0E, 0x04, 0x01, 0x03, 0x0C, 0x00)) }

        val bomba = BombaHci(cable)
        assertTrue("la bomba tiene que arrancar", bomba.arrancar())
        Thread.sleep(1_200)
        bomba.detener()

        assertEquals(
            "La bomba llego a tener ${cable.maximoEnVuelo.get()} transferencias a la vez\n" +
                "sobre el mismo endpoint. Solo puede haber UNA.",
            1, cable.maximoEnVuelo.get(),
        )
    }

    /**
     * Y el detector detecta: si alguien mete un segundo lector, se ve.
     *
     * Sin esta prueba, la de arriba podria estar pasando porque el cable falso
     * no sabe contar, no porque el codigo sea correcto. Un centinela que nunca
     * dispara es indistinguible de uno averiado.
     */
    @Test
    fun el_cable_falso_delata_a_un_segundo_lector() {
        val cable = CanalUsbFalso(demoraMs = 20)
        repeat(300) { cable.entregarEvento(byteArrayOf(0x3E, 0x02, 0x01, 0x00)) }

        val arranca = CountDownLatch(2)
        val hasta = System.currentTimeMillis() + 900
        val dos = (1..2).map { n ->
            thread(name = "lector-$n", isDaemon = true) {
                arranca.countDown()
                while (System.currentTimeMillis() < hasta) {
                    runCatching { cable.leerEvento(200) }
                }
            }
        }
        arranca.await()
        dos.forEach { runCatching { it.join(3_000) } }

        assertTrue(
            "El cable falso no detecto el solapamiento aunque habia dos hilos\n" +
                "leyendo a la vez. Si no detecta esto, la prueba de la bomba no\n" +
                "demuestra nada.",
            cable.maximoEnVuelo.get() >= 2,
        )
    }
}
