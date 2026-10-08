package com.nonosky.carsoc.ui.lienzo

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.nonosky.carsoc.Carro
import com.nonosky.carsoc.tpms.Escalas
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * LLANTAS Y ACEITE de la variante Canvas.
 *
 * Replica las dos tarjetas del tablero HTML —`section.card` de LLANTAS y la
 * de ACEITE— con las reglas del encargo:
 *
 * - Las cuatro ruedas en rejilla, en ORDEN DE LECTURA: arriba-izquierda es la
 *   delantera izquierda. La posicion en pantalla es parte del dato.
 * - Presion y temperatura **al mismo tamaño**, por peticion del dueño: en una
 *   casa rodante una llanta caliente dice tanto como una baja, y la
 *   temperatura delata un freno pegado o un roce antes que la presion.
 * - El rotulo (DI/DD/TI/TD) a la DERECHA, sobre el dibujo de la rueda, con el
 *   taco encendido en la esquina que le toca: el dibujo tambien dice cual es.
 * - La vida del aceite con su barra y los dos contadores, km y horas, porque
 *   manda el que antes se agote.
 *
 * ## Ninguna medida sale de la pantalla
 *
 * Esta funcion recibe una [Caja] y NO sabe cuanto mide el radio. Todo —cajas,
 * margenes, cuerpo de letra, grosores— sale de esa caja y de sus hijas. Ese es
 * el arreglo del defecto que rompio el tablero viejo, donde las
 * columnas salian del ancho y la letra del alto: al cambiar de 1280x480 a
 * 1024x600 la columna se estrecho un 20 % mientras la letra crecio un 25 % y
 * los numeros se pisaron. Aqui estrechar la casilla ENCOGE la letra, porque es
 * la misma medida.
 *
 * ## Y ninguna asigna memoria por cuadro
 *
 * [Reparto] fabrica listas, asi que el reparto se hace UNA vez y se guarda en
 * [TrazadoLlantas]; mientras la caja no cambie, `pintar` solo lee. Los `Paint`,
 * el [RectF] y las metricas se crean al cargar la clase. Hasta el texto de los
 * numeros se guarda: se vuelve a formatear solo cuando el valor cambia, que en
 * un tablero que repinta a 5 cuadros por segundo son cuatro cadenas por segundo
 * en vez de cuarenta.
 *
 * ## Quien grita
 *
 * Una sola alerta puede gritar, y en esta seccion es **el aviso del rotulo**,
 * que nombra la rueda baja y parpadea entre oxido y ocre —nunca se apaga del
 * todo: una alerta que desaparece medio segundo se puede perder—. La casilla
 * de la rueda se pinta en oxido pero QUIETA, y el aceite no parpadea jamas: un
 * aceite gastado no es una urgencia a 100 km/h. La marca de "no cabe" tampoco
 * parpadea, que es un defecto de pintado y no una averia del carro.
 *
 * ## Hilo
 *
 * Se llama desde `onDraw`, o sea desde el hilo de la interfaz, y solo desde
 * ahi: el trazado y las cadenas guardadas son estado mutable sin candado.
 */
object PintaLlantas {

    /** Donde va cada cosa. Se recalcula solo cuando cambia la caja. */
    private val trazado = TrazadoLlantas()

    /**
     * El pincel de reserva, para quien llame sin pasar el suyo.
     *
     * Lo normal es que la vista tenga uno y se lo pase a todos sus pintores;
     * este existe para que la seccion se pueda dibujar sola —en una prueba de
     * pantalla, por ejemplo— sin montar media vista.
     */
    private val pincelPropio = Pincel()

    // --- Pinceles propios, todos preasignados -------------------------------
    // Los de [Pincel] son privados y ademas no cubren lo de aqui: el dibujo de
    // la rueda, el fondo de la casilla y el aviso del rotulo.

