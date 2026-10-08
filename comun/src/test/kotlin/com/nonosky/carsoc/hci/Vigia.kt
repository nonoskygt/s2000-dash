package com.nonosky.carsoc.hci

/**
 * Cuenta hilos vivos por nombre. Un hilo que sobrevive a su `detener()` es un
 * hilo que sigue tocando hardware.
 *
 * No usa `Thread.getAllStackTraces()` porque eso captura la pila de TODOS los
 * hilos de la JVM y cuesta milisegundos; en una prueba de mil ciclos eso solo
 * mediria el coste de medir. Enumerar el grupo raiz cuesta una fraccion.
 */
object Vigia {

    /** Los hilos que tocan el dongle. Si uno de estos vive, el USB no es libre. */
    val NOMBRES = listOf("bomba-hci", "reparto-hci", "bomba-eventos-hci")

    fun hilosDeLaRadio(): List<Thread> = todos().filter { h ->
        h.isAlive && NOMBRES.any { h.name.startsWith(it) }
    }

    fun cuantos(): Int = hilosDeLaRadio().size

    /** Espera a que no quede ninguno. Devuelve cuantos quedaron al vencer. */
    fun esperarAQueMueran(plazoMs: Long): Int {
        val hasta = System.currentTimeMillis() + plazoMs
        var quedan = cuantos()
        while (quedan > 0 && System.currentTimeMillis() < hasta) {
            Thread.sleep(10)
            quedan = cuantos()
        }
        return quedan
    }

    fun volcado(): String {
        val vivos = hilosDeLaRadio()
        if (vivos.isEmpty()) return "(ningun hilo de la radio vivo)"
        return vivos.joinToString("\n") { h ->
            "  ${h.name} estado=${h.state}\n" +
                h.stackTrace.take(6).joinToString("\n") { "      en $it" }
        }
    }

    /** Volcado de TODOS los hilos: para cuando una prueba se cuelga. */
    fun volcadoCompleto(): String = Thread.getAllStackTraces().entries.joinToString("\n") { (h, pila) ->
        "  ${h.name} estado=${h.state}\n" + pila.take(8).joinToString("\n") { "      en $it" }
    }

    private fun todos(): List<Thread> {
        var raiz = Thread.currentThread().threadGroup ?: return emptyList()
        while (raiz.parent != null) raiz = raiz.parent
        var tam = raiz.activeCount() + 64
        while (true) {
            val arr = arrayOfNulls<Thread>(tam)
            val n = raiz.enumerate(arr, true)
            if (n < arr.size) return arr.take(n).filterNotNull()
            tam *= 2
        }
    }
}
