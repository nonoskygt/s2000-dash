package com.nonosky.carsoc.tpms

/**
 * Decodificador del receptor TPMS que cuelga del CH340.
 *
 * ============================================================================
 * QUE ES SEGURO Y QUE ES HIPOTESIS. Leelo antes de tocar nada.
 * ============================================================================
 *
 * SEGURO (medido en vivo a 19200 baudios, cinco tramas, repetidas identicas
 * entre capturas distintas con el carro parado):
 *
 *     byte0-1   55 AA        cabecera
 *     byte2     08           largo de la trama completa, cabecera y XOR dentro
 *     byte3     ID           vistos: 00, 01, 05, 10, 11
 *     byte4     dato A       vistos: 3E(62), 40(64), 00(0)
 *     byte5     dato B       vistos: 4B(75), 4C(76), 4D(77), 51(81)
 *     byte6     dato C       siempre 00 en lo capturado
 *     byte7     XOR de los bytes 0..6
 *
 * El XOR cuadra en las cinco tramas. Eso esta comprobado a mano:
 *     55^AA^08^10^3E^4B^00 = 92  ✓
 *     55^AA^08^11^3E^4C^00 = 94  ✓
 *     55^AA^08^05^00^51^00 = A3  ✓
 *     55^AA^08^00^40^4D^00 = FA  ✓
 *     55^AA^08^01^40^4D^00 = FB  ✓
 *
 * HIPOTESIS, todavia sin confirmar en el carro (viven en [Escalas], cambiarlas
 * es una linea):
 *
 *   - byte4 es PRESION a 0.5 psi por unidad.
 *   - byte5 es TEMPERATURA en °C con offset 40.
 *   - byte6 son BANDERAS de estado, todas en cero porque no pasa nada.
 *   - los ID 00/01/10/11 son las cuatro ruedas, nibble alto = eje.
 *   - el ID 05 NO es una rueda.
 *
 * ----------------------------------------------------------------------------
 * POR QUE 0.5 PSI Y NO OTRA COSA
 * ----------------------------------------------------------------------------
 * La placa del carro donde se capturaron estas tramas pide 32 psi delante y
 * 32 psi detras — iguales, aunque las medidas sean escalonadas (205/55R16
 * delante, 225/50R16 detras). Fuentes: tirepressure.org y tirepressure.com.
 *
 * Con 0.5 psi por unidad:  64 -> 32.0 psi (clavado en la placa)
 *                          62 -> 31.0 psi (1 psi por debajo, normalisimo)
 *
 * Las alternativas se caen solas por fisica:
 *     psi directo   -> 62 y 64 psi. Por encima del maximo del flanco de un
 *                      205/55R16 (~44-51 psi). Imposible tras un inflado normal.
 *     kPa/2         -> 124 y 128 kPa = 18.0 y 18.6 psi. Una llanta de calle a
 *                      18 psi va visiblemente baja, con el flanco pandeado.
 *     kPa directo   -> 9.3 psi. Absurdo.
 *     bar/100       -> 0.64 bar = 9.3 psi. Absurdo.
 *
 * Ademas 0.5 psi = 3.447 kPa es una unidad conocida en estos receptores
 * chinos. Encaja el numero Y encaja la costumbre.
 *
 * ----------------------------------------------------------------------------
 * POR QUE OFFSET 40 EN LA TEMPERATURA (la mas floja de las tres hipotesis)
 * ----------------------------------------------------------------------------
 * Candidatas para byte5 = 75,76,77,77 (ruedas) y 81 (el ID 05):
 *
 *     offset 40  ->  35,36,37,37 °C  y 41 °C
 *     offset 50  ->  25,26,27,27 °C  y 31 °C
 *     °F directo ->  24,24,25,25 °C  y 27 °C
 *     °C directo ->  75,76,77 °C. Con el carro PARADO no. Fuera.
 *
 * Se elige offset 40 por tres razones, ninguna concluyente:
 *   1. Es la convencion mas extendida del automovil (SAE J1979 usa A-40 para
 *      refrigerante y admision — este mismo proyecto la usa en PidDecoder).
 *      El firmware barato copia lo que ya existe.
 *   2. El contexto fisico cuadra: Mexico, clima calido, motor caliente (o sea,
 *      recien rodado). Llantas a 35-37 °C es exactamente eso. A 24 °C estarian
 *      frias, y el motor no lo estaba.
 *   3. Si el ID 05 es la cajita receptora en el tablero, 41 °C es lo propio de
 *      un salpicadero al sol; y sale mas caliente que las llantas, que es el
 *      orden correcto. Con offset 50 daria 31 °C y con °F 27 °C — demasiado
 *      fresco para un tablero al sol en Mexico.
 *
 * Esto se resuelve GRATIS y sin herramientas, ver [COMO_CONFIRMAR].
 *
 * ----------------------------------------------------------------------------
 * REGLA DURA DEL MODULO
 * ----------------------------------------------------------------------------
 * Toda trama cuyo XOR no cuadre se TIRA. No se aprovecha "casi bien", no se
 * corrige, no se muestra. Una presion mal leida en el tablero de un carro es
 * peor que un hueco: el hueco se ve, el numero falso se cree.
 *
 * Por lo mismo, un ID que no este en [Escalas.RUEDA_POR_ID] jamas se pinta
 * como llanta. Se guarda aparte en [EstadoTpms.otras] para poder mirarlo por
 * el puente HTTP, pero no toca la pantalla.
 *
 * Sin nada de Android a proposito: asi se prueba entero en la JVM, igual que
 * PidDecoder.
 */