    private val negrita = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** El rotulo de esquina: DI/DD/TI/TD. Espaciado, como en el HTML. */
    private val marcaRueda = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = negrita
        letterSpacing = 0.20f
    }

    /** Letra menuda: etiquetas, el aviso, la nota del pie. */
    private val menudo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = negrita
        letterSpacing = 0.14f
    }

    private val rect = RectF()
    private val metricas = Paint.FontMetrics()

    // --- Cadenas guardadas --------------------------------------------------
    // Formatear un numero fabrica una cadena, y eso es asignar memoria. No se
    // puede evitar del todo —`drawText` quiere un String— pero si se puede
    // hacer una sola vez por CAMBIO de valor en vez de una por cuadro.

    private const val RANURAS = 11
    private const val SIN_VALOR = Int.MIN_VALUE

    private val textos = arrayOfNulls<String>(RANURAS)
    private val crudos = IntArray(RANURAS)

    private var avisoTexto: String? = null
    private var avisoCodigo = -1

    // --- La puerta ----------------------------------------------------------

    /**
     * Pinta la seccion entera dentro de [caja].
     *
     * @param ahora reloj comun del tablero, en milisegundos. Gobierna el
     *   parpadeo del aviso; pasando el MISMO valor a todos los pintores, todo
     *   lo que parpadea en la pantalla parpadea a la vez, que es lo que hace
     *   que se lea como un aviso y no como ruido.
     */
    fun pintar(canvas: Canvas, caja: Caja, d: DatosTablero, ahora: Long) {
        pintar(canvas, caja, d, ahora, pincelPropio)
    }

    /** La misma, con el pincel de la vista. */
    fun pintar(canvas: Canvas, caja: Caja, d: DatosTablero, ahora: Long, pincel: Pincel) {
        if (!caja.valida) return

        trazado.reparte(caja)
        if (!trazado.valido) {
            // No cabe ni repartiendo. Se ve, no se esconde: pintar encima de
            // cajas invalidas es exactamente como se rompio el tablero viejo.
            pincel.marcaDeQueNoCabe(canvas, caja)
            return
        }

        if (trazado.hayLlantas) pintarLlantas(canvas, d, ahora, pincel)
        pintarAceite(canvas, d, pincel)
    }

    /**
     * Que rueda hay bajo ese punto, o -1.
     *
     * Existe para el calibrador: el dedo sostenido sobre una casilla la abre.
     * El blanco es la CASILLA ENTERA, no el dibujo de la llanta, porque el
     * dibujo mide una fraccion de ella y en movimiento no se acierta.
     *
     * Devuelve -1 mientras el reparto no sea valido: sin cajas buenas, decir
     * "esta es la DI" seria inventarse una posicion, y quien sostenga el dedo
     * abriria el calibrador de una rueda al azar.
     */
    fun ruedaEn(x: Float, y: Float): Int {
        if (!trazado.valido || !trazado.hayLlantas) return -1
        for (i in 0 until 4) if (trazado.casilla[i].contiene(x, y)) return i
        return -1
    }

    // --- Llantas ------------------------------------------------------------

    private fun pintarLlantas(canvas: Canvas, d: DatosTablero, ahora: Long, pincel: Pincel) {
        tarjeta(canvas, trazado.tarjetaLlantas)

        // ⚠️ Un receptor muerto NO vacia los ultimos valores: se quedan
        // congelados y siguen pareciendo correctos. Por eso, con el enlace
        // caido, las cuatro lecturas se tratan como ausentes. Solo cuando el
        // servicio dice `false`; si no dice nada (null) no se sabe, y borrar
        // datos buenos por una sospecha tambien seria mentir.
        val receptorVivo = d.okTpms != false

        var enAlerta = 0
        var primera = -1
        var tipos = 0
        for (i in 0..3) {
            val tipo = if (receptorVivo) alertaDe(d, i) else SIN_ALERTA
            if (tipo != SIN_ALERTA) {
                enAlerta++
                if (primera < 0) primera = i
                tipos = tipos or (1 shl tipo)
            }
        }

        val hayAviso = enAlerta > 0 || !receptorVivo
        val cajaTitulo = if (hayAviso) trazado.tituloCorto else trazado.tituloLlantas
        pincel.tituloDeSeccion(canvas, cajaTitulo, "LLANTAS", Pincel.ARENA)
        if (hayAviso) {
            val tipoPrimera = if (primera >= 0) alertaDe(d, primera) else SIN_ALERTA
            pintarAviso(canvas, receptorVivo, enAlerta, primera, tipoPrimera, tipos, ahora, pincel)
        }

        for (i in 0..3) pintarCasilla(canvas, i, d, receptorVivo, pincel)
    }

    /**
     * El aviso del rotulo: un punto, el nombre de la rueda y lo que le pasa.
     *
     * Solo existe cuando hay algo que decir. Un aviso permanente es un aviso
     * que se ignora — y con dos o mas ruedas en alerta nombrarlas todas no
     * cabe, asi que se cuentan: "3 LLANTAS BAJAS" manda a mirar las cuatro,
     * que es lo que hay que hacer.
     */
    private fun pintarAviso(
        canvas: Canvas,
        receptorVivo: Boolean,
        enAlerta: Int,
        primera: Int,
        tipoPrimera: Int,
        tipos: Int,
        ahora: Long,
        pincel: Pincel,
    ) {
        val caja = trazado.aviso
        if (!caja.valida) return

        // Sin receptor no hay alarma: no se sabe. Se dice apagado y quieto.
        val color = when {
            !receptorVivo -> Pincel.APAGADO
            Latido.parpadeo(ahora) -> Pincel.OXIDO
            else -> Pincel.OCRE
        }
        val rotuloAviso = if (!receptorVivo) "SIN RECEPTOR"
        else textoDeAviso(enAlerta, primera, tipoPrimera, tipos)

        relleno.color = color
        val radio = caja.alto * 0.16f
        canvas.drawCircle(caja.x0 + radio, caja.cy, radio, relleno)

        menudo.color = color
        if (!texto(canvas, trazado.avisoTextoCaja, rotuloAviso, menudo, 0.62f, centrado = false)) {
            // Ni cortado cabe: mejor un aspa que un aviso a medias, que se
            // leeria como el nombre de otra rueda.
            pincel.marcaDeQueNoCabe(canvas, caja)
        }
    }

    /**
     * Una esquina del carro.
     *
     * Dos numeros del mismo tamaño a la izquierda, el rotulo y el dibujo a la
     * derecha. Con una alerta, la casilla entera cambia de piel —fondo
     * tintado, borde y barra de oxido—, se pone en oxido EL NUMERO que la
     * dispara (la presion si va baja o alta, la temperatura si va caliente) y
     * se le abre abajo una banda para la palabra. La banda esta repartida de
     * antemano: cambiar de estado no recalcula nada, solo elige cual de los
     * dos trazados se usa.
     */
    private fun pintarCasilla(
        canvas: Canvas,
        i: Int,
        d: DatosTablero,
        receptorVivo: Boolean,
        pincel: Pincel,
    ) {
        val celda = trazado.casilla[i]
        if (!celda.valida) return

        val psi = if (receptorVivo) psiDe(d, i) else null
        val temp = if (receptorVivo) tempDe(d, i) else null
        val tipo = if (receptorVivo) alertaDe(d, i) else SIN_ALERTA
        val baja = tipo != SIN_ALERTA
        val k = i * 2 + (if (baja) 1 else 0)

        // --- la piel de la casilla ---
        val radio = celda.menor * 0.055f
        val fino = max(1f, celda.menor * 0.02f)
        rect.set(celda.x0, celda.y0, celda.x1, celda.y1)
        relleno.color = if (baja) FONDO_ALARMA else FONDO_CASILLA
        canvas.drawRoundRect(rect, radio, radio, relleno)
        rect.inset(fino * 0.5f, fino * 0.5f)
        trazo.color = if (baja) Pincel.OXIDO else Pincel.LINEA
        trazo.strokeWidth = fino
        canvas.drawRoundRect(rect, radio, radio, trazo)
        if (baja) {
            // La barra del canto izquierdo, como el `box-shadow: inset` del
            // HTML: se ve de reojo sin leer un solo numero.
            relleno.color = Pincel.OXIDO
            canvas.drawRect(
                celda.x0, celda.y0 + radio,
                celda.x0 + fino * 2.2f, celda.y1 - radio, relleno,
            )
        }

        // --- los dos numeros, del MISMO tamaño ---
        // `cifraGrande` mide, encoge y marca sola si no cabe. Un "––" apagado
        // cuando no hay lectura; nunca un cero, que en una llanta significa
        // reventon.
        pincel.cifraGrande(
            canvas, trazado.psi[k],
            entero(i, psi?.roundToInt()), "PSI",
            colorDePresion(psi, tipo == ALERTA_BAJA || tipo == ALERTA_ALTA),
        )
        pincel.cifraGrande(
            canvas, trazado.temp[k],
            entero(4 + i, temp), "°C",
            colorDeTemperatura(temp, tipo == ALERTA_CALIENTE),
        )

        // --- el rotulo y el dibujo ---
        val colorMarca = if (baja) Pincel.OXIDO else Pincel.ARENA
        marcaRueda.color = colorMarca
        if (!texto(canvas, trazado.marca[k], ESQUINAS[i], marcaRueda, 0.60f, true)) {
            pincel.marcaDeQueNoCabe(canvas, celda)
        }
        dibujarRueda(canvas, trazado.dibujo[k], i, colorMarca)

        // --- la palabra, solo cuando grita ---
        if (baja) {
            menudo.color = Pincel.OXIDO
            if (!texto(canvas, trazado.bandera[i], PALABRA[tipo], menudo, 0.66f, true)) {
                pincel.marcaDeQueNoCabe(canvas, celda)
            }
        }
    }

    /**
     * El dibujo de la rueda, con el taco encendido en SU esquina.
     *
     * Es el mismo dibujo del HTML (un `viewBox` de 18x28) escalado a la caja
     * que le toque, manteniendo la proporcion y centrado. No se estira: una
     * rueda ovalada se lee como un fallo de pintado.
     */
    private fun dibujarRueda(canvas: Canvas, caja: Caja, esquina: Int, color: Int) {
        if (!caja.valida) return
        val e = min(caja.ancho / VB_ANCHO, caja.alto / VB_ALTO)
        if (e <= 0f) return
        val ox = caja.cx - VB_ANCHO * 0.5f * e
        val oy = caja.cy - VB_ALTO * 0.5f * e
        val pluma = max(1f, e * 1.1f)

        trazo.color = color
        trazo.strokeWidth = pluma
        trazo.alpha = 115
        rect.set(ox + 3.4f * e, oy + 1.4f * e, ox + 14.6f * e, oy + 26.6f * e)
        canvas.drawRoundRect(rect, 2.4f * e, 2.4f * e, trazo)
        trazo.alpha = 77
        canvas.drawLine(ox + 5.6f * e, oy + 8.6f * e, ox + 12.4f * e, oy + 8.6f * e, trazo)
        canvas.drawLine(ox + 5.6f * e, oy + 19.4f * e, ox + 12.4f * e, oy + 19.4f * e, trazo)
        trazo.alpha = 255

        relleno.color = color
        for (t in 0..3) {
            relleno.alpha = if (t == esquina) 255 else 56
            rect.set(
                ox + TACO_X[t] * e, oy + TACO_Y[t] * e,
                ox + (TACO_X[t] + 3f) * e, oy + (TACO_Y[t] + 5.2f) * e,
            )
            canvas.drawRoundRect(rect, e, e, relleno)
        }
        relleno.alpha = 255
    }

    // --- Aceite -------------------------------------------------------------

    /**
     * La vida del aceite: el porcentaje, lo que falta por kilometros, la barra
     * y lo que falta por horas.
     *
     * Los DOS contadores porque manda el que antes se agote: un motor que pasa
     * la vida ralentizando en un campamento gasta aceite sin sumar kilometros,
     * y con solo el odometro llegaria al cambio tarde.
     *
     * Nada de esto parpadea nunca. El aceite se cambia el sabado, no en la
     * curva; robarle el parpadeo a la llanta baja seria cambiar una urgencia
     * por un recado.
     */
    private fun pintarAceite(canvas: Canvas, d: DatosTablero, pincel: Pincel) {
        tarjeta(canvas, trazado.tarjetaAceite)
        pincel.tituloDeSeccion(canvas, trazado.tituloAceite, "ACEITE", Pincel.OCRE)

        val pct = d.acePct
        val colorVida = colorDeVida(pct)

        pincel.cifraGrande(canvas, trazado.vida, entero(8, pct), "%", colorVida)

        menudo.color = Pincel.APAGADO
        texto(canvas, trazado.faltanEtiqueta, "FALTAN", menudo, 0.74f, centrado = false)
        pincel.cifraGrande(
            canvas, trazado.faltanValor,
            entero(9, d.aceKm), "km",
            if (d.aceKm == null) Pincel.APAGADO else Pincel.TINTA,
        )

        // null NO es cero: sin ancla de odometro la barra se queda vacia del
        // todo, que es distinto de un deposito de vida agotado.
        pincel.barra(
            canvas, trazado.barra,
            pct?.let { (it / 100f).coerceIn(0f, 1f) },
            if (colorVida == Pincel.TINTA) Pincel.OCRE else colorVida,
        )

        pincel.filaGrande(
            canvas, trazado.horas,
            "POR HORAS", entero(10, d.aceH), "h",
            if (d.aceH == null) Pincel.APAGADO else Pincel.TINTA,
        )

        // La nota es lo UNICO de esta seccion que puede desaparecer sin marca:
        // no lleva un dato dentro. Antes que pintarla ilegible, no se pinta.
        if (trazado.nota.valida && trazado.nota.alto >= MINIMO_NOTA_PX) {
            menudo.color = Pincel.APAGADO
            texto(canvas, trazado.nota, "MANDA EL QUE ANTES SE AGOTE", menudo, 0.62f, false)
        }
    }

    // --- Color por umbral ---------------------------------------------------

    /**
     * El color de una presion.
     *
     * Sin lectura, apagado. Fuera de escala plausible, ocre: el numero se
     * enseña igual —esconder una anomalia real es peor— pero avisando de que
     * no hay que creerselo. Con alerta de baja o alta, oxido y quieto; quien
     * grita es el aviso del rotulo. Los limites son los de Ajustes, y los
     * aplica el servicio (`AlertasLlantas`): aqui solo llega el veredicto.
     */
    private fun colorDePresion(psi: Float?, alerta: Boolean): Int = when {
        psi == null -> Pincel.APAGADO
        psi !in Escalas.PSI_PLAUSIBLE -> Pincel.OCRE
        alerta -> Pincel.OXIDO
        else -> Pincel.TINTA
    }

    /**
     * El color de una temperatura de llanta.
     *
     * Solo cambia con SU alerta, no con la de presion: si las dos cosas se
     * pintaran de oxido a la vez no se sabria cual de las dos manda.
     */
    private fun colorDeTemperatura(c: Int?, alerta: Boolean): Int = when {
        c == null -> Pincel.APAGADO
        alerta -> Pincel.OXIDO
        else -> Pincel.TINTA
    }

    private fun colorDeVida(pct: Int?): Int = when {
        pct == null -> Pincel.APAGADO
        pct <= 0 -> Pincel.OXIDO
        pct <= AVISO_VIDA_PCT -> Pincel.OCRE
        else -> Pincel.TINTA
    }

    /**
     * La alerta de esta rueda, como indice de [PALABRA], o [SIN_ALERTA].
     *
     * Manda lo que diga el servicio, que la calcula con la presion calibrada
     * y los limites de Ajustes. Si no dice nada no hay alerta: aqui no se
     * deduce nada por cuenta propia, que es como dos pantallas acaban
     * contradiciendose. Un `when` sobre el texto no asigna memoria.
     */
    private fun alertaDe(d: DatosTablero, i: Int): Int = when (d.alertaDe(i)) {
        "baja" -> ALERTA_BAJA
        "alta" -> ALERTA_ALTA
        "caliente" -> ALERTA_CALIENTE
        else -> SIN_ALERTA
    }

    // --- Las cuatro ruedas, por indice --------------------------------------
    //
    // El contrato las nombra `ll0psi`, `ll1psi`… porque asi viajan en el JSON.
    // Estos tres `when` las vuelven un bucle: cuatro copias de la misma linea
    // son cuatro sitios donde equivocarse de rueda. Un `when` sobre un Int no
    // asigna memoria, asi que se puede llamar desde el camino de dibujo.
    //
    // Viven aqui y no en [DatosTablero] a proposito: ese fichero lo comparten
    // todos los pintores de la variante Canvas y se esta escribiendo a la vez
    // que este. Si algun dia se mudan alla, que sea de una pieza.

    private fun psiDe(d: DatosTablero, i: Int): Float? = when (i) {
        0 -> d.ll0psi; 1 -> d.ll1psi; 2 -> d.ll2psi; 3 -> d.ll3psi; else -> null
    }

    private fun tempDe(d: DatosTablero, i: Int): Int? = when (i) {
        0 -> d.ll0t; 1 -> d.ll1t; 2 -> d.ll2t; 3 -> d.ll3t; else -> null
    }

    /** 500 ms encendido, 500 ms apagado, con el reloj comun del tablero. */

    // --- Cocina de pintado --------------------------------------------------

    /** El fondo de una tarjeta, con su esquina de hoja de mapa. */
    private fun tarjeta(canvas: Canvas, caja: Caja) {
        if (!caja.valida) return
        val radio = max(2f, caja.menor * 0.012f)
        val fino = max(1f, caja.menor * 0.006f)

        rect.set(caja.x0, caja.y0, caja.x1, caja.y1)
        relleno.color = Pincel.TARJETA
        canvas.drawRoundRect(rect, radio, radio, relleno)

        rect.inset(fino * 0.5f, fino * 0.5f)
        trazo.color = Pincel.LINEA
        trazo.strokeWidth = fino
        canvas.drawRoundRect(rect, radio, radio, trazo)

        // La esquina de hoja de mapa. Es lo que hace que la tarjeta se lea
        // como una region de un mapa y no como una caja mas.
        val lado = caja.menor * 0.045f
        trazo.color = Pincel.LINEA2
        canvas.drawLine(caja.x1 - fino, caja.y1 - lado, caja.x1 - fino, caja.y1 - fino, trazo)
        canvas.drawLine(caja.x1 - lado, caja.y1 - fino, caja.x1 - fino, caja.y1 - fino, trazo)
    }

    /**
     * Texto medido, encogido y —si hace falta— CORTADO dentro de su caja.
     *
     * La misma disciplina que [Pincel]: el cuerpo sale de `caja.alto`, se mide
     * con `measureText` y se cede hasta el suelo; por debajo del suelo se
     * corta con `breakText`, que no fabrica subcadenas. Devuelve false si no
     * cupo entero, para que quien llama decida si eso merece un aspa.
     */
    private fun texto(
        canvas: Canvas,
        caja: Caja,
        s: String,
        p: Paint,
        fraccion: Float,
        centrado: Boolean,
    ): Boolean {
        if (!caja.valida || s.isEmpty() || caja.ancho <= 0f) return false

        val ideal = caja.alto * fraccion
        p.textSize = ideal
        var letras = s.length
        val medido = p.measureText(s)
        if (medido > caja.ancho) {
            val proporcional = ideal * (caja.ancho / medido)
            if (proporcional >= ideal * Pincel.SUELO) {
                p.textSize = proporcional
            } else {
                p.textSize = ideal * Pincel.SUELO
                letras = p.breakText(s, true, caja.ancho, null)
            }
        }
        if (letras <= 0) return false

        p.getFontMetrics(metricas)
        val y = caja.cy - (metricas.ascent + metricas.descent) * 0.5f
        p.textAlign = if (centrado) Paint.Align.CENTER else Paint.Align.LEFT
        canvas.drawText(s, 0, letras, if (centrado) caja.cx else caja.x0, y, p)
        return letras == s.length
    }

    // --- Cadenas guardadas --------------------------------------------------

    /**
     * El texto de un entero, formateado UNA vez por cambio de valor.
     *
     * `null` es "––", nunca "0". La separacion de miles va con espacio, igual
     * que en la variante HTML: "2 270" se lee de reojo y "2270" no.
     */
    private fun entero(ranura: Int, v: Int?): String {
        val clave = v ?: SIN_VALOR
        val guardado = textos[ranura]
        if (guardado != null && crudos[ranura] == clave) return guardado
        val nuevo = if (v == null) Pincel.SIN_DATO else miles(v)
        textos[ranura] = nuevo
        crudos[ranura] = clave
        return nuevo
    }

    private fun miles(v: Int): String {
        val digitos = abs(v.toLong()).toString()
        if (digitos.length <= 3) return v.toString()
        val sb = StringBuilder(digitos.length + 4)
        if (v < 0) sb.append('-')
        val cabeza = digitos.length % 3
        var i = 0
        if (cabeza > 0) {
            sb.append(digitos, 0, cabeza)
            i = cabeza
        }
        while (i < digitos.length) {
            if (i > 0) sb.append(' ')
            sb.append(digitos, i, i + 3)
            i += 3
        }
        return sb.toString()
    }

    /**
     * "DI BAJA", "DD CALIENTE"; con varias, "2 LLANTAS ALTAS" si es la misma
     * alerta en todas, o "3 LLANTAS EN ALERTA" si son distintas.
     */
    private fun textoDeAviso(enAlerta: Int, primera: Int, tipoPrimera: Int, tipos: Int): String {
        val codigo = ((enAlerta * 8 + primera + 1) * 8 + tipoPrimera + 1) * 16 + tipos
        val guardado = avisoTexto
        if (guardado != null && avisoCodigo == codigo) return guardado
        val nuevo = when {
            enAlerta == 1 && primera in 0..3 && tipoPrimera >= 0 ->
                ESQUINAS[primera] + " " + PALABRA[tipoPrimera]
            Integer.bitCount(tipos) == 1 && tipoPrimera >= 0 ->
                "" + enAlerta + " LLANTAS " + PLURAL[tipoPrimera]
            else -> "" + enAlerta + " LLANTAS EN ALERTA"
        }
        avisoTexto = nuevo
        avisoCodigo = codigo
        return nuevo
    }

    // --- Numeros de esta seccion --------------------------------------------

    /**
     * Los nombres cortos, en el orden en que el servicio manda las ruedas:
     * delantera izquierda, delantera derecha, trasera izquierda, trasera
     * derecha.
     *
     * El orden ES el dato: es el mismo de `Reparto.rejilla`, asi que el indice
     * cae en la esquina de pantalla que le toca. Si alguien lo cambia sin
     * querer, el tablero avisaria de la rueda equivocada — y por eso hay una
     * prueba que lo fija.
     */
    private val ESQUINAS = arrayOf("DI", "DD", "TI", "TD")

    /** Fondo de casilla en calma: el rebaje del HTML sobre la tarjeta. */
    private const val FONDO_CASILLA = 0xFF202722.toInt()

    /** Fondo de casilla en alarma: el oxido al 13 % sobre la tarjeta. */
    private const val FONDO_ALARMA = 0xFF362C21.toInt()

    // Las alertas de una rueda, como indices de las palabras de abajo.
    private const val SIN_ALERTA = -1
    private const val ALERTA_BAJA = 0
    private const val ALERTA_ALTA = 1
    private const val ALERTA_CALIENTE = 2

    private val PALABRA = arrayOf("BAJA", "ALTA", "CALIENTE")
    private val PLURAL = arrayOf("BAJAS", "ALTAS", "CALIENTES")

    /** Vida de aceite por debajo de la cual la tarjeta se pone ocre. */
    private const val AVISO_VIDA_PCT = 10

    /** Por debajo de esto la nota del pie no se lee, y no se pinta. */
    private const val MINIMO_NOTA_PX = 7f

    // El `viewBox` del dibujo de la rueda, tal cual el del HTML.
    private const val VB_ANCHO = 18f
    private const val VB_ALTO = 28f

    /** Las cuatro esquinas de los tacos, en el orden DI, DD, TI, TD. */
    private val TACO_X = floatArrayOf(0.5f, 14.5f, 0.5f, 14.5f)
    private val TACO_Y = floatArrayOf(4.6f, 4.6f, 18.2f, 18.2f)
}

