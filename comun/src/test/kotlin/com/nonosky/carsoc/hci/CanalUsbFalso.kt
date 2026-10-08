package com.nonosky.carsoc.hci

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Un cable USB de mentira que DELATA el uso despues de cerrar.
 *
 * ### Por que existe
 *
 * El radio se cuelga entero —ni contesta al ping— y hubo que reiniciarlo
 * cuatro veces, la ultima cortandole la corriente al carro. Se culpo al
 * repintado (se bajo a 5 fps: se colgo igual) y a la temperatura (se le puso
 * un ventilador y estaba FRIO: se colgo igual). Las dos hipotesis, muertas.
 *
 * Lo que queda es la capa que ninguna prueba tocaba: `UsbDeviceConnection`,
 * que es codigo nativo. No existe en la JVM y no existe en el emulador de
 * Android, que no tiene host USB. O sea que el unico sitio donde el fallo
 * puede vivir es justo el unico sitio que no se podia probar.
 *
 * Esta clase cierra ese hueco. Imita las DOS propiedades del aparato real que
 * hacen que el fallo sea posible:
 *
 *  1. **Una transferencia TARDA.** Un `bulkTransfer` con plazo de 15 ms tarda
 *     esos 15 ms. Mientras tanto el hilo esta DENTRO del cable.
 *  2. **`interrupt()` NO la aborta.** Es una llamada nativa: la bandera de
 *     interrupcion de Java no la mira nadie. Por eso [dormirComoElNativo]
 *     limpia la bandera a proposito — cualquier otra cosa seria hacer trampa
 *     a favor del codigo que se quiere probar.
 *
 * Y anota el delito: si alguien transfiere sobre un cable ya cerrado, o si el
 * cable se cierra mientras una transferencia sigue dentro, queda escrito en
 * [delitos] con nombre y apellido. En el aparato de verdad eso es un
 * descriptor liberado bajo los pies de una llamada nativa en curso — que es
 * exactamente la forma de tumbar un proceso, o un driver, sin dejar un
 * `logcat` que lo explique.
 */
class CanalUsbFalso(
    /**
     * Cuanto tarda cada transferencia. Es el ancho de la ventana de carrera.
     *
     * 20 ms se parece a los plazos reales de la bomba (15 ms por lectura) y
     * hace que el fallo salga en la primera vuelta en vez de una vez cada mil.
     */
    private val demoraMs: Long = 20,
    private val conAcl: Boolean = true,
) : CanalUsbHci {

    @Volatile
    private var cerrado = false

    override val abierto: Boolean get() = !cerrado
    override val tieneAcl: Boolean get() = conAcl && !cerrado
    override val tamBloqueSalida: Int get() = 64
    override val tamBloqueEntrada: Int get() = 64

    // --- contabilidad, que es para lo que sirve un doble ---

    /** Transferencias que llegaron a completarse. */
    val transferencias = AtomicLong(0)

    /** Cuantas hay DENTRO del cable ahora mismo. */
    val enVuelo = AtomicInteger(0)

    val maximoEnVuelo = AtomicInteger(0)

    val vecesCerrado = AtomicInteger(0)

    /** Transferencias en vuelo en el instante exacto del ultimo `cerrar()`. */
    @Volatile
    var enVueloAlCerrar = 0
        private set

    /** Cuantos delitos en total (la lista se acota para no reventar memoria). */
    val delitosContados = AtomicLong(0)

    /** Los primeros [MAX_DELITOS], con su descripcion. */
    val delitos = CopyOnWriteArrayList<String>()

    val escritosAcl = AtomicLong(0)
    val comandosMandados = AtomicLong(0)

    /** Lo que el "controlador" tiene preparado para entregar. */
    private val eventos = ConcurrentLinkedQueue<ByteArray>()
    private val acl = ConcurrentLinkedQueue<ByteArray>()

    fun entregarEvento(e: ByteArray) = eventos.add(e)
    fun entregarAcl(p: ByteArray) = acl.add(p)
    fun eventosPendientes(): Int = eventos.size

    // --- el cable ------------------------------------------------------

    override fun mandarComando(opcode: Int, parametros: ByteArray): Int =
        transferir("mandarComando(0x${"%04X".format(opcode)})") {
            comandosMandados.incrementAndGet()
            3 + parametros.size
        }

    override fun escribirAclCrudo(paquete: ByteArray, timeoutMs: Int): Int =
        transferir("escribirAclCrudo(${paquete.size} B)") {
            escritosAcl.incrementAndGet()
            paquete.size
        }

    override fun leerAclCrudo(buffer: ByteArray, timeoutMs: Int): Int =
        transferir("leerAclCrudo") {
            val p = acl.poll() ?: return@transferir 0
            val n = minOf(p.size, buffer.size)
            p.copyInto(buffer, 0, 0, n)
            n
        }

    override fun leerEvento(timeoutMs: Int): ByteArray? =
        transferir("leerEvento") { eventos.poll() }

    /**
     * Suelta el descriptor. En [HciUsb] esto es `releaseInterface()` +
     * `close()`, y a partir de aqui el aparato ya no es nuestro.
     */
    override fun cerrar() {
        vecesCerrado.incrementAndGet()
        val dentro = enVuelo.get()
        enVueloAlCerrar = dentro
        if (dentro > 0) {
            anotar("cerrar() con $dentro transferencia(s) TODAVIA dentro del cable")
        }
        cerrado = true
    }

    // --- lo unico que importa -------------------------------------------

    private fun <T> transferir(que: String, cuerpo: () -> T): T {
        if (cerrado) {
            anotar("$que sobre un cable YA CERRADO")
            return cuerpo()
        }
        val n = enVuelo.incrementAndGet()
        maximoEnVuelo.updateAndGet { if (n > it) n else it }
        try {
            dormirComoElNativo(demoraMs)
            // Se comprueba DESPUES de la demora: esta es la carrera de verdad.
            // El hilo entro con el cable abierto y salio con el cerrado; en el
            // aparato real, entre esos dos instantes el descriptor se libero
            // debajo de una llamada nativa en curso.
            if (cerrado) anotar("$que seguia dentro del cable cuando lo cerraron")
            transferencias.incrementAndGet()
            return cuerpo()
        } finally {
            enVuelo.decrementAndGet()
        }
    }

    /**
     * Duerme como duerme una llamada nativa: sin dejarse interrumpir.
     *
     * `Thread.interrupted()` limpia la bandera cada vuelta a proposito. Si se
     * usara `Thread.sleep`, un `interrupt()` la abortaria y la prueba estaria
     * midiendo un aparato que no existe — uno que si se puede cancelar. El
     * dongle de verdad no se puede.
     */
    private fun dormirComoElNativo(ms: Long) {
        if (ms <= 0) {
            Thread.interrupted()
            return
        }
        val hasta = System.nanoTime() + ms * 1_000_000L
        while (System.nanoTime() < hasta) {
            LockSupport.parkNanos(100_000L)
            Thread.interrupted()
        }
    }

    private fun anotar(delito: String) {
        delitosContados.incrementAndGet()
        if (delitos.size < MAX_DELITOS) delitos += delito
    }

    fun resumen(): String =
        "transferencias=${transferencias.get()} delitos=${delitosContados.get()} " +
            "maxEnVuelo=${maximoEnVuelo.get()} cerradoVeces=${vecesCerrado.get()}" +
            (if (delitos.isEmpty()) "" else "\n  " + delitos.take(10).joinToString("\n  "))

    private companion object {
        const val MAX_DELITOS = 200
    }
}
