package com.nonosky.carsoc.obd

import android.content.Context
import android.hardware.usb.UsbManager
import com.nonosky.carsoc.hci.BombaHci
import com.nonosky.carsoc.hci.GestorL2cap
import com.nonosky.carsoc.hci.HciUsb
import com.nonosky.carsoc.hci.L2cap
import com.nonosky.carsoc.hci.Rfcomm
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * El adaptador OBD por Bluetooth clasico, hablando HCI crudo al dongle USB.
 *
 * Existe porque la pila Bluetooth del radio no sirve. Y no es una opinion:
 * el mismo adaptador Steren conecta a la primera en cualquier telefono y ahi
 * lee el motor entero, mientras que aqui `createBond()` moria en BONDING sin
 * que `ACTION_PAIRING_REQUEST` se disparara **nunca**, las cuatro vias de
 * RFCOMM daban "read ret: -1", y la pila llego a apagarse sola.
 *
 * Por esta via el emparejamiento lo controlamos nosotros: cuando el
 * controlador pide el PIN, [com.nonosky.carsoc.hci.EnlaceBrEdr] lo
 * contesta. Ese es exactamente el escalon donde Android se quedaba mudo.
 *
 * Implementa [ObdTransport] entero, asi que [Elm327Session] no se entera de
 * por donde va: el dialogo AT es el mismo que por SPP.
 */
