package com.nonosky.carsoc.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TablaSrsTest {

    private val t = "\t"
    private val ejemplo = sequenceOf(
        "# comentario",
        "> Paso uno",
        "! Cuidado",
        "1-1${t}11-01${t}grave${t}Bolsa del conductor${t}Circuito abierto${t}Explicacion${t}Revisar",
        "2-3${t}23-01${t}atencion${t}Cinturon${t}Otro${t}Explicacion${t}Revisar",
        "linea${t}rota",
    )

    @Test
    fun separaPasosAdvertenciasYCodigos() {
        val tabla = TablaSrs.parsear(ejemplo)
        assertEquals(listOf("Paso uno"), tabla.pasos)
        assertEquals(listOf("Cuidado"), tabla.advertencias)
        assertEquals(2, tabla.codigos.size)
        assertEquals(TablaDtc.Gravedad.GRAVE, tabla.codigos[0].gravedad)
    }

    @Test
    fun seEncuentraComoLoEscribaElDueno() {
        val tabla = TablaSrs.parsear(ejemplo)
        for (q in listOf("1-1", "11", "1 1", "11-01", "1101")) {
            assertEquals(q, "Bolsa del conductor", tabla.buscar(q).single().componente)
        }
        assertEquals("Cinturon", tabla.buscar("23").single().componente)
        assertEquals("Cinturon", tabla.buscar("2").single().componente)
        assertTrue(tabla.buscar("99").isEmpty())
        assertTrue(tabla.buscar("").isEmpty())
    }
}
