package com.nonosky.carsoc.hci

import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/**
 * El bucle de bombeo del HCI: un solo dueno del USB.
 *
 * ### Por que hace falta
 *
 * Hasta ahora el trato con el dongle era "mando un comando y leo la
 * respuesta", y con eso bastaba para interrogarlo y barrer BLE. En cuanto hay
 * enlaces ese modelo se cae: llegan cosas que **nadie pidio** —notificaciones
 * ATT, datos RFCOMM, desconexiones, creditos de flujo— y llegan mezcladas con
 * las respuestas a lo que si se pidio. Quien lea "la siguiente respuesta" se
 * quedara con una notificacion y creera que su comando fallo.
 *
 * ### Por que UN solo hilo toca el USB
 *
 * Dos hilos leyendo el mismo endpoint **se roban paquetes entre si**: cada
 * `bulkTransfer` se lleva un trozo, y el que lo recibe no tiene forma de
 * devolverlo. Media respuesta en un hilo y media en el otro, y ninguno de los
 * dos puede reensamblar nada. Aqui el hilo `bomba-hci` es el unico que
 * escribe y lee del aparato; todo lo demas se le pide por colas.
 *
 * ### Por que hay un SEGUNDO hilo para repartir
 *
 * Porque si la bomba entregara los datos llamando directamente a quien
 * escucha, y ese alguien contestara algo (una respuesta de configuracion
 * L2CAP, por ejemplo), se bloquearia esperando credito de flujo... y el
 * credito llega en un evento que solo la bomba puede leer. **Bloqueo mutuo.**
 * Por eso la bomba solo toca el USB y encola; el hilo `reparto-hci` es el que
 * llama a los oyentes, y ese si se puede bloquear sin parar el mundo.
 *
 * Los creditos (evento 0x13) y las desconexiones (0x05) se procesan **en la
 * bomba**, antes de encolar: son contabilidad, son baratos, y no pueden
 * quedarse esperando detras de un oyente lento.
 *
 * ### Aislamiento de fallos
 *
 * Los dos hilos van envueltos enteros. Una excepcion que escape de un hilo en
 * Android mata el proceso, y eso tumbaria de golpe el tablero, el TPMS, el
 * puente y el actualizador. Ya paso una vez con el DebugServer.
 */
class BombaHci(private val hci: CanalUsbHci) {

    /**
     * Quien quiera enterarse de lo que llega.
     *
     * Los metodos se llaman desde el hilo de reparto, NUNCA desde la bomba.
     * Pueden tardar y pueden enviar. Lo que no pueden es llamar a los metodos
     * bloqueantes de esta clase ([comando], [esperarEvento]) desde dentro:
     * eso si se bloquearia a si mismo.
     */
    interface Oyente {
        fun alEvento(evento: ByteArray) {}
        fun alPdu(handle: Int, pdu: ByteArray) {}
        fun alCaerEnlace(handle: Int, razon: Int) {}
    }

    val flujo = ControlFlujoAcl()

    private val oyentes = CopyOnWriteArrayList<Oyente>()
    private val ensamblador = EnsambladorAcl()

    /** Trozos ACL ya con credito reservado, listos para salir por el BULK. */
    /**
     * Trozos ACL con credito reservado, listos para salir. **Acotada.**
     *
     * Sin tope crecia para siempre, y no por un caso raro: cuando un enlace se
     * cae con envios en vuelo, sus trozos se quedan aqui y nadie los quita —
     * pero los creditos SI se devuelven, asi que quien envia puede encolar
     * otros tantos. Cada caida deja basura permanente. Y las caidas son
     * rutina: el ELM327 clon se cae solo y el BMS se duerme.
     *
     * Con tope, lo que sobra se descarta y se CUENTA. Un descarte contado es
     * un sintoma; una cola creciendo en silencio es una fuga.
     */
    private val salida = LinkedBlockingQueue<ByteArray>(TOPE_SALIDA)

    /** Trozos descartados por cola llena. Distinto de cero = algo va mal. */
    @Volatile
    var trozosDescartados = 0L
        private set

    /** Lo que hay que repartir. Acotada: la bomba no puede bloquearse aqui. */
    private val reparto = ArrayBlockingQueue<Recibido>(REPARTO_MAX)

