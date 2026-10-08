package com.nonosky.carsoc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cuando se enciende el GPS que cuenta los kilometros del aceite.
 *
 * La prueba que importa es la primera de "sin OBD": antes, sin enlace con la
 * ECU, el GPS se apagaba a los cinco segundos de arrancar —antes de la primera
 * fija en frio— y como la unica forma de volver a encenderlo era haber visto
 * movimiento CON el GPS, no volvia nunca. Los kilometros no contaban.
 */
class ReglaGpsTest {

    private val t = 10_000_000L
    private val min = 60_000L

    private fun s(
        permite: Boolean = true,
        girando: Boolean = false,
        motorVivo: Long = 0L,
        movimiento: Long = 0L,
        obdSabe: Boolean = false,
        escucha: Long = 0L,
    ) = ReglaGps.Senales(
        ahora = t,
        permiteGps = permite,
        motorGirandoAhora = girando,
        ultimoMotorVivoMs = motorVivo,
        ultimoMovimientoMs = movimiento,
        obdSabeDelMotor = obdSabe,
        escuchaDesdeMs = escucha,
    )

    // --- sin OBD: lo que estaba roto --------------------------------------

    @Test
    fun sinObdYSinNadaVistoSeAbreUnaVentanaDeEscucha() {
        val d = ReglaGps.decidir(s())
        assertTrue(d.encender)
        assertTrue(d.abrirEscucha)
    }

    @Test
    fun laVentanaSeSostieneLoQueDura() {
        val d = ReglaGps.decidir(s(escucha = t - 60_000L))
        assertTrue(d.encender)
        assertFalse(d.abrirEscucha)
    }

    @Test
    fun trasLaVentanaSinMovimientoSeApagaHastaLaSiguiente() {
        val d = ReglaGps.decidir(s(escucha = t - ReglaGps.VENTANA_ESCUCHA_MS - 1))
        assertFalse(d.encender)
    }

    @Test
    fun yPasadoElCicloSeVuelveAEscuchar() {
        val d = ReglaGps.decidir(s(escucha = t - ReglaGps.CICLO_ESCUCHA_MS))
        assertTrue(d.encender)
        assertTrue(d.abrirEscucha)
    }

    @Test
    fun siEnLaVentanaSeVioMovimientoSigueEncendidoAunqueLaVentanaAcabe() {
        val d = ReglaGps.decidir(
            s(escucha = t - 5 * min, movimiento = t - 20_000L),
        )
        assertTrue(d.encender)
    }

    // --- con OBD: lo que ya funcionaba, que no se rompa --------------------

    @Test
    fun motorGirandoEnciende() {
        assertTrue(ReglaGps.decidir(s(girando = true, obdSabe = true)).encender)
    }

    @Test
    fun motorRecienApagadoAguantaLaGracia() {
        assertTrue(ReglaGps.decidir(s(motorVivo = t - 5 * min, obdSabe = true)).encender)
        assertFalse(ReglaGps.decidir(s(motorVivo = t - 11 * min, obdSabe = true)).encender)
    }

    @Test
    fun siElObdSabeQueElMotorEstaParadoNoSeEscucha() {
        // El adaptador contesta y la ECU no: contacto quitado. Acampado, con
        // el radio encendido horas, escuchar el GPS seria calor para nada.
        val d = ReglaGps.decidir(s(obdSabe = true))
        assertFalse(d.encender)
        assertFalse(d.abrirEscucha)
    }

    @Test
    fun elMovimientoVistoPorElGpsMandaAunqueElObdDigaParado() {
        // El enlace con la ECU se puede caer a mitad de viaje: entonces el GPS
        // es lo unico que cuenta, y apagarlo perderia el viaje entero.
        assertTrue(ReglaGps.decidir(s(obdSabe = true, movimiento = t - min)).encender)
    }

    // --- el guardian termico manda sobre todo -----------------------------

    @Test
    fun conElRadioCalienteNadaLoEnciende() {
        assertFalse(ReglaGps.decidir(s(permite = false, girando = true)).encender)
        assertFalse(ReglaGps.decidir(s(permite = false)).abrirEscucha)
    }
}
