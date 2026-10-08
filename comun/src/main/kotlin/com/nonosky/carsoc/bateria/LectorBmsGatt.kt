package com.nonosky.carsoc.bateria

import com.nonosky.carsoc.hci.Att

/**
 * El canal ATT que hace falta para leer el BMS. **Lo provee la capa ACL/L2CAP.**
 *
 * Esta interfaz es la frontera exacta entre lo que este archivo hace y lo que
 * todavia no existe. Hoy [com.nonosky.carsoc.hci.HciUsb] solo manda comandos
 * y lee eventos: **no usa los endpoints BULK**, o sea que no hay camino de datos
 * ACL. Sin ACL no hay L2CAP, sin L2CAP no hay ATT.
 *
 * Se define como interfaz en vez de esperar a que exista por dos razones:
 *
 *   1. Todo lo de encima —ATT, descubrimiento, CCCD, reensamblado, protocolo
 *      JBD— se puede escribir y **probar entero en la JVM** contra un BMS
 *      simulado. Cuando llegue el ACL, lo que se estrena es el ACL, no seis
 *      capas a la vez.
 *   2. Deja escrito, sin ambiguedad, que tiene que entregar quien la
 *      implemente: **PDU ATT completas**, no trozos de ACL.
 *
 * CONTRATO, y el primer punto es el que se rompe siempre:
 *
 *   - [recibir] devuelve **una PDU ATT completa**, ya sin cabecera L2CAP y ya
 *     reensamblada si venia partida en varios paquetes ACL. Con el paquete LE de
 *     27 bytes que declara este dongle, una trama L2CAP de 40 bytes sale en dos
 *     pedazos; si se entrega el primero como si fuera una PDU, el parseo lee un
 *     ATT truncado. Es literalmente el mismo fallo que ya hubo con los eventos
 *     HCI de 16 bytes. Hay un [com.nonosky.carsoc.hci.EnsambladorL2cap]
 *     escrito y probado para esto.
 *   - [enviar] acepta una PDU ATT y se ocupa de la cabecera L2CAP (CID 0x0004)
 *     y de fragmentar en ACL si no cabe.
 *   - Ninguno de los dos puede lanzar. Una excepcion que escapa de un hilo en
 *     Android mata el proceso entero, y esto corre en el hilo del vigilante.
 */
interface CanalGatt {

    /** Manda una PDU ATT. false = no se pudo escribir. */
    fun enviar(pdu: ByteArray): Boolean

    /** Espera una PDU ATT completa. null = no llego nada en el plazo. */
    fun recibir(timeoutMs: Int): ByteArray?

    val abierto: Boolean

    /** Cierra el enlace LE. Debe ser idempotente. */
    fun cerrar()
}

/**
 * Donde la capa ACL/L2CAP se enchufa cuando exista.
 *
 * Mientras [fabrica] valga null, [VigilanteBateria] sabe que el GATT no esta
 * cableado y lo DICE en el tablero, en vez de fingir que busca. Esa es toda la
 * diferencia entre un hueco explicado y un hueco misterioso.
 */
object CanalGattDisponible {
    /** Recibe la MAC del BMS y devuelve un canal abierto, o null. */
    @Volatile
    var fabrica: ((String) -> CanalGatt?)? = null

    fun hay(): Boolean = fabrica != null
}

