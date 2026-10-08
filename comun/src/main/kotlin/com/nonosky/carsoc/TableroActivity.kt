package com.nonosky.carsoc

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.nonosky.carsoc.ui.lienzo.TableroLienzo

/**
 * El tablero del carro.
 *
 * ## DOS VARIANTES, UN SOLO DATO
 *
 * La misma pantalla se puede pintar de dos maneras, y la elige el dueño con
 * [Variante]:
 *
 * - **html** (la de omision) — un `WebView` sobre `assets/tablero.html`. Es lo
 *   que hay hoy en el carro. En este radio se puede permitir: es un MediaTek
 *   AC8257 de OCHO nucleos con 4 GB, y medido en reposo con el tablero
 *   corriendo esta al 72 % ocioso. A cambio se gana iterar la pantalla sin
 *   recompilar.
 * - **lienzo** — [TableroLienzo], `Canvas` nativo. Repinta al ritmo que manda
 *   `Termometro.msEntreCuadros()`, que baja a UN cuadro por segundo con el
 *   radio caliente. Eso el WebView no lo sabe hacer, y este head unit ya se
 *   apago dos veces por calor.
 *
 * Las dos leen exactamente el mismo [EstadoDelTablero]: la de HTML lo recibe en
 * JSON y la de Canvas como objeto, pero es **la misma lectura y las mismas
 * reglas de frescura**. Ese es el punto — dos pantallas que se leen los
 * sensores por su cuenta acaban contradiciendose, y entonces no hay forma de
 * saber cual miente.
 *
 * El reparto de responsabilidades no cambia: esta clase NO sabe de Bluetooth ni
 * de OBD. Lee [EstadoActual] y lo sirve.
 */
class TableroActivity : Activity(), TableroLienzo.Mandos {

    /** Una de las dos esta viva; la otra es null. */
    private var web: WebView? = null
    private var lienzo: TableroLienzo? = null

    /** La que se puso como `contentView`. Para pantalla completa y limpieza. */
    private var pantalla: View? = null

    private companion object {
        /** El receptor TPMS: un CH340/CH341. */
        const val VID_CH340 = 0x1A86
        const val PID_CH340 = 0x7523
        /** Con el nombre del paquete de ESTA app: cada carro, la suya. */
        fun accionPermisoUsb(paquete: String) = "$paquete.PERMISO_USB"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Un tablero que se apaga a media curva no sirve.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val vista = if (Variante.actual(this) == Variante.LIENZO) crearLienzo()
        else crearWeb()
        pantalla = vista
        setContentView(enMarco(vista))

        EstadoActual.vista = vista
        DashService.arrancar(this)
        pedirPermisoDelReceptorTpms()
    }

    /**
     * El tablero dentro de un marco que, EN PANTALLA DIVIDIDA, se baja lo
     * que mide la barra de estado.
     *
     * A pantalla completa el modo inmersivo esconde esa barra. Dividida,
     * Android 9 la deja siempre a la vista y ENCIMA de las dos mitades
     * —medido en el radio: 56 px, todo el ancho—, y como el tablero dibuja
     * por debajo de ella, la tuerca y la X quedaban tapadas y no se podian
     * tocar. Dividida se respeta ese margen, como hace el mapa de al lado.
     *
     * ⚠️ NO sale de los WindowInsets: en este radio, con la pantalla dividida,
     * a la vista le llegan en 0 —el estable y el de contenido— aunque el
     * gestor de ventanas registre 56 para esa misma ventana. Lo que si es
     * fiable es el marco VISIBLE de la ventana (`getWindowVisibleDisplayFrame`),
     * que ya descuenta la barra: lo que este por encima de el, se deja libre.
     */
    private fun enMarco(vista: View): View {
        val marco = FrameLayout(this)
        marco.addView(vista, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        // Cada vez que la ventana cambia de tamaño o de sitio. Se aplica en un
        // post para no pedir otro reparto en mitad de este; y como solo se toca
        // si cambia, el segundo reparto ya no hace nada.
        marco.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.post { ajustarMargen(v) } }
        return marco
    }

