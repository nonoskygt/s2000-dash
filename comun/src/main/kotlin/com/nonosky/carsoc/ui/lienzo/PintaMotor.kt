package com.nonosky.carsoc.ui.lienzo

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.nonosky.carsoc.Carro

/**
 * LA TARJETA DE MOTOR de la variante Canvas: agua, rpm, aire, carga,
 * colector, avance, la lampara del VTEC y el RELOJ DE MEZCLA.
 *
 * Replica la tarjeta `<section>` "Motor" del tablero HTML —misma rejilla,
 * mismos rotulos, misma paleta— pero repartida con [Reparto] y pintada con
 * [Pincel], que es lo que la hace sobrevivir a un cambio de pantalla.
 *
 * ## Como esta repartida (y por que asi)
 *
 * ```
 *  ┌ MOTOR · (MTR) ·······································┐
 *  │            ┌ AGUA   88 °C ┬ RPM      820 ┐           │
 *  │  ( reloj ) │ AIRE   34 °C │ CARGA    27 %│           │
 *  │  ( mezcla) │ COLECT 4.5 PSI│ AVANCE   –– │           │
 *  │            ├──────────────┴──────────────┤           │
 *  │  AJUSTES −4%│ ⚡ VTEC · DEDUCIDO  EN REPOSO│          │
 *  └─────────────────────────────────────────────────────┘
 * ```
 *
 * Los pesos son los del presupuesto del HTML pegados tal cual —150 para el
 * reloj, 310 para la rejilla; 142/22 dentro del reloj; 120/42 en la
 * columna derecha— porque [Reparto] acepta pesos sin unidad. Nadie escribe
 * `ancho / 3` aqui: **no hay una sola division de reparto en este fichero**.
 *
 * ## La regla que ordena todo
 *
 * Ningun tamaño de letra sale del alto de PANTALLA. Todos salen del alto de
 * SU caja: el numero de una fila lo pone [Pincel.filaGrande] con
 * `caja.alto * VALOR_FILA`, y los textos propios del reloj salen del radio de
 * la esfera, que a su vez sale de la caja del reloj. Estrechar la columna
 * encoge la letra porque es la misma medida — eso es lo que le faltaba al
 * DashView viejo, donde las columnas salian del ancho y la letra del alto.
 *
 * ## Sin asignar memoria por cuadro
 *
 * - Pinceles, [RectF], [Path] y los vectores de marcas se crean UNA vez.
 * - El REPARTO se hace en [medir], no en [pinta]. [pinta] lo llama solo
 *   cuando la caja cambio de verdad (`caja != ultima`, comparacion por valor,
 *   sin alocar), que en la practica es una vez por `onSizeChanged`.
 * - Los numeros se formatean en un [Memo] por campo: mientras el valor no
 *   cambie no se fabrica ni una cadena. Con el radio caliente y el tablero a
 *   1 fps —o con el carro parado— este pintor no aloca nada en absoluto. Es
 *   la unica concesion: cuando un numero SI cambia, se crea su cadena. Ver
 *   [Memo] para por que no se puede evitar del todo sin tocar [Pincel].
 *
 * ## Quien puede gritar
 *
 * **Solo el agua**, y solo por encima de `COOLANT_HIGH_C`. Es la unica alerta
 * parpadeante de esta seccion: un motor recalentado se para en el arcen, y
 * todo lo demas de esta tarjeta se aguanta hasta llegar. El VTEC no parpadea
 * aunque en algun motor sea un acontecimiento, la mezcla pobre no parpadea, y la
 * marca de "no cabe" tampoco — esa es un defecto de pintado, no una averia.
 */
object PintaMotor {

    // =====================================================================
    // Pinceles. Todos preasignados: ni uno se crea dentro de pinta().
    // =====================================================================

