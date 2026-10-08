package com.nonosky.carsoc.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream

class LectorDePromptTest {

    private fun par(): Pair<PipedOutputStream, LectorDePrompt> {
        val entrada = PipedInputStream(4096)
        val salida = PipedOutputStream(entrada)
        return salida to LectorDePrompt(entrada, "prueba")
    }

    @Test
    fun devuelveHastaElPromptSinIncluirlo() {
        val (sal, l) = par()
        sal.write("41 0C 1A F8\r\r>".toByteArray()); sal.flush()
        assertEquals("41 0C 1A F8\r\r", l.leerHastaPrompt(2_000, '>'))
    }

    @Test
    fun loQueLlegaTrasElPromptNoSePierde() {
        val (sal, l) = par()
        sal.write("OK\r>41 0D 32\r>".toByteArray()); sal.flush()
        assertEquals("OK\r", l.leerHastaPrompt(2_000, '>'))
        assertEquals("41 0D 32\r", l.leerHastaPrompt(2_000, '>'))
    }

    @Test
    fun alAgotarElPlazoDevuelveLoQueHaya() {
        val (sal, l) = par()
        sal.write("41 0C".toByteArray()); sal.flush()
        val t0 = System.currentTimeMillis()
        assertEquals("41 0C", l.leerHastaPrompt(300, '>'))
        assertTrue(System.currentTimeMillis() - t0 >= 250)
    }

    @Test
    fun despiertaEnCuantoLlegaElByteYNoAlFinalDelPlazo() {
        val (sal, l) = par()
        Thread { Thread.sleep(100); sal.write(">".toByteArray()); sal.flush() }.start()
        val t0 = System.currentTimeMillis()
        assertEquals("", l.leerHastaPrompt(5_000, '>'))
        assertTrue("tardo ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 2_000)
    }

    @Test
    fun vaciarTiraLoQueNadiePidio() {
        val (sal, l) = par()
        sal.write("BASURA\r>".toByteArray()); sal.flush()
        Thread.sleep(150)
        l.vaciar()
        sal.write("OK>".toByteArray()); sal.flush()
        assertEquals("OK", l.leerHastaPrompt(2_000, '>'))
    }

    @Test(expected = IOException::class)
    fun siElAdaptadorCierraSeDice() {
        val (sal, l) = par()
        sal.close()
        l.leerHastaPrompt(2_000, '>')
    }
}
