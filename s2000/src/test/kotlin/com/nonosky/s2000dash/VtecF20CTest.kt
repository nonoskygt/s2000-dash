package com.nonosky.s2000dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El VTEC del F20C, con SUS numeros.
 *
 * Estos numeros SI estan confirmados: 5.850 rpm es el cruce que Honda
 * publica para el AP1.
 */
class VtecF20CTest {

    @Test
    fun `este motor engancha arriba, y por eso es un acontecimiento`() {
        assertEquals(5_850, MotorF20C.rpmVtec)
        assertFalse(MotorF20C.vtecActive(rpm = 5_849, loadPct = 100))
        assertTrue(MotorF20C.vtecActive(rpm = 5_850, loadPct = 60))
    }

    @Test
    fun `necesita revoluciones Y carga`() {
        // A bajo pedal no engancha aunque las rpm esten arriba: es el caso
        // que se ve al desacelerar en marcha corta.
        assertFalse(MotorF20C.vtecActive(rpm = 7_000, loadPct = 15))
        assertTrue(MotorF20C.vtecActive(rpm = 7_000, loadPct = 80))
    }

    @Test
    fun `a cinco mil no engancha ni a fondo`() {
        // El cruce de este motor esta arriba: a 5.000 rpm, ni con el
        // pedal a fondo.
        assertFalse(MotorF20C.vtecActive(rpm = 5_000, loadPct = 80))
    }

    @Test
    fun `la carga corta por debajo del sesenta por ciento`() {
        assertFalse(MotorF20C.vtecActive(rpm = 7_000, loadPct = 59))
        assertTrue(MotorF20C.vtecActive(rpm = 7_000, loadPct = 60))
    }

    @Test
    fun `el perfil dice que este carro NO puede dar AFR real`() {
        // Sonda de banda ESTRECHA y mapa de PIDs cortado en 0x20: no hay
        // 0134. El reloj de mezcla se dibuja apagado, que es distinto de
        // no dibujarlo — y muy distinto de inventarle un numero.
        assertFalse(PerfilS2000.tieneAfrReal)
        assertTrue(PerfilS2000.vtecEsAcontecimiento)
        assertFalse("no es casa rodante", PerfilS2000.esCasaRodante)
        assertFalse("un solo banco", PerfilS2000.tieneBancoVivienda)
        assertFalse("sin nevera", PerfilS2000.tieneNevera)
    }

    @Test
    fun `los umbrales del F20C mantienen su orden`() {
        // Si alguien ajusta una cifra, esto evita dejar la caratula en un
        // estado imposible (zona roja antes del ambar, etc).
        assertTrue(MotorF20C.rpmVtec < MotorF20C.rpmShiftAmber)
        assertTrue(MotorF20C.rpmShiftAmber < MotorF20C.rpmRedline)
        assertTrue(MotorF20C.rpmRedline <= MotorF20C.rpmFuelCut)
        assertTrue(MotorF20C.rpmFuelCut <= MotorF20C.rpmMax)
        // Y la suelta del VTEC va por DEBAJO del enganche, o la histeresis no
        // seria histeresis sino un parpadeo garantizado.
        assertTrue(MotorF20C.rpmVtecSuelta < MotorF20C.rpmVtec)
    }
}