    private val porMandar = LinkedBlockingQueue<Peticion>()

    /** Un comando a la vez: es lo unico seguro sin mirar Num_HCI_Command_Packets. */
    private val cerrojoComando = ReentrantLock()

    @Volatile private var enCurso: Peticion? = null

    /** Un cerrojo por enlace para que dos PDU no intercalen sus trozos. */
    private val cerrojosEnvio = ConcurrentHashMap<Int, ReentrantLock>()

    @Volatile private var vivo = false
    private var hiloBomba: Thread? = null
    private var hiloReparto: Thread? = null

    private var reensambla = ReensamblaUsb.paraAcl(27)

    // --- contadores, para poder diagnosticar en remoto ---
    @Volatile
    var eventosLeidos = 0L
        private set

    @Volatile
    var pdusRecibidas = 0L
        private set

    @Volatile
    var trozosEnviados = 0L
        private set

    @Volatile
    var trozosFallidos = 0L
        private set

    @Volatile
    var repartosPerdidos = 0L
        private set

    @Volatile
    var vueltasConFallo = 0L
        private set

    @Volatile
    var ultimoFallo: String? = null

    /** Cuantas veces un hilo no murio en su plazo. Debe quedarse en cero. */
    @Volatile
    var hilosQueNoMurieron = 0
        private set

    private class Peticion(val opcode: Int, val parametros: ByteArray) {
        val listo = CountDownLatch(1)
        @Volatile var respuesta: ByteArray? = null
        @Volatile var escrito = Int.MIN_VALUE
    }

    private class Recibido(val evento: ByteArray?, val handle: Int, val pdu: ByteArray?)

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    /**
     * Arranca los dos hilos. El dongle ya tiene que estar abierto.
     *
     * Ojo: **no** hace Reset. Quien llame decide si reinicia el controlador,
     * porque un Reset vacia los buffers y obliga a reconfigurar el control de
     * flujo (y a olvidar los enlaces que hubiera).
     */
    fun arrancar(): Boolean {
        if (vivo) return true
        if (!hci.abierto) return false
        vivo = true

        hiloBomba = thread(name = "bomba-hci", isDaemon = true) {
            val buffer = ByteArray(hci.tamBloqueEntrada.coerceAtLeast(64))
            while (vivo) {
                val ok = runCatching { unaVuelta(buffer) }
                if (ok.isFailure) {
                    vueltasConFallo++
                    ultimoFallo = ok.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
                    Log.w(TAG, "vuelta de la bomba fallida: $ultimoFallo")
                    // Un fallo seguido a toda velocidad quemaria la CPU del
                    // radio mientras el usuario conduce. Se respira.
                    runCatching { Thread.sleep(50) }
                }
            }
        }

        hiloReparto = thread(name = "reparto-hci", isDaemon = true) {
            while (vivo) {
                runCatching {
                    val r = reparto.poll(200, TimeUnit.MILLISECONDS) ?: return@runCatching
                    repartir(r)
                }.onFailure { Log.w(TAG, "reparto fallido: ${it.message}") }
            }
        }
        return true
    }