    private val negrita = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** Los arcos de zona del reloj. Punta recta, como en el SVG. */
    private val arco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    /** La aguja. Punta redonda para que no muerda el borde de la esfera. */
    private val aguja = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** La cifra del centro del reloj. Tabular, o la aguja parece moverse sola. */
    private val cifra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = negrita
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }

    /** Los rotulos chicos: "AFR", "RICA", "POBRE", "DEDUCIDO". */
    private val rotulito = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = negrita
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.18f
    }

    /** El nombre y el estado de la lampara del VTEC. */
    private val palabra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = negrita
        textAlign = Paint.Align.LEFT
        letterSpacing = 0.20f
    }

    private val metricas = Paint.FontMetrics()
    private val rect = RectF()
    private val ovalo = RectF()
    private val rayo = Path()

    /** El Pincel de casa, por si nadie pasa el suyo. Ver [pinta]. */
    private val pincelPropio = Pincel()

    // =====================================================================
    // El reparto. Se calcula en medir() y se guarda; pinta() solo lee.
    // =====================================================================

    private var ultima: Caja = Caja.NADA
    private var repartoValido = false

    private var cRotulo = Caja.NADA
    private var cEsfera = Caja.NADA
    private var cAjustes = Caja.NADA
    private var cVtec = Caja.NADA
    private var cVtecDentro = Caja.NADA
    private val cFila = Array(6) { Caja.NADA }

    /** Centro y radio de la esfera, en pixeles absolutos. */
    private var ejeX = 0f
    private var ejeY = 0f
    private var radio = 0f

    /** Las dos marcas tenues (12 y 18 AFR), 4 floats cada una. */
    private val marcasTenues = FloatArray(8)

    /** La marca de la estequiometrica, que va destacada y sola. */
    private val marcaStq = FloatArray(4)

    // =====================================================================
    // Formateo memorizado. Un Memo por campo.
    // =====================================================================

    private val mAgua = Memo()
    private val mRpm = Memo()
    private val mAire = Memo()
    private val mCarga = Memo()
    private val mColector = Memo()
    private val mAvance = Memo()
    private val mTrim = Memo()
    private val mAfr = Memo()
    private val mTitulo = Memo()

    // =====================================================================
    // API
    // =====================================================================

    /**
     * Reparte la tarjeta. **Llamar desde `onSizeChanged`, no desde `onDraw`.**
     *
     * Aqui se alocan las listas de [Reparto] —esa es la razon de que exista
     * como funcion aparte— y se rehacen el [Path] del rayo y los vectores de
     * marcas del reloj. [pinta] la invoca sola si detecta que la caja cambio,
     * asi que llamarla a mano es una optimizacion, no una obligacion.
     */
    fun medir(caja: Caja) {
        ultima = caja
        repartoValido = false
        if (!caja.valida) return

        // Aire interior de la tarjeta. Fraccion del lado MENOR: asi el respiro
        // encoge con la tarjeta en vez de comerse media caja en 800x480.
        val dentro = caja.margen(caja.menor * 0.075f, caja.menor * 0.06f)
        if (!dentro.valida) return

        val aire = dentro.menor * 0.06f

        // Rotulo de region arriba, cuerpo debajo. 28 y 164 son el presupuesto
        // vertical del HTML dentro de la tarjeta de 216.
        val bandas = Reparto.filas(dentro, PESOS_TARJETA, 0f)
        cRotulo = bandas[0]
        val cuerpo = bandas[1]

        // Reloj a la izquierda, rejilla de datos a la derecha. 150 y 310 con
        // 12 de aire, que es lo que ocupan en el HTML.
        val cols = Reparto.columnas(cuerpo, PESOS_CUERPO, aire)
        val izquierda = cols[0]
        val derecha = cols[1]

        // Dentro del reloj: esfera y pie de ajustes.
        //
        // ⚠️ AQUI MANDA EL PERFIL DEL CARRO. Sin sonda de banda ancha el reloj
        // no puede decir nada, asi que se encoge y le cede el sitio a los
        // ajustes de combustible, que son lo unico que ese motor mide de
        // verdad. No es decoracion: es que el instrumento grande tiene que ser
        // el que tiene dato.
        val pesosReloj = if (Carro.perfil.tieneAfrReal) PESOS_RELOJ else PESOS_RELOJ_MUDO
        val reloj = Reparto.filas(izquierda, pesosReloj, aire * 0.3f)
        cEsfera = reloj[0]
        cAjustes = reloj[1]

        // Columna derecha: seis filas de dato arriba, lampara del VTEC abajo.
        val ladoDerecho = Reparto.filas(derecha, PESOS_DERECHA, aire * 0.35f)
        val rejilla = ladoDerecho[0]
        cVtec = ladoDerecho[1]
        cVtecDentro = cVtec.margen(cVtec.menor * 0.09f)

        // La rejilla de datos: tres bandas SIN aire entre ellas —las separa una
        // linea fina, como en el HTML— y dos columnas CON aire.
        val tres = Reparto.filasIguales(rejilla, 3, 0f)
        var i = 0
        while (i < 3) {
            val par = Reparto.columnasIguales(tres[i], 2, aire)
            cFila[i * 2] = par[0]
            cFila[i * 2 + 1] = par[1]
            i++
        }

        // Geometria de la esfera. TODO sale de cEsfera, nada de la pantalla.
        //
        // El SVG del HTML dibuja la esfera con radio 38 en una ventana de
        // 88x80: 2.32 radios de ancho y 2.10 de alto, con el centro a 1.16
        // radios del borde de arriba. Se conservan esas proporciones y el radio
        // sale del lado que se quede corto.
        radio = minOf(cEsfera.ancho / 2.32f, cEsfera.alto / 2.10f)
        ejeX = cEsfera.cx
        ejeY = cEsfera.y0 + (cEsfera.alto - radio * 2.10f) * 0.5f + radio * 1.16f

        // Las marcas de graduacion: del borde exterior de la banda hacia
        // dentro, cruzandola, como en el SVG (radio 1.00 a 0.842).
        marca(marcasTenues, 0, 12.0f)
        marca(marcasTenues, 4, 18.0f)
        marca(marcaStq, 0, Carro.motor.afrEstequiometrica)

        // El rayo de la lampara del VTEC, en su caja definitiva.
        construirRayo()

        repartoValido = cRotulo.valida && cEsfera.valida && cAjustes.valida &&
            cVtec.valida && cVtecDentro.valida && radio > 0f &&
            cFila.all { it.valida }
    }

    /**
     * Pinta la tarjeta entera dentro de [caja].
     *
     * @param d el estado; **null en cualquier campo se pinta "––" y APAGADO**,
     *   jamas un cero.
     * @param ahora el reloj del cuadro. Entra por parametro y no se lee de
     *   `System.currentTimeMillis()` dentro para que el parpadeo del agua sea
     *   el mismo en toda la pantalla y para poder probarlo.
     */
    fun pinta(canvas: Canvas, caja: Caja, d: DatosTablero, ahora: Long) {
        pinta(canvas, caja, d, ahora, pincelPropio)
    }

    /** Igual, pero con el [Pincel] de la vista, que es lo suyo si ya tiene uno. */
    fun pinta(canvas: Canvas, caja: Caja, d: DatosTablero, ahora: Long, pincel: Pincel) {
        if (!caja.valida) return
        // Comparacion por valor, sin alocar: solo re-reparte si de verdad
        // cambio el sitio. En marcha esto es siempre falso.
        if (caja != ultima) medir(caja)

        fondoDeTarjeta(canvas, caja)

        // REGLA 4: si el reparto no cupo, se ve. Antes se pintaba igual y los
        // numeros se montaban unos sobre otros hasta que alguien mandaba una
        // foto desde el carro.
        if (!repartoValido) {
            pincel.marcaDeQueNoCabe(canvas, caja)
            return
        }

        pintaRotulo(canvas, d, pincel)
        pintaFilas(canvas, d, ahora, pincel)
        pintaReloj(canvas, d)
        pintaAjustes(canvas, d, pincel)
        pintaVtec(canvas, d, pincel)
    }

    // =====================================================================
    // La tarjeta
    // =====================================================================

    private fun fondoDeTarjeta(canvas: Canvas, caja: Caja) {
        val r = caja.menor * 0.015f
        rect.set(caja.x0, caja.y0, caja.x1, caja.y1)
        relleno.color = Pincel.TARJETA
        canvas.drawRoundRect(rect, r, r, relleno)

        val grosor = maxOf(1f, caja.menor * 0.005f)
        trazo.color = Pincel.LINEA
        trazo.strokeWidth = grosor
        val m = grosor * 0.5f
        rect.set(caja.x0 + m, caja.y0 + m, caja.x1 - m, caja.y1 - m)
        canvas.drawRoundRect(rect, r, r, trazo)

        // La esquina de hoja de mapa. Es lo que hace que la tarjeta se lea como
        // una region de un mapa y no como una caja de dialogo.
        val lado = caja.menor * 0.065f
        trazo.color = Pincel.LINEA2
        canvas.drawLine(caja.x1 - m, caja.y1 - lado, caja.x1 - m, caja.y1 - m, trazo)
        canvas.drawLine(caja.x1 - lado, caja.y1 - m, caja.x1 - m, caja.y1 - m, trazo)
    }

    /**
     * "MOTOR · " y el motor del perfil, o "MOTOR · AVERIA (2)" si la ECU guarda codigos.
     *
     * El titulo cambia de COLOR pero no parpadea: un rotulo que titila es
     * ruido, y el color ya lo dice todo estando quieto. Ademas no oscila — o
     * hay codigo o no lo hay.
     */
    private fun pintaRotulo(canvas: Canvas, d: DatosTablero, pincel: Pincel) {
        val codigos = d.codigos ?: 0
        val averia = d.mil == true || codigos > 0
        val texto = if (averia) mTitulo.averia(codigos) else TITULO
        val color = when {
            d.mil == true -> Pincel.OXIDO
            codigos > 0 -> Pincel.OCRE
            else -> Pincel.ARENA
        }
        pincel.tituloDeSeccion(canvas, cRotulo, texto, color)
    }

    // =====================================================================
    // Las seis filas de dato
    // =====================================================================

    private fun pintaFilas(canvas: Canvas, d: DatosTablero, ahora: Long, pincel: Pincel) {
        // Las lineas finas que separan las bandas, como el `border-bottom` de
        // las filas del HTML. Las dos ultimas no la llevan (`.lastrow`).
        relleno.color = Pincel.LINEA
        val grosor = maxOf(1f, cFila[0].alto * 0.02f)
        var i = 0
        while (i < 4) {
            val c = cFila[i]
            canvas.drawRect(c.x0, c.y1 - grosor, c.x1, c.y1, relleno)
            i++
        }

        // AGUA: el unico numero de la tarjeta que puede gritar.
        pincel.filaGrande(
            canvas, cFila[0], "AGUA", mAgua.entero(d.agua), "°C",
            colorAgua(d.agua, ahora),
        )
        // RPM sin unidad y con separacion de miles: "2 270" se lee de reojo
        // mejor que "2270", y la coma se confundiria con el decimal.
        pincel.filaGrande(
            canvas, cFila[1], "RPM", mRpm.miles(d.rpm), null, tinta(d.rpm),
        )
        pincel.filaGrande(
            canvas, cFila[2], "AIRE", mAire.entero(d.aire), "°C", tinta(d.aire),
        )
        pincel.filaGrande(
            canvas, cFila[3], "CARGA", mCarga.entero(d.carga), "%", tinta(d.carga),
        )
        // El colector en PSI, que es como lo pidio el dueño. El puente ya lo
        // convirtio de kPa; aqui no se hace aritmetica de unidades.
        pincel.filaGrande(
            canvas, cFila[4], "COLECTOR", mColector.decimal(d.mapPsi), "PSI", tinta(d.mapPsi),
        )
        // El avance va sin unidad, igual que en el HTML.
        pincel.filaGrande(
            canvas, cFila[5], "AVANCE", mAvance.entero(d.avance), null, tinta(d.avance),
        )
    }

    // =====================================================================
    // El reloj de mezcla
    // =====================================================================

    /**
     * La esfera de 10 a 20 AFR, con sus tres zonas, sus marcas y su aguja.
     *
     * ⚠️ **Si este carro no lleva sonda de banda ancha la esfera va APAGADA y
     * SIN AGUJA.** No es un detalle de estilo: una sonda de banda estrecha
     * solo dice de que lado de la estequiometrica esta la mezcla. Pintar una aguja ahi seria inventar el unico numero que este
     * instrumento existe para enseñar. Con las zonas en gris se ve que el
     * reloj no aplica a este carro, y la cifra dice "SIN SONDA" en vez de
     * "AFR" para que no se confunda con un dato que todavia no ha llegado.
     */
    private fun pintaReloj(canvas: Canvas, d: DatosTablero) {
        val real = Carro.perfil.tieneAfrReal
        val valor = if (real) d.afr else null

        ovalo.set(ejeX - radio, ejeY - radio, ejeX + radio, ejeY + radio)
        arco.strokeWidth = radio * 0.185f

        // Las tres zonas. Los limites salen de la estequiometrica del
        // combustible, no de dos numeros sueltos: rica y pobre son "0,7 AFR a
        // cada lado de donde la ECU quiere estar".
        val stq = Carro.motor.afrEstequiometrica
        val rica = grados(stq - MEDIA_BANDA)
        val pobre = grados(stq + MEDIA_BANDA)

        arco.color = if (real) OCRE_RICA else Pincel.LINEA2
        canvas.drawArc(ovalo, INICIO, rica - INICIO, false, arco)
        arco.color = if (real) Pincel.MUSGO else Pincel.LINEA2
        canvas.drawArc(ovalo, rica, pobre - rica, false, arco)
        // POBRE va en rojo porque es el lado que sube la temperatura de
        // combustion: rica ensucia, pobre funde.
        arco.color = if (real) Pincel.OXIDO else Pincel.LINEA2
        canvas.drawArc(ovalo, pobre, FINAL - pobre, false, arco)

        // Graduacion: 12 y 18 tenues, la estequiometrica destacada.
        trazo.color = if (real) Pincel.ARENA else Pincel.PUNTO
        trazo.strokeWidth = maxOf(1f, radio * 0.029f)
        canvas.drawLines(marcasTenues, trazo)
        trazo.color = if (real) Pincel.TINTA else Pincel.APAGADO
        trazo.strokeWidth = maxOf(1f, radio * 0.042f)
        canvas.drawLine(marcaStq[0], marcaStq[1], marcaStq[2], marcaStq[3], trazo)

        // LA AGUJA. Solo si hay un numero medido detras.
        if (valor != null && valor.isFinite()) {
            val a = Math.toRadians(grados(valor).toDouble())
            aguja.color = Pincel.TINTA
            aguja.strokeWidth = maxOf(1.5f, radio * 0.068f)
            canvas.drawLine(
                ejeX, ejeY,
                ejeX + radio * 0.816f * Math.cos(a).toFloat(),
                ejeY + radio * 0.816f * Math.sin(a).toFloat(),
                aguja,
            )
            relleno.color = Pincel.TARJETA
            canvas.drawCircle(ejeX, ejeY, radio * 0.0895f, relleno)
            trazo.color = Pincel.TINTA
            trazo.strokeWidth = maxOf(1f, radio * 0.042f)
            canvas.drawCircle(ejeX, ejeY, radio * 0.0895f, trazo)
        }

        // La cifra, dentro de la esfera.
        cifra.textSize = radio * 0.42f
        cifra.color = if (valor == null) Pincel.APAGADO else Pincel.TINTA
        val texto = if (valor == null) Pincel.SIN_DATO else mAfr.decimal(valor)
        // Si no cupiera, encoge; y si ni encogiendo, se marca. Un numero que se
        // sale de la esfera pisa la rejilla de al lado.
        if (!centrado(canvas, cifra, texto, ejeX, ejeY + radio * 0.342f, radio * 1.4f)) {
            marcaEsfera(canvas)
        }

        rotulito.textSize = radio * 0.147f
        rotulito.color = Pincel.APAGADO
        // "SIN SONDA" y "AFR" dicen cosas distintas: el primero es "este carro
        // no puede medirlo nunca"; el segundo, "todavia no ha llegado".
        val unidad = if (real) "AFR" else "SIN SONDA"
        centrado(canvas, rotulito, unidad, ejeX, ejeY + radio * 0.553f, radio * 1.6f)

        rotulito.textSize = radio * 0.142f
        rotulito.color = if (real) Pincel.APAGADO else Pincel.PUNTO
        val yExtremos = ejeY + radio * 0.789f
        centrado(canvas, rotulito, "RICA", ejeX - radio * 0.868f, yExtremos, radio * 0.6f)
        centrado(canvas, rotulito, "POBRE", ejeX + radio * 0.868f, yExtremos, radio * 0.6f)
    }

    /**
     * El pie del reloj: los AJUSTES de combustible.
     *
     * Cuando no hay AFR real esta fila MANDA —se le dio el doble de alto en
     * [medir], asi que [Pincel.filaGrande] la pinta con el numero grande— y es
     * lo correcto: la suma de los ajustes corto y largo es un porcentaje
     * medido de verdad, mientras que el AFR de ese carro no existe.
     *
     * El color sigue la escala de la casa: rojo POBRE, ambar RICA, verde bien.
     * Fijo, sin parpadear: el parpadeo esta reservado para el agua.
     */
    private fun pintaAjustes(canvas: Canvas, d: DatosTablero, pincel: Pincel) {
        pincel.filaGrande(
            canvas, cAjustes, "AJUSTES", mTrim.conSigno(d.trim), "%", colorMezcla(d.trim),
        )
    }

    // =====================================================================
    // La lampara del VTEC
    // =====================================================================

    /**
     * Lampara de estado, DEDUCIDA.
     *
     * OBD-II generico no expone el solenoide del VTEC en ningun carro: esto se
     * infiere de rpm mas carga, y por eso va dentro del recinto discontinuo y
     * lleva el rotulo "DEDUCIDO" al lado del nombre. Un dato inferido pintado
     * igual que uno medido convierte una suposicion en un hecho.
     *
     * Hay motores en los que engancha sobre 2.200 rpm y suelta sobre 2.100:
     * entra y sale en cada cuesta, asi que es una lampara y no un fogonazo. En
     * otros ocurre a 5.850 con el pedal a fondo y SI es un acontecimiento
     * ([Carro.perfil.vtecEsAcontecimiento]), asi que ahi el enganche llena
     * la franja de color en vez de solo encender el borde. En ninguno
     * parpadea: eso lo tiene reservado el agua.
     */
    private fun pintaVtec(canvas: Canvas, d: DatosTablero, pincel: Pincel) {
        val on = d.vtec == true
        val grande = on && Carro.perfil.vtecEsAcontecimiento

        if (on) {
            val r = cVtec.menor * 0.06f
            rect.set(cVtec.x0, cVtec.y0, cVtec.x1, cVtec.y1)
            relleno.color = if (grande) FONDO_VTEC_FUERTE else FONDO_VTEC
            canvas.drawRoundRect(rect, r, r, relleno)

            val grosor = maxOf(1f, cVtec.menor * 0.035f)
            trazo.color = Pincel.MUSGO
            trazo.strokeWidth = grosor
            val m = grosor * 0.5f
            rect.set(cVtec.x0 + m, cVtec.y0 + m, cVtec.x1 - m, cVtec.y1 - m)
            canvas.drawRoundRect(rect, r, r, trazo)

            // La barra de la izquierda, que es lo que se ve de reojo. Su ancho
            // es el `inset 3px` del HTML sobre una franja de 38, y se queda
            // POR DENTRO del margen de [cVtecDentro]: mas gruesa se metia
            // debajo del rayo. Que un carro lo viva como un acontecimiento se
            // dice con el fondo y con el tamaño del texto, no ensanchando esto.
            relleno.color = Pincel.MUSGO
            canvas.drawRect(
                cVtec.x0, cVtec.y0, cVtec.x0 + cVtec.menor * 0.08f, cVtec.y1, relleno,
            )
        } else {
            // El recinto discontinuo de lo deducido.
            pincel.recintoDeducido(canvas, cVtec)
        }

        val dentro = cVtecDentro
        val alto = cVtec.alto
        val color = if (on) Pincel.MUSGO else Pincel.APAGADO

        // El rayo. El Path ya esta construido sobre esta misma caja.
        trazo.color = if (on) Pincel.MUSGO else Pincel.LINEA2
        trazo.strokeWidth = maxOf(1f, alto * 0.045f)
        canvas.drawPath(rayo, trazo)

        val estado = when (d.vtec) {
            true -> "ENGANCHADO"
            false -> "EN REPOSO"
            null -> Pincel.SIN_DATO
        }

        // EL ESTADO MANDA, igual que el numero manda en una fila: se mide
        // primero y se queda con lo suyo. El nombre y el rotulo de "deducido"
        // ceden en lo que sobre.
        palabra.textSize = alto * (if (grande) 0.40f else 0.34f)
        palabra.color = color
        palabra.textAlign = Paint.Align.RIGHT
        var cupo = true
        var anchoEstado = palabra.measureText(estado)
        val techo = dentro.ancho * 0.62f
        if (anchoEstado > techo && anchoEstado > 0f) {
            val f = techo / anchoEstado
            palabra.textSize *= f
            anchoEstado = palabra.measureText(estado)
            if (f < Pincel.TOLERANCIA_VALOR) cupo = false
        }
        canvas.drawText(estado, dentro.x1, linea(palabra, dentro), palabra)

        // A la izquierda: rayo, nombre, y el rotulo de DEDUCIDO.
        val tras = dentro.x0 + alto * 0.52f
        val libre = dentro.x1 - anchoEstado - alto * 0.25f - tras

        palabra.textAlign = Paint.Align.LEFT
        palabra.color = if (on) Pincel.TINTA else Pincel.APAGADO
        palabra.textSize = alto * 0.34f
        val letras = encoge(palabra, NOMBRE, libre, alto * 0.34f, alto * 0.34f * Pincel.SUELO)
        if (letras > 0) canvas.drawText(NOMBRE, 0, letras, tras, linea(palabra, dentro), palabra)
        // Media palabra no nombra nada: "VT" al lado de "ENGANCHADO" no dice de
        // que va lo enganchado. Recortar el nombre cuenta como no caber.
        if (letras < NOMBRE.length) cupo = false

        val restante = libre - palabra.measureText(NOMBRE, 0, letras) - alto * 0.22f
        rotulito.textAlign = Paint.Align.LEFT
        rotulito.textSize = alto * 0.185f
        rotulito.color = if (on) Pincel.ARENA else Pincel.APAGADO
        val chip = rotulito.measureText(DEDUCIDO)
        if (chip <= restante) {
            canvas.drawText(
                DEDUCIDO,
                dentro.x1 - anchoEstado - alto * 0.25f - chip,
                linea(rotulito, dentro),
                rotulito,
            )
        } else if (on) {
            // Sin el rotulo Y sin el recinto discontinuo —que solo se pinta
            // apagado— nada diria que esto es deducido. Eso no es ceder, es
            // perder la advertencia, y se marca.
            cupo = false
        }
        rotulito.textAlign = Paint.Align.CENTER

        if (!cupo) pincel.marcaDeQueNoCabe(canvas, cVtec)
    }

    // =====================================================================
    // Colores por umbral. La disciplina del proyecto, con los cortes en
    // EngineConstants para que cada carro traiga los suyos.
    // =====================================================================

    /**
     * Azul frio, verde, ambar, rojo.
     *
     * Los cortes son los del refrigerante, no los del motor: el termostato
     * abre sobre `COOLANT_TIBIO_C`, a `COOLANT_AVISO_C` el ventilador ya
     * deberia estar corriendo, y `COOLANT_HIGH_C` es problema de verdad.
     *
     * ⚠️ Es lo UNICO que parpadea en esta tarjeta. Y con el radio caliente, a
     * un cuadro por segundo, el parpadeo de 2 Hz se convierte en un cambio
     * irregular entre oxido y ocre — sigue leyendose como "esto se esta
     * moviendo", que es lo que tiene que decir, pero deja de ser un ritmo. Es
     * el precio de bajar los cuadros por segundo, y se paga con gusto.
     */
    private fun colorAgua(c: Int?, ahora: Long): Int = when {
        c == null -> Pincel.APAGADO
        c >= Carro.motor.coolantHighC -> if (Latido.parpadeo(ahora)) Pincel.OXIDO else Pincel.OCRE
        c >= Carro.motor.coolantAvisoC -> Pincel.OCRE
        c >= Carro.motor.coolantTibioC -> Pincel.MUSGO
        else -> Pincel.LAGO
    }

    /**
     * Rojo POBRE, ambar RICA, verde en su sitio.
     *
     * El corte esta en el 10 %, que es donde cualquier taller empieza a mirar.
     * Pobre se lleva el rojo porque es el lado que sube la temperatura de
     * combustion; rica ensucia y gasta, pero no funde nada.
     *
     * El puente ya garantiza que este numero es la suma de los DOS ajustes o
     * null: rellenar con cero el que falte diria "corrige perfecto", que es la
     * respuesta contraria a "no lo se".
     */
    private fun colorMezcla(total: Int?): Int = when {
        total == null -> Pincel.APAGADO
        total >= 10 -> Pincel.OXIDO
        total <= -10 -> Pincel.OCRE
        else -> Pincel.MUSGO
    }

    /** Tinta viva si hay dato, apagada si no. La regla en una linea. */
    private fun tinta(v: Any?): Int = if (v == null) Pincel.APAGADO else Pincel.TINTA


    // =====================================================================
    // Cocina
    // =====================================================================

    /** Angulo, en grados de Canvas, del valor [afr] sobre la esfera. */
    private fun grados(afr: Float): Float {
        val min = Carro.motor.afrMin
        val max = Carro.motor.afrMax
        val t = ((afr - min) / (max - min)).coerceIn(0f, 1f)
        return INICIO + t * (FINAL - INICIO)
    }

    /** Guarda en [destino] los cuatro floats de una marca de graduacion. */
    private fun marca(destino: FloatArray, i: Int, afr: Float) {
        val a = Math.toRadians(grados(afr).toDouble())
        val cx = Math.cos(a).toFloat()
        val sy = Math.sin(a).toFloat()
        destino[i] = ejeX + radio * cx
        destino[i + 1] = ejeY + radio * sy
        destino[i + 2] = ejeX + radio * 0.842f * cx
        destino[i + 3] = ejeY + radio * 0.842f * sy
    }

    /** El rayo de la lampara, trazado sobre [cVtecDentro]. Se hace en medir. */
    private fun construirRayo() {
        rayo.rewind()
        if (!cVtecDentro.valida) return
        val lado = cVtec.alto * 0.40f
        val x = cVtecDentro.x0
        val y = cVtecDentro.cy - lado * 0.5f
        fun px(v: Float) = x + lado * (v / 16f)
        fun py(v: Float) = y + lado * (v / 16f)
        rayo.moveTo(px(2.2f), py(9.6f))
        rayo.lineTo(px(8.6f), py(1.4f))
        rayo.lineTo(px(8.6f), py(6.6f))
        rayo.lineTo(px(13.8f), py(6.6f))
        rayo.lineTo(px(7.4f), py(14.6f))
        rayo.lineTo(px(7.4f), py(9.6f))
        rayo.close()
    }

    /** Linea base que centra verticalmente en [caja], con metricas de fuente. */
    private fun linea(p: Paint, caja: Caja): Float {
        p.getFontMetrics(metricas)
        return caja.cy - (metricas.ascent + metricas.descent) * 0.5f
    }

    /**
     * Texto centrado en [x] que encoge si no cabe en [anchoMax].
     *
     * @return false si ni encogiendo entra en un tamaño legible. Quien llama
     *   decide si eso merece una marca; en la esfera si la merece, porque
     *   salirse de ella significa pisar la rejilla de datos de al lado.
     */
    private fun centrado(
        canvas: Canvas,
        p: Paint,
        texto: String,
        x: Float,
        y: Float,
        anchoMax: Float,
    ): Boolean {
        val ideal = p.textSize
        val medido = p.measureText(texto)
        if (medido > anchoMax && medido > 0f) {
            val f = anchoMax / medido
            p.textSize = ideal * f
            canvas.drawText(texto, x, y, p)
            p.textSize = ideal
            return f >= Pincel.SUELO
        }
        canvas.drawText(texto, x, y, p)
        return true
    }

    /**
     * Mide, encoge y —si hace falta— CORTA. Devuelve cuantas letras caben.
     *
     * Es la misma regla de [Pincel]: el suelo no autoriza a salirse. Llegado
     * el suelo se corta con `breakText`, que ademas no fabrica una subcadena.
     * Vive aqui porque la version de [Pincel] es privada y esta pensada para
     * sus propias filas.
     */
    private fun encoge(p: Paint, texto: String, anchoMax: Float, ideal: Float, suelo: Float): Int {
        if (texto.isEmpty() || anchoMax <= 0f) return 0
        p.textSize = ideal
        val medido = p.measureText(texto)
        if (medido <= anchoMax) return texto.length
        val proporcional = ideal * (anchoMax / medido)
        if (proporcional >= suelo) {
            p.textSize = proporcional
            return texto.length
        }
        p.textSize = suelo
        return p.breakText(texto, true, anchoMax, null)
    }

    /** Aspa sobre la esfera cuando su propia cifra no cabe dentro. */
    private fun marcaEsfera(canvas: Canvas) {
        val grosor = maxOf(2f, radio * 0.06f)
        trazo.color = Pincel.OXIDO
        trazo.strokeWidth = grosor
        canvas.drawCircle(ejeX, ejeY, radio * 0.5f, trazo)
        val q = radio * 0.35f
        canvas.drawLine(ejeX - q, ejeY - q, ejeX + q, ejeY + q, trazo)
        canvas.drawLine(ejeX + q, ejeY - q, ejeX - q, ejeY + q, trazo)
    }

    // =====================================================================
    // Constantes
    // =====================================================================

    /** Presupuesto vertical de la tarjeta del HTML: rotulo 28, cuerpo 164. */
    private val PESOS_TARJETA = floatArrayOf(28f, 164f)

    /** Reloj 150, rejilla 310. Los mismos numeros que el CSS. */
    private val PESOS_CUERPO = floatArrayOf(150f, 310f)

    /**
     * Con sonda: manda la esfera.
     *
     * En el HTML el pie de ajustes ocupa 22 de los 164, pero alli el rotulo
     * "Ajustes" es 0,6 del valor y aqui [Pincel.ETIQUETA_FILA] lo pone en
     * 0,24 del alto de la caja. Con 22 salia una etiqueta de 5 px —medida, no
     * supuesta— que no se lee ni parado. Se le dan 36: la esfera pierde un 8 %
     * de radio y el pie gana una etiqueta legible, que es el cambio bueno.
     */
    private val PESOS_RELOJ = floatArrayOf(128f, 36f)

    /** Sin sonda: la esfera se encoge y mandan los ajustes. */
    private val PESOS_RELOJ_MUDO = floatArrayOf(96f, 68f)

    /** Rejilla de datos 120, lampara del VTEC 42. */
    private val PESOS_DERECHA = floatArrayOf(120f, 42f)

    /** Arranque de la esfera, en grados de Canvas (0 = las tres en punto). */
    private const val INICIO = 150f

    /** Fin de la esfera: 240 grados de barrido, como el SVG del HTML. */
    private const val FINAL = 390f

    /**
     * Medio ancho de la banda estequiometrica, en AFR.
     *
     * Sale de las zonas del HTML —14,0 a 15,4— que son exactamente 0,7 a cada
     * lado de 14,7. Escrito asi, la banda sigue a la estequiometrica del
     * combustible en vez de ser dos numeros sueltos que habria que corregir a
     * mano si algun dia se mide otro carburante.
     */
    private const val MEDIA_BANDA = 0.7f

    /** El ocre de la zona rica: mas apagado que el ocre de rotulo del HTML. */
    private const val OCRE_RICA = 0xFFC98A3C.toInt()

    /** Velo de la lampara del VTEC enganchada. */
    private const val FONDO_VTEC = 0x229CBE7A
    private const val FONDO_VTEC_FUERTE = 0x449CBE7A

    private const val DEDUCIDO = "DEDUCIDO"

    private const val NOMBRE = "VTEC"

    /**
     * El titulo, ya montado.
     *
     * `Carro.perfil.motor` es una constante de compilacion, asi que esta
     * cadena se fabrica una vez al cargar la clase y nunca mas.
     */
    private val TITULO = "MOTOR · " + Carro.perfil.motor

    // =====================================================================

    /**
     * Un numero ya formateado, que solo se rehace cuando el numero cambia.
     *
     * ## Por que existe
     *
     * `Pincel` recibe `String`, y convertir un numero a `String` aloca. En un
     * tablero que repinta durante horas eso es basura por cuadro y por fila —
     * justo lo que prohibe la regla 7. Aqui se guarda la ultima cadena junto a
     * la clave que la produjo: si el valor no cambio, se devuelve la misma
     * instancia y **no se aloca nada**. Parado, o con el radio caliente a un
     * cuadro por segundo, este pintor no fabrica ni un objeto.
     *
     * Cuando el numero SI cambia se crea su cadena, y eso no se puede evitar
     * sin que `Pincel` acepte `char[]` —que `Canvas.drawText` y
     * `Paint.measureText` si soportan—. Queda anotado como lo que es: la
     * unica alocacion del camino de dibujo, proporcional a los cambios de
     * valor y no a los cuadros.
     *
     * ## Ojo con compartirlo
     *
     * [PintaMotor] es un `object`, asi que estos Memo son unicos en el
     * proceso. Con dos vistas pintando datos distintos a la vez cada llamada
     * invalidaria la del otro y se reformatearia mas de la cuenta: seguiria
     * pintando BIEN —la cadena se calcula del valor que entra, siempre— pero
     * perderia la ventaja. Hoy hay un tablero.
     */
    private class Memo {
        private var clave = Long.MIN_VALUE
        private var vacio = true
        private var texto: String = Pincel.SIN_DATO

        /** Entero pelado. */
        fun entero(v: Int?): String {
            if (v == null) return nada()
            if (vacio || clave != v.toLong()) {
                vacio = false
                clave = v.toLong()
                texto = v.toString()
            }
            return texto
        }

        /**
         * Entero con separacion de miles por ESPACIO FINO.
         *
         * Un "2270" se lee peor de reojo que un "2 270", y una coma se
         * confundiria con el separador decimal del colector de al lado.
         */
        fun miles(v: Int?): String {
            if (v == null) return nada()
            if (vacio || clave != v.toLong()) {
                vacio = false
                clave = v.toLong()
                texto = conMiles(v)
            }
            return texto
        }

        /** Entero con signo siempre visible: "+3", "-4". */
        fun conSigno(v: Int?): String {
            if (v == null) return nada()
            if (vacio || clave != v.toLong()) {
                vacio = false
                clave = v.toLong()
                texto = if (v > 0) "+$v" else v.toString()
            }
            return texto
        }

        /**
         * Un decimal.
         *
         * La clave son DECIMAS, no el float: asi el ruido por debajo de lo que
         * se ve no rehace la cadena. 4,52 y 4,54 se pintan igual, y ahora
         * tampoco alocan dos veces.
         */
        fun decimal(v: Float?): String {
            if (v == null || !v.isFinite()) return nada()
            val d = Math.round(v * 10f).toLong()
            if (vacio || clave != d) {
                vacio = false
                clave = d
                val signo = if (d < 0) "-" else ""
                val abs = if (d < 0) -d else d
                texto = signo + (abs / 10L) + "." + (abs % 10L)
            }
            return texto
        }

        /** "MOTOR · AVERIA (2)". Solo se rehace al cambiar el numero de codigos. */
        fun averia(codigos: Int): String {
            if (vacio || clave != codigos.toLong()) {
                vacio = false
                clave = codigos.toLong()
                texto = "MOTOR · AVERIA ($codigos)"
            }
            return texto
        }

        private fun nada(): String {
            if (!vacio) {
                vacio = true
                clave = Long.MIN_VALUE
                texto = Pincel.SIN_DATO
            }
            return texto
        }

        private fun conMiles(v: Int): String {
            val negativo = v < 0
            var n = if (negativo) -v.toLong() else v.toLong()
            if (n < 1000L) return v.toString()
            val sb = StringBuilder(8)
            var grupos = 0
            while (n > 0L) {
                if (grupos > 0 && grupos % 3 == 0) sb.append(FINO)
                sb.append(('0' + (n % 10L).toInt()))
                n /= 10L
                grupos++
            }
            if (negativo) sb.append('-')
            return sb.reverse().toString()
        }

        private companion object {
            /** Espacio fino U+2009 —invisible aqui—, el que usa la variante HTML. */
            const val FINO = ' '
        }
    }
}
