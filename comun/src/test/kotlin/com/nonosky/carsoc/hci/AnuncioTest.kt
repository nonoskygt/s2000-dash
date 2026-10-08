package com.nonosky.carsoc.hci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El anuncio BLE que llega por el dongle se lee igual que el que da la pila
 * de Android: si no, el selector de aparatos no reconoceria un BMS por su
 * servicio 0xFF00 y lo pondria al final de la lista sin pista.
 */
class AnuncioTest {

    private fun bytes(hex: String) =
        hex.split(' ').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun saleElNombreYElServicioDelBms() {
        // flags | lista completa de UUID16 = FF00 | nombre "JBD"
        val (nombre, uuids) = SondaHci.leerAnuncio(bytes("02 01 06  03 03 00 FF  04 09 4A 42 44"))
        assertEquals("JBD", nombre)
        assertEquals(listOf("0000ff00-0000-1000-8000-00805f9b34fb"), uuids)
    }

    @Test
    fun variosUuidYNombreCorto() {
        val (nombre, uuids) = SondaHci.leerAnuncio(bytes("05 02 34 12 F0 FF  03 08 41 31"))
        assertEquals("A1", nombre)
        assertEquals(
            listOf("00001234-0000-1000-8000-00805f9b34fb", "0000fff0-0000-1000-8000-00805f9b34fb"),
            uuids,
        )
    }

    @Test
    fun unAnuncioCortadoNoRevienta() {
        val (nombre, uuids) = SondaHci.leerAnuncio(bytes("09 09 4A"))
        assertTrue(nombre == null || nombre.isNotEmpty())
        assertTrue(uuids.isEmpty())
    }
}
