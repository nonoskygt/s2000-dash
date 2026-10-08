package com.nonosky.carsoc.bateria

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.nonosky.carsoc.Carro
import com.nonosky.carsoc.config.Emparejados
import kotlin.concurrent.thread

/**
 * Los bancos de litio del carro, cada uno fijado por su MAC.
 *
 * ⚠️ POR QUE ESTO EXISTE, y no se reutiliza [VigilanteBateria].
 *
 * El vigilante barre el aire y se queda con **el primer BMS JBD que ve**.
 * Con un solo banco eso funciona. Con DOS bancos del
 * mismo fabricante, cual le toca es cuestion de suerte: el dueño vio la
 * tarjeta rotulada "vivienda" mostrando la bateria de arranque. No estaban
 * cruzadas por un error de cableado; estaban **sin identificar**, y el
 * rotulo de la pantalla era una suposicion.
 *
 * La documentacion del ecosistema JBD es tajante en esto: dos BMS de fabrica
 * anuncian el mismo nombre y **hay que distinguirlos por direccion**. Asi que
 * aqui cada banco se fija por MAC y nunca se adivina.
 *
 * Se lee **de uno en uno**, no en paralelo. Sostener dos GATT a la vez es
 * posible, pero la especificacion solo admite un `LE Create Connection`
 * pendiente y encadenarlos mal es la causa mas citada de "solo me conecta a
 * N". Turnarse cuesta unos segundos de latencia y ahorra esa clase entera de
 * fallo.
 */
class BancosBateria(private val context: Context) {

    /** Un banco: quien es, y lo ultimo que dijo. */
    data class Banco(
        val clave: String,
        val mac: String,
        val rotulo: String,
        @Volatile var soc: Int? = null,
        @Volatile var voltaje: Float? = null,
        @Volatile var corrienteA: Float? = null,
        @Volatile var temperaturaC: Int? = null,
        @Volatile var celdas: Int = 0,
        @Volatile var leidoMs: Long = 0L,
        @Volatile var detalle: String? = null,
        /** Vueltas seguidas sin lectura. Ver [esperaTras]. */
        @Volatile var fallosSeguidos: Int = 0,
        /** No se le vuelve a llamar antes de esta hora. */
        @Volatile var proximoIntentoMs: Long = 0L,
    ) {
        val potenciaW: Float?
            get() {
                val v = voltaje ?: return null
                val a = corrienteA ?: return null
                return v * a
            }

        /**
         * Un banco leido hace mas de un minuto ya no es una lectura: es un
         * recuerdo. Se pinta "--" y el punto de enlace se apaga.
         */
        fun vivo(ahoraMs: Long): Boolean =
            leidoMs > 0L && (ahoraMs - leidoMs) < SIN_VERSE_MS && voltaje != null
    }

    /**
     * Cada banco va por su MAC. El nombre que el dueño le pone desde la app
     * de JBD ayuda a saber cual es cual, pero viaja en el anuncio y no
     * siempre llega, asi que manda la MAC.
     */
    /**
     * Los bancos que este carro tiene, con el aparato que el dueño les
     * asigno en el menu de emparejamiento.
     *
     * Ya no hay MAC escritas aqui: hay carros con un solo banco y carros con
     * dos, y cual es cual lo decide el dueño, no el orden en que aparezcan
     * en un barrido.
     */
    val arranque = Banco(
        "arr",
        Emparejados.mac(context, Emparejados.Papel.BancoArranque),
        Emparejados.nombre(context, Emparejados.Papel.BancoArranque)
            .ifBlank { "Batería de arranque" },
    )

    val vivienda: Banco? =
        if (!Carro.perfil.tieneBancoVivienda) null
        else Banco(
            "viv",
            Emparejados.mac(context, Emparejados.Papel.BancoVivienda),
            Emparejados.nombre(context, Emparejados.Papel.BancoVivienda)
                .ifBlank { "Batería de vivienda" },
        )

