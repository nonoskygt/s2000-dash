package com.nonosky.carsoc.ui.lienzo

/**
 * EL CONTRATO DE DATOS del tablero, escrito una sola vez.
 *
 * ## Que es esto
 *
 * Son **exactamente** los campos que `TableroActivity.Puente.estado()` mete en
 * su JSON, con los mismos nombres y en el mismo orden. La variante HTML los lee
 * con `d.vivSoc`, `d.nevT`, `d.ll0psi`…; la variante Canvas los lee de aqui. Un
 * solo contrato para las dos pantallas: si mañana entra un dato nuevo, se añade
 * en el puente y en esta clase, y las dos variantes lo ven.
 *
 * ## Por que TODO es anulable, y por que ninguno tiene un valor "por defecto"
 * ## que no sea null
 *
 * Es la regla dura del proyecto llevada al tipo: **un valor ausente o rancio es
 * `null`, jamas 0**. Un cero y un "no lo se" significan cosas opuestas —"0.0 V"
 * en un carro es una bateria muerta, y "no lo se" es que todavia no ha hablado
 * el BMS—. Confundirlos ya costo caro una vez aqui: MEZCLA llego a pintar
 * "+3 %" en verde sumando un ajuste que faltaba.
 *
 * Con todos los campos `T?` e inicializados a `null`, el tipo obliga: no se
 * puede construir un [DatosTablero] "vacio" que mienta, y ningun pintor puede
 * olvidarse de tratar el hueco, porque el compilador se lo recuerda. Lo que se
 * pinta cuando llega null es `Pincel.SIN_DATO` y color `Pincel.APAGADO`.
 *
 * ## Quien decide si un dato esta rancio
 *
 * **El puente, no el pintor.** `estado()` compara la marca de tiempo de cada
 * lectura contra `Carro.motor.staleAfterMs` y traduce lo viejo a null
 * antes de que salga de ahi. Los pintores de la variante Canvas heredan esa
 * disciplina gratis: si les llega un numero, es fresco; si les llega null, se
 * pinta el hueco. Ningun pintor vuelve a mirar relojes.
 *
 * ## Aviso a quien venga detras
 *
 * Esta clase la creo el pintor de **nevera y cabecera** porque no existia
 * todavia; el motor (`Reparto`, `Pincel`) no la traia. **No la dupliquen**: si
 * a su seccion le falta un campo, añadanlo aqui, que los nombres estan copiados
 * del JSON y los tipos verificados contra las clases de origen
 * (`BateriaState`, `VehicleState`, `TpmsDecoder`, `Alpicool.Estado`,
 * `Mantenimiento`).
 *
 * Es un `data class` inmutable a proposito: se construye uno por vuelta de
 * lectura y se le pasa a los pintores. Nadie lo modifica a media pintada.
 */
