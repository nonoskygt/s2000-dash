package com.nonosky.carsoc.config

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.nonosky.carsoc.Arranque
import com.nonosky.carsoc.Carro
import com.nonosky.carsoc.ConnectionState
import com.nonosky.carsoc.EstadoActual
import com.nonosky.carsoc.Variante
import com.nonosky.carsoc.bateria.BancosBateria
import com.nonosky.carsoc.bt.ObdPairing
import com.nonosky.carsoc.hci.HciUsb
import com.nonosky.carsoc.hci.SondaHci
import kotlin.concurrent.thread

/**
 * El menu de configuracion y emparejamiento.
 *
 * Aqui se decide QUE aparato hace cada papel: bateria de arranque, bateria
 * de vivienda, refrigeradora y adaptador OBD-II. Antes eso vivia en
 * constantes del codigo, y el vigilante viejo ni eso: barria y se quedaba
 * con el primer BMS que veia.
 *
 * ## El selector, como el de cualquier otro aparato
 *
 * Tocar un papel abre una lista como la de los Ajustes de Bluetooth del
 * telefono: los ya emparejados salen al instante, los cercanos van
 * apareciendo mientras se barre, cada uno con su nombre y su señal, y lo que
 * tiene pinta de servir para ese papel va arriba. Tocar uno lo empareja si
 * hace falta —con el dialogo de PIN de siempre— y lo conecta AL MOMENTO.
 *
 * La version anterior esperaba 15 s mirando un mensaje fijo, enseñaba MACs
 * en vez de nombres, y para el OBD llamaba a un barrido que solo registraba
 * el tablero viejo: desde el tablero nuevo la lista salia vacia SIEMPRE. Y lo elegido no se usaba hasta reiniciar el radio.
 *
 * Se dibuja con vistas nativas y a mano, sin XML, igual que el resto del
 * proyecto. No es el tablero: se abre con el carro parado, se usa una vez y
 * se cierra, asi que no compite por pixeles ni por milisegundos.
 */
@SuppressLint("MissingPermission")
class ConfiguracionActivity : Activity() {

    private lateinit var raiz: LinearLayout
    private val ui = Handler(Looper.getMainLooper())

    /** Si no es null, el selector esta abierto buscando aparato para ESTE papel. */
    private var papelBuscado: Emparejados.Papel? = null

    /** Lo que se ve en el selector, por MAC. Se llena en vivo. */
    private val vistos = LinkedHashMap<String, PistasAparato.Aparato>()

    private var barriendo = false

    /** MAC que se esta emparejando ahora mismo; null = ninguna. */
    private var emparejando: String? = null

    /** Que paso con lo ultimo que toco el dueño. Se enseña arriba. */
    private var aviso: String? = null

    /** Tras fallar un emparejamiento: ofrecer usarlo sin emparejar. */
    private var fallido: PistasAparato.Aparato? = null

    private var pairing: ObdPairing? = null
    private var bleCb: ScanCallback? = null

    /** El barrido en curso va por el dongle USB y no por la radio interna. */
    private var porDongle = false

    /** Las lineas de estado de cada papel, para refrescarlas sin repintar todo. */
    private val lineasDeEstado = HashMap<Emparejados.Papel, TextView>()

    /** Las filas del selector, para moverles la señal sin repintar todo. */
    private val filasDelSelector = HashMap<String, TextView>()

    private val finDelBarrido = Runnable {
        detenerBarrido()
        barriendo = false
        pintar()
    }

    /** Hay un repintado ya programado: no se pospone por cada hallazgo. */
    private var repintoPendiente = false

    private val repintar = Runnable { pintar() }

