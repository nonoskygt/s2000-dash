package com.nonosky.carsoc.ui.lienzo

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.nonosky.carsoc.Carro

/**
 * LA NEVERA Y LA CABECERA de la variante Canvas.
 *
 * Dos pintores, una sola firma: `(canvas, caja, d, ahora)`. Cada uno recibe SU
 * rectangulo y no sabe nada de la pantalla — ni su ancho, ni su alto, ni cuanto
 * mide el vecino. Es la regla 2 del encargo y el arreglo del defecto que rompio
 * el tablero viejo: alli las columnas salian del ancho y la letra del alto, y
 * al cambiar de 1280x480 a 1024x600 la columna se estrecho un 20 % mientras la
 * letra crecio un 25 %. Aqui estrechar la caja ENCOGE la letra, porque es la
 * misma medida.
 *
 * ## Que se pinta
 *
 * - [pintar] — la tarjeta de la nevera, **solo si [Carro.perfil.tieneNevera]**.
 *   Temperatura de dentro en grande, carril termico con la marca de la consigna
 *   y el punto de la temperatura real, consigna y voltaje de entrada, los cuatro
 *   mandos tocables y el estado.
 * - [cabecera] — nombre del carro, los puntos de enlace por fuente, la luz de
 *   averia con su cuenta de codigos y la temperatura del radio.
 *
 * ## El reparto se mide UNA vez, no por cuadro
 *
 * `Reparto.filas/columnas` devuelven listas nuevas: eso asigna memoria y no
 * puede pasar en `onDraw`. Aqui el reparto se memoriza contra la caja con la
 * que se hizo ([cajaNevera], [cajaCabecera]) y solo se rehace cuando la caja
 * cambia — o sea, en el primer cuadro y en cada `onSizeChanged`. Del segundo
 * cuadro en adelante **no se asigna nada**: ni Cajas, ni listas, ni cadenas.
 *
 * Las cadenas son la trampa menos obvia. `n.toString()` asigna, y un tablero
 * que repinta durante horas no puede fabricar una cadena por numero y por
 * cuadro. Por eso cada numero pasa por un memorizador ([Entero], [Decimal],
 * [Rotulo]) que solo vuelve a formatear **cuando el valor cambia**. Con el radio
 * caliente el tablero va a 1 fps y la nevera se mueve de grado en grado: en la
 * practica no se formatea casi nunca.
 *
 * ## El ritmo lo pone el termometro
 *
 * Este fichero no programa repintados — eso es de la vista— pero existe por
 * eso: la variante HTML repinta con un `setTimeout` de 700 ms fijo, pase lo que
 * pase, y esta baja a 1 fps cuando el radio se calienta porque la vista la
 * gobierna con `Termometro.msEntreCuadros()`. El head unit ya se apago dos
 * veces por calor; media razon de ser de la variante Canvas es esa.
 *
 * ## El unico numero absoluto del fichero, y por que
 *
 * [MINIMO_DEDO] son 44 pixeles. Todo lo demas sale de la caja, pero **un dedo
 * mide lo que mide en un carro que se mueve, no una fraccion de la pantalla**.
 * Escalar el boton con el tablero es como se consigue un boton de 30 px en una
 * pantalla de 800x480 que nadie acierta en una curva. La fila de mandos reserva
 * su alto ANTES de repartir el resto; si aun asi los botones bajan del 80 % de
 * esos 44 px, se pinta la marca de fallo encima (regla 4: el fallo se ve).
 * Medido con las tres pantallas del encargo, el boton mas estrecho sale de
 * 66 px a 1280x480, 51 a 1024x600 y 40 a 800x480: pasa en las tres, y esos
 * numeros salieron de correr el reparto, no de suponerlo.
 *
 * ## Lo que NO hace este fichero
 *
 * - **No parpadea nada.** Una sola alerta puede gritar en este tablero y es la
 *   llanta baja, que es lo que puede reventar en carretera. La luz de averia se
 *   pinta en oxide y quieta: es grave, pero ya la esta gritando el tablero del
 *   carro.
 * - **No mira relojes para saber si un dato esta rancio.** Eso lo hace el
 *   puente, que traduce lo viejo a `null` antes de que salga de ahi. Aqui un
 *   `null` se pinta `Pincel.SIN_DATO` y APAGADO. Nunca un cero.
 *
 * ## Diferencias deliberadas con el tablero HTML
 *
 * 1. Las parejas rotulo+numero van en FILA (`Pincel.filaGrande`) en vez de
 *    apiladas. Es el lenguaje de fila del proyecto (`AGUA 87 °C`) y es la unica
 *    primitiva que garantiza por construccion que dos textos no se solapen: la
 *    etiqueta encoge, y llegada al suelo se corta.
 * 2. Los puntos de enlace llevan un rotulillo de tres letras debajo. En HTML
 *    cada punto tiene un `title` y basta pasar el raton; en un Canvas de carro
 *    no hay raton, y un punto hueco sin nombre no dice QUE fuente se murio.
 * 3. No se pinta la MAC de la nevera ni el estado deducido del compresor. La
 *    MAC pertenece al menu de emparejamiento, y el compresor —que si viene en
 *    el JSON— tampoco lo enseña el HTML: no hay ancho para un segundo bloque
 *    rotulado sin que "ENCENDIDA" y su etiqueta se peleen. Si algun dia entra,
 *    entra dentro de `Pincel.recintoDeducido`, que para eso existe.
 *
 * ## Hilo
 *
 * Estado mutable de un `object`: se toca SOLO desde el hilo de interfaz —
 * `onDraw`, `onSizeChanged` y `onTouchEvent`—, como cualquier vista.
 */
object PintaNevera {

    /** Los cuatro mandos tocables, en el orden en que se pintan. */
    enum class Mando { MENOS, MAS, PODER, ECO }

    // =========================================================================
    // API publica
    // =========================================================================