    /**
     * Para la bomba y **espera a que sus hilos esten muertos** antes de volver.
     *
     * El join no es cortesia: quien llama a esto es RadioBt.soltar(), y lo
     * siguiente que hace es `hci.cerrar()` — releaseInterface() y close() sobre
     * el descriptor USB. Sin esperar, ese cierre ocurre MIENTRAS el hilo
     * `bomba-hci` puede seguir dentro de un bulkTransfer sobre ese mismo
     * descriptor: uso despues de cerrar, sobre un recurso nativo.
     *
     * Y `interrupt()` no salva de eso. La bandera de interrupcion de Java no
     * la mira nadie dentro del driver USB, asi que a la bomba NO se le
     * interrumpe —seria inutil— y se le espera. Al hilo de reparto si, porque
     * ese espera en colas y la interrupcion si lo despierta.
     *
     * El plazo es de 2 s: mas que una vuelta entera de la bomba, que en el
     * peor caso ronda los 250 ms mas el plazo de una escritura.
     *
     * Contraste que delata que esto era una omision y no una decision:
     * BombaEventos.detener() y CanalRfcomm ya hacian su join. La unica que no
     * esperaba a su hilo era justo la que bombea el dongle — el unico aparato
     * que se abre y se cierra en ciclos.
     */
    fun detener() {
        vivo = false
        val b = hiloBomba
        val r = hiloReparto
        hiloBomba = null
        hiloReparto = null
        // Llamarse desde el propio hilo de la bomba seria esperarse a si mismo.
        val yo = Thread.currentThread()
        runCatching { r?.interrupt() }
        if (b !== yo) runCatching { b?.join(PLAZO_PARADA_MS) }
        if (r !== yo) runCatching { r?.join(PLAZO_PARADA_MS) }
        if (b?.isAlive == true) {
            Log.w(TAG, "el hilo de la bomba sigue vivo tras ${PLAZO_PARADA_MS} ms; " +
                "NO se debe cerrar el USB en este estado")
            hilosQueNoMurieron++
        }
        // Nadie va a contestar ya: se sueltan los que esperaban una respuesta
        // en vez de dejarlos colgados hasta su plazo.
        enCurso?.listo?.countDown()
        enCurso = null
        salida.clear()
        reparto.clear()
        // Las entregas por oyente tambien tienen hilo: hay que pararlas, o
        // sobreviven al detener y siguen llamando a codigo que ya se cerro.
        entregas.values.forEach { runCatching { it.detener() } }
        entregas.clear()
    }

    /**
     * Cada oyente recibe en SU PROPIO hilo, con su propia cola acotada.
     *
     * El reparto era en serie: un bucle sobre los oyentes llamandolos uno tras
     * otro. Bastaba con que uno se bloqueara —por ejemplo esperando credito
     * ACL, cosa que pasa justo cuando el controlador va saturado y mas trafico
     * hay— para que se pararan TODOS los demas: las notificaciones del BMS, los
     * bytes del ELM327 y, lo peor, los avisos de caida de enlace. Todo el mundo
     * seguia creyendo que su enlace vivia.
     *
     * Con un hilo por oyente, el lento solo se ahoga a si mismo. Y su cola
     * tiene tope: si se llena se descarta y se CUENTA, en vez de crecer hasta
     * llevarse la memoria.
     */
    fun suscribir(o: Oyente) {
        if (entregas.containsKey(o)) return
        val entrega = Entrega(o)
        if (entregas.putIfAbsent(o, entrega) != null) return
        oyentes.addIfAbsent(o)
        entrega.arrancar()
    }

    /**
     * Suscribe una funcion a los eventos y devuelve como darse de baja.
     *
     * Existe para que nadie tenga motivo de leer el endpoint por su cuenta.
     * Quien solo quiere escuchar eventos —el barrido de la bateria, por
     * ejemplo— llama a esto en vez de hacer su propio bucle de leerEvento(),
     * que ponia un segundo hilo dentro de un bulkTransfer sobre el mismo
     * descriptor que la bomba.
     */
    fun suscribirEventos(alLlegar: (ByteArray) -> Unit): () -> Unit {
        val o = object : Oyente {
            override fun alEvento(evento: ByteArray) {
                runCatching { alLlegar(evento) }
            }
        }
        suscribir(o)
        return { runCatching { quitar(o) } }
    }

    fun quitar(o: Oyente) {
        oyentes.remove(o)
        entregas.remove(o)?.detener()
    }

