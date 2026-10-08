package com.nonosky.carsoc.config

import com.nonosky.carsoc.config.PistasAparato.Aparato
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PistasAparatoTest {

    private fun ble(mac: String, nombre: String?, rssi: Int?, vararg uuids: String) =
        Aparato(mac, nombre, "BLE", rssi, emparejado = false, uuids = uuids.toList())

    /**
     * La razon de barrer antes de llamar a la nevera: su direccion es
     * aleatoria estatica y la de los BMS no. Si esta prueba cambiara, la
     * explicacion de LectorNevera dejaria de valer.
     */
    @Test
    fun laNeveraTieneDireccionAleatoriaYLosBmsNo() {
        assertTrue(PistasAparato.esAleatoriaEstatica("ED:67:39:96:50:9B"))  // Alpicool
        assertFalse(PistasAparato.esAleatoriaEstatica("A4:C1:38:3B:B9:5E")) // banco de arranque
        assertFalse(PistasAparato.esAleatoriaEstatica("A5:C2:37:09:18:EE")) // banco de vivienda
        assertFalse(PistasAparato.esAleatoriaEstatica("00:04:3E:6F:2E:B2")) // un OBDLink
        assertFalse(PistasAparato.esAleatoriaEstatica("basura"))
    }

    @Test
    fun laNeveraSeReconocePorSuServicio() {
        val nevera = ble("ED:67:39:96:50:9B", "A1-4XXXXXXXXXXX", -70,
            "00001234-0000-1000-8000-00805f9b34fb")
        assertEquals("parece una nevera Alpicool", PistasAparato.pista(Emparejados.Papel.Nevera, nevera))
        // Pero no se ofrece como bateria.
        assertNull(PistasAparato.pista(Emparejados.Papel.BancoArranque, nevera))
    }

    @Test
    fun elBmsSeReconocePorSuServicio() {
        val bms = ble("A5:C2:37:09:18:EE", "Elementos 300AH", -72,
            "0000ff00-0000-1000-8000-00805f9b34fb")
        assertEquals("parece un BMS JBD", PistasAparato.pista(Emparejados.Papel.BancoVivienda, bms))
        assertNull(PistasAparato.pista(Emparejados.Papel.Nevera, bms))
    }

    @Test
    fun elObdLinkSeReconocePorNombre() {
        val mx = Aparato("00:04:3E:6F:2E:B2", "OBDLink MX+ 16808", "CLASICO", null, emparejado = true)
        assertEquals("parece un adaptador OBD", PistasAparato.pista(Emparejados.Papel.AdaptadorObd, mx))
    }

    @Test
    fun loQueTienePistaVaArribaYLuegoElMasCercano() {
        val lejos = ble("11:11:11:11:11:11", "Telefono", -50)
        val bmsLejos = ble("22:22:22:22:22:22", "JBD-SP04S", -88, "0000ff00-0000-1000-8000-00805f9b34fb")
        val bmsCerca = ble("33:33:33:33:33:33", "Elementos 300AH", -61, "0000ff00-0000-1000-8000-00805f9b34fb")
        val orden = PistasAparato.ordenar(Emparejados.Papel.BancoVivienda, listOf(lejos, bmsLejos, bmsCerca))
        assertEquals(listOf(bmsCerca, bmsLejos, lejos), orden)
    }

    @Test
    fun barrasDeSenal() {
        assertNull(PistasAparato.barras(null))
        assertEquals(4, PistasAparato.barras(-55))
        assertEquals(2, PistasAparato.barras(-75))
        assertEquals(0, PistasAparato.barras(-99))
    }
}