    /**
     * La tarjeta de la NEVERA.
     *
     * @return false si este carro no tiene nevera (y entonces no se pinto
     *   nada) o si la caja no sirve. El repartidor de la pantalla no deberia
     *   ni reservarle sitio en ese caso, pero preguntarlo aqui evita que un
     *   sabor pinte una tarjeta vacia de una nevera que no existe.
     */
    fun pintar(canvas: Canvas, caja: Caja, d: DatosTablero, ahora: Long): Boolean =
        pintar(canvas, caja, d, ahora, propio)

    /** Igual, pero compartiendo el [Pincel] de la vista en vez del propio. */
    fun pintar(canvas: Canvas, caja: Caja, d: DatosTablero, ahora: Long, pincel: Pincel): Boolean {
        if (!Carro.perfil.tieneNevera) return false
        if (!caja.valida) return false
        if (caja != cajaNevera) medirNevera(caja)

        tarjeta(canvas, caja, esquina = true)
        pincel.tituloDeSeccion(canvas, rotuloN, TITULO_NEVERA, Pincel.LAGO)

        // La temperatura de dentro manda en la tarjeta: va sola y centrada, sin
        // etiqueta al lado, porque el rotulo de la seccion ya dijo de que es.
        pincel.cifraGrande(canvas, heroN, mNevT.de(d.nevT), GRADOS, colorTemperatura(d))

        carril(canvas, d)

        pincel.filaGrande(
            canvas, consignaN, "CONSIGNA", mNevSet.de(d.nevSet), GRADOS,
            if (d.nevSet == null) Pincel.APAGADO else Pincel.TINTA,
        )
        pincel.filaGrande(
            canvas, entradaN, "ENTRADA", mNevV.de(d.nevV), VOLTIOS,
            if (d.nevV == null) Pincel.APAGADO else Pincel.TINTA,
        )

        mandos(canvas, d, ahora)

        pincel.filaGrande(
            canvas, pieN, "ESTADO", mEstado.de(d.nevOn), null, colorEstado(d.nevOn),
        )

        // Regla 4. Un boton que el dedo no acierta en una curva es un fallo de
        // reparto, y tiene que verse en la pantalla, no en un informe.
        if (!botonesAptos) pincel.marcaDeQueNoCabe(canvas, mandosN)
        return true
    }

    /**
     * La CABECERA: quien es este carro, que fuentes estan hablando, si hay luz
     * de averia y a cuanto esta el radio.
     *
     * `ahora` no se usa: nada de aqui se mueve con el tiempo, y es a proposito
     * —ver "no parpadea nada" arriba—. Se recibe igual para que todos los
     * pintores de seccion tengan la misma firma y la vista los llame en fila
     * sin excepciones que recordar.
     */
    @Suppress("UNUSED_PARAMETER")
    fun cabecera(canvas: Canvas, caja: Caja, d: DatosTablero, ahora: Long) {
        cabecera(canvas, caja, d, ahora, propio)
    }

    /** Igual, compartiendo el [Pincel] de la vista. */
    @Suppress("UNUSED_PARAMETER")
    fun cabecera(canvas: Canvas, caja: Caja, d: DatosTablero, ahora: Long, pincel: Pincel) {
        if (!caja.valida) return
        if (caja != cajaCabecera) medirCabecera(caja)

        tarjeta(canvas, caja, esquina = false)

        // --- marca del carro ---
        if (iconoN.valida) {
            trazo.color = Pincel.OCRE
            trazo.strokeWidth = maxOf(1f, iconoN.menor * 0.085f)
            canvas.drawPath(senda, trazo)
        }
        texto(canvas, nombreN, NOMBRE, 0.42f, IZQ, Pincel.TINTA, espaciado = 0.22f)

        // --- puntos de enlace ---
        texto(canvas, capEnlacesN, "ENLACES", 0.30f, IZQ, Pincel.APAGADO, espaciado = 0.14f)
        for (i in 0 until nPips) {
            val vivo = when (pipFuente[i]) {
                F_ARR -> d.okArr
                F_VIV -> d.okViv
                F_NEV -> d.okNev
                F_TPM -> d.okTpms
                else -> d.okObd
            }
            pip(canvas, pipPuntoN[i], vivo)
            val tag = pipTag[i]
            if (tag != null) {
                texto(
                    canvas, pipRotuloN[i], tag, 0.72f, CEN,
                    if (vivo == true) Pincel.MUSGO else Pincel.APAGADO,
                    espaciado = 0.04f,
                )
            }
        }

        // --- separadores verticales, como los `.vr` del HTML ---
        separador(canvas, (enlacesN.x1 + averiaN.x0) * 0.5f, caja)
        separador(canvas, (averiaN.x1 + radioN.x0) * 0.5f, caja)

        // --- luz de averia ---
        // El numero de codigos viaja como "unidad" del valor: se pinta pegado y
        // mas chico, que es justo la jerarquia del HTML ("Sin avería · 0
        // códigos"). Si no hay lectura fresca del 0101 no hay ni SI ni NO: hay
        // hueco. Decir "sin averia" sin haber hablado con la computadora es la
        // respuesta tranquilizadora y falsa.
        lampara(canvas, lamparaN, d.mil)
        val codigos = mCodigos.de(d.codigos)
        pincel.filaGrande(
            canvas, filaAveriaN, "AVERÍA",
            when (d.mil) {
                true -> "SÍ"
                false -> "NO"
                null -> Pincel.SIN_DATO
            },
            if (codigos.isEmpty()) null else codigos,
            when (d.mil) {
                true -> Pincel.OXIDO
                false -> Pincel.TINTA
                null -> Pincel.APAGADO
            },
        )

        // --- temperatura del radio ---
        // El mismo numero que gobierna `Termometro.msEntreCuadros()`. Si sube,
        // el tablero repinta mas despacio: que se vea por que.
        pincel.filaGrande(
            canvas, radioN, "RADIO", mRadio.de(d.radioC), GRADOS, colorRadio(d.radioC),
        )
    }

    // --- Los mandos, del lado del dedo --------------------------------------