    /**
     * El hilo y la cola de un oyente.
     *
     * Se para con su propio `vivo` y se le espera con join, por la misma razon
     * que a la bomba: un hilo que sobrevive a su detener es un hilo que sigue
     * tocando cosas que ya se cerraron.
     */
    private inner class Entrega(private val oyente: Oyente) {
        private val cola = java.util.concurrent.ArrayBlockingQueue<Recibido>(TOPE_POR_OYENTE)
        @Volatile private var vivoEntrega = false
        private var hilo: Thread? = null

        @Volatile
        var descartados = 0L
            private set

        fun arrancar() {
            vivoEntrega = true
            hilo = thread(name = "entrega-hci", isDaemon = true) {
                while (vivoEntrega) {
                    runCatching {
                        val r = cola.poll(200, TimeUnit.MILLISECONDS) ?: return@runCatching
                        entregarA(oyente, r)
                    }.onFailure { Log.w(TAG, "entrega fallida: ${it.message}") }
                }
            }
        }

        /**
         * Encola para este oyente. Si su cola esta llena, se descarta.
         *
         * El descarte se cuenta DOS veces a proposito: en el contador propio
         * del oyente —para saber cual se atasca— y en el global
         * [repartosPerdidos], para que siga siendo cierto que "un evento que no
         * llego a alguien queda contado". Al repartir por oyente, el descarte
         * dejo de ocurrir en la cola de reparto; si no se sumara aqui, los
         * descartes se volverian invisibles, que es peor que perderlos.
         */
        fun ofrecer(r: Recibido) {
            if (!cola.offer(r)) {
                descartados++
                repartosPerdidos++
            }
        }

        fun detener() {
            vivoEntrega = false
            val h = hilo
            hilo = null
            if (h !== Thread.currentThread()) {
                runCatching { h?.interrupt() }
                runCatching { h?.join(PLAZO_PARADA_MS) }
            }
        }
    }

    private val entregas = java.util.concurrent.ConcurrentHashMap<Oyente, Entrega>()

    // ------------------------------------------------------------------
    // El bucle
    // ------------------------------------------------------------------

    private fun unaVuelta(buffer: ByteArray) {
        // 1. Un comando, si alguien lo dejo pedido.
        porMandar.poll()?.let { p ->
            p.escrito = hci.mandarComando(p.opcode, p.parametros)
            if (p.escrito < 0) {
                // No salio del USB: no va a llegar respuesta nunca. Soltar ya
                // al que espera es mejor que dejarlo agotar su plazo sin saber
                // que el problema fue la escritura y no el controlador.
                enCurso = null
                p.listo.countDown()
            }
        }

        // 2. Los trozos ACL que ya tienen credito.
        var escritos = 0
        while (escritos < MAX_POR_VUELTA) {
            val t = salida.poll() ?: break
            val n = hci.escribirAclCrudo(t)
            if (n < 0) {
                trozosFallidos++
                ultimoFallo = "escritura ACL fallida (${t.size} bytes)"
                // El credito ya estaba reservado y el controlador nunca va a
                // devolverlo con un 0x13, porque nunca recibio el paquete.
                flujo.devolver(PaqueteAcl.handleDe(t), 1)
            } else {
                trozosEnviados++
            }
            escritos++
        }

        // 3. Eventos.
        var leidos = 0
        while (leidos < MAX_POR_VUELTA) {
            val e = hci.leerEvento(ESPERA_EVENTO_MS) ?: break
            eventosLeidos++
            contabilizar(e)
            encolar(Recibido(e, -1, null))
            leidos++
        }

        // 4. Datos ACL.
        leidos = 0
        while (leidos < MAX_POR_VUELTA) {
            val n = hci.leerAclCrudo(buffer, ESPERA_ACL_MS)
            if (n <= 0) break
            for (paquete in reensambla.alimentar(buffer, n)) {
                for (pdu in ensamblador.alimentar(paquete)) {
                    pdusRecibidas++
                    encolar(Recibido(null, pdu.handle, pdu.datos))
                }
            }
            leidos++
        }
    }

    /**
     * Contabilidad que NO puede esperar detras de un oyente lento.
     *
     * Los creditos y las caidas de enlace se procesan aqui, en la bomba, por
     * dos razones: son cuatro operaciones de aritmetica, y si se retrasaran
     * el envio se pararia esperando creditos que ya estaban concedidos.
     */
    private fun contabilizar(e: ByteArray) {
        if (e.isEmpty()) return
        when (e[0].toInt() and 0xFF) {
            HciUsb.EVT_NUM_COMPLETED_PACKETS -> flujo.procesarEvento(e)

            HciUsb.EVT_DISCONNECTION_COMPLETE -> {
                // 05 | largo | estado | handle(2) | razon
                if (e.size >= 6) {
                    val handle = (e[3].toInt() and 0xFF) or ((e[4].toInt() and 0x0F) shl 8)
                    val devueltos = flujo.olvidar(handle)
                    ensamblador.olvidar(handle)
                    cerrojosEnvio.remove(handle)
                    // Purgar lo que quedo encolado de ESE enlace. Si no, sus
                    // trozos se quedan para siempre ocupando sitio, y ademas
                    // saldrian por el cable dirigidos a un handle que ya no
                    // existe — que es basura que el controlador tiene que
                    // rechazar una por una.
                    val purgados = purgarSalida(handle)
                    if (devueltos > 0 || purgados > 0) {
                        Log.i(TAG, "enlace 0x${"%03X".format(handle)} caido: " +
                            "$devueltos creditos recuperados, $purgados trozos purgados")
                    }
                }
            }
        }
        casarConComando(e)
    }