/**
 * DONDE va cada cosa de la seccion de llantas y aceite.
 *
 * Vive aparte del pintado por dos razones, y las dos importan:
 *
 * 1. **Se calcula una vez.** [Reparto] devuelve listas nuevas, o sea que
 *    reparte asignando memoria. Aqui se reparte al cambiar la caja —una vez al
 *    arrancar y una por rotacion de pantalla— y se guarda en campos. `onDraw`
 *    solo lee.
 * 2. **Es codigo puro.** Ni un import de Android, asi que las tres pantallas
 *    del encargo —1280x480, 1024x600 y 800x480— se comprueban en la JVM, en un
 *    segundo, sin encender un radio. La vez pasada el tablero se rompio al
 *    cambiar de pantalla y nadie pudo verlo hasta que llego una foto desde el
 *    carro.
 *
 * Las cajas son publicas para que las lean el pintor y las pruebas. Nadie las
 * escribe desde fuera.
 */
class TrazadoLlantas {

    /** La caja del ultimo reparto. Si no cambia, no se reparte otra vez. */
    var seccion: Caja = Caja.NADA
        private set

    /** ¿Salio todo? Si es false, el pintor marca la seccion y no pinta. */
    var valido: Boolean = false
        private set

    /** Este carro lleva TPMS. Si no, el aceite se queda con la caja entera. */
    var hayLlantas: Boolean = false
        private set