    /**
     * Que mando cae bajo el dedo, o null si no cae ninguno.
     *
     * Los rectangulos son los MISMOS que se pintaron, asi que lo que se ve es
     * lo que se toca. No hay zona de gracia invisible: si el boton se ve
     * pequeño es que ES pequeño, y para eso esta la marca de la regla 4.
     */
    fun mandoEn(x: Float, y: Float): Mando? {
        if (!Carro.perfil.tieneNevera) return null
        for (i in MANDOS.indices) if (botones[i].contiene(x, y)) return MANDOS[i]
        return null
    }

    /**
     * Un dedo bajo. Enciende el resalte del boton y dice cual es.
     *
     * El resalte es SOLO eso: acuse de recibo del toque. El estado del boton
     * —encendida, eco— no se toca aqui. Se pinta cuando la NEVERA contesta con
     * su estado nuevo, porque un boton que se pone verde porque el tablero
     * mando algo miente igual que un valor inventado, y este enlace falla una
     * de cada tres veces.
     *
     * La vista tiene que llamar a `invalidate()` despues, o el resalte no se
     * vera hasta el cuadro siguiente — que con el radio caliente es un segundo
     * entero.
     */
    fun tocar(x: Float, y: Float, ahora: Long): Mando? {
        val m = mandoEn(x, y) ?: return null
        pulsado = m
        msPulsado = ahora
        dedoDentro = true
        return m
    }

    /** El dedo se fue. El resalte se apaga solo, [MS_PULSO] despues. */
    fun soltar() {
        dedoDentro = false
    }

    /**
     * ¿Tiene sentido mandar este comando ahora mismo?
     *
     * Apagar sin saber si esta encendida seria adivinar el destino del
     * conmutador. La vista pregunta esto antes de llamar al puente; el boton
     * se pinta igual (y en hueco), porque un boton que desaparece se busca.
     */
    fun estaHabilitado(m: Mando, d: DatosTablero): Boolean = when (m) {
        Mando.MENOS, Mando.MAS -> true
        Mando.PODER -> encendida(d.nevOn) != null
        Mando.ECO -> d.nevEco != null
    }

    /**
     * ¿Cumplen los botones los 44 dp? Falso si la caja que les dieron es
     * demasiado pequeña. Vale desde el primer [pintar]; lo mismo que ya se ve
     * pintado como aspa, para quien quiera comprobarlo sin mirar la pantalla.
     */
    val mandosAceptables: Boolean get() = botonesAptos

    // =========================================================================
    // Reparto — se ejecuta al cambiar de tamaño, NUNCA por cuadro
    // =========================================================================

    private var cajaNevera: Caja? = null
    private var rotuloN = Caja.NADA
    private var heroN = Caja.NADA
    private var railN = Caja.NADA
    private var etqMinN = Caja.NADA
    private var etqMaxN = Caja.NADA
    private var consignaN = Caja.NADA
    private var entradaN = Caja.NADA
    private var mandosN = Caja.NADA
    private var pieN = Caja.NADA
    private val botones = arrayOf(Caja.NADA, Caja.NADA, Caja.NADA, Caja.NADA)
    private val botonesTexto = arrayOf(Caja.NADA, Caja.NADA, Caja.NADA, Caja.NADA)
    private var botonesAptos = true

    /**
     * Reparte la tarjeta de la nevera.
     *
     * El orden importa y no es el de un `LinearLayout`:
     *
     * 1. La fila de MANDOS se lleva su alto ABSOLUTO primero ([MINIMO_DEDO]),
     *    con un techo para que no se coma la tarjeta en una pantalla baja.
     * 2. Lo que sobra se reparte por pesos, que son los del presupuesto del
     *    HTML. Al ser pesos sin unidad, la misma linea vale para 1280x480 y
     *    para 800x480 sin tocar un numero.
     */
    private fun medirNevera(caja: Caja) {
        cajaNevera = caja
        rotuloN = Caja.NADA; heroN = Caja.NADA
        railN = Caja.NADA; etqMinN = Caja.NADA; etqMaxN = Caja.NADA
        consignaN = Caja.NADA; entradaN = Caja.NADA
        mandosN = Caja.NADA; pieN = Caja.NADA
        for (i in botones.indices) {
            botones[i] = Caja.NADA
            botonesTexto[i] = Caja.NADA
        }
        botonesAptos = true
        if (!caja.valida) return

        val dentro = caja.margenRelativo(AIRE_TARJETA)
        if (!dentro.valida) return
        val hueco = dentro.alto * HUECO_TARJETA
        val libre = dentro.alto - 2f * hueco
        if (libre <= 0f) return

        // El dedo primero. Con techo y suelo: ni se come la tarjeta en una
        // pantalla baja, ni se queda en una rendija en una alta.
        var hMandos = MINIMO_DEDO
        val techo = libre * 0.34f
        val suelo = libre * 0.14f
        if (hMandos > techo) hMandos = techo
        if (hMandos < suelo) hMandos = suelo
        val hPie = (libre - hMandos) * 0.20f

        pesosTarjeta[0] = libre - hMandos - hPie
        pesosTarjeta[1] = hMandos
        pesosTarjeta[2] = hPie
        val bandas = Reparto.filas(dentro, pesosTarjeta, hueco)
        val arriba = bandas[0]
        mandosN = bandas[1]
        pieN = bandas[2]

        val aire = arriba.alto * HUECO_ARRIBA
        val fs = Reparto.filas(arriba, PESOS_ARRIBA, aire)
        rotuloN = fs[0]
        heroN = fs[1]
        val carril = fs[2]
        val datos = Reparto.columnas(fs[3], PESOS_MITADES, aire)
        consignaN = datos[0]
        entradaN = datos[1]

        // El carril termico: arriba el rail con la marca y el punto, abajo los
        // dos extremos escritos. Los extremos los dicta la nevera (minima y
        // maxima seleccionables), no una constante inventada.
        val partes = Reparto.filas(carril, PESOS_CARRIL, 0f)
        railN = partes[0]
        val etiquetas = Reparto.columnas(partes[1], PESOS_MITADES, 0f)
        etqMinN = etiquetas[0]
        etqMaxN = etiquetas[1]

        val aireBoton = mandosN.ancho * 0.022f
        val bs = Reparto.columnas(mandosN, PESOS_MANDOS, aireBoton)
        val minimoUtil = MINIMO_DEDO * TOLERANCIA_DEDO
        for (i in botones.indices) {
            val b = bs[i]
            botones[i] = b
            // La caja del rotulillo va metida, para que la palabra no toque el
            // borde redondeado del boton.
            botonesTexto[i] = if (b.valida) b.margen(b.ancho * 0.05f, b.alto * 0.16f) else Caja.NADA
            if (!b.valida || b.ancho < minimoUtil || b.alto < minimoUtil) botonesAptos = false
        }
    }