    /**
     * Casa un evento con el comando que espera respuesta.
     *
     * Command Complete y Command Status llevan el opcode del comando al que
     * contestan, en sitios distintos:
     *
     * ```
     *  0E | largo | numPaquetes | opcode(2, LE) | estado | ...   Command Complete
     *  0F | largo | estado | numPaquetes | opcode(2, LE)         Command Status
     * ```
     *
     * Sin comparar el opcode, un evento espontaneo se tomaria por la
     * respuesta del comando en curso.
     */
    private fun casarConComando(e: ByteArray) {
        val p = enCurso ?: return
        val codigo = e[0].toInt() and 0xFF
        val opcode = when {
            codigo == HciUsb.EVT_COMMAND_COMPLETE && e.size >= 5 ->
                (e[3].toInt() and 0xFF) or ((e[4].toInt() and 0xFF) shl 8)
            codigo == HciUsb.EVT_COMMAND_STATUS && e.size >= 6 ->
                (e[4].toInt() and 0xFF) or ((e[5].toInt() and 0xFF) shl 8)
            else -> return
        }
        if (opcode != p.opcode) return
        p.respuesta = e
        enCurso = null
        p.listo.countDown()
    }

    private fun encolar(r: Recibido) {
        if (!reparto.offer(r)) {
            // Preferible perder una notificacion y contarla que bloquear la
            // bomba: si la bomba para, para tambien el control de flujo y el
            // enlace entero se cae en cadena.
            repartosPerdidos++
        }
    }

    /**
     * Encola para cada oyente y vuelve de inmediato.
     *
     * No llama a nadie: solo reparte copias a las colas. Asi el hilo de
     * reparto nunca se queda dentro del codigo de un oyente.
     */
    private fun repartir(r: Recibido) {
        for (e in entregas.values) {
            runCatching { e.ofrecer(r) }
        }
    }

