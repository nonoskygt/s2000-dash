package com.nonosky.carsoc.ui.lienzo

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Las primitivas de dibujo de la variante Canvas.
 *
 * ## La regla que gobierna este fichero
 *
 * **NINGUNA funcion de aqui recibe el alto de pantalla.** Todas reciben una
 * [Caja] y sacan de ella sus tamaños de letra. Si un bloque mide 300x140, su
 * tipografia sale de esos 140 y de nada mas.
 *
 * Ese es el arreglo del defecto que rompio el tablero viejo: alli
 * las columnas salian del ANCHO (`w / 3`) y la letra del ALTO (`h * 0.125f`),
 * asi que al pasar de 1280x480 a 1024x600 la columna se estrecho un 20 %
 * mientras la letra crecio un 25 %, y "ALTERNADOR 13.8 V" quedo una palabra
 * encima de otra. Con la letra derivada de la caja, estrechar la columna
 * ENCOGE la letra: la relacion no puede desmadrarse porque es la misma
 * medida.
 *
 * ## Y la que la respalda
 *
 * Medir con [Paint.measureText] y ceder. El tablero viejo ya media, pero su
 * suelo de encogimiento —59 % del tamaño ideal— era un suelo MUDO: si al
 * suelo tampoco cabia, pintaba igual y se solapaba. Aqui el suelo sigue
 * existiendo (por debajo no se lee de reojo) pero ya no es el final del
 * camino: por debajo del suelo el texto se CORTA con [Paint.breakText], y si
 * ademas desaparece entero, la fila se marca con [marcaDeQueNoCabe]. Dos
 * textos superpuestos dejan de ser posibles por construccion, no por suerte.
 *
 * ## Sin asignar memoria
 *
 * Pinceles, [RectF] y [Paint.FontMetrics] se crean UNA vez, en el
 * constructor. Nada de esto aloca por cuadro: es un tablero que repinta
 * durante horas en un radio que ya se apago dos veces por calor. Por eso
 * tambien se corta con `drawText(texto, ini, fin, ...)` en vez de con
 * `substring`, que fabricaria una cadena por cuadro y por fila.
 *
 * Se crea uno por vista y se le pasa a los pintores de seccion. No es un
 * `object` global porque no hace falta que lo sea, y un estado mutable
 * compartido entre vistas es una fuga esperando su turno.
 */
class Pincel {

    // --- Pinceles, todos preasignados ---------------------------------------

    private val negrita = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val normal = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