    /**
     * Solo los que TIENEN aparato asignado. Un banco sin MAC no se sondea:
     * intentar conectar a una cadena vacia gasta un turno de radio por
     * ciclo y ensucia el registro con fallos que no son fallos.
     */
    private val todos: List<Banco>
        get() = listOfNotNull(vivienda, arranque).filter { it.mac.isNotBlank() }

    @Volatile private var vivo = false
    private var hilo: Thread? = null

    /** Avisa a la pantalla de que hay lectura nueva. */
    var alCambiar: (() -> Unit)? = null

    fun arrancar() {
        if (vivo) return
        vivo = true
        hilo = thread(name = "bancos-litio", isDaemon = true) {
            while (vivo) {
                // El guardian termico manda, igual que con las demas fuentes.
                // Hay radios mas holgados que otros, pero uno se apago TRES
                // veces por calor y la regla se aplica entera a todos.
                if (!com.nonosky.carsoc.Termometro.permiteBateria()) {
                    dormir(PERIODO_MS)
                    continue
                }
                for (b in todos) {
                    if (!vivo) break
                    // Un banco que no aparece se deja descansar: sin esto, una
                    // bateria desconectada costaba dos intentos de 12 s cada
                    // vuelta, para siempre.
                    if (System.currentTimeMillis() < b.proximoIntentoMs) continue
                    // Un reintento. El banco de vivienda esta mas lejos
                    // (RSSI -72 contra -66) y falla la conexion una de cada
                    // dos: sin reintentar, caducaba antes de la siguiente
                    // lectura buena y la tarjeta parpadeaba a "--" teniendo
                    // la bateria a metro y medio.
                    var ok = leer(b)
                    if (!ok && vivo) {
                        dormir(REINTENTO_MS)
                        ok = leer(b)
                    }
                    b.fallosSeguidos = if (ok) 0 else b.fallosSeguidos + 1
                    b.proximoIntentoMs = System.currentTimeMillis() + esperaTras(b.fallosSeguidos) - PERIODO_MS
                    // Respiro entre bancos: le da tiempo al controlador a
                    // cerrar el enlace anterior antes de abrir el siguiente.
                    dormir(PAUSA_ENTRE_BANCOS_MS)
                }
                dormir(PERIODO_MS)
            }
        }
    }

    fun detener() {
        vivo = false
        hilo?.interrupt()
        hilo = null
    }

    private fun dormir(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }

    private fun adaptador(): BluetoothAdapter? = runCatching {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }.getOrNull()

    /** Devuelve true si la lectura llego a publicar datos. */
    private fun leer(b: Banco): Boolean {
        var ok = false
        runCatching {
            // Turno unico para toda la radio BLE: con tres lectores sueltos,
            // el de vivienda dejo de leer del todo mientras el de arranque
            // seguia contestando. Ver TurnoBle.
            val lectura = TurnoBle.conLaRadio("banco-${b.clave}") {
                // POR DONDE SE LEE. Hay radios cuyo BLE interno no ha
                // recibido un solo anuncio en su vida —medido, bitacora
                // radio-nuevo— y en ellos la bateria solo se lee por el dongle
                // USB. Si el dongle esta enchufado, se usa; si no, la radio
                // interna, que en otros radios funciona. Antes los bancos solo
                // sabian ir por la interna: en esos radios el tablero se
                // quedaba sin bateria.
                if (com.nonosky.carsoc.hci.HciUsb.hayDongle(context)) leerPorDongle(b.mac)
                else LectorBmsAndroid.leer(
                    context = context,
                    adaptador = adaptador(),
                    mac = b.mac,
                    // El registro 0x04 (voltaje por celda) se pedia y se tiraba:
                    // Banco no lo guarda. El numero de celdas sale del basico.
                    pedirCeldas = false,
                )
            } ?: run {
                b.detalle = "sin turno de radio"
                Log.w(TAG, "banco ${b.clave}: sin turno")
                return@runCatching
            }
            val basico = lectura.basico
            if (basico == null) {
                // NO se borra lo anterior: se deja envejecer y que `vivo()`
                // decida. Borrar al primer fallo hace parpadear la tarjeta
                // cada vez que una lectura se pierde, que en BLE es normal.
                b.detalle = lectura.problemas.firstOrNull() ?: "sin datos del BMS"
                Log.w(TAG, "banco ${b.clave} sin basico: ${b.detalle}")
                return@runCatching
            }
            b.soc = basico.soc
            b.voltaje = basico.voltajeV
            b.corrienteA = basico.corrienteA
            b.temperaturaC = basico.temperaturasC.firstOrNull()?.let { Math.round(it) }
            b.celdas = basico.numeroCeldas
            b.leidoMs = System.currentTimeMillis()
            b.detalle = null
            Log.i(TAG, "banco ${b.clave}: soc=${b.soc} v=${b.voltaje} a=${b.corrienteA}")
            ok = true
            runCatching { alCambiar?.invoke() }
        }.onFailure {
            b.detalle = "fallo leyendo: ${it.javaClass.simpleName}"
            Log.w(TAG, "banco ${b.clave} fallo: ${it.message}")
        }
        return ok
    }