    /** Lo que antes hacia el bucle de reparto, ahora por oyente y en su hilo. */
    private fun entregarA(o: Oyente, r: Recibido) {
        run {
            runCatching {
                if (r.evento != null) {
                    o.alEvento(r.evento)
                    if ((r.evento[0].toInt() and 0xFF) == HciUsb.EVT_DISCONNECTION_COMPLETE &&
                        r.evento.size >= 6
                    ) {
                        val handle = (r.evento[3].toInt() and 0xFF) or ((r.evento[4].toInt() and 0x0F) shl 8)
                        o.alCaerEnlace(handle, r.evento[5].toInt() and 0xFF)
                    }
                } else if (r.pdu != null) {
                    o.alPdu(r.handle, r.pdu)
                }
            }.onFailure { Log.w(TAG, "un oyente lanzo: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------
    // Comandos
    // ------------------------------------------------------------------

    /**
     * Manda un comando HCI y espera SU respuesta.
     *
     * Devuelve el evento Command Complete o Command Status, o null si no
     * llego a tiempo. Ojo con la diferencia, que se paga cara:
     *
     *  - **Command Complete** significa que el comando termino.
     *  - **Command Status** significa que el comando fue ACEPTADO y que el
     *    resultado de verdad llegara despues en otro evento. Es lo que
     *    contestan `LE_Create_Connection` y `Create_Connection`: el enlace no
     *    existe hasta que llega el Connection Complete, y quien tome el
     *    Command Status por exito intentara hablar con un handle que aun no
     *    existe.
     *
     * Se serializa: un comando a la vez. La especificacion permite mas si el
     * controlador lo dice en `Num_HCI_Command_Packets`, pero mandar de mas es
     * exactamente como se pierde la sincronia entre comando y respuesta.
     */
    fun comando(opcode: Int, parametros: ByteArray = ByteArray(0), timeoutMs: Long = 3_000): ByteArray? {
        if (!vivo) return null
        cerrojoComando.lock()
        try {
            val p = Peticion(opcode, parametros)
            enCurso = p
            porMandar.offer(p)
            val llego = p.listo.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!llego) {
                // Se abandona la espera, pero si la respuesta llega tarde se
                // repartira como un evento mas. Mejor eso que dejar el hueco
                // ocupado y que el siguiente comando case con la respuesta del
                // anterior — el desfase de un turno no se corrige solo.
                enCurso = null
            }
            return p.respuesta
        } finally {
            cerrojoComando.unlock()
        }
    }

    /**
     * Espera un evento que cumpla un filtro. Para lo que llega DESPUES del
     * Command Status: Connection Complete, Encryption Change, etc.
     */
    fun esperarEvento(timeoutMs: Long, filtro: (ByteArray) -> Boolean): ByteArray? {
        val encontrado = java.util.concurrent.atomic.AtomicReference<ByteArray>()
        val listo = CountDownLatch(1)
        val o = object : Oyente {
            override fun alEvento(evento: ByteArray) {
                if (encontrado.get() == null && runCatching { filtro(evento) }.getOrDefault(false)) {
                    encontrado.set(evento)
                    listo.countDown()
                }
            }
        }
        suscribir(o)
        try {
            listo.await(timeoutMs, TimeUnit.MILLISECONDS)
            return encontrado.get()
        } finally {
            quitar(o)
        }
    }

    // ------------------------------------------------------------------
    // Envio de datos
    // ------------------------------------------------------------------

    /**
     * Manda una PDU L2CAP por un enlace: la arma, la trocea y la encola.
     *
     * `canal` es el CID: 0x0004 para ATT, 0x0001 para la senalizacion
     * clasica, o el dinamico que haya repartido la senalizacion.
     *
     * Tres cosas pasan aqui y las tres importan:
     *
     *  1. **Cerrojo por enlace.** Los trozos de una PDU tienen que salir
     *     seguidos. Si dos hilos enviaran a la vez por el mismo handle, sus
     *     trozos se intercalarian y el otro lado reensamblaria una PDU con
     *     mitades de las dos. No da error: da datos falsos.
     *  2. **Un credito por trozo, no todos de golpe.** Reservar los N de una
     *     PDU larga se bloquearia para siempre si N fuera mayor que el pool
     *     entero (15 buffers en LE); pidiendo de uno en uno siempre se avanza.
     *  3. **Si falta credito a mitad, el enlace queda roto.** Los trozos ya
     *     mandados no se pueden recoger, asi que el otro lado se queda con una
     *     PDU incompleta. Se devuelve false y se anota: quien llame debe tirar
     *     el enlace, no reintentar el trozo.
     */
    fun enviarAcl(handle: Int, canal: Int, datos: ByteArray, timeoutMs: Long = 5_000): Boolean {
        if (!vivo) return false
        if (!hci.tieneAcl) {
            ultimoFallo = "este dongle no expone endpoints BULK: no hay camino de datos"
            return false
        }

        val pdu = L2cap.armar(canal, datos)
        val max = PaqueteAcl.maxDatosSeguro(flujo.tamPaquete, hci.tamBloqueSalida)
        val trozos = PaqueteAcl.trocear(handle, pdu, max)

        val cerrojo = cerrojosEnvio.getOrPut(handle) { ReentrantLock() }
        cerrojo.lock()
        try {
            for ((i, t) in trozos.withIndex()) {
                if (!flujo.reservar(handle, timeoutMs)) {
                    ultimoFallo = "sin creditos ACL tras ${timeoutMs}ms en el trozo $i de " +
                        "${trozos.size} (enlace 0x${"%03X".format(handle)} desincronizado)"
                    Log.w(TAG, ultimoFallo!!)
                    return false
                }
                // offer sobre una cola con tope devuelve false cuando esta
                // llena: se cuenta y se sigue, en vez de bloquear el hilo que
                // envia o crecer sin fin.
                if (!salida.offer(t)) trozosDescartados++
            }
            return true
        } finally {
            cerrojo.unlock()
        }
    }

    /**
     * Espera una PDU de un enlace y un CID concretos, y devuelve su carga.
     *
     * Existe para los dialogos de pregunta-respuesta (una peticion ATT, una
     * respuesta de senalizacion). Lo asincrono —notificaciones, datos que
     * llegan solos— se atiende registrando un [Oyente], que es para lo que
     * esta la bomba.
     */
    fun esperarPdu(handle: Int, cid: Int, timeoutMs: Long, filtro: ((ByteArray) -> Boolean)? = null): ByteArray? {
        val encontrado = java.util.concurrent.atomic.AtomicReference<ByteArray>()
        val listo = CountDownLatch(1)
        val o = object : Oyente {
            override fun alPdu(h: Int, pdu: ByteArray) {
                if (h != handle || L2cap.cidDe(pdu) != cid) return
                val carga = L2cap.cargaDe(pdu)
                if (filtro != null && !runCatching { filtro(carga) }.getOrDefault(false)) return
                if (encontrado.compareAndSet(null, carga)) listo.countDown()
            }
        }
        suscribir(o)
        try {
            listo.await(timeoutMs, TimeUnit.MILLISECONDS)
            return encontrado.get()
        } finally {
            quitar(o)
        }
    }

    // ------------------------------------------------------------------
    // Control de flujo: preguntarle al controlador cuanto aguanta
    // ------------------------------------------------------------------

    /**
     * Lee el pool LE y deja el control de flujo listo para enlaces LE.
     *
     * Respuesta medida en este dongle: `0E07010220001B000F`
     * -> paquete de 0x001B = **27 bytes**, 0x0F = **15 buffers**.
     *
     * Si el tamano viene 0, el controlador NO tiene pool LE propio y usa el de
     * BR/EDR: hay que preguntar por [configurarDesdeClasico]. Es un caso real
     * de la especificacion, no una rareza.
     */
    fun configurarDesdeLe(): String {
        val e = comando(HciUsb.CMD_LE_READ_BUFFER_SIZE)
            ?: return "LE_READ_BUFFER_SIZE: sin respuesta"
        // 0E | largo | num | opcode(2) | estado | tam(2) | numPaquetes(1)
        //  1     1       1      2          1        2         1        = 9 bytes
        //
        // Nueve, no diez. Con el tope en 10 este dongle —que contesta
        // exactamente 0E07010220001B000F— se rechazaba como "respuesta
        // corta", el control de flujo se quedaba en cero buffers, y entonces
        // NINGUN paquete ACL podia salir: la conexion LE se establecia bien y
        // luego no se podia mandar ni el Exchange MTU. Un byte de mas en una
        // comparacion tumbaba toda la capa de datos.
        if (e.size < 9) return "LE_READ_BUFFER_SIZE: respuesta corta ${HciUsb.hex(e)}"
        if ((e[5].toInt() and 0xFF) != 0) return "LE_READ_BUFFER_SIZE: estado ${e[5].toInt() and 0xFF}"
        val tam = (e[6].toInt() and 0xFF) or ((e[7].toInt() and 0xFF) shl 8)
        val num = e[8].toInt() and 0xFF
        if (tam == 0 || num == 0) {
            return "el controlador no tiene pool LE propio (tam=$tam num=$num): usar el de BR/EDR"
        }
        flujo.configurar(num, tam)
        return "pool LE: $num buffers de $tam bytes"
    }

    /**
     * Lee el pool BR/EDR. Es el que hace falta para el enlace clasico del
     * adaptador OBD, y tambien el de reserva si el LE no tiene pool propio.
     */
    fun configurarDesdeClasico(): String {
        val e = comando(HciUsb.CMD_READ_BUFFER_SIZE)
            ?: return "READ_BUFFER_SIZE: sin respuesta"
        // 0E | largo | num | opcode(2) | estado | acl(2) | sco(1) | numAcl(2) | numSco(2)
        if (e.size < 13) return "READ_BUFFER_SIZE: respuesta corta ${HciUsb.hex(e)}"
        if ((e[5].toInt() and 0xFF) != 0) return "READ_BUFFER_SIZE: estado ${e[5].toInt() and 0xFF}"
        val tam = (e[6].toInt() and 0xFF) or ((e[7].toInt() and 0xFF) shl 8)
        val num = (e[9].toInt() and 0xFF) or ((e[10].toInt() and 0xFF) shl 8)
        if (tam == 0 || num == 0) return "READ_BUFFER_SIZE devolvio pool vacio (tam=$tam num=$num)"
        flujo.configurar(num, tam)
        return "pool BR/EDR: $num buffers de $tam bytes"
    }

    /**
     * Ajusta el reensamblador de entrada al tamano que dijo el controlador.
     *
     * Se llama despues de configurar el pool: un paquete mas grande que eso no
     * puede venir del controlador, y aceptarlo seria tragarse un flujo
     * desalineado como si fuera un dato bueno.
     */
    fun ajustarEntrada() {
        reensambla = ReensamblaUsb.paraAcl(flujo.tamPaquete)
    }

    /**
     * Saca de la cola de salida los trozos de un handle que ya no existe.
     *
     * Se vacia y se vuelve a llenar solo con lo que sigue siendo valido: es la
     * unica forma de borrar del medio de una cola concurrente sin candados
     * nuevos, y la cola es corta por definicion.
     */
    private fun purgarSalida(handle: Int): Int {
        val rescatados = ArrayList<ByteArray>(TOPE_SALIDA)
        salida.drainTo(rescatados)
        var fuera = 0
        for (t in rescatados) {
            if (PaqueteAcl.handleDe(t) == handle) fuera++
            else if (!salida.offer(t)) trozosDescartados++
        }
        return fuera
    }

    fun diagnostico(): List<String> = listOf(
        "bomba: ${if (vivo) "viva" else "parada"}",
        "eventos leidos: $eventosLeidos, PDU recibidas: $pdusRecibidas",
        "trozos enviados: $trozosEnviados, fallidos: $trozosFallidos",
        "cola de salida: ${salida.size}, cola de reparto: ${reparto.size}, " +
            "perdidos por cola llena: $repartosPerdidos",
        "vueltas con fallo: $vueltasConFallo, ultimo: ${ultimoFallo ?: "ninguno"}",
        "reensamblado USB: ${reensambla.completos} paquetes, " +
            "${reensambla.longitudesImposibles} longitudes imposibles, " +
            "${reensambla.pendientes()} bytes a medias",
    ) + ensamblador.diagnostico() + flujo.diagnostico()

    private companion object {

        /**
         * Cuanto se espera a que un hilo muera. Mayor que una vuelta entera.
         */
        const val PLAZO_PARADA_MS = 2_000L

        /**
         * Tope de la cola de salida.
         *
         * Generoso pero finito: el pool de este dongle son 15 buffers, asi que
         * 256 trozos son mas de diez veces lo que el controlador puede tener
         * en vuelo. Si se llena, no es congestion: es una fuga.
         */
        const val TOPE_SALIDA = 256

        /**
         * Tope de la cola de cada oyente.
         *
         * Un oyente sano vacia su cola en microsegundos. Si llega a 128
         * pendientes es que se atasco, y lo correcto es descartar y contarlo
         * antes que crecer sin fin por culpa de uno solo.
         */
        const val TOPE_POR_OYENTE = 128

        const val TAG = "BombaHci"

        /**
         * Plazos cortos: la vuelta entera cuesta como mucho la suma de los
         * dos, y de eso depende cuanto tarda en salir un trozo que espera en
         * la cola. Demasiado corto quema CPU; demasiado largo hace lento el
         * dialogo con el ELM327, que son cientos de idas y vueltas por minuto.
         */
        const val ESPERA_EVENTO_MS = 15
        const val ESPERA_ACL_MS = 15

        /** Tope de operaciones por vuelta, para que ninguna ahogue a las otras. */
        const val MAX_POR_VUELTA = 8

        const val REPARTO_MAX = 256
    }
}
