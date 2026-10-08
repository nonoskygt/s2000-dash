package com.nonosky.carsoc.obd

/**
 * Transporte guionado para las pruebas.
 *
 * Vive **solo** en `src/test/` y no se compila dentro del APK. No es el modo
 * demo descartado en §3 del diseño: no hay forma de activarlo desde la app
 * instalada ni genera datos plausibles de manejo — solo reproduce respuestas
 * fijas, incluidos los fallos.
 */
class FakeTransport(
    /** Comando (sin `\r`) -> respuesta que devuelve el adaptador. */
    private val script: Map<String, String>,
    /** Respuesta para cualquier comando fuera del guion. */
    private val fallback: String = "",
) : ObdTransport {

    val written = mutableListOf<String>()

    /** Cuantas veces se drena, y antes de que comando. */
    val drains = mutableListOf<Int>()

    var connected = false
        private set
    var closed = false
        private set

    /** Si se fija, [connect] falla — para probar el camino de reconexion. */
    var failOnConnect: Exception? = null

    private var pending: String = ""

    override val isConnected: Boolean get() = connected

    override fun connect() {
        failOnConnect?.let { throw it }
        connected = true
    }

    override fun write(bytes: ByteArray) {
        val cmd = String(bytes, Charsets.US_ASCII).trim()
        written += cmd
        pending = script[cmd] ?: fallback
    }

    override fun readUntilPrompt(timeoutMs: Long): String {
        val out = pending
        pending = ""
        return out
    }

    override fun drain() {
        drains += written.size
        pending = ""
    }

    override fun close() {
        closed = true
        connected = false
    }
}