/**
 * Lee un BMS JBD por GATT: negocia MTU, descubre, activa notificaciones y pide.
 *
 * ============================================================================
 * EL ORDEN IMPORTA Y NO ES NEGOCIABLE
 * ============================================================================
 *
 *   1. `Exchange MTU` — **primero de todo**. Por especificacion solo se puede
 *      intercambiar una vez por conexion, y hacerlo despues de empezar a
 *      descubrir es un error de protocolo.
 *   2. Descubrir servicios hasta encontrar el 0xFF00.
 *   3. Descubrir sus caracteristicas y elegir, POR SUS PROPIEDADES, cual
 *      notifica y cual se escribe.
 *   4. Encontrar el CCCD de la que notifica y escribirle **0x0001**.
 *   5. Solo entonces, escribir la peticion `DD A5 03 00 FF FD 77`.
 *
 * Saltarse el paso 4 es el error clasico: todo lo demas funciona, la peticion se
 * escribe sin error, y el BMS no manda un solo byte. Parece muerto y no lo esta.
 *
 * ============================================================================
 * POR QUE SE DESCUBRE EN VEZ DE CODIFICAR LOS UUID
 * ============================================================================
 * Lo que se sabe de este aparato:
 *
 *   - **MEDIDO**: el servicio es 0xFF00. Sale del anuncio real capturado por
 *     HCI (`03 02 00 FF` = lista de UUID de 16 bits con 0xFF00) y el dueño
 *     confirma que la app Xiaoxiang funciona con esta bateria.
 *   - **HIPOTESIS ALTA (no verificada en este aparato)**: la caracteristica de
 *     escritura es 0xFF02 y la de notificacion 0xFF01. Es lo que documentan los
 *     proyectos libres que hablan con BMS JBD, y es consistente entre ellos.
 *     Pero **nadie lo ha leido de esta bateria**, y hay modulos BLE JBD que
 *     usan otra pareja de caracteristicas dentro del mismo servicio 0xFF00.
 *
 * Por eso el codigo NO codifica handles ni exige esos dos UUID: enumera las
 * caracteristicas del servicio 0xFF00 y elige la que **declara notificar** y la
 * que **declara escribir**. Si resultan ser 0xFF01/0xFF02, perfecto y se anota
 * en la traza. Si son otras, funciona igual. Codificar el handle "porque
 * normalmente es el 0x000B" es exactamente el tipo de invento que este proyecto
 * evita en todas partes.
 *
 * Sin nada de Android a proposito: se prueba entero en la JVM contra un BMS
 * simulado, incluido el caso de la respuesta partida en dos notificaciones.
 */
