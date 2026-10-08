package com.nonosky.carsoc.hci

/**
 * Abrir un enlace LE hablando HCI crudo: el comando que lo pide, el evento que
 * lo confirma, y la cancelacion que hace falta cuando no contesta.
 *
 * ============================================================================
 * QUE ES VERIFICABLE Y QUE ES HIPOTESIS. Leelo antes de tocar nada.
 * ============================================================================
 *
 * VERIFICABLE — sale de la especificacion Bluetooth Core 4.0, Vol 2 Parte E,
 * seccion 7.8.12 (`LE Create Connection`) y 7.7.65.1 (`LE Connection
 * Complete`). No es una suposicion sobre este aparato: es el contrato del
 * transporte HCI, el mismo que ya cumplieron RESET, READ_BD_ADDR y
 * LE_SET_SCAN_ENABLE en este mismo dongle. Cualquiera puede releer la
 * especificacion y comprobar cada campo de aqui.
 *
 * VERIFICABLE tambien, y medido en este dongle:
 *   - HCI version 6 = Bluetooth 4.0. Por eso el evento que se espera es el
 *     subevento **0x01** (`LE Connection Complete`) y NO el 0x0A (`LE Enhanced
 *     Connection Complete`), que es de 4.2 y solo llega si se habilita a mano
 *     con `LE Set Event Mask`. Esperar el 0x0A en un chip 4.0 seria esperar un
 *     evento que nunca va a llegar.
 *   - `LE_READ_BUFFER_SIZE` devolvio paquete LE de 27 bytes y 15 buffers, o sea
 *     que el controlador SI acepta datos ACL LE. Lo que falta no es capacidad
 *     del chip: es que [HciUsb] todavia no usa los endpoints BULK.
 *
 * HIPOTESIS, y esta es la importante:
 *   - **Que el BMS acepte conexiones.** El anuncio capturado dice que es
 *     descubrible y que no soporta BR/EDR, pero el byte que dice si es
 *     CONECTABLE es el tipo de evento del informe de anuncio, y ese byte no se
 *     anoto en la captura. Ver [COMO_CONFIRMAR_LE] punto 1.
 *   - **Cual de las dos direcciones es la buena.** Ver [COMO_CONFIRMAR_LE]
 *     punto 2. El anuncio trae DOS direcciones distintas.
 *
 * ----------------------------------------------------------------------------
 * POR QUE LA DIRECCION DEL BMS ES PUBLICA (tipo 0x00) — deduccion verificable
 * ----------------------------------------------------------------------------
 * MAC vista: `A4:C1:38:CD:FA:C8`.
 *
 * En BLE el tipo de direccion no es opcional: si se manda 0x01 (aleatoria) para
 * una direccion publica, el controlador busca a un aparato que no existe y la
 * conexion caduca sin explicacion. Se puede deducir del propio valor:
 *
 *   - Una direccion **aleatoria estatica** tiene los dos bits mas altos del
 *     octeto mas significativo a `11` (o sea, primer octeto >= 0xC0).
 *   - Una **privada resoluble** los tiene a `01` (0x40..0x7F).
 *   - Una **privada no resoluble** los tiene a `00` (0x00..0x3F).
 *
 * 0xA4 = `1010 0100`: sus dos bits altos son `10`, que **no corresponde a
 * ningun tipo de direccion aleatoria valida**. Ademas `A4:C1:38` es un OUI
 * registrado (Telink, el chip BLE que llevan estos modulos). Las dos cosas
 * apuntan al mismo sitio: es una direccion PUBLICA, tipo 0x00.
 *
 * Aun asi el informe de anuncio trae el tipo de direccion en un byte y eso es
 * medirlo en vez de deducirlo — ver [COMO_CONFIRMAR_LE] punto 1.
 *
 * ----------------------------------------------------------------------------
 * Sin nada de Android a proposito: asi se prueba entero en la JVM, igual que
 * TpmsDecoder y PidDecoder. El que habla con el dongle es [EnlaceLeHci].
 */
object ConexionLe {

    // ========================================================================
    // Opcodes y codigos de evento. opcode = (OGF shl 10) or OCF.
    // ========================================================================

    /** OGF 0x08 (LE) OCF 0x0D. Pide abrir un enlace LE como iniciador. */
    const val CMD_LE_CREATE_CONNECTION = 0x200D

