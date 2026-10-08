package com.nonosky.carsoc.bateria

import com.nonosky.carsoc.nevera.LectorNevera
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Un aparato que no aparece se deja descansar, pero sin perderlo de vista:
 * el periodo normal mientras los fallos son los de siempre en BLE, y luego
 * cada vez mas espacio, con techo de 5 minutos.
 */
class RetrocesoTest {

    @Test
    fun losBancosAguantanDosFallosAntesDeEspaciar() {
        assertEquals(20_000L, BancosBateria.esperaTras(0))
        assertEquals(20_000L, BancosBateria.esperaTras(2))
        assertEquals(40_000L, BancosBateria.esperaTras(3))
        assertEquals(80_000L, BancosBateria.esperaTras(4))
        assertEquals(300_000L, BancosBateria.esperaTras(50))
    }

    @Test
    fun laNeveraEspaciaHastaCincoMinutos() {
        assertEquals(30_000L, LectorNevera.esperaTras(0))
        assertEquals(30_000L, LectorNevera.esperaTras(1))
        assertEquals(60_000L, LectorNevera.esperaTras(2))
        assertEquals(240_000L, LectorNevera.esperaTras(4))
        assertEquals(300_000L, LectorNevera.esperaTras(50))
    }
}