    private val refrescarEstados = object : Runnable {
        override fun run() {
            if (papelBuscado == null) {
                for ((p, tv) in lineasDeEstado) {
                    val (texto, bien) = estadoDe(p)
                    tv.text = texto
                    tv.setTextColor(if (bien) VIVO else AMBAR)
                }
                refrescarLlantas()
            }
            ui.postDelayed(this, MS_REFRESCO)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(FONDO)
            setPadding(dp(18), dp(14), dp(18), dp(18))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(FONDO)
            addView(raiz, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
        pintar()
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(refrescarEstados)
        ui.post(refrescarEstados)
    }

    override fun onPause() {
        ui.removeCallbacks(refrescarEstados)
        super.onPause()
    }

    override fun onDestroy() {
        detenerBarrido()
        ui.removeCallbacksAndMessages(null)
        runCatching { pairing?.stop() }
        pairing = null
        super.onDestroy()
    }

    @Deprecated("onBackPressed sigue siendo la via en API 30, que es la del radio")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Atras cierra el selector antes que la pantalla, igual que el boton.
        if (papelBuscado != null) cerrarSelector() else super.onBackPressed()
    }

    // ---------------------------------------------------------------- vista

    private fun pintar() {
        ui.removeCallbacks(repintar)
        repintoPendiente = false
        raiz.removeAllViews()
        lineasDeEstado.clear()
        lineaLlantas = null
        filasDelSelector.clear()

        raiz.addView(titulo("Configuración"))
        raiz.addView(nota("${Carro.perfil.vehiculo} · ${Carro.perfil.motor}"))
        aviso?.let { raiz.addView(avisoVista(it)) }

        val buscando = papelBuscado
        if (buscando == null) pintarPapeles() else pintarSelector(buscando)
    }

    private fun pintarPapeles() {
        raiz.addView(nota("Toca un papel para elegir su aparato Bluetooth."))
        for (p in Emparejados.papelesDeEsteCarro()) {
            val mac = Emparejados.mac(this, p)
            val nombre = Emparejados.nombre(this, p)
            val asignado = mac.isNotBlank()
            val detalle =
                if (!asignado) "sin asignar · toca para elegir"
                else "${nombre.ifBlank { "sin nombre" }}  ·  $mac  ·  toca para cambiarlo"
            val (texto, bien) = estadoDe(p)
            val fila = fila(p.rotulo, detalle, asignado) { abrirSelector(p) }
            val estado = TextView(this).apply {
                text = texto
                setTextColor(if (bien) VIVO else AMBAR)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(4), 0, 0)
            }
            fila.addView(estado)
            lineasDeEstado[p] = estado
            raiz.addView(fila)
            if (asignado) {
                raiz.addView(botonPequeno("Olvidar: ${p.rotulo}") {
                    Emparejados.olvidar(this, p)
                    aplicar(p, "${p.rotulo}: olvidado")
                })
            }
        }

        raiz.addView(nota(
            "Lo que elijas se aplica al instante: el tablero se reconecta solo. " +
                "Un aparato sin asignar no se sondea: no gasta radio."
        ))

        if (Carro.perfil.tieneTpms) {
            raiz.addView(separador())
            pintarLlantas()
            raiz.addView(separador())
            pintarAlertas()
        }

        raiz.addView(separador())
        pintarArranque()

        raiz.addView(separador())
        pintarCuadros()

        raiz.addView(separador())
        pintarVariante()
    }

    /**
     * Los cuadros del tablero se ocultan, se mueven y se agrandan sosteniendo
     * el dedo encima, en el propio tablero. Aqui esta la salida de emergencia:
     * si uno se oculto sin querer, se recupera desde un sitio que siempre esta.
     */
    private fun pintarCuadros() {
        val ocultos = CuadrosTablero.ocultos(this)
        val cambios = CuadrosTablero.hayCambios(this)
        raiz.addView(fila(
            "Cuadros del tablero",
            when {
                ocultos > 0 -> "$ocultos oculto(s)  ·  toca para mostrar todos como al principio"
                cambios -> "Movidos o con otro tamaño  ·  toca para dejarlos como al principio"
                else -> "Todos a la vista, como al principio"
            },
            ok = !cambios,
        ) {
            if (cambios) {
                CuadrosTablero.restaurar(this)
                aviso = "Cuadros como al principio. Se ve al volver al tablero."
                pintar()
            }
        })
        raiz.addView(nota(
            "En el tablero, sostén el dedo sobre un cuadro para ocultarlo, " +
                "moverlo o cambiarle el tamaño."
        ))
    }