    private var cajaCabecera: Caja? = null
    private var iconoN = Caja.NADA
    private var nombreN = Caja.NADA
    private var enlacesN = Caja.NADA
    private var capEnlacesN = Caja.NADA
    private var averiaN = Caja.NADA
    private var lamparaN = Caja.NADA
    private var filaAveriaN = Caja.NADA
    private var radioN = Caja.NADA
    private val pipPuntoN = arrayOf(Caja.NADA, Caja.NADA, Caja.NADA, Caja.NADA, Caja.NADA)
    private val pipRotuloN = arrayOf(Caja.NADA, Caja.NADA, Caja.NADA, Caja.NADA, Caja.NADA)
    private val pipTag = arrayOfNulls<String>(5)
    private val pipFuente = IntArray(5)
    private var nPips = 0

    private fun medirCabecera(caja: Caja) {
        cajaCabecera = caja
        iconoN = Caja.NADA; nombreN = Caja.NADA
        enlacesN = Caja.NADA; capEnlacesN = Caja.NADA
        averiaN = Caja.NADA; lamparaN = Caja.NADA; filaAveriaN = Caja.NADA
        radioN = Caja.NADA
        nPips = 0
        senda.reset()
        if (!caja.valida) return

        val dentro = caja.margen(caja.alto * 0.26f, caja.alto * 0.13f)
        if (!dentro.valida) return
        val cs = Reparto.columnas(dentro, PESOS_CABECERA, caja.alto * 0.28f)
        val marca = cs[0]
        enlacesN = cs[1]
        averiaN = cs[2]
        radioN = cs[3]

        // Marca: un cuadrado para el dibujo y el resto para el nombre. El peso
        // del cuadrado va en PIXELES, que es legal porque los pesos no tienen
        // unidad: pedir "esto de ancho y el resto para el otro" es un reparto
        // como cualquiera.
        cuadradoYResto(marca, 0.30f, 0.34f)?.let {
            iconoN = it[0]
            nombreN = it[1]
        }
        construirMarca(iconoN)

        // Los puntos: uno por FUENTE que este carro tiene de verdad. Hay carros
        // sin nevera ni banco de vivienda, y un punto hueco de una fuente
        // que no existe seria una averia permanente pintada de adorno.
        agregarPip(TAG_ARR, F_ARR)
        if (Carro.perfil.tieneBancoVivienda) agregarPip(TAG_VIV, F_VIV)
        if (Carro.perfil.tieneNevera) agregarPip(TAG_NEV, F_NEV)
        if (Carro.perfil.tieneTpms) agregarPip(TAG_TPM, F_TPM)
        agregarPip(TAG_OBD, F_OBD)

        val e = Reparto.columnas(enlacesN, PESOS_ENLACES, enlacesN.alto * 0.14f)
        capEnlacesN = e[0]
        val celdas = Reparto.columnasIguales(e[1], nPips, e[1].ancho * 0.02f)
        for (i in 0 until nPips) {
            val partes = Reparto.filas(celdas[i], PESOS_PIP, 0f)
            pipPuntoN[i] = partes[0]
            pipRotuloN[i] = partes[1]
        }

        cuadradoYResto(averiaN, 0.24f, 0.30f)?.let {
            lamparaN = it[0]
            filaAveriaN = it[1]
        }
    }

    private fun agregarPip(tag: String, fuente: Int) {
        if (nPips >= pipTag.size) return
        pipTag[nPips] = tag
        pipFuente[nPips] = fuente
        nPips++
    }

    /**
     * Parte [caja] en un cuadrado a la izquierda y el resto a la derecha.
     *
     * El lado del cuadrado es el alto de la caja, limitado a [maxAncho] del
     * ancho: en una cabecera muy baja y ancha el dibujo se queda proporcionado,
     * y en una estrecha no se traga la mitad del bloque. Null si no queda sitio
     * para el resto — y entonces el que llama deja las dos cajas en NADA y no
     * pinta ni el dibujo ni el texto, en vez de pintarlos encima.
     */
    private fun cuadradoYResto(caja: Caja, maxAncho: Float, aireRel: Float): List<Caja>? {
        if (!caja.valida) return null
        val lado = minOf(caja.alto, caja.ancho * maxAncho)
        val aire = lado * aireRel
        val resto = caja.ancho - aire - lado
        if (lado <= 0f || resto <= 0f) return null
        pesosDos[0] = lado
        pesosDos[1] = resto
        return Reparto.columnas(caja, pesosDos, aire)
    }

    /** Tres curvas de nivel anidadas: la marca del tablero topografico. */
    private fun construirMarca(c: Caja) {
        senda.reset()
        if (!c.valida) return
        val w = c.ancho
        val h = c.alto
        senda.moveTo(c.x0 + w * 0.05f, c.y0 + h * 0.80f)
        senda.quadTo(c.x0 + w * 0.50f, c.y0 + h * 0.02f, c.x0 + w * 0.95f, c.y0 + h * 0.80f)
        senda.moveTo(c.x0 + w * 0.21f, c.y0 + h * 0.88f)
        senda.quadTo(c.x0 + w * 0.50f, c.y0 + h * 0.32f, c.x0 + w * 0.79f, c.y0 + h * 0.88f)
        senda.moveTo(c.x0 + w * 0.36f, c.y0 + h * 0.96f)
        senda.quadTo(c.x0 + w * 0.50f, c.y0 + h * 0.62f, c.x0 + w * 0.64f, c.y0 + h * 0.96f)
    }