    private fun ajustarMargen(marco: View) {
        val arriba = if (enVentanaDividida()) {
            val visible = android.graphics.Rect()
            marco.getWindowVisibleDisplayFrame(visible)
            val donde = IntArray(2)
            marco.getLocationOnScreen(donde)
            // Con tope: un marco visible raro no puede comerse el tablero.
            (visible.top - donde[1]).coerceIn(0, marco.height / 4)
        } else 0
        if (marco.paddingTop != arriba) marco.setPadding(0, arriba, 0, 0)
    }

    private fun enVentanaDividida(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode

    // Solo existe desde Android 8, y antes nadie la llama: el radio es 9.
    @android.annotation.TargetApi(Build.VERSION_CODES.O)
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration?) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        (pantalla?.parent as? View)?.let { m -> m.post { ajustarMargen(m) } }
    }

    /** La variante Canvas. Sin XML: la vista se construye y se pone, y ya. */
    private fun crearLienzo(): View {
        val v = TableroLienzo(this, this)
        lienzo = v
        return v
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun crearWeb(): View {
        val v = WebView(this).apply {
            settings.javaScriptEnabled = true
            // El tablero es un asset local: no hay red de por medio.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            // Sin esto la animacion de entrada sale MUDA: Android exige por
            // omision un gesto del usuario antes de dejar sonar nada, y aqui
            // no hay nadie tocando la pantalla al arrancar el carro.
            settings.mediaPlaybackRequiresUserGesture = false
            // Sin zoom ni scroll: la pagina mide 1024x600 y ya.
            settings.builtInZoomControls = false
            settings.setSupportZoom(false)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // Transparente: el velo y las tarjetas los pinta el HTML, y
            // por los huecos asoma el fondo de pantalla del radio.
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            addJavascriptInterface(Puente(), "Puente")
            loadUrl("file:///android_asset/tablero.html")
        }
        web = v
        return v
    }

    /**
     * Pide el permiso USB del receptor TPMS, UNA vez y solo si falta.
     *
     * `TpmsReader` se niega a pedirlo, y hace bien: corre en el servicio, con
     * el tablero cerrado y el carro solo, y un dialogo que nadie contesta es
     * peor que un mensaje claro por el puente. Pero la Activity si tiene
     * pantalla y alguien delante, asi que este es su sitio.
     *
     * Junto con el filtro `USB_DEVICE_ATTACHED` del manifiesto, contestarlo
     * una vez marcando "usar por omision" lo deja concedido para siempre.
     */
    private fun pedirPermisoDelReceptorTpms() {
        runCatching {
            val um = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
            val dev = um.deviceList.values.firstOrNull {
                it.vendorId == VID_CH340 && it.productId == PID_CH340
            } ?: return
            if (um.hasPermission(dev)) return
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(accionPermisoUsb(packageName)).setPackage(packageName),
                if (android.os.Build.VERSION.SDK_INT >= 31)
                    PendingIntent.FLAG_IMMUTABLE else 0,
            )
            um.requestPermission(dev, pi)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) aPantallaCompleta()
    }

    /** Inmersivo: sin barras del sistema robando pixeles ni atencion. */
    @Suppress("DEPRECATION")
    private fun aPantallaCompleta() {
        pantalla?.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    /**
     * Con el tablero fuera de la pantalla —Android Auto delante, o los ajustes
     * abiertos— el WebView paraba de pintar pero sus temporizadores de
     * JavaScript seguian: cada 700 ms leia el estado, lo serializaba y lo
     * parseaba para nadie. La alerta de pinchazo no vive aqui sino en el
     * servicio, asi que pausar esto no calla nada.
     */
    // En onStop y no en onPause: en PANTALLA DIVIDIDA la mitad que no tiene
    // el foco esta "en pausa" pero se sigue viendo, y pausar ahi el WebView
    // la dejaba en blanco —medido en el radio, con el mapa en la otra mitad—.
    // onStop es "ya no se ve", que es cuando no hay nada que pintar.
    override fun onStop() {
        web?.onPause()
        web?.pauseTimers()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        web?.resumeTimers()
        web?.onResume()
        // Por si en Ajustes se restauraron los cuadros mientras no se veia.
        runCatching { web?.evaluateJavascript("window.cuadrosRecargar && cuadrosRecargar()", null) }
    }

    override fun onDestroy() {
        if (EstadoActual.vista === pantalla) EstadoActual.vista = null
        web?.destroy()
        web = null
        lienzo = null
        pantalla = null
        super.onDestroy()
    }

    // =========================================================================
    // Las acciones del tablero. UNA implementacion para las dos variantes:
    // el JavaScript llega por `Puente` y el dedo del Canvas por esta interfaz,
    // pero acaban en las mismas cuatro lineas.
    // =========================================================================

    /**
     * Los mandos de la nevera devuelven si se pudo ENCOLAR, no si la nevera
     * obedecio: eso lo dira la siguiente lectura, y es la unica confirmacion
     * que vale. Un boton que se pone verde porque el tablero mando algo miente
     * igual que un valor inventado.
     */
    override fun neveraEncender(on: Boolean): Boolean =
        EstadoActual.nevera?.encender(on) ?: false

    override fun neveraEco(eco: Boolean): Boolean =
        EstadoActual.nevera?.modoEco(eco) ?: false

    override fun neveraConsigna(delta: Int): Boolean =
        EstadoActual.nevera?.moverConsigna(delta) ?: false

    /**
     * Abre la pantalla de averias.
     *
     * Va APARTE del tablero a proposito: carga la tabla de codigos y abre su
     * propia conexion al adaptador, y nada de eso hace falta mientras se
     * maneja. Se abre a mano, se usa, se cierra, y al cerrarse suelta la tabla.
     */
    override fun abrirAverias() {
        runCatching {
            startActivity(Intent(this, com.nonosky.carsoc.diag.DiagnosticoActivity::class.java))
        }
    }

    /**
     * La X de arriba a la derecha: cierra el tablero y deja el radio donde
     * estaba. El servicio NO se cierra: las alertas de llanta, las baterias y
     * el contador del aceite siguen, y el tablero se vuelve a abrir desde su
     * icono o al encender el radio (si esta puesto en Ajustes).
     */
    override fun cerrar() {
        finish()
    }

    /** Abre el menu de configuracion y emparejamiento. */
    override fun abrirAjustes() {
        runCatching {
            startActivity(
                Intent(this, com.nonosky.carsoc.config.ConfiguracionActivity::class.java)
            )
        }
    }

    // --- Calibracion de llantas para la variante Canvas ---------------------
    //
    // Son los MISMOS metodos que el `Puente` le da al JavaScript, y a
    // proposito: las dos variantes del tablero corrigen las llantas por el
    // mismo camino y guardan en el mismo sitio, asi que una rueda calibrada
    // desde el tablero HTML sale ya corregida en el de Canvas. Si cada una
    // guardara lo suyo, cambiar de variante en ajustes descalibraria el carro
    // sin avisar.

    override fun calibrarLlanta(rueda: Int, pasos: Int): Float =
        com.nonosky.carsoc.config.CalibracionLlantas.mover(this, rueda, pasos)

    override fun calibrarACero(rueda: Int) {
        com.nonosky.carsoc.config.CalibracionLlantas.poner(this, rueda, 0f)
    }

    override fun ajusteLlanta(rueda: Int): Float =
        com.nonosky.carsoc.config.CalibracionLlantas.ajuste(this, rueda)

    override fun calibrarTodasALaVez(): Boolean =
        com.nonosky.carsoc.config.CalibracionLlantas.aplicarATodas(this)

    override fun ponerCalibrarTodasALaVez(valor: Boolean) {
        com.nonosky.carsoc.config.CalibracionLlantas.ponerAplicarATodas(this, valor)
    }

    /**
     * "Atras" cierra el calibrador antes de salir del tablero.
     *
     * Sin esto, la unica salida del calibrador seria el boton LISTO. Un modal
     * a pantalla completa del que la tecla de siempre no saca es un modal en
     * el que la gente se queda encerrada, y aqui ademas taparia el tablero
     * entero: las llantas, el motor y las baterias dejarian de verse.
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (lienzo?.cerrarCalibradorSiAbierto() == true) return
        if (edicionCuadroAbierta) {
            // Igual que el calibrador: se cierra por el camino de LISTO.
            edicionCuadroAbierta = false
            runCatching { web?.evaluateJavascript("cuadrosCerrarEdicion()", null) }
            return
        }
        if (calibradorHtmlAbierto) {
            // Se cierra por el mismo camino que el boton LISTO, no tocando el
            // DOM desde aqui: asi el aviso de vuelta —`calibradorVisible(false)`—
            // sale de un solo sitio y no puede quedarse encendido.
            calibradorHtmlAbierto = false
            runCatching { web?.evaluateJavascript("calCerrar()", null) }
            return
        }
        super.onBackPressed()
    }

    /**
     * ¿El calibrador del tablero HTML esta delante?
     *
     * Lo escribe el hilo del WebView y lo lee el de interfaz, de ahi el
     * `@Volatile`. Es un booleano, asi que no hace falta mas.
     */
    @Volatile
    private var calibradorHtmlAbierto = false

    /** ¿Hay un cuadro del tablero HTML en edicion (dedo sostenido)? */
    @Volatile
    private var edicionCuadroAbierta = false

    /**
     * Lo unico que el JavaScript puede llamar.
     *
     * Ojo: estos metodos NO corren en el hilo de interfaz, sino en el del
     * WebView. Por eso lo que toca vistas o arranca Activities va envuelto en
     * `runCatching` y lo demas solo lee estado `@Volatile`.
     */
    inner class Puente {

        // ---------------- calibracion de las llantas ----------------
        //
        // Los sensores TPMS baratos se desvian por una constante. Estos
        // mandos dejan corregirlos desde el propio tablero, sosteniendo el
        // dedo sobre la rueda, sin entrar en ajustes ni recompilar nada.

        /** Mueve la correccion de una rueda. Devuelve la resultante en PSI. */
        @JavascriptInterface
        fun calibrarLlanta(rueda: Int, pasos: Int): Float =
            com.nonosky.carsoc.config.CalibracionLlantas
                .mover(this@TableroActivity, rueda, pasos)

        /** Deja esa rueda —o las cuatro, si esta puesto— sin correccion. */
        @JavascriptInterface
        fun calibrarACero(rueda: Int) {
            com.nonosky.carsoc.config.CalibracionLlantas
                .poner(this@TableroActivity, rueda, 0f)
        }

        /**
         * ¿Un toque corrige las cuatro a la vez? Encendido por omision: el
         * desvio suele venir del juego entero de sensores o de comparar
         * contra otro manometro, asi que corregir las cuatro acierta casi
         * siempre.
         */
        @JavascriptInterface
        fun calibrarTodasALaVez(): Boolean =
            com.nonosky.carsoc.config.CalibracionLlantas
                .aplicarATodas(this@TableroActivity)

        @JavascriptInterface
        fun ponerCalibrarTodasALaVez(valor: Boolean) {
            com.nonosky.carsoc.config.CalibracionLlantas
                .ponerAplicarATodas(this@TableroActivity, valor)
        }

        /** La correccion guardada de una rueda, para pintarla en el mando. */
        @JavascriptInterface
        fun ajusteLlanta(rueda: Int): Float =
            com.nonosky.carsoc.config.CalibracionLlantas
                .ajuste(this@TableroActivity, rueda)

        /** El paso de cada toque, para que la pantalla no lo suponga. */
        @JavascriptInterface
        fun pasoCalibracion(): Float =
            com.nonosky.carsoc.config.CalibracionLlantas.PASO_PSI

        /**
         * El tablero HTML avisa de que su calibrador esta delante.
         *
         * Sirve para una sola cosa: que ATRAS cierre el modal y no el tablero.
         * La variante Canvas no necesita contarlo —la vista sabe lo que
         * pinta—, pero el WebView es una caja negra desde aqui, asi que si no
         * lo dice, no se sabe. Y sin saberlo, el mismo boton hace dos cosas
         * distintas en los dos tableros del mismo carro.
         */
        @JavascriptInterface
        fun calibradorVisible(abierto: Boolean) {
            calibradorHtmlAbierto = abierto
        }

        @JavascriptInterface
        fun abrirConfiguracion() {
            abrirAjustes()
        }

        @JavascriptInterface
        fun abrirDiagnostico() {
            abrirAverias()
        }

        /** ESCANEAR CODIGO: leer los del carro y la base de codigos explicada. */
        @JavascriptInterface
        fun abrirCodigos() {
            runCatching {
                startActivity(
                    Intent(this@TableroActivity, com.nonosky.carsoc.diag.CodigosActivity::class.java)
                )
            }
        }

        /** Como dejo el dueño los cuadros. Vacio = como vienen. */
        @JavascriptInterface
        fun leerCuadros(): String = com.nonosky.carsoc.config.CuadrosTablero.leer(this@TableroActivity)

        @JavascriptInterface
        fun guardarCuadros(json: String) {
            com.nonosky.carsoc.config.CuadrosTablero.guardar(this@TableroActivity, json)
        }

        @JavascriptInterface
        fun edicionCuadroVisible(abierta: Boolean) {
            edicionCuadroAbierta = abierta
        }

        /** La X del tablero HTML. Llega en el hilo del WebView: se pasa al de la pantalla. */
        @JavascriptInterface
        fun cerrar() {
            runOnUiThread { this@TableroActivity.cerrar() }
        }

        @JavascriptInterface
        fun neveraEncender(on: Boolean): Boolean = this@TableroActivity.neveraEncender(on)

        @JavascriptInterface
        fun neveraEco(eco: Boolean): Boolean = this@TableroActivity.neveraEco(eco)

        @JavascriptInterface
        fun neveraConsigna(delta: Int): Boolean = this@TableroActivity.neveraConsigna(delta)

        /**
         * Cambia de variante desde el propio tablero HTML.
         *
         * `Puente.cambiarVariante("lienzo")` y la pantalla se rehace con el
         * tablero Canvas; `"html"` vuelve. Cualquier otra cadena no hace nada:
         * viene de JavaScript, y guardar basura dejaria el arranque
         * decidiendose por un `else`.
         *
         * @return false si el nombre no vale o no se pudo guardar. Se contesta
         *   en vez de fallar en silencio para que el HTML pueda decirlo.
         */
        @JavascriptInterface
        fun cambiarVariante(cual: String): Boolean {
            if (!Variante.poner(this@TableroActivity, cual)) return false
            // `recreate()` toca la Activity, asi que tiene que ir al hilo de
            // interfaz: esto corre en el del WebView.
            runOnUiThread { runCatching { recreate() } }
            return true
        }

        /** Cual esta puesta ahora. Para que el HTML pinte el interruptor. */
        @JavascriptInterface
        fun variante(): String = Variante.actual(this@TableroActivity)

        /**
         * El estado del carro en JSON.
         *
         * La regla dura del proyecto —un dato ausente o rancio es `null`, jamas
         * 0— se aplica en [EstadoDelTablero], no aqui y no en la pantalla. Esto
         * solo serializa lo que ya vino decidido, y por eso la variante Canvas
         * no puede divergir: lee el mismo objeto antes de serializarlo.
         */
        /**
         * Cada cuanto vuelve a preguntar el JavaScript: el mismo ritmo que el
         * termometro le da a la variante Canvas (el HTML le pone un suelo).
         */
        @JavascriptInterface
        fun msEntreCuadros(): Long = Termometro.msEntreCuadros()

        @JavascriptInterface
        fun estado(): String =
            EstadoDelTablero.aJson(
                EstadoDelTablero.leer(System.currentTimeMillis(), this@TableroActivity))
    }
}
