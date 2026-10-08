package com.nonosky.carsoc.ui.lienzo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.nonosky.carsoc.Carro
import com.nonosky.carsoc.EstadoDelTablero
import com.nonosky.carsoc.Termometro

/**
 * LA VARIANTE CANVAS DEL TABLERO. Una sola `View` que reparte la pantalla,
 * llama a los pintores y se repinta al ritmo que manda el termometro del radio.
 *
 * ## Que hace de verdad, y que no
 *
 * No dibuja NADA por su cuenta salvo el fondo y los dos botones de la cabecera.
 * Su trabajo es el que fallo la vez pasada: **decidir donde va cada seccion y
 * pasarle a cada pintor SU rectangulo**. El tablero viejo hacia eso
 * mismo con aritmetica suelta dentro de `onDraw` —`w / 3` para las columnas y
 * `h * 0.125f` para la letra— y por eso al pasar de 1280x480 a 1024x600 la
 * columna se estrecho un 20 % mientras la letra crecia un 25 % y los numeros se
 * pisaron. Aqui el reparto vive en [RepartoTablero], que es codigo puro y se
 * comprueba en la JVM en las tres pantallas antes de compilar nada.
 *
 * ## El ritmo lo pone el radio, no una constante
 *
 * Esta es media razon de ser de esta variante, y lo que la de HTML no tiene: el
 * repintado se reprograma con `postDelayed(..., Termometro.msEntreCuadros())`,
 * que da 5 cuadros por segundo con el radio fresco, 2 tibio y **1 caliente**.
 * Este head unit se apago dos veces por calor; un tablero que apaga el radio
 * del carro no es un tablero, es una averia.
 *
 * Y la LECTURA de los sensores va en el mismo latido, no en `onDraw`: si otro
 * hilo llama a `postInvalidate()` —el servicio lo hace cuando contesta una
 * bateria o la nevera— se repinta la ultima foto, pero no se dispara trabajo
 * extra. Con el radio caliente, mirar el estado del carro cuesta una vez por
 * segundo y punto.
 *
 * ## Sin asignar memoria por cuadro
 *
 * Los `Paint`, el `RectF` y las metricas se crean en el constructor. El reparto
 * se hace en `onSizeChanged` y los pintores memorizan el suyo contra la caja
 * que reciben, asi que un cuadro normal no llama a [Reparto] ni una vez. Lo
 * unico que se asigna por latido es el [DatosTablero], que es inmutable a
 * proposito —nadie lo cambia a media pintada— y sale mas barato que el
 * `StringBuilder` de 1 KB por vuelta que fabrica la variante HTML.
 *
 * ## Nada de XML
 *
 * Ni una linea de layout. La vista se construye en codigo y se pone como
 * `contentView`, igual que el resto del proyecto.
 */
