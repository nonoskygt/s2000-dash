package com.nonosky.carsoc.obd

/**
 * Codigos de averia: decodificarlos y armar la lista desde la respuesta cruda.
 *
 * Todo lo de aqui es PURO —entra texto, sale una lista— para poder probarlo en
 * la JVM sin carro, sin adaptador y sin Bluetooth. La parte que habla con la
 * ECU vive en [LectorDtc].
 *
 * ## La trampa que casi cuesta codigos fantasma
 *
 * El resto del proyecto localiza la respuesta con `hex.indexOf(prefijo)`, y
 * para prefijos de cuatro caracteres —`410C`, `4105`— es correcto. Aqui NO
 * sirve: el prefijo del modo 03 es de dos caracteres, `43`, y hay codigos que
 * contienen ese byte. **P0143 se codifica literalmente `0143`**, asi que la
 * trama `43014300000000` tiene un `43` en la posicion 4. Buscarlo con
 * `indexOf` encontraria el segundo y decodificaria basura como si fueran
 * averias del carro.
 *
 * Por eso aqui el prefijo se ANCLA al principio de la linea, y punto.
 */
object Dtc {

    /**
     * Un codigo ya legible.
     *
     * Llevaba tambien los dos bytes crudos "por si hay que depurar", y nadie
     * los miro nunca: el texto ya se deduce de ellos y la traza del lector
     * guarda la respuesta entera del ELM327, que es lo que de verdad sirve
     * cuando algo no cuadra.
     */
    data class Codigo(val texto: String) {
        /** P, C, B o U. */
        val sistema: Char get() = texto.first()
    }

    /** Lo que se le pide a la ECU y como se llama. */
    enum class Tipo(val modo: String, val prefijo: String, val etiqueta: String) {
        /** Confirmados: los que encendieron la luz. */
        GUARDADOS("03", "43", "guardados"),

        /**
         * Pendientes: la ECU los vio UNA vez y espera a verlos otra.
         *
         * Valen mucho mas de lo que parece: son la averia antes de que
         * encienda la luz, y salen del modo 07 que casi ningun lector barato
         * consulta.
         */
        PENDIENTES("07", "47", "pendientes"),

        /**
         * Permanentes: no se borran con el modo 04, solo cuando la ECU
         * comprueba sola que la averia ya no esta.
         *
         * En un OBD-II del año 2000 lo normal es que NO exista: el modo 0A se
         * hizo obligatorio en 2010. Se pregunta igual y si contesta "NO DATA"
         * no es un fallo, es que este carro es de antes.
         */
        PERMANENTES("0A", "4A", "permanentes"),
    }

    /**
     * Dos bytes a un codigo tipo `P0301`.
     *
     * Los dos bits altos del primer byte dan la letra, los dos siguientes el
     * primer digito, y el resto son digitos hexadecimales tal cual.
     *
     * Devuelve null para `0000`, que NO es un codigo: es el relleno con el que
     * la ECU completa la ultima trama.
     */
    fun decodificar(a: Int, b: Int): Codigo? {
        val alto = a and 0xFF
        val bajo = b and 0xFF
        if (alto == 0 && bajo == 0) return null

        val letra = when ((alto shr 6) and 0x03) {
            0 -> 'P'   // motor y transmision
            1 -> 'C'   // chasis
            2 -> 'B'   // carroceria
            else -> 'U' // red / comunicaciones
        }
        val d1 = (alto shr 4) and 0x03
        val d2 = alto and 0x0F
        val d3 = (bajo shr 4) and 0x0F
        val d4 = bajo and 0x0F
        return Codigo("%c%d%X%X%X".format(letra, d1, d2, d3, d4))
    }

    /**
     * Saca todos los codigos de la respuesta cruda del ELM327.
     *
     * En K-line cada trama fisica lleva como mucho 7 bytes, asi que la
     * respuesta al 03 son TRES codigos por linea, y con mas de tres la ECU
     * manda varias lineas — **cada una repitiendo el `43` delante**. Se
     * concatenan las cargas en el orden de llegada, sin reordenar: en K-line
     * llegan en secuencia y reordenar solo podria estropearlo.
     */
    fun leerLista(crudo: String?, tipo: Tipo): List<Codigo> {
        if (crudo.isNullOrBlank()) return emptyList()

        val cuerpo = StringBuilder()
        for (linea in crudo.lines()) {
            val hex = linea.uppercase().filter { it.isDigit() || it in 'A'..'F' }
            if (hex.isEmpty()) continue
            if (esRuido(hex)) continue

            // ANCLADO al principio. Ver la nota de arriba sobre P0143.
            //
            // Con los encabezados apagados (ATH0, que es lo que usa el
            // proyecto) la linea buena EMPIEZA por el prefijo. Si algun dia se
            // encienden, habria que saltar los tres bytes del encabezado — y
            // eso seria un cambio explicito aqui, no un indexOf que "ya lo
            // encuentra" y de paso encuentra lo que no debe.
            if (!hex.startsWith(tipo.prefijo)) continue

            var resto = hex.substring(tipo.prefijo.length)
            if (resto.length % 2 != 0) resto = resto.dropLast(1)
            cuerpo.append(resto)
        }

        val bytes = cuerpo.toString()
        val salida = mutableListOf<Codigo>()
        var i = 0
        while (i + 3 < bytes.length) {
            val a = bytes.substring(i, i + 2).toIntOrNull(16)
            val b = bytes.substring(i + 2, i + 4).toIntOrNull(16)
            i += 4
            if (a == null || b == null) continue
            decodificar(a, b)?.let { salida += it }
        }
        // Sin duplicados: una ECU puede repetir el mismo codigo entre tramas
        // si se pregunta a caballo de un ciclo de diagnostico.
        return salida.distinctBy { it.texto }
    }

