package com.nonosky.carsoc.bateria

/**
 * Protocolo JBD / Xiaoxiang: peticiones al BMS y decodificacion de respuestas.
 *
 * ============================================================================
 * LEE ESTO ANTES DE TOCAR UNA SOLA ESCALA
 * ============================================================================
 *
 * Esto decodifica el voltaje de una **bateria de litio**. Si una escala esta
 * mal, el tablero pinta un voltaje falso, y sobre un voltaje falso se toman
 * decisiones que acaban en una bateria destruida o en un incendio. Por eso este
 * archivo hace tres cosas que un decodificador normal no hace:
 *
 *   1. **Rechaza** toda trama cuyo checksum no cuadre. Sin excepciones, sin
 *      "casi bien", sin arreglarla. Misma regla dura que el XOR del TPMS.
 *   2. Guarda **siempre** los bytes crudos junto a lo interpretado, para poder
 *      recalcular sin volver al carro el dia que una escala resulte mal.
 *   3. **Marca** cada valor que cae fuera de lo fisicamente posible en vez de
 *      esconderlo. Un numero absurdo se ve y se corrige; un numero absurdo
 *      escondido se convierte en un aparato "que no funciona" y se busca el
 *      fallo donde no esta.
 *
 * Y una cosa que deliberadamente NO hace: **escribir en el BMS**. El protocolo
 * tiene una marca de escritura (0x5A) que permite apagar los FET y mover los
 * umbrales de proteccion. No esta implementada y no debe implementarse para
 * este tablero. Un tablero LEE. Ver [MARCA_ESCRITURA_NO_IMPLEMENTADA].
 *
 * ----------------------------------------------------------------------------
 * EL FORMATO
 * ----------------------------------------------------------------------------
 * PETICION (7 bytes, siempre):
 *
 *     DD  A5  <registro>  <largo=00>  <checksum 2 bytes>  77
 *     0   1   2           3           4  5                6
 *
 * RESPUESTA (4 + largo + 3 bytes):
 *
 *     DD  <registro>  <estado>  <largo>  <datos...>  <checksum 2 bytes>  77
 *     0   1           2         3        4..3+largo
 *
 * CHECKSUM — la formula exacta, y es la misma para los dos sentidos:
 *
 *     checksum = (0x10000 - suma) and 0xFFFF     (complemento a dos, 16 bits)
 *
 *   donde `suma` es la suma aritmetica de los bytes desde el **offset 2** hasta
 *   el **offset 3+largo**, ambos incluidos. Se manda **MSB primero** (big
 *   endian), al reves que todo lo demas de Bluetooth.
 *
 *   En la peticion esos bytes son `registro` y `largo`.
 *   En la respuesta son `estado` y `largo` — el **registro NO entra**.
 *   Es la misma regla de offsets en los dos casos; solo cambia que byte cae
 *   en la posicion 2.
 *
 * ----------------------------------------------------------------------------
 * POR QUE ESTA FORMULA ES VERIFICABLE Y NO UNA SUPOSICION
 * ----------------------------------------------------------------------------
 * Se comprueba a mano, con una calculadora, sobre [TRAMA_REFERENCIA_03]:
 *
 *   estado(00) + largo(1B=27) + suma de los 27 datos (0x3E6 = 998) = 1025 = 0x401
 *   0x10000 - 0x401 = 0xFBFF  ->  la trama termina en `FB FF 77`.  CUADRA.
 *
 * Que un checksum de 16 bits cuadre por casualidad es una posibilidad entre
 * 65536. Y ademas la misma trama cuadra por dentro en cuatro sitios mas — ver
 * [TRAMA_REFERENCIA_03]. No es una coincidencia; es el formato.
 *
 * Sin nada de Android a proposito: se prueba entero en la JVM.
 */
object BmsJbd {

    // ========================================================================
    // Framing. VERIFICABLE.
    // ========================================================================

    const val INICIO = 0xDD
    const val FIN = 0x77

    /** Marca de LECTURA en la peticion. Es la unica que usa este proyecto. */
    const val MARCA_LEER = 0xA5

    /**
     * Marca de ESCRITURA del protocolo: 0x5A. **No implementada a proposito.**
     *
     * Con ella se apagan los FET de carga y descarga y se cambian los umbrales
     * de proteccion de una bateria de litio. Un tablero de coche no tiene
     * ningun motivo para hacer eso, y un bug que la mandara sin querer podria
     * dejar el pack sin proteccion. Se documenta el valor para que quede claro
     * que la omision es una decision, no un olvido.
     */
    const val MARCA_ESCRITURA_NO_IMPLEMENTADA = 0x5A

    /** Estado basico del pack: voltaje, corriente, SoC, temperaturas. */
    const val REG_BASICO = 0x03

    /** Voltaje de cada celda, en milivoltios. */
    const val REG_CELDAS = 0x04

    /** Nombre / version de hardware, en texto. Util para identificar el modulo. */
    const val REG_NOMBRE = 0x05

    /** estado == 0 significa que el BMS acepto la peticion. */
    const val ESTADO_OK = 0x00

    /** Lo que contesta un BMS a un registro que no entiende. */
    const val ESTADO_ERROR = 0x80

    const val POS_REGISTRO = 1
    const val POS_ESTADO = 2
    const val POS_LARGO = 3
    const val POS_DATOS = 4

    /** Cabecera(4) + checksum(2) + terminador(1). */
    const val BYTES_FUERA_DE_DATOS = 7

