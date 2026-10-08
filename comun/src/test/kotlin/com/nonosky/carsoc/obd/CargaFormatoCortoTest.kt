package com.nonosky.carsoc.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El 0104 de esta ECU llega sin el byte del PID.
 *
 * Medido en el carro: contesta `414B` en vez de `41044B`. Sin tolerarlo, la
 * carga sale vacia para siempre y con ella se cae la deteccion del VTEC.
 */
class CargaFormatoCortoTest {

    @Test
    fun `el formato normal sigue mandando`() {
        assertEquals(29, PidDecoder.decodeLoad("41044B"))
    }

    @Test
    fun `se acepta el formato corto que manda esta ECU`() {
        // 0x4B = 75 -> 75*100/255 = 29%, una carga tipica de ralenti.
        assertEquals(29, PidDecoder.decodeLoad("414B"))
    }

    @Test
    fun `no se traga la respuesta de otro PID`() {
        // 410E88 es el avance: seis digitos, no cuatro. Si esto colara, la
        // carga mostraria un numero inventado sacado del encendido.
        assertNull(PidDecoder.decodeLoad("410E88"))
        assertNull(PidDecoder.decodeLoad("41067A"))
    }

    @Test
    fun `la basura no pasa`() {
        assertNull(PidDecoder.decodeLoad("NODATA"))
        assertNull(PidDecoder.decodeLoad(""))
        assertNull(PidDecoder.decodeLoad("7F0412"))
    }
}
