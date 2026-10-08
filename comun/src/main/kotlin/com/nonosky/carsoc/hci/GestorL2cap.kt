package com.nonosky.carsoc.hci

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * La capa L2CAP: reparte los CID de un enlace y abre canales.
 *
 * Hay dos caminos y son muy distintos de coste. Conviene tenerlo claro antes
 * de leer el codigo, porque explica por que la bateria es alcanzable y el OBD
 * no lo es todavia:
 *
 * ### Camino 1: canal FIJO (la bateria, por ATT)
 *
 * En un enlace LE el CID **0x0004** existe desde el primer instante. No se
 * pide, no se negocia, no se configura: en cuanto hay Connection Complete se
 * puede mandar una PDU ATT y el aparato contesta. Eso es todo lo que hace
 * falta para leer un BMS. Es [canalFijo] y son cuatro lineas.
 *
 * ### Camino 2: canal DINAMICO (el OBD, por RFCOMM)
 *
 * RFCOMM (PSM 0x0003) vive en un canal que hay que pedir. El dialogo completo,
 * por el CID 0x0001 de senalizacion:
 *
 * ```
 *   yo  -> CONNECTION REQUEST     (PSM=3, mi CID)
 *   yo  <- CONNECTION RESPONSE    (su CID, resultado)         [puede venir "pendiente"]
 *   yo  -> CONFIGURATION REQUEST  (su CID, MTU que quiero)
 *   yo  <- CONFIGURATION RESPONSE (resultado)
 *   yo  <- CONFIGURATION REQUEST  (mi CID, MTU que quiere)     <-- ESTE hay que contestar
 *   yo  -> CONFIGURATION RESPONSE (OK)
 *   ---> el canal esta abierto SOLO cuando las dos configuraciones terminaron
 * ```
 *
 * El paso que se olvida siempre es el penultimo. Quien manda su peticion,
 * recibe su respuesta y empieza a hablar deja al otro lado esperando una
 * configuracion que nunca llega, y el sintoma es "mando datos y no contesta"
 * — tres capas por encima de la causa real.
 *
 * **Y aun asi, con el canal abierto todavia no hay OBD**: por encima queda el
 * multiplexor RFCOMM (SABM/UA, DLCI, creditos, MSC, PN) y por encima de ese,
 * el dialogo del ELM327. Este archivo llega hasta L2CAP y no finge llegar mas
 * lejos.
 *
 * ### Que hilo llama a que
 *
 * Los metodos bloqueantes ([abrirDinamico], [Canal.enviar] cuando falta
 * credito) los llaman hilos de trabajo. Los `alPdu` los llama el hilo de
 * reparto de la bomba. **Nunca** llamar a [abrirDinamico] desde dentro de un
 * callback: se estaria esperando a si mismo.
 */
class GestorL2cap(private val bomba: BombaHci) : BombaHci.Oyente {

    /**
     * Un canal abierto. Lo unico que sabe hacer es mandar y recibir cargas.
     *
     * `mtuRemoto` es cuanto acepta el otro lado en una sola PDU. L2CAP en modo
     * basico **no segmenta por encima de la MTU**: si el de arriba manda mas,
     * el otro lado tiene derecho a tirar la PDU entera. Por eso [enviar]
     * devuelve false en vez de partirla — partirla seria inventarse un
     * protocolo que el otro no habla.
     */
    class Canal internal constructor(
        val handle: Int,
        val cidLocal: Int,
        val cidRemoto: Int,
        val fijo: Boolean,
        private val gestor: GestorL2cap,
    ) {
        @Volatile
        var mtuRemoto: Int = L2cap.MTU_MINIMA_CLASICA
            internal set

        @Volatile
        var abierto: Boolean = false
            internal set

        var alRecibir: ((ByteArray) -> Unit)? = null

        @Volatile
        var rechazadosPorMtu = 0L
            private set

        fun enviar(datos: ByteArray, timeoutMs: Long = 5_000): Boolean {
            if (!abierto) return false
            if (datos.size > mtuRemoto) {
                rechazadosPorMtu++
                Log.w(TAG, "PDU de ${datos.size} B sobre una MTU de $mtuRemoto: no se manda")
                return false
            }
            return gestor.bomba.enviarAcl(handle, cidRemoto, datos, timeoutMs)
        }

        fun cerrar() = gestor.cerrar(this)

        override fun toString(): String =
            "canal handle=0x${"%03X".format(handle)} local=0x${"%04X".format(cidLocal)} " +
                "remoto=0x${"%04X".format(cidRemoto)} mtu=$mtuRemoto " +
                "${if (fijo) "fijo" else "dinamico"} ${if (abierto) "abierto" else "cerrado"}"
    }