data class DatosTablero(

    // ---------- banco de vivienda ----------
    /** Estado de carga, en por ciento. */
    val vivSoc: Int? = null,
    val vivV: Float? = null,
    val vivA: Float? = null,
    /** Vatios con SIGNO: positivo entra energia al banco, negativo sale. */
    val vivW: Int? = null,
    /** Temperatura de celdas. */
    val vivT: Int? = null,
    /** Autonomia a este consumo. Texto porque es aproximada ("~14"). */
    val vivH: String? = null,
    /** Rotulo y MAC salen del banco, NO del tablero: la tarjeta no puede
     *  volver a decir que es una bateria que no es. */
    val vivNom: String? = null,
    val vivMac: String? = null,
    val arrNom: String? = null,
    val arrMac: String? = null,

    // ---------- deducidos del banco de vivienda ----------
    /** Estado del cargador DC-DC, inferido del signo de la corriente y de las
     *  rpm. Va DENTRO del recinto de deducido (`Pincel.recintoDeducido`). */
    val dcdc: String? = null,
    /** Consumo del inversor, inferido. Tambien deducido. */
    val inversorW: Int? = null,

    // ---------- banco de arranque ----------
    val arrSoc: Int? = null,
    val arrV: Float? = null,
    val arrA: Float? = null,
    val arrW: Int? = null,
    val arrT: Int? = null,

    // ---------- nevera ----------
    /** Temperatura de dentro, medida. */
    val nevT: Int? = null,
    /** Consigna seleccionada. */
    val nevSet: Int? = null,
    /** Voltaje de ENTRADA a la nevera. */
    val nevV: Float? = null,
    val nevEco: Boolean? = null,
    /** "Encendida" / "Apagada" / null. Texto, tal cual lo manda el puente. */
    val nevOn: String? = null,
    /** "En marcha" / "Parado" / null. DEDUCIDO: el protocolo no lo dice. */
    val nevComp: String? = null,
    /** Extremos del carril termico: los dicta la nevera, no una constante. */
    val nevMin: Int? = null,
    val nevMax: Int? = null,

    // ---------- motor ----------
    val agua: Int? = null,
    val rpm: Int? = null,
    val aire: Int? = null,
    val carga: Int? = null,
    val avance: Int? = null,
    val mapPsi: Float? = null,
    /** Suma de los DOS ajustes de combustible, o null si falta uno. */
    val trim: Int? = null,
    /** Mezcla real. Null mientras no se confirme que la ECU da el PID 0134. */
    val afr: Float? = null,
    /** DEDUCIDO de rpm + carga con histeresis. */
    val vtec: Boolean? = null,

    // ---------- limites del motor de ESTE carro ----------
    // Fijos, del perfil del motor: el tacometro los dibuja sin traer
    // numeros escritos en el HTML. Escala, VTEC, zona de cambio, roja y corte.
    val rpmMax: Int? = null,
    val rpmVtec: Int? = null,
    val rpmCambio: Int? = null,
    val rpmRoja: Int? = null,
    val rpmCorte: Int? = null,

    // ---------- llantas, en orden DI, DD, TI, TD ----------
    // `llNal` es la alerta de esa rueda con los umbrales de Ajustes:
    // "baja", "alta" o "caliente", o null si nada. `llNbaja` se queda porque
    // hay tableros que solo saben de presion baja; dice lo mismo que
    // `llNal == "baja"`.
    val ll0psi: Float? = null,
    val ll0t: Int? = null,
    val ll0baja: Boolean? = null,
    val ll0al: String? = null,
    val ll1psi: Float? = null,
    val ll1t: Int? = null,
    val ll1baja: Boolean? = null,
    val ll1al: String? = null,
    val ll2psi: Float? = null,
    val ll2t: Int? = null,
    val ll2baja: Boolean? = null,
    val ll2al: String? = null,
    val ll3psi: Float? = null,
    val ll3t: Int? = null,
    val ll3baja: Boolean? = null,
    val ll3al: String? = null,

    // ---------- aceite y radio ----------
    val acePct: Int? = null,
    val aceKm: Int? = null,
    val aceH: Int? = null,
    /** Temperatura del SoC del radio. La misma que gobierna el ritmo de
     *  repintado por `Termometro.msEntreCuadros()`. */
    val radioC: Int? = null,

    // ---------- luz de averia ----------
    /** Solo si el 0101 llego fresco. Null es "no lo se", NUNCA "sin averia":
     *  esa es la respuesta tranquilizadora y falsa. */
    val mil: Boolean? = null,
    val codigos: Int? = null,

    // ---------- puntos de enlace ----------
    // No son adorno. Si muere el receptor de las llantas, las cuatro presiones
    // se quedan congeladas en su ultimo valor bueno y siguen pareciendo
    // correctas: el punto es lo unico que lo delata.
    val okViv: Boolean? = null,
    val okArr: Boolean? = null,
    val okNev: Boolean? = null,
    val okTpms: Boolean? = null,
    val okObd: Boolean? = null,
) {
    companion object {
        /**
         * Lo que se pinta antes de la primera lectura: todo en hueco.
         *
         * Existe para que la vista tenga algo que dibujar en el primer cuadro
         * sin inventarse ceros, y para que las pruebas de pantalla partan de
         * "no se nada" en vez de de un tablero a medio rellenar.
         */
        val VACIO = DatosTablero()
    }

    // ---- acceso a las llantas POR INDICE ------------------------------
    //
    // Los cuatro campos sueltos (ll0psi, ll1psi...) son comodos para el
    // JSON y horribles para dibujar cuatro casillas en un bucle. Estos
    // helpers existen para que el pintor no tenga un `when` de cuatro
    // ramas repetido tres veces.
    //
    // Orden: 0 delantera izquierda, 1 delantera derecha,
    //        2 trasera izquierda,   3 trasera derecha.

    /** Presion de la rueda [i], o null si no hay lectura. */
    fun psiDe(i: Int): Float? = when (i) {
        0 -> ll0psi; 1 -> ll1psi; 2 -> ll2psi; 3 -> ll3psi; else -> null
    }

    /** Temperatura de la rueda [i], o null. */
    fun tempDe(i: Int): Int? = when (i) {
        0 -> ll0t; 1 -> ll1t; 2 -> ll2t; 3 -> ll3t; else -> null
    }

    /**
     * ¿Esa rueda esta baja?
     *
     * Devuelve false ante la duda, no null: una alarma solo se enciende
     * cuando hay dato que la respalde. Encenderla porque no sabemos seria
     * exactamente al reves de la regla del proyecto.
     */
    fun bajaDe(i: Int): Boolean = when (i) {
        0 -> ll0baja; 1 -> ll1baja; 2 -> ll2baja; 3 -> ll3baja; else -> null
    } == true

    /** Los indices de las ruedas que estan bajas. Vacio si ninguna. */
    fun ruedasBajas(): List<Int> = (0..3).filter { bajaDe(it) }

    /** La alerta de la rueda [i] ("baja", "alta", "caliente"), o null. */
    fun alertaDe(i: Int): String? = when (i) {
        0 -> ll0al; 1 -> ll1al; 2 -> ll2al; 3 -> ll3al; else -> null
    }
}

/** Rotulos cortos de rueda, en el mismo orden que los indices. */
val RUEDAS = listOf("DI", "DD", "TI", "TD")