    /**
     * OGF 0x08 OCF 0x0E. Cancela el intento de conexion en curso.
     *
     * No es un lujo: mientras hay un `LE Create Connection` pendiente el
     * controlador **rechaza** otro con "Command Disallowed" (0x0C). Un intento
     * colgado bloquea todos los siguientes, y como aqui el barrido y la
     * conexion comparten el mismo dongle, un intento colgado tambien deja al
     * vigilante sin poder barrer. Se cancela SIEMPRE que no llegue el evento.
     */
    const val CMD_LE_CREATE_CONNECTION_CANCEL = 0x200E

    /** OGF 0x01 (Link Control) OCF 0x06. Cierra un enlace ya abierto. */
    const val CMD_DISCONNECT = 0x0406

    const val EVT_DISCONNECTION_COMPLETE = 0x05

    /** Subevento LE Meta de 4.0. El que este dongle (HCI v6) puede dar. */
    const val SUBEVT_LE_CONNECTION_COMPLETE = 0x01

    /**
     * Subevento LE Meta de 4.2. Este dongle NO lo va a emitir, pero se
     * reconoce igual: si algun dia se cambia de dongle, el parser no debe
     * quedarse mudo esperando el 0x01.
     */
    const val SUBEVT_LE_ENHANCED_CONNECTION_COMPLETE = 0x0A

    /** Motivo de desconexion "terminado por el usuario remoto/local". */
    const val RAZON_TERMINADO_POR_USUARIO = 0x13

    // ========================================================================
    // Unidades. Todas de la especificacion; equivocarse aqui es pedir
    // intervalos absurdos y que el controlador rechace el comando con
    // "Invalid HCI Command Parameters" (0x12) sin decir cual.
    // ========================================================================

    /** Intervalo y ventana de barrido del iniciador: 0.625 ms por unidad. */
    const val MS_POR_UNIDAD_BARRIDO = 0.625

    /** Intervalo de conexion: 1.25 ms por unidad. */
    const val MS_POR_UNIDAD_INTERVALO = 1.25

    /** Timeout de supervision: 10 ms por unidad. */
    const val MS_POR_UNIDAD_TIMEOUT = 10.0

    /** Longitud del evento de conexion: 0.625 ms por unidad. */
    const val MS_POR_UNIDAD_CE = 0.625

    // Rangos legales, tal cual los fija 7.8.12.
    val RANGO_BARRIDO = 0x0004..0x4000          // 2.5 ms .. 10.24 s
    val RANGO_INTERVALO_CONEXION = 0x0006..0x0C80  // 7.5 ms .. 4 s
    val RANGO_LATENCIA = 0x0000..0x01F3         // 0 .. 499 eventos saltables
    val RANGO_TIMEOUT = 0x000A..0x0C80          // 100 ms .. 32 s

    const val DIRECCION_PUBLICA = 0x00
    const val DIRECCION_ALEATORIA = 0x01

    /** Se usa la direccion del par que va en el comando. Es lo que queremos. */
    const val FILTRO_USAR_DIRECCION_DEL_PAR = 0x00

    /**
     * Se usa la lista blanca del controlador y se IGNORA la direccion del
     * comando. No se usa aqui: la lista blanca hay que poblarla antes con
     * `LE Add Device To White List`, y para un solo aparato conocido no aporta
     * nada. Se documenta para que nadie ponga 0x01 pensando que "filtra mejor"
     * y acabe con un comando que ignora la MAC que le acaba de dar.
     */
    const val FILTRO_USAR_LISTA_BLANCA = 0x01

    /** El comando LE Create Connection lleva exactamente 25 bytes de parametros. */
    const val LARGO_PARAMETROS = 25

    /** El evento LE Connection Complete lleva 19 bytes de parametros. */
    const val LARGO_PARAMETROS_CONEXION_COMPLETA = 19

    /**
     * Handle de conexion: 12 bits significativos.
     *
     * Los 4 bits altos son reservados y **pueden venir con basura**. Usar el
     * valor sin enmascarar produciria un handle que el controlador rechaza en
     * cada paquete ACL posterior, con un fallo que parece del ACL y en realidad
     * es de aqui.
     */
    const val MASCARA_HANDLE = 0x0FFF

    // ========================================================================
    // Los parametros del comando, uno por uno.
    // ========================================================================