    /**
     * Lineas que NO son datos.
     *
     * `NO DATA` merece una mencion aparte: cuando el carro esta SANO, muchas
     * ECU de esta epoca sencillamente no contestan al 03 y el ELM327 escupe
     * eso al agotar su plazo. Tratarlo como fallo de comunicacion es el error
     * clasico — le dices al dueño que el dongle no sirve cuando lo que pasa es
     * que su carro no tiene averias.
     */
    fun esRuido(hex: String): Boolean {
        if (hex.isEmpty()) return true
        // Estas palabras, ya filtradas a solo [0-9A-F], dejan restos
        // reconocibles: NODATA -> "NODATA" pierde N,O,T; queda "DA". Se
        // comparan contra el texto ORIGINAL antes de filtrar seria mejor,
        // pero aqui basta con descartar lo que no empieza por el prefijo.
        return hex.length < 4
    }

    /**
     * ¿La respuesta dice explicitamente que NO hay codigos?
     *
     * Dos formas legitimas y hay que aguantar las dos:
     * - `43000000000000` — la ECU contesta "cero codigos". Lo tipico en Honda.
     * - `NO DATA` — la ECU no contesta al 03 porque no tiene nada que decir.
     */
    fun sinCodigos(crudo: String?): Boolean {
        if (crudo == null) return false
        // Sin respuesta no se puede afirmar que no haya codigos: solo que no
        // se sabe. Esta guarda es la que impide el falso "SIN AVERIAS".
        if (!huboRespuesta(crudo)) return false
        val limpio = crudo.uppercase()
        if (limpio.contains("NO DATA") || limpio.contains("NODATA")) return true
        return leerLista(crudo, Tipo.GUARDADOS).isEmpty() &&
            leerLista(crudo, Tipo.PENDIENTES).isEmpty() &&
            leerLista(crudo, Tipo.PERMANENTES).isEmpty()
    }

    /**
     * ¿La ECU llego a CONTESTAR algo?
     *
     * Una respuesta de verdad es una linea que empieza por `4` —el modo de
     * peticion mas 0x40— con al menos un byte detras, o un `NO DATA`
     * explicito. Todo lo demas es el adaptador hablando solo.
     *
     * Esta funcion existe por un fallo que se midio con el carro apagado: al
     * pedir los PIDs, el ELM327 contesto `SEARCHING...` a secas. Esa palabra
     * no estaba en ninguna lista de errores y ninguna linea empezaba por
     * `43`, asi que la lista de codigos salia vacia, nadie detectaba fallo, y
     * la pantalla habria dicho **SIN AVERIAS**.
     *
     * Decirle al dueño que su carro esta sano sin haberle hablado es el peor
     * fallo posible en una pantalla de averias. Por eso ahora se exige
     * confirmacion POSITIVA de que hubo respuesta, en vez de dar por sano
     * todo lo que no suene a error conocido.
     */
    fun huboRespuesta(crudo: String?): Boolean {
        if (crudo.isNullOrBlank()) return false
        if (crudo.uppercase().replace(" ", "").contains("NODATA")) return true
        return crudo.lines().any { linea ->
            val hex = linea.uppercase().filter { it.isDigit() || it in 'A'..'F' }
            hex.length >= 4 && hex.startsWith("4")
        }
    }

    /**
     * ¿La respuesta es un fallo y no un "no hay nada"?
     *
     * Distinguirlo importa: lo primero es un problema del adaptador que hay
     * que enseñar, lo segundo es la buena noticia de que el carro esta sano.
     *
     * Son fallo las dos cosas: un error conocido del bus, **y tambien el
     * silencio**. Ver [huboRespuesta].
     */
    fun esFalloDeEnlace(crudo: String?): Boolean {
        val t = crudo?.uppercase() ?: return true
        if (t.contains("UNABLE TO CONNECT") ||
            t.contains("BUS ERROR") ||
            t.contains("CAN ERROR") ||
            t.contains("BUS BUSY") ||
            t.contains("FB ERROR") ||
            t.contains("DATA ERROR") ||
            t.contains("STOPPED") ||
            t.contains("ERROR")
        ) return true
        return !huboRespuesta(crudo)
    }
}