@SuppressLint("ViewConstructor")
class TableroLienzo(
    context: Context,
    /**
     * Lo que la vista puede PEDIR que se haga. No lo hace ella: esta clase no
     * sabe de Bluetooth ni de Activities, igual que `TableroActivity` no sabe
     * de OBD. Es el mismo juego de acciones que el `Puente` le ofrece al
     * JavaScript del tablero HTML, para que las dos variantes hagan lo mismo
     * con los mismos botones.
     */
    private val mandos: Mandos,
) : View(context) {

    /** Las acciones que el dedo puede pedir desde el tablero. */
    interface Mandos {
        /** Mueve la consigna de la nevera. Devuelve si se pudo ENCOLAR. */
        fun neveraConsigna(delta: Int): Boolean

        fun neveraEncender(on: Boolean): Boolean

        fun neveraEco(eco: Boolean): Boolean

        /** Abre la pantalla de averias. */
        fun abrirAverias()

        /** Abre el menu de configuracion y emparejamiento. */
        fun abrirAjustes()

        /** Cierra el tablero. El servicio sigue: alertas y lecturas no paran. */
        fun cerrar()

        // --- Calibracion de llantas ------------------------------------------
        //
        // La vista no toca las preferencias: no tiene por que conocerlas, y
        // ademas leerlas en `onDraw` seria tocar disco por cuadro. Quien las
        // guarda es la Activity, que ya tiene el `Context` bueno.

        /** Mueve la correccion de esa rueda. Devuelve la resultante, en PSI. */
        fun calibrarLlanta(rueda: Int, pasos: Int): Float

        /** Deja esa rueda —o las cuatro— sin correccion. */
        fun calibrarACero(rueda: Int)

        /** La correccion guardada de esa rueda, en PSI. */
        fun ajusteLlanta(rueda: Int): Float

        /** ¿Un toque corrige las cuatro? */
        fun calibrarTodasALaVez(): Boolean

        fun ponerCalibrarTodasALaVez(valor: Boolean)
    }

    // --- Estado de la vista -------------------------------------------------

    private val reparto = RepartoTablero()
    private val pincel = Pincel()

    /**
     * La ultima foto del carro. Se cambia SOLO en el hilo de interfaz, dentro
     * del latido de repintado.
     */
    private var datos: DatosTablero = DatosTablero.VACIO

    /**
     * El reloj de ESTE cuadro. Uno solo para toda la pantalla, y por eso se
     * lee una vez y se reparte a todos los pintores: si cada uno mirara la hora
     * por su cuenta, lo que parpadea en una tarjeta y lo que parpadea en la de
     * al lado se irian separando hasta parpadear a destiempo.
     */
    private var ahora: Long = 0L

    /** La pantalla entera. Guardada para no fabricar una [Caja] por cuadro. */
    private var cajaPantalla: Caja = Caja.NADA

    // --- Los dos botones de la cabecera -------------------------------------
    //
    // En el HTML viven en el rotulo de la tarjeta de motor. Aqui no pueden:
    // `PintaMotor` es dueño de su tarjeta entera y se pintarian encima de lo
    // suyo. Van a la derecha de la cabecera, que es donde sobra sitio.

    private companion object {
        const val AVERIAS = "AVERÍAS"
        const val AJUSTES = "AJUSTES"

        /** Ninguno / averias / ajustes. */
        const val NADA = 0
        const val B_AVERIAS = 1
        const val B_AJUSTES = 2
        const val B_CERRAR = 3

        /**
         * Lo que dice [DatosTablero.nevOn] cuando la nevera esta encendida.
         * Lo escribe `EstadoDelTablero.leer`; si alla cambia la palabra, aqui
         * el boton de encendido empieza a mandar lo contrario de lo que se ve.
         */
        const val ENCENDIDA = "Encendida"

        /** Alto de la pastilla dentro de la franja: los 26 de 44 del HTML. */
        const val ALTO_PASTILLA = 0.59f

        /** Letra de la pastilla, en fraccion de SU alto. No del de pantalla. */
        const val LETRA_BOTON = 0.40f

        /** Por debajo de esto no se lee de reojo; se corta y se marca. */
        const val SUELO_BOTON = 0.55f

        /**
         * Cuanto hay que sostener el dedo para abrir el calibrador.
         *
         * Los mismos 600 ms del tablero HTML, mas que los 500 de Android a
         * proposito: en un carro que se mueve se toca la pantalla sin querer,
         * y esto NO es un menu contextual que se cierra solo — es un mando que
         * cambia el numero por el que suena la alarma de presion.
         */
        const val SOSTENIDO_MS = 600L

        /** Holgura del sostenido, AL CUADRADO para no sacar raices. */
        const val HOLGURA_MS2 = 24f * 24f
    }

    private var botonPulsado = NADA

    /** Que mando de la nevera esta bajo el dedo, para soltarlo donde toca. */
    private var mandoPulsado: PintaNevera.Mando? = null

    // --- Pinceles propios, todos preasignados -------------------------------

    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val letra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.16f
    }

    private val metricas = Paint.FontMetrics()
    private val rect = RectF()

    // --- Ciclo de vida ------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Sin esto el primer cuadro pintaria el tablero vacio durante hasta un
        // segundo. "No lo se" es la respuesta correcta cuando no se sabe, pero
        // aqui si se sabe: basta con preguntar.
        latir()
        programarRepintado()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(repintar)
        super.onDetachedFromWindow()
    }

    /**
     * Fuera de la pantalla no se late. Antes el latido seguia a 5 Hz con el
     * tablero tapado (Android Auto delante, ajustes abiertos): leyendo el
     * estado y comparando para nada. Solo paraba al destruirse la vista.
     */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            latir()
            invalidate()
            programarRepintado()
        } else {
            removeCallbacks(repintar)
        }
    }

    /**
     * El latido: leer, y repintar SOLO SI HAY MOTIVO.
     *
     * Antes repintaba siempre, y eso costo caro. Medido en el emulador con el
     * carro parado —o sea con todo el tablero en guiones y sin un solo sensor
     * conectado— la variante Canvas gastaba **siete veces mas CPU que la de
     * HTML** (3,4 % de un nucleo contra 0,5 %), estando a la vez en un tercio
     * de memoria. La CPU es lo que calienta el radio, y no dejarlo caliente es
     * media razon de ser de esta variante: el argumento se estaba cumpliendo
     * al reves.
     *
     * La causa no era el dibujo sino cuando: el lienzo repintaba la pantalla
     * entera cinco veces por segundo pasara lo que pasara, mientras que el
     * WebView solo recompone cuando algun nodo cambia. Con el carro parado no
     * cambia ninguno; el lienzo estaba repintando cuarenta veces seguidas
     * exactamente los mismos guiones.
     *
     * Hay motivo cuando la lectura CAMBIO —[DatosTablero] es `data class`, asi
     * que compararla es comparar todos sus campos— o cuando algo parpadeaba en
     * el ultimo cuadro. La lectura se sigue haciendo al ritmo de siempre: lo
     * que se salta es el dibujo, no el mirar.
     */
    private val repintar = Runnable {
        val antes = datos
        latir()
        if (datos != antes || Latido.alguienParpadea) invalidate()
        programarRepintado()
    }

    /** Una lectura y su reloj, que van juntos y por eso se toman juntos. */
    private fun latir() {
        ahora = System.currentTimeMillis()
        datos = EstadoDelTablero.leer(ahora, context)
    }

    /**
     * El ritmo lo pone el termometro, no una constante.
     *
     * Con el radio fresco van 5 cuadros por segundo; tibio, 2; caliente, 1. Un
     * tablero de vigilancia sigue siendo legible a un cuadro por segundo, y a
     * esas alturas lo que importa es que el radio no se apague.
     *
     * El precio esta medido y se acepta: los parpadeos de alarma son de medio
     * segundo, o sea 2 Hz, y a un cuadro por segundo se alias an — se sigue
     * viendo que algo cambia, que es lo que tienen que conseguir, pero deja de
     * ser un ritmo. Antes que un parpadeo bonito, un radio encendido.
     */
    private fun programarRepintado() {
        removeCallbacks(repintar)
        postDelayed(repintar, Termometro.msEntreCuadros())
    }

    // --- Reparto ------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, viejoW: Int, viejoH: Int) {
        super.onSizeChanged(w, h, viejoW, viejoH)
        cajaPantalla = Caja.pantalla(w.toFloat(), h.toFloat())
        // AQUI es donde se asigna memoria de reparto, una vez por cambio de
        // tamaño. En `onDraw` no se llama a `Reparto` ni una sola vez.
        reparto.reparteEsteCarro(w.toFloat(), h.toFloat())
        if (reparto.valido) PintaMotor.medir(reparto.motor)
    }

    // --- Pintado ------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        // Se borra la huella ANTES de pintar: la dejan los pintores que
        // parpadean, al preguntar la hora. Lo que quede apuntado al terminar
        // este cuadro es lo que decide si hace falta el siguiente.
        Latido.nuevoCuadro()

        relleno.color = Pincel.FONDO
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), relleno)

        // Regla 4: si el tablero no cabe en esta pantalla, se dice. Un aspa a
        // pantalla completa es feo y se arregla; seis tarjetas del reves
        // pintadas una encima de otra parecen un tablero y no lo son.
        if (!reparto.valido) {
            pincel.marcaDeQueNoCabe(canvas, cajaPantalla)
            return
        }

        val d = datos
        val t = ahora

        PintaNevera.cabecera(canvas, reparto.cabecera, d, t, pincel)
        pintaBoton(canvas, reparto.botonAverias, AVERIAS, botonPulsado == B_AVERIAS)
        pintaBoton(canvas, reparto.botonAjustes, AJUSTES, botonPulsado == B_AJUSTES)
        pintaCerrar(canvas, reparto.botonCerrar, botonPulsado == B_CERRAR)

        PintaEnergia.pinta(canvas, reparto.energia, d, t, pincel)
        if (reparto.conNevera) PintaNevera.pintar(canvas, reparto.nevera, d, t, pincel)
        PintaMotor.pinta(canvas, reparto.motor, d, t, pincel)
        PintaLlantas.pintar(canvas, reparto.llantas, d, t, pincel)

        // El ultimo, encima de todo: es una capa modal. Y el ajuste se le pasa
        // LEIDO AHORA, no cacheado, para que lo que se ve sea lo guardado.
        if (PintaCalibracion.abierto) {
            PintaCalibracion.pintar(
                canvas, cajaPantalla,
                mandos.ajusteLlanta(PintaCalibracion.rueda),
                mandos.calibrarTodasALaVez(),
                pincel,
            )
        }
    }

    /**
     * Un boton de la cabecera: pastilla con borde y una palabra dentro.
     *
     * La letra sale del alto de LA PASTILLA, no del de la pantalla — regla 2 —
     * y se mide antes de pintarla. Si no cabe ni encogiendo hasta el suelo, se
     * marca: un boton cuyo rotulo no se lee es un boton que nadie toca.
     */
    private fun pintaBoton(canvas: Canvas, caja: Caja, texto: String, pulsado: Boolean) {
        if (!caja.valida) return

        // La pastilla no ocupa la franja entera: se ve como en el HTML, con
        // aire arriba y abajo. Lo que se TOCA si es la franja entera, que es lo
        // contrario de una zona de gracia invisible — aqui el dedo acierta de
        // mas, no de menos.
        val alto = caja.alto * ALTO_PASTILLA
        val pastilla = caja.margen(0f, (caja.alto - alto) * 0.5f)
        if (!pastilla.valida) {
            pincel.marcaDeQueNoCabe(canvas, caja)
            return
        }

        val radio = pastilla.alto * 0.14f
        rect.set(pastilla.x0, pastilla.y0, pastilla.x1, pastilla.y1)
        if (pulsado) {
            relleno.color = FONDO_PULSADO
            canvas.drawRoundRect(rect, radio, radio, relleno)
        }
        trazo.color = if (pulsado) Pincel.OCRE else Pincel.LINEA2
        trazo.strokeWidth = maxOf(1f, pastilla.alto * 0.045f)
        canvas.drawRoundRect(rect, radio, radio, trazo)

        // Medir y ceder. El relleno lateral es fraccion del ancho de la propia
        // pastilla, asi que en una pantalla estrecha aprieta en vez de sacar el
        // texto por los lados.
        val hueco = pastilla.ancho * 0.88f
        val ideal = pastilla.alto * LETRA_BOTON
        var tam = ideal
        letra.textSize = tam
        var ancho = letra.measureText(texto)
        if (ancho > hueco && ancho > 0f) {
            tam = maxOf(ideal * SUELO_BOTON, tam * hueco / ancho)
            letra.textSize = tam
            ancho = letra.measureText(texto)
        }
        if (ancho > hueco) {
            // Ni al suelo cabe. Se marca y no se pinta el texto: media palabra
            // en un boton se lee como otra palabra.
            pincel.marcaDeQueNoCabe(canvas, caja)
            return
        }

        letra.color = if (pulsado) Pincel.TINTA else Pincel.ARENA
        letra.getFontMetrics(metricas)
        val base = pastilla.cy - (metricas.ascent + metricas.descent) * 0.5f
        canvas.drawText(texto, pastilla.cx, base, letra)
    }

    /**
     * La X de cerrar: la misma pastilla que los otros botones, con un aspa
     * dibujada a trazo en vez de una palabra. Un caracter "X" de la fuente
     * se veria distinto en cada radio; dos lineas se ven iguales en todos.
     */
    private fun pintaCerrar(canvas: Canvas, caja: Caja, pulsado: Boolean) {
        if (!caja.valida) return
        val alto = caja.alto * ALTO_PASTILLA
        val pastilla = caja.margen(0f, (caja.alto - alto) * 0.5f)
        if (!pastilla.valida) {
            pincel.marcaDeQueNoCabe(canvas, caja)
            return
        }
        val radio = pastilla.alto * 0.14f
        rect.set(pastilla.x0, pastilla.y0, pastilla.x1, pastilla.y1)
        if (pulsado) {
            relleno.color = FONDO_PULSADO
            canvas.drawRoundRect(rect, radio, radio, relleno)
        }
        trazo.color = if (pulsado) Pincel.OCRE else Pincel.LINEA2
        trazo.strokeWidth = maxOf(1f, pastilla.alto * 0.045f)
        canvas.drawRoundRect(rect, radio, radio, trazo)

        val brazo = minOf(pastilla.ancho, pastilla.alto) * 0.22f
        val antes = trazo.strokeWidth
        trazo.color = if (pulsado) Pincel.TINTA else Pincel.ARENA
        trazo.strokeWidth = maxOf(1.5f, pastilla.alto * 0.07f)
        canvas.drawLine(pastilla.cx - brazo, pastilla.cy - brazo, pastilla.cx + brazo, pastilla.cy + brazo, trazo)
        canvas.drawLine(pastilla.cx + brazo, pastilla.cy - brazo, pastilla.cx - brazo, pastilla.cy + brazo, trazo)
        trazo.strokeWidth = antes
    }

    // --- El dedo ------------------------------------------------------------

    /**
     * Los mandos de la nevera y los dos botones de la cabecera.
     *
     * La orden se manda al SOLTAR y solo si el dedo sigue encima del mismo
     * control, que es como se comporta un boton de verdad: se puede empezar a
     * tocar y arrepentirse. En un carro que se mueve eso no es un lujo.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(evento: MotionEvent): Boolean {
        val x = evento.x
        val y = evento.y

        // El calibrador es una capa MODAL: mientras esta abierto se come el
        // dedo entero. Sin esto, un toque que caiga sobre el velo llegaria al
        // boton de la nevera que hay debajo y que ya no se ve.
        if (PintaCalibracion.abierto) return calibrando(evento, x, y)

        when (evento.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Reloj propio: `ahora` es el del CUADRO y va emparejado con
                // `datos`. Pisarlo aqui adelantaria el parpadeo respecto a la
                // lectura que se esta pintando, que es justo lo que el reloj
                // unico existe para impedir.
                val toque = System.currentTimeMillis()

                // El dedo sostenido sobre una rueda abre el calibrador. Se
                // arma aqui y se desarma en cuanto el dedo se mueve o se
                // levanta: sostenido de verdad, no "tocado despacio".
                ruedaSostenida = PintaLlantas.ruedaEn(x, y)
                if (ruedaSostenida >= 0) {
                    xAbajo = x
                    yAbajo = y
                    postDelayed(abrirCalibrador, SOSTENIDO_MS)
                }

                botonPulsado = when {
                    reparto.botonAverias.contiene(x, y) -> B_AVERIAS
                    reparto.botonAjustes.contiene(x, y) -> B_AJUSTES
                    reparto.botonCerrar.contiene(x, y) -> B_CERRAR
                    else -> NADA
                }
                mandoPulsado =
                    if (botonPulsado == NADA) PintaNevera.tocar(x, y, toque) else null
                // El resalte es SOLO acuse de recibo del toque. Sin este
                // `invalidate()` no se veria hasta el cuadro siguiente, que con
                // el radio caliente es un segundo entero.
                if (botonPulsado != NADA || mandoPulsado != null) invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // Arrastrar cancela el sostenido. El umbral no es cosmetico:
                // en un carro andando el dedo NUNCA se queda quieto del todo,
                // y sin holgura el calibrador no se abriria jamas en marcha,
                // que es justo cuando se ve que una llanta va rara.
                if (ruedaSostenida >= 0) {
                    val dx = x - xAbajo
                    val dy = y - yAbajo
                    if (dx * dx + dy * dy > HOLGURA_MS2) soltarSostenido()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val boton = botonPulsado
                val mando = mandoPulsado
                // Levantar el dedo antes de tiempo NO abre nada: eso es un
                // toque, y un toque sobre una rueda no hace nada a proposito.
                soltarSostenido()
                botonPulsado = NADA
                mandoPulsado = null
                PintaNevera.soltar()
                invalidate()

                if (boton != NADA) {
                    if (boton == B_AVERIAS && reparto.botonAverias.contiene(x, y)) {
                        performClick()
                        mandos.abrirAverias()
                    } else if (boton == B_AJUSTES && reparto.botonAjustes.contiene(x, y)) {
                        performClick()
                        mandos.abrirAjustes()
                    } else if (boton == B_CERRAR && reparto.botonCerrar.contiene(x, y)) {
                        performClick()
                        mandos.cerrar()
                    }
                    return true
                }

                if (mando != null && PintaNevera.mandoEn(x, y) == mando) {
                    performClick()
                    obedecer(mando)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                soltarSostenido()
                botonPulsado = NADA
                mandoPulsado = null
                PintaNevera.soltar()
                invalidate()
                return true
            }
        }
        return true
    }

    // --- El calibrador de llantas -------------------------------------------

    /** La rueda bajo el dedo mientras se cuenta el sostenido, o -1. */
    private var ruedaSostenida = -1
    private var xAbajo = 0f
    private var yAbajo = 0f

    private val abrirCalibrador = Runnable {
        val cual = ruedaSostenida
        ruedaSostenida = -1
        if (cual >= 0) {
            // Se cancela lo que hubiera pulsado debajo: el dedo ya no va a
            // soltar sobre un boton, va a soltar sobre el calibrador.
            botonPulsado = NADA
            mandoPulsado = null
            PintaNevera.soltar()
            PintaCalibracion.abrir(cual)
            invalidate()
        }
    }

    private fun soltarSostenido() {
        if (ruedaSostenida >= 0) {
            ruedaSostenida = -1
            removeCallbacks(abrirCalibrador)
        }
    }

    /**
     * El dedo mientras el calibrador esta abierto.
     *
     * Se manda al SOLTAR y solo si el dedo sigue sobre el mismo mando, igual
     * que los botones de la cabecera. Y `+` / `−` NO se repiten al mantener:
     * la correccion util son dos o tres pasos, y una repeticion automatica
     * pasada de vueltas dejaria la rueda descalibrada del otro lado.
     */
    private fun calibrando(evento: MotionEvent, x: Float, y: Float): Boolean {
        when (evento.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mandoCal = PintaCalibracion.tocar(x, y)
                invalidate()
            }

            MotionEvent.ACTION_UP -> {
                val m = mandoCal
                mandoCal = null
                PintaCalibracion.soltar()
                if (m != null && PintaCalibracion.mandoEn(x, y) == m) {
                    performClick()
                    obedecerCal(m)
                }
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                mandoCal = null
                PintaCalibracion.soltar()
                invalidate()
            }
        }
        return true
    }

    private var mandoCal: PintaCalibracion.Mando? = null

    private fun obedecerCal(m: PintaCalibracion.Mando) {
        val rueda = PintaCalibracion.rueda
        if (rueda < 0) return
        when (m) {
            PintaCalibracion.Mando.MENOS -> mandos.calibrarLlanta(rueda, -1)
            PintaCalibracion.Mando.MAS -> mandos.calibrarLlanta(rueda, 1)
            PintaCalibracion.Mando.CERO -> mandos.calibrarACero(rueda)
            PintaCalibracion.Mando.TODAS ->
                mandos.ponerCalibrarTodasALaVez(!mandos.calibrarTodasALaVez())
            PintaCalibracion.Mando.LISTO, PintaCalibracion.Mando.FUERA ->
                PintaCalibracion.cerrar()
        }
    }

    /**
     * ¿Se puede cerrar el calibrador con la tecla de atras?
     *
     * La Activity pregunta antes de salirse del tablero. Un modal del que no
     * se sale con "atras" es un modal en el que la gente se queda encerrada.
     */
    fun cerrarCalibradorSiAbierto(): Boolean {
        if (!PintaCalibracion.abierto) return false
        PintaCalibracion.cerrar()
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * Manda el comando de la nevera.
     *
     * Se pregunta ANTES si tiene sentido: apagar sin saber si esta encendida
     * seria adivinar el destino del conmutador. Y no se toca nada de lo que se
     * pinta — el estado del boton lo dira la nevera cuando conteste, que es la
     * unica confirmacion que vale. Un boton que se pone verde porque el tablero
     * mando algo miente igual que un valor inventado, y este enlace falla una
     * de cada tres veces.
     */
    private fun obedecer(mando: PintaNevera.Mando) {
        if (!PintaNevera.estaHabilitado(mando, datos)) return
        when (mando) {
            PintaNevera.Mando.MENOS -> mandos.neveraConsigna(-1)
            PintaNevera.Mando.MAS -> mandos.neveraConsigna(1)
            PintaNevera.Mando.PODER -> mandos.neveraEncender(datos.nevOn != ENCENDIDA)
            PintaNevera.Mando.ECO -> mandos.neveraEco(datos.nevEco != true)
        }
    }

    /**
     * Un carro con nevera tiene botones; el otro no.
     *
     * No se usa para pintar —de eso ya se encarga el reparto, que ni le reserva
     * sitio— sino para poder decirlo desde fuera sin preguntarle al perfil.
     */
    val tieneMandos: Boolean get() = Carro.perfil.tieneNevera
}

/** El `.diagbtn:active` del HTML: ocre al 20 %. */
private const val FONDO_PULSADO = 0x33E0A84A