    /**
     * Tope de datos que se acepta declarar.
     *
     * El registro mas grande que existe es el 0x04 de un pack de 32 celdas: 64
     * bytes. 128 es el doble con holgura. Existe para que un 0xDD que caiga
     * dentro de basura y declare 255 no deje el reensamblador esperando 262
     * bytes mientras la trama buena que viene detras se retrasa.
     */
    const val LARGO_MAX_DATOS = 128

    // ========================================================================
    // Peticiones
    // ========================================================================

    /**
     * Arma la peticion de lectura de un registro.
     *
     * `DD A5 <registro> 00 <checksum> 77`. El registro 0x03 sale
     * `DD A5 03 00 FF FD 77` y el 0x04 sale `DD A5 04 00 FF FC 77`, que es lo
     * que se puede comparar contra cualquier captura de la app Xiaoxiang.
     */
    fun peticion(registro: Int): ByteArray {
        val suma = (registro and 0xFF) + 0x00
        val ck = checksum(suma)
        return byteArrayOf(
            INICIO.toByte(), MARCA_LEER.toByte(),
            (registro and 0xFF).toByte(), 0x00,
            ((ck shr 8) and 0xFF).toByte(), (ck and 0xFF).toByte(),
            FIN.toByte(),
        )
    }

    fun peticionBasico(): ByteArray = peticion(REG_BASICO)
    fun peticionCeldas(): ByteArray = peticion(REG_CELDAS)

    /** Complemento a dos de la suma, en 16 bits. La formula, sola. */
    fun checksum(suma: Int): Int = (0x10000 - (suma and 0xFFFF)) and 0xFFFF

    /** El checksum de un tramo de bytes, sumandolos primero. */
    fun checksumDe(bytes: ByteArray, desde: Int, hasta: Int): Int {
        var s = 0
        for (i in desde..hasta) {
            if (i < 0 || i >= bytes.size) return -1
            s += bytes[i].toInt() and 0xFF
        }
        return checksum(s)
    }

    // ========================================================================
    // Registro 0x03 — el mapa de campos, uno por uno.
    // ========================================================================

    /**
     * Los offsets del registro 0x03, dentro de los DATOS (no de la trama).
     *
     * Cada campo con su OFFSET, TAMAÑO, ESCALA y UNIDAD, y con su nivel de
     * confianza. "VERIFICABLE" quiere decir que [TRAMA_REFERENCIA_03] lo
     * confirma; "HIPOTESIS" quiere decir que esa trama no lo puede confirmar y
     * el valor viene de documentacion de terceros.
     */
    object Campos {

        /**
         * VERIFICABLE. offset 0, 2 bytes, **big endian sin signo**, 10 mV/unidad.
         *
         * En la trama de referencia: 0x1700 = 5888 -> **58.88 V**.
         * Con 15 celdas (offset 21) sale 3.925 V por celda, que cae de lleno en
         * la banda de una celda de litio cargada (3.0-4.2 V). Con cualquier otra
         * escala no: 1 mV daria 5.9 V para 15 celdas (0.39 V/celda, imposible) y
         * 100 mV daria 588 V.
         */
        const val VOLTAJE = 0
        const val VOLTAJE_V_POR_UNIDAD = 0.01f

        /**
         * **HIPOTESIS** — offset 2, 2 bytes, big endian **CON SIGNO**
         * (complemento a dos), 10 mA/unidad.
         *
         * Es el UNICO campo del registro que la trama de referencia no puede
         * confirmar, porque en ella la corriente vale exactamente 0.
         *
         * A FAVOR de 10 mA: la capacidad de los offsets 4 y 6 esta en 10 mAh, y
         * el mismo firmware usando dos escalas distintas para amperios y
         * amperios-hora seria raro.
         * EN CONTRA: nada lo mide. Por eso hay [EstadoBasico.corrienteCruda] y
         * la comprobacion de rango, y por eso esta el punto 5 de
         * [com.nonosky.carsoc.hci.COMO_CONFIRMAR_LE].
         *
         * **HIPOTESIS sobre el SIGNO**: negativo = descargando, positivo =
         * cargando. Si resulta al reves, el tablero mostraria una bateria
         * cargandose cuando se esta vaciando. Se invierte cambiando SOLO
         * [CORRIENTE_NEGATIVA_ES_DESCARGA].
         */
        const val CORRIENTE = 2
        const val CORRIENTE_A_POR_UNIDAD = 0.01f

        /**
         * VERIFICABLE por coherencia interna. offset 4, 2 bytes, BE, 10 mAh.
         *
         * Referencia: 0x02D0 = 720 -> 7.20 Ah. Y 720/1000 = 72%, que es
         * exactamente el byte de SoC del offset 19 (0x48 = 72). Tres campos
         * distintos cuadrando entre si no es casualidad.
         */
        const val CAPACIDAD_RESTANTE = 4
        const val CAPACIDAD_AH_POR_UNIDAD = 0.01f

        /** VERIFICABLE igual que el anterior. offset 6, 2 bytes, BE, 10 mAh. */
        const val CAPACIDAD_NOMINAL = 6

        /** VERIFICABLE (vale 0 en la referencia). offset 8, 2 bytes, BE, cuenta. */
        const val CICLOS = 8

        /**
         * VERIFICABLE. offset 10, 2 bytes, BE, **empaquetada**:
         *   bits 15..9 = año - 2000   (7 bits)
         *   bits  8..5 = mes          (4 bits)
         *   bits  4..0 = dia          (5 bits)
         *
         * Referencia: 0x2078 = `0010000 0011 11000` -> 2016-03-24. Una fecha
         * de fabricacion perfectamente creible para uno de estos modulos. Con
         * cualquier otro reparto de bits salen meses del 0 al 15 o dias del 0
         * al 31 fuera de sitio; este reparto da los tres campos plausibles a la
         * vez, que es lo que lo confirma.
         */
        const val FECHA = 10