    /** Resultado de intentar abrir un canal dinamico, con el motivo si fallo. */
    class Apertura(val canal: Canal?, val traza: List<String>) {
        val ok: Boolean get() = canal != null
    }

    private class Espera {
        val listo = CountDownLatch(1)
        @Volatile var datos: ByteArray? = null
    }

    /** Canal por (handle, cidLocal). Es la clave de enrutado de las PDU. */
    private val canales = ConcurrentHashMap<Long, Canal>()

    /** Peticiones de senalizacion sin contestar, por Identifier. */
    private val esperas = ConcurrentHashMap<Int, Espera>()

    /** Canales a medio abrir, por cidLocal: hace falta para casar el CONFIG del otro. */
    private val configurando = ConcurrentHashMap<Int, EnCurso>()

    private class EnCurso(val canal: Canal) {
        val miConfigOk = CountDownLatch(1)
        val suConfigContestada = CountDownLatch(1)
        @Volatile var falloConfig: String? = null
    }

    private val siguienteCid = AtomicInteger(L2cap.CID_DINAMICO_MIN)
    private val siguienteId = AtomicInteger(1)

    var senalesRecibidas = 0L
        private set

    var senalesRechazadas = 0L
        private set

    fun arrancar() = bomba.suscribir(this)

    fun detener() {
        bomba.quitar(this)
        canales.clear()
        configurando.clear()
        esperas.clear()
    }

    // ------------------------------------------------------------------
    // Canal fijo: ATT y SMP. Sin negociacion ninguna.
    // ------------------------------------------------------------------

    /**
     * Devuelve el canal de un CID fijo. Esto es TODO lo que hace falta para
     * hablar ATT con la bateria.
     *
     * No manda nada al aire porque no hay nada que mandar: el canal ya existe.
     * La MTU arranca en 23, el minimo de ATT en LE, y sube si el de arriba
     * negocia un `ATT_EXCHANGE_MTU`.
     */
    fun canalFijo(handle: Int, cid: Int = L2cap.CID_ATT): Canal {
        val clave = clave(handle, cid)
        canales[clave]?.let { return it }
        val c = Canal(handle, cid, cid, fijo = true, gestor = this)
        c.mtuRemoto = if (cid == L2cap.CID_ATT) L2cap.MTU_ATT_POR_DEFECTO else L2cap.MTU_MINIMA_CLASICA
        c.abierto = true
        canales[clave] = c
        return c
    }

    // ------------------------------------------------------------------
    // Canal dinamico: el camino largo, para RFCOMM
    // ------------------------------------------------------------------