    // =========================================================================
    // Pintado
    // =========================================================================

    /**
     * El carril termico: un corte de elevacion.
     *
     * La marca (barra vertical, arena) es la CONSIGNA; el punto (circulo, lago)
     * es la temperatura real. Los dos se colocan sobre el recorrido util, que
     * esta metido hacia dentro el radio del punto: sin ese margen el punto se
     * saldria de su caja en los extremos y pisaria a la seccion vecina — que es
     * literalmente como se rompio el tablero viejo.
     *
     * Si falta el dato, no se pinta el elemento. Un punto por omision en mitad
     * del carril seria una temperatura que nadie ha medido.
     */
    private fun carril(canvas: Canvas, d: DatosTablero) {
        val caja = railN
        if (!caja.valida) return

        val lo = d.nevMin ?: LIMITE_FRIO
        val hi = d.nevMax ?: LIMITE_TEMPLADO
        val hayEscala = hi > lo

        // El rail: un rebaje con marcas cada decima parte, como una regla.
        val altoRail = caja.alto * 0.30f
        val cy = caja.cy - caja.alto * 0.06f
        val radio = altoRail * 0.5f
        rect.set(caja.x0, cy - radio, caja.x1, cy + radio)
        relleno.color = Pincel.HUECO
        canvas.drawRoundRect(rect, radio, radio, relleno)
        relleno.color = Pincel.LINEA2
        val fino = maxOf(1f, caja.alto * 0.03f)
        val paso = caja.ancho / 10f
        var i = 1
        while (i < 10) {
            val x = caja.x0 + paso * i
            canvas.drawRect(x, cy - radio, x + fino, cy + radio, relleno)
            i++
        }
        trazo.color = Pincel.LINEA
        trazo.strokeWidth = fino
        rect.set(caja.x0 + fino * 0.5f, cy - radio, caja.x1 - fino * 0.5f, cy + radio)
        canvas.drawRoundRect(rect, radio, radio, trazo)

        val rPunto = caja.alto * 0.36f
        val x0 = caja.x0 + rPunto
        val x1 = caja.x1 - rPunto
        if (hayEscala && x1 > x0) {
            val set = d.nevSet
            if (set != null) {
                val x = x0 + (x1 - x0) * fraccion(set, lo, hi)
                val ancho = maxOf(2f, caja.ancho * 0.008f)
                relleno.color = Pincel.ARENA
                canvas.drawRect(
                    x - ancho * 0.5f, cy - caja.alto * 0.40f,
                    x + ancho * 0.5f, cy + caja.alto * 0.40f, relleno,
                )
            }
            val ahoraT = d.nevT
            if (ahoraT != null) {
                val x = x0 + (x1 - x0) * fraccion(ahoraT, lo, hi)
                relleno.color = Pincel.TARJETA
                canvas.drawCircle(x, cy, rPunto, relleno)
                relleno.color = Pincel.LAGO
                canvas.drawCircle(x, cy, rPunto * 0.68f, relleno)
            }
        }

        val fr = 0.80f
        texto(canvas, etqMinN, mNevMin.de(if (hayEscala) lo else null), fr, IZQ, Pincel.APAGADO)
        texto(canvas, etqMaxN, mNevMax.de(if (hayEscala) hi else null), fr, DER, Pincel.APAGADO)
    }

    private fun fraccion(v: Int, lo: Int, hi: Int): Float {
        val f = (v - lo).toFloat() / (hi - lo).toFloat()
        return if (f < 0f) 0f else if (f > 1f) 1f else f
    }

    /**
     * Los cuatro mandos. El unico sitio del tablero donde se TOCA algo.
     *
     * Los dos de la consigna van anchos y con el signo grande porque se buscan
     * a ciegas; los dos de estado llevan palabra porque decir lo que hacen
     * importa mas que su tamaño. Ninguno cambia de estado al tocarlo: lo que
     * sale escrito es lo que la nevera contesto en la ultima lectura.
     */
    private fun mandos(canvas: Canvas, d: DatosTablero, ahora: Long) {
        boton(canvas, 0, Mando.MENOS, MENOS, SIGNO_DEL_BOTON, Pincel.ARENA, Pincel.LINEA2, ahora)
        boton(canvas, 1, Mando.MAS, "+", SIGNO_DEL_BOTON, Pincel.ARENA, Pincel.LINEA2, ahora)

        val enc = encendida(d.nevOn)
        boton(
            canvas, 2, Mando.PODER,
            when (enc) {
                true -> "APAGAR"
                false -> "ENCENDER"
                null -> Pincel.SIN_DATO
            },
            PALABRA_DEL_BOTON,
            if (enc == null) Pincel.APAGADO else Pincel.ARENA,
            Pincel.LINEA2, ahora,
        )

        val eco = d.nevEco
        boton(
            canvas, 3, Mando.ECO,
            when (eco) {
                true -> "ECO"
                false -> "MAX"
                null -> Pincel.SIN_DATO
            },
            PALABRA_DEL_BOTON,
            when (eco) {
                true -> Pincel.MUSGO
                false -> Pincel.ARENA
                null -> Pincel.APAGADO
            },
            if (eco == true) Pincel.MUSGO else Pincel.LINEA2,
            ahora,
        )
    }

    private fun boton(
        canvas: Canvas,
        indice: Int,
        mando: Mando,
        rotulillo: String,
        fraccion: Float,
        colorTexto: Int,
        colorBorde: Int,
        ahora: Long,
    ) {
        val caja = botones[indice]
        if (!caja.valida) return
        val vivo = resaltado(mando, ahora)
        val r = caja.menor * 0.11f

        rect.set(caja.x0, caja.y0, caja.x1, caja.y1)
        relleno.color = if (vivo) PULSO else FONDO_BOTON
        canvas.drawRoundRect(rect, r, r, relleno)

        val grosor = maxOf(1f, caja.menor * 0.030f)
        trazo.color = if (vivo) Pincel.MUSGO else colorBorde
        trazo.strokeWidth = grosor
        val m = grosor * 0.5f
        rect.set(caja.x0 + m, caja.y0 + m, caja.x1 - m, caja.y1 - m)
        canvas.drawRoundRect(rect, r, r, trazo)

        texto(
            canvas, botonesTexto[indice], rotulillo, fraccion, CEN,
            if (vivo) Pincel.TINTA else colorTexto, espaciado = 0.02f,
        )
    }