        /** offset 12, 2 bytes BE: mapa de balanceo de las celdas 1..16. */
        const val BALANCEO_BAJO = 12

        /** offset 14, 2 bytes BE: mapa de balanceo de las celdas 17..32. */
        const val BALANCEO_ALTO = 14

        /**
         * offset 16, 2 bytes BE: mapa de bits de PROTECCIONES.
         *
         * Vale 0 en la referencia, o sea "sin fallos", que es coherente con
         * todo lo demas. Los NOMBRES de cada bit son **HIPOTESIS**: vienen de
         * documentacion de terceros y no se han visto encenderse en este
         * aparato. Ver [PROTECCIONES] y el punto 6 de COMO_CONFIRMAR_LE.
         * El mapa se vuelca SIEMPRE crudo ademas de nombrado.
         */
        const val PROTECCIONES = 16

        /**
         * offset 18, 1 byte: version del firmware. Nibble alto . nibble bajo.
         * Referencia: 0x10 -> "1.0". HIPOTESIS ALTA (encaja, no se ha medido
         * contra otra version).
         */
        const val VERSION = 18

        /**
         * VERIFICABLE. offset 19, 1 byte, **porcentaje directo** 0..100.
         *
         * Referencia: 0x48 = 72 -> **72 %**, que coincide con
         * capacidadRestante/capacidadNominal = 720/1000. Este cruce es lo que
         * convierte el SoC de hipotesis en dato: dos caminos independientes de
         * la misma trama dan el mismo numero.
         */
        const val SOC = 19

        /**
         * offset 20, 1 byte: estado de los FET.
         *   bit 0 = FET de CARGA encendido
         *   bit 1 = FET de DESCARGA encendido
         * Referencia: 0x03 = los dos encendidos, coherente con protecciones=0.
         * HIPOTESIS ALTA.
         */
        const val FET = 20
        const val FET_CARGA = 0x01
        const val FET_DESCARGA = 0x02

        /**
         * VERIFICABLE por coherencia. offset 21, 1 byte: numero de celdas.
         * Referencia: 0x0F = 15, y 58.88 V / 15 = 3.925 V por celda.
         */
        const val NUM_CELDAS = 21

        /**
         * VERIFICABLE por el largo. offset 22, 1 byte: numero de sensores NTC.
         * Referencia: 0x02, y el largo declarado 0x1B = 27 = 23 + 2*2. El
         * propio largo de la trama confirma este campo.
         */
        const val NUM_NTC = 22

        /**
         * VERIFICABLE. offset 23 en adelante, 2 bytes BE por sensor,
         * **decimas de kelvin**:  °C = valor/10 - 273.15
         *
         * Referencia: 0x0B76 = 2934 -> 20.25 °C, y 0x0B82 = 2946 -> 21.45 °C.
         * Dos sensores separados 1.2 °C dentro de un mismo pack a temperatura
         * ambiente. Cualquier otra escala se cae sola: en decimas de °C serian
         * 293 °C, y en °C directos 2934 °C.
         */
        const val TEMPERATURAS = 23

        /** Datos minimos del registro 0x03: todo lo de arriba menos los NTC. */
        const val MINIMO_SIN_NTC = 23

        /** El largo que DEBE tener el registro 0x03 con [n] sensores. */
        fun largoEsperado(n: Int): Int = MINIMO_SIN_NTC + 2 * n
    }

    /**
     * HIPOTESIS: negativo = descargando. Si el carro dice lo contrario, esto
     * es lo unico que hay que cambiar.
     */
    const val CORRIENTE_NEGATIVA_ES_DESCARGA = true

    /**
     * Nombres de los bits de [Campos.PROTECCIONES].
     *
     * **PRESTADOS.** Vienen de documentacion de terceros del protocolo JBD, no
     * de este aparato: aqui nunca se ha visto ninguno encendido. Se nombran
     * porque un nombre ayuda a diagnosticar, y se dice que son prestados porque
     * mentir sobre la procedencia de un dato es peor que no tenerlo. El valor
     * crudo se expone siempre al lado.
     */
    val PROTECCIONES: List<String> = listOf(
        "sobretension de celda",           // bit 0
        "subtension de celda",             // bit 1
        "sobretension del pack",           // bit 2
        "subtension del pack",             // bit 3
        "sobretemperatura al cargar",      // bit 4
        "temperatura baja al cargar",      // bit 5
        "sobretemperatura al descargar",   // bit 6
        "temperatura baja al descargar",   // bit 7
        "sobrecorriente de carga",         // bit 8
        "sobrecorriente de descarga",      // bit 9
        "cortocircuito",                   // bit 10
        "fallo del integrado de medida",   // bit 11
        "MOS bloqueado por software",      // bit 12
        "bit 13 reservado",
        "bit 14 reservado",
        "bit 15 reservado",
    )

    // ========================================================================
    // Rangos de plausibilidad fisica.
    //
    // Que un valor caiga fuera NO significa que la bateria este mal: significa
    // que la ESCALA esta mal. Se marca, no se esconde. Misma politica que
    // Escalas.PSI_PLAUSIBLE del TPMS.
    // ========================================================================

    /** Un pack de 4S LiFePO4 da ~12.8 V; uno de 24S Li-ion ~100 V. */
    val VOLTAJE_PLAUSIBLE = 5.0f..100.0f