    /** Aire entre cajas, derivado de la caja. Nunca un numero de pixeles fijo. */
    var aire: Float = 0f
        private set

    var tarjetaLlantas: Caja = Caja.NADA; private set
    var tarjetaAceite: Caja = Caja.NADA; private set

    /** El rotulo cuando NO hay aviso: la franja entera. */
    var tituloLlantas: Caja = Caja.NADA; private set

    /** El rotulo cuando SI lo hay: deja sitio al aviso, no se pisan. */
    var tituloCorto: Caja = Caja.NADA; private set

    /** El aviso entero, punto incluido. */
    var aviso: Caja = Caja.NADA; private set

    /** Lo que queda del aviso a la derecha del punto: donde va el texto. */
    var avisoTextoCaja: Caja = Caja.NADA; private set

    /** Las cuatro casillas en orden de lectura: DI, DD, TI, TD. */
    val casilla = Array(4) { Caja.NADA }

    // Dos trazados por casilla, indexados `rueda * 2 + variante`, con la
    // variante 0 en calma y la 1 con la banda de la palabra "BAJA" abierta
    // abajo. Estan repartidos los dos de antemano: cuando la rueda se pone a
    // gritar no se recalcula nada, solo se elige el otro juego de cajas.
    val psi = Array(8) { Caja.NADA }
    val temp = Array(8) { Caja.NADA }
    val marca = Array(8) { Caja.NADA }
    val dibujo = Array(8) { Caja.NADA }