    private fun resaltado(m: Mando, ahora: Long): Boolean {
        if (pulsado != m) return false
        if (dedoDentro) return true
        val transcurrido = ahora - msPulsado
        return transcurrido in 0 until MS_PULSO
    }

    private var pulsado: Mando? = null
    private var msPulsado = 0L
    private var dedoDentro = false

    /** El fondo de tarjeta con su borde y, si toca, la esquina de hoja de mapa. */
    private fun tarjeta(canvas: Canvas, caja: Caja, esquina: Boolean) {
        val r = caja.menor * 0.014f
        rect.set(caja.x0, caja.y0, caja.x1, caja.y1)
        relleno.color = Pincel.TARJETA
        canvas.drawRoundRect(rect, r, r, relleno)

        val grosor = maxOf(1f, caja.menor * 0.004f)
        val m = grosor * 0.5f
        trazo.color = Pincel.LINEA
        trazo.strokeWidth = grosor
        rect.set(caja.x0 + m, caja.y0 + m, caja.x1 - m, caja.y1 - m)
        canvas.drawRoundRect(rect, r, r, trazo)

        if (esquina) {
            val l = caja.menor * 0.055f
            trazo.color = Pincel.LINEA2
            canvas.drawLine(caja.x1 - l, caja.y1 - m, caja.x1 - m, caja.y1 - m, trazo)
            canvas.drawLine(caja.x1 - m, caja.y1 - l, caja.x1 - m, caja.y1 - m, trazo)
        }
    }

    private fun separador(canvas: Canvas, x: Float, caja: Caja) {
        if (x <= caja.x0 || x >= caja.x1) return
        relleno.color = Pincel.LINEA
        val g = maxOf(1f, caja.alto * 0.02f)
        canvas.drawRect(x, caja.y0 + caja.alto * 0.24f, x + g, caja.y1 - caja.alto * 0.24f, relleno)
    }

    /**
     * Un punto de enlace. Lleno = esa fuente esta dando datos AHORA.
     *
     * Hueco significa que no. Y no es lo mismo que "no hay dato": si muere el
     * receptor de las llantas, las cuatro presiones se quedan congeladas en su
     * ultimo valor bueno y siguen pareciendo correctas. El punto es lo unico
     * que lo delata.
     */
    private fun pip(canvas: Canvas, caja: Caja, vivo: Boolean?) {
        if (!caja.valida) return
        val r = caja.menor * 0.32f
        if (r <= 0f) return
        if (vivo == true) {
            relleno.color = Pincel.MUSGO
            canvas.drawCircle(caja.cx, caja.cy, r, relleno)
        } else {
            val g = maxOf(1f, r * 0.42f)
            trazo.color = Pincel.APAGADO
            trazo.strokeWidth = g
            canvas.drawCircle(caja.cx, caja.cy, r - g * 0.5f, trazo)
        }
    }

    /** La luz de averia. Encendida = relleno oxido. Quieta: no parpadea. */
    private fun lampara(canvas: Canvas, caja: Caja, encendida: Boolean?) {
        if (!caja.valida) return
        val r = caja.menor * 0.32f
        if (r <= 0f) return
        if (encendida == true) {
            relleno.color = Pincel.OXIDO
            canvas.drawCircle(caja.cx, caja.cy, r, relleno)
            return
        }
        val g = maxOf(1f, r * 0.32f)
        trazo.color = if (encendida == false) Pincel.MUSGO else Pincel.APAGADO
        trazo.strokeWidth = g
        canvas.drawCircle(caja.cx, caja.cy, r - g * 0.5f, trazo)
    }

    // --- Texto suelto -------------------------------------------------------

    /**
     * Un texto solo dentro de su caja: mide, encoge y —si hace falta— CORTA.
     *
     * [Pincel] no tiene primitiva de texto suelto: la suya mas parecida,
     * `tituloDeSeccion`, pinta ademas guia de puntos y linea inferior, que aqui
     * sobran (un rotulillo de tres letras bajo un punto de enlace no lleva
     * subrayado). Asi que va aqui, con **la misma disciplina**: el tamaño sale
     * de `caja.alto`, se mide con `measureText`, se encoge hasta
     * `Pincel.SUELO`, y por debajo se corta con `breakText` — nunca se pinta
     * saliendose. Si algun dia alguien consolida las primitivas, esto se muda a
     * [Pincel] y se borra de aqui.
     *
     * @return false si el texto no cupo entero. Quien llama decide si eso vale
     *   una marca; para un rotulillo de adorno no la vale.
     */
    private fun texto(
        canvas: Canvas,
        caja: Caja,
        txt: String,
        fraccion: Float,
        alineado: Int,
        color: Int,
        negrita: Boolean = true,
        espaciado: Float = 0.10f,
    ): Boolean {
        if (!caja.valida || txt.isEmpty() || caja.ancho <= 0f) return false
        letra.typeface = if (negrita) tipoNegrita else tipoNormal
        letra.letterSpacing = espaciado
        letra.color = color
        val ideal = caja.alto * fraccion
        letra.textSize = ideal

        var letras = txt.length
        var medido = letra.measureText(txt)
        if (medido > caja.ancho) {
            val proporcional = ideal * (caja.ancho / medido)
            if (proporcional >= ideal * Pincel.SUELO) {
                letra.textSize = proporcional
            } else {
                letra.textSize = ideal * Pincel.SUELO
                letras = letra.breakText(txt, true, caja.ancho, null)
            }
            medido = if (letras > 0) letra.measureText(txt, 0, letras) else 0f
        }
        if (letras <= 0) return false

        letra.getFontMetrics(metricas)
        val y = caja.cy - (metricas.ascent + metricas.descent) * 0.5f
        val x = when (alineado) {
            DER -> caja.x1 - medido
            CEN -> caja.cx - medido * 0.5f
            else -> caja.x0
        }
        canvas.drawText(txt, 0, letras, x, y, letra)
        return letras == txt.length
    }

