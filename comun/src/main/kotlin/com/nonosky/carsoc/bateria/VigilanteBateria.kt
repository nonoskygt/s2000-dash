package com.nonosky.carsoc.bateria

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.nonosky.carsoc.Carro
import com.nonosky.carsoc.hci.HciUsb
import com.nonosky.carsoc.hci.RadioBt
import kotlin.concurrent.thread

/**
 * Vigila la bateria de litio barriendo BLE por el dongle USB.
 *
 * Existe porque el Bluetooth del radio no sirve: no ve aparatos que cualquier
 * telefono ve, se apaga solo, y un barrido BLE de 25 segundos por la via
 * normal de Android no encontro absolutamente nada. El kernel de esta ROM
 * tampoco trae `btusb`, asi que el dongle nunca va a ser la radio del
 * sistema. Pero Android SI da permiso sobre el aparato USB, y un dongle
 * Bluetooth es un transporte HCI simple — asi que se le habla directo.
 *
 * Por ahora esto solo DETECTA: da MAC, nombre y señal. El voltaje y el SoC
 * viven detras de una conexion GATT (L2CAP + ATT sobre HCI) que todavia no
 * esta escrita. Detectar no es leer, y el tablero lo dice asi en vez de
 * inventar numeros.
 */
class VigilanteBateria(private val context: Context) {

    @Volatile
    var estado: BateriaState = BateriaState()
        private set

    var alCambiar: (() -> Unit)? = null

    @Volatile
    private var vivo = false
    private var hilo: Thread? = null

    /**
     * Cuando esta en pausa, el vigilante no toca el dongle.
     *
     * Hace falta porque el OBD necesita el dongle de forma CONTINUA mientras
     * dura una sesion AT, y el vigilante lo tomaba cada 30 segundos. Con el
     * candado a secas el OBD simplemente no llegaba a entrar nunca: perdia
     * todas las carreras contra un vigilante que ya estaba dentro.
     *
     * Pausar es mejor que subir el plazo de espera: esperar mas solo alarga la
     * carrera, no la gana.
     */
    @Volatile
    var enPausa: Boolean = false
        private set

    fun pausar() { enPausa = true }

    fun reanudar() { enPausa = false }