    /** La banda de la palabra, una por rueda. Solo se usa en la variante 1. */
    val bandera = Array(4) { Caja.NADA }

    var tituloAceite: Caja = Caja.NADA; private set
    var vida: Caja = Caja.NADA; private set
    var faltanEtiqueta: Caja = Caja.NADA; private set
    var faltanValor: Caja = Caja.NADA; private set
    var barra: Caja = Caja.NADA; private set
    var horas: Caja = Caja.NADA; private set
    var nota: Caja = Caja.NADA; private set

    /**
     * Reparte [caja] entera. No hace nada si ya estaba repartida.
     *
     * Se llama desde `onSizeChanged` o, como aqui, desde la primera pasada de
     * pintado; lo que NO se puede es repartir por cuadro.
     */
    fun reparte(caja: Caja) {
        if (caja == seccion) return
        seccion = caja
        valido = false
        vaciar()
        if (!caja.valida) return

        // El aire sale de la caja, con topes para que no desaparezca en una
        // pantalla chica ni se coma la tarjeta en una grande.
        aire = (caja.menor * 0.028f).coerceIn(3f, 12f)
        hayLlantas = Carro.perfil.tieneTpms

        if (!hayLlantas) {
            // Un carro sin TPMS no tiene por que enseñar cuatro casillas
            // vacias: el aceite se queda con todo.
            tarjetaAceite = caja
        } else {
            // El presupuesto del HTML, tal cual: la tarjeta de llantas mide
            // 296 y la de aceite 216. Son PESOS, no pixeles — en otra
            // pantalla dan otros tamaños en la misma proporcion.
            //
            // Y si la seccion llega apaisada —porque la vista la coloque en
            // una banda ancha— se reparte en columnas en vez de en filas. Una
            // tarjeta de 900x120 partida en dos filas no cabe de ninguna
            // manera, y encogerla no es la respuesta: girarla si.
            val enColumnas = caja.ancho > caja.alto * 1.5f
            val partes =
                if (enColumnas) Reparto.columnas(caja, PESOS_SECCION, aire)
                else Reparto.filas(caja, PESOS_SECCION, aire)
            tarjetaLlantas = partes[0]
            tarjetaAceite = partes[1]
        }

        if (hayLlantas && !repartirLlantas()) return
        if (!repartirAceite()) return
        valido = true
    }

