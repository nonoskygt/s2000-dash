package com.nonosky.carsoc.ui.lienzo

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.nonosky.carsoc.config.CalibracionLlantas

/**
 * EL CALIBRADOR DE LLANTAS, EN CANVAS.
 *
 * El gemelo del que ya vive en los dos tableros HTML. Existe porque la
 * variante Canvas se quedo sin el: se podia elegir el tablero ligero en
 * ajustes y perder la unica forma de corregir unos sensores que se sabe que
 * mienten. Dos tableros del mismo carro que hacen cosas distintas con el
 * mismo dedo es peor que tener uno solo.
 *
 * ## Por que se dibuja aqui y no en un dialogo del sistema
 *
 * Un `AlertDialog` habria costado un tercio. Pero este tablero se mira de
 * reojo con el carro andando y su tipografia esta calibrada para eso; un
 * dialogo de Android llega con la letra del sistema, sus margenes y su tema
 * claro/oscuro, y en la pantalla de un carro eso se lee como si otra
 * aplicacion se hubiera puesto delante. Ademas obligaria a `TableroLienzo`
 * —que hoy no sabe de `Activity` ni de temas— a conocer un `Context` de UI.
 *
 * ## Se abre con el dedo SOSTENIDO, no con un toque
 *
 * Igual que en el HTML, y por el mismo motivo: a media curva se toca
 * cualquier cosa. Un toque suelto sobre una rueda no debe abrir nada.
 *
 * ## Lo que este objeto NO hace
 *
 * No guarda nada. Solo dice que se ha tocado; quien escribe en
 * [CalibracionLlantas] es la vista, que si tiene `Context`. Y no cachea el
 * ajuste: lo recibe pintado en cada cuadro, para que lo que se ve sea lo que
 * hay guardado y no una copia que se pueda quedar atras.
 */
object PintaCalibracion {

    /** Los mandos del calibrador. `FUERA` es el velo: cierra. */
    enum class Mando { MENOS, MAS, TODAS, CERO, LISTO, FUERA }

    /** La rueda que se esta calibrando, o -1 si el calibrador esta cerrado. */
    var rueda: Int = -1
        private set

    val abierto: Boolean get() = rueda >= 0

    fun abrir(cual: Int) {
        if (cual in 0 until CalibracionLlantas.RUEDAS) {
            rueda = cual
            pulsado = null
        }
    }

    fun cerrar() {
        rueda = -1
        pulsado = null
    }

    /** Que mando esta bajo el dedo ahora mismo, para resaltarlo. */
    private var pulsado: Mando? = null

    // --- Cajas del ultimo reparto -------------------------------------------
    //
    // Se reparten una vez por tamaño de pantalla, no por cuadro, igual que el
    // resto del tablero.

    private var pantalla: Caja = Caja.NADA
    private var valido = false

    private var marco: Caja = Caja.NADA
    private var cTitulo: Caja = Caja.NADA
    private var cNota1: Caja = Caja.NADA
    private var cNota2: Caja = Caja.NADA
    private var cMenos: Caja = Caja.NADA
    private var cValor: Caja = Caja.NADA
    private var cMas: Caja = Caja.NADA
    private var cTodas: Caja = Caja.NADA
    private var cMarca: Caja = Caja.NADA
    private var cCero: Caja = Caja.NADA
    private var cListo: Caja = Caja.NADA

    // --- Pinceles, todos preasignados ---------------------------------------

    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val negrita = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val normal = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