/** Las cuatro esquinas del carro. */
enum class Rueda {
    DelanteraIzquierda,
    DelanteraDerecha,
    TraseraIzquierda,
    TraseraDerecha;

    /** Etiqueta corta para una pantalla de 1280x480 donde no sobra un pixel. */
    val corta: String
        get() = when (this) {
            DelanteraIzquierda -> "DI"
            DelanteraDerecha -> "DD"
            TraseraIzquierda -> "TI"
            TraseraDerecha -> "TD"
        }
}

/**
 * Todo lo ajustable del TPMS, en un solo sitio.
 *
 * Lo de arriba de la linea esta MEDIDO. Lo de abajo son SUPOSICIONES. Cambiar
 * una suposicion es cambiar una constante aqui y nada mas — ni el decodificador
 * ni la vista traen numeros propios.
 */
object Escalas {

    // ======================= MEDIDO — no lo toques =========================

    const val CABECERA_1 = 0x55
    const val CABECERA_2 = 0xAA

    /** Trama completa: cabecera + largo + id + 3 datos + XOR. */
    const val LARGO_TRAMA = 8

    const val POS_LARGO = 2
    const val POS_ID = 3
    const val POS_A = 4
    const val POS_B = 5
    const val POS_C = 6
    const val POS_XOR = 7

    // ===================== HIPOTESIS — de aqui para abajo ==================

    /**
     * MEDIDO CON CALIBRADOR DIGITAL: **media libra por cuenta**.
     *
     * El dueño midio las llantas con un manometro digital y confirmo 32 y 31
     * psi, que es exactamente crudoA/2 para los crudos 0x40 (64) y 0x3E (62).
     *
     * Hubo un rodeo que conviene dejar escrito para no repetirlo. Se comparo
     * primero contra la app TPMS nativa del radio (com.syt.tmps), que muestra
     * 29,7 y 28,7 psi, y se "corrigio" la escala a 3.2 kPa por cuenta para
     * cuadrar con ella. Estaba mal: **la nativa es la que esta descalibrada**,
     * unas 2.3 psi baja. Tomarla por verdad de referencia fue el error.
     *
     * La leccion: una segunda implementacion NO es una fuente de verdad, solo
     * una segunda opinion. La verdad es un instrumento contra el mundo fisico
     * — aqui, un calibrador. Cuando las dos discrepan, gana el que toca la
     * llanta.
     */
    const val PSI_POR_UNIDAD = 0.5f
    const val KPA_POR_UNIDAD = PSI_POR_UNIDAD * 6.894757f