    /** Ni el arranque de un motor pasa de 500 A por un BMS de estos. */
    val CORRIENTE_PLAUSIBLE = -500.0f..500.0f

    /** Fuera de esto, o arde o esta en la Antartida. */
    val TEMPERATURA_PLAUSIBLE = -40.0f..100.0f

    /** Litio: 2.0 V muy vacia, 4.3 V muy llena. Fuera de ahi es la escala. */
    val CELDA_PLAUSIBLE = 1.0f..5.0f

    val CELDAS_PLAUSIBLE = 1..32
    val NTC_PLAUSIBLE = 0..8
    val SOC_PLAUSIBLE = 0..100

    /**
     * Cuanto puede alejarse el SoC declarado del que sale de las capacidades.
     *
     * Un BMS cuenta culombios y recalibra a saltos, asi que unos puntos de
     * diferencia son normales. 6 puntos es holgado: sirve para cazar una escala
     * equivocada (que daria decenas de puntos), no para auditar al BMS.
     */
    const val TOLERANCIA_SOC_PUNTOS = 6

    /**
     * Cuanto puede alejarse la suma de las celdas del voltaje total.
     *
     * Este cruce es la mejor defensa que existe contra una escala mal puesta:
     * el registro 0x03 da el total en 10 mV y el 0x04 da cada celda en 1 mV,
     * por caminos independientes. Si las dos escalas estan bien, las dos cifras
     * coinciden dentro del error de redondeo. Si una esta mal, la diferencia es
     * de un factor 10, no de un 2%.
     */
    const val TOLERANCIA_SUMA_CELDAS = 0.02f

    // ========================================================================
    // Decodificacion
    // ========================================================================

    /** Cualquier cosa que salga de una trama con checksum bueno. */
    sealed class Respuesta {
        abstract val registro: Int
        abstract val estado: Int
        abstract val crudo: ByteArray

        /** El BMS acepto la peticion. Con estado != 0 los datos no valen. */
        val aceptada: Boolean get() = estado == ESTADO_OK

        fun hex(): String = crudo.joinToString("") { "%02X".format(it) }
    }

