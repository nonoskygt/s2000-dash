package com.nonosky.carsoc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Los valores derivados de §7. Ninguno consume lecturas del K-line. */
class DerivedValuesTest {

    @Before
    fun carro() = CarroDePrueba.instalar()

    // ⚠️ Aqui solo van las pruebas que valen para LOS DOS CARROS.
    //
    // Las que afirman un numero concreto —"engancha a 2200"— son
    // afirmaciones sobre UN motor, no sobre el codigo, y viven en
    // la app de cada carro. Tenerlas aqui hacia que un sabor con otro motor
    // fallara cuatro pruebas por decir la verdad sobre el suyo.


    @Test
    fun `soltar el pedal suelta el VTEC aunque las rpm sigan arriba`() {
        // La histeresis es de REVOLUCIONES, no de carga: al levantar el pie
        // en retencion, el VTEC sale aunque el motor siga girando alto. Sin
        // esto la lampara se quedaria encendida bajando un puerto.
        assertFalse(Carro.motor.vtecActive(rpm = 6000, loadPct = 20, enganchadoAntes = true))
    }

    @Test
    fun `sin dato de carga se asume que no engancha`() {
        // Preferimos no prender la banda de mas: una banda encendida cuando
        // no lo esta enseña a desconfiar del tablero.
        assertFalse(Carro.motor.vtecActive(rpm = 8000, loadPct = null))
        assertFalse(Carro.motor.vtecActive(rpm = null, loadPct = 90))
    }

    @Test
    fun `el estado expone el VTEC ya derivado`() {
        assertTrue(VehicleState(rpm = 6500, loadPct = 75).vtecActive)
        assertFalse(VehicleState(rpm = 6500, loadPct = 20).vtecActive)
    }

    @Test
    fun `un valor viejo se marca como rancio`() {
        val s = VehicleState()
        val ahora = 100_000L
        assertFalse(s.isStale(atMs = ahora - 1_000, nowMs = ahora))
        assertTrue(s.isStale(atMs = ahora - 5_000, nowMs = ahora))
        // Timestamp cero = nunca hubo muestra.
        assertTrue(s.isStale(atMs = 0, nowMs = ahora))
    }
}
