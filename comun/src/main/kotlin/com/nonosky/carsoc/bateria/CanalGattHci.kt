package com.nonosky.carsoc.bateria

import android.content.Context
import android.hardware.usb.UsbManager
import com.nonosky.carsoc.hci.BombaHci
import com.nonosky.carsoc.hci.ConexionLe
import com.nonosky.carsoc.hci.GestorL2cap
import com.nonosky.carsoc.hci.HciUsb
import com.nonosky.carsoc.hci.L2cap
import com.nonosky.carsoc.hci.RadioBt

/**
 * La union que faltaba: de un dongle USB a una sesion GATT utilizable.
 *
 * Las piezas ya existian por separado —[HciUsb] habla con el dongle,
 * [BombaHci] reparte eventos y ACL, [GestorL2cap] da canales, `Att` arma las
 * PDU y [LectorBmsGatt] entiende el BMS— pero nadie las ataba. Esta clase es
 * ese nudo, y nada mas: abre el dongle, conecta con la bateria por LE, toma
 * el canal fijo de ATT y expone el par enviar/recibir que el lector espera.
 *
 * El canal de ATT es **fijo (CID 0x0004)**, y esa es la razon por la que la
 * bateria se puede leer y el adaptador OBD es mucho mas trabajo: aqui no hay
 * que negociar canal, ni consultar SDP, ni emparejarse. Se conecta y se
 * habla.
 */