    private fun repartirLlantas(): Boolean {
        val dentro = tarjetaLlantas.margenRelativo(MARGEN_TARJETA)
        if (!dentro.valida) return false

        val filas = Reparto.filas(dentro, PESOS_LLANTAS, aire * 0.3f)
        tituloLlantas = filas[0]
        val rejilla = filas[1]
        if (!tituloLlantas.valida || !rejilla.valida) return false

        // El aviso se lleva un tercio largo de la franja del rotulo, y el
        // rotulo se queda con el resto. Repartidos, no superpuestos: asi
        // "LLANTAS" y "TD BAJA" no pueden pisarse ni en la pantalla mas
        // estrecha.
        val franja = Reparto.columnas(tituloLlantas, PESOS_ROTULO, aire * 0.5f)
        tituloCorto = franja[0]
        aviso = franja[1]
        if (!tituloCorto.valida || !aviso.valida) return false
        avisoTextoCaja = Caja(aviso.x0 + aviso.alto * 0.45f, aviso.y0, aviso.x1, aviso.y1)
        if (!avisoTextoCaja.valida) return false

        val celdas = Reparto.rejilla(rejilla, 2, 2, aire * 0.85f)
        if (celdas.size < 4) return false
        for (i in 0..3) {
            val celda = celdas[i]
            casilla[i] = celda
            if (!celda.valida) return false

            val cuerpo = celda.margenRelativo(MARGEN_CASILLA)
            if (!cuerpo.valida) return false

            // Variante en calma: el cuerpo entero.
            if (!repartirCasilla(i, 0, cuerpo)) return false

            // Variante con aviso: se le abre una banda abajo para la palabra.
            val partido = Reparto.filas(cuerpo, PESOS_CON_BANDERA, aire * 0.2f)
            if (!partido[0].valida || !partido[1].valida) return false
            bandera[i] = partido[1]
            if (!repartirCasilla(i, 1, partido[0])) return false
        }
        return true
    }