    /**
     * CALIBRAR LAS LLANTAS: un solo deslizador que suma o resta PSI a las
     * cuatro a la vez.
     *
     * Los sensores TPMS baratos se desvian. El dueño mide con un manometro
     * digital y mueve el deslizador hasta que aqui marquen lo mismo; las
     * presiones de las cuatro se ven en vivo, ya corregidas.
     *
     * Es la misma correccion que la del dedo sostenido sobre una rueda en el
     * tablero (`CalibracionLlantas`): se guarda en el mismo sitio y vale para
     * las dos variantes. Mover el deslizador pone LA MISMA correccion en las
     * cuatro, aunque antes tuvieran una distinta cada una.
     */
    private fun pintarLlantas() {
        raiz.addView(subtitulo("Calibrar llantas"))
        raiz.addView(nota(
            "Mide las llantas con un manómetro y mueve el deslizador hasta que " +
                "aquí marquen lo mismo. Suma o resta lo mismo a los cuatro sensores."
        ))
        val ajuste = TextView(this).apply {
            setTextColor(TINTA)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val pasos = (CalibracionLlantas.TOPE_PSI / CalibracionLlantas.PASO_PSI).toInt()
        val actual = CalibracionLlantas.ajusteComun(this)
        fun rotular(psi: Float) {
            ajuste.text = if (psi == 0f) "Sin corrección" else "%+.1f PSI a las cuatro".format(psi)
        }
        rotular(actual ?: 0f)
        raiz.addView(ajuste)
        if (actual == null) {
            raiz.addView(nota(
                "Ahora cada rueda tiene una corrección distinta. Mover el " +
                    "deslizador les pone la misma a las cuatro."
            ))
        }
        val barra = SeekBar(this).apply {
            max = pasos * 2
            progress = pasos + Math.round((actual ?: 0f) / CalibracionLlantas.PASO_PSI)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, valor: Int, delUsuario: Boolean) {
                    if (!delUsuario) return
                    val psi = (valor - pasos) * CalibracionLlantas.PASO_PSI
                    CalibracionLlantas.ponerEnTodas(this@ConfiguracionActivity, psi)
                    rotular(psi)
                    refrescarLlantas()
                }
                override fun onStartTrackingTouch(b: SeekBar?) = Unit
                override fun onStopTrackingTouch(b: SeekBar?) = Unit
            })
        }
        raiz.addView(barra)
        val lecturas = TextView(this).apply {
            setTextColor(ARENA)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(4), 0, dp(8))
        }
        lineaLlantas = lecturas
        raiz.addView(lecturas)
        refrescarLlantas()
    }

    /** La linea con las cuatro presiones, ya corregidas. La refresca el latido. */
    private var lineaLlantas: TextView? = null

    private fun refrescarLlantas() {
        val tv = lineaLlantas ?: return
        val ahora = System.currentTimeMillis()
        val estado = EstadoActual.lectorTpms?.estado()
        val partes = com.nonosky.carsoc.tpms.Rueda.values().map { r ->
            val l = estado?.de(r)
            val vale = l != null && !l.rancia(ahora)
            val psi = if (vale) CalibracionLlantas.corregir(this, r.ordinal, l?.presionPsi) else null
            val temp = if (vale) l?.temperaturaC else null
            "${r.corta} " + (psi?.let { "%.1f".format(it) } ?: "--") + " PSI " +
                (temp?.let { "$it°" } ?: "--")
        }
        tv.text = partes.joinToString("    ")
    }

    /**
     * CUANDO AVISA UNA LLANTA: presion baja, presion alta y temperatura.
     *
     * Los tres limites los usan por igual la notificacion —que suena aunque
     * el tablero este cerrado— y las dos variantes del tablero, siempre
     * contra la presion YA CALIBRADA de arriba (`AlertasLlantas`). Se guardan
     * al mover el deslizador y valen desde la siguiente lectura.
     */
    private fun pintarAlertas() {
        raiz.addView(subtitulo("Alertas de llantas"))
        raiz.addView(nota(
            "Suena una alarma y la rueda se pinta en rojo cuando cruza uno de estos " +
                "límites. Se compara con la presión ya calibrada."
        ))
        deslizador(
            AlertasLlantas.RANGO_BAJA.start, AlertasLlantas.RANGO_BAJA.endInclusive,
            AlertasLlantas.PASO_PSI, AlertasLlantas.psiBaja(this),
            { "Presión baja: menos de %.1f PSI".format(it) },
        ) { AlertasLlantas.ponerPsiBaja(this, it) }
        deslizador(
            AlertasLlantas.RANGO_ALTA.start, AlertasLlantas.RANGO_ALTA.endInclusive,
            AlertasLlantas.PASO_PSI, AlertasLlantas.psiAlta(this),
            { "Presión alta: más de %.1f PSI".format(it) },
        ) { AlertasLlantas.ponerPsiAlta(this, it) }
        deslizador(
            AlertasLlantas.RANGO_TEMP.first.toFloat(), AlertasLlantas.RANGO_TEMP.last.toFloat(),
            1f, AlertasLlantas.tempAlta(this).toFloat(),
            { "Temperatura alta: desde ${it.toInt()} °C" },
        ) { AlertasLlantas.ponerTempAlta(this, it.toInt()) }
    }

    /** Un rotulo con el valor y su deslizador debajo, de [minimo] a [maximo]. */
    private fun deslizador(
        minimo: Float,
        maximo: Float,
        paso: Float,
        actual: Float,
        texto: (Float) -> String,
        alCambiar: (Float) -> Unit,
    ) {
        val rotulo = TextView(this).apply {
            setTextColor(TINTA)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, 0)
            text = texto(actual)
        }
        raiz.addView(rotulo)
        val pasos = Math.round((maximo - minimo) / paso)
        raiz.addView(SeekBar(this).apply {
            max = pasos
            progress = Math.round((actual - minimo) / paso).coerceIn(0, pasos)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, valor: Int, delUsuario: Boolean) {
                    if (!delUsuario) return
                    val v = minimo + valor * paso
                    alCambiar(v)
                    rotulo.text = texto(v)
                }
                override fun onStartTrackingTouch(b: SeekBar?) = Unit
                override fun onStopTrackingTouch(b: SeekBar?) = Unit
            })
        })
    }

    /**
     * ¿El tablero se abre solo al encender el radio? Las alertas de llanta,
     * las baterias y el contador del aceite siguen trabajando en segundo plano
     * aunque se diga que no: lo que no se abre es la pantalla.
     */
    private fun pintarArranque() {
        val abre = Arranque.abrirAlEncender(this)
        raiz.addView(fila(
            "Al encender el radio",
            if (abre) "Se abre el tablero solo  ·  toca para que NO se abra"
            else "No se abre el tablero  ·  toca para que se abra solo",
            ok = abre,
        ) {
            Arranque.poner(this, !abre)
            pintar()
        })
        raiz.addView(nota(
            "Aunque no se abra, siguen vigilando en segundo plano las llantas, " +
                "las baterías y el contador del aceite."
        ))
    }

    /**
     * COMO SE PINTA EL TABLERO: HTML o Canvas.
     *
     * Es una fila y no un menu porque solo hay dos, y con dos un interruptor se
     * entiende sin leer nada: dice cual esta puesta y a cual se cambia.
     *
     * Los dos tableros enseñan lo mismo con los mismos datos —la lectura y las
     * reglas de frescura son las de `EstadoDelTablero`, compartidas— asi que
     * esto no cambia lo que se ve, sino quien lo pinta y lo que cuesta. El
     * Canvas repinta al ritmo del termometro del radio y baja a un cuadro por
     * segundo cuando el aparato se calienta; el WebView, no.
     */
    private fun pintarVariante() {
        val actual = Variante.actual(this)
        val otra = Variante.contraria(actual)
        raiz.addView(fila(
            "Tablero",
            "${Variante.rotulo(actual)}  ·  toca para usar ${Variante.rotulo(otra)}",
            ok = true,
        ) {
            Variante.poner(this, otra)
            pintar()
        })
        raiz.addView(nota(
            "El tablero Canvas repinta al ritmo del termómetro del radio: " +
                "5 cuadros por segundo en frío y uno en caliente. " +
                "El cambio se ve al volver a abrir el tablero."
        ))
    }

    private fun pintarSelector(p: Emparejados.Papel) {
        raiz.addView(subtitulo("Elegir: ${p.rotulo}"))
        raiz.addView(botonPequeno("← Volver sin cambiar") { cerrarSelector() })

        val a = adaptador()
        if (a == null) {
            raiz.addView(nota("Este radio no expone Bluetooth."))
            return
        }
        if (!a.isEnabled) {
            raiz.addView(nota("El Bluetooth del radio está apagado."))
            raiz.addView(botonPequeno("Encender Bluetooth") { encenderBluetooth(a) })
            return
        }
        if (permisosQueFaltan().isNotEmpty()) {
            raiz.addView(nota(
                "Android no deja buscar aparatos Bluetooth sin el permiso de " +
                    "ubicación. Sin él, la búsqueda devuelve cero EN SILENCIO."
            ))
            raiz.addView(botonPequeno("Dar permiso") { pedirPermisos() })
            return
        }
        if (!ubicacionEncendida()) {
            raiz.addView(avisoVista(
                "La ubicación del radio está apagada. En este Android, con ella " +
                    "apagada la búsqueda Bluetooth no encuentra nada."
            ))
            raiz.addView(botonPequeno("Abrir ajustes de ubicación") {
                runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            })
        }

        fallido?.let { f ->
            raiz.addView(botonPequeno("Reintentar emparejar con ${nombreDe(f)}") { elegir(p, f) })
            raiz.addView(botonPequeno("Usar ${nombreDe(f)} sin emparejar") {
                asignarYConectar(p, f)
            })
        }

        raiz.addView(nota(when {
            emparejando != null -> "Emparejando…"
            barriendo && p.esBle && porDongle -> "Buscando aparatos Bluetooth LE cerca, por el dongle USB…"
            barriendo && p.esBle -> "Buscando aparatos Bluetooth LE cerca…"
            barriendo -> "Buscando aparatos Bluetooth cerca…"
            vistos.isEmpty() -> "No apareció ningún aparato."
            else -> "Toca el que corresponda."
        }))

        val lista = PistasAparato.ordenar(p, vistos.values)
        val (emparejados, cercanos) =
            if (p.esBle) emptyList<PistasAparato.Aparato>() to lista
            else lista.partition { it.emparejado && it.rssi == null }
        if (emparejados.isNotEmpty()) {
            raiz.addView(subtitulo("Emparejados en este radio"))
            emparejados.forEach { raiz.addView(filaAparato(p, it)) }
        }
        if (cercanos.isNotEmpty()) {
            raiz.addView(subtitulo("Cerca"))
            cercanos.forEach { raiz.addView(filaAparato(p, it)) }
        }

        if (!barriendo && emparejando == null) {
            if (vistos.isEmpty()) {
                raiz.addView(nota(
                    if (p == Emparejados.Papel.AdaptadorObd)
                        "Comprueba que el adaptador está enchufado y con el contacto " +
                            "puesto: los OBDLink se duermen con el carro apagado. Y que " +
                            "no lo tenga tomado el teléfono: atienden a uno solo."
                    else
                        "Comprueba que está encendido y cerca. La nevera deja de " +
                            "anunciarse mientras la app del teléfono está conectada a ella."
                ))
            }
            raiz.addView(botonPequeno("Buscar otra vez") { empezarBarrido(p) })
        }
    }

    private fun filaAparato(p: Emparejados.Papel, a: PistasAparato.Aparato): View {
        val pista = PistasAparato.pista(p, a)
        val noSirve = p == Emparejados.Papel.AdaptadorObd && a.tipo == "BLE"
        val v = fila(nombreDe(a), detalleDe(p, a), ok = pista != null && !noSirve) { elegir(p, a) }
        // La segunda linea es la que lleva la señal: se guarda para moverla
        // en vivo sin repintar la pantalla bajo el dedo del dueño.
        (v.getChildAt(1) as? TextView)?.let { filasDelSelector[a.mac] = it }
        if (emparejando == a.mac) v.alpha = 0.6f
        return v
    }

    private fun nombreDe(a: PistasAparato.Aparato): String =
        a.nombre?.takeIf { it.isNotBlank() } ?: "(sin nombre) ${a.mac}"

    private fun detalleDe(p: Emparejados.Papel, a: PistasAparato.Aparato): String {
        val partes = mutableListOf<String>()
        PistasAparato.pista(p, a)?.let { partes += it }
        if (p == Emparejados.Papel.AdaptadorObd && a.tipo == "BLE") {
            partes += "solo BLE: este tablero necesita un adaptador clásico"
        }
        partes += a.mac
        PistasAparato.barras(a.rssi)?.let { b ->
            // En palabras: los simbolos de barras se veian todos iguales en
            // la letra de este radio.
            partes += "señal " + when (b) {
                4 -> "muy buena"
                3 -> "buena"
                2 -> "media"
                1 -> "débil"
                else -> "muy débil"
            }
        }
        if (a.emparejado) partes += "emparejado"
        if (emparejando == a.mac) partes += "emparejando…"
        return partes.joinToString("  ·  ")
    }

    // ------------------------------------------------------------- selector

    private fun abrirSelector(p: Emparejados.Papel) {
        papelBuscado = p
        aviso = null
        fallido = null
        vistos.clear()
        empezarBarrido(p)
    }

    private fun cerrarSelector() {
        detenerBarrido()
        papelBuscado = null
        emparejando = null
        fallido = null
        vistos.clear()
        pintar()
    }

    private fun empezarBarrido(p: Emparejados.Papel) {
        detenerBarrido()
        val a = adaptador()
        if (a == null || !a.isEnabled) { pintar(); return }
        if (permisosQueFaltan().isNotEmpty()) {
            pedirPermisos()
            pintar()
            return
        }

        if (!p.esBle) {
            // Los emparejados salen YA, sin esperar a barrer: casi siempre el
            // adaptador esta ahi y no hace falta buscar nada.
            runCatching { a.bondedDevices.orEmpty() }.getOrDefault(emptySet()).forEach { d ->
                alVerAparato(desdeDevice(d, rssi = null))
            }
        }

        barriendo = true
        porDongle = false
        val arranco = if (p.esBle) barrerBle(a) else barrerClasico(a)
        if (!arranco) barriendo = false
        // El del dongle se termina solo, en su hilo: no lleva temporizador.
        else if (!porDongle) {
            ui.postDelayed(finDelBarrido, if (p.esBle) MS_BARRIDO_BLE else MS_BARRIDO_CLASICO)
        }
        pintar()
    }

    private fun detenerBarrido() {
        ui.removeCallbacks(finDelBarrido)
        val a = adaptador()
        bleCb?.let { cb -> runCatching { a?.bluetoothLeScanner?.stopScan(cb) } }
        bleCb = null
        runCatching { if (a?.isDiscovering == true) a.cancelDiscovery() }
    }

    private val oyente = object : ObdPairing.Listener {
        override fun onDevices(devices: List<BluetoothDevice>) {
            ui.post {
                if (papelBuscado?.esBle != false) return@post
                devices.forEach { d -> alVerAparato(desdeDevice(d, rssi = null)) }
            }
        }

        override fun onBonded(device: BluetoothDevice) {
            ui.post {
                val p = papelBuscado ?: return@post
                if (emparejando != device.address) return@post
                emparejando = null
                val a = vistos[device.address] ?: desdeDevice(device, rssi = null)
                asignarYConectar(p, a.copy(emparejado = true))
            }
        }

        override fun onBondFailed(device: BluetoothDevice) {
            ui.post { fallaEmparejando(device.address) }
        }

        override fun onScanFinished() {
            ui.post {
                if (papelBuscado?.esBle == false && barriendo) {
                    ui.removeCallbacks(finDelBarrido)
                    barriendo = false
                    pintar()
                }
            }
        }
    }

    private fun barrerClasico(a: BluetoothAdapter): Boolean = runCatching {
        val pr = pairing ?: ObdPairing(this, a).also { pairing = it }
        pr.start(oyente)
        if (a.isDiscovering) a.cancelDiscovery()
        a.startDiscovery()
    }.getOrElse {
        aviso = "No se pudo buscar: ${it.message}"
        false
    }

    private fun barrerBle(a: BluetoothAdapter): Boolean {
        // Con el dongle USB enchufado se busca por EL. Hay radios cuyo BLE
        // interno no recibe ni un anuncio —medido—: buscar por ahi
        // daria una lista vacia que parece "no hay baterias cerca".
        if (HciUsb.hayDongle(this)) return barrerBlePorDongle()
        val scanner = runCatching { a.bluetoothLeScanner }.getOrNull() ?: run {
            aviso = "Este radio no deja buscar Bluetooth LE ahora mismo."
            return false
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(tipo: Int, r: ScanResult) {
                ui.post { runCatching { desdeScan(r) }.getOrNull()?.let { alVerAparato(it) } }
            }

            override fun onScanFailed(codigo: Int) {
                ui.post {
                    aviso = "La búsqueda Bluetooth LE falló (código $codigo). Prueba otra vez."
                    ui.removeCallbacks(finDelBarrido)
                    barriendo = false
                    pintar()
                }
            }
        }
        return runCatching {
            // LOW_LATENCY solo aqui: es un barrido corto que pide el dueño con
            // el carro parado. El del servicio va en BALANCED por el calor.
            val ajustes = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, ajustes, cb)
            bleCb = cb
            true
        }.getOrElse {
            aviso = "No se pudo buscar: ${it.message}"
            false
        }
    }

    /**
     * El mismo barrido BLE, por el dongle USB. Bloquea su hilo los segundos
     * que dura; los hallazgos van llegando a la lista uno a uno.
     */
    private fun barrerBlePorDongle(): Boolean {
        porDongle = true
        thread(isDaemon = true) {
            val salida = runCatching {
                SondaHci.barrerBle(
                    applicationContext,
                    (MS_BARRIDO_BLE / 1000).toInt(),
                    alVer = { mac, nombre, rssi, uuids ->
                        ui.post {
                            alVerAparato(PistasAparato.Aparato(
                                mac = mac, nombre = nombre, tipo = "BLE", rssi = rssi,
                                emparejado = false, uuids = uuids,
                            ))
                        }
                    },
                )
            }.getOrElse { listOf("ERROR: ${it.message}") }
            // "el dongle lo tiene X" o un ERROR: decirlo, no callarlo.
            val problema = salida.firstOrNull { it.startsWith("ERROR") || it.startsWith("el dongle lo tiene") }
            ui.post {
                if (problema != null) aviso = "Búsqueda por el dongle USB: $problema"
                ui.removeCallbacks(finDelBarrido)
                barriendo = false
                pintar()
            }
        }
        return true
    }

    /**
     * Un aparato visto. Si es NUEVO se repinta la lista (con un respiro, para
     * no rehacer la pantalla veinte veces por segundo); si ya estaba, solo se
     * le actualiza la linea de la señal, sin mover nada bajo el dedo.
     */
    private fun alVerAparato(a: PistasAparato.Aparato) {
        val p = papelBuscado ?: return
        val previo = vistos[a.mac]
        val junto = if (previo == null) a else previo.copy(
            nombre = a.nombre?.takeIf { it.isNotBlank() } ?: previo.nombre,
            tipo = if (a.tipo != "DESCONOCIDO") a.tipo else previo.tipo,
            rssi = a.rssi ?: previo.rssi,
            emparejado = a.emparejado || previo.emparejado,
            uuids = (previo.uuids + a.uuids).distinct(),
        )
        vistos[a.mac] = junto
        val fila = filasDelSelector[a.mac]
        if (previo == null || fila == null || previo.nombre != junto.nombre) {
            // Sin posponer: con aparatos llegando cada pocos cientos de ms,
            // reprogramar en cada uno dejaria la lista sin pintar nunca.
            if (!repintoPendiente) {
                repintoPendiente = true
                ui.postDelayed(repintar, MS_REPINTAR)
            }
        } else {
            fila.text = detalleDe(p, junto)
        }
    }

    private fun desdeDevice(d: BluetoothDevice, rssi: Int?): PistasAparato.Aparato =
        PistasAparato.Aparato(
            mac = d.address.uppercase(),
            nombre = runCatching { d.name }.getOrNull(),
            tipo = when (runCatching { d.type }.getOrNull()) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> "CLASICO"
                BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
                BluetoothDevice.DEVICE_TYPE_DUAL -> "DUAL"
                else -> "DESCONOCIDO"
            },
            rssi = rssi,
            emparejado = runCatching { d.bondState == BluetoothDevice.BOND_BONDED }
                .getOrDefault(false),
            uuids = runCatching { d.uuids?.map { it.uuid.toString() } }.getOrNull().orEmpty(),
        )

    private fun desdeScan(r: ScanResult): PistasAparato.Aparato {
        val d = r.device
        return desdeDevice(d, rssi = r.rssi).copy(
            nombre = r.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                ?: runCatching { d.name }.getOrNull(),
            tipo = "BLE",
            uuids = r.scanRecord?.serviceUuids?.map { it.uuid.toString() }.orEmpty(),
        )
    }

    /** El dueño toco un aparato de la lista. */
    private fun elegir(p: Emparejados.Papel, a: PistasAparato.Aparato) {
        if (emparejando != null) return
        if (p == Emparejados.Papel.AdaptadorObd && a.tipo == "BLE") {
            aviso = "${nombreDe(a)} solo habla Bluetooth LE. El tablero necesita un " +
                "adaptador clásico (SPP), como el OBDLink MX o un ELM327 Bluetooth."
            pintar()
            return
        }
        // Lo clasico se empareja antes de usarlo, como cualquier aparato. Lo
        // BLE de este carro (BMS y nevera) no se empareja: se llama y ya.
        if (!p.esBle && !a.emparejado) emparejar(p, a) else asignarYConectar(p, a)
    }

    private fun emparejar(p: Emparejados.Papel, a: PistasAparato.Aparato) {
        val ad = adaptador() ?: return
        val dev = runCatching { ad.getRemoteDevice(a.mac) }.getOrNull() ?: return
        detenerBarrido()
        barriendo = false
        fallido = null
        emparejando = a.mac
        aviso = "Emparejando con ${nombreDe(a)}… Si el radio pide un código, prueba " +
            "1234 (o 0000). En los OBDLink, pulsa el botón del adaptador si lo pide."
        pintar()
        val pr = pairing ?: ObdPairing(this, ad).also { pairing = it }
        pr.start(oyente)
        // bond() puede dormir segundo y medio si hay un vinculo colgado: fuera
        // del hilo de la pantalla.
        thread(isDaemon = true) { runCatching { pr.bond(dev) } }
        ui.postDelayed({ fallaEmparejando(a.mac) }, MS_EMPAREJAR)
    }

    private fun fallaEmparejando(mac: String) {
        if (emparejando != mac) return
        emparejando = null
        val a = vistos[mac]
        fallido = a
        aviso = "No se pudo emparejar con ${a?.let { nombreDe(it) } ?: mac}. " +
            "Comprueba que está enchufado, con el contacto puesto, y que no lo tiene " +
            "tomado el teléfono. Algunos ELM327 funcionan sin emparejar."
        pintar()
    }

    private fun asignarYConectar(p: Emparejados.Papel, a: PistasAparato.Aparato) {
        detenerBarrido()
        Emparejados.asignar(this, p, a.mac, a.nombre)
        papelBuscado = null
        emparejando = null
        fallido = null
        barriendo = false
        vistos.clear()
        aplicar(p, "${p.rotulo}: ${nombreDe(a)} elegido. Conectando…")
    }

    /**
     * Le dice al servicio que use YA lo elegido, y enseña lo que contesta.
     * Va en un hilo: reconectar el OBD suelta y vuelve a abrir el enlace.
     */
    private fun aplicar(p: Emparejados.Papel, mensaje: String) {
        aviso = mensaje
        pintar()
        thread(isDaemon = true) {
            val r = runCatching { EstadoActual.reconectar?.invoke(p) }.getOrNull()
                ?: "el servicio del tablero no está corriendo: se aplicará al abrirlo"
            ui.post {
                aviso = "${p.rotulo}: $r"
                if (papelBuscado == null) pintar()
            }
        }
    }

    /** Que esta haciendo ahora el aparato de este papel, en palabras. */
    private fun estadoDe(p: Emparejados.Papel): Pair<String, Boolean> {
        if (!Emparejados.hay(this, p)) return "sin aparato" to false
        val ahora = System.currentTimeMillis()
        return when (p) {
            Emparejados.Papel.AdaptadorObd -> when (EstadoActual.ultimo.connection) {
                ConnectionState.Polling -> "conectado · leyendo el motor" to true
                ConnectionState.Initializing -> "conectado · hablando con la computadora del carro…" to false
                ConnectionState.Connecting -> "conectando…" to false
                ConnectionState.Disconnected ->
                    "sin enlace · ¿contacto puesto? ¿adaptador enchufado?" to false
                ConnectionState.SinAdaptador -> "sin adaptador" to false
                ConnectionState.BluetoothApagado -> "Bluetooth apagado" to false
            }
            Emparejados.Papel.Nevera -> {
                val n = EstadoActual.nevera ?: return "el tablero no está corriendo" to false
                if (n.vivoAhora(ahora)) "leyendo · hace ${(ahora - n.leidoMs) / 1000} s" to true
                else (n.detalle ?: if (n.leidoMs == 0L) "todavía no contesta" else "sin lectura reciente") to false
            }
            Emparejados.Papel.BancoArranque -> estadoBanco(EstadoActual.bancos?.arranque, ahora)
            Emparejados.Papel.BancoVivienda -> estadoBanco(EstadoActual.bancos?.vivienda, ahora)
        }
    }

    private fun estadoBanco(b: BancosBateria.Banco?, ahora: Long): Pair<String, Boolean> = when {
        b == null -> "el tablero no está corriendo" to false
        b.vivo(ahora) -> "leyendo · hace ${(ahora - b.leidoMs) / 1000} s" to true
        else -> (b.detalle ?: if (b.leidoMs == 0L) "todavía no contesta" else "sin lectura reciente") to false
    }

    // ---------------------------------------------------- permisos y radio

    private fun adaptador(): BluetoothAdapter? = runCatching {
        (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }.getOrNull()

    private fun permisosQueFaltan(): List<String> {
        if (Build.VERSION.SDK_INT < 23) return emptyList()
        val necesarios = if (Build.VERSION.SDK_INT >= 31)
            listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT")
        else listOf("android.permission.ACCESS_FINE_LOCATION")
        return necesarios.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun pedirPermisos() {
        val faltan = permisosQueFaltan()
        if (faltan.isNotEmpty() && Build.VERSION.SDK_INT >= 23) {
            requestPermissions(faltan.toTypedArray(), REQ_PERMISOS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISOS) return
        val p = papelBuscado ?: return
        if (permisosQueFaltan().isEmpty()) {
            aviso = null
            empezarBarrido(p)
        } else {
            aviso = "Sin ese permiso Android no deja buscar aparatos Bluetooth."
            pintar()
        }
    }

    /** En Android 6 a 11 la busqueda Bluetooth no ve nada con la ubicacion apagada. */
    private fun ubicacionEncendida(): Boolean {
        if (Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT >= 31) return true
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return true
        return runCatching {
            if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else modoUbicacion() != 0
        }.getOrDefault(true)
    }

    @Suppress("DEPRECATION")
    private fun modoUbicacion(): Int =
        Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE, 0)

    @Suppress("DEPRECATION")
    private fun encenderDirecto(a: BluetoothAdapter): Boolean =
        Build.VERSION.SDK_INT < 33 && runCatching { a.enable() }.getOrDefault(false)



    private fun encenderBluetooth(a: BluetoothAdapter) {
        val ok = encenderDirecto(a)
        if (!ok) runCatching { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        // La radio tarda un par de segundos en levantarse.
        ui.postDelayed({ papelBuscado?.let { empezarBarrido(it) } }, 2_500)
        aviso = "Encendiendo Bluetooth…"
        pintar()
    }

    // ------------------------------------------------------------- piezas UI

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    private fun titulo(t: String) = TextView(this).apply {
        text = t
        setTextColor(TINTA)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.12f
        setPadding(0, 0, 0, dp(4))
    }

    private fun subtitulo(t: String) = TextView(this).apply {
        text = t
        setTextColor(ARENA)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(10), 0, dp(8))
    }

    private fun nota(t: String) = TextView(this).apply {
        text = t
        setTextColor(APAGADO)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(6), 0, dp(12))
    }

    private fun avisoVista(t: String) = TextView(this).apply {
        text = t
        setTextColor(TINTA)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setBackgroundColor(TARJETA_AVISO)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            .also { it.bottomMargin = dp(10) }
    }

    private fun separador() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).also {
            it.topMargin = dp(10); it.bottomMargin = dp(10)
        }
        setBackgroundColor(LINEA)
    }

    private fun fila(rotulo: String, detalle: String, ok: Boolean, alTocar: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 44 dp de alto minimo: es lo que se acierta con el dedo.
            minimumHeight = dp(56)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.bottomMargin = dp(8) }
            setBackgroundColor(TARJETA)
            isClickable = true
            setOnClickListener { alTocar() }
            addView(TextView(context).apply {
                text = rotulo
                setTextColor(if (ok) VIVO else TINTA)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(context).apply {
                text = detalle
                setTextColor(APAGADO)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(3), 0, 0)
            })
        }

    private fun botonPequeno(t: String, alTocar: () -> Unit) = TextView(this).apply {
        text = t
        setTextColor(ARENA)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        minimumHeight = dp(46)
        setPadding(dp(12), dp(13), dp(12), dp(13))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            .also { it.bottomMargin = dp(8) }
        setBackgroundColor(TARJETA)
        isClickable = true
        setOnClickListener { alTocar() }
    }

    private companion object {
        const val REQ_PERMISOS = 41

        /** El barrido clasico de Android dura unos 12 s; se le da margen. */
        const val MS_BARRIDO_CLASICO = 20_000L
        const val MS_BARRIDO_BLE = 15_000L

        /** Si en este tiempo no se emparejo ni fallo, se da por fallido. */
        const val MS_EMPAREJAR = 45_000L

        const val MS_REPINTAR = 500L
        const val MS_REFRESCO = 2_000L

        // Misma familia de color que los tableros, sin depender de ellos.
        val FONDO = Color.parseColor("#131715")
        val TARJETA = Color.parseColor("#1A201C")
        val TARJETA_AVISO = Color.parseColor("#2A2A1E")
        val LINEA = Color.parseColor("#2A312B")
        val TINTA = Color.parseColor("#EDE4D3")
        val ARENA = Color.parseColor("#BEB39A")
        val APAGADO = Color.parseColor("#8E968A")
        val VIVO = Color.parseColor("#9CBE7A")
        val AMBAR = Color.parseColor("#D9A55B")
    }
}