    /**
     * Abre un canal dinamico para un PSM. Bloquea hasta terminar el dialogo.
     *
     * Devuelve la traza completa aunque salga bien: cuando esto falla en un
     * radio sin shell, saber EN QUE paso fallo es la diferencia entre
     * arreglarlo y adivinar. Un "no se pudo conectar" no vale de nada.
     */
    fun abrirDinamico(
        handle: Int,
        psm: Int = L2cap.PSM_RFCOMM,
        mtu: Int = MTU_DESEADA,
        timeoutMs: Long = 8_000,
    ): Apertura {
        val traza = mutableListOf<String>()
        val cidLocal = pedirCid()
        val canal0 = Canal(handle, cidLocal, 0, fijo = false, gestor = this)
        val curso = EnCurso(canal0)
        configurando[cidLocal] = curso
        canales[clave(handle, cidLocal)] = canal0

        try {
            // --- 1. CONNECTION REQUEST, con reintentos por "pendiente" ---
            var cidRemoto = 0
            var intentos = 0
            while (intentos < MAX_PENDIENTES) {
                intentos++
                val id = pedirId()
                val esp = Espera()
                esperas[id] = esp
                traza += "-> CONNECTION REQUEST psm=0x${"%04X".format(psm)} miCid=0x${"%04X".format(cidLocal)} id=$id"
                if (!bomba.enviarAcl(handle, L2cap.CID_SENAL_CLASICO,
                        SenalizacionL2cap.peticionConexion(id, psm, cidLocal), timeoutMs)) {
                    esperas.remove(id)
                    traza += "ERROR: no se pudo mandar la peticion de conexion"
                    return fracaso(handle, cidLocal, traza)
                }
                val llego = esp.listo.await(timeoutMs, TimeUnit.MILLISECONDS)
                esperas.remove(id)
                if (!llego) {
                    traza += "ERROR: sin CONNECTION RESPONSE en ${timeoutMs}ms"
                    return fracaso(handle, cidLocal, traza)
                }
                val rsp = SenalizacionL2cap.leerConexionRsp(esp.datos ?: ByteArray(0))
                if (rsp == null) {
                    traza += "ERROR: CONNECTION RESPONSE ilegible"
                    return fracaso(handle, cidLocal, traza)
                }
                traza += "<- CONNECTION RESPONSE suCid=0x${"%04X".format(rsp.cidDestino)} " +
                    "resultado=${SenalizacionL2cap.nombreResultadoConexion(rsp.resultado)}"
                if (rsp.pendiente) {
                    // "Pendiente" significa que esta preguntando arriba (a
                    // menudo, pidiendo autorizacion). Hay que esperar otra
                    // respuesta con el MISMO id, no reintentar la peticion.
                    val esp2 = Espera()
                    esperas[id] = esp2
                    val llego2 = esp2.listo.await(timeoutMs, TimeUnit.MILLISECONDS)
                    esperas.remove(id)
                    if (!llego2) {
                        traza += "ERROR: quedo en pendiente y no llego la respuesta final"
                        return fracaso(handle, cidLocal, traza)
                    }
                    val rsp2 = SenalizacionL2cap.leerConexionRsp(esp2.datos ?: ByteArray(0))
                        ?: return fracaso(handle, cidLocal, traza + "ERROR: segunda respuesta ilegible")
                    traza += "<- CONNECTION RESPONSE (final) " +
                        "resultado=${SenalizacionL2cap.nombreResultadoConexion(rsp2.resultado)}"
                    if (!rsp2.ok) return fracaso(handle, cidLocal, traza)
                    cidRemoto = rsp2.cidDestino
                    break
                }
                if (!rsp.ok) {
                    if (rsp.resultado == SenalizacionL2cap.RES_CONEXION_SEGURIDAD) {
                        traza += "   el aparato exige emparejamiento antes de abrir el canal"
                    }
                    return fracaso(handle, cidLocal, traza)
                }
                cidRemoto = rsp.cidDestino
                break
            }
            if (cidRemoto == 0) {
                traza += "ERROR: no se obtuvo CID remoto"
                return fracaso(handle, cidLocal, traza)
            }

            // El canal ya tiene los dos CID: se recrea con el remoto puesto y
            // se reemplaza en las dos tablas antes de configurar, porque el
            // CONFIG REQUEST del otro lado puede llegar en cualquier momento.
            val canal = Canal(handle, cidLocal, cidRemoto, fijo = false, gestor = this)
            canal.mtuRemoto = L2cap.MTU_MINIMA_CLASICA
            val curso2 = EnCurso(canal)
            configurando[cidLocal] = curso2
            canales[clave(handle, cidLocal)] = canal

            // --- 2. CONFIGURATION REQUEST mio ---
            val idCfg = pedirId()
            val espCfg = Espera()
            esperas[idCfg] = espCfg
            traza += "-> CONFIGURATION REQUEST suCid=0x${"%04X".format(cidRemoto)} mtu=$mtu id=$idCfg"
            if (!bomba.enviarAcl(handle, L2cap.CID_SENAL_CLASICO,
                    SenalizacionL2cap.peticionConfig(idCfg, cidRemoto, mtu), timeoutMs)) {
                esperas.remove(idCfg)
                traza += "ERROR: no se pudo mandar la configuracion"
                return fracaso(handle, cidLocal, traza)
            }
            val llegoCfg = espCfg.listo.await(timeoutMs, TimeUnit.MILLISECONDS)
            esperas.remove(idCfg)
            if (!llegoCfg) {
                traza += "ERROR: sin CONFIGURATION RESPONSE en ${timeoutMs}ms"
                return fracaso(handle, cidLocal, traza)
            }
            val cfg = SenalizacionL2cap.leerConfigRsp(espCfg.datos ?: ByteArray(0))
            if (cfg == null || !cfg.ok) {
                traza += "<- CONFIGURATION RESPONSE resultado=0x${"%04X".format(cfg?.resultado ?: -1)} (fallo)"
                return fracaso(handle, cidLocal, traza)
            }
            // Si contesta con una MTU menor que la pedida, esa es la que vale.
            SenalizacionL2cap.mtuDeOpciones(cfg.opciones)?.let { canal.mtuRemoto = it }
            traza += "<- CONFIGURATION RESPONSE OK (mtu efectiva ${canal.mtuRemoto})"

            // --- 3. Esperar el CONFIGURATION REQUEST del otro lado ---
            // Lo contesta [atenderSenal] en el hilo de reparto; aqui solo se
            // espera. Sin este paso el canal NO esta abierto para el otro lado
            // aunque lo este para nosotros.
            if (!curso2.suConfigContestada.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                traza += "ERROR: el otro lado no mando su CONFIGURATION REQUEST; " +
                    "el canal quedaria abierto solo en un sentido"
                return fracaso(handle, cidLocal, traza)
            }
            curso2.falloConfig?.let {
                traza += "ERROR al contestar su configuracion: $it"
                return fracaso(handle, cidLocal, traza)
            }
            traza += "-> CONFIGURATION RESPONSE OK (contestada la suya)"

            canal.abierto = true
            configurando.remove(cidLocal)
            traza += "canal ABIERTO: $canal"
            return Apertura(canal, traza)
        } catch (e: Exception) {
            return fracaso(handle, cidLocal, traza + "ERROR: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun fracaso(handle: Int, cidLocal: Int, traza: List<String>): Apertura {
        configurando.remove(cidLocal)
        canales.remove(clave(handle, cidLocal))
        return Apertura(null, traza)
    }

    /** Cierra un canal dinamico con el dialogo que toca. */
    fun cerrar(canal: Canal) {
        canal.abierto = false
        canales.remove(clave(canal.handle, canal.cidLocal))
        configurando.remove(canal.cidLocal)
        if (canal.fijo) return
        runCatching {
            bomba.enviarAcl(
                canal.handle, L2cap.CID_SENAL_CLASICO,
                SenalizacionL2cap.peticionDesconexion(pedirId(), canal.cidRemoto, canal.cidLocal),
                1_000,
            )
        }
    }

    // ------------------------------------------------------------------
    // Entrada: repartir PDU y atender la senalizacion
    // ------------------------------------------------------------------

    override fun alPdu(handle: Int, pdu: ByteArray) {
        val cid = L2cap.cidDe(pdu)
        val carga = L2cap.cargaDe(pdu)

        if (cid == L2cap.CID_SENAL_CLASICO || cid == L2cap.CID_SENAL_LE) {
            atenderSenal(handle, cid, carga)
            return
        }

        val canal = canales[clave(handle, cid)]
        if (canal == null) {
            // Una PDU para un CID que no tenemos abierto. En un canal fijo esto
            // pasa de verdad: el aparato manda una notificacion ATT antes de
            // que nadie haya pedido el canal. Se crea al vuelo para no perder
            // el dato.
            if (cid == L2cap.CID_ATT || cid == L2cap.CID_SMP) {
                canalFijo(handle, cid).alRecibir?.invoke(carga)
            }
            return
        }
        runCatching { canal.alRecibir?.invoke(carga) }
            .onFailure { Log.w(TAG, "el consumidor del canal lanzo: ${it.message}") }
    }

    override fun alCaerEnlace(handle: Int, razon: Int) {
        // Todos los canales de ese enlace dejan de existir. Dejarlos "abiertos"
        // haria que el de arriba siguiera mandando a un handle que el
        // controlador ya reasigno a otro aparato.
        val muertos = canales.entries.filter { (it.key shr 32).toInt() == handle }
        for (e in muertos) {
            e.value.abierto = false
            canales.remove(e.key)
            configurando.remove(e.value.cidLocal)
        }
        if (muertos.isNotEmpty()) {
            Log.i(TAG, "enlace 0x${"%03X".format(handle)} caido (razon 0x${"%02X".format(razon)}): " +
                "${muertos.size} canales cerrados")
        }
    }

    /**
     * Atiende un mensaje de senalizacion.
     *
     * Se contesta a TODO lo que llega, aunque sea para decir que no se
     * soporta. Ignorar un mensaje de senalizacion deja al otro lado esperando
     * y hay pilas que no siguen adelante hasta que se les contesta.
     */
    private fun atenderSenal(handle: Int, cid: Int, carga: ByteArray) {
        for (m in SenalizacionL2cap.desarmar(carga)) {
            senalesRecibidas++
            when (m.codigo) {
                SenalizacionL2cap.COD_CONEXION_RSP,
                SenalizacionL2cap.COD_CONFIG_RSP,
                -> despertar(m)

                SenalizacionL2cap.COD_CONFIG_PET -> atenderConfigPet(handle, cid, m)

                SenalizacionL2cap.COD_DESCONEXION_PET -> {
                    val cids = SenalizacionL2cap.leerDesconexion(m.datos)
                    if (cids != null) {
                        // datos = miCid(destino) | suCid(origen)
                        val canal = canales.remove(clave(handle, cids.first))
                        canal?.abierto = false
                        configurando.remove(cids.first)
                        responder(handle, cid,
                            SenalizacionL2cap.respuestaDesconexion(m.id, cids.second, cids.first))
                    }
                }

                SenalizacionL2cap.COD_DESCONEXION_RSP -> despertar(m)

                SenalizacionL2cap.COD_ECO_PET ->
                    responder(handle, cid, SenalizacionL2cap.respuestaEco(m.id, m.datos))

                SenalizacionL2cap.COD_INFO_PET -> {
                    val tipo = if (m.datos.size >= 2) {
                        (m.datos[0].toInt() and 0xFF) or ((m.datos[1].toInt() and 0xFF) shl 8)
                    } else 0
                    // Decir "no soportado" es correcto y suficiente: no
                    // implementamos features extendidas. Callarse, no.
                    responder(handle, cid, SenalizacionL2cap.respuestaInfo(
                        m.id, tipo, SenalizacionL2cap.INFO_RES_NO_SOPORTADO))
                }

                SenalizacionL2cap.COD_RECHAZO -> {
                    senalesRechazadas++
                    Log.w(TAG, "el otro lado rechazo nuestro mensaje id=${m.id}: " +
                        HciUsb.hex(m.datos))
                    despertar(m)
                }

                else -> {
                    senalesRechazadas++
                    responder(handle, cid, SenalizacionL2cap.rechazo(
                        m.id, SenalizacionL2cap.RECHAZO_NO_ENTENDIDO))
                }
            }
        }
    }

    /**
     * Contesta el CONFIGURATION REQUEST del otro lado.
     *
     * Es el paso que hace que el canal quede abierto en su sentido. Se acepta
     * la MTU que pida —tomar nota de cuanto acepta EL es justo el dato que
     * hace falta para no pasarse al enviar— y se rechazan por tipo las
     * opciones que no se entienden y no traen el bit de hint, que es lo que
     * manda la especificacion.
     */
    private fun atenderConfigPet(handle: Int, cid: Int, m: SenalizacionL2cap.Mensaje) {
        val pet = SenalizacionL2cap.leerConfigPet(m.datos) ?: return
        val curso = configurando[pet.cidDestino]
        val canal = curso?.canal ?: canales[clave(handle, pet.cidDestino)]

        val veredicto = SenalizacionL2cap.revisarOpciones(pet.opciones)
        if (veredicto.ok) {
            SenalizacionL2cap.mtuDeOpciones(pet.opciones)?.let { canal?.mtuRemoto = it }
        }
        val ok = responder(handle, cid, SenalizacionL2cap.respuestaConfig(
            m.id, pet.cidDestino, veredicto.resultado, veredicto.opciones))

        if (curso != null) {
            if (!ok) curso.falloConfig = "no se pudo mandar la respuesta de configuracion"
            else if (!veredicto.ok) curso.falloConfig =
                "sus opciones no se pudieron aceptar (resultado 0x${"%04X".format(veredicto.resultado)})"
            // Con banderas de continuacion faltan mas opciones y el dialogo
            // sigue; solo se da por contestada la ultima parte.
            if (!pet.continua) curso.suConfigContestada.countDown()
        }
    }

    /**
     * Contesta un mensaje de señalizacion **sin bloquear el reparto**.
     *
     * Este metodo se llama desde `alPdu`, o sea desde el hilo de reparto de la
     * bomba, que atiende a TODOS los oyentes en serie. La version anterior
     * hacia `enviarAcl(..., 2_000)`, que espera hasta dos segundos a que haya
     * credito ACL — y durante esos dos segundos se paraban tambien:
     *
     *   - las notificaciones ATT del BMS (la bateria dejaba de actualizarse),
     *   - los bytes del ELM327 (el motor se congelaba),
     *   - y los avisos de caida de enlace, que es lo peor: todo el mundo
     *     seguia creyendo que su enlace vivia.
     *
     * Justo cuando falta credito —o sea, cuando el controlador va saturado— es
     * cuando mas trafico hay que repartir. Bloquear ahi es bloquear en el peor
     * momento posible.
     *
     * Ahora se manda en un hilo aparte y se vuelve de inmediato. La
     * señalizacion L2CAP tolera de sobra ese retraso: sus plazos son de
     * segundos y hay reintentos por encima.
     */
    private fun responder(handle: Int, cid: Int, mensaje: ByteArray): Boolean {
        runCatching {
            kotlin.concurrent.thread(name = "l2cap-responde", isDaemon = true) {
                runCatching { bomba.enviarAcl(handle, cid, mensaje, 2_000) }
                    .onFailure { Log.w(TAG, "no se pudo responder senalizacion: ${it.message}") }
            }
        }.onFailure { Log.w(TAG, "no se pudo lanzar la respuesta: ${it.message}") }
        // Se devuelve true porque la respuesta quedo ENCARGADA. Si de verdad
        // no sale, el otro extremo reintenta: eso lo cubre el protocolo.
        return true
    }

    private fun despertar(m: SenalizacionL2cap.Mensaje) {
        val esp = esperas[m.id] ?: return
        esp.datos = m.datos
        esp.listo.countDown()
    }

    // ------------------------------------------------------------------

    /**
     * Reparte CID locales desde 0x0040, que es donde empieza el rango
     * dinamico. No se reutilizan enseguida a proposito: un CID recien cerrado
     * puede recibir todavia una PDU en camino, y darsela a un canal nuevo
     * seria entregar datos de una conversacion a otra.
     */
    private fun pedirCid(): Int {
        while (true) {
            val v = siguienteCid.getAndIncrement()
            if (v > L2cap.CID_DINAMICO_MAX - 1) {
                siguienteCid.set(L2cap.CID_DINAMICO_MIN)
                continue
            }
            if (canales.keys.none { (it and 0xFFFFFFFFL).toInt() == v }) return v
        }
    }

    private fun pedirId(): Int {
        while (true) {
            val v = siguienteId.getAndIncrement()
            if (v > 255) {
                siguienteId.set(1)
                continue
            }
            if (!esperas.containsKey(v)) return v
        }
    }

    /** Clave de enrutado: handle en los 32 bits altos, CID en los bajos. */
    private fun clave(handle: Int, cid: Int): Long =
        (handle.toLong() shl 32) or (cid.toLong() and 0xFFFFFFFFL)

    fun diagnostico(): List<String> = listOf(
        "canales abiertos: ${canales.size}",
    ) + canales.values.map { "  $it" } + listOf(
        "senales recibidas: $senalesRecibidas, rechazos: $senalesRechazadas",
        "aperturas en curso: ${configurando.size}, esperas de respuesta: ${esperas.size}",
    )

    private companion object {
        const val TAG = "GestorL2cap"

        /**
         * MTU que se pide en un canal dinamico.
         *
         * 330 es lo que piden las pilas para RFCOMM y lo que aceptan los
         * adaptadores OBD baratos. Pedir mas no ayuda —las respuestas de un
         * ELM327 son de decenas de bytes— y hay clones que rechazan MTU
         * grandes en vez de negociarlas a la baja.
         */
        const val MTU_DESEADA = 330

        /** Reintentos ante un "pendiente" antes de rendirse. */
        const val MAX_PENDIENTES = 2
    }
}
