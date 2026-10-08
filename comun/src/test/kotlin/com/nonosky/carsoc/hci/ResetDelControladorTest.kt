package com.nonosky.carsoc.hci

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * El dongle NO se entera de que el proceso murio.
 *
 * Cuando Android reemplaza el APK, mata el proceso y arranca otro. La
 * interfaz USB se libera, pero el controlador Bluetooth conserva sus enlaces:
 * nadie le mando nada. El proceso nuevo reclama el aparato, lo encuentra
 * sano, pide la conexion con la bateria — y recibe 0x0B, `ACL Connection
 * Already Exists`, contra un enlace de un proceso que ya no existe y que por
 * lo tanto nadie va a cerrar jamas.
 *
 * Se vio en el carro: tras cada actualizacion la bateria decia "no se pudo
 * abrir el canal GATT" hasta desconectar el dongle a mano.
 *
 * La cura es el primer comando de cualquier pila Bluetooth, que aqui faltaba:
 * `HCI Reset` en la apertura en frio.
 */
class ResetDelControladorTest {

    /** Bomba de mentira: apunta los opcodes y contesta lo que se le diga. */
    private class BombaDePapel(
        private val respuesta: (Int) -> ByteArray?,
    ) : ComandosHci {
        val opcodes = mutableListOf<Int>()
        override fun ejecutar(opcode: Int, parametros: ByteArray, timeoutMs: Long): ByteArray? {
            opcodes += opcode
            return respuesta(opcode)
        }
    }

    /** `Command Complete` del reset: 0E | largo | num | opcode(2) | estado. */
    private fun completo(opcode: Int, estado: Int) = byteArrayOf(
        0x0E, 0x04, 0x01,
        (opcode and 0xFF).toByte(), ((opcode shr 8) and 0xFF).toByte(),
        estado.toByte(),
    )

    @Before
    fun sinDormir() {
        RadioBt.reposoTrasReset = 0L
    }

    @After
    fun devolverElReposo() {
        RadioBt.reposoTrasReset = 300L
    }

    @Test
    fun `la apertura en frio manda HCI Reset`() {
        val bomba = BombaDePapel { completo(it, 0) }

        val dicho = RadioBt.reiniciarControlador(bomba)

        assertEquals(
            "el reset tiene que ser el opcode 0x0C03 y nada mas",
            listOf(HciUsb.CMD_RESET), bomba.opcodes,
        )
        assertTrue("deberia decir que reinicio: $dicho", dicho.contains("reiniciado"))
    }

    @Test
    fun `un reset rechazado se reporta y no se traga`() {
        // 0x12 = Invalid HCI Command Parameters. Da igual cual: lo que no
        // puede pasar es que un rechazo pase por exito y luego la bateria
        // falle sin que la traza diga por que.
        val bomba = BombaDePapel { completo(it, 0x12) }

        val dicho = RadioBt.reiniciarControlador(bomba)

        assertTrue("tendria que nombrar el rechazo: $dicho", dicho.contains("rechazo"))
        assertTrue("y el estado: $dicho", dicho.contains("18"))
    }

    @Test
    fun `sin respuesta al reset no se rompe la apertura`() {
        // Un dongle mudo es malo, pero peor es dejar el tablero entero a
        // oscuras: el TPMS y la pantalla no dependen de este comando.
        val bomba = BombaDePapel { null }

        val dicho = RadioBt.reiniciarControlador(bomba)

        assertTrue("deberia decir que no contesto: $dicho", dicho.contains("sin respuesta"))
    }

    @Test
    fun `una respuesta truncada no revienta el hilo de apertura`() {
        // El endpoint de eventos de este dongle entrega 16 bytes por paquete
        // y ya hubo un fallo por leer un evento a medias. Aqui se comprueba
        // que un Command Complete corto se rechaza sin lanzar: una excepcion
        // suelta en este hilo se lleva el proceso entero.
        val bomba = BombaDePapel { byteArrayOf(0x0E, 0x04, 0x01) }

        val dicho = RadioBt.reiniciarControlador(bomba)

        assertTrue("deberia hablar de respuesta corta: $dicho", dicho.contains("corta"))
    }
}
