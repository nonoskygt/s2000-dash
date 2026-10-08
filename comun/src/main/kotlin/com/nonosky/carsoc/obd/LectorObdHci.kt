package com.nonosky.carsoc.obd

import android.content.Context
import android.util.Log
import com.nonosky.carsoc.ConnectionState
import com.nonosky.carsoc.EstadoActual
import com.nonosky.carsoc.VehicleState
import com.nonosky.carsoc.hci.DuenoDongle
import kotlin.concurrent.thread

/**
 * Lee el motor por el dongle USB, en el servicio y no en la pantalla.
 *
 * El sondeo vivia en la Activity y usaba un `BluetoothDevice` de la pila de
 * Android. Esa pila esta rota y ademas ahora la tenemos **apagada a
 * proposito**, porque era ella la que le robaba el adaptador Steren al dongle
 * y provocaba el PAGE TIMEOUT. Asi que el motor tenia que mudarse aqui, junto
 * al TPMS y la bateria, que ya viven en el servicio por la misma razon: hay
 * que seguir midiendo con el tablero cerrado.
 *
 * ----------------------------------------------------------------------------
 * EL REPARTO DEL DONGLE
 * ----------------------------------------------------------------------------
 * Hay **un** dongle y dos cosas que lo quieren de forma continua: el motor y
 * la bateria. No hay manera de que las dos lo tengan a la vez, asi que se
 * turnan, y el turno es desigual a proposito:
 *
 *   - El motor manda. Sus datos cambian segundo a segundo y son los que se
 *     miran manejando.
 *   - La bateria se conforma con una lectura cada pocos minutos. Un SoC no
 *     cambia en diez segundos, y el voltaje de una LiFePO4 tampoco.
 *
 * Reconectar cuesta caro —emparejar e inquiry rondan los diez segundos— asi
 * que el motor **mantiene** el enlace y solo lo suelta cuando le toca a la
 * bateria. Soltarlo en cada vuelta seria pasarse la vida reconectando.
 */