    /**
     * Registro 0x03 desarmado.
     *
     * Los campos "crudo" no son ruido: son el seguro. El dia que una escala
     * resulte estar mal, con ellos se recalcula sin volver al carro.
     */
    data class EstadoBasico(
        override val registro: Int,
        override val estado: Int,
        override val crudo: ByteArray,

        val voltajeCrudo: Int,
        val corrienteCruda: Int,
        val capacidadRestanteCruda: Int,
        val capacidadNominalCruda: Int,
        val ciclos: Int,
        val fechaCruda: Int,
        val balanceo: Long,
        val proteccionesCrudo: Int,
        val versionCruda: Int,
        val soc: Int,
        val fetCrudo: Int,
        val numeroCeldas: Int,
        val numeroNtc: Int,
        val temperaturasCrudas: List<Int>,
        /**
         * El largo declarado no cuadra con 23 + 2*numeroNtc.
         *
         * No invalida la trama —el checksum ya la valido— pero avisa de que
         * este modulo puede traer campos que este mapa no conoce.
         */
        val largoInesperado: Boolean,
    ) : Respuesta() {

        val voltajeV: Float get() = voltajeCrudo * Campos.VOLTAJE_V_POR_UNIDAD

        /** HIPOTESIS de escala Y de signo. Ver [Campos.CORRIENTE]. */
        val corrienteA: Float
            get() = corrienteCruda * Campos.CORRIENTE_A_POR_UNIDAD *
                (if (CORRIENTE_NEGATIVA_ES_DESCARGA) 1f else -1f)

        val capacidadRestanteAh: Float
            get() = capacidadRestanteCruda * Campos.CAPACIDAD_AH_POR_UNIDAD
        val capacidadNominalAh: Float
            get() = capacidadNominalCruda * Campos.CAPACIDAD_AH_POR_UNIDAD

        /** °C = decimas de kelvin / 10 - 273.15. */
        val temperaturasC: List<Float>
            get() = temperaturasCrudas.map { it / 10f - 273.15f }

        /**
         * La temperatura que le importa a una bateria de litio: la mas alta.
         *
         * Promediar sensores esconde justamente lo que hay que ver. Si una
         * celda se calienta y las otras no, la media lo diluye y la maxima lo
         * enseña.
         */
        val temperaturaMaximaC: Float? get() = temperaturasC.maxOrNull()

        val cargando: Boolean get() = fetCrudo and Campos.FET_CARGA != 0
        val descargando: Boolean get() = fetCrudo and Campos.FET_DESCARGA != 0

        val version: String
            get() = "%d.%d".format((versionCruda shr 4) and 0x0F, versionCruda and 0x0F)

        /** Fecha de fabricacion empaquetada, o null si sale imposible. */
        val fecha: String?
            get() {
                val anio = 2000 + ((fechaCruda shr 9) and 0x7F)
                val mes = (fechaCruda shr 5) and 0x0F
                val dia = fechaCruda and 0x1F
                return if (mes in 1..12 && dia in 1..31) "%04d-%02d-%02d".format(anio, mes, dia)
                else null
            }

        /** Que celdas estan balanceando ahora mismo, por numero (1..32). */
        val celdasBalanceando: List<Int>
            get() = (0 until 32).filter { (balanceo shr it) and 1L == 1L }.map { it + 1 }

        /** Las protecciones activas, con el aviso de que el nombre es prestado. */
        fun proteccionesActivas(): List<String> =
            (0 until 16).filter { (proteccionesCrudo shr it) and 1 == 1 }
                .map { PROTECCIONES.getOrElse(it) { "bit $it" } }

        fun describirProtecciones(): String =
            if (proteccionesCrudo == 0) "0x0000 (sin protecciones activas)"
            else "0x%04X (%s — nombres tomados de documentacion, NO verificados en este aparato)"
                .format(proteccionesCrudo, proteccionesActivas().joinToString(", "))

        // --- Marcas de "la escala esta mal", no "la bateria esta mal" -------

        val voltajeFueraDeRango: Boolean get() = voltajeV !in VOLTAJE_PLAUSIBLE
        val corrienteFueraDeRango: Boolean get() = corrienteA !in CORRIENTE_PLAUSIBLE
        val socFueraDeRango: Boolean get() = soc !in SOC_PLAUSIBLE
        val celdasFueraDeRango: Boolean get() = numeroCeldas !in CELDAS_PLAUSIBLE
        val ntcFueraDeRango: Boolean get() = numeroNtc !in NTC_PLAUSIBLE
        val temperaturaFueraDeRango: Boolean
            get() = temperaturasC.any { it !in TEMPERATURA_PLAUSIBLE }

        /**
         * Voltaje por celda. La comprobacion mas parlante de todas: si esto no
         * cae entre 2 y 4.3 V, la escala del voltaje o el numero de celdas
         * estan mal, y no hace falta nada mas para saberlo.
         */
        val voltajePorCeldaV: Float?
            get() = if (numeroCeldas in CELDAS_PLAUSIBLE) voltajeV / numeroCeldas else null

        /**
         * El SoC declarado cuadra con capacidadRestante/capacidadNominal.
         *
         * null cuando la capacidad nominal es 0 y por tanto no hay con que
         * comparar (un BMS recien puesto en marcha lo deja a cero).
         */
        val socCoherente: Boolean?
            get() {
                if (capacidadNominalCruda <= 0) return null
                val calculado = 100f * capacidadRestanteCruda / capacidadNominalCruda
                return kotlin.math.abs(calculado - soc) <= TOLERANCIA_SOC_PUNTOS
            }

        /**
         * Todo lo que huele a escala equivocada, en una linea por problema.
         *
         * Lista vacia = la trama es creible. Es lo que decide si un numero
         * llega a la pantalla o se queda en el diagnostico.
         */
        fun sospechas(): List<String> {
            val s = mutableListOf<String>()
            if (voltajeFueraDeRango) {
                s += "voltaje %.2f V fuera de %.0f..%.0f: revisa la escala, no la bateria"
                    .format(voltajeV, VOLTAJE_PLAUSIBLE.start, VOLTAJE_PLAUSIBLE.endInclusive)
            }
            if (corrienteFueraDeRango) s += "corriente %.2f A fuera de rango".format(corrienteA)
            if (socFueraDeRango) s += "SoC $soc% fuera de 0..100"
            if (celdasFueraDeRango) s += "numero de celdas $numeroCeldas fuera de 1..32"
            if (ntcFueraDeRango) s += "numero de sensores NTC $numeroNtc fuera de 0..8"
            if (temperaturaFueraDeRango) {
                s += "temperatura fuera de rango: " +
                    temperaturasC.joinToString(", ") { "%.1f C".format(it) }
            }
            voltajePorCeldaV?.let {
                if (it !in CELDA_PLAUSIBLE) {
                    s += "%.3f V por celda: imposible en litio, la escala o el conteo de celdas fallan"
                        .format(it)
                }
            }
            if (socCoherente == false) {
                s += "el SoC declarado (%d%%) no cuadra con %.2f/%.2f Ah (%.0f%%)".format(
                    soc, capacidadRestanteAh, capacidadNominalAh,
                    100f * capacidadRestanteCruda / capacidadNominalCruda,
                )
            }
            if (largoInesperado) {
                s += "el largo declarado no es 23 + 2*$numeroNtc: puede haber campos desconocidos"
            }
            return s
        }

        /** La trama es creible de arriba abajo. Solo entonces se pinta. */
        fun creible(): Boolean = aceptada && sospechas().isEmpty()

        override fun equals(other: Any?): Boolean =
            other is EstadoBasico && crudo.contentEquals(other.crudo)

        override fun hashCode(): Int = crudo.contentHashCode()
    }

    /** Registro 0x04: el voltaje de cada celda, en milivoltios crudos. */
    data class VoltajesCelda(
        override val registro: Int,
        override val estado: Int,
        override val crudo: ByteArray,
        /** Milivoltios, tal cual llegaron. 1 mV por unidad. */
        val celdasMv: List<Int>,
    ) : Respuesta() {

        val celdasV: List<Float> get() = celdasMv.map { it / 1000f }

        val sumaV: Float get() = celdasMv.sum() / 1000f
        val minimaV: Float? get() = celdasV.minOrNull()
        val maximaV: Float? get() = celdasV.maxOrNull()

        /** Desequilibrio entre la celda mas alta y la mas baja, en voltios. */
        val desviacionV: Float?
            get() {
                val mn = minimaV ?: return null
                val mx = maximaV ?: return null
                return mx - mn
            }

        val algunaFueraDeRango: Boolean get() = celdasV.any { it !in CELDA_PLAUSIBLE }

        fun sospechas(): List<String> {
            val s = mutableListOf<String>()
            if (celdasMv.isEmpty()) s += "el registro 0x04 no trajo ninguna celda"
            if (celdasMv.size !in CELDAS_PLAUSIBLE) {
                s += "${celdasMv.size} celdas: fuera de 1..32"
            }
            if (algunaFueraDeRango) {
                s += "alguna celda fuera de %.1f..%.1f V: %s".format(
                    CELDA_PLAUSIBLE.start, CELDA_PLAUSIBLE.endInclusive,
                    celdasV.joinToString(", ") { "%.3f".format(it) },
                )
            }
            return s
        }

        fun creible(): Boolean = aceptada && sospechas().isEmpty()

        /**
         * El cruce que caza una escala equivocada: la suma de las celdas
         * (1 mV/unidad) contra el voltaje total del registro 0x03
         * (10 mV/unidad). Dos caminos independientes; si no coinciden, una de
         * las dos escalas esta mal y el tablero NO debe pintar ninguna.
         */
        fun cuadraCon(basico: EstadoBasico): Boolean {
            val total = basico.voltajeV
            if (total <= 0f) return false
            return kotlin.math.abs(sumaV - total) / total <= TOLERANCIA_SUMA_CELDAS
        }

        override fun equals(other: Any?): Boolean =
            other is VoltajesCelda && crudo.contentEquals(other.crudo)

        override fun hashCode(): Int = crudo.contentHashCode()
    }

