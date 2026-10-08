package com.nonosky.carsoc.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La ausencia de respuesta NO es una respuesta.
 *
 * Se descubrio pidiendo los PIDs con el carro apagado: el ELM327 contesto
 * `SEARCHING...` a secas. Esa palabra no estaba en la lista de errores y
 * ninguna linea empezaba por `43`, asi que la lista salia vacia, nadie
 * detectaba fallo, y la pantalla habria dicho SIN AVERIAS.
 *
 * Es el peor fallo imaginable en una pantalla de averias: decirle al dueño
 * que su carro esta sano sin haberle hablado. Estas pruebas lo clavan.
 */
class DtcSinRespuestaTest {

    @Test
    fun `SEARCHING a secas no es un carro sano`() {
        assertFalse(Dtc.sinCodigos("SEARCHING..."))
        assertTrue(Dtc.esFalloDeEnlace("SEARCHING..."))
    }

    @Test
    fun `BUS INIT a secas tampoco`() {
        // El banner del arranque del bus, sin la respuesta detras.
        assertFalse(Dtc.sinCodigos("BUS INIT: ...OK"))
        assertTrue(Dtc.esFalloDeEnlace("BUS INIT: ...OK"))
    }

    @Test
    fun `una respuesta vacia tampoco`() {
        assertFalse(Dtc.sinCodigos(""))
        assertTrue(Dtc.esFalloDeEnlace(""))
    }

    @Test
    fun `el eco del comando solo tampoco`() {
        assertFalse(Dtc.sinCodigos("03"))
        assertTrue(Dtc.esFalloDeEnlace("03"))
    }

    /**
     * Y lo contrario: las dos formas legitimas de decir "sano" siguen
     * contando como sano. Apretar el filtro no puede romper esto.
     */
    @Test
    fun `la ECU contestando cero codigos sigue siendo sano`() {
        assertTrue(Dtc.sinCodigos("43000000000000"))
        assertFalse(Dtc.esFalloDeEnlace("43000000000000"))
    }

    @Test
    fun `NO DATA sigue siendo sano`() {
        assertTrue(Dtc.sinCodigos("NO DATA"))
        assertFalse(Dtc.esFalloDeEnlace("NO DATA"))
    }

    @Test
    fun `SEARCHING seguido de la respuesta de verdad SI vale`() {
        // Lo normal: el banner y detras los datos. Eso es una respuesta.
        val crudo = "SEARCHING...\r43030100000000\r>"
        assertFalse(Dtc.esFalloDeEnlace(crudo))
        assertFalse(Dtc.sinCodigos(crudo))
        assertTrue(Dtc.leerLista(crudo, Dtc.Tipo.GUARDADOS).any { it.texto == "P0301" })
    }

    @Test
    fun `SEARCHING seguido de cero codigos es sano`() {
        val crudo = "SEARCHING...\r43000000000000\r>"
        assertFalse(Dtc.esFalloDeEnlace(crudo))
        assertTrue(Dtc.sinCodigos(crudo))
    }
}