class HciObdTransport(
    private val context: Context,
    private val mac: String,
    /** Canal RFCOMM del ELM327. Casi todos usan el 1. */
    private val canalRfcomm: Int = 1,
) : ObdTransport, BombaHci.Oyente {

    /** Traza paso a paso, que es lo unico que permite depurar esto en remoto. */
    val traza = mutableListOf<String>()

    /** Tramas crudas del canal, para cuando el encuadre sea el sospechoso. */
    private val crudos = java.util.Collections.synchronizedList(mutableListOf<String>())

    private var hci: HciUsb? = null
    private var bomba: BombaHci? = null
    private var gestor: GestorL2cap? = null
    private var canal: GestorL2cap.Canal? = null
    private var handle = -1

    private val dlci = Rfcomm.dlciDe(canalRfcomm)

    /**
     * Bytes ya desempaquetados de las UIH, listos para el dialogo AT.
     *
     * Cola y suscripcion permanente, no espera puntual: el ELM327 contesta en
     * varios trozos y entre dos lecturas nadie estaria escuchando. Es
     * exactamente el fallo que dejo la respuesta del BMS a medias durante dos
     * despliegues — misma trampa, mismo remedio.
     */
    private val entrada = LinkedBlockingQueue<Byte>()

    /** Tramas de control (UA, DM) esperando a que alguien las mire. */
    private val control = LinkedBlockingQueue<Rfcomm.Recibida>()

    /**
     * Creditos que el otro extremo nos concede para mandarle.
     *
     * Si se ignoran, el modulo deja de aceptar datos y el enlace se queda
     * mudo con todo aparentemente bien.
     */
    @Volatile
    private var creditosSalida = 0

    @Volatile
    private var conectado = false

    /** Si tenemos una referencia de RadioBt que soltar. */
    @Volatile
    private var tomada = false

    override val isConnected: Boolean get() = conectado

    // --- Recepcion -----------------------------------------------------------

    override fun alPdu(handle: Int, pdu: ByteArray) {
        // Envuelto ENTERO: esto corre en el hilo de la bomba y una excepcion
        // que escape mata el proceso. Ya tumbo el tablero una vez por dejar
        // una sola linea fuera.
        runCatching {
            if (handle != this.handle) return@runCatching
            val local = canal?.cidLocal ?: return@runCatching
            if (L2cap.cidDe(pdu) != local) return@runCatching
            val carga = L2cap.cargaDe(pdu)
            // Volcar el crudo ANTES de interpretarlo. Si el FCS o el
            // encuadre estan mal, `interpretar` devuelve null y la trama
            // desaparece sin dejar rastro — y entonces "no contesto al SABM"
            // no distingue entre que no contesto y que contesto algo que yo
            // no supe leer. Son dos problemas distintos.
            if (crudos.size < 40) crudos += "<- " + carga.joinToString("") { "%02X".format(it) }
            val t = Rfcomm.interpretar(carga) ?: run {
                if (crudos.size < 40) crudos += "   (no paso el encuadre/FCS de RFCOMM)"
                return@runCatching
            }

            if (t.creditos > 0) creditosSalida += t.creditos

            when {
                t.dlci == Rfcomm.DLCI_CONTROL && t.esUih -> {
                    // Un MSC entrante hay que contestarlo o el modulo espera.
                    if (Rfcomm.esMsc(t.datos)) {
                        enviarRfcomm(Rfcomm.mensajeMsc(dlci, comando = false))
                    }
                }
                t.esUih && t.dlci == dlci -> for (b in t.datos) entrada.offer(b)
                else -> control.offer(t)
            }
        }
    }

    override fun alCaerEnlace(handle: Int, razon: Int) {
        runCatching { if (handle == this.handle) conectado = false }
    }

    // --- Apertura ------------------------------------------------------------

    /**
     * Abre el enlace sobre la radio COMPARTIDA, sin acaparar el dongle.
     *
     * La version anterior tomaba un candado exclusivo mientras durara la
     * sesion, lo que obligaba a que la bateria y el motor se turnaran: cada
     * uno en linea a ratos. Era una limitacion de mi diseño, no del aparato —
     * un controlador de doble modo mantiene los dos enlaces a la vez y separa
     * los paquetes por su handle.
     */
    override fun connect() {
        val piezas = com.nonosky.carsoc.hci.RadioBt.tomar(context, "obd-rfcomm")
            ?: throw IOException(
                "no se pudo abrir la radio: " +
                    (com.nonosky.carsoc.hci.RadioBt.ultimoFallo ?: "motivo desconocido")
            )
        tomada = true
        try {
            conectarSobreRadio(piezas)
        } catch (e: Exception) {
            runCatching { com.nonosky.carsoc.hci.RadioBt.soltar("obd-rfcomm") }
            tomada = false
            throw e
        }
    }

    private fun conectarSobreRadio(piezas: com.nonosky.carsoc.hci.RadioBt.Piezas) {
        val h = piezas.hci
        hci = h

        // --- 1. Emparejar. UNA SOLA bomba leyendo el endpoint de eventos.
        //
        // Esto va antes de arrancar BombaHci y no despues, y no es un detalle
        // de estilo: las dos bombas leen el MISMO endpoint de interrupcion, y
        // dos hilos haciendo bulkTransfer sobre el mismo endpoint se reparten
        // los paquetes al azar. La primera version arrancaba las dos y el
        // resultado eran "RESET -> SIN RESPUESTA" y "READ_BUFFER_SIZE -> SIN
        // RESPUESTA": los eventos llegaban, pero a la bomba que no preguntaba.
        //
        // El emparejamiento solo necesita eventos, no datos ACL, asi que se
        // hace con BombaEventos, se la para, y solo entonces arranca BombaHci.
        // El enlace sobrevive al cambio porque vive en el controlador, no en
        // el hilo que lo pidio.
        traza += "conectando por BR/EDR con $mac"
        // La bomba COMPARTIDA, no una nueva. Arrancar otra sobre el mismo
        // endpoint de eventos hace que las dos se roben los paquetes: los
        // comandos se quedan sin respuesta y el error dice "no se pudo preparar
        // el controlador", que apunta al controlador cuando el problema es
        // tener dos lectores. Paso dos veces; las interfaces de Bombeo.kt
        // existen para que no pueda volver a pasar.
        val bombeo = com.nonosky.carsoc.hci.BombeoCompartido(piezas.bomba)
        run {
            val enlace = com.nonosky.carsoc.hci.EnlaceBrEdr(
                hci = h,
                bomba = bombeo,
                cmd = bombeo,
                mac = mac,
            )
            // La traza del enlace se recoge en un finally, SIEMPRE.
            //
            // Antes se recogia despues de conectar(), asi que cuando conectar()
            // lanzaba —o sea, justo cuando hacia falta— se perdia entera. El
            // resultado era un PAGE TIMEOUT desnudo, sin las lineas del INQUIRY
            // que dicen si al aparato se le encontro y con que parametros se le
            // pagino. Quedarse sin traza en el unico caso que importa es lo
            // mismo que no tener traza.
            try {
                if (!enlace.preparar()) {
                    throw IOException("no se pudo preparar el controlador para BR/EDR")
                }
                handle = enlace.conectar()
                if (handle < 0) throw IOException("no se establecio el enlace clasico con $mac")
                traza += "enlace clasico listo, handle=$handle"
            } finally {
                traza += enlace.traza
            }
        }

        // --- 2. Ahora si, la bomba de ACL, ya sin nadie mas leyendo ---
        val b = piezas.bomba
        bomba = b
        val g = piezas.gestor
        gestor = g

        // --- 3. Canal L2CAP al PSM de RFCOMM ---
        val apertura = g.abrirDinamico(handle, L2cap.PSM_RFCOMM)
        traza += apertura.traza
        val c = apertura.canal ?: throw IOException("no se abrio el canal L2CAP al PSM 3")
        canal = c
        b.suscribir(this)
        traza += "canal L2CAP abierto (cid local ${c.cidLocal})"

        // --- 4. Multiplexor RFCOMM ---
        if (!abrirDlci(Rfcomm.DLCI_CONTROL)) {
            traza += "--- crudo recibido por el canal ---"
            traza += crudos.toList()
            traza += "--- lo que mande ---"
            traza += "-> SABM dlci=0: " +
                Rfcomm.trama(Rfcomm.DLCI_CONTROL, Rfcomm.SABM or Rfcomm.PF)
                    .joinToString("") { "%02X".format(it) }
            throw IOException("el multiplexor RFCOMM no contesto al SABM de control")
        }
        traza += "multiplexor RFCOMM abierto"

        if (!abrirDlci(dlci)) {
            traza += "--- crudo recibido por el canal ---"
            traza += crudos.toList()
            throw IOException("el canal RFCOMM $canalRfcomm fue rechazado (¿es otro canal?)")
        }
        traza += "canal RFCOMM $canalRfcomm abierto"

        // Sin MSC muchos modulos no mandan un byte, y el canal parece mudo.
        enviarRfcomm(Rfcomm.mensajeMsc(dlci))
        // Creditos iniciales para que el otro extremo pueda hablarnos.
        enviarRfcomm(Rfcomm.trama(dlci, Rfcomm.UIH, ByteArray(0), creditos = CREDITOS_INICIALES))

        conectado = true
    }

    /** SABM y espera de UA. Un DM es un "no" explicito. */
    private fun abrirDlci(objetivo: Int): Boolean {
        control.clear()
        if (!enviarRfcomm(Rfcomm.trama(objetivo, Rfcomm.SABM or Rfcomm.PF))) return false
        val hasta = System.currentTimeMillis() + APERTURA_MS
        while (System.currentTimeMillis() < hasta) {
            val t = control.poll(500, TimeUnit.MILLISECONDS) ?: continue
            if (t.dlci != objetivo) continue
            if (t.esUa) return true
            if (t.esDm) {
                traza += "el otro extremo contesto DM al DLCI $objetivo"
                return false
            }
        }
        return false
    }

    private fun enviarRfcomm(trama: ByteArray): Boolean {
        val b = bomba ?: return false
        val c = canal ?: return false
        return runCatching { b.enviarAcl(handle, c.cidRemoto, trama) }.getOrDefault(false)
    }

    // --- Dialogo AT ----------------------------------------------------------

    override fun write(bytes: ByteArray) {
        if (!conectado) throw IOException("el enlace no esta abierto")
        // El ELM327 espera cada comando terminado en retorno de carro.
        val conCr = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
            bytes
        } else {
            bytes + '\r'.code.toByte()
        }
        if (!enviarRfcomm(Rfcomm.trama(dlci, Rfcomm.UIH, conCr))) {
            throw IOException("no se pudo escribir por RFCOMM")
        }
    }

    override fun readUntilPrompt(timeoutMs: Long): String {
        val sb = StringBuilder()
        val hasta = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < hasta) {
            val b = entrada.poll(120, TimeUnit.MILLISECONDS) ?: continue
            val ch = (b.toInt() and 0xFF).toChar()
            if (ch == '>') break
            sb.append(ch)
            // Reponer creditos a medida que se consume, o el modulo se calla.
            if (sb.length % REPONER_CADA == 0) {
                enviarRfcomm(
                    Rfcomm.trama(dlci, Rfcomm.UIH, ByteArray(0), creditos = CREDITOS_INICIALES)
                )
            }
        }
        return sb.toString()
    }

    override fun drain() {
        entrada.clear()
    }

    override fun close() {
        conectado = false
        runCatching { bomba?.quitar(this) }
        runCatching { enviarRfcomm(Rfcomm.trama(dlci, Rfcomm.DISC or Rfcomm.PF)) }
        runCatching { canal?.let { gestor?.cerrar(it) } }
        // La radio NO se cierra aqui: la bateria puede tener su enlace LE vivo
        // en ella. Se suelta la referencia y RadioBt cierra cuando nadie quede.
        if (tomada) {
            runCatching { com.nonosky.carsoc.hci.RadioBt.soltar("obd-rfcomm") }
            tomada = false
        }
        hci = null
        bomba = null
        gestor = null
        canal = null
        handle = -1
    }

    private companion object {
        const val APERTURA_MS = 8_000L

        /**
         * Creditos que concedemos de golpe.
         *
         * Generoso a proposito: una respuesta de PID son pocas decenas de
         * bytes, y quedarse corto obliga a reponer a mitad de trama, que es
         * donde aparecen los desfases mas dificiles de ver.
         */
        const val CREDITOS_INICIALES = 32

        /** Cada cuantos bytes consumidos se reponen creditos. */
        const val REPONER_CADA = 16
    }
}
