package com.nonosky.carsoc.hci

/**
 * Cuenta los paquetes ACL en vuelo para no desbordar al controlador.
 *
 * Esto NO es opcional y no hay forma de darse cuenta tarde: el controlador
 * tiene un numero fijo de buffers, y cuando se le mandan mas paquetes de los
 * que caben **no contesta un error, deja de funcionar**. El sintoma es un
 * dongle que respondia bien y de pronto se queda mudo hasta desenchufarlo.
 *
 * ESTANDAR VERIFICABLE (Core Spec Vol 4 Part E, secciones 4.1 a 4.3):
 *
 *  - `HCI_Read_Buffer_Size` (0x1005) da el tamano y el numero de buffers ACL
 *    del pool BR/EDR.
 *  - `HCI_LE_Read_Buffer_Size` (0x2002) da los del pool LE. Este dongle
 *    contesto **27 bytes x 15 buffers** (`0E07010220001B000F`).
 *  - Si el LE contesta tamano 0, es que **no tiene pool propio** y usa el de
 *    BR/EDR. Es un caso real y hay que tratarlo: contarlos por separado
 *    cuando comparten pool desborda igual.
 *  - El controlador devuelve credito con el evento **Number Of Completed
 *    Packets (0x13)**, que dice cuantos paquetes de cada handle ya salieron
 *    al aire. Ese evento es el UNICO permiso para mandar mas.
 *
 * En el otro sentido (controlador -> host) no hace falta contar nada: el
 * control de flujo hacia el host viene DESACTIVADO de fabrica y solo se
 * enciende con `Set_Controller_To_Host_Flow_Control`. Mientras no se toque,
 * el host puede recibir sin devolver creditos.
 *
 * ### La trampa que hay que evitar al usar esto
 *
 * **El hilo que lee los eventos no puede bloquearse aqui.** Si el hilo de la
 * bomba se quedara esperando credito, no podria leer el evento 0x13 que da
 * ese credito: bloqueo mutuo, y el tablero se queda quieto para siempre. Por
 * eso [reservar] lo llaman los hilos que ENVIAN, nunca la bomba, y la bomba
 * solo llama a [devolver].
 *
 * Sin Android: se prueba entera en la JVM con hilos.
 */
class ControlFlujoAcl {

    private val cerrojo = Object()

    private var total = 0
    private var libres = 0
    private var tam = 27

    /** Cuantos hay en vuelo por handle, para poder devolverlos si se cae. */
    private val enVueloPorHandle = HashMap<Int, Int>()

    var esperasConTiempoAgotado = 0L
        private set

    var maximoEnVuelo = 0
        private set

    /** Creditos que llegaron sin que nadie los debiera. Delata un desajuste. */
    var creditosSobrantes = 0L
        private set

    /**
     * Fija el pool a partir de lo que dijo el controlador.
     *
     * Se puede llamar mas de una vez (al reabrir el dongle). Lo que ya
     * estuviera en vuelo se olvida: tras un Reset el controlador vacia sus
     * buffers, asi que seguir contandolos dejaria creditos perdidos para
     * siempre y el enlace se pararia solo al cabo de un rato.
     */
    fun configurar(numeroBuffers: Int, tamPaquete: Int) {
        synchronized(cerrojo) {
            total = numeroBuffers.coerceAtLeast(1)
            libres = total
            tam = tamPaquete.coerceAtLeast(27)
            enVueloPorHandle.clear()
            cerrojo.notifyAll()
        }
    }

    val tamPaquete: Int get() = synchronized(cerrojo) { tam }
    val buffers: Int get() = synchronized(cerrojo) { total }

    fun libres(): Int = synchronized(cerrojo) { libres }

    fun enVuelo(): Int = synchronized(cerrojo) { total - libres }

