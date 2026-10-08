package com.nonosky.carsoc.diag

import com.nonosky.carsoc.diag.TablaDtc.Entrada
import com.nonosky.carsoc.diag.TablaDtc.Gravedad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuscarDtcTest {

    private val tabla = listOf(
        Entrada("P0420", Gravedad.LEVE, "Catalizador gastado", "El catalizador ya no limpia.", "Catalizador viejo"),
        Entrada("P0700", Gravedad.ATENCION, "La caja automática pide revisión", "Mira los P0730 a P0734.", "Aceite de la caja"),
        Entrada("P0731", Gravedad.GRAVE, "Patina el embrague de 1ª", "La caja patina.", "Aceite quemado"),
        Entrada("P0134", Gravedad.ATENCION, "Sonda de oxígeno sin actividad", "La sonda no responde.", "Sonda vieja"),
    )

    private fun codigos(q: String) = BuscarDtc.filtrar(tabla, q).map { it.codigo }

    @Test
    fun `sin texto salen todos`() {
        assertEquals(4, codigos("").size)
        assertEquals(4, codigos("   ").size)
    }

    @Test
    fun `por codigo primero los que empiezan asi`() {
        // P0700 menciona P0730 en su explicacion, pero el que se busca es P0731.
        assertEquals(listOf("P0731"), codigos("p0731"))
        assertEquals("P0700", codigos("p07").first())
        assertTrue(codigos("p07").containsAll(listOf("P0700", "P0731")))
    }

    @Test
    fun `por palabras sin tildes ni mayusculas`() {
        assertEquals(listOf("P0134"), codigos("OXIGENO"))
        assertEquals(listOf("P0700", "P0731"), codigos("caja"))
        assertEquals(listOf("P0731"), codigos("aceite quemado"))
        assertTrue(codigos("nada que ver").isEmpty())
    }
}
