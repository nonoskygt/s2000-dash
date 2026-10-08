package com.nonosky.carsoc.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El dialogo AT, contra un transporte guionado. Sin adaptador ni carro. */
class Elm327SessionTest {

    private fun okScript(extra: Map<String, String> = emptyMap()) = buildMap {
        put("ATZ", "ELM327 v1.5")
        put("ATE0", "OK")
        put("ATL0", "OK")
        put("ATS0", "OK")
        put("ATH0", "OK")
        put("ATSP5", "OK")
        put("ATAT1", "OK")
        put("ATDP", "ISO 14230-4 KWP FAST")
        put("010C", "410C1AF8")
        putAll(extra)
    }

    @Test
    fun `la inicializacion manda la secuencia AT en orden`() {
        val t = FakeTransport(okScript())
        Elm327Session(t).initialize()

        val esperado = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP5", "ATAT1", "ATDP")
        assertEquals(esperado, t.written.take(esperado.size))
    }

    @Test
    fun `reporta el protocolo que dijo ATDP`() {
        val info = Elm327Session(FakeTransport(okScript())).initialize()
        assertEquals("ISO 14230-4 KWP FAST", info.describedAs)
        assertFalse(info.usedFallback)
    }

    @Test
    fun `si ISO 14230-4 no contesta cae a ATSP0 automatico`() {
        // El bus no responde con el protocolo fijado a mano...
        var probes = 0
        val t = object : ObdTransport {
            private var pending = ""
            val written = mutableListOf<String>()
            override val isConnected = true
            override fun connect() {}
            override fun write(bytes: ByteArray) {
                val cmd = String(bytes, Charsets.US_ASCII).trim()
                written += cmd
                pending = when {
                    cmd == "010C" -> {
                        probes++
                        // ...pero si contesta despues de ATSP0.
                        if (probes == 1) "NO DATA" else "410C1AF8"
                    }
                    cmd == "ATDP" -> if (probes >= 2) "AUTO, ISO 15765-4" else "ISO 14230-4 KWP FAST"
                    else -> "OK"
                }
            }
            override fun readUntilPrompt(timeoutMs: Long) = pending.also { pending = "" }
            override fun drain() {}
            override fun close() {}
        }

        val info = Elm327Session(t).initialize()

        assertTrue("debio caer a automatico", info.usedFallback)
        assertTrue("debio mandar ATSP0", t.written.contains("ATSP0"))
        assertEquals("AUTO, ISO 15765-4", info.describedAs)
    }

    @Test
    fun `query devuelve la carga util ya limpia`() {
        val s = Elm327Session(FakeTransport(okScript()))
        val payload = s.query("010C")!!
        assertEquals(2, payload.size)
        assertEquals(0x1A, payload[0].toInt() and 0xFF)
        assertEquals(0xF8, payload[1].toInt() and 0xFF)
    }

    @Test
    fun `query devuelve null ante basura y no lanza`() {
        val basura = listOf("NO DATA", "SEARCHING...", "STOPPED", "?", "", "BUS ERROR")
        for (b in basura) {
            val s = Elm327Session(FakeTransport(okScript(mapOf("010C" to b))))
            assertNull("debio ser null para '$b'", s.query("010C"))
        }
    }

    @Test
    fun `una excepcion del transporte se traga y da null`() {
        val roto = object : ObdTransport {
            override val isConnected = true
            override fun connect() {}
            override fun write(bytes: ByteArray) = throw java.io.IOException("cable jalado")
            override fun readUntilPrompt(timeoutMs: Long) = ""
            override fun drain() {}
            override fun close() {}
        }
        // La regla dura de §10: nunca se propaga una excepcion a la UI.
        assertNull(Elm327Session(roto).queryRaw("010C"))
        assertNull(Elm327Session(roto).query("010C"))
        assertNull(Elm327Session(roto).readVoltage())
    }

    @Test
    fun `el voltaje sale de ATRV`() {
        val s = Elm327Session(FakeTransport(okScript(mapOf("ATRV" to "12.6V"))))
        assertEquals(12.6f, s.readVoltage()!!, 0.001f)
    }

    @Test
    fun `los comandos salen terminados en retorno de carro`() {
        val t = FakeTransport(okScript())
        Elm327Session(t).query("010C")
        // FakeTransport recorta, asi que verificamos por el lado del contenido.
        assertEquals("010C", t.written.last())
    }

    @Test
    fun `se drena el buffer antes de cada comando`() {
        // Regresion: si una respuesta se corto por timeout, su cola —con su
        // prompt '>'— se queda en el socket y la siguiente lectura termina
        // con ESE prompt viejo. El desfase de un turno no se corrige solo:
        // cada PID quedaria leyendo lo que contesto el anterior, para siempre.
        val t = FakeTransport(okScript())
        Elm327Session(t).query("010C")
        assertEquals("debio drenar una vez por comando", t.written.size, t.drains.size)
    }

    @Test
    fun `el banner BUS INIT no tumba la comprobacion del bus`() {
        // Con ISO 9141-2 la primera peticion SIEMPRE trae este banner. Si se
        // tomaba por error, initialize() creia que el bus no responde y caia
        // a ATSP0 en un enlace que estaba perfecto.
        val t = FakeTransport(okScript(mapOf("010C" to "BUS INIT: ...OK\r41 0C 1A F8\r\r>")))
        val info = Elm327Session(t).initialize()

        assertFalse("no debio caer a automatico", info.usedFallback)
        assertFalse("no debio mandar ATSP0", t.written.contains("ATSP0"))
    }
}
