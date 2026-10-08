package com.nonosky.carsoc.diag

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import com.nonosky.carsoc.Carro
import com.nonosky.carsoc.DashService
import com.nonosky.carsoc.EstadoActual
import com.nonosky.carsoc.obd.Dtc
import kotlin.concurrent.thread

/**
 * La pantalla de averias. Vive APARTE del tablero, a proposito.
 *
 * ## Por que es su propia Activity y no una pestaña
 *
 * El dueño lo pidio asi —"que no este cargada siempre"— y ademas es lo
 * correcto en este aparato. El diagnostico necesita 81 codigos con sus
 * explicaciones, unos 36 KB de texto, y abre su propia conexion al OBD. Nada
 * de eso hace falta mientras se maneja.
 *
 * Al cerrarse suelta la tabla ([TablaDtc.soltar]) y la conexion. Mientras el
 * tablero esta en pantalla, este modulo ocupa lo que ocupa una clase sin
 * instanciar: nada.
 *
 * Se dibuja a mano en un Canvas como el resto del proyecto — inflar layouts
 * XML aqui traeria toda la maquinaria de AppCompat para tres botones y una
 * lista, en un rk3326 que ya va justo.
 */
class DiagnosticoActivity : Activity() {

    private lateinit var vista: VistaDiagnostico

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vista = VistaDiagnostico(this)
        setContentView(vista)

