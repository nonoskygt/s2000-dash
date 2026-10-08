package com.nonosky.carsoc.nevera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El protocolo de la nevera, contra capturas reales.
 *
 * La trama de estado y las dos estaticas salen de la ingenieria inversa
 * publicada, y el checksum se comprobo aritmeticamente antes de escribir una
 * linea de codigo. Si estas pruebas pasan, el decodificador entiende lo que
 * la nevera dice de verdad — no lo que yo creo que dice.
 */
class AlpicoolTest {

    private fun hex(s: String): ByteArray =
        s.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `la consulta es la trama estatica documentada`() {
        // fe fe 03 01 02 00 — suma FE+FE+03+01 = 0x0200
        assertEquals("fe fe 03 01 02 00",
            Alpicool.consulta().joinToString(" ") { "%02x".format(it) })
    }

    @Test
    fun `el checksum es una suma, no un CRC`() {
        val t = hex("fe fe 03 01")
        assertEquals(0x0200, Alpicool.checksum(t))
    }

    @Test
    fun `decodifica la captura real publicada`() {
        // Trama capturada de una nevera real. Suma de los 22 bytes previos =
        // 1388 = 0x056C, que coincide con los dos ultimos bytes.
        val t = hex(
            "fe fe 15 01 00 01 00 00 f1 14 ec 02 00 00 00 00 " +
                "00 00 f3 64 0c 03 05 6c"
        )
        val e = Alpicool.decodificar(t)
        assertNotNull(e)
        e!!
        assertTrue("deberia estar encendida", e.encendida)
        assertEquals("consigna -15 C", -15, e.consigna)
        assertEquals("temperatura actual -13 C", -13, e.actual)
        assertEquals("minima seleccionable -20", -20, e.minima)
        assertEquals("maxima seleccionable 20", 20, e.maxima)
        assertEquals("histeresis 2", 2, e.histeresis)
        assertTrue("la nevera esta en Celsius", e.unidadCelsius)
        assertEquals("12.3 V de entrada", 12.3f, e.voltaje!!, 0.01f)
    }

    @Test
    fun `una trama con checksum malo NO se interpreta`() {
        // Misma trama con el ultimo byte cambiado. Interpretarla seria pintar
        // una temperatura que la nevera no dijo.
        val t = hex(
            "fe fe 15 01 00 01 00 00 f1 14 ec 02 00 00 00 00 " +
                "00 00 f3 64 0c 03 05 6d"
        )
        assertNull(Alpicool.decodificar(t))
    }

    @Test
    fun `la consigna se emite con el largo COHERENTE, no con el de la app`() {
        // La app de fabrica manda `fe fe 03 05 ec 02 f1`: largo 0x03 con un
        // checksum calculado como si fuera 0x04. Se emite la version buena.
        val t = Alpicool.fijarConsigna(-20)
        assertEquals("fe fe 04 05 ec 02 f1",
            t.joinToString(" ") { "%02x".format(it) })
    }

    @Test
    fun `separa dos tramas pegadas en una sola notificacion`() {
        // Al mandar un SET la nevera contesta con el eco y el estado juntos.
        val eco = Alpicool.fijarConsigna(-20)
        val estado = hex(
            "fe fe 15 01 00 01 00 00 f1 14 ec 02 00 00 00 00 " +
                "00 00 f3 64 0c 03 05 6c"
        )
        val juntas = eco + estado
        val (tramas, consumidos) = Alpicool.partir(juntas, juntas.size)
        assertEquals("deberian salir las dos", 2, tramas.size)
        assertEquals(juntas.size, consumidos)
        assertNotNull("la segunda es el estado", Alpicool.decodificar(tramas[1]))
    }

    @Test
    fun `una trama partida a la mitad espera al resto`() {
        // Con MTU 23 caben 20 bytes utiles: una trama de 24 llega en dos.
        val estado = hex(
            "fe fe 15 01 00 01 00 00 f1 14 ec 02 00 00 00 00 " +
                "00 00 f3 64 0c 03 05 6c"
        )
        val (tramas, consumidos) = Alpicool.partir(estado, 20)
        assertTrue("no debe entregar nada a medias", tramas.isEmpty())
        assertEquals("y no debe consumir nada", 0, consumidos)

        val (completas, _) = Alpicool.partir(estado, estado.size)
        assertEquals(1, completas.size)
    }

    @Test
    fun `tira la basura anterior a la cabecera`() {
        val estado = hex(
            "fe fe 15 01 00 01 00 00 f1 14 ec 02 00 00 00 00 " +
                "00 00 f3 64 0c 03 05 6c"
        )
        val conRuido = hex("aa bb cc") + estado
        val (tramas, _) = Alpicool.partir(conRuido, conRuido.size)
        assertEquals(1, tramas.size)
        assertEquals(-13, Alpicool.decodificar(tramas[0])!!.actual)
    }

    @Test
    fun `el compresor se deduce, y apagada no se inventa`() {
        val base = Alpicool.Estado(
            encendida = true, consigna = -15, actual = -13,
            minima = -20, maxima = 20, histeresis = 2,
            unidadCelsius = true, voltaje = 12.3f, modoEco = false,
        )
        // -13 > -15+2 = -13 -> falso: justo en el limite NO arranca
        assertEquals(false, base.compresorEnMarcha())
        // un grado por encima si
        assertEquals(true, base.copy(actual = -12).compresorEnMarcha())
        // en consigna, parado
        assertEquals(false, base.copy(actual = -15).compresorEnMarcha())
        // apagada: no esta en marcha, y no se especula
        assertEquals(false, base.copy(encendida = false, actual = 20).compresorEnMarcha())
    }

    @Test
    fun `una temperatura imposible se descarta`() {
        // Byte 0x0E puesto a 0x50 = 80 grados: fuera del rango de una nevera.
        val t = hex(
            "fe fe 15 01 00 01 00 00 f1 14 ec 02 00 00 00 00 " +
                "00 00 50 64 0c 03 04 c9"
        )
        assertNull(Alpicool.decodificar(t))
    }
}