    /**
     * MEDIDO: °C desplazado **50**, no 40.
     *
     * Calibrado contra la app nativa del radio con las cuatro ruedas a la
     * vez: crudoB de 77 y 75 dan 27 y 25 grados en la nativa, o sea offset
     * 50 exacto. La suposicion de 40 —que es el offset habitual del
     * automovil, y por eso se eligio— daba 37 y 35: diez grados de mas en
     * las cuatro, sistematicamente.
     *
     * El dueño confirmo que la temperatura era LO UNICO que estaba mal, asi
     * que aqui la nativa si acerto. Que una referencia falle en un campo no
     * la invalida en otro — pero tampoco la convierte en verdad.
     */
    const val TEMP_OFFSET = 50

    /** HIPOTESIS: si un dia se confirma que byte5 son °F, pon esto en true. */
    const val TEMP_EN_FAHRENHEIT = false

    // Aqui vivian la presion de placa y el umbral de aviso (su 75 %). Eran
    // los de UN carro y los usaba el codigo comun, asi que el otro avisaba
    // con la placa ajena. Los umbrales de alerta ahora los pone el dueño en
    // Ajustes: `config.AlertasLlantas`.

    /**
     * Rango en el que una lectura de presion es FISICAMENTE posible en una
     * llanta montada. Fuera de esto la escala esta mal, no la llanta.
     *
     * No se tira la lectura por salirse — se marca. Tirarla en silencio haria
     * que una escala equivocada pareciera un aparato muerto, y un aparato
     * muerto no se depura. Marcada, se ve el numero absurdo y se corrige
     * [PSI_POR_UNIDAD] en una linea.
     */
    val PSI_PLAUSIBLE = 5.0f..60.0f

    /** Igual, para la temperatura. */
    val TEMP_C_PLAUSIBLE = -40..120

    /**
     * HIPOTESIS FUERTE: nibble alto = eje, nibble bajo = lado.
     *
     * A FAVOR:
     *   - Los cuatro ID vistos (00, 01, 10, 11) son exactamente las cuatro
     *     combinaciones de dos bits por dos bits. Para cuatro llantas eso no
     *     parece casualidad.
     *   - El quinto ID, 05, NO cabe en el esquema: no existe un "lado 5". Que
     *     el intruso no encaje refuerza que el esquema es real.
     *   - Es el orden idiomatico del automovil (FL, FR, RL, RR = 0,1,2,3).
     *
     * EN CONTRA — y esto es importante:
     *   - Los datos NO distinguen "eje arriba, lado abajo" de "lado arriba,
     *     eje abajo". Las dos lecturas agrupan {00,01} contra {10,11}: son los
     *     MISMOS dos grupos, solo cambia la etiqueta. Ninguna captura estatica
     *     puede separarlas, por muchas que se hagan.
     *   - El agrupamiento 64/64 contra 62/62 se atribuyo a que el carro pide
     *     presiones distintas por eje. NO ES CIERTO: su placa pide 32 psi en
     *     las cuatro. La diferencia de 1 psi entre pares es desgaste
     *     normal, no una firma del eje. Sirve para confirmar la ESCALA (ambos
     *     valores caen sobre 32 psi), no para orientar las ruedas.
     *   - Estos receptores se "enseñan": la posicion la programo quien lo
     *     instalo. Puede estar cruzada de fabrica.
     *
     * O sea: que hay dos pares esta claro. CUAL par es cual, no. Se resuelve
     * en un minuto con el experimento de [COMO_CONFIRMAR].
     */
    val POR_EJE: Map<Int, Rueda> = mapOf(
        0x00 to Rueda.DelanteraIzquierda,
        0x01 to Rueda.DelanteraDerecha,
        0x10 to Rueda.TraseraIzquierda,
        0x11 to Rueda.TraseraDerecha,
    )