        // La tabla se carga AQUI, no en el arranque de la app.
        thread(isDaemon = true) {
            val n = TablaDtc.cargar(applicationContext)
            runOnUiThread { vista.tablaLista(n) }
        }
    }

    override fun onDestroy() {
        // Soltar la tabla al salir: 36 KB que no tienen por que seguir en el
        // heap mientras el dueño maneja.
        TablaDtc.soltar()
        super.onDestroy()
    }

    /**
     * El atras del sistema navega igual que el boton de la pantalla.
     *
     * Este radio no siempre pinta la barra de navegacion, pero el gesto y el
     * boton del volante si llegan. Sin esto, atras cerraba el diagnostico
     * entero desde el detalle de un codigo — tres pasos de golpe cuando se
     * pedia uno — y habia que volver a leer la computadora para recuperar lo
     * que ya estaba en pantalla.
     */
    @Deprecated("onBackPressed sigue siendo la via en API 30, que es la del radio")
    @Suppress("DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        if (!vista.retroceder()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /**
     * Suelta el adaptador ANTES de leer, y espera a que se entere.
     *
     * ⚠️ Cierra una deuda que la bitacora dejo apuntada: esta pantalla
     * llamaba al lector A PELO mientras el sondeo seguia corriendo. Con un
     * solo adaptador atendiendo un unico enlace RFCOMM, eso son dos sockets
     * contra el mismo aparato: o el segundo muere, o —peor— el clon acepta
     * los dos, una respuesta de RPM cae dentro del buffer del modo 03 y se
     * decodifica como averias que el carro NO TIENE.
     *
     * Se reutiliza el gancho del puente en vez de duplicar su logica. Los
     * seis segundos NO son de manual: con dos, medido en el carro, el
     * transporte se lanzaba contra un adaptador todavia ocupado y una
     * peticion tardo cuatro minutos en vez de medio.
     */
    private fun soltarElAdaptador(): String? {
        val soltar = EstadoActual.soltarBluetooth ?: return null
        return runCatching {
            val dicho = soltar()
            Thread.sleep(MS_SOLTAR_ADAPTADOR)
            dicho
        }.getOrNull()
    }

    private fun lector(): LectorDtc {
        val adapter = runCatching {
            (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull()
        return LectorDtc(adapter, DashService.macObd(applicationContext))
    }

    fun leerCodigos() {
        vista.trabajando("Soltando el adaptador y leyendo la computadora...")
        thread(isDaemon = true) {
            val r = conElAdaptador { lector().leer() }
            runOnUiThread { vista.mostrar(r, borrado = false) }
        }
    }

    fun borrarCodigos() {
        vista.trabajando("Soltando el adaptador y borrando...")
        thread(isDaemon = true) {
            val r = conElAdaptador { lector().borrar() }
            runOnUiThread { vista.mostrar(r, borrado = true) }
        }
    }

    /**
     * Toma el cerrojo de la lectura de codigos —el mismo que `/dtc`—, suelta
     * el adaptador, lee, y devuelve el cerrojo pase lo que pase. Mientras lo
     * tiene, el servicio no vuelve a levantar el sondeo encima del lector.
     */
    private fun conElAdaptador(leer: () -> LectorDtc.Resultado): LectorDtc.Resultado {
        val tomar = EstadoActual.tomarObdParaDtc
        if (tomar != null && !tomar()) {
            return LectorDtc.Resultado(error = "ya hay una lectura de codigos en marcha; espera a que termine")
        }
        return try {
            soltarElAdaptador()
            runCatching { leer() }.getOrElse {
                LectorDtc.Resultado(error = "${it.javaClass.simpleName}: ${it.message}")
            }
        } finally {
            if (tomar != null) runCatching { EstadoActual.soltarObdTrasDtc?.invoke() }
        }
    }
    /** La bolsa de aire va en su propia pantalla: es otra computadora. */
    fun abrirBolsasDeAire() {
        runCatching {
            startActivity(android.content.Intent(this, BolsasDeAireActivity::class.java))
        }
    }

    private companion object {
        /** Medido en el carro: con dos segundos no basta. */
        const val MS_SOLTAR_ADAPTADOR = 6_000L
    }
}

/**
 * Todo el dibujo del diagnostico.
 *
 * Tres estados y nada mas: el menu, el "trabajando", y el resultado. Se
 * separan por una enum en vez de por visibilidad de vistas porque asi es
 * imposible que dos se pinten a la vez.
 */
private class VistaDiagnostico(private val act: DiagnosticoActivity) : View(act) {

    private enum class Estado { MENU, TRABAJANDO, RESULTADO, DETALLE }

// El boton de atras del sistema navega igual que el de la pantalla; ver
// VistaDiagnostico.retroceder().

    private var estado = Estado.MENU
    private var mensaje = ""
    private var resultado: LectorDtc.Resultado? = null
    private var fueBorrado = false
    private var codigosEnPantalla = 0

    /** El codigo cuya explicacion se esta leyendo. */
    private var detalle: TablaDtc.Entrada? = null
    private var detalleCodigo: String = ""

    /** Desplazamiento de la lista, en pixeles. */
    private var desplazamiento = 0f
    private var altoContenido = 0f

    private val cajaLeer = RectF()
    private val cajaBorrar = RectF()

    /** Vacia (0x0) en el carro que no tiene tabla de la bolsa de aire. */
    private val cajaSrs = RectF()
    private val cajaVolver = RectF()
    private val cajasCodigo = mutableListOf<Pair<RectF, String>>()

    private val titulo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun tablaLista(n: Int) {
        codigosEnPantalla = n
        invalidate()
    }

    fun trabajando(m: String) {
        estado = Estado.TRABAJANDO
        mensaje = m
        invalidate()
    }

    fun mostrar(r: LectorDtc.Resultado, borrado: Boolean) {
        resultado = r
        fueBorrado = borrado
        desplazamiento = 0f
        estado = Estado.RESULTADO
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(FONDO)
        when (estado) {
            Estado.MENU -> dibujarMenu(canvas, w, h)
            Estado.TRABAJANDO -> dibujarTrabajando(canvas, w, h)
            Estado.RESULTADO -> dibujarResultado(canvas, w, h)
            Estado.DETALLE -> dibujarDetalle(canvas, w, h)
        }
    }

    // --- Menu ---------------------------------------------------------------

    private fun dibujarMenu(canvas: Canvas, w: Float, h: Float) {
        titulo.color = TENUE
        titulo.textSize = h * 0.06f
        canvas.drawText("DIAGNOSTICO", w * 0.5f, h * 0.13f, titulo)

        texto.textAlign = Paint.Align.CENTER
        texto.color = TENUE
        texto.textSize = h * 0.042f
        canvas.drawText(
            "lee la computadora del carro y te dice que significa cada averia",
            w * 0.5f, h * 0.23f, texto,
        )

        val conSrs = Carro.perfil.tablaSrs != null
        val anchoB = w * 0.36f
        // Con la bolsa de aire hay una fila mas: los dos botones del motor
        // encogen un poco para que quepa debajo sin tapar los avisos del pie.
        val altoB = if (conSrs) h * 0.24f else h * 0.30f
        val y = if (conSrs) h * 0.30f else h * 0.36f
        cajaLeer.set(w * 0.28f - anchoB / 2, y, w * 0.28f + anchoB / 2, y + altoB)
        cajaBorrar.set(w * 0.72f - anchoB / 2, y, w * 0.72f + anchoB / 2, y + altoB)

        boton(canvas, cajaLeer, "LEER CODIGOS", VERDE, h)
        boton(canvas, cajaBorrar, "BORRAR CODIGOS", AMBAR, h)

        if (conSrs) {
            // La luz de la bolsa de aire es OTRA computadora, que estos dos
            // botones no leen. Tenerla aqui al lado evita que "SIN AVERIAS"
            // se lea como "la bolsa de aire esta bien".
            cajaSrs.set(cajaLeer.left, y + altoB + h * 0.04f, cajaBorrar.right, y + altoB + h * 0.18f)
            boton(canvas, cajaSrs, "BOLSAS DE AIRE (luz SRS)", ROJO, h)
        } else {
            cajaSrs.setEmpty()
        }

        texto.color = TENUE
        texto.textSize = h * 0.038f
        val pie = if (codigosEnPantalla > 0) {
            "$codigosEnPantalla codigos conocidos de este carro"
        } else {
            "cargando la tabla de codigos..."
        }
        canvas.drawText(pie, w * 0.5f, h * 0.78f, texto)

        canvas.drawText(
            "borrar apaga la luz, pero tambien borra los monitores de emisiones",
            w * 0.5f, h * 0.86f, texto,
        )
        canvas.drawText("o toca fuera de los botones", w * 0.5f, h * 0.94f, texto)
        // El menu era la unica pantalla sin salida a la vista: la instruccion
        // "toca fuera" hay que saberla de antes, y quien abre el diagnostico
        // por primera vez no la sabe.
        dibujarVolver(canvas, w, h)
    }

    private fun boton(canvas: Canvas, caja: RectF, etiqueta: String, color: Int, h: Float) {
        relleno.color = CAJA
        canvas.drawRoundRect(caja, h * 0.04f, h * 0.04f, relleno)
        trazo.color = color
        trazo.strokeWidth = h * 0.008f
        canvas.drawRoundRect(caja, h * 0.04f, h * 0.04f, trazo)
        titulo.color = color
        titulo.textSize = h * 0.062f
        canvas.drawText(etiqueta, caja.centerX(), caja.centerY() + h * 0.022f, titulo)
    }

    // --- Trabajando ---------------------------------------------------------

    private fun dibujarTrabajando(canvas: Canvas, w: Float, h: Float) {
        titulo.color = BLANCO
        titulo.textSize = h * 0.07f
        canvas.drawText(mensaje, w * 0.5f, h * 0.48f, titulo)
        texto.textAlign = Paint.Align.CENTER
        texto.color = TENUE
        texto.textSize = h * 0.042f
        canvas.drawText(
            "puede tardar unos segundos: la K-line de este carro es lenta",
            w * 0.5f, h * 0.60f, texto,
        )
    }

    // --- Resultado ----------------------------------------------------------

    private fun dibujarResultado(canvas: Canvas, w: Float, h: Float) {
        val r = resultado ?: return
        cajasCodigo.clear()

        val margen = w * 0.03f
        var y = h * 0.11f - desplazamiento

        titulo.color = TENUE
        titulo.textSize = h * 0.055f
        canvas.drawText(
            if (fueBorrado) "RESULTADO DEL BORRADO" else "CODIGOS DEL CARRO",
            w * 0.5f, h * 0.07f, titulo,
        )

        texto.textAlign = Paint.Align.LEFT

        if (r.error != null) {
            texto.color = ROJO
            texto.textSize = h * 0.05f
            canvas.drawText("Error: ${r.error}", margen, y + h * 0.06f, texto)
            dibujarVolver(canvas, w, h)
            return
        }

        if (!r.hayAlgo) {
            titulo.color = VERDE
            titulo.textSize = h * 0.10f
            canvas.drawText(
                if (fueBorrado) "BORRADO" else "SIN AVERIAS",
                w * 0.5f, h * 0.42f, titulo,
            )
            texto.textAlign = Paint.Align.CENTER
            texto.color = TENUE
            texto.textSize = h * 0.045f
            canvas.drawText(
                if (fueBorrado) "la computadora ya no guarda ningun codigo"
                else "la computadora no tiene ningun codigo guardado",
                w * 0.5f, h * 0.56f, texto,
            )
            if (r.luzEncendida) {
                texto.color = AMBAR
                canvas.drawText(
                    "pero la luz de averia sigue encendida",
                    w * 0.5f, h * 0.66f, texto,
                )
            }
            texto.textAlign = Paint.Align.LEFT
            dibujarVolver(canvas, w, h)
            return
        }

        y = dibujarGrupo(canvas, r.guardados, "GUARDADOS", margen, y, w, h)
        y = dibujarGrupo(canvas, r.pendientes, "PENDIENTES (aun sin encender la luz)", margen, y, w, h)
        y = dibujarGrupo(canvas, r.permanentes, "PERMANENTES", margen, y, w, h)
        altoContenido = y + desplazamiento

        dibujarVolver(canvas, w, h)
    }

    private fun dibujarGrupo(
        canvas: Canvas,
        lista: List<Dtc.Codigo>,
        rotulo: String,
        margen: Float,
        yInicial: Float,
        w: Float,
        h: Float,
    ): Float {
        if (lista.isEmpty()) return yInicial
        var y = yInicial

        texto.color = TENUE
        texto.textSize = h * 0.038f
        canvas.drawText(rotulo, margen, y, texto)
        y += h * 0.05f

        for (c in lista) {
            val e = TablaDtc.de(c.texto)
            val alto = h * 0.15f
            val caja = RectF(margen, y, w - margen, y + alto)
            cajasCodigo += caja to c.texto

            val color = when (e?.gravedad) {
                TablaDtc.Gravedad.GRAVE -> ROJO
                TablaDtc.Gravedad.ATENCION -> AMBAR
                TablaDtc.Gravedad.LEVE -> VERDE
                null -> TENUE
            }
            relleno.color = CAJA
            canvas.drawRoundRect(caja, h * 0.02f, h * 0.02f, relleno)
            trazo.color = color
            trazo.strokeWidth = h * 0.005f
            canvas.drawRoundRect(caja, h * 0.02f, h * 0.02f, trazo)

            texto.color = color
            texto.textSize = h * 0.06f
            canvas.drawText(c.texto, margen + w * 0.02f, y + h * 0.065f, texto)

            texto.color = BLANCO
            texto.textSize = h * 0.042f
            // Que un codigo no este en la tabla NO se oculta: la computadora
            // fijo algo que la tabla no preveia. Se dice, con su grupo.
            val t = e?.titulo ?: GrupoDtc.de(c.texto)?.let { "Sin ficha propia · ${it.titulo}" }
                ?: "codigo sin ficha en la tabla"
            canvas.drawText(recortar(t, w * 0.72f, texto), margen + w * 0.16f, y + h * 0.065f, texto)

            texto.color = TENUE
            texto.textSize = h * 0.036f
            canvas.drawText("toca para saber que significa", margen + w * 0.02f, y + h * 0.12f, texto)

            y += alto + h * 0.025f
        }
        return y + h * 0.02f
    }

    // --- Detalle de un codigo -----------------------------------------------

    private fun dibujarDetalle(canvas: Canvas, w: Float, h: Float) {
        val margen = w * 0.04f
        val e = detalle

        titulo.textAlign = Paint.Align.LEFT
        titulo.color = when (e?.gravedad) {
            TablaDtc.Gravedad.GRAVE -> ROJO
            TablaDtc.Gravedad.ATENCION -> AMBAR
            TablaDtc.Gravedad.LEVE -> VERDE
            null -> TENUE
        }
        titulo.textSize = h * 0.09f
        canvas.drawText(detalleCodigo, margen, h * 0.13f, titulo)
        titulo.textAlign = Paint.Align.CENTER

        texto.textAlign = Paint.Align.LEFT
        if (e == null) {
            // Sin ficha propia: se explica su GRUPO, diciendo que es eso y no
            // la pieza exacta.
            val g = GrupoDtc.de(detalleCodigo)
            texto.color = TENUE
            texto.textSize = h * 0.044f
            var y = h * 0.24f
            y = parrafo(
                canvas, "Este código no tiene ficha propia en la tabla de este carro. " +
                    "Esto es lo que dice su grupo:",
                margen, y, w - margen * 2, h * 0.055f, texto,
            )
            if (g == null) {
                parrafo(canvas, "No tiene forma de código OBD. Anótalo tal cual.",
                    margen, y + h * 0.03f, w - margen * 2, h * 0.055f, texto)
                dibujarVolver(canvas, w, h)
                return
            }
            texto.color = BLANCO
            texto.textSize = h * 0.052f
            y = parrafo(canvas, g.titulo, margen, y + h * 0.03f, w - margen * 2, h * 0.062f, texto)
            texto.color = TENUE
            texto.textSize = h * 0.044f
            y = parrafo(canvas, g.explicacion, margen, y + h * 0.02f, w - margen * 2, h * 0.055f, texto)
            texto.color = AMBAR
            texto.textSize = h * 0.040f
            parrafo(canvas, g.queHacer, margen, y + h * 0.03f, w - margen * 2, h * 0.050f, texto)
            dibujarVolver(canvas, w, h)
            return
        }

        texto.color = BLANCO
        texto.textSize = h * 0.052f
        var y = h * 0.24f
        y = parrafo(canvas, e.titulo, margen, y, w - margen * 2, h * 0.062f, texto)

        texto.color = TENUE
        texto.textSize = h * 0.044f
        y += h * 0.03f
        y = parrafo(canvas, e.explicacion, margen, y, w - margen * 2, h * 0.055f, texto)

        texto.color = AMBAR
        texto.textSize = h * 0.040f
        y += h * 0.035f
        parrafo(canvas, "Causas mas comunes: ${e.causas}", margen, y, w - margen * 2, h * 0.050f, texto)

        dibujarVolver(canvas, w, h)
    }

    /** Parte un texto en lineas que quepan. Devuelve la Y de despues. */
    private fun parrafo(
        canvas: Canvas, t: String, x: Float, yInicial: Float,
        ancho: Float, salto: Float, p: Paint,
    ): Float {
        var y = yInicial
        val linea = StringBuilder()
        for (palabra in t.split(' ')) {
            val prueba = if (linea.isEmpty()) palabra else "$linea $palabra"
            if (p.measureText(prueba) > ancho && linea.isNotEmpty()) {
                canvas.drawText(linea.toString(), x, y, p)
                y += salto
                linea.setLength(0)
                linea.append(palabra)
            } else {
                linea.setLength(0)
                linea.append(prueba)
            }
        }
        if (linea.isNotEmpty()) {
            canvas.drawText(linea.toString(), x, y, p)
            y += salto
        }
        return y
    }

    private fun recortar(t: String, ancho: Float, p: Paint): String {
        if (p.measureText(t) <= ancho) return t
        var s = t
        while (s.length > 4 && p.measureText("$s...") > ancho) s = s.dropLast(1)
        return "$s..."
    }

    /**
     * El boton de atras, y dice ATRAS.
     *
     * Antes era una X sola en una esquina. En un tablero que se mira de reojo
     * conduciendo, una X puede leerse como "cerrar la aplicacion entera" tanto
     * como "volver un paso", y el radio no trae barra de navegacion del
     * sistema donde comprobarlo: si uno se equivoca, se queda sin tablero en
     * marcha. Una flecha con su palabra no admite esa duda.
     *
     * Va arriba a la IZQUIERDA a proposito, lejos de la X de cerrar el tablero
     * que vive arriba a la derecha: dos gestos opuestos no deben caer bajo el
     * mismo dedo.
     */
    private fun dibujarVolver(canvas: Canvas, w: Float, h: Float) {
        val alto = h * 0.14f
        val margen = h * 0.03f
        val ancho = alto * 2.6f
        cajaVolver.set(margen, margen, margen + ancho, margen + alto)
        relleno.color = CAJA
        canvas.drawRoundRect(cajaVolver, alto * 0.22f, alto * 0.22f, relleno)
        trazo.color = TENUE
        trazo.strokeWidth = h * 0.008f
        canvas.drawRoundRect(cajaVolver, alto * 0.22f, alto * 0.22f, trazo)

        // La flecha, a mano: tres trazos y no una fuente, porque el resto de
        // la pantalla ya se dibuja asi y una fuente de iconos seria un peso
        // nuevo en un modulo que se abre a mano y se cierra.
        val cx = cajaVolver.left + alto * 0.62f
        val cy = cajaVolver.centerY()
        val d = alto * 0.20f
        canvas.drawLine(cx + d, cy, cx - d, cy, trazo)
        canvas.drawLine(cx - d, cy, cx, cy - d, trazo)
        canvas.drawLine(cx - d, cy, cx, cy + d, trazo)

        texto.color = TENUE
        texto.textSize = alto * 0.42f
        texto.textAlign = Paint.Align.LEFT
        canvas.drawText(
            "ATRAS", cx + d + alto * 0.28f, cy + texto.textSize * 0.36f, texto,
        )
        texto.textAlign = Paint.Align.CENTER
    }

    // --- Toques -------------------------------------------------------------

    private var yArrastre = 0f

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                yArrastre = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (estado == Estado.RESULTADO) {
                    val dy = yArrastre - event.y
                    if (kotlin.math.abs(dy) > 2f) {
                        desplazamiento = (desplazamiento + dy)
                            .coerceIn(0f, kotlin.math.max(0f, altoContenido - height * 0.85f))
                        yArrastre = event.y
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                manejarToque(event.x, event.y)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * Un paso atras. Devuelve false cuando ya no queda a donde volver.
     *
     * Lo usan el boton de la pantalla Y el boton de atras del sistema, para
     * que no puedan discrepar: si cada uno navegara por su cuenta, el mismo
     * gesto haria cosas distintas segun donde se toque, que es como se pierde
     * la confianza en una pantalla que se mira de reojo.
     */
    fun retroceder(): Boolean = when (estado) {
        Estado.DETALLE -> { estado = Estado.RESULTADO; invalidate(); true }
        Estado.RESULTADO -> { estado = Estado.MENU; invalidate(); true }
        // A media lectura no se retrocede: el lector ya paro el sondeo y esta
        // hablando con la computadora. Salir aqui dejaria el hilo suelto
        // terminando contra una pantalla que ya no existe.
        Estado.TRABAJANDO -> true
        Estado.MENU -> false
    }

    private fun manejarToque(x: Float, y: Float) {
        when (estado) {
            Estado.MENU -> when {
                cajaLeer.contains(x, y) -> act.leerCodigos()
                cajaBorrar.contains(x, y) -> act.borrarCodigos()
                cajaSrs.contains(x, y) -> act.abrirBolsasDeAire()
                else -> act.finish()
            }
            Estado.TRABAJANDO -> Unit
            Estado.RESULTADO -> {
                if (cajaVolver.contains(x, y)) {
                    estado = Estado.MENU
                    invalidate()
                    return
                }
                for ((caja, codigo) in cajasCodigo) {
                    if (caja.contains(x, y)) {
                        detalleCodigo = codigo
                        detalle = TablaDtc.de(codigo)
                        estado = Estado.DETALLE
                        invalidate()
                        return
                    }
                }
            }
            Estado.DETALLE -> {
                estado = Estado.RESULTADO
                invalidate()
            }
        }
    }

    private companion object {
        const val FONDO = 0xFF07090C.toInt()
        const val CAJA = 0xFF141A21.toInt()
        const val BLANCO = Color.WHITE
        const val TENUE = 0xFF8A96A3.toInt()
        const val VERDE = 0xFF35D07F.toInt()
        const val AMBAR = 0xFFFFB020.toInt()
        const val ROJO = 0xFFFF3B30.toInt()
    }
}
