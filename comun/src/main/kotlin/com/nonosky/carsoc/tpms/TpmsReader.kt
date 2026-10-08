package com.nonosky.carsoc.tpms

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Process
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lee el receptor TPMS solo, sin que nadie mande un comando.
 *
 * Encuentra el CH340, lo abre a [TpmsEnlaceConstantes.BAUDIOS], lee en bucle
 * en su propio hilo y le pasa los bytes a [TpmsDecoder]. Se reconecta solo.
 *
 * ## Por que un hilo propio y no una corrutina
 *
 * `bulkTransfer` es una llamada nativa BLOQUEANTE y la cancelacion de Kotlin
 * es cooperativa: una corrutina metida dentro de un `bulkTransfer` no se
 * cancela, se queda ahi hasta que venza el timeout. Este proyecto ya pago esa
 * leccion con `BluetoothSocket` — esta escrita en el comentario de
 * `PollScheduler.currentTransport`, que guarda el transporte solo para poder
 * cerrarlo, porque cancelar la corrutina no desatora la lectura.
 *
 * `Dispatchers.IO` tampoco vale, por dos razones:
 *
 *  1. Un hilo que pasa la vida bloqueado no "toma prestado" un obrero del
 *     pool: lo **ocupa indefinidamente**.
 *  2. A un obrero del pool no se le puede fijar prioridad, y aqui la
 *     prioridad es la mitad del argumento del presupuesto de CPU (ver
 *     [bucle]).
 *
 * Y desde luego no `lifecycleScope`, que es el defecto CRITICO ya corregido en
 * este proyecto: despacha en el hilo PRINCIPAL, con ANR garantizado. Aqui no
 * se usa ningun scope de Android — este lector vive en `DashService`, no en la
 * pantalla, y sigue leyendo con el tablero cerrado.
 *
 * ## Dueño unico del aparato
 *
 * **El USB esta confinado a este hilo. Nadie mas lo abre.** El diagnostico
 * (`/serial`) ya no abre el aparato por su cuenta: le PIDE a este hilo que
 * capture, y este hilo lo hace entre dos lecturas suyas (ver [probar]).
 *
 * Es mas fuerte que un candado. Con un candado siguen existiendo dos
 * secuencias de `openDevice` + `claimInterface(force = true)` capaces de
 * robarse la interfaz, y entonces las tramas de 8 bytes se reparten entre dos
 * lectores: las dos mitades salen cortadas, el XOR falla en ambas, y el
 * diagnostico rompe justo lo que intenta diagnosticar — y encima miente sobre
 * ello. Confinar el aparato a un hilo hace que ese estado no exista.
 *
 * ## Aislamiento de fallos
 *
 * El hilo entero corre envuelto y ninguna excepcion escapa. Una excepcion que
 * se escapa de un hilo en Android **mata el proceso**, y eso tumbaria de un
 * golpe el tablero, el puente y el actualizador. Ya paso una vez con el
 * `DebugServer` (defecto 4 de ESTADO.md): bastaba un escaneo de puertos.
 */