    private fun repartirCasilla(i: Int, variante: Int, cuerpo: Caja): Boolean {
        val columnas = Reparto.columnas(cuerpo, PESOS_CASILLA, aire * 0.6f)
        val numeros = Reparto.filas(columnas[0], PESOS_IGUALES, aire * 0.2f)
        val derecha = Reparto.filas(columnas[1], PESOS_MARCA, aire * 0.2f)
        val k = i * 2 + variante
        psi[k] = numeros[0]
        temp[k] = numeros[1]
        marca[k] = derecha[0]
        dibujo[k] = derecha[1]
        return psi[k].valida && temp[k].valida && marca[k].valida && dibujo[k].valida
    }

    private fun repartirAceite(): Boolean {
        val dentro = tarjetaAceite.margenRelativo(MARGEN_TARJETA)
        if (!dentro.valida) return false

        val filas = Reparto.filas(dentro, PESOS_ACEITE, aire * 0.3f)
        tituloAceite = filas[0]
        barra = filas[2]
        horas = filas[3]
        // La nota puede no caber sin que eso sea un fallo: es lo unico de la
        // seccion que no lleva un dato dentro.
        nota = filas[4]

        val principal = Reparto.columnas(filas[1], PESOS_ACEITE_ARRIBA, aire)
        vida = principal[0]
        val derecha = Reparto.filas(principal[1], PESOS_FALTAN, 0f)
        faltanEtiqueta = derecha[0]
        faltanValor = derecha[1]

        return tituloAceite.valida && barra.valida && horas.valida &&
            vida.valida && faltanEtiqueta.valida && faltanValor.valida
    }