class LectorObdHci(
    private val context: Context,
    private val mac: String,
) {

    @Volatile
    private var vivo = false
    private var hilo: Thread? = null

    /** Ultima traza de conexion, para /obd-hci y para diagnosticar en remoto. */
    @Volatile
    var ultimaTraza: List<String> = emptyList()
        private set

    @Volatile
    var cicloDeTurnos: Long = 0
        private set

    /**
     * La sesion viva, para poder hacerle preguntas sueltas desde el puente.
     *
     * Se comparte en vez de abrir un enlace aparte porque el ELM327 atiende a
     * uno solo: un segundo enlace tiraria el primero. El candado serializa las
     * preguntas contra el sondeo, que es lo unico que hace falta — el dialogo
     * AT es pregunta/respuesta y no admite dos a la vez.
     */
    @Volatile
    private var sesionViva: Elm327Session? = null

    private val candadoSesion = Any()

    /**
     * Manda comandos crudos y devuelve lo que conteste el adaptador.
     *
     * Sirve para averiguar que soporta el carro sin adivinar: preguntandole a
     * la ECU por su mapa de PIDs se sabe exactamente que hay, en vez de
     * confiar en lo que "deberia" tener un modelo de ese año.
     */
    fun preguntar(comandos: List<String>): List<String> = synchronized(candadoSesion) {
        val s = sesionViva ?: return listOf("no hay enlace vivo con el adaptador")
        comandos.map { c ->
            val r = runCatching { s.queryRaw(c, 4_000) }.getOrNull()
            "$c -> ${r ?: "sin respuesta"}"
        }
    }

    fun arrancar() {
        if (vivo) return
        vivo = true
        // Hilo propio y envuelto entero: una excepcion que escape de aqui se
        // lleva el proceso, y con el el tablero, el puente y el actualizador.
        hilo = thread(name = "lector-obd", isDaemon = true) {
            while (vivo) {
                runCatching { unTurno() }
                    .onFailure { Log.w(TAG, "turno fallido: ${it.message}") }
                dormir(ESPERA_ENTRE_TURNOS_MS)
            }
        }
    }

    fun detener() {
        vivo = false
        runCatching { hilo?.interrupt() }
        hilo = null
    }

    private fun dormir(ms: Long) {
        val hasta = System.currentTimeMillis() + ms
        while (vivo && System.currentTimeMillis() < hasta) {
            runCatching { Thread.sleep(300) }.onFailure { return }
        }
    }

    /**
     * Conecta y se queda sondeando mientras el enlace aguante.
     *
     * Ya no hay turnos: la radio es compartida y el enlace del motor convive
     * con el de la bateria. Lo de turnarse venia de que cada parte abria su
     * propio dongle, y eso dejaba al motor en linea solo a ratos — justo lo
     * que no sirve para mirar de reojo mientras se maneja.
     */
    private fun unTurno() {
        // Con el radio caliente no se abre enlace: el sondeo del motor es lo
        // que mas CPU gasta de todo el tablero.
        if (!com.nonosky.carsoc.Termometro.permiteObd()) {
            EstadoActual.ultimoErrorEnlace =
                "en pausa: radio a ${com.nonosky.carsoc.Termometro.gradosC} C"
            publicar(ConnectionState.Disconnected)
            return
        }
        publicar(ConnectionState.Connecting)
        turnoConDongle()
        cicloDeTurnos++
    }

    private fun turnoConDongle() {
        val t = HciObdTransport(context, mac)
        try {
            t.connect()
            ultimaTraza = t.traza.toList()
            publicar(ConnectionState.Initializing)

            val sesion = Elm327Session(t)
            sesionViva = sesion
            val info = sesion.initialize()
            EstadoActual.ultimoErrorEnlace = null
            publicar(ConnectionState.Polling, protocolo = info.describedAs)

            val hasta = System.currentTimeMillis() + TURNO_MS
            while (vivo && System.currentTimeMillis() < hasta && t.isConnected &&
                com.nonosky.carsoc.Termometro.permiteObd()
            ) {
                synchronized(candadoSesion) { sondearUnaVuelta(sesion) }
                runCatching { Thread.sleep(ENTRE_VUELTAS_MS) }
            }
        } catch (e: Exception) {
            ultimaTraza = t.traza.toList()
            EstadoActual.ultimoErrorEnlace = "${e.javaClass.simpleName}: ${e.message}"
            publicar(ConnectionState.Disconnected)
        } finally {
            sesionViva = null
            runCatching { t.close() }
        }
    }

    /**
     * Una vuelta de PIDs.
     *
     * El orden importa poco, pero el voltaje va primero a proposito: lo da el
     * propio adaptador con ATRV y no gasta el bus K-line del motor, que es
     * lento. Si algo se cae a media vuelta, al menos ese ya se publico.
     */
    private fun sondearUnaVuelta(sesion: Elm327Session) {
        val ahora = System.currentTimeMillis()
        var s = EstadoActual.ultimo

        sesion.readVoltage()?.let { s = s.copy(batteryV = it, batteryAtMs = ahora) }

        PidDecoder.decodeRpm(sesion.queryRaw(PidDecoder.PID_RPM))
            ?.let { s = s.copy(rpm = it, rpmAtMs = ahora, sessionMaxRpm = maxOf(s.sessionMaxRpm, it)) }
        PidDecoder.decodeCoolant(sesion.queryRaw(PidDecoder.PID_COOLANT))
            ?.let { s = s.copy(coolantC = it, coolantAtMs = ahora) }
        PidDecoder.decodeIat(sesion.queryRaw(PidDecoder.PID_IAT))
            ?.let { s = s.copy(iatC = it, iatAtMs = ahora) }
        // Se dejo de pedir el 0134 y el 0124 (lambda de banda ancha): el mapa
        // de PIDs de esta ECU (0100 -> BE3EF810) tiene el bit del 0x20 en
        // CERO, o sea que no soporta NADA por encima del PID 0x20. Pedirlos
        // era gastar una peticion de K-line por vuelta para recibir NO DATA.
        PidDecoder.decodeMap(sesion.queryRaw(PidDecoder.PID_MAP))
            ?.let { s = s.copy(mapKpa = it, mapAtMs = ahora) }
        PidDecoder.decodeAcelerador(sesion.queryRaw(PidDecoder.PID_ACELERADOR))
            ?.let { s = s.copy(aceleradorPct = it, aceleradorAtMs = ahora) }
        PidDecoder.decodeAvance(sesion.queryRaw(PidDecoder.PID_AVANCE))
            ?.let { s = s.copy(avanceGrados = it, avanceAtMs = ahora) }

        EstadoActual.ultimo = s
        runCatching { EstadoActual.alCambiarObd?.invoke() }
    }

    private fun publicar(estado: ConnectionState, protocolo: String? = null) {
        EstadoActual.ultimo = EstadoActual.ultimo.copy(
            connection = estado,
            protocol = protocolo ?: EstadoActual.ultimo.protocol,
        )
        runCatching { EstadoActual.alCambiarObd?.invoke() }
    }

    private companion object {
        const val TAG = "LectorObdHci"

        /**
         * Cuanto dura un turno del motor con el dongle.
         *
         * Largo a proposito: conectar cuesta unos diez segundos entre inquiry
         * y emparejamiento, asi que turnos cortos serian casi todo reconexion
         * y casi nada de datos.
         */
        /**
         * Cuanto se mantiene un enlace antes de renovarlo.
         *
         * Muy largo: el enlace se conserva y solo se renueva por si el
         * adaptador se hubiera colgado. Reconectar cuesta unos diez segundos
         * entre inquiry y emparejamiento, asi que renovar seguido seria
         * cambiar datos por reconexiones.
         */
        const val TURNO_MS = 3_600_000L

        /** Si el enlace se cae, cuanto se espera antes de reintentar. */
        const val ESPERA_ENTRE_TURNOS_MS = 5_000L

        /**
         * Entre vueltas de PIDs.
         *
         * El dueño pidio uno o dos segundos de refresco. La K-line da
         * unas 9 lecturas por segundo y una vuelta gasta 5 peticiones, asi que
         * una vuelta tarda algo mas de medio segundo: con esta pausa sale
         * alrededor de un segundo por vuelta, que es lo pedido sin ahogar el bus.
         */
        /**
         * Un segundo entre vueltas.
         *
         * La K-line da unas 9 lecturas por segundo y una vuelta gasta
         * ocho peticiones, asi que el bus ya esta al limite: pedir mas seguido
         * no trae datos mas frescos, solo calienta la CPU esperando respuestas
         * que el bus no puede dar mas rapido.
         */
        const val ENTRE_VUELTAS_MS = 1_000L
    }
}
