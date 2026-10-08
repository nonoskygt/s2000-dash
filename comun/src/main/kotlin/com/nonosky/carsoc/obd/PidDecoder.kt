package com.nonosky.carsoc.obd

/**
 * Convierte respuestas crudas del ELM327 en magnitudes fisicas.
 *
 * Funciones puras: sin I/O, sin estado, sin excepciones. Toda entrada
 * invalida produce `null`. Esta es la unidad mas facil de probar y ahi vive
 * el grueso de las pruebas — un clon de ELM327 escupe basura constantemente
 * (§10 del diseño) y el parser jamas puede fallar con ninguna de ellas.
 */
object PidDecoder {

    /** PIDs que usa el tablero. */
    const val PID_RPM = "010C"
    const val PID_SPEED = "010D"
    const val PID_LOAD = "0104"
    const val PID_COOLANT = "0105"
    const val PID_IAT = "010F"

    /**
     * Sonda de oxigeno de banda ANCHA, sensor 1 del banco 1.
     *
     * Este es el que sirve para calcular la mezcla de verdad. Hay motores que
     * montan una sonda lineal (LAF), no una de banda estrecha, y una banda estrecha
     * solo sabe decir "rica" o "pobre" — su voltaje salta entre extremos y no
     * se puede convertir en un numero.
     *
     * El PID 0134 devuelve la **relacion de equivalencia** (lambda) en los dos
     * primeros bytes, escalada a 2/65536. Con eso la mezcla sale directa:
     * AFR = lambda * 14.7 para gasolina.
     */
    /**
     * Presion absoluta del colector, en kPa. Un byte, sin escala.
     *
     * En un atmosferico esto es un vacuometro: al ralenti ronda los 30 kPa y
     * a acelerador abierto se acerca a la presion atmosferica (~100 kPa al
     * nivel del mar, menos en altura). Dice cuanto esta pidiendo el motor
     * mucho mejor que el porcentaje de carga calculado.
     */
    /** Ajuste de combustible a CORTO plazo, banco 1. */
    const val PID_TRIM_CORTO = "0106"

    /** Ajuste de combustible a LARGO plazo, banco 1. */
    const val PID_TRIM_LARGO = "0107"

    /** Estado de monitores: luz de averia y cuantos codigos hay guardados. */
    const val PID_ESTADO = "0101"

    const val PID_MAP = "010B"

    /** Posicion del acelerador, 0-100%. Un byte escalado 100/255. */
    const val PID_ACELERADOR = "0111"

    /** Avance de encendido en grados. Un byte: A/2 - 64. */
    const val PID_AVANCE = "010E"

    /**
     * Voltaje de la sonda de oxigeno 1, banco 1.
     *
     * YA NO SE PIDE. Se conserva la constante porque la prueba del reparto la
     * usa para verificar que este PID **no** aparece en la tabla de turnos: es
     * un centinela, no un resto.
     *
     * Se dejo de pedir cuando MEZCLA paso a salir de los ajustes de
     * combustible 0106/0107. La sonda de este carro es de banda ESTRECHA:
     * su voltaje solo dice de que lado de la estequiometrica esta, y sacarle
     * un porcentaje seria inventarlo. Los ajustes si son un porcentaje medido.
     */
    const val PID_O2_V = "0114"

    /**
     * Sonda de banda ANCHA, sensor 1 del banco 1: la mezcla de verdad. Solo
     * se pide si el perfil del carro dice `tieneAfrReal`. Ver [decodeLambda].
     */
    const val PID_LAMBDA = "0134"

    /**
     * Tokens que en `ATRV` significan que no hubo lectura.
     *
     * Ojo: NO se usan para las tramas de PID. Ver [payloadOf] — ahi buscar
     * tokens de error sobre la respuesta entera era justamente el error.
     */
    private val ERROR_TOKENS = listOf(
        "NODATA", "STOPPED", "UNABLETOCONNECT", "BUSERROR",
        "CANERROR", "DATAERROR", "ERROR", "?"
    )