    /** La lectura alternativa, por si el experimento dice que es esta. */
    val POR_LADO: Map<Int, Rueda> = mapOf(
        0x00 to Rueda.DelanteraIzquierda,
        0x01 to Rueda.TraseraIzquierda,
        0x10 to Rueda.DelanteraDerecha,
        0x11 to Rueda.TraseraDerecha,
    )

    /**
     * CONFIRMADO por la app nativa: el nibble alto es el EJE.
     *
     * La duda que ninguna captura estatica podia resolver —si {00,01} eran
     * las delanteras o el lado izquierdo— la contesto la nativa: muestra las
     * dos delanteras a 29,7 psi y las dos traseras a 28,7, y esos son
     * exactamente los pares de crudoA {64,64} y {62,62} que traen los ids
     * {00,01} y {10,11}. Los ids agrupan por EJE.
     */
    val RUEDA_POR_ID: Map<Int, Rueda> = POR_EJE

    /**
     * Cuanto aguanta una lectura antes de pintarse en gris.
     *
     * OJO, esto tambien es hipotesis: no se ha medido cada cuanto refresca el
     * receptor. Un sensor TPMS dormido transmite cada 15-60 s, pero este
     * receptor parece reenviar su tabla en bucle (por eso las capturas salen
     * identicas). 15 minutos es un techo prudente hasta medirlo de verdad —
     * ver [COMO_CONFIRMAR], punto 6.
     */
    const val RANCIA_TRAS_MS = 15 * 60_000L

    /**
     * Tope del buffer de bytes a medias.
     *
     * Existe para que un cable con ruido, que nunca produzca una cabecera, no
     * haga crecer memoria sin fin en un servicio que corre durante horas.
     */
    const val MAX_PENDIENTE = 4096
}

/**
 * Lo que hace falta capturar para dejar de suponer.
 *
 * Se deja en el codigo y no en un documento aparte porque el documento se
 * pierde y el codigo se lee. Cada punto dice QUE hacer y QUE mirar.
 */
val COMO_CONFIRMAR: List<String> = listOf(
    "1. ORIENTACION (eje contra lado). Baja 5 psi de la llanta DELANTERA " +
        "IZQUIERDA apretando la valvula unos segundos y captura. El ID cuyo " +
        "byte4 baje ~10 unidades ES esa llanta. Si baja el 0x00, POR_EJE es " +
        "correcto. Si baja el 0x10, cambia RUEDA_POR_ID a POR_LADO. Un solo " +
        "experimento cierra la pregunta para siempre.",

    "2. ESCALA DE PRESION. En el mismo experimento: si byte4 baja ~10 " +
        "unidades por 5 psi perdidos, PSI_POR_UNIDAD = 0.5 queda confirmado. " +
        "Si baja ~5, es psi directo. Si baja ~34, es kPa/2.",

    "3. ESCALA DE TEMPERATURA. Captura al amanecer con el carro frio toda la " +
        "noche, y anota la temperatura ambiente real. Con offset 40 y 18 °C " +
        "ambiente, byte5 deberia dar ~58 (0x3A); con offset 50, ~68 (0x44); " +
        "si son °F, ~64 (0x40). Los tres predicen numeros distintos, asi que " +
        "una sola captura en frio decide. Gratis y sin herramientas.",

    "4. BANDERAS (byte6). Es el unico byte del que no se sabe NADA: siempre " +
        "salio 00, y salio 00 hasta en la trama rara del ID 05. Para verlo " +
        "moverse hay que provocar cada aviso, uno por uno y anotando cual: " +
        "  (a) desinflar una llanta por debajo de la alerta de presion baja y " +
        "      esperar a que el receptor de el aviso de presion baja; " +
        "  (b) desenroscar un sensor de la valvula y dejarlo fuera 20-30 min, " +
        "      hasta que el receptor lo declare perdido; " +
        "  (c) sobreinflar una a ~45 psi, por si hay aviso de presion alta; " +
        "  (d) rodar 20 min en carretera para calentar de verdad las llantas, " +
        "      por si hay aviso de temperatura; " +
        "  (e) mirar el byte6 del ID 05 mientras tanto: si cambia con lo que " +
        "      le pasa a las llantas, el 05 es un resumen del receptor. " +
        "Cada aviso deberia poner UN bit. Anotar que bit con que aviso.",

    "5. QUE ES EL ID 0x05. Su byte4 es 0x00 (presion cero), que en una llanta " +
        "montada no existe. Tres candidatos: trama de estado del receptor, " +
        "un quinto sensor no emparejado (refaccion), o una trama con otro " +
        "formato entero donde byte4 no es presion. Para separarlos: tapa la " +
        "antena del receptor o aleja los sensores y mira si el 05 cambia " +
        "mientras las cuatro ruedas dejan de refrescarse.",

    "6. PERIODO DE REFRESCO. Registra la hora de llegada de cada ID durante " +
        "una hora con el carro parado y otra rodando. De ahi sale el valor " +
        "real de RANCIA_TRAS_MS en vez del techo prudente de 15 minutos.",

    "7. IDs QUE NO SE HAN VISTO. Cinco tramas es poco. Deja capturando 10 " +
        "minutos y mira DiagnosticoTpms.idsDesconocidos: si aparece un sexto " +
        "ID, o un largo distinto de 8, el formato tiene mas de lo que se vio.",
)