    /**
     * Trama valida (checksum bueno) de un registro que no se interpreta.
     *
     * Se vuelca cruda en vez de tirarse. Es la misma decision que el ID 0x05
     * del TPMS: lo que no se entiende se guarda para poder mirarlo, y no se
     * pinta.
     */
    data class Cruda(
        override val registro: Int,
        override val estado: Int,
        override val crudo: ByteArray,
        val datos: ByteArray,
    ) : Respuesta() {
        override fun equals(other: Any?): Boolean =
            other is Cruda && crudo.contentEquals(other.crudo)

        override fun hashCode(): Int = crudo.contentHashCode()
    }

    /**
     * Decodifica una trama COMPLETA y ya validada por [validar].
     *
     * No revalida el checksum: quien llama es [EnsambladorBms], que no deja
     * pasar nada sin validar. Publica esta funcion igualmente para poder
     * decodificar una captura pegada a mano — y por eso valida primero si le
     * llega algo que no cuadra: devuelve null antes que inventar.
     */
    fun decodificar(trama: ByteArray): Respuesta? {
        if (validar(trama) != null) return null
        val registro = trama[POS_REGISTRO].toInt() and 0xFF
        val estado = trama[POS_ESTADO].toInt() and 0xFF
        val largo = trama[POS_LARGO].toInt() and 0xFF
        val datos = trama.copyOfRange(POS_DATOS, POS_DATOS + largo)

        // Con estado != 0 el BMS rechazo la peticion y los "datos" no son
        // datos. Interpretarlos daria un voltaje inventado.
        if (estado != ESTADO_OK) return Cruda(registro, estado, trama.copyOf(), datos)

        return when (registro) {
            REG_BASICO -> decodificarBasico(trama, estado, datos)
            REG_CELDAS -> decodificarCeldas(trama, estado, datos)
            else -> Cruda(registro, estado, trama.copyOf(), datos)
        }
    }

    private fun decodificarBasico(trama: ByteArray, estado: Int, d: ByteArray): Respuesta {
        // Sin los 23 bytes fijos no hay ni voltaje ni SoC: se vuelca cruda.
        if (d.size < Campos.MINIMO_SIN_NTC) return Cruda(REG_BASICO, estado, trama.copyOf(), d)

        val numNtc = d[Campos.NUM_NTC].toInt() and 0xFF
        // Solo los sensores que de verdad caben. Leer mas alla del arreglo
        // daria temperaturas inventadas a partir del checksum.
        val caben = ((d.size - Campos.TEMPERATURAS) / 2).coerceAtLeast(0)
        val temps = (0 until minOf(numNtc, caben)).map {
            be16(d, Campos.TEMPERATURAS + it * 2)
        }

        return EstadoBasico(
            registro = REG_BASICO,
            estado = estado,
            crudo = trama.copyOf(),
            voltajeCrudo = be16(d, Campos.VOLTAJE),
            corrienteCruda = be16con(d, Campos.CORRIENTE),
            capacidadRestanteCruda = be16(d, Campos.CAPACIDAD_RESTANTE),
            capacidadNominalCruda = be16(d, Campos.CAPACIDAD_NOMINAL),
            ciclos = be16(d, Campos.CICLOS),
            fechaCruda = be16(d, Campos.FECHA),
            balanceo = (be16(d, Campos.BALANCEO_BAJO).toLong()) or
                (be16(d, Campos.BALANCEO_ALTO).toLong() shl 16),
            proteccionesCrudo = be16(d, Campos.PROTECCIONES),
            versionCruda = d[Campos.VERSION].toInt() and 0xFF,
            soc = d[Campos.SOC].toInt() and 0xFF,
            fetCrudo = d[Campos.FET].toInt() and 0xFF,
            numeroCeldas = d[Campos.NUM_CELDAS].toInt() and 0xFF,
            numeroNtc = numNtc,
            temperaturasCrudas = temps,
            largoInesperado = d.size != Campos.largoEsperado(numNtc),
        )
    }

    private fun decodificarCeldas(trama: ByteArray, estado: Int, d: ByteArray): Respuesta {
        // Un largo impar significa media celda al final: se ignora ese byte en
        // vez de completarlo con un cero, que daria una celda a 0 V — y una
        // celda a 0 V en una pantalla es una alarma falsa de las gordas.
        val n = d.size / 2
        return VoltajesCelda(
            registro = REG_CELDAS,
            estado = estado,
            crudo = trama.copyOf(),
            celdasMv = (0 until n).map { be16(d, it * 2) },
        )
    }