    /**
     * Los 12 campos de `LE Create Connection`, con los valores elegidos para
     * hablar con un BMS y el motivo de cada eleccion.
     *
     * Los valores por omision NO son "los de un ejemplo de internet": cada uno
     * se justifica abajo contra lo que este aparato concreto tiene que hacer.
     */
    data class Parametros(
        /**
         * **1. Intervalo de barrido del iniciador** (2 bytes, 0.625 ms/unidad).
         *
         * Mientras intenta conectar, el controlador barre esperando oir un
         * anuncio conectable del par. Este es cada cuanto empieza una ventana.
         *
         * 0x0060 = 96 -> 60 ms. Con intervalo == ventana el barrido es
         * CONTINUO, que es lo que conviene: el dongle no tiene nada mejor que
         * hacer y asi se engancha al primer anuncio que pase, en vez de
         * perderse uno de cada dos.
         */
        val intervaloBarrido: Int = 0x0060,

        /**
         * **2. Ventana de barrido** (2 bytes, 0.625 ms/unidad).
         *
         * Cuanto de cada intervalo se escucha de verdad. Tiene que ser
         * <= intervalo, o el controlador rechaza el comando.
         *
         * Igual al intervalo: escucha continua. Ver arriba.
         */
        val ventanaBarrido: Int = 0x0060,

        /**
         * **3. Politica de filtro del iniciador** (1 byte).
         *
         * 0x00 = se usa [direccionPar] / [tipoDireccionPar] y se ignora la
         *        lista blanca. Es lo que hace falta: sabemos a quien queremos.
         * 0x01 = se usa la lista blanca y se IGNORAN los campos de direccion.
         *
         * Se fija a 0x00 y no se deja tocar por error: con 0x01 el comando
         * saldria bien formado, no daria ningun error, y se conectaria a
         * cualquier cosa de la lista blanca (que aqui esta vacia, asi que a
         * nada) mientras la MAC del BMS se ignora en silencio.
         */
        val politicaFiltro: Int = FILTRO_USAR_DIRECCION_DEL_PAR,

        /**
         * **4. Tipo de direccion del par** (1 byte). 0x00 publica, 0x01 aleatoria.
         *
         * 0x00 para este BMS. La deduccion completa esta en la cabecera de
         * [ConexionLe]: 0xA4 no encaja en ningun patron de direccion aleatoria
         * y A4:C1:38 es un OUI registrado.
         */
        val tipoDireccionPar: Int = DIRECCION_PUBLICA,

        /**
         * **5. Direccion del par** (6 bytes, **LSB primero**).
         *
         * El orden es la trampa clasica de HCI: A4:C1:38:CD:FA:C8 va al aire
         * como `C8 FA CD 38 C1 A4`. Al reves, el controlador busca al aparato
         * `C8:FA:CD:38:C1:A4`, que no existe, y el sintoma es un timeout
         * silencioso indistinguible de "la bateria esta apagada".
         * Usa [macALittleEndian] y no lo escribas a mano.
         */
        val direccionPar: ByteArray,

        /**
         * **6. Tipo de direccion propia** (1 byte). 0x00 publica, 0x01 aleatoria.
         *
         * 0x00: el dongle tiene direccion publica de fabrica —
         * `21:49:86:00:69:3D`, leida en vivo con READ_BD_ADDR. Pedir 0x01 sin
         * haber fijado antes una direccion aleatoria con
         * `LE Set Random Address` hace que el controlador use ceros o rechace
         * el comando, segun el chip.
         */
        val tipoDireccionPropia: Int = DIRECCION_PUBLICA,

        /**
         * **7. Intervalo de conexion minimo** (2 bytes, 1.25 ms/unidad).
         *
         * 0x0018 = 24 -> 30 ms.
         */
        val intervaloConexionMin: Int = 0x0018,

        /**
         * **8. Intervalo de conexion maximo** (2 bytes, 1.25 ms/unidad).
         *
         * 0x0028 = 40 -> 50 ms.
         *
         * POR QUE 30-50 ms y no algo mas lento, que gastaria menos: con el MTU
         * por omision de 23 bytes, una respuesta del BMS de 34 bytes llega
         * partida en DOS notificaciones, y cada notificacion necesita su propio
         * evento de conexion. A 50 ms eso son ~100 ms por lectura, que para un
         * tablero es inmediato. A 1 s por intervalo la misma lectura tardaria
         * 2 s y el numero de la pantalla iria siempre por detras.
         *
         * POR QUE no mas rapido: por debajo de ~15 ms el chip barato del BMS
         * suele negociar hacia arriba de todas formas, y ademas el mismo dongle
         * tiene que seguir barriendo para el resto del tablero.
         *
         * Ojo: esto es una PETICION. El par puede contestar con otro intervalo,
         * y el que vale es el que traiga [ConexionCompleta.intervaloConexion].
         */
        val intervaloConexionMax: Int = 0x0028,

        /**
         * **9. Latencia de conexion** (2 bytes, en numero de eventos).
         *
         * Cuantos eventos de conexion puede SALTARSE el esclavo si no tiene
         * nada que decir. Ahorra bateria del BMS a cambio de retraso.
         *
         * 0: aqui el retraso es justo lo que no queremos. El BMS solo habla
         * cuando se le pregunta, asi que una latencia alta retrasaria
         * exactamente la respuesta que estamos esperando.
         */
        val latencia: Int = 0x0000,

        /**
         * **10. Timeout de supervision** (2 bytes, 10 ms/unidad).
         *
         * Cuanto se aguanta sin oir al par antes de declarar el enlace muerto.
         *
         * 0x01F4 = 500 -> 5000 ms. Cinco segundos: suficiente para que un
         * paquete perdido no tire la conexion, y lo bastante corto para que
         * una bateria que se apaga no deje al tablero pintando un voltaje viejo
         * durante medio minuto.
         *
         * La especificacion impone una relacion, no solo un rango:
         *     timeout_ms > (1 + latencia) * intervaloMax_ms * 2
         * Con los valores de arriba: 5000 > 1 * 50 * 2 = 100. Holgado.
         * [validar] comprueba esto, porque si no cuadra el controlador rechaza
         * el comando con 0x12 y no dice cual de los cuatro campos falla.
         */
        val timeoutSupervision: Int = 0x01F4,

        /**
         * **11. Longitud minima del evento de conexion** (2 bytes, 0.625 ms/u).
         *
         * Cuanto tiempo del evento se reserva para este enlace. 0 = sin
         * preferencia, que el controlador decida. Es lo correcto cuando no hay
         * varios enlaces peleandose por el aire, y muchos controladores lo
         * ignoran de todas formas.
         */
        val ceLongitudMin: Int = 0x0000,

        /** **12. Longitud maxima del evento de conexion.** 0 = sin preferencia. */
        val ceLongitudMax: Int = 0x0000,
    ) {

        /**
         * Los problemas que el controlador rechazaria, dichos en castellano.
         *
         * Devuelve lista en vez de lanzar por la regla del proyecto: una
         * excepcion que escapa de un hilo en Android mata el proceso, y esto se
         * llama desde el hilo del vigilante.
         */
        fun validar(): List<String> {
            val p = mutableListOf<String>()
            if (intervaloBarrido !in RANGO_BARRIDO) {
                p += "intervalo de barrido 0x%04X fuera de 0x0004..0x4000".format(intervaloBarrido)
            }
            if (ventanaBarrido !in RANGO_BARRIDO) {
                p += "ventana de barrido 0x%04X fuera de 0x0004..0x4000".format(ventanaBarrido)
            }
            if (ventanaBarrido > intervaloBarrido) {
                p += "la ventana de barrido (0x%04X) no puede superar el intervalo (0x%04X)"
                    .format(ventanaBarrido, intervaloBarrido)
            }
            if (politicaFiltro != FILTRO_USAR_DIRECCION_DEL_PAR &&
                politicaFiltro != FILTRO_USAR_LISTA_BLANCA
            ) {
                p += "politica de filtro $politicaFiltro no es 0x00 ni 0x01"
            }
            if (tipoDireccionPar != DIRECCION_PUBLICA && tipoDireccionPar != DIRECCION_ALEATORIA) {
                p += "tipo de direccion del par $tipoDireccionPar no es 0x00 ni 0x01"
            }
            if (tipoDireccionPropia != DIRECCION_PUBLICA &&
                tipoDireccionPropia != DIRECCION_ALEATORIA
            ) {
                p += "tipo de direccion propia $tipoDireccionPropia no es 0x00 ni 0x01"
            }
            if (direccionPar.size != 6) {
                p += "la direccion del par mide ${direccionPar.size} bytes y deben ser 6"
            }
            if (intervaloConexionMin !in RANGO_INTERVALO_CONEXION) {
                p += "intervalo minimo 0x%04X fuera de 0x0006..0x0C80".format(intervaloConexionMin)
            }
            if (intervaloConexionMax !in RANGO_INTERVALO_CONEXION) {
                p += "intervalo maximo 0x%04X fuera de 0x0006..0x0C80".format(intervaloConexionMax)
            }
            if (intervaloConexionMin > intervaloConexionMax) {
                p += "el intervalo minimo (0x%04X) supera al maximo (0x%04X)"
                    .format(intervaloConexionMin, intervaloConexionMax)
            }
            if (latencia !in RANGO_LATENCIA) {
                p += "latencia $latencia fuera de 0..499"
            }
            if (timeoutSupervision !in RANGO_TIMEOUT) {
                p += "timeout de supervision 0x%04X fuera de 0x000A..0x0C80".format(timeoutSupervision)
            }
            // La relacion que casi nadie comprueba y que el controlador castiga
            // con un 0x12 sin explicacion.
            val timeoutMs = timeoutSupervision * MS_POR_UNIDAD_TIMEOUT
            val minimoMs = (1 + latencia) * intervaloConexionMax * MS_POR_UNIDAD_INTERVALO * 2
            if (timeoutMs <= minimoMs) {
                p += ("timeout de supervision %.0f ms no supera (1+latencia)*intervaloMax*2 = %.0f ms")
                    .format(timeoutMs, minimoMs)
            }
            if (ceLongitudMin > ceLongitudMax) {
                p += "longitud de evento minima 0x%04X supera la maxima 0x%04X"
                    .format(ceLongitudMin, ceLongitudMax)
            }
            return p
        }

        /**
         * Los 25 bytes del comando, en el orden y el endianness de 7.8.12.
         *
         * Todos los campos de 16 bits van **LSB primero**. Es el mismo orden
         * que ya usa [HciUsb.mandarComando] para el opcode, asi que si algo se
         * ve del reves aqui, se ve del reves en todo el transporte.
         */
        fun codificar(): ByteArray {
            val b = ByteArray(LARGO_PARAMETROS)
            var i = 0
            i = le16(b, i, intervaloBarrido)
            i = le16(b, i, ventanaBarrido)
            b[i++] = politicaFiltro.toByte()
            b[i++] = tipoDireccionPar.toByte()
            // Se copia solo lo que quepa: validar() ya avisa si no mide 6, y
            // aqui no se puede lanzar (hilo de Android).
            for (k in 0 until 6) {
                b[i++] = if (k < direccionPar.size) direccionPar[k] else 0
            }
            b[i++] = tipoDireccionPropia.toByte()
            i = le16(b, i, intervaloConexionMin)
            i = le16(b, i, intervaloConexionMax)
            i = le16(b, i, latencia)
            i = le16(b, i, timeoutSupervision)
            i = le16(b, i, ceLongitudMin)
            le16(b, i, ceLongitudMax)
            return b
        }

        /** Los mismos numeros en milisegundos, para poder leer un log. */
        fun describir(): String =
            "barrido %.1f/%.1f ms, filtro=0x%02X, par=%s tipo=%d, propia tipo=%d, ".format(
                intervaloBarrido * MS_POR_UNIDAD_BARRIDO,
                ventanaBarrido * MS_POR_UNIDAD_BARRIDO,
                politicaFiltro,
                macDesdeLittleEndian(direccionPar),
                tipoDireccionPar,
                tipoDireccionPropia,
            ) + "conexion %.2f-%.2f ms, latencia=%d, supervision %.0f ms, CE %.2f-%.2f ms".format(
                intervaloConexionMin * MS_POR_UNIDAD_INTERVALO,
                intervaloConexionMax * MS_POR_UNIDAD_INTERVALO,
                latencia,
                timeoutSupervision * MS_POR_UNIDAD_TIMEOUT,
                ceLongitudMin * MS_POR_UNIDAD_CE,
                ceLongitudMax * MS_POR_UNIDAD_CE,
            )

        // data class con ByteArray: equals/hashCode a mano o dos parametros
        // iguales no se compararian iguales. Importa porque las pruebas
        // comparan Parametros.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Parametros) return false
            return intervaloBarrido == other.intervaloBarrido &&
                ventanaBarrido == other.ventanaBarrido &&
                politicaFiltro == other.politicaFiltro &&
                tipoDireccionPar == other.tipoDireccionPar &&
                direccionPar.contentEquals(other.direccionPar) &&
                tipoDireccionPropia == other.tipoDireccionPropia &&
                intervaloConexionMin == other.intervaloConexionMin &&
                intervaloConexionMax == other.intervaloConexionMax &&
                latencia == other.latencia &&
                timeoutSupervision == other.timeoutSupervision &&
                ceLongitudMin == other.ceLongitudMin &&
                ceLongitudMax == other.ceLongitudMax
        }