    /** Relleno liso: barras, fondos, marcas. */
    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Trazo: bordes, aspas, carriles. */
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /**
     * El rotulo de seccion. Mayusculas espaciadas, como una etiqueta de mapa.
     */
    private val rotulo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = negrita
        textAlign = Paint.Align.LEFT
        letterSpacing = 0.18f
    }

    /** La palabra que dice DE QUE es el numero. Siempre la que cede. */
    private val etiqueta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = negrita
        textAlign = Paint.Align.LEFT
        letterSpacing = 0.10f
    }

    /**
     * El numero. CIFRAS TABULARES: `tnum` fija el ancho de cada digito, y sin
     * eso un valor que salta de 9 a 10 mueve toda la fila. Un tablero que
     * baila se lee peor de reojo que uno feo.
     */
    private val valor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = negrita
        textAlign = Paint.Align.LEFT
        fontFeatureSettings = "tnum"
        letterSpacing = -0.02f
    }

    /** La unidad, pegada al numero y mas chica. */
    private val unidad = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = normal
        textAlign = Paint.Align.LEFT
    }

    private val metricas = Paint.FontMetrics()
    private val rect = RectF()

    // --- Rotulo de seccion --------------------------------------------------

    /**
     * El titulo de una tarjeta: texto a la izquierda, guia de puntos hasta el
     * borde y una linea fina por debajo.
     *
     * La guia de puntos no es adorno: separa el rotulo de los numeros sin
     * gastar el alto de una franja de color, y hace que la tarjeta se lea
     * como una region de mapa y no como una caja mas.
     *
     * @return false si el rotulo no cupo entero (se corto). Quien quiera que
     *   eso se vea puede llamar a [marcaDeQueNoCabe]; aqui no se marca solo,
     *   porque un titulo recortado sigue siendo legible y marcar por eso seria
     *   gritar por una arruga.
     */
    fun tituloDeSeccion(canvas: Canvas, caja: Caja, texto: String, color: Int): Boolean {
        if (!caja.valida) return false

        val ideal = caja.alto * TITULO
        rotulo.color = color
        val letras = encoger(rotulo, texto, caja.ancho, ideal, ideal * SUELO)
        val yBase = base(rotulo, caja) - caja.alto * 0.10f
        if (letras > 0) canvas.drawText(texto, 0, letras, caja.x0, yBase, rotulo)

        // Guia de puntos desde donde acaba el texto hasta el borde derecho.
        val ancho = rotulo.measureText(texto, 0, letras)
        val desde = caja.x0 + ancho + caja.alto * 0.28f
        if (desde < caja.x1) puntos(canvas, desde, caja.x1, yBase - caja.alto * 0.12f, caja.alto)

        // Linea de base de la region.
        relleno.color = LINEA
        val grosor = grosorFino(caja)
        canvas.drawRect(caja.x0, caja.y1 - grosor, caja.x1, caja.y1, relleno)
        return letras == texto.length
    }

    // --- Fila de dato -------------------------------------------------------

    /**
     * ETIQUETA a la izquierda, NUMERO grande a la derecha, en la misma caja.
     *
     * Es la fila de vigilancia del proyecto: `AGUA 87 °C`, `ALTERNADOR
     * 13.8 V`. El reparto de ancho no es a medias — es por prioridad:
     *
     * 1. El numero se mide primero y se queda con lo que necesita. Es el
     *    dato; la palabra solo dice de que es.
     * 2. La etiqueta cede en lo que sobre: encoge, y si al suelo tampoco
     *    cabe, se CORTA. Nunca se pinta por encima del numero.
     * 3. Si el numero SOLO no cabe en la caja, entonces si encoge el numero
     *    —salir de la caja seria pisar la seccion vecina, que es peor— y la
     *    fila se marca. Es la unica via por la que el numero cede, y deja
     *    huella.
     *
     * Diferencia con el tablero viejo: alli el paso 2 tenia un suelo mudo y
     * al llegar a el pintaba igual, solapado. Este corta.
     *
     * @param unid la unidad, o null si el valor ya la lleva dentro.
     * @return false si hubo que romper el contrato (numero encogido de mas, o
     *   etiqueta desaparecida entera). En ese caso YA se pinto la marca: un
     *   fallo de reparto no puede depender de que el pintor mire el retorno.
     */
    fun filaGrande(
        canvas: Canvas,
        caja: Caja,
        etq: String,
        val_: String,
        unid: String?,
        color: Int,
    ): Boolean {
        if (!caja.valida) return false

        // ⚠️ LA LETRA SALE DE LAS DOS DIMENSIONES DE LA CAJA, NO SOLO DEL ALTO.
        //
        // Esta es la misma piedra que rompio el tablero viejo, y se
        // volvio a pisar aqui: una fila es `etiqueta ... numero unidad` en UNA
        // linea, asi que su ancho manda tanto como su alto. Sacando el tamaño
        // solo del alto, una caja que se estira a lo alto pide una letra que
        // no cabe a lo ancho, y la fila entera se marca como que no cupo.
        //
        // Paso en un tablero sin nevera: su columna del medio es del motor
        // ENTERA, y las celdas pasaron de 150x40 a 150x110. Cinco de seis
        // salieron tachadas de naranja.
        //
        // El tope se eligio MIDIENDO, no por bonito: se subio hasta que dejo
        // de tocar las celdas que ya cabian —las del tablero con nevera, de
        // 110x38, que con 0,20 salian un 7 % mas chicas sin motivo— y se
        // comprobo que seguia salvando las estiradas del tablero sin nevera. Un tope demasiado apretado
        // no rompe nada, pero encoge la letra de un tablero que se mira de
        // reojo, y eso tambien se paga.
        val idealValor = minOf(caja.alto * VALOR_FILA, caja.ancho * VALOR_POR_ANCHO)
        valor.textSize = idealValor
        valor.color = color
        valor.textAlign = Paint.Align.RIGHT

        unidad.textSize = idealValor * UNIDAD_DEL_VALOR
        unidad.color = if (color == APAGADO) APAGADO else ARENA
        unidad.textAlign = Paint.Align.LEFT

        // Los huecos se derivan del NUMERO, no del alto de la caja. Con las
        // proporciones del diseño dan lo mismo que antes (0,62 x 0,16 = 0,10);
        // en una caja estirada acompañan a la letra en vez de abrirse solos.
        val aire = idealValor * 0.16f
        val pegado = idealValor * 0.13f
        var cupo = true

        var unidadVisible = !unid.isNullOrEmpty()
        var anchoUnidad =
            if (unidadVisible) unidad.measureText(unid!!) + pegado else 0f
        // Una unidad que se lleva media fila deja al NUMERO sin sitio, y el
        // numero es el dato. Antes que empujarlo fuera de su caja —donde
        // pisaria a la seccion vecina, que es como se rompio el tablero
        // viejo— se cae la unidad y se marca la fila.
        if (anchoUnidad > caja.ancho * 0.5f) {
            unidadVisible = false
            anchoUnidad = 0f
            cupo = false
        }

        var anchoValor = valor.measureText(val_)
        val sitioDelValor = caja.ancho - anchoUnidad
        if (anchoValor > sitioDelValor && sitioDelValor > 0f) {
            // Ultimo recurso. Documentado arriba: el numero cede antes que
            // salirse de su caja, pero no en silencio.
            val factor = sitioDelValor / anchoValor
            valor.textSize = idealValor * factor
            anchoValor = valor.measureText(val_)
            if (factor < TOLERANCIA_VALOR) cupo = false
        }

        val yBase = base(valor, caja)
        val xValor = caja.x1 - anchoUnidad
        canvas.drawText(val_, xValor, yBase, valor)
        if (unidadVisible) {
            canvas.drawText(unid!!, xValor + pegado, yBase, unidad)
        }

        // Lo que quede, para la palabra. Encoge CON el numero: si el numero
        // tuvo que ceder por ancho, la etiqueta cede en la misma proporcion y
        // la fila conserva su jerarquia en vez de quedar con la palabra mas
        // grande que el dato.
        val idealEtiqueta = idealValor * (ETIQUETA_FILA / VALOR_FILA)
        etiqueta.color = APAGADO
        etiqueta.textAlign = Paint.Align.LEFT
        val disponible = xValor - anchoValor - aire - caja.x0
        val letras = encoger(etiqueta, etq, disponible, idealEtiqueta, idealEtiqueta * SUELO)
        if (letras > 0) {
            canvas.drawText(etq, 0, letras, caja.x0, base(etiqueta, caja), etiqueta)
        }
        // Una fila sin etiqueta es un numero sin nombre: eso no es ceder, es
        // perder el dato. Cuenta como no caber.
        if (etq.isNotEmpty() && letras == 0) cupo = false

        if (!cupo) marcaDeQueNoCabe(canvas, caja)
        return cupo
    }

    // --- Cifra sola ---------------------------------------------------------

    /**
     * UN numero grande centrado en su caja, con la unidad pegada.
     *
     * Para lo que manda en una tarjeta —el porcentaje del banco, la presion
     * de una llanta— donde la etiqueta ya la dio el rotulo de la seccion y
     * repetirla al lado del numero solo gastaria ancho.
     *
     * Aqui no hay nada que ceda antes que el numero, asi que el numero encoge
     * lo que haga falta para no salirse. Si tiene que encoger por debajo de
     * [TOLERANCIA_VALOR] la caja es demasiado pequeña para el dato que le
     * pusieron y eso se marca.
     *
     * @return false si hubo que encoger de mas (y entonces ya se marco).
     */
    fun cifraGrande(
        canvas: Canvas,
        caja: Caja,
        val_: String,
        unid: String?,
        color: Int,
    ): Boolean {
        if (!caja.valida) return false

        var tam = caja.alto * CIFRA
        valor.textSize = tam
        valor.color = color
        valor.textAlign = Paint.Align.LEFT
        unidad.textSize = tam * UNIDAD_DEL_VALOR
        unidad.color = if (color == APAGADO) APAGADO else ARENA
        unidad.textAlign = Paint.Align.LEFT

        val separacion = caja.alto * 0.06f
        var anchoValor = valor.measureText(val_)
        var anchoUnidad = if (unid.isNullOrEmpty()) 0f else unidad.measureText(unid) + separacion
        var cupo = true

        val total = anchoValor + anchoUnidad
        if (total > caja.ancho && total > 0f) {
            val factor = caja.ancho / total
            tam *= factor
            valor.textSize = tam
            unidad.textSize = tam * UNIDAD_DEL_VALOR
            anchoValor = valor.measureText(val_)
            anchoUnidad = if (unid.isNullOrEmpty()) 0f else unidad.measureText(unid) + separacion
            cupo = factor >= TOLERANCIA_VALOR
        }

        val yBase = base(valor, caja)
        val x = caja.cx - (anchoValor + anchoUnidad) * 0.5f
        canvas.drawText(val_, x, yBase, valor)
        if (!unid.isNullOrEmpty()) {
            canvas.drawText(unid, x + anchoValor + separacion, yBase, unidad)
        }

        if (!cupo) marcaDeQueNoCabe(canvas, caja)
        return cupo
    }

    // --- Barra --------------------------------------------------------------

    /**
     * Barra de nivel con marcas cada 10 %, ocupando la caja entera.
     *
     * [fraccion] va de 0 a 1. **Null es "no lo se" y se pinta distinto de
     * cero**, que es la regla dura del proyecto llevada a un grafico: un
     * carril vacio con un cero medido dentro no puede parecerse a un carril
     * del que no sabemos nada. Cero deja un tope minimo encendido; null no
     * enciende nada.
     */
    fun barra(canvas: Canvas, caja: Caja, fraccion: Float?, color: Int) {
        if (!caja.valida) return
        // El radio nunca pasa de la mitad del lado corto: por encima, la
        // esquina redondeada se come la barra y deja una lenteja.
        val radio = caja.menor * 0.5f

        rect.set(caja.x0, caja.y0, caja.x1, caja.y1)
        relleno.color = HUECO
        canvas.drawRoundRect(rect, radio, radio, relleno)

        if (fraccion != null && fraccion.isFinite()) {
            val f = if (fraccion < 0f) 0f else if (fraccion > 1f) 1f else fraccion
            // Un cero MEDIDO deja tope: sin el, "0 %" y "no lo se" se pintan
            // igual, y son cosas opuestas.
            val ancho = maxOf(caja.ancho * f, caja.alto * 0.35f)
            rect.set(caja.x0, caja.y0, caja.x0 + ancho, caja.y1)
            relleno.color = color
            val r = minOf(radio, ancho * 0.5f)
            canvas.drawRoundRect(rect, r, r, relleno)
        }

        // Marcas de decima parte: convierten la mancha en una escala.
        relleno.color = FONDO
        val grosor = grosorFino(caja)
        val paso = caja.ancho / 10f
        var i = 1
        while (i < 10) {
            val x = caja.x0 + paso * i
            canvas.drawRect(x, caja.y0, x + grosor, caja.y1, relleno)
            i++
        }

        rect.set(caja.x0, caja.y0, caja.x1, caja.y1)
        trazo.color = LINEA
        trazo.strokeWidth = grosor
        canvas.drawRoundRect(rect, radio, radio, trazo)
    }

    // --- Deducido -----------------------------------------------------------

    /**
     * El recinto de trazo discontinuo que envuelve lo DEDUCIDO.
     *
     * Regla 6 del encargo, y no es una floritura: el compresor de la nevera,
     * el DC-DC, el inversor y el VTEC no los mide nadie — se infieren. Un
     * numero inferido pintado igual que uno medido convierte una suposicion
     * en un hecho a los ojos del dueño, y este proyecto ya pago ese error una
     * vez.
     *
     * Devuelve la caja INTERIOR, ya con su aire, para pintar dentro.
     */
    fun recintoDeducido(canvas: Canvas, caja: Caja): Caja {
        if (!caja.valida) return Caja.NADA
        val grosor = grosorFino(caja)
        relleno.color = LINEA2
        val trozo = caja.menor * 0.05f
        rayas(canvas, caja.x0, caja.x1, caja.y0, grosor, trozo)
        rayas(canvas, caja.x0, caja.x1, caja.y1 - grosor, grosor, trozo)
        rayasVerticales(canvas, caja.y0, caja.y1, caja.x0, grosor, trozo)
        rayasVerticales(canvas, caja.y0, caja.y1, caja.x1 - grosor, grosor, trozo)
        return caja.margen(caja.menor * 0.08f)
    }

    // --- La marca de fallo --------------------------------------------------

    /**
     * EL FALLO SE VE. Borde y aspa sobre la caja que no pudo pintarse bien.
     *
     * Hoy, en la variante HTML, un texto que se sale queda tapado por el
     * vecino y nadie se entera hasta que alguien manda una foto desde el
     * carro. Un aspa en su casilla dice "esto de aqui esta mal repartido" a
     * medio metro, sin leer.
     *
     * Va en OXIDO —el color de alarma— pero **no parpadea**: no compite con
     * la unica alerta que puede gritar. Es un defecto de pintado, no una
     * averia del carro.
     *
     * Sobre una caja sin area no se puede pintar nada; en ese caso el pintor
     * tiene que marcar la caja PADRE, que es la que si existe.
     */
    fun marcaDeQueNoCabe(canvas: Canvas, caja: Caja) {
        if (!caja.valida) return
        val grosor = maxOf(2f, caja.menor * 0.035f)
        trazo.color = OXIDO
        trazo.strokeWidth = grosor
        val m = grosor * 0.5f
        rect.set(caja.x0 + m, caja.y0 + m, caja.x1 - m, caja.y1 - m)
        canvas.drawRect(rect, trazo)
        canvas.drawLine(rect.left, rect.top, rect.right, rect.bottom, trazo)
        canvas.drawLine(rect.right, rect.top, rect.left, rect.bottom, trazo)
    }

    // --- Cocina -------------------------------------------------------------

    /**
     * La linea base que CENTRA verticalmente el texto en la caja.
     *
     * Con las metricas de la fuente, no a ojo con un `+ h * 0.012f`. El ojo
     * de quien escribio el numero magico estaba mirando una pantalla concreta.
     */
    private fun base(p: Paint, caja: Caja): Float {
        p.getFontMetrics(metricas)
        return caja.cy - (metricas.ascent + metricas.descent) * 0.5f
    }

    /**
     * Mide, encoge y —si hace falta— CORTA. Devuelve cuantas letras caben.
     *
     * El suelo existe porque por debajo de el no se lee de reojo, y una
     * etiqueta ilegible es tan inutil como una tapada. Lo que NO puede pasar
     * es que el suelo autorice a salirse: llegado el suelo, lo que sobra se
     * corta. Ahi estaba el defecto del tablero viejo.
     */
    private fun encoger(
        p: Paint,
        texto: String,
        anchoMax: Float,
        ideal: Float,
        suelo: Float,
    ): Int {
        if (texto.isEmpty()) return 0
        p.textSize = ideal
        if (anchoMax <= 0f) return 0
        val medido = p.measureText(texto)
        if (medido <= anchoMax) return texto.length
        val proporcional = ideal * (anchoMax / medido)
        if (proporcional >= suelo) {
            p.textSize = proporcional
            return texto.length
        }
        p.textSize = suelo
        // `breakText` no aloca y devuelve cuantos caracteres caben; se pinta
        // con `drawText(texto, 0, n, ...)`, sin fabricar una subcadena.
        return p.breakText(texto, true, anchoMax, null)
    }

    /** Grosor de hairline que sigue siendo visible en cualquier pantalla. */
    private fun grosorFino(caja: Caja): Float = maxOf(1f, caja.menor * 0.03f)

    /** Guia de puntos horizontal, sin asignar un PathEffect por cuadro. */
    private fun puntos(canvas: Canvas, desde: Float, hasta: Float, y: Float, alto: Float) {
        relleno.color = PUNTO
        val lado = maxOf(1f, alto * 0.05f)
        val paso = lado * 3f
        var x = desde
        var guarda = 0
        while (x < hasta && guarda < 400) {
            canvas.drawRect(x, y, x + lado, y + lado, relleno)
            x += paso
            guarda++
        }
    }

    private fun rayas(canvas: Canvas, x0: Float, x1: Float, y: Float, grosor: Float, trozo: Float) {
        var x = x0
        var guarda = 0
        while (x < x1 && guarda < 400) {
            canvas.drawRect(x, y, minOf(x + trozo, x1), y + grosor, relleno)
            x += trozo * 2f
            guarda++
        }
    }

    private fun rayasVerticales(
        canvas: Canvas, y0: Float, y1: Float, x: Float, grosor: Float, trozo: Float,
    ) {
        var y = y0
        var guarda = 0
        while (y < y1 && guarda < 400) {
            canvas.drawRect(x, y, x + grosor, minOf(y + trozo, y1), relleno)
            y += trozo * 2f
            guarda++
        }
    }

    companion object {

        /**
         * Lo que se pinta cuando NO hay dato. Nunca un cero.
         *
         * Guion largo doble, el mismo que usa la variante HTML, para que las
         * dos pantallas del mismo carro digan "no lo se" con la misma cara.
         */
        const val SIN_DATO = "––"

        // --- Tipografia, siempre en FRACCION DE LA CAJA ---------------------
        //
        // Ni una sola de estas constantes se multiplica por el alto de
        // pantalla. Se multiplican por `caja.alto`, y ahi esta la diferencia
        // entre relativo y responsive.
        //
        // Los valores salen de medir el tablero HTML: fila `.er` de 40 px con
        // el numero a 26 (0,65) y la etiqueta a 8,5 (0,21); unidad `i` a 11
        // sobre 26 (0,42); cifra `.big` a 76 dentro de un bloque de 112
        // (0,68); rotulo `.rl` a 10,5 en una franja de 23 (0,45).

        /** Tamaño del numero de una fila, en fraccion del alto de su caja. */
        const val VALOR_FILA = 0.62f

        /** Tamaño de la etiqueta de una fila. */
        const val ETIQUETA_FILA = 0.24f

        /**
         * Tope del numero de una fila POR EL ANCHO de su caja.
         *
         * Existe porque una fila es una sola linea: `etiqueta ... numero
         * unidad`. Sin este tope, una caja estirada a lo alto pide una letra
         * que no cabe a lo ancho y la fila se marca entera. El valor sale de
         * medir las celdas reales de los dos carros: por encima de este tope
         * empieza a encoger filas que cabian de sobra.
         */
        const val VALOR_POR_ANCHO = 0.26f

        /** Tamaño de una cifra que va sola y manda en su caja. */
        const val CIFRA = 0.68f

        /** Tamaño del rotulo de seccion. */
        const val TITULO = 0.45f

        /** La unidad, en fraccion del numero al que acompaña. */
        const val UNIDAD_DEL_VALOR = 0.40f

        /**
         * Suelo de encogimiento: la mitad del tamaño ideal.
         *
         * Por debajo no se lee de reojo, asi que aqui se deja de encoger y se
         * empieza a CORTAR. Que exista un suelo no es lo que rompio el
         * tablero viejo; lo que lo rompio fue que al llegar al suelo pintaba
         * igual, saliendose.
         */
        const val SUELO = 0.5f

        /**
         * Cuanto puede encoger un NUMERO antes de que eso sea un fallo.
         *
         * Un margen del 20 % cubre la diferencia honesta entre `-- V` y
         * `+1 234 W` en la misma casilla. Por debajo, la caja es demasiado
         * pequeña para lo que le pusieron y hay que verlo.
         */
        const val TOLERANCIA_VALOR = 0.80f

        // --- Paleta ---------------------------------------------------------
        //
        // La del tablero HTML (direccion de arte "topografico"),
        // que es la que hay que replicar. Vive aqui de momento porque el
        // paquete `ui/tema` que anuncia `Carro.perfil.tema` todavia no
        // existe; cuando exista, estas constantes se mudan alli y el Pincel
        // las recibe. Ninguna primitiva las usa para el COLOR DEL DATO — ese
        // lo pone siempre quien llama, que es el unico que sabe si el valor
        // esta en rango.

        /** Fondo de pagina. */
        const val FONDO = 0xFF131715.toInt()

        /** Fondo de tarjeta. */
        const val TARJETA = 0xFF1A201C.toInt()

        /** Rebaje / rail. */
        const val REBAJE = 0xFF232A25.toInt()

        /** Hueco muerto: el fondo de una barra vacia. */
        const val HUECO = 0xFF0D100E.toInt()

        const val LINEA = 0xFF2A312B.toInt()
        const val LINEA2 = 0xFF3A4239.toInt()
        const val PUNTO = 0xFF333B34.toInt()

        /** Tinta principal. 13,2:1 sobre tarjeta. */
        const val TINTA = 0xFFEDE4D3.toInt()

        /** Tinta secundaria: unidades. 8,1:1. */
        const val ARENA = 0xFFBEB39A.toInt()

        /** Apagado: etiquetas, y TODO valor ausente o rancio. 5,4:1. */
        const val APAGADO = 0xFF8E968A.toInt()

        /** Ocre: aceite, rotulos. */
        const val OCRE = 0xFFE0A84A.toInt()

        /** Musgo: litio, lo vivo. */
        const val MUSGO = 0xFF9CBE7A.toInt()

        /** Lago: nevera, lo frio. */
        const val LAGO = 0xFF8FB9C4.toInt()

        /** Oxido: UNA sola alarma, y la marca de reparto imposible. */
        const val OXIDO = 0xFFED7A45.toInt()
    }
}