    /**
     * Comprueba una trama completa. Devuelve null si esta bien, o el MOTIVO.
     *
     * Devolver el motivo y no un booleano es deliberado: es lo que se enseña
     * por el puente HTTP cuando algo falla, igual que hace
     * [com.nonosky.carsoc.tpms.TpmsDecoder.rechazadas].
     */
    fun validar(trama: ByteArray?): String? {
        if (trama == null) return "trama nula"
        if (trama.size < BYTES_FUERA_DE_DATOS) {
            return "mide ${trama?.size} bytes y el minimo son $BYTES_FUERA_DE_DATOS"
        }
        if ((trama[0].toInt() and 0xFF) != INICIO) {
            return "no empieza en DD sino en %02X".format(trama[0])
        }
        val largo = trama[POS_LARGO].toInt() and 0xFF
        if (largo > LARGO_MAX_DATOS) return "largo declarado $largo > $LARGO_MAX_DATOS"
        val total = BYTES_FUERA_DE_DATOS + largo
        if (trama.size != total) return "mide ${trama.size} bytes y declara $largo (deberia medir $total)"
        if ((trama[total - 1].toInt() and 0xFF) != FIN) {
            return "no termina en 77 sino en %02X".format(trama[total - 1])
        }

        val esperado = checksumDe(trama, POS_ESTADO, POS_LARGO + largo)
        val traido = ((trama[total - 3].toInt() and 0xFF) shl 8) or (trama[total - 2].toInt() and 0xFF)
        if (esperado != traido) {
            return "checksum esperaba %04X y trajo %04X".format(esperado, traido)
        }
        return null
    }

    /** Rearma la trama de una respuesta, con su checksum. Para las pruebas. */
    fun armarRespuesta(registro: Int, estado: Int, datos: ByteArray): ByteArray {
        val t = ByteArray(BYTES_FUERA_DE_DATOS + datos.size)
        t[0] = INICIO.toByte()
        t[POS_REGISTRO] = (registro and 0xFF).toByte()
        t[POS_ESTADO] = (estado and 0xFF).toByte()
        t[POS_LARGO] = (datos.size and 0xFF).toByte()
        datos.copyInto(t, POS_DATOS)
        val ck = checksumDe(t, POS_ESTADO, POS_LARGO + datos.size)
        t[t.size - 3] = ((ck shr 8) and 0xFF).toByte()
        t[t.size - 2] = (ck and 0xFF).toByte()
        t[t.size - 1] = FIN.toByte()
        return t
    }

    /** Sin signo, big endian. Devuelve 0 si no cabe, nunca lanza. */
    private fun be16(b: ByteArray, i: Int): Int =
        if (i + 1 >= b.size) 0
        else ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    /** Con signo (complemento a dos de 16 bits), big endian. */
    private fun be16con(b: ByteArray, i: Int): Int {
        val v = be16(b, i)
        return if (v >= 0x8000) v - 0x10000 else v
    }

