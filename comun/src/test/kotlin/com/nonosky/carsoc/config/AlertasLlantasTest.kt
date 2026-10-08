package com.nonosky.carsoc.config

import com.nonosky.carsoc.config.AlertasLlantas.Reglas
import com.nonosky.carsoc.config.AlertasLlantas.Tipo
import com.nonosky.carsoc.tpms.TramaTpms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cuando avisa una llanta. La regla es una sola y la usan la notificacion y
 * los dos tableros, asi que aqui se fija lo que decide.
 */
class AlertasLlantasTest {

    private val reglas = Reglas(psiBaja = 26f, psiAlta = 38f, tempAlta = 75)

    @Test
    fun `dentro de los limites no avisa`() {
        assertNull(reglas.evaluar(30f, 40))
        assertNull("el limite mismo todavia no es alerta", reglas.evaluar(26f, 40))
        assertNull(reglas.evaluar(38f, 40))
    }

    @Test
    fun `cada limite da su alerta`() {
        assertEquals(Tipo.Baja, reglas.evaluar(25.5f, 40))
        assertEquals(Tipo.Alta, reglas.evaluar(38.5f, 40))
        assertEquals(Tipo.Caliente, reglas.evaluar(30f, 75))
    }

    @Test
    fun `con dos cosas a la vez manda la presion`() {
        assertEquals(Tipo.Baja, reglas.evaluar(20f, 90))
        assertEquals(Tipo.Alta, reglas.evaluar(45f, 90))
    }

    @Test
    fun `sin dato no hay alerta`() {
        assertNull(reglas.evaluar(null, null))
        assertEquals(Tipo.Caliente, reglas.evaluar(null, 80))
        assertEquals(Tipo.Baja, reglas.evaluar(20f, null))
    }

    @Test
    fun `una trama imposible no dispara nada`() {
        // 200 unidades = 100 PSI: es la escala, no la llanta.
        val absurda = TramaTpms(id = 0x00, crudoA = 200, crudoB = 77, crudoC = 0, recibidaMs = 1)
        assertTrue(absurda.presionFueraDeRango)
        assertNull(AlertasLlantas.evaluar(null, 0, absurda, reglas))
        // Y sin trama, tampoco.
        assertNull(AlertasLlantas.evaluar(null, 0, null, reglas))
    }

    @Test
    fun `una trama real pasa por la misma regla`() {
        // 48 unidades = 24 PSI, por debajo de los 26 de estas reglas.
        val baja = TramaTpms(id = 0x00, crudoA = 48, crudoB = 77, crudoC = 0, recibidaMs = 1)
        assertEquals(Tipo.Baja, AlertasLlantas.evaluar(null, 0, baja, reglas))
        // crudoB 130 = 80 °C con el desplazamiento de 50.
        val caliente = TramaTpms(id = 0x00, crudoA = 60, crudoB = 130, crudoC = 0, recibidaMs = 1)
        assertEquals(Tipo.Caliente, AlertasLlantas.evaluar(null, 0, caliente, reglas))
    }

    @Test
    fun `los limites de fabrica no se cruzan`() {
        assertTrue(AlertasLlantas.PSI_BAJA_POR_OMISION in AlertasLlantas.RANGO_BAJA)
        assertTrue(AlertasLlantas.PSI_ALTA_POR_OMISION in AlertasLlantas.RANGO_ALTA)
        assertTrue(AlertasLlantas.TEMP_ALTA_POR_OMISION in AlertasLlantas.RANGO_TEMP)
        assertTrue(
            "la baja mas alta no puede pasar a la alta mas baja",
            AlertasLlantas.RANGO_BAJA.endInclusive <= AlertasLlantas.RANGO_ALTA.start,
        )
    }

    @Test
    fun `el texto es el que lee el tablero`() {
        // Son las claves del JSON (`llNal`) que el HTML y el Canvas reconocen.
        assertEquals(listOf("baja", "alta", "caliente"), Tipo.values().map { it.texto })
    }
}