        override fun hashCode(): Int =
            (((((intervaloBarrido * 31 + ventanaBarrido) * 31 + politicaFiltro) * 31 +
                tipoDireccionPar) * 31 + direccionPar.contentHashCode()) * 31 +
                intervaloConexionMax) * 31 + timeoutSupervision
    }

    /** Atajo: los parametros de arriba apuntando a una MAC en texto. */
    fun paraMac(mac: String): Parametros = Parametros(direccionPar = macALittleEndian(mac))

    // ========================================================================
    // El evento que trae el handle. Sin esto no hay ACL, ni L2CAP, ni ATT.
    // ========================================================================

    /**
     * `LE Connection Complete` desarmado.
     *
     * Formato completo del evento: `3E 13 01 <19 bytes de parametros>`.
     * Los parametros, en orden: estado(1), handle(2 LE), rol(1),
     * tipoDireccionPar(1), direccionPar(6 LE), intervalo(2 LE), latencia(2 LE),
     * timeout(2 LE), precisionReloj(1).
     */
    data class ConexionCompleta(
        val estado: Int,
        /** Ya enmascarado a 12 bits. Es LO QUE SE USA para todo lo demas. */
        val handle: Int,
        val rol: Int,
        val tipoDireccionPar: Int,
        val macPar: String,
        val intervaloConexion: Int,
        val latencia: Int,
        val timeoutSupervision: Int,
        val precisionReloj: Int,
    ) {
        val exito: Boolean get() = estado == 0x00

        /** El intervalo que el par ACEPTO, no el que se pidio. */
        val intervaloMs: Double get() = intervaloConexion * MS_POR_UNIDAD_INTERVALO
        val timeoutMs: Double get() = timeoutSupervision * MS_POR_UNIDAD_TIMEOUT

        /** 0x00 = somos el maestro (central). Es lo que debe salir aqui. */
        val somosMaestro: Boolean get() = rol == 0x00

        fun describir(): String =
            if (!exito) {
                "FALLO estado=0x%02X (%s)".format(estado, nombreEstado(estado))
            } else {
                ("handle=0x%04X rol=%d (%s) par=%s tipo=%d " +
                    "intervalo=%.2f ms latencia=%d supervision=%.0f ms").format(
                    handle, rol, if (somosMaestro) "maestro" else "esclavo",
                    macPar, tipoDireccionPar, intervaloMs, latencia, timeoutMs,
                )
            }
    }

    /**
     * Saca la [ConexionCompleta] de un evento HCI, o null si no es ese evento.
     *
     * Nunca lanza: le puede entrar cualquier cosa que salga del endpoint de
     * interrupcion, incluido un evento truncado por el reensamblado de 16 bytes
     * de [HciUsb.leerEvento].
     */
    fun interpretarConexionCompleta(evento: ByteArray?): ConexionCompleta? {
        if (evento == null || evento.size < 3) return null
        if (u(evento, 0) != HciUsb.EVT_LE_META) return null
        val sub = u(evento, 2)
        if (sub != SUBEVT_LE_CONNECTION_COMPLETE &&
            sub != SUBEVT_LE_ENHANCED_CONNECTION_COMPLETE
        ) return null

        // 3 de cabecera (codigo, largo, subevento) + 19 de parametros.
        // Con el 0x0A de 4.2 hay 12 bytes mas (dos direcciones locales
        // resolubles) DESPUES de la direccion del par, asi que todo lo que
        // leemos aqui cae en el mismo sitio en los dos subeventos... menos los
        // cuatro ultimos campos. Por eso el 0x0A se acepta pero solo se
        // garantiza el handle y el estado: es el caso "otro dongle", no el
        // nuestro, y prometer mas seria inventar.
        if (evento.size < 3 + 10) return null

        val estado = u(evento, 3)
        val handle = (u(evento, 4) or (u(evento, 5) shl 8)) and MASCARA_HANDLE
        val rol = u(evento, 6)
        val tipoDir = u(evento, 7)
        val mac = macDesdeLittleEndian(evento.copyOfRange(8, 14))

        val mejorado = sub == SUBEVT_LE_ENHANCED_CONNECTION_COMPLETE
        val base = if (mejorado) 14 + 12 else 14
        val hayCola = evento.size >= base + 7
        return ConexionCompleta(
            estado = estado,
            handle = handle,
            rol = rol,
            tipoDireccionPar = tipoDir,
            macPar = mac,
            intervaloConexion = if (hayCola) u(evento, base) or (u(evento, base + 1) shl 8) else 0,
            latencia = if (hayCola) u(evento, base + 2) or (u(evento, base + 3) shl 8) else 0,
            timeoutSupervision = if (hayCola) u(evento, base + 4) or (u(evento, base + 5) shl 8) else 0,
            precisionReloj = if (hayCola) u(evento, base + 6) else -1,
        )
    }

    /**
     * `Command Status` de un opcode concreto, o null.
     *
     * OJO, y es el error tipico: `LE Create Connection` contesta con **Command
     * Status** (0x0F), NO con Command Complete (0x0E). Quien espere un 0x0E se
     * queda esperando para siempre y concluye que el dongle no soporta LE.
     * Formato: `0F 04 <estado> <numCmd> <opcodeLo> <opcodeHi>`.
     */
    fun interpretarCommandStatus(evento: ByteArray?, opcodeEsperado: Int): Int? {
        if (evento == null || evento.size < 6) return null
        if (u(evento, 0) != HciUsb.EVT_COMMAND_STATUS) return null
        val opcode = u(evento, 4) or (u(evento, 5) shl 8)
        if (opcode != opcodeEsperado) return null
        return u(evento, 2)
    }

    /**
     * `Command Complete` de un opcode concreto: devuelve el estado, o null.
     *
     * `LE Create Connection Cancel` si contesta con Command Complete, al
     * contrario que el comando que cancela. No son simetricos y hay que
     * tratarlos distinto.
     * Formato: `0E <largo> <numCmd> <opcodeLo> <opcodeHi> <estado> [...]`.
     */
    fun interpretarCommandComplete(evento: ByteArray?, opcodeEsperado: Int): Int? {
        if (evento == null || evento.size < 6) return null
        if (u(evento, 0) != HciUsb.EVT_COMMAND_COMPLETE) return null
        val opcode = u(evento, 3) or (u(evento, 4) shl 8)
        if (opcode != opcodeEsperado) return null
        return u(evento, 5)
    }

    /** `Disconnection Complete`: `05 04 <estado> <handle 2 LE> <razon>`. */
    data class Desconexion(val estado: Int, val handle: Int, val razon: Int)

    fun interpretarDesconexion(evento: ByteArray?): Desconexion? {
        if (evento == null || evento.size < 7) return null
        if (u(evento, 0) != EVT_DISCONNECTION_COMPLETE) return null
        return Desconexion(
            estado = u(evento, 2),
            handle = (u(evento, 3) or (u(evento, 4) shl 8)) and MASCARA_HANDLE,
            razon = u(evento, 5),
        )
    }

    /** Parametros de `Disconnect`: handle (2 LE) + razon. */
    fun parametrosDesconectar(handle: Int, razon: Int = RAZON_TERMINADO_POR_USUARIO): ByteArray =
        byteArrayOf(
            (handle and 0xFF).toByte(),
            ((handle shr 8) and 0x0F).toByte(),
            razon.toByte(),
        )

    // ========================================================================
    // Direcciones. El endianness de HCI es donde se pierde media tarde.
    // ========================================================================

    /**
     * "A4:C1:38:CD:FA:C8" -> `C8 FA CD 38 C1 A4`.
     *
     * Tolerante con el formato porque la MAC llega de un parametro HTTP escrito
     * a mano. Si no salen 6 bytes devuelve un arreglo del tamaño que salga, y
     * [Parametros.validar] lo denuncia — en vez de lanzar en un hilo de fondo.
     */
    fun macALittleEndian(mac: String?): ByteArray {
        if (mac.isNullOrBlank()) return ByteArray(0)
        val limpio = mac.uppercase().filter { it.isDigit() || it in 'A'..'F' }
        if (limpio.length != 12) return ByteArray(0)
        val bytes = ByteArray(6) { limpio.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return bytes.reversedArray()
    }

    /** `C8 FA CD 38 C1 A4` -> "A4:C1:38:CD:FA:C8". */
    fun macDesdeLittleEndian(b: ByteArray): String {
        if (b.size < 6) return "??"
        return (0 until 6).map { b[it] }.reversed()
            .joinToString(":") { "%02X".format(it) }
    }

    /** Los codigos de error que de verdad salen al conectar por LE. */
    fun nombreEstado(estado: Int): String = when (estado) {
        0x00 -> "exito"
        0x02 -> "handle de conexion desconocido (es lo que devuelve una cancelacion)"
        0x04 -> "fallo de hardware"
        0x08 -> "el enlace caduco antes de establecerse"
        0x0C -> "comando no permitido (¿hay otro intento colgado sin cancelar?)"
        0x12 -> "parametros de comando invalidos"
        0x1F -> "error no especificado"
        0x3E -> "no se pudo establecer la conexion (el par no contesto)"
        else -> "codigo 0x%02X".format(estado)
    }

    // --- Ayudantes ----------------------------------------------------------

    private fun u(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    private fun le16(b: ByteArray, i: Int, v: Int): Int {
        b[i] = (v and 0xFF).toByte()
        b[i + 1] = ((v shr 8) and 0xFF).toByte()
        return i + 2
    }
}

/**
 * Lo que hace falta capturar para dejar de suponer sobre el enlace LE.
 *
 * Mismo criterio que [com.nonosky.carsoc.tpms.COMO_CONFIRMAR]: va en el
 * codigo y no en un documento aparte porque el documento se pierde y el codigo
 * se lee.
 */
val COMO_CONFIRMAR_LE: List<String> = listOf(
    "1. ¿EL BMS ES CONECTABLE? Repite el barrido con /hci-ble?crudo=1 y mira el " +
        "informe de anuncio ENTERO, no solo los datos. Tras `3E <len> 02 <num>` " +
        "el primer byte es el TIPO DE EVENTO: 0x00 = ADV_IND (conectable, es lo " +
        "que hace falta), 0x01 = ADV_DIRECT_IND, 0x02 = ADV_SCAN_IND (NO " +
        "conectable), 0x03 = ADV_NONCONN_IND (NO conectable). Si sale 0x02 o " +
        "0x03, LE Create Connection va a caducar siempre y no es culpa del " +
        "codigo. El SIGUIENTE byte es el tipo de direccion: 0x00 confirma que " +
        "es publica, que es lo que se deduce del OUI Telink.",

    "2. ¿CUAL DE LAS DOS DIRECCIONES? Un BMS Telink puede anunciar DOS: la " +
        "del informe, y otra metida en el campo 0xFF (datos de fabricante), " +
        "escrita al reves y distinta en los tres ultimos bytes. No se sabe por " +
        "que. Si la conexion a la del informe caduca una y otra vez con 0x3E, " +
        "prueba /le-conectar?mac=<la del campo 0xFF> antes de dar por roto el " +
        "camino.",

    "3. EL MTU DE VERDAD. Manda Exchange MTU Request y anota el MTU que " +
        "contesta el BMS. Si contesta 23 (o no contesta), las respuestas de 34 " +
        "bytes llegan en DOS notificaciones y el reensamblado de BmsJbd es " +
        "obligatorio. Si contesta >= 37, llegan de una y el reensamblado no se " +
        "ejercita nunca en el carro — razon de mas para que lo cubran las " +
        "pruebas y no el azar.",

    "4. LOS HANDLES REALES. Vuelca el descubrimiento completo (servicios y " +
        "caracteristicas de 0xFF00) y ANOTALO aqui. Hoy el codigo los descubre " +
        "en cada conexion, que es lo correcto, pero tener los numeros escritos " +
        "permite ver de un golpe si el modulo cambio de firmware.",

    "5. LA ESCALA DE LA CORRIENTE. Es el unico campo del registro 0x03 que la " +
        "trama de referencia NO puede confirmar, porque en ella la corriente " +
        "vale 0. Con el carro apagado y una carga conocida (unos faros, un " +
        "ventilador) mide los amperios con un gancho amperimetrico y comparalo " +
        "con el crudo. Y anota el SIGNO: se supone negativo al descargar.",

    "6. QUE SIGNIFICA CADA BIT DE PROTECCIONES. Los nombres que hay en " +
        "BmsJbd.PROTECCIONES vienen de documentacion de terceros, NO de este " +
        "aparato. Para verlos moverse hay que provocar cada aviso, y eso en una " +
        "bateria de litio se hace con cabeza: la unica prueba razonable sin " +
        "riesgo es la de sobretemperatura de carga con el carro al sol. El " +
        "resto se deja como es hoy: el mapa de bits se vuelca CRUDO y ademas " +
        "se nombra, dejando claro que el nombre es prestado.",
)