/**
 * Una trama de 8 bytes que YA paso el XOR.
 *
 * Guarda siempre los bytes crudos ademas de lo interpretado. Es deliberado:
 * el dia que una escala resulte estar mal, el crudo permite recalcular sin
 * volver al carro. Es la misma razon por la que Descubridor vuelca hexadecimal.
 */
data class TramaTpms(
    val id: Int,
    val crudoA: Int,
    val crudoB: Int,
    val crudoC: Int,
    val recibidaMs: Long,
) {

    /** null si el ID no es de una rueda conocida. Nunca se adivina. */
    val rueda: Rueda? get() = Escalas.RUEDA_POR_ID[id]

    /**
     * HIPOTESIS de escala. null cuando [crudoA] es 0.
     *
     * Cero NO se traduce como "0.0 psi". Una llanta montada a 0 psi sale del
     * rin; lo que un cero significa de verdad es que el receptor no tiene
     * dato de ese sensor. Pintar "0.0" haria creer en un reventon que no
     * existe, y a la tercera vez nadie vuelve a creerle al tablero.
     */
    val presionPsi: Float?
        get() = if (crudoA == 0) null else crudoA * Escalas.PSI_POR_UNIDAD

    val presionKpa: Float?
        get() = presionPsi?.let { it * 6.89476f }

    /** HIPOTESIS de escala. */
    val temperaturaC: Int?
        get() = if (Escalas.TEMP_EN_FAHRENHEIT) {
            ((crudoB - 32) * 5) / 9
        } else {
            crudoB - Escalas.TEMP_OFFSET
        }

    /** El receptor no tiene dato de este sensor. Ver [presionPsi]. */
    val sinReporte: Boolean get() = crudoA == 0

    /** HIPOTESIS: byte6 son banderas. Se expone crudo, sin bautizar bits. */
    val banderas: Int get() = crudoC

    /**
     * La presion cae fuera de lo fisicamente posible.
     *
     * Si esto se enciende, lo que esta mal es [Escalas.PSI_POR_UNIDAD], no la
     * llanta. La vista no debe pintar un valor marcado asi como si fuera un
     * numero normal.
     */
    val presionFueraDeRango: Boolean
        get() = presionPsi?.let { it !in Escalas.PSI_PLAUSIBLE } ?: false

    val temperaturaFueraDeRango: Boolean
        get() = temperaturaC?.let { it !in Escalas.TEMP_C_PLAUSIBLE } ?: false

    /** La trama tal cual llego, para el puente HTTP. */
    fun hex(): String {
        val cuerpo = intArrayOf(
            Escalas.CABECERA_1, Escalas.CABECERA_2, Escalas.LARGO_TRAMA,
            id, crudoA, crudoB, crudoC,
        )
        var x = 0
        for (v in cuerpo) x = x xor v
        return (cuerpo.toList() + x).joinToString("") { "%02X".format(it) }
    }

    /**
     * Describe [banderas] sin inventarles nombre.
     *
     * Bautizar un bit que no se ha visto moverse es como decidir la escala sin
     * medirla. Aqui se dice que bit esta puesto y se admite que no se sabe
     * que significa; cuando el experimento 4 de [COMO_CONFIRMAR] lo aclare,
     * se cambia esto por nombres de verdad.
     */
    fun describirBanderas(): String {
        if (crudoC == 0) return "00 (todo en cero: sin novedad, o sin banderas)"
        val bits = (0..7).filter { (crudoC shr it) and 1 == 1 }
        return "%02X".format(crudoC) + " (bits " + bits.joinToString(",") +
            " puestos, significado DESCONOCIDO — ver COMO_CONFIRMAR punto 4)"
    }
}