    /** Una lectura por el dongle USB: abre el canal GATT, lee y lo cierra. */
    private fun leerPorDongle(mac: String): LectorBmsGatt.Lectura {
        val (canal, traza) = CanalGattHci.abrir(context, mac)
        if (canal == null) {
            return LectorBmsGatt.Lectura(
                problemas = listOf(traza.lastOrNull { it.startsWith("ERROR") } ?: "el dongle no conecto"),
                traza = traza,
            )
        }
        return try {
            LectorBmsGatt(canal).leerTodo(pedirCeldas = false)
        } finally {
            runCatching { canal.cerrar() }
        }
    }

    /** Para el puente HTTP: que esta viendo cada banco. */
    fun diagnostico(): List<String> {
        val ahora = System.currentTimeMillis()
        return todos.map { b ->
            val edad = if (b.leidoMs > 0) "${(ahora - b.leidoMs) / 1000}s" else "nunca"
            "${b.clave}  ${b.mac}  ${b.rotulo}  " +
                "soc=${b.soc ?: "--"}  v=${b.voltaje ?: "--"}  a=${b.corrienteA ?: "--"}  " +
                "celdas=${b.celdas}  leido=$edad  ${b.detalle ?: ""}"
        }
    }

    companion object {
        private const val TAG = "BancosBateria"

        /**
         * Cada cuanto se da una vuelta completa a los bancos.
         *
         * Era 8 s: una conexion GATT completa por banco cada 12-15 s, cuando
         * el propio proyecto ya habia medido que conectar asi de seguido
         * recalentaba el radio (ver ESPERA_DETECTADA_MS en VigilanteBateria).
         * Un SoC de litio no se mueve en 20 s. Con un fallo y su reintento
         * sigue sobrando margen hasta SIN_VERSE_MS.
         */
        const val PERIODO_MS = 20_000L
        const val PAUSA_ENTRE_BANCOS_MS = 1_200L
        const val REINTENTO_MS = 1_500L

        /** Igual que en BateriaState: pasado un minuto, deja de ser dato. */
        const val SIN_VERSE_MS = 60_000L

        /**
         * Cada cuanto se vuelve a llamar a un banco segun sus vueltas seguidas
         * sin lectura: el periodo normal mientras falle una o dos veces —que
         * en BLE es lo normal—, y despues el doble cada vez hasta 5 minutos.
         */
        internal fun esperaTras(fallos: Int): Long =
            if (fallos <= 2) PERIODO_MS
            else minOf(PERIODO_MS shl (fallos - 2).coerceAtMost(8), MAX_ESPERA_MS)

        const val MAX_ESPERA_MS = 300_000L
    }
}