    fun arrancar() {
        if (vivo) return
        vivo = true
        // Hilo propio y envuelto entero: una excepcion que escapa de un hilo
        // en Android MATA el proceso, y eso tumbaria de golpe el tablero, el
        // puente y el actualizador. Ya paso una vez con el DebugServer.
        hilo = thread(name = "vigilante-bateria", isDaemon = true) {
            while (vivo) {
                if (enPausa) {
                    dormir(2_000)
                    continue
                }
                runCatching { unaRonda() }
                    .onFailure { Log.w(TAG, "ronda fallida: ${it.message}") }
                dormir(if (estado.detectada()) ESPERA_DETECTADA_MS else ESPERA_BUSCANDO_MS)
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
            runCatching { Thread.sleep(500) }.onFailure { return }
        }
    }

    /**
     * Un barrido corto. Se abre el dongle, se barre, se cierra.
     *
     * Todo bajo [DuenoDongle]: si el OBD tiene el dongle tomado, esta ronda se
     * salta sin drama y se reintenta a la siguiente. Antes las dos cosas lo
     * abrian a la vez, se robaban paquetes del mismo endpoint, y el sintoma
     * era "el BMS dejo de contestar" — que apunta al BMS y no al conflicto.
     */
    private fun unaRonda() {
        // El termometro manda: con el radio caliente, la bateria es lo primero
        // que se suelta. Sus datos cambian despacio y su lectura es cara.
        if (!com.nonosky.carsoc.Termometro.permiteBateria()) {
            publicar(estado.copy(detalle = "en pausa por temperatura del radio"))
            return
        }
        // Ya no hay candado: la radio es compartida y el barrido convive con el
        // enlace del motor. Lo unico que sigue siendo cierto es que barrer y
        // mantener un enlace LE a la vez es delicado en muchos controladores,
        // asi que el barrido solo se hace cuando aun no sabemos la MAC.
        rondaConDongle()
    }

    /**
     * Una ronda: si ya sabemos la MAC, se lee y punto; si no, se barre.
     *
     * Barrer y mantener enlaces a la vez es delicado en muchos controladores,
     * y ademas ya no hace falta: una vez conocida la MAC, el barrido no
     * aporta nada y solo estorba al enlace del motor.
     *
     * Y sobre todo: esto ya **no abre el aparato USB por su cuenta**. Lo hacia,
     * y ese fue el fallo que dejo al motor y a la bateria sin datos en plena
     * manejada: al reclamar la interfaz USB para barrer, ni el OBD ni el GATT
     * podian entrar. Antes lo tapaba un candado; al quitar el candado por la
     * radio compartida, el descuido quedo al descubierto. Ahora todo pasa por
     * [RadioBt].
     */
    private fun rondaConDongle() {
        val conocida = macFijada ?: estado.mac ?: macRecordada
        if (conocida != null) {
            leerBms(conocida)
            return
        }
        barrerParaEncontrarla()
    }

    /** Barrido BLE, solo mientras no se sepa a quien conectarse. */
    private fun barrerParaEncontrarla() {
        // Con la radio interna cableada no hace falta abrir ningun dongle.
        val interno = LectorBmsDirecto.barrer
        if (interno != null) {
            if (!estado.detectada()) {
                publicar(estado.copy(enlace = EnlaceBateria.Buscando, detalle = null))
            }
            val oidas = runCatching { interno(BARRIDO_MS.toInt() / 1000) }
                .getOrDefault(emptyList())
                .map { Hallazgo(it.first, it.second, it.third) }
            val elegida = elegir(oidas)
            if (elegida == null) {
                publicar(estado.copy(
                    enlace = EnlaceBateria.Buscando,
                    detalle = "no se oyo ninguna BMS por la radio interna",
                ))
                return
            }
            macRecordada = elegida.mac
            publicar(estado.copy(
                mac = elegida.mac,
                nombre = elegida.nombre ?: estado.nombre,
                rssi = elegida.rssi,
                vistaMs = System.currentTimeMillis(),
                enlace = EnlaceBateria.Detectada,
                detalle = null,
                candidatas = oidas.map {
                    "${it.mac}  ${it.nombre ?: "(sin nombre)"}  ${it.rssi} dBm" +
                        if (it.mac == elegida.mac) "  <- elegida" else ""
                },
            ))
            return
        }

        val piezas = RadioBt.tomar(context, "vigilante-bateria")
        if (piezas == null) {
            publicar(estado.copy(
                enlace = EnlaceBateria.SinDongle,
                detalle = RadioBt.ultimoFallo ?: "no se pudo abrir la radio",
            ))
            return
        }
        val hci = piezas.hci
        try {
            // Los comandos tambien van por la bomba: es la unica dueña del
            // aparato, y mandar por fuera de ella deja su respuesta en manos
            // de quien lea primero.
            //
            // Y el barrido va con ciclo de trabajo BAJO, no al 100%. Antes
            // intervalo y ventana eran iguales (0x0010 los dos), o sea
            // receptor encendido todo el tiempo — el modo de mayor consumo de
            // radio que existe. Con ventana 0x0010 sobre intervalo 0x00A0 se
            // escucha el 10% del tiempo, se encuentra igual un BMS que se
            // anuncia varias veces por segundo, y se le pide al puerto USB una
            // fraccion de la corriente.
            piezas.bomba.comando(
                HciUsb.CMD_LE_SET_SCAN_PARAMS,
                byteArrayOf(0x00, 0x10, 0x00, 0xA0.toByte(), 0x00, 0x00, 0x00),
            )
            piezas.bomba.comando(HciUsb.CMD_LE_SET_SCAN_ENABLE, byteArrayOf(0x01, 0x00))

            if (!estado.detectada()) {
                publicar(estado.copy(enlace = EnlaceBateria.Buscando, detalle = null))
            }

            // Los anuncios se reciben SUSCRIBIENDOSE a la bomba, no leyendo el
            // endpoint por nuestra cuenta.
            //
            // Antes este bucle llamaba a `hci.leerEvento(400)` mientras
            // `bomba-hci` leia el MISMO endpoint: dos hilos dentro de un
            // bulkTransfer sobre el mismo descriptor del kernel. Eso no es un
            // bug de aplicacion, es acceso concurrente a un recurso nativo — y
            // pasaba durante el barrido, que es exactamente cuando el dueño
            // veia reiniciarse el radio "al intentar conectar por Bluetooth".
            //
            // El propio proyecto ya documentaba dos veces que dos lectores del
            // mismo endpoint se roban los paquetes. Volvio a entrar por esta
            // puerta al migrar el vigilante a la radio compartida.
            val oidas = linkedMapOf<String, Hallazgo>()
            val quitar = piezas.bomba.suscribirEventos { e ->
                runCatching {
                    val h = interpretarAnuncio(e) ?: return@runCatching
                    synchronized(oidas) { oidas[h.mac] = h }
                }
            }
            try {
                val hasta = System.currentTimeMillis() + BARRIDO_MS
                while (vivo && System.currentTimeMillis() < hasta) {
                    try {
                        Thread.sleep(200)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            } finally {
                runCatching { quitar() }
            }

            piezas.bomba.comando(HciUsb.CMD_LE_SET_SCAN_ENABLE, byteArrayOf(0x00, 0x00))

            val elegida = elegir(oidas.values.toList())
            if (elegida != null) {
                // Se recuerda en disco: tras un reinicio se va directo al GATT
                // en vez de volver a barrer para redescubrir lo mismo.
                macRecordada = elegida.mac
                publicar(estado.copy(
                    mac = elegida.mac,
                    nombre = elegida.nombre ?: estado.nombre,
                    rssi = elegida.rssi,
                    vistaMs = System.currentTimeMillis(),
                    enlace = EnlaceBateria.Detectada,
                    detalle = null,
                    candidatas = oidas.values.map {
                        "${it.mac}  ${it.nombre ?: "(sin nombre)"}  ${it.rssi} dBm" +
                            if (it.mac == elegida.mac) "  <- elegida" else ""
                    },
                ))
            } else {
                publicar(estado.copy(enlace = EnlaceBateria.Buscando, detalle = "no se oyo ninguna BMS"))
            }
        } catch (e: Exception) {
            publicar(estado.copy(
                enlace = EnlaceBateria.Fallo,
                detalle = "${e.javaClass.simpleName}: ${e.message}",
            ))
        } finally {
            RadioBt.soltar("vigilante-bateria")
        }
    }

    /**
     * Conecta por GATT y lee el BMS de verdad.
     *
     * Va DESPUES del barrido y en su propia apertura del dongle: barrer y
     * mantener una conexion a la vez no lo admiten muchos controladores, y
     * mezclarlos deja las dos cosas a medias.
     *
     * Envuelto entero, como todo lo que puede tocar el dongle: una excepcion
     * suelta aqui se lleva el tablero, el puente y el actualizador. Ya paso
     * una vez por dejar una linea fuera del runCatching.
     */
    private fun leerBms(mac: String) {
        // Ya se entra con el dongle tomado por rondaConDongle: DuenoDongle es
        // reentrante justo para esto, asi que volver a pedirlo no se abraza a
        // si mismo.
        runCatching {
            // Camino corto: la radio INTERNA del head unit. Cuando esta
            // cableada no hay dongle de por medio, ni canal ATT que abrir, ni
            // DuenoDongle que respetar — la pila de Android hace el
            // descubrimiento y el MTU por dentro.
            val directo = LectorBmsDirecto.leer
            if (directo != null) {
                val lectura = directo(mac) ?: run {
                    publicar(estado.copy(detalle = "la radio interna no devolvio lectura"))
                    return
                }
                publicarLectura(lectura)
                return
            }

            val fabrica = CanalGattDisponible.fabrica ?: run {
                publicar(estado.copy(detalle = "el GATT no esta cableado"))
                return
            }
            val canal = fabrica(mac) ?: run {
                publicar(estado.copy(detalle = "no se pudo abrir el canal GATT"))
                return
            }
            try {
                val lectura = LectorBmsGatt(canal).leerTodo()
                publicarLectura(lectura)
            } finally {
                runCatching { canal.cerrar() }
            }
        }.onFailure {
            Log.w(TAG, "leerBms fallo: ${it.message}")
            runCatching {
                publicar(estado.copy(detalle = "fallo leyendo el BMS: ${it.javaClass.simpleName}"))
            }
        }
    }

    /**
     * Vuelca una lectura del BMS al estado. Comun a las dos radios.
     *
     * Se extrajo al meter la radio interna: antes vivia dentro del camino del
     * dongle, y duplicarlo habria significado dos sitios donde arreglar la
     * misma regla de prioridad entre el 0x03 y el 0x04.
     */
    private fun publicarLectura(lectura: LectorBmsGatt.Lectura) {
        val b = lectura.basico
        val c = lectura.celdas
        if (b == null && c == null) {
            publicar(estado.copy(
                detalle = "conectado pero sin datos del BMS" +
                    (lectura.problemas.firstOrNull()?.let { ": $it" } ?: ""),
            ))
            return
        }
        publicar(
            estado.copy(
                // El voltaje del 0x03 manda; la suma de celdas del 0x04 es el
                // respaldo. Si solo llega uno de los dos, se usa ese en vez de
                // dejar la pantalla vacia.
                voltaje = b?.voltajeV?.toFloat() ?: c?.sumaV?.toFloat(),
                soc = b?.soc,
                corrienteA = b?.corrienteA?.toFloat(),
                temperaturaC = b?.temperaturasC?.maxOrNull()?.toInt(),
                celdas = c?.celdasMv?.map { it / 1000f } ?: emptyList(),
                vistaMs = System.currentTimeMillis(),
                enlace = EnlaceBateria.Leyendo,
                detalle = null,
            )
        )
    }

    private fun publicar(nuevo: BateriaState) {
        estado = nuevo
        runCatching { alCambiar?.invoke() }
    }

    private data class Hallazgo(val mac: String, val nombre: String?, val rssi: Int)

    /**
     * Cual de las BMS oidas es la del carro.
     *
     * Por orden: la que el usuario fijo a mano, la que se llama como el carro,
     * y si no hay ninguna de las dos, la de señal mas fuerte — que casi
     * siempre es la que esta a un metro y no la del vecino.
     *
     * Antes se tomaba la primera que llegara, y eso no es un criterio: es el
     * orden en que el aire las puso, que cambia en cada ronda.
     */
    private fun elegir(oidas: List<Hallazgo>): Hallazgo? {
        if (oidas.isEmpty()) return null
        macFijada?.let { fija ->
            oidas.firstOrNull { it.mac.equals(fija, ignoreCase = true) }?.let { return it }
        }
        oidas.firstOrNull { it.nombre?.contains(NOMBRE_DEL_CARRO, ignoreCase = true) == true }
            ?.let { return it }
        return oidas.maxByOrNull { it.rssi }
    }

    /**
     * MAC fijada a mano, **guardada en disco**.
     *
     * Vivia solo en memoria y se perdia en cada reinicio del proceso — que en
     * este radio pasa a menudo: cambio de red, actualizacion, o el gestor de
     * bateria de la ROM. Al perderse, el vigilante volvia a elegir por su
     * cuenta y llego a leer la bateria de una BICICLETA, que tambien es un BMS
     * JBD y tambien anuncia el servicio 0xFF00.
     *
     * Elegir mal una bateria no es un detalle cosmetico: son los numeros con
     * los que alguien decide si su pack de litio esta bien.
     */
    var macFijada: String?
        get() = prefs.getString(CLAVE_MAC_FIJA, null)
        set(v) {
            prefs.edit().apply {
                if (v.isNullOrBlank()) remove(CLAVE_MAC_FIJA) else putString(CLAVE_MAC_FIJA, v)
            }.apply()
        }

    /**
     * La ultima MAC que se descubrio sola, tambien en disco.
     *
     * Sirve para que tras un reinicio se vaya directo al GATT en vez de
     * gastar un barrido de seis segundos redescubriendo lo que ya se sabia.
     */
    private var macRecordada: String?
        get() = prefs.getString(CLAVE_MAC_VISTA, null)
        set(v) {
            prefs.edit().apply {
                if (v.isNullOrBlank()) remove(CLAVE_MAC_VISTA) else putString(CLAVE_MAC_VISTA, v)
            }.apply()
        }

    private val prefs by lazy {
        context.getSharedPreferences("bateria", Context.MODE_PRIVATE)
    }

    /**
     * Saca la bateria de un LE Advertising Report, si es ella.
     *
     * Se reconoce por el UUID de servicio 0xFF00 del anuncio, que es lo que
     * delata a un BMS JBD/Xiaoxiang — no por el nombre. El nombre del aparato
     * suele coincidir con el del carro, que es una coincidencia comoda pero un
     * criterio fragil: cualquiera puede renombrar un BMS, y el servicio no.
     */
    private fun interpretarAnuncio(e: ByteArray): Hallazgo? {
        if (e.size < 12) return null
        if ((e[0].toInt() and 0xFF) != HciUsb.EVT_LE_META) return null
        if ((e[2].toInt() and 0xFF) != HciUsb.SUBEVT_LE_ADVERTISING_REPORT) return null

        var i = 4
        i++ // tipo de evento
        i++ // tipo de direccion
        if (i + 7 > e.size) return null
        val mac = (0 until 6).map { e[i + it] }.reversed()
            .joinToString(":") { String.format("%02X", it) }
        i += 6
        val largo = e[i].toInt() and 0xFF
        i++
        if (i + largo > e.size) return null
        val datos = e.copyOfRange(i, i + largo)
        val rssi = if (i + largo < e.size) e[i + largo].toInt() else 0

        var esBms = false
        var nombre: String? = null
        var j = 0
        while (j + 1 < datos.size) {
            val len = datos[j].toInt() and 0xFF
            if (len == 0) break
            val tipo = datos[j + 1].toInt() and 0xFF
            val fin = (j + 1 + len).coerceAtMost(datos.size)
            when (tipo) {
                // 0x02 lista incompleta de UUID de 16 bits, 0x03 completa.
                0x02, 0x03 -> {
                    var k = j + 2
                    while (k + 1 < fin) {
                        val uuid = ((datos[k + 1].toInt() and 0xFF) shl 8) or (datos[k].toInt() and 0xFF)
                        if (uuid == BateriaState.SERVICIO_JBD) esBms = true
                        k += 2
                    }
                }
                // 0x08 nombre corto, 0x09 nombre completo.
                0x08, 0x09 -> if (j + 2 < fin) {
                    nombre = String(datos, j + 2, fin - j - 2, Charsets.UTF_8)
                }
            }
            j += len + 1
        }

        return if (esBms) Hallazgo(mac, nombre, rssi) else null
    }

    private companion object {
        const val TAG = "VigilanteBateria"

        /** Cuanto se escucha en cada ronda. */
        const val BARRIDO_MS = 6_000L

        /**
         * Ya detectada, se comprueba de tarde en tarde: el dongle es un
         * recurso compartido y abrirlo cada segundo para reconfirmar lo que ya
         * se sabe solo quita tiempo a lo que venga despues (el GATT).
         */
        /**
         * 30 segundos entre lecturas de la bateria. NO 2.
         *
         * Se bajo a 2 s cuando el dueño pidio "tiempo real", y eso fue el
         * error que recalento el radio: cada ronda abre una conexion BLE
         * COMPLETA —conectar, descubrir servicios, negociar MTU, activar el
         * CCCD, dos peticiones y desconectar— asi que eran treinta ciclos por
         * minuto martilleando el dongle y la CPU sin parar.
         *
         * Y no aportaba nada: un SoC no se mueve en dos segundos, ni el
         * voltaje de una LiFePO4. "Tiempo real" tiene sentido para las RPM,
         * no para el estado de carga de una bateria.
         */
        const val ESPERA_DETECTADA_MS = 30_000L

        /** Sin detectar, se insiste mas seguido. */
        const val ESPERA_BUSCANDO_MS = 10_000L

        /**
         * El BMS del carro suele llevar el nombre del carro: se busca la
         * clave del carro de ESTA app.
         *
         * Es una comodidad, no una garantia: cualquiera puede renombrar un
         * BMS. Por eso hay ademas MAC fijable, y por eso se publican todas
         * las candidatas.
         */
        val NOMBRE_DEL_CARRO: String get() = Carro.perfil.clave

        const val CLAVE_MAC_FIJA = "mac_fijada"
        const val CLAVE_MAC_VISTA = "mac_vista"
    }
}