    private val titulo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = negrita
        letterSpacing = 0.14f
    }
    private val nota = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = normal }
    private val cifra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = negrita
        textAlign = Paint.Align.CENTER
    }
    private val signo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = negrita
        textAlign = Paint.Align.CENTER
    }

    private val metricas = Paint.FontMetrics()
    private val rect = RectF()

    // --- Reparto ------------------------------------------------------------

    /**
     * Reparte el calibrador dentro de [caja], que es la pantalla entera.
     *
     * El marco se centra y ocupa una fraccion de la pantalla con TOPES: en un
     * radio de 1280x480 un 46 % de alto deja el paso de 44 px justo, y en una
     * tableta un 42 % de ancho daria un cuadro absurdo de media pantalla.
     */
    private fun reparte(caja: Caja) {
        if (caja == pantalla) return
        pantalla = caja
        valido = false
        if (!caja.valida) return

        val ancho = (caja.ancho * ANCHO).coerceAtMost(caja.ancho - caja.menor * 0.10f)
        val alto = (caja.alto * ALTO).coerceAtMost(caja.alto - caja.menor * 0.08f)
        marco = Caja(
            caja.cx - ancho * 0.5f, caja.cy - alto * 0.5f,
            caja.cx + ancho * 0.5f, caja.cy + alto * 0.5f,
        )
        if (!marco.valida) return

        val dentro = marco.margenRelativo(0.055f)
        if (!dentro.valida) return

        val aire = dentro.menor * 0.035f
        val filas = Reparto.filas(dentro, PESOS_FILAS, aire)
        cTitulo = filas[0]
        val notas = Reparto.filasIguales(filas[1], 2)
        cNota1 = notas[0]
        cNota2 = notas[1]
        val paso = Reparto.columnas(filas[2], PESOS_PASO, aire)
        cMenos = paso[0]
        cValor = paso[1]
        cMas = paso[2]
        cTodas = filas[3]
        cMarca = Caja(
            cTodas.x0, cTodas.cy - cTodas.alto * 0.34f,
            cTodas.x0 + cTodas.alto * 0.68f, cTodas.cy + cTodas.alto * 0.34f,
        )
        val botones = Reparto.columnasIguales(filas[4], 2, aire)
        cCero = botones[0]
        cListo = botones[1]

        valido = cTitulo.valida && cNota1.valida && cMenos.valida && cValor.valida &&
            cMas.valida && cTodas.valida && cMarca.valida && cCero.valida && cListo.valida

        // 44 px de dedo, la misma regla que la cabecera. Si el calibrador no
        // los da, no se pinta: unos mandos que no se aciertan en movimiento
        // son peores que no tenerlos, porque el que falla el "-" acierta el
        // "+" y corrige al reves.
        if (valido && minOf(cMenos.alto, cMenos.ancho, cCero.alto) < DEDO) valido = false
    }

    // --- Pintado ------------------------------------------------------------

    /**
     * Pinta el calibrador sobre lo que ya haya. No hace nada si esta cerrado.
     *
     * [ajuste] y [todas] llegan de fuera EN CADA CUADRO, leidos de las
     * preferencias por quien si tiene `Context`. Este objeto no los guarda a
     * proposito: una copia local se quedaria atras en cuanto algo los cambiara
     * por otro camino, y el numero que se ve dejaria de ser el que manda.
     */
    fun pintar(
        canvas: Canvas,
        caja: Caja,
        ajuste: Float,
        todas: Boolean,
        pincel: Pincel,
    ) {
        if (!abierto) return
        reparte(caja)
        if (!caja.valida) return

        // El velo. Tapa el tablero de verdad —no lo oscurece un poco— porque
        // mientras se calibra, lo de detras no es lo que hay que mirar. Y
        // ademas es lo que hace evidente que el toque de fuera cierra.
        relleno.color = VELO
        canvas.drawRect(caja.x0, caja.y0, caja.x1, caja.y1, relleno)

        if (!valido) {
            pincel.marcaDeQueNoCabe(canvas, caja)
            return
        }

        // El marco.
        val radio = marco.menor * 0.03f
        rect.set(marco.x0, marco.y0, marco.x1, marco.y1)
        relleno.color = Pincel.TARJETA
        canvas.drawRoundRect(rect, radio, radio, relleno)
        trazo.color = Pincel.LINEA2
        trazo.strokeWidth = maxOf(1f, marco.menor * 0.004f)
        canvas.drawRoundRect(rect, radio, radio, trazo)

        // Titulo: dice QUE rueda. Sin esto, con la marca de "las cuatro"
        // apagada, no habria forma de saber cual se esta tocando.
        titulo.color = Pincel.TINTA
        titulo.textSize = cTitulo.alto * 0.72f
        titulo.textAlign = Paint.Align.LEFT
        canvas.drawText("CALIBRAR ${ESQUINAS[rueda]}", cTitulo.x0, base(titulo, cTitulo), titulo)

        nota.color = Pincel.APAGADO
        nota.textSize = cNota1.alto * 0.78f
        nota.textAlign = Paint.Align.LEFT
        canvas.drawText(NOTA1, cNota1.x0, base(nota, cNota1), nota)
        canvas.drawText(NOTA2, cNota2.x0, base(nota, cNota2), nota)

        pastilla(canvas, cMenos, "−", pulsado == Mando.MENOS)
        pastilla(canvas, cMas, "+", pulsado == Mando.MAS)

        // El numero con SIGNO SIEMPRE, "+0.0" incluido: un "0.0" pelado se
        // lee como "no hay correccion", y aqui cero es una correccion tan
        // elegida como cualquier otra.
        cifra.color = Pincel.TINTA
        cifra.textSize = cValor.alto * 0.62f
        val texto = String.format("%+.1f", ajuste)
        val anchoNum = cifra.measureText(texto)
        nota.textSize = cValor.alto * 0.24f
        nota.color = Pincel.ARENA
        val anchoUni = nota.measureText(" PSI")
        val centro = cValor.cx - anchoUni * 0.5f
        canvas.drawText(texto, centro, base(cifra, cValor), cifra)
        canvas.drawText(" PSI", centro + anchoNum * 0.5f, base(cifra, cValor), nota)

        // La marca de "las cuatro a la vez". Encendida por omision, y por eso
        // se pinta bien VISIBLE: una marca que parece apagada estandolo
        // corregiria las cuatro ruedas creyendo corregir una.
        rect.set(cMarca.x0, cMarca.y0, cMarca.x1, cMarca.y1)
        val rm = cMarca.menor * 0.16f
        trazo.color = if (todas) Pincel.MUSGO else Pincel.LINEA2
        trazo.strokeWidth = maxOf(1.5f, cMarca.menor * 0.09f)
        canvas.drawRoundRect(rect, rm, rm, trazo)
        if (todas) {
            trazo.color = Pincel.MUSGO
            val m = cMarca.menor
            canvas.drawLine(
                cMarca.x0 + m * 0.24f, cMarca.cy,
                cMarca.cx - m * 0.04f, cMarca.y1 - m * 0.24f, trazo,
            )
            canvas.drawLine(
                cMarca.cx - m * 0.04f, cMarca.y1 - m * 0.24f,
                cMarca.x1 - m * 0.20f, cMarca.y0 + m * 0.24f, trazo,
            )
        }
        nota.color = if (todas) Pincel.TINTA else Pincel.APAGADO
        nota.textSize = cTodas.alto * 0.46f
        val huecoTexto = cTodas.x1 - (cMarca.x1 + cTodas.alto * 0.30f)
        var t = TODAS
        if (nota.measureText(t) > huecoTexto) t = TODAS_CORTO
        canvas.drawText(t, cMarca.x1 + cTodas.alto * 0.30f, base(nota, cTodas), nota)

        pastilla(canvas, cCero, "A CERO", pulsado == Mando.CERO)
        pastilla(canvas, cListo, "LISTO", pulsado == Mando.LISTO)
    }

    /** Una pastilla con borde y una palabra centrada. */
    private fun pastilla(canvas: Canvas, caja: Caja, texto: String, activo: Boolean) {
        val radio = caja.menor * 0.10f
        rect.set(caja.x0, caja.y0, caja.x1, caja.y1)
        relleno.color = if (activo) PULSADO else Pincel.REBAJE
        canvas.drawRoundRect(rect, radio, radio, relleno)
        trazo.color = if (activo) Pincel.OCRE else Pincel.LINEA2
        trazo.strokeWidth = maxOf(1f, caja.menor * 0.025f)
        canvas.drawRoundRect(rect, radio, radio, trazo)

        signo.color = if (activo) Pincel.TINTA else Pincel.ARENA
        // La letra sale del alto de LA PASTILLA. Un solo caracter puede ir
        // grande; una palabra tiene que medirse y ceder, o se sale por los
        // lados en la pantalla estrecha.
        var tam = caja.alto * (if (texto.length <= 1) 0.52f else 0.34f)
        signo.textSize = tam
        val hueco = caja.ancho * 0.86f
        val medido = signo.measureText(texto)
        if (medido > hueco && medido > 0f) {
            tam = maxOf(tam * 0.55f, tam * hueco / medido)
            signo.textSize = tam
        }
        canvas.drawText(texto, caja.cx, base(signo, caja), signo)
    }

    private fun base(p: Paint, caja: Caja): Float {
        p.getFontMetrics(metricas)
        return caja.cy - (metricas.ascent + metricas.descent) * 0.5f
    }

    // --- El dedo ------------------------------------------------------------

    /** Que mando hay en ese punto. Nunca null con el calibrador abierto. */
    fun mandoEn(x: Float, y: Float): Mando? {
        if (!abierto || !valido) return null
        return when {
            cMenos.contiene(x, y) -> Mando.MENOS
            cMas.contiene(x, y) -> Mando.MAS
            cTodas.contiene(x, y) -> Mando.TODAS
            cCero.contiene(x, y) -> Mando.CERO
            cListo.contiene(x, y) -> Mando.LISTO
            // Dentro del marco pero en ningun mando: se traga el toque. Si
            // cayera a FUERA, fallar el "+" por dos pixeles CERRARIA el
            // calibrador en vez de no hacer nada.
            marco.contiene(x, y) -> null
            else -> Mando.FUERA
        }
    }

    fun tocar(x: Float, y: Float): Mando? {
        val m = mandoEn(x, y)
        pulsado = if (m == Mando.FUERA) null else m
        return m
    }

    fun soltar() {
        pulsado = null
    }

    // --- Constantes ---------------------------------------------------------

    /** El orden de siempre: DI, DD, TI, TD. */
    private val ESQUINAS = arrayOf("DI", "DD", "TI", "TD")

    private const val NOTA1 = "Corrige lo que reporta el sensor. Estos sensores se"
    private const val NOTA2 = "desvían por una constante: ajusta hasta que cuadre."

    private const val TODAS = "Aplicar la misma corrección a las cuatro llantas"
    private const val TODAS_CORTO = "Aplicar a las cuatro llantas"

    /** Fraccion de pantalla que ocupa el marco. */
    private const val ANCHO = 0.44f
    private const val ALTO = 0.50f

    /** Titulo, notas, paso, marca, botones. Los pesos del modal del HTML. */
    private val PESOS_FILAS = floatArrayOf(22f, 40f, 74f, 32f, 48f)

    /** Menos, valor, mas. */
    private val PESOS_PASO = floatArrayOf(66f, 240f, 66f)

    /**
     * El blanco tactil minimo, en pixeles de verdad.
     *
     * Va en pixeles y no en fraccion a proposito: el dedo mide lo que mide
     * pase lo que pase con la pantalla. 44 es la cifra de Apple; Material
     * pide 48 dp, que en estos radios de baja densidad sale parecido.
     */
    private const val DEDO = 44f

    /** El velo, casi opaco. */
    private const val VELO = 0xE60D100E.toInt()

    /** El `:active` de las pastillas: ocre al 20 %, como en el HTML. */
    private const val PULSADO = 0x33E0A84A
}