    // --- Colores por umbral -------------------------------------------------

    /**
     * La temperatura de dentro.
     *
     * En ocre cuando se ha ido [MARGEN_TIBIO] grados POR ENCIMA de la consigna:
     * la nevera esta perdiendo la pelea y eso se nota horas antes de que la
     * comida se estropee. Nunca en oxido: la unica alerta que grita en este
     * tablero es la llanta baja.
     */
    private fun colorTemperatura(d: DatosTablero): Int {
        val t = d.nevT ?: return Pincel.APAGADO
        val set = d.nevSet ?: return Pincel.TINTA
        return if (t >= set + MARGEN_TIBIO) Pincel.OCRE else Pincel.TINTA
    }

    private fun colorEstado(nevOn: String?): Int =
        if (encendida(nevOn) == true) Pincel.MUSGO else Pincel.APAGADO

    /**
     * El radio. Los umbrales son los MISMOS de `Termometro` (tibio 70,
     * caliente 78) porque es el mismo numero: cuando esto se pone ocre, el
     * tablero ya esta repintando mas despacio.
     */
    private fun colorRadio(c: Int?): Int = when {
        c == null -> Pincel.APAGADO
        c >= RADIO_CALIENTE -> Pincel.OXIDO
        c >= RADIO_TIBIO -> Pincel.OCRE
        else -> Pincel.ARENA
    }

    /** "Encendida" / "Apagada" / null, tal cual lo manda el puente. */
    private fun encendida(nevOn: String?): Boolean? = when {
        nevOn == null -> null
        ENCENDIDA.equals(nevOn, ignoreCase = true) -> true
        APAGADA.equals(nevOn, ignoreCase = true) -> false
        else -> null
    }

    // =========================================================================
    // Herramienta preasignada. Nada de esto se crea por cuadro.
    // =========================================================================

    private val propio = Pincel()

    private val tipoNegrita: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val tipoNormal: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val letra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        fontFeatureSettings = "tnum"
    }
    private val metricas = Paint.FontMetrics()
    private val rect = RectF()
    private val senda = Path()

    private val pesosTarjeta = FloatArray(3)
    private val pesosDos = FloatArray(2)
    private val MANDOS = Mando.values()

    // =========================================================================
    // Constantes
    // =========================================================================

    /**
     * 44 pixeles. **El unico numero absoluto del fichero**, y el unico que no
     * sale de la caja.
     *
     * Un dedo mide lo que mide: escalar el boton con la tarjeta es como se
     * consigue un boton de 30 px en una pantalla de 800x480 que nadie acierta
     * en una curva. Por eso la fila de mandos se lleva su alto ANTES de que se
     * reparta el resto.
     *
     * Son PIXELES y no `dp` a proposito. Todo este tablero esta acotado en
     * pixeles de pantalla —el HTML fija un lienzo de 1024x600 y estos radios
     * son de resolucion fija—, y el minimo del boceto es `min-width:46px` en
     * ese lienzo, o sea pixeles. Convertir con la densidad que declare la ROM
     * moveria el objetivo sin que el dedo hubiera cambiado de tamaño: hay
     * cabinas que declaran 1.0 y cabinas identicas que declaran 1.5. Si algun
     * dia se monta esto en una pantalla de densidad distinta, esta es la linea
     * que hay que revisar.
     */
    private const val MINIMO_DEDO = 44f

    /** Por debajo de esto el boton es inacertable y se marca (ver regla 4). */
    private const val TOLERANCIA_DEDO = 0.80f

    /** Cuanto dura el acuse de recibo de un toque. */
    private const val MS_PULSO = 220L

    private const val AIRE_TARJETA = 0.048f
    private const val HUECO_TARJETA = 0.028f
    private const val HUECO_ARRIBA = 0.034f

    /**
     * rotulo · temperatura grande · carril · consigna+entrada.
     *
     * NO son los del HTML tal cual: son los que hacen que la LETRA salga como
     * la del HTML. Medido a 1024x600 sobre estos pesos: rotulo de seccion
     * 10,7 px (el HTML tiene 10,5), etiqueta de fila 7,5 (tiene 8,5), numero de
     * fila 19,3 (tiene 22). El presupuesto de pixeles del HTML no se puede
     * copiar entero porque alli el rotulo y el numero van APILADOS y aqui van
     * en fila: la fila gasta menos alto y ese alto se le devuelve a la cifra
     * grande, que es la que manda en la tarjeta.
     */
    private val PESOS_ARRIBA = floatArrayOf(26f, 80f, 26f, 34f)
    private val PESOS_MITADES = floatArrayOf(1f, 1f)
    private val PESOS_CARRIL = floatArrayOf(20f, 12f)

    /**
     * Los dos del signo mas estrechos que los de palabra, como en el HTML —
     * pero no 62/95: **85/100**.
     *
     * Medido: con la proporcion del HTML, a 800x480 el boton del signo sale de
     * 33,5 px de ancho, por debajo del minimo del dedo, y la tarjeta se lleva
     * el aspa de la regla 4 para siempre. Con 85/100 sale de 40. El HTML puede
     * permitirse 62/95 porque a 1024x600 le sobran 236 px de ancho; a 800x480
     * no le sobran, y ahi el que manda es el dedo, no el boceto.
     */
    private val PESOS_MANDOS = floatArrayOf(85f, 85f, 100f, 100f)

    /** marca · enlaces · averia · radio. La marca se queda el aire sobrante. */
    private val PESOS_CABECERA = floatArrayOf(400f, 150f, 200f, 110f)
    private val PESOS_ENLACES = floatArrayOf(52f, 108f)
    private val PESOS_PIP = floatArrayOf(58f, 42f)

    private const val IZQ = 0
    private const val CEN = 1
    private const val DER = 2

    private const val F_ARR = 0
    private const val F_VIV = 1
    private const val F_NEV = 2
    private const val F_TPM = 3
    private const val F_OBD = 4

    private const val TAG_ARR = "ARR"
    private const val TAG_VIV = "VIV"
    private const val TAG_NEV = "NEV"
    private const val TAG_TPM = "TPM"
    private const val TAG_OBD = "OBD"

    private const val TITULO_NEVERA = "NEVERA · ALPICOOL"
    private const val GRADOS = "°C"
    private const val GRADOS_SUELTO = " °C"
    private const val VOLTIOS = "V"

    /** Signo menos tipografico, no guion: pega con las cifras tabulares. */
    private const val MENOS = "−"

    /**
     * Tamaño del rotulillo de un boton, en fraccion del alto de SU boton — no
     * del de la tarjeta, ni del de la pantalla.
     *
     * Los dos valores salen de igualar el boceto a 1024x600: el signo a 26 px
     * (`.btn.grande`) y la palabra a 12,5 (`.btn`). En pantallas mas estrechas
     * la palabra no baja por esto sino porque no cabe de ancho, y de eso ya se
     * encarga [texto], que mide y encoge.
     */
    private const val SIGNO_DEL_BOTON = 0.80f
    private const val PALABRA_DEL_BOTON = 0.42f

    private const val ENCENDIDA = "Encendida"
    private const val APAGADA = "Apagada"

    /** Extremos del carril cuando la nevera no dice los suyos. */
    private const val LIMITE_FRIO = -20
    private const val LIMITE_TEMPLADO = 20

    /** Grados por encima de la consigna a partir de los cuales preocupa. */
    private const val MARGEN_TIBIO = 5

    // Los de `Termometro`. Repetidos porque alli son privados; si cambian alli,
    // cambian aqui — es el mismo umbral, no una opinion de esta pantalla.
    private const val RADIO_TIBIO = 70
    private const val RADIO_CALIENTE = 78

    /** rgba(156,190,122,.22): el `:active` de los botones del HTML. */
    private const val PULSO = 0x389CBE7A

    /** rgba(0,0,0,.28) sobre la tarjeta: el rebaje del boton. */
    private const val FONDO_BOTON = 0xFF12160F.toInt()

    // =========================================================================
    // Memorizadores de texto
    //
    // Van AL FINAL a proposito: los inicializadores de un `object` corren en
    // orden de declaracion, y estos leen las constantes de arriba. Puestos
    // antes, el compilador canta "Variable GRADOS_SUELTO must be initialized"
    // — y tiene razon.
    // =========================================================================

    private val mNevT = Entero()
    private val mNevSet = Entero()
    private val mNevV = Decimal()
    private val mNevMin = Entero(sufijo = GRADOS_SUELTO)
    private val mNevMax = Entero(sufijo = GRADOS_SUELTO)
    private val mRadio = Entero()
    private val mCodigos = Entero(vacio = "", prefijo = "· ")
    private val mEstado = Rotulo()

    /** El nombre del carro, en mayusculas UNA vez y no por cuadro. */
    private val NOMBRE: String = Carro.perfil.nombre.uppercase()
}