class CanalGattHci private constructor(
    private val hci: HciUsb,
    private val bomba: BombaHci,
    private val gestor: GestorL2cap,
    private val canal: GestorL2cap.Canal,
    private val handle: Int,
    val traza: List<String>,
) : CanalGatt, BombaHci.Oyente {

    @Volatile
    private var vivo = true

    override val abierto: Boolean get() = vivo

    /**
     * Cola propia, con suscripcion PERMANENTE mientras el canal viva.
     *
     * No se usa `BombaHci.esperarPdu` y esto no es un capricho: ese metodo se
     * suscribe, espera UN PDU y se da de baja en su `finally`. Entre que el
     * lector procesa una notificacion y vuelve a llamar, nadie escucha — y las
     * dos notificaciones en que llega la respuesta del registro 0x03 del BMS
     * salen con microsegundos de diferencia, asi que la segunda cae justo en
     * ese hueco y se descarta.
     *
     * Se midio: el 0x03 llegaba siempre "a medias, 20 bytes sin completar tras
     * 1 notificacion". No era el MTU ni el CCCD —los dos sospechosos obvios—
     * era la ventana ciega entre dos esperas. Con una cola no hay ventana.
     */
    private val recibidas = java.util.concurrent.LinkedBlockingQueue<ByteArray>()

    /**
     * Encola lo que llega. **Envuelto de arriba a abajo, sin excepciones.**
     *
     * Esto corre en el hilo de la bomba, y en Android una excepcion que escapa
     * de un hilo MATA el proceso entero. La primera version dejaba fuera del
     * runCatching la llamada a `L2cap.cidDe(pdu)` —solo envolvia el `offer`—
     * y con eso basto: un PDU mas corto de lo que espera el parseo tumbo el
     * tablero, el puente y el actualizador de golpe, y hubo que arrancar el
     * carro para recuperarlo.
     *
     * La regla de este proyecto no es "envuelve lo que pueda fallar", es
     * **envuelve el metodo entero** cuando corre en un hilo ajeno. Adivinar
     * cual linea lanza es como se llega a un radio incomunicado.
     */
    override fun alPdu(handle: Int, pdu: ByteArray) {
        runCatching {
            if (handle != this.handle) return
            if (L2cap.cidDe(pdu) != L2cap.CID_ATT) return
            recibidas.offer(L2cap.cargaDe(pdu))
        }
    }

    override fun alCaerEnlace(handle: Int, razon: Int) {
        runCatching { if (handle == this.handle) vivo = false }
    }

    override fun enviar(pdu: ByteArray): Boolean =
        runCatching { bomba.enviarAcl(handle, L2cap.CID_ATT, pdu) }.getOrDefault(false)

    override fun recibir(timeoutMs: Int): ByteArray? = runCatching {
        recibidas.poll(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
    }.getOrNull()

    /**
     * Cierra en orden inverso al de apertura.
     *
     * Importa: dejar la conexion LE viva sin cerrarla ocupa una de las pocas
     * ranuras del controlador, y a la tercera vez el dongle deja de aceptar
     * conexiones nuevas sin decir por que.
     */
    override fun cerrar() {
        if (!vivo) return
        vivo = false
        runCatching { bomba.quitar(this) }
        runCatching { gestor.cerrar(canal) }
        runCatching {
            bomba.comando(
                ConexionLe.CMD_DISCONNECT,
                ConexionLe.parametrosDesconectar(handle),
            )
        }
        // La radio NO se cierra aqui: puede haber un enlace del motor vivo en
        // ella. Solo se suelta la referencia, y RadioBt cierra cuando nadie
        // quede. Cerrarla de golpe tiraria el OBD sin motivo.
        RadioBt.soltar("gatt-bateria")
    }

    companion object {

        /**
         * Abre todo el camino hasta la bateria.
         *
         * Devuelve null si algo falla, y la traza cuenta hasta donde se llego:
         * en un radio sin shell, "no se pudo" a secas no permite arreglar nada.
         */
        /**
         * Abre el camino a la bateria sobre la radio COMPARTIDA.
         *
         * Antes cada consumidor abria su propio HciUsb y habia que serializarlos
         * con un candado, lo que obligaba a repartir el dongle por turnos: el
         * motor en linea a ratos y la bateria a ratos. Un controlador de doble
         * modo aguanta el enlace LE y el clasico a la vez, asi que el reparto
         * era mio, no del aparato.
         */
        fun abrir(context: Context, mac: String): Pair<CanalGattHci?, List<String>> {
            val traza = mutableListOf<String>()

            val piezas = RadioBt.tomar(context, "gatt-bateria")
                ?: return null to (RadioBt.traza + listOf(
                    "ERROR: no se pudo abrir la radio: ${RadioBt.ultimoFallo}"
                ))
            var conservar = false
            try {
                return abrirSobreRadio(piezas, mac, traza).also { conservar = it.first != null }
            } finally {
                // Si no salio canal, se suelta ya. Si salio, lo suelta cerrar().
                if (!conservar) RadioBt.soltar("gatt-bateria")
            }
        }

        private fun abrirSobreRadio(
            piezas: RadioBt.Piezas,
            mac: String,
            traza: MutableList<String>,
        ): Pair<CanalGattHci?, List<String>> {
            val hci = piezas.hci
            val bomba = piezas.bomba
            val gestor = piezas.gestor

            traza += "conectando por LE con $mac"
            val estado = bomba.comando(
                ConexionLe.CMD_LE_CREATE_CONNECTION,
                ConexionLe.paraMac(mac).codificar(),
            )
            val st = ConexionLe.interpretarCommandStatus(estado, ConexionLe.CMD_LE_CREATE_CONNECTION)
            traza += "Command Status de la conexion: ${st ?: "sin respuesta"}"
            if (st != 0) {
                // Nombrarlo importa. 0x0B es "ACL Connection Already Exists":
                // el controlador cree que ya hay enlace con esta bateria,
                // normalmente uno huerfano de un proceso muerto. RadioBt lo
                // limpia con un HCI Reset al abrir en frio; si aun asi
                // aparece, el mensaje dice exactamente que buscar.
                traza += "ERROR: el controlador rechazo la conexion: " +
                    ConexionLe.nombreEstado(st ?: -1)
                return null to traza
            }

            // El evento de conexion tarda: el anuncio de la bateria puede ir
            // cada 1-2 segundos y hay que esperar a que toque.
            val evento = bomba.esperarEvento(12_000) {
                ConexionLe.interpretarConexionCompleta(it) != null
            }
            val completa = ConexionLe.interpretarConexionCompleta(evento)
            if (completa == null || completa.estado != 0) {
                traza += "ERROR: no se completo la conexion LE " +
                    "(${completa?.let { ConexionLe.nombreEstado(it.estado) } ?: "sin evento"})"
                // Cancelar, o el intento se queda colgado y bloquea el siguiente.
                runCatching { bomba.comando(ConexionLe.CMD_LE_CREATE_CONNECTION_CANCEL) }
                return null to traza
            }

            traza += "conectado, handle=${completa.handle}"
            val canal = gestor.canalFijo(completa.handle, L2cap.CID_ATT)
            traza += "canal ATT (CID 0x0004) listo"

            val ch = CanalGattHci(hci, bomba, gestor, canal, completa.handle, traza)
            // Suscribirse ANTES de devolver: si el BMS empieza a notificar solo
            // en cuanto se activa el CCCD, esas primeras tramas no se pierden.
            bomba.suscribir(ch)
            return ch to traza
        }

    }
}
