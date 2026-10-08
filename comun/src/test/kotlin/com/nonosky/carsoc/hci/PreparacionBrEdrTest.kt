package com.nonosky.carsoc.hci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La etapa 0 del enlace clasico no puede resetear el controlador.
 *
 * Lo hacia, y el sintoma enganaba entero: el enlace BR/EDR se abria, el
 * L2CAP se abria, el RFCOMM se abria — y despues ni un `ATI` recibia
 * respuesta. La traza mostraba exito en cada escalon y silencio al final.
 *
 * La causa: la radio se COMPARTE. `HCI_Reset` borra el control de flujo del
 * controlador, pero el [ControlFlujoAcl] de la bomba se queda con los
 * contadores de antes. Desde ahi creemos tener creditos que el controlador ya
 * no reconoce y los paquetes salientes se caen sin ruido: el `ATI` nunca
 * llegaba a salir del dongle.
 *
 * Y de paso tiraba el enlace LE de la bateria en cada intento del motor.
 *
 * El reset vive en [RadioBt], una vez por apertura en frio, antes de que
 * exista ningun enlace ni ningun contador. Aqui, nunca.
 */
class PreparacionBrEdrTest {

    private class BombaDePapel(private val respuesta: (Int) -> ByteArray?) : ComandosHci {
        val opcodes = mutableListOf<Int>()
        override fun ejecutar(opcode: Int, parametros: ByteArray, timeoutMs: Long): ByteArray? {
            opcodes += opcode
            return respuesta(opcode)
        }
    }

    /** `Command Complete` con parametros de retorno: 0E | largo | num | opcode(2) | estado | ... */
    private fun completo(opcode: Int, estado: Int, retorno: ByteArray = ByteArray(0)) =
        byteArrayOf(
            0x0E, (3 + retorno.size + 1).toByte(), 0x01,
            (opcode and 0xFF).toByte(), ((opcode shr 8) and 0xFF).toByte(),
            estado.toByte(),
        ) + retorno

    @Test
    fun `preparar el enlace clasico NUNCA manda HCI Reset`() {
        val bomba = BombaDePapel { completo(it, 0) }

        EnlaceBrEdr.preparacion(bomba, usarSsp = true) {}

        assertFalse(
            "un reset aqui borra el control de flujo compartido y tumba la bateria; " +
                "el reset va en RadioBt, en la apertura en frio",
            bomba.opcodes.contains(HciUsb.CMD_RESET),
        )
        assertTrue(
            "y aun asi tiene que configurar la mascara de eventos: sin ella no hay SSP",
            bomba.opcodes.contains(HciBrEdr.CMD_SET_EVENT_MASK),
        )
    }

    @Test
    fun `el numero de buffers ACL se lee del campo correcto`() {
        // Read_Buffer_Size, tras el estado:
        //   acl(2) | sco(1) | numAcl(2) | numSco(2)
        // Este dongle: 1021 bytes, 8 paquetes ACL, 1 paquete SCO.
        val retorno = byteArrayOf(
            0xFD.toByte(), 0x03,   // 1021 bytes de ACL
            0x40,                  // 64 bytes de SCO
            0x08, 0x00,            // 8 buffers ACL   <- este
            0x01, 0x00,            // 1 buffer SCO    <- se leia este
        )
        val bomba = BombaDePapel {
            if (it == HciBrEdr.CMD_READ_BUFFER_SIZE) completo(it, 0, retorno) else completo(it, 0)
        }

        val r = EnlaceBrEdr.preparacion(bomba, usarSsp = true) {}

        assertEquals("el tamano de paquete ACL", 1021, r.bufferAcl)
        assertEquals(
            "8 buffers ACL, no 1: leer numSco por numAcl hacia que la traza " +
                "reportara '1 buffers' teniendo 8",
            8, r.creditosAcl,
        )
    }

    @Test
    fun `sin mascara de eventos se avisa pero se sigue`() {
        // El barrido BLE ya funcionaba con la mascara por defecto, asi que
        // esto no es fatal. Pero tiene que quedar escrito: sin mascara, el
        // emparejamiento seguro no da senales de vida y depurarlo a ciegas
        // cuesta una tarde.
        val dicho = mutableListOf<String>()
        val bomba = BombaDePapel {
            if (it == HciBrEdr.CMD_SET_EVENT_MASK) completo(it, 0x12) else completo(it, 0)
        }

        EnlaceBrEdr.preparacion(bomba, usarSsp = true) { dicho += it }

        assertTrue(
            "tendria que avisar del SSP: $dicho",
            dicho.any { it.contains("SSP") },
        )
    }
}