// =============================================================================
// Memorizadores: formatear SOLO cuando el valor cambia
// =============================================================================

/**
 * Un entero formateado, con memoria.
 *
 * `n.toString()` asigna una cadena. Cuatro numeros por cuadro durante seis
 * horas de viaje son cientos de miles de cadenas para el recolector, en un
 * radio que ya se apago dos veces por calor. Esto guarda el ultimo valor CRUDO
 * y solo vuelve a formatear cuando cambia de verdad — y la temperatura de una
 * nevera cambia de grado en grado, cada varios minutos.
 *
 * El signo menos se escribe con el menos tipografico (U+2212), que a cifras
 * tabulares se alinea; el guion normal es mas corto y baila.
 */
private class Entero(
    private val vacio: String = Pincel.SIN_DATO,
    private val prefijo: String = "",
    private val sufijo: String = "",
) {
    private var ultimo = 0
    private var habia = false
    private var virgen = true

    fun de(n: Int?): String {
        val hay = n != null
        if (!virgen && hay == habia && (!hay || n == ultimo)) return texto
        virgen = false
        habia = hay
        texto = if (n == null) {
            vacio
        } else {
            ultimo = n
            if (n < 0) prefijo + "−" + (-n) + sufijo else prefijo + n + sufijo
        }
        return texto
    }

    var texto: String = vacio
        private set
}

/**
 * Un decimal con UN decimal, con memoria.
 *
 * Guarda el valor multiplicado por diez y redondeado: asi "12.34" y "12.35" son
 * el mismo texto y no se reformatea, que es lo que pasaria comparando floats.
 */
private class Decimal(private val vacio: String = Pincel.SIN_DATO) {
    private val sb = StringBuilder(12)
    private var ultimo = Int.MIN_VALUE
    private var virgen = true

    fun de(x: Float?): String {
        val d = if (x == null || !x.isFinite()) Int.MIN_VALUE else Math.round(x * 10f)
        if (!virgen && d == ultimo) return texto
        virgen = false
        ultimo = d
        texto = if (d == Int.MIN_VALUE) {
            vacio
        } else {
            sb.setLength(0)
            val a = if (d < 0) { sb.append('−'); -d } else d
            sb.append(a / 10).append('.').append(a % 10)
            sb.toString()
        }
        return texto
    }

    var texto: String = vacio
        private set
}

/** Un texto del puente, pasado a mayusculas una sola vez por cambio. */
private class Rotulo(private val vacio: String = Pincel.SIN_DATO) {
    private var ultimo: String? = null
    private var virgen = true

    fun de(s: String?): String {
        if (!virgen && s == ultimo) return texto
        virgen = false
        ultimo = s
        texto = if (s.isNullOrEmpty()) vacio else s.uppercase()
        return texto
    }

    var texto: String = vacio
        private set
}

