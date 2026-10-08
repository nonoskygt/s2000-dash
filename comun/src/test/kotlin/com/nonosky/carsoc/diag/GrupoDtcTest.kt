package com.nonosky.carsoc.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un codigo que no esta en la tabla del carro igual tiene que decir DE QUE
 * trata. Aqui se fija que cada familia caiga en su sistema.
 */
class GrupoDtcTest {

    private fun titulo(c: String) = GrupoDtc.de(c)!!.titulo

    @Test
    fun `el sistema sale de la tercera posicion`() {
        assertEquals("Mezcla de aire y combustible", titulo("P0101"))
        assertEquals("Inyectores y combustible", titulo("P0201"))
        assertEquals("Encendido", titulo("P0351"))
        assertEquals("Control de emisiones", titulo("P0401"))
        assertEquals("Velocidad, ralentí y entradas auxiliares", titulo("P0562"))
        assertEquals("Computadora y sus salidas", titulo("P0601"))
        assertEquals("Caja de cambios", titulo("P0741"))
        assertEquals("Caja de cambios", titulo("P0841"))
        assertEquals("Caja de cambios", titulo("P0962"))
        assertEquals("Sistema híbrido", titulo("P0A80"))
    }

    @Test
    fun `los del fabricante y los P2 siguen la misma regla`() {
        assertEquals("Caja de cambios", titulo("P1705"))
        assertEquals("Encendido", titulo("P1361"))
        assertEquals("Caja de cambios", titulo("P2703"))
        assertEquals("Encendido", titulo("P2300"))
        assertEquals("Mezcla de aire y combustible", titulo("P2A01"))
        assertTrue(GrupoDtc.de("P1705")!!.explicacion.contains("fabricante"))
        assertTrue(GrupoDtc.de("P0741")!!.explicacion.contains("estándar"))
    }

    @Test
    fun `fuera del motor tambien se dice el area`() {
        assertEquals("Carrocería", titulo("B0010"))
        assertEquals("Chasis: frenos, dirección y suspensión", titulo("C0035"))
        assertEquals("Comunicación entre computadoras", titulo("U0100"))
    }

    @Test
    fun `lo que no tiene forma de codigo no se inventa`() {
        assertNull(GrupoDtc.de(""))
        assertNull(GrupoDtc.de("P07"))
        assertNull(GrupoDtc.de("X0700"))
        assertNull(GrupoDtc.de("P4700"))
        assertNull(GrupoDtc.de("P07000"))
        // En minusculas o con espacios es el mismo codigo.
        assertEquals("Caja de cambios", titulo(" p0741 "))
    }

    @Test
    fun `cada grupo trae que hacer`() {
        for (c in listOf("P0101", "P0201", "P0351", "P0401", "P0562", "P0601", "P0741",
            "P0A80", "P3400", "B0010", "C0035", "U0100")) {
            val g = GrupoDtc.de(c)
            assertNotNull(c, g)
            assertTrue(c, g!!.queHacer.isNotBlank() && g.explicacion.isNotBlank())
        }
    }
}