    /**
     * Pide UN credito para mandar UN paquete ACL. Bloquea hasta tenerlo.
     *
     * Devuelve false si se agoto el plazo, y entonces quien llama tiene un
     * problema serio: si estaba a mitad de una PDU troceada, el otro lado ya
     * recibio trozos de algo que nunca va a terminar. Por eso el que envia
     * debe tratar un false como "este enlace hay que tirarlo", no como
     * "reintento el trozo".
     */
    fun reservar(handle: Int, timeoutMs: Long): Boolean {
        val hasta = System.currentTimeMillis() + timeoutMs
        synchronized(cerrojo) {
            while (libres <= 0) {
                val queda = hasta - System.currentTimeMillis()
                if (queda <= 0) {
                    esperasConTiempoAgotado++
                    return false
                }
                try {
                    cerrojo.wait(queda)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            libres--
            enVueloPorHandle[handle] = (enVueloPorHandle[handle] ?: 0) + 1
            val vuelo = total - libres
            if (vuelo > maximoEnVuelo) maximoEnVuelo = vuelo
            return true
        }
    }

    /**
     * Devuelve creditos porque llego un Number Of Completed Packets.
     *
     * Se acota a lo que ese handle debia: un controlador que devuelva de mas
     * —o un evento que llegue tras un Reset— no puede inflar el pool por
     * encima del total, porque entonces se mandarian mas paquetes de los que
     * caben y volveriamos al dongle mudo.
     */
    fun devolver(handle: Int, cuantos: Int) {
        if (cuantos <= 0) return
        synchronized(cerrojo) {
            val debia = enVueloPorHandle[handle] ?: 0
            val real = minOf(cuantos, debia)
            if (real < cuantos) creditosSobrantes += (cuantos - real)
            if (real <= 0) return
            if (debia - real <= 0) enVueloPorHandle.remove(handle)
            else enVueloPorHandle[handle] = debia - real
            libres = (libres + real).coerceAtMost(total)
            cerrojo.notifyAll()
        }
    }

    /**
     * El enlace se cayo: el controlador tira sus paquetes pendientes y NO va
     * a mandar el 0x13 por ellos.
     *
     * Sin esto, cada desconexion se comeria unos cuantos creditos para
     * siempre. Tras unas pocas reconexiones no quedaria ninguno y el tablero
     * dejaria de poder enviar sin ningun sintoma que apunte a la causa.
     */
    fun olvidar(handle: Int): Int {
        synchronized(cerrojo) {
            val debia = enVueloPorHandle.remove(handle) ?: return 0
            libres = (libres + debia).coerceAtMost(total)
            cerrojo.notifyAll()
            return debia
        }
    }

    /** Desarma un evento Number Of Completed Packets y devuelve los creditos. */
    fun procesarEvento(evento: ByteArray): Int {
        // 0x13 | largo | numeroDeHandles | (handle 2B, paquetes 2B) x N
        if (evento.size < 3) return 0
        if ((evento[0].toInt() and 0xFF) != HciUsb.EVT_NUM_COMPLETED_PACKETS) return 0
        val cuantos = evento[2].toInt() and 0xFF
        var i = 3
        var devueltos = 0
        repeat(cuantos) {
            if (i + 4 > evento.size) return devueltos
            val handle = ((evento[i].toInt() and 0xFF) or ((evento[i + 1].toInt() and 0x0F) shl 8))
            val n = (evento[i + 2].toInt() and 0xFF) or ((evento[i + 3].toInt() and 0xFF) shl 8)
            devolver(handle, n)
            devueltos += n
            i += 4
        }
        return devueltos
    }

    fun diagnostico(): List<String> = synchronized(cerrojo) {
        listOf(
            "pool ACL: $total buffers de $tam bytes",
            "libres: $libres, en vuelo: ${total - libres}, maximo visto: $maximoEnVuelo",
            "en vuelo por enlace: " + (
                enVueloPorHandle.entries.joinToString(", ") { "0x${"%03X".format(it.key)}=${it.value}" }
                    .ifEmpty { "ninguno" }
                ),
            "esperas agotadas: $esperasConTiempoAgotado, creditos sobrantes: $creditosSobrantes",
        )
    }
}