class LectorBmsGatt(
    private val canal: CanalGatt,
    /** Plazo por PDU. Corto a proposito: un tablero no puede colgarse 30 s. */
    private val timeoutMs: Int = 2_000,
) {

    /** Lo que paso, paso a paso. Es lo que se sirve por `/bateria-gatt`. */
    val traza = mutableListOf<String>()

    private var mtu = Att.MTU_POR_DEFECTO
    private val ensamblador = EnsambladorBms()

    /** Notificaciones que llegaron mientras se esperaba otra cosa. */
    private val respuestasSueltas = mutableListOf<BmsJbd.Respuesta>()

    /** Los handles que hacen falta, una vez descubiertos. */
    data class Perfil(
        val servicio: Att.Servicio,
        val notificacion: Att.Caracteristica,
        val escritura: Att.Caracteristica,
        val handleCccd: Int,
    )

    /** El resultado de una ronda de lectura. */
    data class Lectura(
        val basico: BmsJbd.EstadoBasico? = null,
        val celdas: BmsJbd.VoltajesCelda? = null,
        /** Tramas validas de registros que no se interpretan. Se vuelcan. */
        val otras: List<BmsJbd.Respuesta> = emptyList(),
        val perfil: Perfil? = null,
        val mtu: Int = Att.MTU_POR_DEFECTO,
        val problemas: List<String> = emptyList(),
        val traza: List<String> = emptyList(),
    ) {
        /**
         * Hay un estado basico creible Y, si tambien llegaron las celdas, las
         * dos escalas cuadran entre si.
         *
         * Esta ultima condicion es la que impide pintar un voltaje de litio
         * equivocado: el registro 0x03 da el total en 10 mV y el 0x04 da cada
         * celda en 1 mV. Si los dos caminos no coinciden, una escala esta mal y
         * **no se pinta ninguno**.
         */
        fun utilizable(): Boolean {
            val b = basico ?: return false
            if (!b.creible()) return false
            val c = celdas ?: return true
            if (!c.creible()) return true // el 0x03 vale igual; el 0x04 se descarta
            return c.cuadraCon(b)
        }
    }

    /**
     * Una ronda completa: MTU, descubrimiento, CCCD, registro 0x03 y 0x04.
     *
     * Nunca lanza. Devuelve lo que consiguio y una lista de problemas con lo
     * que no. Un fallo a mitad de camino deja los campos anteriores rellenos:
     * si el 0x03 llego y el 0x04 no, el tablero puede pintar voltaje y SoC sin
     * las celdas, que es mejor que nada y esta dicho.
     */
    fun leerTodo(pedirCeldas: Boolean = true): Lectura {
        val problemas = mutableListOf<String>()

        if (!canal.abierto) {
            return Lectura(problemas = listOf("el canal ATT no esta abierto"), traza = traza.toList())
        }

        mtu = negociarMtu(problemas)

        val perfil = descubrir(problemas)
            ?: return Lectura(mtu = mtu, problemas = problemas, traza = traza.toList())

        if (!activarNotificaciones(perfil, problemas)) {
            // Se sigue igualmente: hay modulos que notifican sin CCCD. Pero
            // queda dicho, porque si luego no llega nada, ESTA es la causa.
            problemas += "no se pudo confirmar el CCCD: si no llega nada, es por esto"
        }

        // La PRIMERA peticion tras activar el CCCD se pierde en este BMS.
        //
        // Medido: en la primera lectura real el 0x03 —que se pedia primero, justo
        // despues del CCCD— no contesto NADA, y el 0x04 pedido a continuacion
        // llego entero a la primera. Si el CCCD estuviera mal no habria llegado
        // ninguno de los dos, asi que el CCCD no es la causa por mucho que sea
        // el sospechoso habitual: el modulo necesita un respiro entre habilitar
        // las notificaciones y atender la primera peticion.
        //
        // Un reintento lo cubre, y ademas cubre cualquier perdida suelta del
        // aire, que en Bluetooth pasa. Rendirse al primer silencio dejaria el
        // SoC vacio para siempre por un fallo que se arregla preguntando otra vez.
        var basico = pedir(perfil, BmsJbd.REG_BASICO, problemas) as? BmsJbd.EstadoBasico
        if (basico == null) {
            traza += "el 0x03 no contesto a la primera; reintentando"
            runCatching { Thread.sleep(REPOSO_TRAS_CCCD_MS) }
            basico = pedir(perfil, BmsJbd.REG_BASICO, problemas) as? BmsJbd.EstadoBasico
            if (basico != null) traza += "el 0x03 contesto al segundo intento"
        }
        if (basico == null) problemas += "el registro 0x03 no llego ni tras reintentar"

        val celdas = if (pedirCeldas) {
            pedir(perfil, BmsJbd.REG_CELDAS, problemas) as? BmsJbd.VoltajesCelda
        } else null

        basico?.let { b ->
            for (s in b.sospechas()) problemas += "0x03: $s"
            traza += "0x03: %.2f V, %d %%, %.2f A, %s, %d celdas".format(
                b.voltajeV, b.soc, b.corrienteA,
                b.temperaturasC.joinToString("/") { "%.1f C".format(it) },
                b.numeroCeldas,
            )
        }
        celdas?.let { c ->
            for (s in c.sospechas()) problemas += "0x04: $s"
            if (basico != null && !c.cuadraCon(basico)) {
                problemas += ("las dos escalas NO cuadran: 0x03 dice %.2f V y la suma de " +
                    "las celdas del 0x04 da %.2f V. No se pinta ninguna.")
                    .format(basico.voltajeV, c.sumaV)
            }
            traza += "0x04: %d celdas, suma %.2f V, desviacion %s".format(
                c.celdasMv.size, c.sumaV,
                c.desviacionV?.let { "%.3f V".format(it) } ?: "n/d",
            )
        }

        return Lectura(
            basico = basico,
            celdas = celdas,
            otras = respuestasSueltas.filter {
                it !is BmsJbd.EstadoBasico && it !is BmsJbd.VoltajesCelda
            },
            perfil = perfil,
            mtu = mtu,
            problemas = problemas,
            traza = traza.toList(),
        )
    }

    // ========================================================================
    // Paso 1: MTU
    // ========================================================================

    /**
     * Pide MTU y devuelve el acordado, que es el MINIMO de los dos.
     *
     * Si el par no contesta, la especificacion es clara: se queda el de
     * omision, 23. No es un fallo y no aborta nada — solo significa que las
     * respuestas van a llegar partidas, que es justo el caso que
     * [EnsambladorBms] existe para resolver.
     */
    private fun negociarMtu(problemas: MutableList<String>): Int {
        if (!canal.enviar(Att.peticionMtu())) {
            problemas += "no se pudo mandar Exchange MTU Request"
            traza += "MTU: fallo el envio, se sigue con ${Att.MTU_POR_DEFECTO}"
            return Att.MTU_POR_DEFECTO
        }
        val r = esperar(problemas) { it is Att.Pdu.Mtu || it is Att.Pdu.Error }
        return when (r) {
            is Att.Pdu.Mtu -> {
                val acordado = minOf(r.mtu, Att.MTU_DESEADO)
                traza += "MTU: el par ofrece ${r.mtu}, se usa $acordado " +
                    "(${Att.cargaMaxima(acordado)} bytes por notificacion)"
                acordado
            }
            is Att.Pdu.Error -> {
                // Muchos modulos baratos no soportan el intercambio. No es
                // grave: se sigue con 23.
                traza += "MTU: el par rechaza el intercambio (${r.describir()}), se usa " +
                    "${Att.MTU_POR_DEFECTO}"
                Att.MTU_POR_DEFECTO
            }
            else -> {
                traza += "MTU: sin respuesta, se usa ${Att.MTU_POR_DEFECTO} " +
                    "(la respuesta del 0x03 va a llegar en dos notificaciones)"
                Att.MTU_POR_DEFECTO
            }
        }
    }

    // ========================================================================
    // Paso 2 y 3: descubrimiento
    // ========================================================================

    /**
     * Encuentra el servicio 0xFF00 y elige sus caracteristicas por PROPIEDADES.
     *
     * Un descubrimiento de servicios no cabe en una respuesta: hay que
     * preguntar en bucle desde el handle siguiente al ultimo devuelto, hasta
     * que el par conteste `Error Response` con 0x0A (Attribute Not Found). Ese
     * error **no es un fallo**: es como termina el descubrimiento. Tratarlo
     * como error abortaria justo cuando ya se tiene todo.
     */
    fun descubrir(problemas: MutableList<String>): Perfil? {
        val servicios = mutableListOf<Att.Servicio>()
        var desde = Att.HANDLE_PRIMERO
        var vueltas = 0

        while (desde <= Att.HANDLE_ULTIMO && vueltas < MAX_VUELTAS) {
            vueltas++
            if (!canal.enviar(Att.peticionServicios(desde))) {
                problemas += "no se pudo mandar Read By Group Type Request"
                return null
            }
            val r = esperar(problemas) { it is Att.Pdu.Servicios || it is Att.Pdu.Error }
            if (r is Att.Pdu.Error) {
                if (!r.finDeDescubrimiento) problemas += "descubriendo servicios: ${r.describir()}"
                break
            }
            val lote = (r as? Att.Pdu.Servicios)?.lista
            if (lote.isNullOrEmpty()) {
                problemas += "el par dejo de contestar al descubrimiento de servicios"
                break
            }
            servicios += lote
            val ultimo = lote.maxOf { it.handleFin }
            // Sin esta guarda, un par que devuelva siempre el mismo handle deja
            // el bucle girando para siempre en el hilo del vigilante.
            if (ultimo <= desde - 1 || ultimo >= Att.HANDLE_ULTIMO) break
            desde = ultimo + 1
        }

        traza += "servicios: " + (if (servicios.isEmpty()) "ninguno" else
            servicios.joinToString(", ") { "${it.uuid}[0x%04X-0x%04X]".format(it.handleInicio, it.handleFin) })

        val objetivo = servicios.firstOrNull { it.uuid.corto == BateriaState.SERVICIO_JBD }
        if (objetivo == null) {
            problemas += "no aparece el servicio 0x%04X, que es el que anuncia este BMS"
                .format(BateriaState.SERVICIO_JBD)
            return null
        }

        // Caracteristicas del servicio, tambien en bucle.
        val cars = mutableListOf<Att.Caracteristica>()
        var d = objetivo.handleInicio
        vueltas = 0
        while (d <= objetivo.handleFin && vueltas < MAX_VUELTAS) {
            vueltas++
            if (!canal.enviar(Att.peticionPorTipo(d, objetivo.handleFin, Att.UUID_CARACTERISTICA))) {
                problemas += "no se pudo mandar Read By Type Request"
                return null
            }
            val r = esperar(problemas) {
                it is Att.Pdu.Caracteristicas || it is Att.Pdu.Atributos || it is Att.Pdu.Error
            }
            if (r is Att.Pdu.Error) {
                if (!r.finDeDescubrimiento) {
                    problemas += "descubriendo caracteristicas: ${r.describir()}"
                }
                break
            }
            val lote = (r as? Att.Pdu.Caracteristicas)?.lista
            if (lote.isNullOrEmpty()) break
            cars += lote
            val ultimo = lote.maxOf { it.handleDeclaracion }
            if (ultimo < d) break
            d = ultimo + 1
        }

        traza += "caracteristicas de ${objetivo.uuid}: " +
            (if (cars.isEmpty()) "ninguna" else cars.joinToString(" | ") { it.describir() })

        val notifica = cars.firstOrNull { it.notifica() }
            ?: cars.firstOrNull { it.indica() }
        val escribe = cars.firstOrNull { it.escribible() }

        if (notifica == null) {
            problemas += "ninguna caracteristica de 0x%04X declara notificar: sin eso el BMS no " +
                "puede contestar".format(BateriaState.SERVICIO_JBD)
            return null
        }
        if (escribe == null) {
            problemas += "ninguna caracteristica de 0x%04X declara escritura: sin eso no se le " +
                "puede preguntar".format(BateriaState.SERVICIO_JBD)
            return null
        }

        // La HIPOTESIS es que salen 0xFF01 y 0xFF02. Si sale otra cosa, no se
        // aborta: se ANOTA, porque es informacion nueva sobre el aparato.
        comprobarHipotesisUuid(notifica, escribe)

        val cccd = buscarCccd(objetivo, cars, notifica, problemas)
        if (cccd == null) {
            problemas += "no aparece el descriptor CCCD (0x2902) de ${notifica.uuid}: sin el, " +
                "el BMS no manda nada y parece muerto"
            return null
        }

        return Perfil(objetivo, notifica, escribe, cccd)
    }

    private fun comprobarHipotesisUuid(
        notifica: Att.Caracteristica,
        escribe: Att.Caracteristica,
    ) {
        val n = notifica.uuid.corto
        val e = escribe.uuid.corto
        if (n == UUID_NOTIFICA_ESPERADO && e == UUID_ESCRIBE_ESPERADO) {
            traza += "los UUID salen como se esperaba: notifica 0x%04X, escribe 0x%04X. " +
                "La hipotesis queda CONFIRMADA en este aparato."
                .format(UUID_NOTIFICA_ESPERADO, UUID_ESCRIBE_ESPERADO)
        } else {
            traza += ("OJO: se esperaba notificar en 0x%04X y escribir en 0x%04X, y este modulo " +
                "usa %s y %s. Funciona igual porque se descubre, pero ANOTALO: es un dato nuevo " +
                "del aparato.").format(
                UUID_NOTIFICA_ESPERADO, UUID_ESCRIBE_ESPERADO, notifica.uuid, escribe.uuid,
            )
        }
    }

    /**
     * Busca el CCCD de la caracteristica que notifica.
     *
     * Se busca por su UUID (0x2902) dentro del rango de handles que pertenece a
     * ESA caracteristica, y no se asume "el handle del valor mas uno". Ese
     * atajo funciona en la mayoria de los aparatos y falla en los que meten un
     * descriptor de presentacion en medio — y falla en silencio: la escritura
     * va a un handle valido pero equivocado, el par contesta OK, y no llega
     * ninguna notificacion. Un fallo asi cuesta un dia.
     *
     * El rango de una caracteristica va desde el handle de su valor + 1 hasta
     * el handle de la siguiente declaracion - 1, o hasta el final del servicio
     * si es la ultima.
     */
    private fun buscarCccd(
        servicio: Att.Servicio,
        todas: List<Att.Caracteristica>,
        cual: Att.Caracteristica,
        problemas: MutableList<String>,
    ): Int? {
        val siguiente = todas.map { it.handleDeclaracion }
            .filter { it > cual.handleDeclaracion }.minOrNull()
        val desde = cual.handleValor + 1
        val hasta = (siguiente?.minus(1) ?: servicio.handleFin)
        if (desde > hasta) {
            problemas += "la caracteristica ${cual.uuid} no tiene sitio para descriptores " +
                "(0x%04X..0x%04X)".format(desde, hasta)
            return null
        }

        if (!canal.enviar(Att.peticionPorTipo(desde, hasta, Att.UUID_CCCD))) {
            problemas += "no se pudo mandar la busqueda del CCCD"
            return null
        }
        val r = esperar(problemas) {
            it is Att.Pdu.Atributos || it is Att.Pdu.Caracteristicas || it is Att.Pdu.Error
        }
        val handle = when (r) {
            is Att.Pdu.Atributos -> r.lista.firstOrNull()?.first
            // Un CCCD mide 2 bytes de valor, asi que el parser no deberia
            // confundirlo con una declaracion; se contempla por si un par
            // devuelve un largo raro.
            is Att.Pdu.Caracteristicas -> r.lista.firstOrNull()?.handleDeclaracion
            else -> null
        }
        if (handle != null) {
            traza += "CCCD de ${cual.uuid} en 0x%04X (buscado por UUID 0x2902 en 0x%04X..0x%04X)"
                .format(handle, desde, hasta)
        }
        return handle
    }

    // ========================================================================
    // Paso 4: el CCCD. El paso que todo el mundo se salta.
    // ========================================================================

    /**
     * Escribe 0x0001 en el CCCD. **Sin esto el BMS no manda un solo byte.**
     *
     * Se usa escritura CON acuse (0x12) a proposito, aunque sea mas lenta: un
     * CCCD que no se activo es indistinguible de una bateria apagada, y el
     * acuse es lo unico que separa las dos cosas.
     */
    fun activarNotificaciones(perfil: Perfil, problemas: MutableList<String>): Boolean {
        val valor = if (perfil.notificacion.notifica()) Att.valorCccdNotificar()
        else Att.valorCccdIndicar()

        if (!canal.enviar(Att.escrituraConAcuse(perfil.handleCccd, valor))) {
            problemas += "no se pudo mandar la escritura del CCCD"
            return false
        }
        val r = esperar(problemas) { it is Att.Pdu.EscrituraConfirmada || it is Att.Pdu.Error }
        return when (r) {
            is Att.Pdu.EscrituraConfirmada -> {
                traza += "CCCD 0x%04X <- %02X%02X: confirmado. El BMS ya puede hablar."
                    .format(perfil.handleCccd, valor[1], valor[0])
                true
            }
            is Att.Pdu.Error -> {
                problemas += "el CCCD rechazo la escritura: ${r.describir()}"
                false
            }
            else -> {
                problemas += "el CCCD no confirmo la escritura en $timeoutMs ms"
                false
            }
        }
    }

    // ========================================================================
    // Paso 5: preguntar
    // ========================================================================

    /**
     * Escribe la peticion de un registro y espera su respuesta reensamblada.
     *
     * La respuesta llega por notificacion, y con MTU 23 llega **en dos**. Por
     * eso no se lee "una PDU y ya": se sigue leyendo hasta que
     * [EnsambladorBms] entrega una trama completa con checksum bueno, o hasta
     * que se agota el plazo.
     */
    fun pedir(
        perfil: Perfil,
        registro: Int,
        problemas: MutableList<String>,
    ): BmsJbd.Respuesta? {
        // Lo que quedara de una ronda anterior no se mezcla con esta: media
        // trama vieja pegada a una nueva puede llegar a pasar el checksum.
        ensamblador.reiniciar()

        val peticion = BmsJbd.peticion(registro)
        val pdu = if (perfil.escritura.conAcuse()) {
            Att.escrituraConAcuse(perfil.escritura.handleValor, peticion)
        } else {
            Att.escrituraSinAcuse(perfil.escritura.handleValor, peticion)
        }

        if (!canal.enviar(pdu)) {
            problemas += "no se pudo escribir la peticion del registro 0x%02X".format(registro)
            return null
        }
        traza += "peticion 0x%02X -> handle 0x%04X: %s".format(
            registro, perfil.escritura.handleValor,
            peticion.joinToString("") { "%02X".format(it) },
        )

        // Si la escritura fue con acuse hay que consumir el Write Response, o
        // se confundiria con la respuesta de la peticion siguiente. Por
        // especificacion no se puede tener dos peticiones ATT en vuelo.
        if (perfil.escritura.conAcuse()) {
            esperar(problemas) { it is Att.Pdu.EscrituraConfirmada || it is Att.Pdu.Error }
        }

        // Lo que ya hubiera llegado antes de pedir (el BMS de algunos modulos
        // empieza a soltar el 0x03 solo, en cuanto se activa el CCCD).
        respuestasSueltas.firstOrNull { it.registro == registro && it.aceptada }
            ?.let {
                traza += "el registro 0x%02X ya habia llegado sin pedirlo".format(registro)
                return it
            }

        val hasta = System.currentTimeMillis() + timeoutMs * 3L
        var trozos = 0
        while (System.currentTimeMillis() < hasta) {
            val crudo = canal.recibir(timeoutMs) ?: break
            val p = Att.interpretar(crudo) ?: continue
            when (p) {
                is Att.Pdu.Notificacion -> {
                    trozos++
                    for (r in ensamblador.alimentar(p.valor)) {
                        respuestasSueltas += r
                        if (r.registro == registro) {
                            traza += "registro 0x%02X reensamblado de %d notificacion(es)"
                                .format(registro, trozos)
                            if (!r.aceptada) {
                                problemas += "el BMS rechazo el registro 0x%02X (estado 0x%02X)"
                                    .format(registro, r.estado)
                            }
                            return r
                        }
                    }
                }
                is Att.Pdu.Indicacion -> {
                    // Una indicacion EXIGE confirmacion. Sin ella el par no
                    // manda la siguiente y la lectura se queda a medias para
                    // siempre.
                    trozos++
                    canal.enviar(Att.confirmacion())
                    for (r in ensamblador.alimentar(p.valor)) {
                        respuestasSueltas += r
                        if (r.registro == registro) return r
                    }
                }
                is Att.Pdu.Error -> problemas += "mientras se esperaba el 0x%02X: %s"
                    .format(registro, p.describir())
                else -> { /* PDU de otro asunto; no estorba */ }
            }
        }

        if (ensamblador.bytesPendientes > 0) {
            problemas += ("el registro 0x%02X se quedo a medias: %d bytes sin completar tras %d " +
                "notificacion(es). Es lo que pasa si el MTU es 23 y falta el segundo trozo.")
                .format(registro, ensamblador.bytesPendientes, trozos)
        }
        if (ensamblador.tramasChecksumMalo > 0) {
            problemas += "${ensamblador.tramasChecksumMalo} trama(s) con checksum malo, tiradas: " +
                ensamblador.rechazadas().joinToString(" ; ")
        }
        if (trozos == 0) {
            problemas += ("no llego ni una notificacion tras pedir el 0x%02X. La causa mas " +
                "probable, con diferencia, es el CCCD.").format(registro)
        }
        return null
    }

    // ========================================================================
    // Espera con enrutado
    // ========================================================================

    /**
     * Espera la PDU que cumpla [quiero], guardando por el camino lo que no sea.
     *
     * Hace falta enrutar y no simplemente "leer la siguiente": el BMS puede
     * estar notificando mientras se descubre, y una notificacion que llegue en
     * medio de un descubrimiento no puede tirarse ni confundirse con la
     * respuesta esperada.
     */
    private fun esperar(
        problemas: MutableList<String>,
        quiero: (Att.Pdu) -> Boolean,
    ): Att.Pdu? {
        val hasta = System.currentTimeMillis() + timeoutMs.toLong()
        while (System.currentTimeMillis() < hasta) {
            val crudo = canal.recibir(timeoutMs) ?: return null
            val p = Att.interpretar(crudo) ?: continue
            if (p is Att.Pdu.Notificacion) {
                respuestasSueltas += ensamblador.alimentar(p.valor)
                continue
            }
            if (p is Att.Pdu.Indicacion) {
                canal.enviar(Att.confirmacion())
                respuestasSueltas += ensamblador.alimentar(p.valor)
                continue
            }
            if (p is Att.Pdu.Desconocida) {
                problemas += "PDU ATT no reconocida, opcode 0x%02X: %s".format(
                    p.opcode, p.crudo.joinToString("") { "%02X".format(it) },
                )
                continue
            }
            if (quiero(p)) return p
        }
        return null
    }

    companion object {

        /**
         * Respiro entre activar el CCCD y reintentar la primera peticion.
         *
         * Medido: la primera peticion tras el CCCD se pierde en este BMS.
         */
        const val REPOSO_TRAS_CCCD_MS = 400L

        /**
         * HIPOTESIS ALTA, no verificada en ESTE aparato: los UUID que la
         * documentacion de terceros da para un BMS JBD. No se exigen —se
         * comprueban y se anota el resultado. Ver la cabecera de la clase.
         */
        const val UUID_NOTIFICA_ESPERADO = 0xFF01
        const val UUID_ESCRIBE_ESPERADO = 0xFF02

        /**
         * Tope de vueltas de un bucle de descubrimiento.
         *
         * Un par que conteste siempre el mismo handle dejaria el bucle girando
         * para siempre en el hilo del vigilante, y eso no se ve: el tablero
         * simplemente deja de refrescar la bateria sin que nada falle.
         */
        const val MAX_VUELTAS = 32
    }
}