/** Lo ultimo que se sabe de una rueda, con su hora. */
data class LecturaRueda(
    val rueda: Rueda,
    val trama: TramaTpms,
) {
    val presionPsi: Float? get() = trama.presionPsi
    val temperaturaC: Int? get() = trama.temperaturaC
    val medidaMs: Long get() = trama.recibidaMs

    /**
     * Misma idea que VehicleState.isStale: al perder el enlace no se borra la
     * pantalla, se marca el valor viejo. Un hueco a media curva es peor que un
     * dato viejo señalado como viejo.
     */
    fun rancia(ahoraMs: Long): Boolean =
        medidaMs == 0L || (ahoraMs - medidaMs) > Escalas.RANCIA_TRAS_MS
}

/** Foto inmutable de lo que el TPMS sabe ahora mismo. */
data class EstadoTpms(
    val ruedas: Map<Rueda, LecturaRueda> = emptyMap(),
    /**
     * Tramas con ID que no es de ninguna rueda — hoy, el 0x05.
     *
     * Se guardan para poder mirarlas por HTTP, y NO se pintan. Mapear un ID
     * desconocido a una llanta seria exactamente el tipo de invento que este
     * proyecto evita en todas partes.
     */
    val otras: Map<Int, TramaTpms> = emptyMap(),
) {
    fun de(r: Rueda): LecturaRueda? = ruedas[r]
}

/** Contadores para depurar el enlace desde el puente HTTP. */
data class DiagnosticoTpms(
    val tramasBuenas: Long = 0,
    val tramasXorMalo: Long = 0,
    val tramasLargoRaro: Long = 0,
    val bytesDescartados: Long = 0,
    val idsDesconocidos: Map<Int, Int> = emptyMap(),
    val largosDesconocidos: Map<Int, Int> = emptyMap(),
    val bytesPendientes: Int = 0,
)

/**
 * Parser de flujo. Se le echan los bytes segun llegan y devuelve tramas.
 *
 * Tiene estado porque el flujo serie NO respeta las tramas: una lectura BULK
 * puede traer media trama, dos y media, o basura y luego una. Las capturas
 * reales llegaron cortadas, asi que esto no es un caso hipotetico.
 *
 * Un solo hilo lo alimenta (el lector del CH340). El estado se publica en
 * [instantanea], que devuelve copia, para que la vista lo lea desde el hilo
 * de UI sin candados.
 */
class TpmsDecoder {

    private var pendiente = ByteArray(0)

    private val ruedas = LinkedHashMap<Rueda, LecturaRueda>()
    private val otras = LinkedHashMap<Int, TramaTpms>()
    private val idsDesconocidos = LinkedHashMap<Int, Int>()
    private val largosDesconocidos = LinkedHashMap<Int, Int>()

    private var buenas = 0L
    private var xorMalo = 0L
    private var largoRaro = 0L
    private var descartados = 0L