class TpmsReader(
    private val context: Context,
    private val reloj: () -> Long = System::currentTimeMillis,
) {

    private val decodificador = TpmsDecoder()

    // --- Estado del enlace (el de las ruedas lo lleva el decodificador) -----

    @Volatile
    var enlace: EnlaceTpms = EnlaceTpms.SinReceptor
        private set

    @Volatile
    var enlaceDetalle: String? = null
        private set

    @Volatile
    var enlaceAtMs: Long = 0
        private set

    @Volatile
    var reaperturas: Int = 0
        private set

    @Volatile
    var ultimoByteMs: Long = 0
        private set

    fun estado(): EstadoTpms = decodificador.instantanea()

    fun diagnostico(): DiagnosticoTpms = decodificador.diagnostico()

    // --- Hilo ---------------------------------------------------------------

    @Volatile
    private var vivo = false

    private var hilo: Thread? = null

    /** Monitor para dormir de forma interrumpible: parar tiene que ser rapido. */
    private val despertador = Object()

    @Volatile
    private var peticion: Peticion? = null

    @Volatile
    private var forzarReapertura = false

    @Volatile
    private var aparato: UsbDevice? = null

    private val anilloCrudo = ArrayDeque<String>()
    private val trazaApertura = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** Se avisa a la pantalla de que hay dato nuevo. Puede no haber pantalla. */
    @Volatile
    var alCambiar: (() -> Unit)? = null

    // --- Arranque y parada --------------------------------------------------

    fun arrancar() {
        if (vivo) return
        vivo = true
        hilo = Thread({
            // La ultima red. Si algo se escapa de [bucle] se anota y el hilo
            // muere SOLO: el tablero, el puente y el actualizador siguen.
            runCatching { bucle() }.onFailure { t ->
                Log.e(TAG, "el lector TPMS murio", t)
                marcar(
                    EnlaceTpms.Fallo,
                    "el hilo lector murio: ${t.javaClass.simpleName}: ${t.message}",
                )
            }
        }, "tpms-lector").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "lector TPMS arriba")
    }

    fun detener() {
        vivo = false
        despertar()
        hilo = null
    }

    val corriendo: Boolean get() = vivo

    private fun despertar() {
        synchronized(despertador) { despertador.notifyAll() }
    }

    /**
     * Aviso de que el USB cambio (se enchufo o se desenchufo algo).
     *
     * Sin esto habria que esperar al chequeo de presencia o al silencio de 90
     * segundos. Con esto, enchufar el receptor lo pone a leer en el acto.
     */
    fun avisarCambioUsb() {
        forzarReapertura = true
        despertar()
    }

    // --- Bucle principal ----------------------------------------------------

    private fun bucle() {
        // Prioridad de fondo, y es una decision, no un adorno. Mientras espera
        // en `bulkTransfer` este hilo no consume CPU: esta dormido en el
        // kernel. Cuando despierta tiene como mucho 32 bytes que procesar, o
        // sea microsegundos. Dejarlo por debajo del hilo de render garantiza
        // que ni en el peor caso pueda quitarle un cuadro a la aguja en la CPU
        // flojita del rk3326.
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

        var intento = 0
        while (vivo) {
            // Cada vuelta va envuelta ADEMAS de la envoltura del hilo: un
            // fallo al abrir no debe cortar el ciclo de reconexion.
            val ch = try {
                abrirReceptor()
            } catch (t: Throwable) {
                marcar(EnlaceTpms.Fallo, "abriendo: ${t.javaClass.simpleName}: ${t.message}")
                null
            }

            if (ch == null) {
                dormir(respaldoMs(intento++))
                continue
            }

            var huboBytes = false
            try {
                huboBytes = leer(ch)
            } catch (t: Throwable) {
                marcar(EnlaceTpms.Fallo, "leyendo: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                runCatching { ch.cerrar() }
            }

            if (!vivo) break

            // El retroceso solo se reinicia si el aparato DIO datos. Si abre y
            // se muere en el acto, reiniciarlo dejaria un bucle de aperturas a
            // un segundo para siempre — que es peor que no reconectar, porque
            // consume bateria del carro sin arreglar nada.
            if (huboBytes) intento = 0
            dormir(respaldoMs(intento++))
        }
        Log.i(TAG, "lector TPMS abajo")
    }

    /** 1 s, 2 s, 4 s, 8 s... con techo en [TpmsEnlaceConstantes.RESPALDO_MAX_MS]. */
    internal fun respaldoMs(intento: Int): Long =
        minOf(
            TpmsEnlaceConstantes.RESPALDO_BASE_MS shl intento.coerceIn(0, 10),
            TpmsEnlaceConstantes.RESPALDO_MAX_MS,
        )

    /** Duerme sin dejar de reaccionar a [detener] ni a [avisarCambioUsb]. */
    private fun dormir(ms: Long) {
        if (!vivo || ms <= 0) return
        synchronized(despertador) {
            runCatching { despertador.wait(ms) }
        }
    }

    // --- Apertura -----------------------------------------------------------

    private fun abrirReceptor(): Ch340? {
        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (um == null) {
            marcar(EnlaceTpms.Fallo, "este radio no expone UsbManager")
            return null
        }

        val dev = runCatching { um.deviceList.values }.getOrNull()
            ?.firstOrNull { esReceptor(it) }
        if (dev == null) {
            marcar(EnlaceTpms.SinReceptor, "no hay ningun USB-serial colgado del puerto")
            return null
        }
        aparato = dev

        if (!runCatching { um.hasPermission(dev) }.getOrDefault(false)) {
            // No se pide el permiso desde aqui a proposito: `requestPermission`
            // saca un dialogo, y este servicio corre con el tablero cerrado y
            // el carro solo. Decirlo por el puente es mas util que abrir una
            // ventana que nadie va a ver ni contestar.
            marcar(
                EnlaceTpms.SinPermiso,
                "sin permiso USB para VID=0x${"%04X".format(dev.vendorId)} " +
                    "PID=0x${"%04X".format(dev.productId)}",
            )
            return null
        }

        marcar(EnlaceTpms.Abriendo, "abriendo a ${TpmsEnlaceConstantes.BAUDIOS} baudios")
        val ch = Ch340(um, dev)
        val traza = ch.abrir(TpmsEnlaceConstantes.BAUDIOS)
        trazaApertura.clear()
        trazaApertura.addAll(traza)

        val error = traza.firstOrNull { it.startsWith("ERROR") }
        if (error != null) {
            runCatching { ch.cerrar() }
            marcar(EnlaceTpms.Fallo, error)
            return null
        }

        forzarReapertura = false
        // Tirar los bytes a medias de la sesion anterior: pegarlos a los
        // nuevos formaria una trama Frankenstein que igual pasa el XOR.
        decodificador.reiniciar()
        reaperturas++
        ultimoByteMs = reloj()
        marcar(EnlaceTpms.Leyendo, "abierto a ${TpmsEnlaceConstantes.BAUDIOS} baudios")
        return ch
    }

    private fun esReceptor(d: UsbDevice): Boolean = d.vendorId in VID_SERIAL

    // --- Lectura ------------------------------------------------------------

    /**
     * Lee hasta que el enlace muera o pidan parar.
     *
     * @return true si llego a leer algun byte. Lo usa el retroceso.
     */
    private fun leer(ch: Ch340): Boolean {
        val buffer = ByteArray(ch.tamPaquete)
        var ultimaPresencia = reloj()
        var huboBytes = false
        ultimoByteMs = reloj()

        while (vivo) {
            val n = ch.leerBloque(buffer, TpmsEnlaceConstantes.LECTURA_TIMEOUT_MS)
            val ahora = reloj()

            if (n > 0) {
                huboBytes = true
                ultimoByteMs = ahora
                anotarCrudo(buffer, n, ahora)
                val tramas = decodificador.alimentar(buffer, n, ahora)
                if (tramas.isNotEmpty()) {
                    marcar(EnlaceTpms.Leyendo, "${tramas.size} trama(s)")
                    avisar()
                }
            }

            // Una peticion de diagnostico se atiende AQUI, en el hilo dueño del
            // aparato. Es la razon de que no haga falta ningun candado.
            peticion?.let { p ->
                peticion = null
                runCatching { atender(ch, p) }.onFailure {
                    p.resultado = listOf("ERROR: ${it.javaClass.simpleName}: ${it.message}")
                }
                p.listo.countDown()
                ultimoByteMs = reloj()   // la captura no cuenta como silencio
            }

            if (forzarReapertura) {
                marcar(EnlaceTpms.Abriendo, "aviso de cambio en el USB")
                return huboBytes
            }

            // Que el aparato desaparezca del USB es la señal AUTORITATIVA de
            // enlace caido. Se consulta cada dos segundos y no en cada vuelta:
            // `deviceList` es una llamada Binder al servicio de USB, y hacerla
            // cinco veces por segundo durante horas es gasto puro.
            if (ahora - ultimaPresencia > TpmsEnlaceConstantes.REVISAR_PRESENCIA_MS) {
                ultimaPresencia = ahora
                if (!sigueConectado()) {
                    marcar(EnlaceTpms.SinReceptor, "el receptor se desconecto del USB")
                    return huboBytes
                }
            }

            // Y el silencio total como respaldo, por si el dongle se queda
            // colgado sin desaparecer del bus. Ver el comentario largo de
            // SIN_BYTES_MS: un `n < 0` NO entra en esta cuenta.
            if (ahora - ultimoByteMs > TpmsEnlaceConstantes.SIN_BYTES_MS) {
                marcar(
                    EnlaceTpms.Fallo,
                    "ni un byte en ${TpmsEnlaceConstantes.SIN_BYTES_MS / 1000}s; reabriendo",
                )
                return huboBytes
            }
        }
        return huboBytes
    }

    private fun sigueConectado(): Boolean {
        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val nombre = aparato?.deviceName ?: return false
        return runCatching { um.deviceList.containsKey(nombre) }.getOrDefault(false)
    }

    // --- Publicacion --------------------------------------------------------

    private fun marcar(e: EnlaceTpms, detalle: String) {
        enlace = e
        enlaceDetalle = detalle
        enlaceAtMs = reloj()
    }

    /**
     * Avisa a la pantalla. Envuelto a proposito: un fallo dibujando no puede
     * tumbar la fuente de datos.
     */
    private fun avisar() {
        runCatching { alCambiar?.invoke() }
            .onFailure { Log.w(TAG, "el gancho de la vista fallo: ${it.message}") }
    }

    private fun anotarCrudo(buffer: ByteArray, n: Int, ahora: Long) {
        val hex = StringBuilder(n * 2)
        for (i in 0 until n) hex.append("%02X".format(buffer[i]))
        synchronized(anilloCrudo) {
            if (anilloCrudo.size >= TpmsEnlaceConstantes.ANILLO_CRUDO) anilloCrudo.removeFirst()
            anilloCrudo.addLast("t=${ahora % 1_000_000} ${n}B $hex")
        }
    }

    fun trazaDeApertura(): List<String> = trazaApertura.toList()

    fun crudoReciente(): List<String> = synchronized(anilloCrudo) { anilloCrudo.toList() }

    // --- Diagnostico bajo peticion (desde el hilo del puente HTTP) ----------

    /**
     * Captura crudo durante [segundos], opcionalmente a otra velocidad.
     *
     * Sustituye a que `/serial` abra el aparato por su cuenta. Corre EN el
     * hilo lector, entre dos de sus lecturas, asi que no existe ningun
     * instante con dos dueños del USB.
     */
    fun probar(baudiosPrueba: Int, segundos: Int): List<String> {
        if (!vivo) return listOf("ERROR: el lector TPMS no esta corriendo")
        if (enlace != EnlaceTpms.Leyendo) {
            return listOf(
                "ERROR: el lector no tiene el aparato abierto (enlace=$enlace, " +
                    "detalle=$enlaceDetalle). No se abre por otra via a proposito: " +
                    "dos dueños del mismo USB-serial se pisan y las tramas salen cortadas."
            )
        }
        val p = Peticion(baudiosPrueba, segundos.coerceIn(1, 30))
        peticion = p
        despertar()
        // Tope acotado: si el hilo lector estuviera atascado, esta llamada NO
        // puede quedarse colgada — la hace un hilo de peticion HTTP que tiene
        // su propio timeout de socket.
        val ok = p.listo.await((p.segundos + 15).toLong(), TimeUnit.SECONDS)
        return if (ok) p.resultado else listOf(
            "ERROR: el lector no contesto en ${p.segundos + 15}s " +
                "(enlace=$enlace, detalle=$enlaceDetalle)"
        )
    }

    private fun atender(ch: Ch340, p: Peticion) {
        val salida = mutableListOf<String>()
        val original = TpmsEnlaceConstantes.BAUDIOS
        val cambia = p.baudios != original

        salida += "captura de ${p.segundos}s a ${p.baudios} baudios " +
            if (cambia) "(temporal; se vuelve a $original)" else "(la velocidad en uso)"
        salida += "la hace el HILO LECTOR, que es el unico dueño del aparato"

        if (cambia) salida += ch.reconfigurar(p.baudios)

        val prueba = TpmsDecoder()
        val buffer = ByteArray(ch.tamPaquete)
        val acumulado = java.io.ByteArrayOutputStream()
        val hasta = reloj() + p.segundos * 1000L
        val tramas = mutableListOf<TramaTpms>()

        while (reloj() < hasta && acumulado.size() < MAX_CAPTURA) {
            val n = ch.leerBloque(buffer, TpmsEnlaceConstantes.LECTURA_TIMEOUT_MS)
            if (n > 0) {
                acumulado.write(buffer, 0, n)
                tramas += prueba.alimentar(buffer, n, reloj())
            }
        }

        if (cambia) {
            salida += "--- restaurando $original baudios ---"
            salida += ch.reconfigurar(original)
            decodificador.reiniciar()
        }

        val datos = acumulado.toByteArray()
        if (datos.isEmpty()) {
            salida += "Nada llego en ${p.segundos}s a ${p.baudios} baudios."
            salida += if (p.baudios == original) {
                "A 19200 eso NO es cosa de la velocidad —esta verificada en vivo—: " +
                    "el receptor esta mudo, o los sensores no han transmitido todavia. " +
                    "Con el carro parado pueden tardar hasta un minuto."
            } else {
                "Prueba otras: " + Ch340.VELOCIDADES_TIPICAS
                    .filter { it != p.baudios }.joinToString(", ")
            }
        } else {
            salida += "--- ${datos.size} bytes ---"
            salida += "HEX:   " + datos.joinToString("") { "%02X".format(it) }
            salida += "ASCII: " + datos.map { b ->
                val c = b.toInt() and 0xFF
                if (c in 32..126) c.toChar() else '.'
            }.joinToString("")
            val d = prueba.diagnostico()
            salida += "--- ${tramas.size} tramas validas, " +
                "${d.tramasXorMalo} con XOR malo, ${d.tramasLargoRaro} con largo raro ---"
            tramas.forEach { salida += "  " + it.hex() }
        }
        p.resultado = salida
    }

    private class Peticion(val baudios: Int, val segundos: Int) {
        val listo = CountDownLatch(1)

        @Volatile
        var resultado: List<String> = emptyList()
    }

    companion object {
        private const val TAG = "TpmsReader"
        private const val MAX_CAPTURA = 32 * 1024

        /** VID de los USB-serial baratos. El receptor es un CH340 (0x1A86). */
        private val VID_SERIAL = setOf(Ch340.VID_QINHENG, 0x10C4, 0x0403, 0x067B)
    }
}