    /**
     * Extrae los bytes de datos de una respuesta a [pid].
     *
     * Se parsea **linea por linea**, y una linea que no sea una trama valida
     * simplemente se ignora. Esto importa mas de lo que parece: un ELM327
     * antepone banners de progreso a la trama buena, dentro de la MISMA
     * respuesta —
     *
     *     BUS INIT: ...OK\r41 0C 1A F8\r\r>
     *     SEARCHING...\r41 0C 1A F8\r\r>
     *
     * — y en ISO 9141-2 la primera peticion de cada conexion SIEMPRE trae
     * el `BUS INIT`. Rechazar la respuesta entera por contener ese texto
     * tiraba la lectura con la que se comprueba que el bus responde, asi que
     * el enlace bueno se declaraba muerto y no se leia un solo dato.
     *
     * No hace falta buscar tokens de error: si no hay trama, no hay muestra.
     * `BUS INIT: ERROR` y `UNABLE TO CONNECT` caen solos por no traer trama.
     *
     * Absorbe ademas el prompt `>`, los espacios (por si `ATS0` no tomo
     * efecto) y el eco del comando (por si `ATE0` no tomo efecto): el eco
     * lleva el modo de peticion `01`, no el de respuesta `41`.
     */
    fun payloadOf(raw: String?, pid: String): ByteArray? {
        if (raw.isNullOrBlank()) return null
        val prefix = responsePrefix(pid) ?: return null

        for (line in raw.lines()) {
            val hex = line.uppercase().filter { it.isDigit() || it in 'A'..'F' }
            if (hex.isEmpty()) continue

            val at = hex.indexOf(prefix)
            if (at < 0) continue

            var body = hex.substring(at + prefix.length)
            // Longitud impar = respuesta truncada a la mitad de un byte.
            if (body.length % 2 != 0) body = body.dropLast(1)
            if (body.isEmpty()) continue

            return ByteArray(body.length / 2) { i ->
                body.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
        return null
    }

    /** `010C` -> `410C`. El modo de respuesta es el de peticion mas 0x40. */
    private fun responsePrefix(pid: String): String? {
        if (pid.length < 4) return null
        val mode = pid.substring(0, 2).toIntOrNull(16) ?: return null
        return "%02X".format(mode + 0x40) + pid.substring(2)
    }

    private fun ByteArray.u(i: Int): Int = this[i].toInt() and 0xFF

    /** RPM = ((A * 256) + B) / 4 */
    fun decodeRpm(raw: String?): Int? {
        val d = payloadOf(raw, PID_RPM) ?: return null
        if (d.size < 2) return null
        val rpm = ((d.u(0) * 256) + d.u(1)) / 4
        return if (rpm in 0..16_383) rpm else null
    }

    /** Velocidad = A, ya en km/h. */
    fun decodeSpeed(raw: String?): Int? {
        val d = payloadOf(raw, PID_SPEED) ?: return null
        if (d.isEmpty()) return null
        return d.u(0)
    }

    /** Refrigerante = A - 40 (°C). */
    fun decodeCoolant(raw: String?): Int? {
        val d = payloadOf(raw, PID_COOLANT) ?: return null
        if (d.isEmpty()) return null
        return d.u(0) - 40
    }

    /** Aire de admision = A - 40 (°C). */
    fun decodeIat(raw: String?): Int? {
        val d = payloadOf(raw, PID_IAT) ?: return null
        if (d.isEmpty()) return null
        return d.u(0) - 40
    }


    /**
     * Que PIDs soporta esta ECU, preguntandoselo a ella.
     *
     * El modo 01 tiene un PID de indice cada 32: `0100` contesta con cuatro
     * bytes cuyos bits dicen si soporta del 0x01 al 0x20, el bit mas alto
     * del primer byte para el 0x01. Si el ultimo bit esta puesto, es que hay
     * otro bloque y se puede preguntar `0120`, y asi.
     *
     * Existe porque el proyecto llevaba tiempo suponiendo lo que soporta la
     * ECU —y suponiendo mal: se gastaron turnos pidiendo el AFR de banda
     * ancha 0134 durante quien sabe cuanto para recibir vacio—. Preguntar
     * cuesta una lectura y zanja la discusion.
     *
     * @param base 0x00 para el bloque 01-20, 0x20 para el 21-40, etc.
     */
    fun soportados(raw: String?, base: Int): List<Int> {
        val bits = mascaraDe(raw, base) ?: return emptyList()
        return (0 until 32).filter { i ->
            (bits shr (31 - i)) and 1 == 1
        }.map { base + it + 1 }
    }

    /**
     * ¿La ECU llegó a DECIR su máscara para este bloque?
     *
     * Es el mismo remedio que [Dtc.huboRespuesta] y por la misma razón: la
     * ausencia de respuesta no es una respuesta. [soportados] devuelve lista
     * vacía en tres situaciones que NO son la misma cosa —la ECU contestó "no
     * soporto nada de este bloque", no contestó nada, o contestó media
     * máscara— y las dos últimas son un fallo de enlace disfrazado de dato.
     * Sin esta función, `/pids` pinta una lista vacía que cualquiera lee como
     * "este carro no mide nada".
     *
     * Se exige confirmación POSITIVA: cuatro bytes enteros detrás del prefijo
     * de ESTE bloque. Y ojo a la diferencia deliberada con los códigos de
     * avería: allí un `NO DATA` sí cuenta como respuesta, porque al modo 03
     * una ECU sana puede callarse por no tener averías. Aquí no: el `0100` es
     * obligatorio en cualquier OBD-II, así que si no lo contesta, el que está
     * mudo es el enlace.
     */
    fun huboMascara(raw: String?, base: Int): Boolean = mascaraDe(raw, base) != null

    /**
     * Hay otro bloque de 32 detrás de este.
     *
     * Ojo al llamarla: `false` significa "no hay más" Y TAMBIÉN "no pude
     * leer". No se le cambia la firma porque quien pregunta ya no depende de
     * ella para saberlo: consulta antes [huboMascara], igual que
     * [Dtc.sinCodigos] consulta [Dtc.huboRespuesta] antes de declarar sano un
     * carro con el que no ha hablado.
     */
    fun hayMasBloques(raw: String?, base: Int): Boolean =
        soportados(raw, base).contains(base + 0x20)

    /**
     * Los 32 bits de la máscara del bloque [base], o `null` si la ECU no la dijo.
     *
     * `null` y `0` son lo que aquí se separa: `0` es la ECU contestando que no
     * soporta nada de este bloque, `null` es que no hubo con quien hablar.
     *
     * No se apoya en [payloadOf] a propósito, por dos motivos:
     *
     * 1. `payloadOf` se queda con la PRIMERA trama y tira el resto. Para una
     *    medida está bien —un régimen es un régimen—, pero la máscara de PIDs
     *    es lo único que la norma manda COMBINAR: si varios módulos contestan
     *    al mismo PID de índice, el conjunto soportado es el OR de TODAS las
     *    máscaras. Hoy solo habla la ECU del motor y no muerde; el día que se
     *    encienda `ATH1` o entre otro módulo, el mapa saldría recortado y en
     *    silencio. Por eso el OR vive aquí y no en `payloadOf`: hacerlo allí
     *    mezclaría lecturas de sensores, y un OR de dos temperaturas es un
     *    número inventado.
     * 2. `payloadOf` no distingue "no había trama" de "la trama venía corta":
     *    devuelve `null` en el primer caso y bytes de menos en el segundo, y
     *    los dos tienen que ser un fallo.
     */
    private fun mascaraDe(raw: String?, base: Int): Int? {
        if (raw.isNullOrBlank()) return null
        val prefijo = responsePrefix("01%02X".format(base)) ?: return null

        var union: Int? = null
        for (linea in raw.lines()) {
            val hex = linea.uppercase().filter { it.isDigit() || it in 'A'..'F' }
            val at = hex.indexOf(prefijo)
            if (at < 0) continue

            val cuerpo = hex.substring(at + prefijo.length)
            // Media máscara es PEOR que ninguna: daría una lista corta con
            // pinta de buena, el tablero dejaría de pedir sensores que el
            // carro sí tiene y no habría un solo síntoma visible. Con menos de
            // cuatro bytes enteros esta línea no aporta nada, ni siquiera a
            // medias.
            if (cuerpo.length < 8) continue

            // Por Long y luego a Int porque `FFFFFFFF` no cabe en un Int con
            // signo: `toIntOrNull(16)` devolvería null justo con la máscara
            // más llena, que es la que más dolería perder.
            val trozo = cuerpo.substring(0, 8).toLongOrNull(16) ?: continue
            union = (union ?: 0) or trozo.toInt()
        }
        return union
    }

    /** Nombre legible de los PIDs del modo 01 que valen la pena. */
    val NOMBRES: Map<Int, String> = mapOf(
        0x01 to "estado de monitores y numero de averias",
        0x03 to "estado del sistema de combustible (lazo abierto/cerrado)",
        0x04 to "carga calculada",
        0x05 to "temperatura del refrigerante",
        0x06 to "ajuste de combustible CORTO plazo, banco 1",
        0x07 to "ajuste de combustible LARGO plazo, banco 1",
        0x08 to "ajuste corto, banco 2",
        0x09 to "ajuste largo, banco 2",
        0x0A to "presion de combustible",
        0x0B to "presion del colector (MAP)",
        0x0C to "revoluciones",
        0x0D to "velocidad",
        0x0E to "avance de encendido",
        0x0F to "temperatura del aire de admision",
        0x10 to "caudal de aire (MAF)",
        0x11 to "posicion del acelerador",
        0x13 to "sondas lambda presentes",
        0x14 to "sonda 1 banco 1: voltaje y ajuste corto",
        0x15 to "sonda 2 banco 1: voltaje y ajuste corto",
        0x1C to "norma OBD a la que responde",
        0x1F to "tiempo funcionando desde el arranque",
        0x21 to "distancia con la luz de averia encendida",
        0x2E to "purga del canister",
        0x2F to "nivel de combustible",
        0x33 to "presion barometrica",
        0x42 to "voltaje del modulo de control",
        0x43 to "carga absoluta",
        0x44 to "relacion aire/combustible mandada",
        0x45 to "posicion relativa del acelerador",
        0x46 to "temperatura ambiente",
        0x5C to "temperatura del aceite del motor",
    )

    /**
     * Ajuste de combustible, en por ciento. `(A - 128) * 100 / 128`.
     *
     * Es lo que la centralita esta corrigiendo sobre la inyeccion base para
     * mantener la mezcla donde quiere. **Cero es perfecto.** Positivo =
     * mete mas gasolina porque lee pobre; negativo = quita porque lee rica.
     *
     * Vale mas que casi cualquier otro dato de esta ECU para un motor viejo,
     * porque delata la averia ANTES de que encienda la luz: una fuga de
     * vacio empuja el ajuste arriba, un inyector sucio tambien, y una sonda
     * muriendose lo vuelve erratico. Por encima de +-10% ya hay algo que
     * mirar; por encima de +-25% la centralita esta al limite de lo que
     * puede corregir y la luz esta a punto de encenderse.
     */
    fun decodeTrim(raw: String?, pid: String): Int? =
        payloadOf(raw, pid)?.takeIf { it.isNotEmpty() }
            ?.let { (((it[0].toInt() and 0xFF) - 128) * 100) / 128 }
            ?.takeIf { it in -100..99 }

    /**
     * Luz de averia encendida, y cuantos codigos hay guardados.
     *
     * Byte A del `0101`: el bit alto es la lampara, los siete de abajo son
     * el numero de codigos. Se lee entero de una vez porque van en el mismo
     * byte y separarlos costaria dos peticiones para el mismo dato.
     */
    fun decodeMil(raw: String?): Pair<Boolean, Int>? =
        payloadOf(raw, PID_ESTADO)?.takeIf { it.isNotEmpty() }
            ?.let { d ->
                val a = d[0].toInt() and 0xFF
                Pair((a and 0x80) != 0, a and 0x7F)
            }

    /** Presion del colector en kPa. Un byte directo. */
    fun decodeMap(raw: String?): Int? =
        payloadOf(raw, PID_MAP)?.takeIf { it.isNotEmpty() }
            ?.let { it[0].toInt() and 0xFF }
            ?.takeIf { it in 0..255 }

    /** Acelerador en por ciento. */
    fun decodeAcelerador(raw: String?): Int? =
        payloadOf(raw, PID_ACELERADOR)?.takeIf { it.isNotEmpty() }
            ?.let { ((it[0].toInt() and 0xFF) * 100 / 255) }

    /**
     * Lambda del 0134: los dos primeros bytes, escalados a 2/65536. Los dos
     * siguientes son la corriente de la sonda y no se usan.
     *
     * Medido en el carro: `4134AE837F6F` = 1,363 (soltando el acelerador)
     * y `4134FC137F6F` = 1,969 (corte de inyeccion en retencion). Se acepta
     * de 0 a 2: es todo lo que cabe en la escala del PID, y un corte de
     * inyeccion marca justo el tope — eso es un dato, no un error.
     */
    fun decodeLambda(raw: String?): Float? {
        val d = payloadOf(raw, PID_LAMBDA) ?: return null
        if (d.size < 2) return null
        val l = ((d.u(0) * 256) + d.u(1)) * 2f / 65_536f
        return if (l > 0f && l <= 2f) l else null
    }

    /** Avance de encendido en grados. A/2 - 64. */
    fun decodeAvance(raw: String?): Int? =
        payloadOf(raw, PID_AVANCE)?.takeIf { it.isNotEmpty() }
            ?.let { ((it[0].toInt() and 0xFF) / 2) - 64 }
            ?.takeIf { it in -64..64 }

    fun decodeLoad(raw: String?): Int? {
        val d = payloadOf(raw, PID_LOAD)
        if (d != null && d.isNotEmpty()) return d.u(0) * 100 / 255

        // ESTA ECU CONTESTA EL 0104 SIN EL BYTE DEL PID.
        //
        // Medido en el carro: `0104` devuelve `414B` en vez de `41044B`. El
        // `0100` la declara soportada y todos los demas PIDs contestan con
        // su encabezado completo —`41067A`, `410E88`, `411112`— asi que no es
        // el adaptador comiendose bytes en general: es este PID.
        //
        // La consecuencia era que la carga salia vacia PARA SIEMPRE, y con
        // ella se caia la deteccion del VTEC, que necesita rpm y carga.
        //
        // Se acepta el formato corto solo aqui y con dos guardias: tiene que
        // ser exactamente `41` mas un byte —ni mas ni menos, para no tragarse
        // la respuesta de otro PID que pase cerca— y el resultado tiene que
        // caer en 0..100. Un motor al ralenti da ~29%, que es justo lo que
        // sale de ese `4B`.
        return cargaEnFormatoCorto(raw)
    }

    private fun cargaEnFormatoCorto(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        for (line in raw.lines()) {
            val hex = line.uppercase().filter { it.isDigit() || it in 'A'..'F' }
            if (hex.length != 4 || !hex.startsWith("41")) continue
            val a = hex.substring(2, 4).toIntOrNull(16) ?: continue
            val pct = a * 100 / 255
            if (pct in 0..100) return pct
        }
        return null
    }

    /**
     * Voltaje de `ATRV`, que responde algo como `12.6V`.
     *
     * Lo da el adaptador, no la ECU, asi que no gasta presupuesto de K-line
     * y no lleva encabezado de respuesta que validar.
     */
    fun decodeVoltage(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val compact = raw.uppercase().filter { !it.isWhitespace() && it != '>' }
        if (ERROR_TOKENS.any { compact.contains(it) }) return null
        val number = compact.takeWhile { it.isDigit() || it == '.' }
        val v = number.toFloatOrNull() ?: return null
        // Un carro sano vive entre 11 y 15 V. Fuera de rango es basura.
        return if (v in 6f..20f) v else null
    }
}