    /**
     * Los BYTES de las ultimas tramas rechazadas, no solo la cuenta.
     *
     * Un contador dice que algo va mal; estos bytes dicen QUE va mal, y son
     * cosas distintas. Un XOR que falla por un bit es ruido en el cable; ocho
     * bytes que no se parecen a una trama es la velocidad equivocada o otro
     * aparato en el puerto. Sin los bytes no hay forma de separarlas en
     * remoto, que es la unica forma en que se depura este radio.
     */
    private val rechazadas = ArrayDeque<String>()

    @Volatile
    private var foto = EstadoTpms()

    /** Copia inmutable del estado. Segura desde cualquier hilo. */
    fun instantanea(): EstadoTpms = foto

    fun diagnostico(): DiagnosticoTpms = DiagnosticoTpms(
        tramasBuenas = buenas,
        tramasXorMalo = xorMalo,
        tramasLargoRaro = largoRaro,
        bytesDescartados = descartados,
        idsDesconocidos = LinkedHashMap(idsDesconocidos),
        largosDesconocidos = LinkedHashMap(largosDesconocidos),
        bytesPendientes = pendiente.size,
    )

    /**
     * Mete [largo] bytes de [datos] y devuelve las tramas completas que salgan.
     *
     * Nunca lanza. La misma regla que PidDecoder: el aparato escupe lo que
     * quiera y el parser aguanta. Lo que no se entiende se cuenta y se tira.
     */
    fun alimentar(
        datos: ByteArray,
        largo: Int = datos.size,
        ahoraMs: Long = System.currentTimeMillis(),
    ): List<TramaTpms> {
        val n = largo.coerceIn(0, datos.size)
        if (n == 0 && pendiente.isEmpty()) return emptyList()

        val buf = if (pendiente.isEmpty()) datos.copyOf(n) else pendiente + datos.copyOf(n)

        val salida = mutableListOf<TramaTpms>()
        var i = 0

        while (i < buf.size) {
            val cab = buscarCabecera(buf, i)
            if (cab < 0) {
                // Nada aprovechable en lo que queda.
                descartados += buf.size - i
                i = buf.size
                break
            }
            if (cab > i) descartados += cab - i
            i = cab

            // Trama a medias: se guarda y se espera al siguiente trozo.
            if (buf.size - i < Escalas.LARGO_TRAMA) break

            val largoDeclarado = buf.u(i + Escalas.POS_LARGO)
            if (largoDeclarado != Escalas.LARGO_TRAMA) {
                // Puede ser una cabecera falsa dentro de basura, o un formato
                // de otro largo que no se ha visto. Se anota por si acaso y
                // se avanza UN byte, nunca ocho: si fue falsa, saltar ocho se
                // comeria la trama buena que venga detras.
                largoRaro++
                largosDesconocidos[largoDeclarado] = (largosDesconocidos[largoDeclarado] ?: 0) + 1
                descartados++
                anotarRechazo(buf, i, "largo declarado %02X, se esperaba 08".format(largoDeclarado))
                i++
                continue
            }

            var x = 0
            for (k in 0 until Escalas.POS_XOR) x = x xor buf.u(i + k)
            if (x != buf.u(i + Escalas.POS_XOR)) {
                xorMalo++
                descartados++
                anotarRechazo(
                    buf, i,
                    "XOR esperaba %02X y trajo %02X".format(x, buf.u(i + Escalas.POS_XOR)),
                )
                i++
                continue
            }

            val trama = TramaTpms(
                id = buf.u(i + Escalas.POS_ID),
                crudoA = buf.u(i + Escalas.POS_A),
                crudoB = buf.u(i + Escalas.POS_B),
                crudoC = buf.u(i + Escalas.POS_C),
                recibidaMs = ahoraMs,
            )
            buenas++
            anotar(trama)
            salida += trama
            i += Escalas.LARGO_TRAMA
        }

        var resto = if (i >= buf.size) ByteArray(0) else buf.copyOfRange(i, buf.size)

        // El resto es siempre una trama a medias (arranca en 55 y mide menos
        // de 8), asi que este tope no deberia dispararse nunca. Esta por si un
        // cambio futuro rompe esa invariante: el servicio corre horas seguidas
        // y una fuga de memoria ahi no se ve hasta que el radio se arrastra.
        if (resto.size > Escalas.MAX_PENDIENTE) {
            descartados += resto.size - Escalas.MAX_PENDIENTE
            resto = resto.copyOfRange(resto.size - Escalas.MAX_PENDIENTE, resto.size)
        }
        pendiente = resto

        if (salida.isNotEmpty()) {
            foto = EstadoTpms(ruedas = LinkedHashMap(ruedas), otras = LinkedHashMap(otras))
        }
        return salida
    }

