package com.nonosky.carsoc.obd

import com.nonosky.carsoc.Carro
import android.util.Log
import com.nonosky.carsoc.ConnectionState
import com.nonosky.carsoc.VehicleState
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Round-robin por prioridad sobre el enlace K-line (§5 del diseño).
 *
 * El K-line es serial: una peticion, una respuesta, ~70-120 ms de ida y
 * vuelta. No hay peticiones multi-PID (eso es exclusivo de CAN), asi que el
 * presupuesto total son ~9-14 lecturas por segundo repartidas entre todo.
 *
 * Cada ciclo pide RPM — es lo unico que la aguja necesita seguir de cerca —
 * y como mucho un dato secundario mas, segun [Plan]. El ritmo lo marca el
 * enlace, no un temporizador: si el adaptador resulta mas rapido de lo
 * previsto, todo sube de frecuencia sin cambios de codigo.
 */
class PollScheduler(
    private val transportFactory: () -> ObdTransport,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _state = MutableStateFlow(VehicleState())
    val state: StateFlow<VehicleState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * El transporte de la conexion en curso.
     *
     * Se guarda para poder cerrarlo desde [stop]: un `read` bloqueado sobre
     * un `BluetoothSocket` NO se interrumpe con cancelar la corrutina — la
     * cancelacion de Kotlin es cooperativa y el hilo esta dentro de una
     * llamada nativa. Cerrar el socket es lo unico que lo desatora.
     */
    @Volatile
    private var currentTransport: ObdTransport? = null

    /** PIDs que contestaron mal 3 veces seguidas: fuera de la rotacion. */
    private val failures = mutableMapOf<String, Int>()
    private val disabled = mutableSetOf<String>()

    fun start() {
        if (job?.isActive == true) return
        // Dispatchers.IO y no el del scope: el scope que nos pasan suele ser
        // lifecycleScope, que corre en el hilo principal. Todo aqui abajo es
        // I/O bloqueante (BluetoothSocket, sleeps del transporte), asi que en
        // Main congelaria la UI y daria ANR en cuanto conectara.
        job = scope.launch(Dispatchers.IO) { runForever() }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { currentTransport?.close() }
        currentTransport = null
        // Antes de publicar Disconnected: quien tenga un lote esperando merece
        // saber que se paro el sondeo, en vez de comerse el plazo entero para
        // leer un "se agoto" que no distingue un sondeo detenido de uno
        // atascado dentro de un read.
        descartarPendientes("el sondeo se detuvo antes de ejecutarlo")
        _state.update { it.copy(connection = ConnectionState.Disconnected) }
    }

    // ------------------------------------------------------------------
    // COMANDOS A MANO, SOBRE LA MISMA SESION
    // ------------------------------------------------------------------
    //
    // La ruta /at existia solo por el camino del dongle, que guarda su sesion
    // en un campo y la comparte con un candado. Aqui eso no se puede copiar:
    // la Elm327Session nace y muere DENTRO de runForever(), es una local de
    // cada ciclo de conexion, y sacarla a un campo obligaria a publicar y
    // anular una referencia que cambia con cada reconexion, con la garantia de
    // que algun dia alguien la usaria despues de cerrada.
    //
    // Asi que el que pregunta no toca la sesion: deja el lote en una cola y se
    // duerme. El bucle de sondeo, que es el unico que tiene la sesion en la
    // mano, lo ejecuta entre dos turnos. Nadie abre un segundo enlace: el
    // ELM327 atiende a UNO, y este proyecto ya pago cuatro minutos de
    // reconexion por olvidarlo.

    /**
     * Un lote de comandos esperando turno.
     *
     * Objeto y no un par de listas porque quien pregunta y quien ejecuta son
     * hilos distintos: el del puente HTTP se duerme en [terminado] y el del
     * sondeo lo despierta. El `@Volatile` de [respuestas] sobra mientras el
     * resultado se lea despues del latch —el latch ya ordena la memoria— y se
     * deja puesto porque el dia que alguien lo lea sin esperar, el error seria
     * de los que no dan la cara.
     */
    private class Lote(val comandos: List<String>) {
        val terminado = java.util.concurrent.CountDownLatch(1)

        @Volatile
        var respuestas: List<String> = emptyList()
    }

    /**
     * Lotes en espera. Acotada a proposito.
     *
     * Sin tope, un sondeo atascado dentro de un `read` nativo acumularia
     * peticiones que nadie va a atender, y cada una se lleva un hilo del
     * puente. El puente tiene cuatro: bastarian cuatro preguntas para dejar el
     * radio incontactable, que es justo lo que no puede pasar en un carro.
     */
    private val lotesPendientes =
        java.util.concurrent.ArrayBlockingQueue<Lote>(CUPO_LOTES)

    /**
     * Manda comandos crudos al ELM327 y espera la respuesta.
     *
     * BLOQUEA al hilo que llama, que es un hilo del puente HTTP y jamas el del
     * sondeo. Devuelve una linea por comando con la misma forma que el camino
     * del dongle —"comando -> respuesta"— para que la misma pregunta se lea
     * igual venga por donde venga.
     */
    fun preguntar(comandos: List<String>): List<String> {
        if (comandos.isEmpty()) return listOf("no se mando ningun comando")

        // Todo o nada. Ejecutar hasta el prohibido y parar ahi dejaria un lote
        // a medias, y quien lee la respuesta tendria que adivinar donde se
        // corto y con que adaptador hablaron los de arriba.
        val vetados = comandos.mapNotNull { c ->
            porQueNoSePuede(c)?.let { "$c -> RECHAZADO: $it" }
        }
        if (vetados.isNotEmpty()) return vetados + "no se ejecuto nada del lote"

        // Mirar el enlace antes de encolar no es una optimizacion: si el
        // sondeo esta reconectando no hay nadie vaciando la cola, y quien
        // pregunta se comeria el plazo entero para leerse un tiempo agotado
        // que no explica por que.
        val enlace = _state.value.connection
        if (enlace != ConnectionState.Polling) {
            return listOf(
                "el sondeo no esta preguntando ahora mismo (esta en $enlace)",
                "sin sesion viva no hay donde mandar comandos; espera a que reconecte",
            )
        }

        val lote = Lote(comandos)
        if (!lotesPendientes.offer(lote)) {
            return listOf("hay $CUPO_LOTES lotes esperando turno; prueba en unos segundos")
        }

        if (lote.terminado.await(PLAZO_ESPERA_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            return lote.respuestas
        }

        // Sacarlo de la cola al rendirse. Un lote que se ejecuta cuando su
        // autor ya se fue es peor que uno que no se ejecuta: estos comandos se
        // mandan mirando el motor, y una respuesta que nadie va a leer solo
        // sirve para robarle turnos a la aguja.
        lotesPendientes.remove(lote)
        return listOf(
            "se agotaron los ${PLAZO_ESPERA_MS / 1000} s sin que el sondeo tomara el lote",
            "el enlace figura en ${_state.value.connection}; si sigue asi, " +
                "reinicia el motor con /fuente?cual=motor&on=0 y luego on=1",
        )
    }

    /**
     * Contesta a los que esperan que su lote se quedo sin sesion.
     *
     * Se llama al caerse el enlace y al detener el sondeo. Sin esto el unico
     * final posible seria el plazo agotado, que dice "no te contesto nadie"
     * cuando la verdad es "se cayo el enlace" — y con esa diferencia se decide
     * si vale la pena reintentar o hay que ir a mirar el adaptador.
     */
    private fun descartarPendientes(motivo: String) {
        while (true) {
            val lote = lotesPendientes.poll() ?: return
            lote.respuestas = lote.comandos.map { "$it -> NO EJECUTADO: $motivo" }
            lote.terminado.countDown()
        }
    }

    /**
     * Un lote por vuelta, entre turnos y sobre la MISMA sesion.
     *
     * Lo que le cuesta al ritmo, con los numeros del reparto: 60 turnos con 30
     * secundarios son 90 peticiones por periodo; a las ~10 lecturas/s de la
     * K-line eso es un periodo de unos 9 s, y de ahi salen los 6,7 Hz del RPM.
     * Cada comando a mano es UNA peticion mas. Un lote de doce se lleva algo
     * mas de un segundo, pero no lo reparte: lo cobra de golpe. O sea que no
     * es que el RPM baje a 5,9 Hz, es que la aguja se queda quieta un segundo
     * y despues salta.
     *
     * Es aceptable porque esto se dispara A MANO desde la laptop, un lote cada
     * vez y leyendo la respuesta antes de mandar el siguiente; nunca en bucle.
     * Un hueco de un segundo cada varios minutos no cambia como se lee el
     * tablero, y si se alargara, la vista ya pinta en gris lo rancio en vez de
     * fingir que el dato es de ahora. Lo que no seria aceptable es un hueco
     * SIN TECHO, y para eso esta [PLAZO_LOTE_MS].
     *
     * Uno por vuelta y no la cola entera: entre dos lotes se cuela una lectura
     * de RPM, asi preguntar dos veces seguidas no clava la aguja el doble.
     */
    private fun atenderComandos(session: Elm327Session) {
        val lote = lotesPendientes.poll() ?: return
        try {
            lote.respuestas = ejecutar(session, lote.comandos)
        } catch (e: Exception) {
            lote.respuestas = listOf("ERROR: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            // En finally sin excusas: un lote que no despierta a su autor lo
            // deja doce segundos mirando el reloj para leer algo que ya se
            // sabia aqui.
            lote.terminado.countDown()
        }
    }

    private fun ejecutar(session: Elm327Session, comandos: List<String>): List<String> {
        val salida = mutableListOf<String>()
        val limite = clock() + PLAZO_LOTE_MS
        var huboAt = false

        for (c in comandos) {
            if (clock() >= limite) {
                salida += "$c -> NO EJECUTADO: el lote agoto sus " +
                    "${PLAZO_LOTE_MS / 1000} s y la aguja no puede esperar mas"
                continue
            }
            if (normalizado(c).startsWith("AT")) huboAt = true
            salida += "$c -> " + (session.queryRaw(c, PLAZO_COMANDO_MS) ?: "sin respuesta")
        }

        // Devolver el adaptador a como lo dejo initialize().
        //
        // Los cuatro de formato NO rompen la decodificacion —se comprobo
        // leyendo PidDecoder.payloadOf: parte por lineas, se traga el eco
        // porque lleva el modo 01 y no el 41, busca el prefijo DENTRO de la
        // linea asi que el encabezado de ATH1 le da igual, y filtra todo lo
        // que no sea hexadecimal, asi que los espacios de ATS1 tampoco
        // molestan—. Pero engordan cada respuesta, y sobre un enlace que ya va
        // al limite eso son lecturas que dejan de caber.
        //
        // No se hila mas fino sobre cuales AT tocan estado y cuales no porque
        // la lista de los que lo tocan en silencio (ATAT, ATST, ATCAF, ATCFC,
        // ATSH, ATTA...) es mas larga que la de los que no, y reponer cuesta
        // cinco ordenes que el adaptador atiende el solo, sin salir a la
        // K-line: unas decenas de milisegundos que no le quitan turno a nadie.
        if (huboAt) {
            for (a in AJUSTES_DE_INICIO) runCatching { session.queryRaw(a, PLAZO_AJUSTE_MS) }
            salida += "(repuestos " + AJUSTES_DE_INICIO.joinToString(" ") +
                ", que es lo que fija initialize)"
        }
        return salida
    }

    private suspend fun runForever() {
        var attempt = 0
        while (coroutineContext.isActive) {
            var transport: ObdTransport? = null
            // Cierto si el adaptador contesto pero la ECU no dio ni un RPM.
            var ecuMuda = false
            try {
                _state.update { it.copy(connection = ConnectionState.Connecting) }
                transport = transportFactory()
                currentTransport = transport
                val t0 = clock()
                transport.connect()
                com.nonosky.carsoc.EstadoActual.ultimoErrorEnlace =
                    "conectado en " + (clock() - t0) + " ms"

                _state.update { it.copy(connection = ConnectionState.Initializing) }
                val session = Elm327Session(transport)
                val info = session.initialize()
                // El adaptador contesto. Que la ECU conteste o no se sabra en
                // el pollLoop; esto ya dice que hay alguien al otro lado.
                com.nonosky.carsoc.EstadoActual.adaptadorContestoMs = clock()

                _state.update {
                    it.copy(
                        connection = ConnectionState.Polling,
                        protocol = info.describedAs,
                    )
                }
                failures.clear()
                disabled.clear()

                // El retroceso vuelve a cero SOLO si la ECU llego a contestar.
                // Antes volvia a cero en cuanto el adaptador respondia, y con
                // el ELM327 alimentado y el contacto quitado eso era un bucle
                // sin fin: RFCOMM nuevo, ATZ, dos sondeos de bus de 10 s, seis
                // ciclos sin RPM y a empezar, cada ~25 s para siempre.
                ecuMuda = !pollLoop(session)
                if (!ecuMuda) attempt = 0
            } catch (e: Exception) {
                coroutineContext.ensureActive()   // cancelacion no es un fallo
                Log.w(TAG, "Ciclo de conexion caido: ${e.message}")
                com.nonosky.carsoc.EstadoActual.ultimoErrorEnlace =
                    e.javaClass.simpleName + ": " + e.message
            } finally {
                runCatching { transport?.close() }
                if (currentTransport === transport) currentTransport = null
                // La sesion que iba a ejecutar los lotes acaba de morir con el
                // transporte. Dejarlos en la cola solo cambia "se cayo el
                // enlace" por "se agoto el plazo", y esas dos respuestas piden
                // cosas distintas de quien las lee.
                descartarPendientes("el enlace se cayo antes de ejecutarlo")
            }

            coroutineContext.ensureActive()
            // Se conservan los ultimos valores; solo cambia el estado, y la
            // vista los pintara en gris cuando pasen de rancios.
            _state.update { it.copy(connection = ConnectionState.Disconnected) }

            val wait = if (ecuMuda) backoffEcuMudaMs(attempt++) else backoffMs(attempt++)
            if (ecuMuda) {
                com.nonosky.carsoc.EstadoActual.ultimoErrorEnlace =
                    "el adaptador contesta pero la ECU no (¿contacto quitado?); " +
                    "reintento en ${wait / 1000} s"
            }
            Log.i(TAG, "Reintento en ${wait} ms")
            delay(wait)
        }
    }

    /** 1 s, 2 s, 4 s, 8 s, con techo de 10 s (§9). */
    internal fun backoffMs(attempt: Int): Long =
        minOf(1000L shl attempt.coerceIn(0, 10), MAX_BACKOFF_MS)

    /**
     * Cuando el adaptador contesta pero la ECU no: el mismo 1-2-4-8 s, con
     * techo de 20 s en vez de 10. Cada vuelta en ese estado cuesta un enlace
     * RFCOMM y unos 20 s de sondeo de K-line; el techo es lo que puede tardar
     * de mas el RPM en aparecer al girar la llave.
     */
    internal fun backoffEcuMudaMs(attempt: Int): Long =
        minOf(1000L shl attempt.coerceIn(0, 10), MAX_BACKOFF_ECU_MUDA_MS)

    /** @return true si la ECU entrego al menos un RPM en esta sesion. */
    private suspend fun pollLoop(session: Elm327Session): Boolean {
        var cycle = 0
        var consecutiveDead = 0
        var huboRpm = false

        while (coroutineContext.isActive) {
            // El RPM va en todos los ciclos: es lo que mueve la aguja.
            val gotRpm = readAndApply(session, PidDecoder.PID_RPM)
            consecutiveDead = if (gotRpm) 0 else consecutiveDead + 1
            if (gotRpm) huboRpm = true

            // Si ni el RPM contesta varias veces seguidas, el enlace se cayo
            // (motor apagado, adaptador desconectado): salir y reconectar.
            if (consecutiveDead >= DEAD_LINK_THRESHOLD) {
                Log.w(TAG, "Enlace muerto tras $consecutiveDead ciclos sin RPM")
                return huboRpm
            }

            Plan.secondaryFor(cycle)
                ?.takeIf { it !in disabled }
                ?.let { readAndApply(session, it) }

            // Al final de la vuelta y no al principio: asi el RPM de este
            // ciclo ya esta publicado cuando el lote se lleve el enlace.
            atenderComandos(session)

            cycle++
            // Ceder el hilo sin frenar el ritmo: el round-trip del K-line ya
            // es el limitante real, no hace falta un delay artificial.
            delay(1)
        }
        return huboRpm
    }

    /** @return true si el PID entrego una muestra utilizable. */
    private fun readAndApply(session: Elm327Session, pid: String): Boolean {
        if (pid == PID_VOLTAGE) {
            val v = session.readVoltage()
            if (v != null) _state.update { it.copy(batteryV = v, batteryAtMs = clock()) }
            return v != null
        }

        val raw = session.queryRaw(pid)
        val now = clock()
        // `update` y no `value = value.copy(...)`: lo segundo es un
        // leer-modificar-escribir que puede perder una muestra si algo mas
        // toca el estado entre medias.
        val applied = when (pid) {
            PidDecoder.PID_RPM -> PidDecoder.decodeRpm(raw)?.also { rpm ->
                _state.update {
                    it.copy(
                        rpm = rpm,
                        rpmAtMs = now,
                        sessionMaxRpm = maxOf(it.sessionMaxRpm, rpm),
                    )
                }
            }
            PidDecoder.PID_SPEED -> PidDecoder.decodeSpeed(raw)?.also { v ->
                _state.update { it.copy(speedKmh = v, speedAtMs = now) }
            }
            PidDecoder.PID_LOAD -> PidDecoder.decodeLoad(raw)?.also { v ->
                _state.update { it.copy(loadPct = v, loadAtMs = now) }
            }
            PidDecoder.PID_COOLANT -> PidDecoder.decodeCoolant(raw)?.also { v ->
                _state.update { it.copy(coolantC = v, coolantAtMs = now) }
            }
            PidDecoder.PID_IAT -> PidDecoder.decodeIat(raw)?.also { v ->
                _state.update { it.copy(iatC = v, iatAtMs = now) }
            }
            // La columna de ADMISION. Estos cuatro tenian campo en
            // VehicleState, decodificador propio y sitio reservado en la
            // pantalla desde el principio — pero nadie los pedia, asi que
            // salian en guiones para siempre. Se vio con el motor andando:
            // COLECTOR, ACELERADOR y AVANCE vacios mientras una prueba
            // directa por /obd-spp devolvia 410B55, o sea 85 kPa.
            PidDecoder.PID_MAP -> PidDecoder.decodeMap(raw)?.also { v ->
                _state.update { it.copy(mapKpa = v, mapAtMs = now) }
            }
            PidDecoder.PID_ACELERADOR -> PidDecoder.decodeAcelerador(raw)?.also { v ->
                _state.update { it.copy(aceleradorPct = v, aceleradorAtMs = now) }
            }
            PidDecoder.PID_AVANCE -> PidDecoder.decodeAvance(raw)?.also { v ->
                _state.update { it.copy(avanceGrados = v, avanceAtMs = now) }
            }
            PidDecoder.PID_LAMBDA -> PidDecoder.decodeLambda(raw)?.also { v ->
                _state.update { it.copy(lambda = v, lambdaAtMs = now) }
            }
            PidDecoder.PID_TRIM_CORTO -> PidDecoder.decodeTrim(raw, pid)?.also { v ->
                _state.update { it.copy(trimCortoPct = v, trimCortoAtMs = now) }
            }
            PidDecoder.PID_TRIM_LARGO -> PidDecoder.decodeTrim(raw, pid)?.also { v ->
                _state.update { it.copy(trimLargoPct = v, trimLargoAtMs = now) }
            }
            // Devuelve el numero de codigos, no el Pair: todas las ramas de
            // este `when` tienen que compartir tipo o el `applied != null`
            // de abajo se queda sin `equals` utilizable.
            PidDecoder.PID_ESTADO -> PidDecoder.decodeMil(raw)?.let { (mil, n) ->
                _state.update {
                    it.copy(milEncendida = mil, codigosGuardados = n, estadoAtMs = now)
                }
                n
            }
            else -> null
        }

        if (applied != null) {
            failures.remove(pid)
            return true
        }

        // Tres fallos seguidos en el mismo PID: el carro no lo soporta.
        // Sacarlo libera su presupuesto para el resto (§10).
        val n = (failures[pid] ?: 0) + 1
        failures[pid] = n
        if (n >= UNSUPPORTED_THRESHOLD && pid != PidDecoder.PID_RPM) {
            Log.i(TAG, "$pid no soportado tras $n fallos; fuera de la rotacion")
            disabled += pid
        }
        return false
    }

    /**
     * Que dato secundario toca en cada ciclo. Pura y sin estado a proposito:
     * asi las proporciones de §5 se verifican en la JVM sin adaptador.
     *
     * Es una tabla explicita de 60 ciclos y no una cadena de modulos porque
     * los modulos se pisan entre si: "cada 3" y "cada 20" coinciden una de
     * cada tres veces, y el de mayor prioridad le robaria el turno al otro.
     * Con la tabla los turnos son disjuntos por construccion y cada PID
     * recibe exactamente la frecuencia que §5 le asigna.
     *
     * 60 es el minimo comun multiplo de 3, 10 y 20: el patron cierra justo.
     */
    object Plan {
        const val PERIOD = 60

        // LA VELOCIDAD SE FUE, y con ella 20 de los 35 turnos.
        //
        // La quito el dueño: el carro ya la tiene en el cuadro original a la
        // altura de los ojos, igual que el tacometro, y repetirla aqui gastaba
        // un tercio del presupuesto entero de la K-line en un dato duplicado.
        // Esos 20 turnos son los que hacen posible la columna de ADMISION.
        private val LOAD_SLOTS = listOf(1, 11, 22, 31, 41, 52)       // 6  -> ~0.6 Hz
        private val COOLANT_SLOTS = listOf(4, 25, 44)                // 3  -> ~0.3 Hz
        private val IAT_SLOTS = listOf(14, 34, 55)                   // 3, desfasado del agua
        private val VOLTAGE_SLOTS = listOf(8, 28, 49)                // 3, gratis

        // La columna de ADMISION, en huecos que estaban VACIOS.
        //
        // Se eligieron turnos libres, sin quitarselos a nadie: de los 60
        // slots habia 25 sin asignar y aqui se ocupan 16, dejando 9 de
        // margen. El colector es el que mas se mueve, asi que lleva mas.
        //
        // La mezcla va por 0114 —voltaje de sonda de banda estrecha— y no
        // por 0134: el mapa de PIDs de esta ECU no soporta nada por encima
        // del 0x20, y pedir el AFR de banda ancha solo gastaba turnos para
        // recibir vacio.
        // La columna de ADMISION, pagada con los turnos de la velocidad.
        //
        // El presupuesto manda: la K-line va a ~10 lecturas/s y §5 le reserva
        // al RPM 6 Hz, o sea 600/(60+S) >= 6, o sea S <= 40. Con la velocidad
        // fuera quedan 15 basicos, asi que hay 25 turnos para repartir y aqui
        // se usan 20 — el RPM queda en 6,3 Hz y sobra margen.
        //
        // El colector y el acelerador se mueven rapido y llevan 6 cada uno
        // (~0,6 Hz). El avance y la mezcla cambian mas despacio y llevan 4.
        private val MAP_SLOTS = listOf(0, 9, 18, 27, 36, 45)         // 6
        // El ACELERADOR ya no se pide: el dueño lo quito de la pantalla
        // porque el pie ya sabe donde esta. Sus seis turnos vuelven al
        // presupuesto y el RPM sube de 6,0 a 7,1 Hz.
        private val AVANCE_SLOTS = listOf(6, 20, 39, 51)             // 4
        // Aqui vivian los 4 turnos del O2 (PID 0114). Se van y NO se
        // reparten: menos trafico en la K-line significa que el resto de
        // lecturas llegan antes, y el RPM sube de 6,0 a 6,7 Hz.
        //
        // El voltaje de la sonda dejo de pintarse cuando MEZCLA paso a salir
        // de los ajustes de combustible 0106/0107, que si son un porcentaje
        // medido. Desde entonces se le preguntaba a la ECU cuatro veces por
        // periodo para tirar la respuesta. Y una sonda que se muere ya la
        // delatan los ajustes —se disparan— y el modulo de averias, que trae
        // P0131 a P0135 y P1163 a P1167 con su explicacion.

        // Los ajustes de combustible y la luz de averia: los cinco turnos
        // que quedaban del presupuesto (S llega justo a 40, RPM a 6,0 Hz).
        //
        // Se piden pocas veces a proposito y no es tacañeria: un ajuste de
        // combustible se mueve en decenas de segundos, no en decimas. Lo que
        // importa de el es la TENDENCIA — si esta en +2% o en +22% — y para
        // eso sobra con mirarlo cada diez segundos. La luz de averia cambia
        // aun menos: un turno basta.
        private val TRIM_CORTO_SLOTS = listOf(21, 42)                // 2
        private val TRIM_LARGO_SLOTS = listOf(12, 48)                // 2
        private val ESTADO_SLOTS = listOf(58)                        // 1

        // LA MEZCLA DE VERDAD: el 0134 de la sonda de banda ancha, en seis
        // huecos que estaban libres. La mezcla se mueve rapido —acelerar,
        // soltar, corte en retencion— y por eso lleva tantos como el colector.
        // Solo en el carro cuyo perfil dice `tieneAfrReal`: en otro, esos
        // turnos quedan libres (ver secondaryFor).
        private val LAMBDA_SLOTS = listOf(3, 13, 23, 33, 43, 53)     // 6

        private val table: Array<String?> = arrayOfNulls<String>(PERIOD).also { t ->
            LOAD_SLOTS.forEach { t[it] = PidDecoder.PID_LOAD }
            COOLANT_SLOTS.forEach { t[it] = PidDecoder.PID_COOLANT }
            IAT_SLOTS.forEach { t[it] = PidDecoder.PID_IAT }
            VOLTAGE_SLOTS.forEach { t[it] = PID_VOLTAGE }
            MAP_SLOTS.forEach { t[it] = PidDecoder.PID_MAP }
            AVANCE_SLOTS.forEach { t[it] = PidDecoder.PID_AVANCE }
            TRIM_CORTO_SLOTS.forEach { t[it] = PidDecoder.PID_TRIM_CORTO }
            TRIM_LARGO_SLOTS.forEach { t[it] = PidDecoder.PID_TRIM_LARGO }
            ESTADO_SLOTS.forEach { t[it] = PidDecoder.PID_ESTADO }
            LAMBDA_SLOTS.forEach { t[it] = PidDecoder.PID_LAMBDA }
        }

        fun secondaryFor(cycle: Int): String? = deEsteCarro(table[Math.floorMod(cycle, PERIOD)])

        /** La lambda solo existe en el carro que la tiene. */
        private fun deEsteCarro(pid: String?): String? =
            if (pid == PidDecoder.PID_LAMBDA && !Carro.perfil.tieneAfrReal) null else pid

        /** Cuantas veces se pide [pid] por periodo. El RPM, en todos. */
        fun vecesPorPeriodo(pid: String): Int =
            if (pid == PidDecoder.PID_RPM) PERIOD else table.count { deEsteCarro(it) == pid }

        /**
         * Lecturas por segundo que se le suponen a la K-line para calcular
         * cuando caduca un dato. Va por lo BAJO a proposito (en un carro se
         * midio ~6,7): suponer de mas haria caducar datos que solo van lentos.
         */
        const val LECTURAS_POR_SEGUNDO_PRUDENTE = 5.0

        /**
         * Cuanto vale una lectura de [pid] antes de darla por vieja.
         *
         * ⚠️ ANTES ERAN 3 s PARA TODO, y casi nada se lee tan seguido: el agua
         * y el aire van cada ~5 s, los ajustes cada ~7 y la luz de averia cada
         * ~14. Entre lectura y lectura el dato caducaba y el tablero lo pintaba
         * en blanco con el enlace perfecto — el dueño lo vio parpadear.
         *
         * Ahora un dato caduca cuando faltan DOS lecturas suyas seguidas: dos
         * veces y media lo que tarda en volver a pedirse, con el ritmo prudente
         * de arriba. Nunca menos que el plazo del motor (3 s, el del RPM).
         */
        fun plazoMs(pid: String, minimoMs: Long): Long {
            val veces = vecesPorPeriodo(pid)
            if (veces <= 0) return minimoMs
            val lecturasPeriodo = PERIOD + table.count { deEsteCarro(it) != null }
            val periodoMs = lecturasPeriodo * 1000.0 / LECTURAS_POR_SEGUNDO_PRUDENTE
            return maxOf(minimoMs, (2.5 * periodoMs / veces).toLong())
        }
    }

    companion object {
        private const val TAG = "PollScheduler"
        const val PID_VOLTAGE = "ATRV"
        const val UNSUPPORTED_THRESHOLD = 3
        const val DEAD_LINK_THRESHOLD = 6
        const val MAX_BACKOFF_MS = 10_000L
        const val MAX_BACKOFF_ECU_MUDA_MS = 20_000L

        /** Lotes de comandos a mano que caben esperando turno. */
        const val CUPO_LOTES = 2

        /**
         * Lo que se le concede a UN comando suelto.
         *
         * Cuatro segundos y no los 350 ms del sondeo: por aqui se pregunta lo
         * que no se pregunta a diario —mapas de PIDs, modo 09, modo 03— y esas
         * respuestas llegan en varias tramas y tardan. Es el mismo plazo que
         * usa el camino del dongle, para que la misma pregunta se conteste
         * igual venga por donde venga.
         */
        const val PLAZO_COMANDO_MS = 4_000L

        /**
         * Techo del lote ENTERO, que es lo que protege a la aguja.
         *
         * Doce comandos sin respuesta a cuatro segundos cada uno serian casi
         * un minuto con el tacometro clavado, y eso ya no es un hueco: es el
         * tablero mintiendo. Pasado el techo, los que queden se contestan
         * diciendo que no se ejecutaron, que es informacion y no una excusa.
         */
        const val PLAZO_LOTE_MS = 8_000L

        /**
         * Lo que espera quien pregunta, contando la espera a que le toque.
         *
         * El techo del lote mas margen para que el sondeo lo recoja. No mucho
         * mas: /at no esta entre las RUTAS_LENTAS del puente y quien pregunta
         * esta mirando la terminal, asi que vale mas una respuesta que diga
         * "no lo tomo nadie" que un cliente colgado.
         */
        const val PLAZO_ESPERA_MS = 12_000L

        /** Los ajustes locales son inmediatos: no salen a la K-line. */
        private const val PLAZO_AJUSTE_MS = 500L

        /**
         * Lo que [Elm327Session.initialize] deja fijado y aqui se repone.
         *
         * No lleva ATSP5 a proposito: reponer el protocolo dispararia otro BUS
         * INIT, que son segundos. Los que estan son ordenes que el adaptador
         * atiende el solo, sin tocar el bus del carro.
         */
        private val AJUSTES_DE_INICIO = listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")

        /**
         * El ELM327 se come los espacios DENTRO del comando: "AT SP 0" y
         * "ATSP0" son la misma orden para el. Y /at solo hace trim y uppercase,
         * asi que si el filtro mirara el texto tal cual llega bastaria un
         * espacio para colarle al sondeo un cambio de protocolo.
         */
        private fun normalizado(comando: String): String =
            comando.uppercase().filter { !it.isWhitespace() }

        /**
         * Por que este comando no puede ir por aqui, o `null` si si puede.
         *
         * El corte no es "peligroso/inofensivo" sino "¿puede el sondeo seguir
         * sin enterarse?". Los de formato —ATE1, ATH1, ATS1, ATL1— si pueden:
         * el parser los aguanta y ademas se reponen al cerrar el lote, asi que
         * pasan. Estos no. Dejan el adaptador hablando otro idioma mientras el
         * bucle sigue pidiendo RPM como si nada, y lo unico que lo saca es
         * acumular DEAD_LINK_THRESHOLD lecturas muertas para tirar el socket y
         * reconectar entero — contra este clon eso ya se midio costando CUATRO
         * MINUTOS cuando se reconecta con prisa. Nada de lo que se averigua
         * preguntando vale ese apagon.
         *
         * Rechazar no quita funcionalidad: si lo que se quiere es un adaptador
         * virgen, /fuente?cual=motor&on=0 seguido de on=1 lo deja limpio, y
         * /pids y /dtc ya abren su propia sesion sabiendo esperar a que el
         * adaptador suelte el canal.
         *
         * `internal` para que la prueba pueda afirmarlo en la JVM, sin carro.
         */
        internal fun porQueNoSePuede(comando: String): String? {
            val c = normalizado(comando)
            return when {
                c == "ATZ" || c == "ATD" || c == "ATWS" ->
                    "reinicia el adaptador: se pierde el protocolo y vuelve el eco, " +
                        "y el sondeo tarda $DEAD_LINK_THRESHOLD lecturas muertas en " +
                        "enterarse. Para empezar limpio: /fuente?cual=motor&on=0 y luego on=1"
                c.startsWith("ATSP") ->
                    "cambia el protocolo bajo los pies del sondeo, que seguiria " +
                        "preguntando igual. initialize() ya fija ATSP5 y cae solo a " +
                        "ATSP0 si el carro no contesta"
                c.startsWith("ATMA") || c.startsWith("ATMR") || c.startsWith("ATMT") ->
                    "los modos monitor no devuelven el prompt '>': el hilo del sondeo " +
                        "esperaria el plazo entero y despues leeria el chorro como si " +
                        "fueran respuestas a otras preguntas"
                c == "ATLP" ->
                    "duerme el adaptador, y con el se va el enlace"
                c.startsWith("ATPP") || c.startsWith("ATBRD") ||
                    c.startsWith("ATBRT") || c.startsWith("ATIB") ->
                    "toca parametros del propio adaptador, y los PP se guardan en " +
                        "memoria NO volatil: eso sobrevive a desenchufarlo y desde aqui " +
                        "no hay como deshacerlo"
                c == "04" ->
                    "borra los codigos de averia de verdad, y con ellos la trama " +
                        "congelada y los monitores de emisiones. Para eso esta " +
                        "/dtc?borrar=1, que para el sondeo antes y avisa si el motor gira"
                else -> null
            }
        }
    }
}
