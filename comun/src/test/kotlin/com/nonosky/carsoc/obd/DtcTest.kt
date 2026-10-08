package com.nonosky.carsoc.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del decodificador de codigos de averia.
 *
 * Los ejemplos crudos son los que devuelve de verdad un ELM327 en ISO 9141-2,
 * no inventados: incluyen el banner del arranque del bus, el eco del comando
 * de los clones que ignoran ATE0, el relleno de la ultima trama y las dos
 * formas legitimas de decir "no hay codigos".
 */
class DtcTest {

    // --- Los dos bytes a texto -------------------------------------------

    @Test
    fun `decodifica un fallo de encendido`() {
        // 0x03 0x01 -> P (bits 00), digito 0, digito 3, byte 01
        assertEquals("P0301", Dtc.decodificar(0x03, 0x01)?.texto)
    }

    @Test
    fun `decodifica un codigo de Honda`() {
        assertEquals("P1259", Dtc.decodificar(0x12, 0x59)?.texto)
    }

    @Test
    fun `las cuatro letras salen de los dos bits altos`() {
        assertEquals('P', Dtc.decodificar(0x01, 0x00)?.sistema)
        assertEquals('C', Dtc.decodificar(0x41, 0x00)?.sistema)
        assertEquals('B', Dtc.decodificar(0x81, 0x00)?.sistema)
        assertEquals('U', Dtc.decodificar(0xC1, 0x00)?.sistema)
    }

    @Test
    fun `los digitos hexadecimales se muestran como tales`() {
        // 0x01 0xAB -> P01AB. No se convierte a decimal: son nibbles.
        assertEquals("P01AB", Dtc.decodificar(0x01, 0xAB)?.texto)
    }

    /** `0000` es el RELLENO de la ultima trama, no una averia. */
    @Test
    fun `cero cero no es un codigo`() {
        assertNull(Dtc.decodificar(0x00, 0x00))
    }

    // --- La respuesta entera ---------------------------------------------

    @Test
    fun `un solo codigo con el resto de relleno`() {
        val r = Dtc.leerLista("43030100000000", Dtc.Tipo.GUARDADOS)
        assertEquals(listOf("P0301"), r.map { it.texto })
    }

    @Test
    fun `tres codigos en una trama`() {
        val r = Dtc.leerLista("43030101331259", Dtc.Tipo.GUARDADOS)
        assertEquals(listOf("P0301", "P0133", "P1259"), r.map { it.texto })
    }

    /**
     * Con mas de tres codigos la ECU manda VARIAS tramas, y cada una repite
     * el 43 delante. Si el parser solo mirara la primera linea, el dueño
     * tendria cuatro averias y veria tres — sin ningun aviso de que falta una.
     */
    @Test
    fun `cuatro codigos llegan en dos tramas`() {
        val crudo = "43030101331259\r43042000000000\r"
        val r = Dtc.leerLista(crudo, Dtc.Tipo.GUARDADOS)
        assertEquals(listOf("P0301", "P0133", "P1259", "P0420"), r.map { it.texto })
    }

    /**
     * ESTE es el que justifica todo el archivo.
     *
     * P0143 se codifica literalmente `0143`, asi que la trama que lo contiene
     * lleva un `43` en la posicion 4. Un parser que buscara el prefijo con
     * indexOf engancharia ese segundo `43` y decodificaria basura como si
     * fueran averias del carro. Anclado al principio, no pasa.
     */
    @Test
    fun `un codigo que contiene 43 no confunde al parser`() {
        val r = Dtc.leerLista("43014300000000", Dtc.Tipo.GUARDADOS)
        assertEquals(listOf("P0143"), r.map { it.texto })
    }

    @Test
    fun `el banner del arranque del bus no estorba`() {
        val crudo = "03\rBUS INIT: ...OK\r43030100000000\r\r>"
        assertEquals(listOf("P0301"), Dtc.leerLista(crudo, Dtc.Tipo.GUARDADOS).map { it.texto })
    }

    @Test
    fun `el eco del comando de los clones no cuenta`() {
        // Un clon que ignora ATE0 devuelve el "03" antes de la respuesta.
        val crudo = "03\r03\r43 03 01 00 00 00 00\r>"
        assertEquals(listOf("P0301"), Dtc.leerLista(crudo, Dtc.Tipo.GUARDADOS).map { it.texto })
    }

    @Test
    fun `los pendientes usan su propio prefijo`() {
        // Modo 07 -> 47. Con el prefijo del modo 03 no debe encontrar nada.
        val crudo = "47030100000000"
        assertEquals(listOf("P0301"), Dtc.leerLista(crudo, Dtc.Tipo.PENDIENTES).map { it.texto })
        assertTrue(Dtc.leerLista(crudo, Dtc.Tipo.GUARDADOS).isEmpty())
    }

    @Test
    fun `no se repite un codigo que llega dos veces`() {
        val crudo = "43030100000000\r43030100000000\r"
        assertEquals(1, Dtc.leerLista(crudo, Dtc.Tipo.GUARDADOS).size)
    }

    // --- Sano contra averiado --------------------------------------------

    /**
     * Las dos formas legitimas de decir "tu carro esta bien". Confundir
     * cualquiera de las dos con un fallo del adaptador significa decirle al
     * dueño que su dongle no sirve justo cuando la noticia era buena.
     */
    @Test
    fun `la ECU contestando cero codigos es carro sano`() {
        assertTrue(Dtc.sinCodigos("43000000000000"))
        assertFalse(Dtc.esFalloDeEnlace("43000000000000"))
    }

    @Test
    fun `NO DATA tambien es carro sano`() {
        assertTrue(Dtc.sinCodigos("NO DATA"))
        assertFalse(Dtc.esFalloDeEnlace("NO DATA"))
    }

    @Test
    fun `un fallo de enlace de verdad si se distingue`() {
        assertTrue(Dtc.esFalloDeEnlace("UNABLE TO CONNECT"))
        assertTrue(Dtc.esFalloDeEnlace("BUS ERROR"))
        assertTrue(Dtc.esFalloDeEnlace(null))
    }

    @Test
    fun `con codigos de verdad no se dice que este sano`() {
        assertFalse(Dtc.sinCodigos("43030100000000"))
    }
}
