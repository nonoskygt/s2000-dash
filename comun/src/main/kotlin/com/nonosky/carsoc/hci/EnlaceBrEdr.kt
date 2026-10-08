package com.nonosky.carsoc.hci

import android.content.Context
import android.util.Log
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Donde se guarda la link key para no volver a emparejar en cada arranque. */
interface AlmacenLlaves {
    fun leer(mac: String): ByteArray?
    fun guardar(mac: String, llave: ByteArray, tipo: Int)
    fun olvidar(mac: String)
}

/**
 * Link keys en las preferencias de la app.
 *
 * Guardar la llave no es comodidad: sin ella hay que reemparejar en cada
 * arranque del carro, y cada emparejamiento es la parte fragil de todo esto.
 * Con la llave guardada, encender el carro es `Link Key Request` ->
 * `Link Key Request Reply` y ya.
 */
class LlavesEnPreferencias(context: Context) : AlmacenLlaves {
    private val prefs = context.getSharedPreferences("llaves_bt", Context.MODE_PRIVATE)

    override fun leer(mac: String): ByteArray? {
        val hex = prefs.getString(clave(mac), null) ?: return null
        if (hex.length != 32) return null
        return runCatching {
            ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }

    override fun guardar(mac: String, llave: ByteArray, tipo: Int) {
        if (llave.size != 16) return
        prefs.edit()
            .putString(clave(mac), llave.joinToString("") { String.format("%02X", it) })
            .putInt(clave(mac) + ".tipo", tipo)
            .apply()
    }

    override fun olvidar(mac: String) {
        prefs.edit().remove(clave(mac)).remove(clave(mac) + ".tipo").apply()
    }

    private fun clave(mac: String) = "llave." + mac.uppercase()
}

/**
 * Abre y empareja un enlace ACL de Bluetooth CLASICO por HCI crudo.
 *
 * **Esta clase es el punto entero del ejercicio.** Lo que la pila del radio
 * no hacia no era conectar: era contestar. `createBond()` entraba en
 * BONDING(11), moria en NONE(10) y `ACTION_PAIRING_REQUEST` no se disparaba
 * jamas — o sea que alguien pedia el PIN y nadie lo contestaba. Aqui el
 * dialogo esta a la vista y lo contestamos nosotros:
 *
 * ```
 *   nosotros                                  el ELM327
 *   --------                                  ---------
 *   Create Connection (0x0405)  ------------>
 *                               <------------  Command Status (aceptado)
 *                               <------------  Connection Complete (0x03) + handle
 *   Authentication Requested    ------------>
 *                               <------------  Link Key Request (0x17)
 *   Link Key Neg Reply (0x040C) ------------>       (la primera vez)
 *                               <------------  PIN Code Request (0x16)
 *   PIN Code Reply (0x040D)     ------------>       1234 / 6789 / 0000
 *                               <------------  Link Key Notification (0x18)
 *                               <------------  Authentication Complete (0x06)
 * ```
 *
 * Con emparejamiento seguro (2.1 en adelante) el tramo del medio cambia a
 * `IO Capability Request` -> Reply(NoInputNoOutput) y `User Confirmation
 * Request` -> Reply, y **no hay PIN**. Se atienden los dos caminos porque
 * cual toca lo decide el otro extremo, no nosotros.
 *
 * Los eventos de emparejamiento son espontaneos: llegan cuando al otro
 * extremo le da la gana, incluso a mitad de una conexion L2CAP ya abierta.
 * Por eso hay un hilo atendedor permanente en vez de una secuencia de
 * "manda y espera".
 */
class EnlaceBrEdr(
    private val hci: HciUsb,
    /**
     * Solo las capacidades, no la implementacion.
     *
     * Asi el enlace sirve tanto con la bomba de un solo dueño como con la
     * compartida, y —sobre todo— no puede arrancar una segunda bomba sobre el
     * mismo endpoint, que es el error que ya costo dos depuraciones.
     */
    private val bomba: EventosHci,
    private val cmd: ComandosHci,
    private val mac: String,
    private val almacen: AlmacenLlaves? = null,
    /**
     * PIN a probar, en orden. Los clones de ELM327 usan casi siempre 1234;
     * los de la familia HC-05 tambien aceptan 0000 y algunos 6789.
     *
     * Un PIN equivocado no se puede reintentar en caliente: el controlador
     * pide el PIN **una vez por autenticacion**, y si falla hay que rehacer
     * el ciclo entero. Por eso el indice avanza entre intentos, no dentro.
     */
    private val pines: List<String> = listOf("1234", "6789", "0000", "8888"),
    /**
     * Habilitar emparejamiento seguro.
     *
     * Se deja en `true` por evidencia MEDIDA, no por gusto: el vector de
     * caracteristicas de este dongle (BFFECFFEDBFF7B87) trae el bit 51
     * "Secure Simple Pairing" en 1, o sea que el firmware que ya esta en el
     * chip lo soporta sin necesitar el patchram que `btusb` nunca le subio.
     *
     * Y conviene: si el ELM327 es 2.1 o superior, SSP con NoInputNoOutput es
     * "Just Works" y **no hay PIN que adivinar**. Si el ELM327 es 2.0, el
     * controlador cae solo al emparejamiento heredado con PIN. Habilitarlo
     * cubre los dos casos; deshabilitarlo cierra uno.
     *
     * Ponerlo en `false` es el plan B si el SSP resultara estar a medias en
     * este firmware sin parchear.
     */
    private val usarSsp: Boolean = true,
) {

    /** Todo lo que paso, en orden, para poder diagnosticarlo por HTTP. */
    val traza = CopyOnWriteArrayList<String>()

    @Volatile
    var handle: Int = -1
        private set

    @Volatile
    var emparejado: Boolean = false
        private set

    @Volatile
    var cifrado: Boolean = false
        private set

    /** La ultima link key que nos notificaron. Prueba de que se emparejo. */
    @Volatile
    var llave: ByteArray? = null
        private set

    @Volatile
    var caido: String? = null
        private set

    private var pinActual = 0

    private val hitos = LinkedBlockingQueue<Hito>(64)
    private val paraAtender = LinkedBlockingQueue<ByteArray>(64)

    @Volatile
    private var vivo = false
    private var atendedor: Thread? = null
    private var baja: (() -> Unit)? = null

    private enum class Hito { CONECTADO, FALLO_CONEXION, AUTENTICADO, FALLO_AUTENTICACION, CIFRADO, CAIDO }

    // ------------------------------------------------------------------ etapas

    /**
     * Etapa 0: ajustar el controlador para BR/EDR.
     *
     * **Aqui NO se resetea.** Lo hacia, y era un error grave desde que la
     * radio se comparte:
     *
     *   - `HCI_Reset` borra el estado de control de flujo del controlador,
     *     pero el [ControlFlujoAcl] de la bomba —que es COMPARTIDO— se queda
     *     con sus contadores de antes. A partir de ahi creemos tener creditos
     *     que el controlador ya no reconoce, y los paquetes salientes se caen
     *     **en silencio**. El sintoma enganaba entero: el enlace BR/EDR se
     *     abria, el L2CAP se abria, el RFCOMM se abria... y luego ni un `ATI`
     *     recibia respuesta, porque el `ATI` nunca llego a salir.
     *   - Y tira TODOS los enlaces del controlador, incluido el LE de la
     *     bateria. Cada reintento del motor dejaba la bateria muda hasta que
     *     el vigilante volviera a entrar, treinta segundos despues.
     *
     * El reset va donde le toca: en [RadioBt], una sola vez por apertura en
     * frio del dongle, antes de que exista ningun enlace ni ningun contador.
     */
    fun preparar(): Boolean {
        val r = preparacion(cmd, usarSsp, ::anotar)
        bufferAcl = r.bufferAcl
        creditosAcl = r.creditosAcl
        return r.ok
    }

    /**
     * El cuerpo de [preparar], sin depender de la instancia.
     *
     * Separado para poder probarlo en la JVM: [EnlaceBrEdr] recibe un
     * [HciUsb] concreto, que necesita el `UsbManager` de Android y no se
     * puede construir aqui. Lo que hay que dejar clavado —que esta secuencia
     * NO manda `HCI_Reset`— vive en esta funcion.
     */
    internal class Preparacion(val ok: Boolean, val bufferAcl: Int, val creditosAcl: Int)


    /** Tamano maximo de un paquete ACL de BR/EDR. Lo necesita la capa ACL. */
    @Volatile
    var bufferAcl: Int = 0
        private set

    /** Cuantos paquetes ACL se pueden tener en vuelo. Lo necesita la capa ACL. */
    @Volatile
    var creditosAcl: Int = 0
        private set

    /**
     * Etapa 1: abrir el enlace ACL.
     *
     * Devuelve el handle. Lanza [IOException] con el motivo TRADUCIDO si no.
     */
    fun conectar(timeoutMs: Long = 20_000): Int {
        arrancarAtendedor()
        hitos.clear()

        val params = HciBrEdr.crearConexion(mac)
        anotar("CREATE_CONNECTION $mac  params=${HciUsb.hex(params)}")
        anotar("  tipos=0xCC18 pageScanRep=R2 desfase=desconocido cambioDeRol=NO")

        val st = cmd.ejecutar(HciBrEdr.CMD_CREATE_CONNECTION, params, 4_000)
            ?: throw IOException("Create Connection: el controlador no contesto ni Command Status")
        val e0 = CanalComandos.estadoDe(st)
        anotar("  Command Status -> ${HciBrEdr.motivo(e0)}")
        if (e0 != 0) throw IOException("Create Connection rechazado: ${HciBrEdr.motivo(e0)}")

        // Command Status solo dice "lo intento". El resultado llega en
        // Connection Complete, que puede tardar todo el Page Timeout.
        val h = esperar(timeoutMs, Hito.CONECTADO, Hito.FALLO_CONEXION)
        if (h != Hito.CONECTADO) {
            throw IOException(caido ?: "no hubo Connection Complete en ${timeoutMs} ms")
        }
        return handle
    }

    /**
     * Etapa 2: emparejar. Aqui es donde fallaba Android.
     *
     * Se llama cuando hace falta, no siempre: muchos clones de ELM327
     * aceptan L2CAP sin autenticar, y en ese caso emparejar es trabajo y
     * riesgo de mas. El transporte lo invoca cuando el L2CAP le devuelve un
     * rechazo por seguridad, o de entrada si se le pide explicitamente.
     */
    fun autenticar(timeoutMs: Long = 30_000): Boolean {
        if (handle < 0) throw IOException("no hay enlace ACL que autenticar")
        hitos.clear()
        val p = byteArrayOf((handle and 0xFF).toByte(), ((handle shr 8) and 0xFF).toByte())
        val st = cmd.ejecutar(HciBrEdr.CMD_AUTHENTICATION_REQUESTED, p, 4_000)
        val e0 = CanalComandos.estadoDe(st ?: ByteArray(0))
        anotar("AUTHENTICATION_REQUESTED handle=$handle -> ${HciBrEdr.motivo(e0)}")
        if (st == null || e0 != 0) return false

        val h = esperar(timeoutMs, Hito.AUTENTICADO, Hito.FALLO_AUTENTICACION)
        return h == Hito.AUTENTICADO
    }

    /**
     * Etapa 2b: cifrar el enlace.
     *
     * Opcional pero barata. El vector de caracteristicas medido dice que
     * este controlador soporta cifrado (byte 0 = 0xBF, bit 2). Algunos
     * modulos no dan datos por RFCOMM hasta que el enlace esta cifrado; si
     * este no lo exige, no molesta.
     */
    fun cifrar(timeoutMs: Long = 10_000): Boolean {
        if (handle < 0) return false
        hitos.clear()
        val p = byteArrayOf((handle and 0xFF).toByte(), ((handle shr 8) and 0xFF).toByte(), 0x01)
        val st = cmd.ejecutar(HciBrEdr.CMD_SET_CONNECTION_ENCRYPTION, p, 4_000)
        anotar("SET_CONNECTION_ENCRYPTION -> ${resumen(st)}")
        if (st == null || CanalComandos.estadoDe(st) != 0) return false
        return esperar(timeoutMs, Hito.CIFRADO, Hito.CAIDO) == Hito.CIFRADO
    }

    fun desconectar() {
        val h = handle
        if (h >= 0) {
            anotar("DISCONNECT handle=$h")
            runCatching { cmd.ejecutar(HciBrEdr.CMD_DISCONNECT, HciBrEdr.desconectar(h), 2_000) }
        }
        handle = -1
        vivo = false
        runCatching { baja?.invoke() }
        baja = null
        runCatching { atendedor?.interrupt() }
        atendedor = null
    }

    /** Que se pruebe el siguiente PIN en el proximo ciclo de emparejamiento. */
    fun siguientePin(): String? {
        pinActual++
        return pines.getOrNull(pinActual)
    }

    // ------------------------------------------------------------- el atendedor

    private fun arrancarAtendedor() {
        if (vivo) return
        vivo = true
        caido = null
        baja = bomba.suscribir { e -> paraAtender.offer(e) }
        // Hilo propio y envuelto: una excepcion que escape mata el proceso
        // entero, y con el se van el TPMS, el tablero y el actualizador.
        atendedor = thread(name = "atendedor-bredr", isDaemon = true) {
            while (vivo) {
                val e = runCatching { paraAtender.poll(300, TimeUnit.MILLISECONDS) }.getOrNull()
                    ?: continue
                runCatching { atender(e) }
                    .onFailure { Log.w(TAG, "atender fallo: ${it.message}") }
            }
        }
    }

    /**
     * El corazon: un evento HCI entra, se contesta lo que haya que contestar.
     *
     * Se filtra por MAC en todo lo que la trae. No es paranoia: el dongle
     * puede tener mas de un enlace, y contestarle el PIN al aparato
     * equivocado empareja lo que no es.
     */
    private fun atender(e: ByteArray) {
        val codigo = e[0].toInt() and 0xFF
        val cuerpo = if (e.size > 2) e.copyOfRange(2, e.size) else ByteArray(0)

        when (codigo) {
            HciBrEdr.EVT_CONNECTION_COMPLETE -> {
                if (cuerpo.size < 11) return
                val estado = cuerpo[0].toInt() and 0xFF
                val h = ((cuerpo[2].toInt() and 0xFF) shl 8) or (cuerpo[1].toInt() and 0xFF)
                val quien = HciBrEdr.macTexto(cuerpo, 3)
                val tipo = cuerpo[9].toInt() and 0xFF
                val cif = cuerpo[10].toInt() and 0xFF
                anotar("Connection Complete $quien handle=$h tipo=${if (tipo == 1) "ACL" else tipo} " +
                    "cifrado=$cif -> ${HciBrEdr.motivo(estado)}")
                if (!esNuestro(quien)) return
                if (estado == 0) {
                    handle = h
                    cifrado = cif == 1
                    hitos.offer(Hito.CONECTADO)
                } else {
                    caido = "Connection Complete: ${HciBrEdr.motivo(estado)}"
                    hitos.offer(Hito.FALLO_CONEXION)
                }
            }

            HciBrEdr.EVT_DISCONNECTION_COMPLETE -> {
                if (cuerpo.size < 4) return
                val h = ((cuerpo[2].toInt() and 0xFF) shl 8) or (cuerpo[1].toInt() and 0xFF)
                if (h != handle) return
                val razon = cuerpo[3].toInt() and 0xFF
                anotar("Disconnection Complete handle=$h -> ${HciBrEdr.motivo(razon)}")
                caido = "el enlace cayo: ${HciBrEdr.motivo(razon)}"
                handle = -1
                cifrado = false
                hitos.offer(Hito.CAIDO)
            }

            // --------------------------------------------- emparejamiento heredado

            HciBrEdr.EVT_LINK_KEY_REQUEST -> {
                val quien = HciBrEdr.macTexto(cuerpo, 0)
                if (!esNuestro(quien)) return
                val guardada = almacen?.leer(quien)
                if (guardada != null) {
                    // Ya emparejados en otro arranque: se reusa y no hay PIN.
                    anotar("Link Key Request $quien -> tenemos llave, la mandamos")
                    cmd.ejecutar(HciBrEdr.CMD_LINK_KEY_REQUEST_REPLY, HciBrEdr.linkKeyReply(quien, guardada))
                } else {
                    // La primera vez NO hay llave. Contestar negativo es lo
                    // que dispara el PIN Code Request; callarse deja el
                    // emparejamiento colgado hasta que el enlace muere, que
                    // es exactamente el sintoma que daba Android.
                    anotar("Link Key Request $quien -> no tenemos llave, respuesta NEGATIVA (dispara el PIN)")
                    cmd.ejecutar(HciBrEdr.CMD_LINK_KEY_REQUEST_NEGATIVE_REPLY, HciBrEdr.soloMac(quien))
                }
            }

            HciBrEdr.EVT_PIN_CODE_REQUEST -> {
                val quien = HciBrEdr.macTexto(cuerpo, 0)
                if (!esNuestro(quien)) return
                val pin = pines.getOrNull(pinActual)
                if (pin == null) {
                    anotar("PIN Code Request $quien -> se acabaron los PIN que probar")
                    cmd.ejecutar(HciBrEdr.CMD_PIN_CODE_REQUEST_NEGATIVE_REPLY, HciBrEdr.soloMac(quien))
                    return
                }
                anotar("PIN Code Request $quien -> mandamos '$pin' (intento ${pinActual + 1}/${pines.size})")
                cmd.ejecutar(HciBrEdr.CMD_PIN_CODE_REQUEST_REPLY, HciBrEdr.pinReply(quien, pin))
            }

            HciBrEdr.EVT_LINK_KEY_NOTIFICATION -> {
                if (cuerpo.size < 23) return
                val quien = HciBrEdr.macTexto(cuerpo, 0)
                if (!esNuestro(quien)) return
                val k = cuerpo.copyOfRange(6, 22)
                val tipo = cuerpo[22].toInt() and 0xFF
                llave = k
                almacen?.guardar(quien, k, tipo)
                // La llave se anota, no se imprime: es el secreto del enlace.
                anotar("Link Key Notification $quien tipo=$tipo -> GUARDADA (${k.size} bytes)")
            }

            HciBrEdr.EVT_AUTHENTICATION_COMPLETE -> {
                if (cuerpo.size < 3) return
                val estado = cuerpo[0].toInt() and 0xFF
                val h = ((cuerpo[2].toInt() and 0xFF) shl 8) or (cuerpo[1].toInt() and 0xFF)
                if (h != handle) return
                anotar("Authentication Complete -> ${HciBrEdr.motivo(estado)}")
                if (estado == 0) {
                    emparejado = true
                    hitos.offer(Hito.AUTENTICADO)
                } else {
                    // Un PIN malo se ve exactamente asi. Se avanza el indice
                    // para que el siguiente ciclo pruebe otro.
                    if (estado == 0x05 || estado == 0x06) {
                        val sig = siguientePin()
                        anotar("  el PIN no le cuadro; el proximo intento usara ${sig ?: "(ninguno, se acabaron)"}")
                    }
                    caido = "autenticacion fallida: ${HciBrEdr.motivo(estado)}"
                    hitos.offer(Hito.FALLO_AUTENTICACION)
                }
            }

            HciBrEdr.EVT_ENCRYPTION_CHANGE -> {
                if (cuerpo.size < 4) return
                val estado = cuerpo[0].toInt() and 0xFF
                val h = ((cuerpo[2].toInt() and 0xFF) shl 8) or (cuerpo[1].toInt() and 0xFF)
                if (h != handle) return
                cifrado = estado == 0 && (cuerpo[3].toInt() and 0xFF) != 0
                anotar("Encryption Change -> ${HciBrEdr.motivo(estado)} cifrado=$cifrado")
                if (estado == 0) hitos.offer(Hito.CIFRADO)
            }

            // ------------------------------------------- emparejamiento seguro (2.1+)

            HciBrEdr.EVT_IO_CAPABILITY_REQUEST -> {
                val quien = HciBrEdr.macTexto(cuerpo, 0)
                if (!esNuestro(quien)) return
                anotar("IO Capability Request $quien -> NoInputNoOutput, sin MITM, vinculo general")
                cmd.ejecutar(HciBrEdr.CMD_IO_CAPABILITY_REQUEST_REPLY, HciBrEdr.ioCapabilityReply(quien))
            }

            HciBrEdr.EVT_IO_CAPABILITY_RESPONSE -> {
                if (cuerpo.size < 9) return
                val quien = HciBrEdr.macTexto(cuerpo, 0)
                if (!esNuestro(quien)) return
                val io = cuerpo[6].toInt() and 0xFF
                val req = cuerpo[8].toInt() and 0xFF
                anotar("IO Capability Response $quien io=$io requisitos=0x${String.format("%02X", req)}")
                // Si el otro extremo exige MITM (requisitos impares) y
                // nosotros no tenemos ni teclado ni pantalla, el
                // emparejamiento seguro NO puede completarse. Decirlo aqui
                // ahorra buscar el fallo en otro sitio.
                if (req % 2 == 1) {
                    anotar("  AVISO: exige proteccion contra intermediario y no tenemos con que. " +
                        "El SSP va a fallar; el plan B es usarSsp=false y emparejar con PIN")
                }
            }

            HciBrEdr.EVT_USER_CONFIRMATION_REQUEST -> {
                val quien = HciBrEdr.macTexto(cuerpo, 0)
                if (!esNuestro(quien)) return
                // "Just Works": el numero que trae no se le ensena a nadie
                // porque nadie lo va a comparar. Se confirma y punto.
                anotar("User Confirmation Request $quien -> confirmado automaticamente (Just Works)")
                cmd.ejecutar(HciBrEdr.CMD_USER_CONFIRMATION_REQUEST_REPLY, HciBrEdr.soloMac(quien))
            }

            HciBrEdr.EVT_USER_PASSKEY_REQUEST -> {
                val quien = HciBrEdr.macTexto(cuerpo, 0)
                if (!esNuestro(quien)) return
                // Nos pide teclear un numero de 6 digitos que el aparato
                // muestra. Un ELM327 no tiene pantalla, asi que esto no
                // deberia pasar; si pasa, se prueba con el PIN como numero.
                val n = pines.getOrNull(pinActual)?.toIntOrNull()
                if (n == null) {
                    anotar("User Passkey Request $quien -> no hay numero que ofrecer, NEGATIVO")
                    cmd.ejecutar(HciBrEdr.CMD_USER_PASSKEY_REQUEST_NEGATIVE_REPLY, HciBrEdr.soloMac(quien))
                } else {
                    anotar("User Passkey Request $quien -> probamos con $n")
                    cmd.ejecutar(HciBrEdr.CMD_USER_PASSKEY_REQUEST_REPLY, HciBrEdr.passkeyReply(quien, n))
                }
            }

            HciBrEdr.EVT_SIMPLE_PAIRING_COMPLETE -> {
                if (cuerpo.isEmpty()) return
                val estado = cuerpo[0].toInt() and 0xFF
                anotar("Simple Pairing Complete -> ${HciBrEdr.motivo(estado)}")
            }

            HciBrEdr.EVT_ROLE_CHANGE -> {
                if (cuerpo.size < 8) return
                anotar("Role Change ${HciBrEdr.macTexto(cuerpo, 1)} -> " +
                    if ((cuerpo[7].toInt() and 0xFF) == 0) "somos maestro" else "somos esclavo")
            }

            HciBrEdr.EVT_CONNECTION_REQUEST -> {
                // Entrante. No la esperamos, pero dejarla sin contestar hace
                // que el otro extremo insista y ensucie el enlace.
                val quien = HciBrEdr.macTexto(cuerpo, 0)
                anotar("Connection Request entrante de $quien (no la esperabamos)")
            }
        }
    }

    private fun esNuestro(quien: String): Boolean =
        quien.equals(mac, ignoreCase = true)

    private fun esperar(timeoutMs: Long, bueno: Hito, malo: Hito): Hito? {
        val hasta = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < hasta) {
            val h = runCatching { hitos.poll(250, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
            if (h == bueno || h == malo || h == Hito.CAIDO) return h
        }
        return null
    }

    private fun anotar(t: String) {
        Log.i(TAG, t)
        if (traza.size > 200) traza.removeAt(0)
        traza.add(t)
    }

    internal companion object {
        const val TAG = "EnlaceBrEdr"

        internal fun resumen(e: ByteArray?): String =
            if (e == null) "SIN RESPUESTA" else HciBrEdr.motivo(CanalComandos.estadoDe(e))

        internal fun preparacion(
            cmd: ComandosHci,
            usarSsp: Boolean,
            anotar: (String) -> Unit,
        ): Preparacion {
            var ok = true
            var bufferAcl = 0
            var creditosAcl = 0

            // La mascara de eventos ANTES de nada: sin ella los eventos del
            // emparejamiento seguro (bits 48..53) no existen para nosotros.
            val m = cmd.ejecutar(HciBrEdr.CMD_SET_EVENT_MASK, HciBrEdr.MASCARA_EVENTOS)
            anotar("SET_EVENT_MASK -> ${resumen(m)}")
            if (m == null || CanalComandos.estadoDe(m) != 0) {
                // No es fatal: el barrido BLE ya funcionaba con la mascara por
                // defecto. Pero si esto falla, el emparejamiento seguro no va a
                // funcionar y conviene que quede escrito ANTES de perder una hora.
                anotar("  AVISO: sin mascara de eventos, el SSP no dara senales de vida")
            }

            val p = cmd.ejecutar(HciBrEdr.CMD_WRITE_PAGE_TIMEOUT, HciBrEdr.pageTimeout())
            anotar("WRITE_PAGE_TIMEOUT (10.24 s) -> ${resumen(p)}")

            val s = cmd.ejecutar(
                HciBrEdr.CMD_WRITE_SIMPLE_PAIRING_MODE,
                byteArrayOf(if (usarSsp) 0x01 else 0x00),
            )
            anotar("WRITE_SIMPLE_PAIRING_MODE(${if (usarSsp) 1 else 0}) -> ${resumen(s)}")

            val b = cmd.ejecutar(HciBrEdr.CMD_READ_BUFFER_SIZE)
            if (b != null && CanalComandos.estadoDe(b) == 0) {
                val q = CanalComandos.retornoDe(b)
                if (q.size >= 7) {
                    val maxAcl = ((q[1].toInt() and 0xFF) shl 8) or (q[0].toInt() and 0xFF)
                    // Read_Buffer_Size devuelve, tras el estado:
                    //   acl(2) | sco(1) | numAcl(2) | numSco(2)
                    // o sea numAcl en 3..4. Se leia en 5..6, que es numSco: este
                    // dongle declara 1 buffer SCO y la traza reportaba "1 buffers"
                    // teniendo 8. Nadie consume el valor todavia, pero una traza
                    // que miente cuesta horas cuando algo si depende de ella.
                    val numAcl = ((q[4].toInt() and 0xFF) shl 8) or (q[3].toInt() and 0xFF)
                    bufferAcl = maxAcl
                    creditosAcl = numAcl
                    anotar("READ_BUFFER_SIZE -> paquete ACL $maxAcl bytes, $numAcl buffers")
                }
            } else {
                anotar("READ_BUFFER_SIZE -> ${resumen(b)}")
            }
            return Preparacion(ok, bufferAcl, creditosAcl)
        }
    }
}