    private fun vaciar() {
        hayLlantas = false
        tarjetaLlantas = Caja.NADA
        tarjetaAceite = Caja.NADA
        tituloLlantas = Caja.NADA
        tituloCorto = Caja.NADA
        aviso = Caja.NADA
        avisoTextoCaja = Caja.NADA
        for (i in 0..3) {
            casilla[i] = Caja.NADA
            bandera[i] = Caja.NADA
        }
        for (k in 0..7) {
            psi[k] = Caja.NADA
            temp[k] = Caja.NADA
            marca[k] = Caja.NADA
            dibujo[k] = Caja.NADA
        }
        tituloAceite = Caja.NADA
        vida = Caja.NADA
        faltanEtiqueta = Caja.NADA
        faltanValor = Caja.NADA
        barra = Caja.NADA
        horas = Caja.NADA
        nota = Caja.NADA
    }

    private companion object {

        /** Llantas y aceite, con el presupuesto del HTML: 296 y 216. */
        val PESOS_SECCION = floatArrayOf(296f, 216f)

        /** Dentro de la tarjeta de llantas: rotulo 28, rejilla 244. */
        val PESOS_LLANTAS = floatArrayOf(28f, 244f)

        /** La franja del rotulo: nombre de la region y aviso. */
        val PESOS_ROTULO = floatArrayOf(56f, 44f)

        /** La casilla: numeros a la izquierda, rotulo y dibujo a la derecha. */
        val PESOS_CASILLA = floatArrayOf(1f, 0.38f)

        /** Presion y temperatura, del MISMO tamaño. Por eso los pesos son 1. */
        val PESOS_IGUALES = floatArrayOf(1f, 1f)

        /** A la derecha: el rotulo de esquina encima del dibujo. */
        val PESOS_MARCA = floatArrayOf(0.32f, 0.68f)

        /** Con aviso: el cuerpo cede una banda para la palabra "BAJA". */
        val PESOS_CON_BANDERA = floatArrayOf(0.84f, 0.16f)

        /** Aceite: rotulo, fila grande, barra, horas y nota. */
        val PESOS_ACEITE = floatArrayOf(28f, 62f, 18f, 40f, 22f)

        /** La fila grande: el porcentaje y lo que falta. */
        val PESOS_ACEITE_ARRIBA = floatArrayOf(1f, 1.25f)

        /** "FALTAN" encima de los kilometros. */
        val PESOS_FALTAN = floatArrayOf(0.34f, 0.66f)

        /** El `padding` de una tarjeta, en fraccion de su lado corto. */
        const val MARGEN_TARJETA = 0.042f

        /** El de una casilla de rueda. */
        const val MARGEN_CASILLA = 0.06f
    }
}