    /** Tira lo que hubiera a medias. Para cuando se reabre el puerto. */
    fun reiniciar() {
        pendiente = ByteArray(0)
    }

    /**
     * Los bytes de las ultimas tramas rechazadas, con el motivo.
     *
     * Sincronizado porque lo alimenta el hilo lector y lo lee el hilo de una
     * peticion HTTP. Es la unica estructura de esta clase que cruza hilos.
     */
    fun rechazadas(): List<String> = synchronized(rechazadas) { rechazadas.toList() }

    private fun anotarRechazo(buf: ByteArray, desde: Int, motivo: String) {
        val hasta = minOf(desde + Escalas.LARGO_TRAMA, buf.size)
        val hex = (desde until hasta).joinToString("") { "%02X".format(buf.u(it)) }
        synchronized(rechazadas) {
            if (rechazadas.size >= MAX_RECHAZADAS) rechazadas.removeFirst()
            rechazadas.addLast("$hex  <- $motivo")
        }
    }

    private fun anotar(t: TramaTpms) {
        val r = t.rueda
        if (r != null) {
            ruedas[r] = LecturaRueda(r, t)
        } else {
            otras[t.id] = t
            idsDesconocidos[t.id] = (idsDesconocidos[t.id] ?: 0) + 1
        }
    }

    /**
     * Busca 55 AA a partir de [desde].
     *
     * Detalle que parece menor y no lo es: si el buffer TERMINA en 0x55, se
     * devuelve esa posicion aunque todavia no se haya visto el 0xAA. Asi ese
     * byte sobrevive en [pendiente] y la trama se arma cuando llegue el
     * siguiente trozo. Descartarlo perderia una trama de cada tantas, y de
     * forma intermitente — el peor tipo de fallo para diagnosticar.
     */
    private fun buscarCabecera(b: ByteArray, desde: Int): Int {
        var j = desde
        while (j < b.size) {
            if (b.u(j) == Escalas.CABECERA_1) {
                if (j == b.size - 1) return j
                if (b.u(j + 1) == Escalas.CABECERA_2) return j
            }
            j++
        }
        return -1
    }

    private fun ByteArray.u(i: Int): Int = this[i].toInt() and 0xFF

    companion object {

        /** Cuantas tramas rechazadas se conservan para `/tpms`. */
        const val MAX_RECHAZADAS = 40

        /**
         * Convierte una captura pegada en hexadecimal a bytes.
         *
         * Sirve para reproducir por el puente HTTP una captura vieja sin ir al
         * carro: se pega el HEX que ya volco Descubridor.volcarUsbSerial y se
         * ve como lo decodifica esta version del codigo. Tolera espacios y
         * minusculas porque el hex se pega a mano.
         */
        fun hexABytes(s: String?): ByteArray {
            if (s.isNullOrBlank()) return ByteArray(0)
            val limpio = s.uppercase().filter { it.isDigit() || it in 'A'..'F' }
            val par = if (limpio.length % 2 == 0) limpio else limpio.dropLast(1)
            return ByteArray(par.length / 2) {
                par.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            }
        }

        /**
         * Las cinco tramas reales capturadas a 19200 el dia que se descifro
         * el formato. Se dejan en el codigo como referencia viva: si un
         * cambio futuro deja de decodificarlas, las pruebas lo cazan.
         */
        val CAPTURA_REAL: List<String> = listOf(
            "55AA08103E4B0092",
            "55AA08113E4C0094",
            "55AA0805005100A3",
            "55AA0800404D00FA",
            "55AA0801404D00FB",
        )
    }
}