    /** Hexadecimal pegado a mano -> bytes. Igual que TpmsDecoder.hexABytes. */
    fun hexABytes(s: String?): ByteArray {
        if (s.isNullOrBlank()) return ByteArray(0)
        val limpio = s.uppercase().filter { it.isDigit() || it in 'A'..'F' }
        val par = if (limpio.length % 2 == 0) limpio else limpio.dropLast(1)
        return ByteArray(par.length / 2) { par.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /**
     * Trama de referencia del registro 0x03, con checksum que CUADRA.
     *
     * No sale de esta bateria: sale de documentacion publica del protocolo JBD.
     * Se usa igualmente porque **se valida sola**, y de cinco formas
     * independientes a la vez:
     *
     *   1. El checksum de 16 bits cuadra exacto (`FB FF`). 1 entre 65536.
     *   2. SoC declarado 0x48 = 72 % == capacidadRestante/nominal = 720/1000.
     *   3. Voltaje 5888*10 mV / 15 celdas = 3.925 V/celda, dentro de la banda
     *      del litio.
     *   4. La fecha empaquetada 0x2078 da 2016-03-24: año, mes y dia los tres
     *      plausibles con el mismo reparto de bits.
     *   5. El largo declarado 0x1B = 27 == 23 + 2 sensores NTC, y los dos
     *      sensores dan 20.25 y 21.45 °C.
     *
     * Cinco comprobaciones independientes cuadrando a la vez es lo que
     * convierte "lo dice internet" en "el formato es este". Aun asi, el dia que
     * llegue una trama de la bateria del carro, **pegala aqui** — una trama de
     * este aparato vale mas que cinco deducciones.
     */
    const val TRAMA_REFERENCIA_03 =
        "DD03001B1700000000" + "02D003E8" + "0000" + "2078" +
            "00000000" + "0000" + "10" + "48" + "03" + "0F" + "02" +
            "0B760B82" + "FBFF" + "77"

    /** La peticion del registro 0x03, tal como debe salir al aire. */
    const val PETICION_REFERENCIA_03 = "DDA50300FFFD77"

    /** La peticion del registro 0x04. */
    const val PETICION_REFERENCIA_04 = "DDA50400FFFC77"
}

/**
 * Junta las notificaciones del BMS hasta formar tramas y las decodifica.
 *
 * Tiene estado porque **el BMS no contesta en una notificacion**. Con el
 * ATT_MTU por omision de 23 bytes caben 20 bytes de valor, y la respuesta del
 * registro 0x03 mide 34: llega en dos trozos de 20 y 14. Quien trate cada
 * notificacion como una trama no decodifica ninguna y concluye que el aparato
 * habla otro protocolo.
 *
 * Es el mismo problema, y la misma solucion, que ya hubo dos veces en este
 * proyecto: los eventos HCI partidos por el `maxPacketSize` de 16 bytes del
 * dongle, y las tramas del TPMS partidas por la lectura BULK del CH340.
 *
 * Un solo hilo lo alimenta. Nunca lanza: lo que no cuadra se cuenta y se tira.
 */
class EnsambladorBms {

    private var pendiente = ByteArray(0)

    var tramasBuenas = 0L
        private set
    var tramasChecksumMalo = 0L
        private set
    var bytesDescartados = 0L
        private set

    private val rechazadas = ArrayDeque<String>()

    val bytesPendientes: Int get() = pendiente.size

    /** Los bytes de las ultimas tramas rechazadas, con el motivo. */
    fun rechazadas(): List<String> = synchronized(rechazadas) { rechazadas.toList() }

    /**
     * Mete el valor de una notificacion y devuelve las tramas que salgan.
     *
     * Puede devolver cero (falta la segunda mitad), una, o varias si dos
     * respuestas vinieron pegadas.
     */
    fun alimentar(datos: ByteArray): List<BmsJbd.Respuesta> {
        if (datos.isEmpty() && pendiente.isEmpty()) return emptyList()
        val buf = if (pendiente.isEmpty()) datos.copyOf() else pendiente + datos

        val salida = mutableListOf<BmsJbd.Respuesta>()
        var i = 0

        while (i < buf.size) {
            // 1. Buscar el 0xDD que abre una trama.
            val inicio = buscarInicio(buf, i)
            if (inicio < 0) {
                bytesDescartados += (buf.size - i).toLong()
                i = buf.size
                break
            }
            if (inicio > i) bytesDescartados += (inicio - i).toLong()
            i = inicio

            // 2. Sin la cabecera no se sabe cuanto mide: esperar mas bytes.
            if (buf.size - i < BmsJbd.POS_DATOS) break

            val largo = buf[i + BmsJbd.POS_LARGO].toInt() and 0xFF
            if (largo > BmsJbd.LARGO_MAX_DATOS) {
                // Un 0xDD dentro de basura declarando 200 bytes dejaria el
                // canal esperando datos que no van a llegar mientras la trama
                // buena que viene detras se retrasa. Se rechaza YA.
                anotarRechazo(buf, i, minOf(buf.size - i, 8), "largo declarado $largo, imposible")
                bytesDescartados++
                i++
                continue
            }

            val total = BmsJbd.BYTES_FUERA_DE_DATOS + largo
            // 3. Trama a medias: se guarda y se espera al siguiente trozo. Es
            //    el caso NORMAL, no el excepcional.
            if (buf.size - i < total) break

            val trama = buf.copyOfRange(i, i + total)
            val motivo = BmsJbd.validar(trama)
            if (motivo != null) {
                if (motivo.startsWith("checksum")) tramasChecksumMalo++
                anotarRechazo(buf, i, total, motivo)
                // Se avanza UN byte, nunca `total`: el 0xDD pudo caer dentro de
                // basura, y saltar la trama entera se comeria la buena que
                // venga detras. Misma leccion que el TpmsDecoder.
                bytesDescartados++
                i++
                continue
            }

            val r = BmsJbd.decodificar(trama)
            if (r == null) {
                anotarRechazo(buf, i, total, "valido pero no decodificable")
                bytesDescartados++
                i++
                continue
            }
            tramasBuenas++
            salida += r
            i += total
        }

        var resto = if (i >= buf.size) ByteArray(0) else buf.copyOfRange(i, buf.size)
        if (resto.size > MAX_PENDIENTE) {
            bytesDescartados += (resto.size - MAX_PENDIENTE).toLong()
            resto = resto.copyOfRange(resto.size - MAX_PENDIENTE, resto.size)
        }
        pendiente = resto
        return salida
    }

    /** Tira lo que hubiera a medias. Obligatorio al reabrir la conexion. */
    fun reiniciar() {
        pendiente = ByteArray(0)
    }

    /**
     * Busca 0xDD desde [desde].
     *
     * Devuelve la posicion aunque sea el ultimo byte del buffer: asi ese byte
     * sobrevive en [pendiente] y la trama se arma cuando llegue la segunda
     * notificacion. Descartarlo perderia una lectura de cada tantas y de forma
     * intermitente, que es el peor fallo de diagnosticar.
     */
    private fun buscarInicio(b: ByteArray, desde: Int): Int {
        for (j in desde until b.size) if ((b[j].toInt() and 0xFF) == BmsJbd.INICIO) return j
        return -1
    }

    private fun anotarRechazo(buf: ByteArray, desde: Int, largo: Int, motivo: String) {
        val hasta = minOf(desde + largo, buf.size)
        val hex = (desde until hasta).joinToString("") { "%02X".format(buf[it]) }
        synchronized(rechazadas) {
            if (rechazadas.size >= MAX_RECHAZADAS) rechazadas.removeFirst()
            rechazadas.addLast("$hex  <- $motivo")
        }
    }

    private companion object {
        const val MAX_RECHAZADAS = 20

        /**
         * Tope del buffer a medias. Existe para que un canal con ruido, que
         * nunca produzca una trama, no haga crecer memoria sin fin en un
         * servicio que corre durante horas.
         */
        const val MAX_PENDIENTE = 1024
    }
}
