package com.nonosky.carsoc

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.io.File

/**
 * Vigila la temperatura del SoC y hace que el tablero se aparte cuando quema.
 *
 * Existe porque el head unit se apago **dos veces** por calor. Y no fue mala
 * suerte: este rk3326 idlea a 59 grados sin hacer nada —medido por SSH con la
 * app cerrada— asi que el margen es estrecho desde el principio. Encima se le
 * puso un tablero repintando, sondeo OBD, conexiones BLE, lectura USB, un
 * servidor HTTP y un actualizador. Cada pieza parecia barata por separado.
 *
 * La leccion es que en este aparato **no basta con que cada parte sea
 * razonable**: hay que medir el total y ceder cuando sube. Un tablero que
 * apaga el radio del carro no es un tablero, es una averia.
 *
 * Por eso esto no aconseja, MANDA: los consumidores preguntan por [nivel] y
 * se apagan solos. Y el TPMS nunca se apaga — cuesta casi nada y es lo unico
 * que avisa de algo que puede reventar en carretera.
 *
 * ## Por que hay tres fuentes y no una
 *
 * En el radio nuevo las tres rutas que se probaban al principio **no
 * existen**, y el regulador se quedaba ciego sin decirlo: [medir] salia sin
 * tocar nada, [nivel] se quedaba en `Fresco` para siempre y [permiteObd]
 * contestaba que si a todo. O sea que justo en el aparato donde no se podia
 * medir, tampoco se protegia. Un termometro que falla en abierto es peor que
 * no tenerlo, porque aparenta que alguien vigila.
 *
 * Ahora se pregunta por tres caminos independientes y se declara [ciego]
 * cuando ninguno contesta.
 */
object Termometro {

    private const val TAG = "Termometro"

    /** Contexto de aplicacion, para las fuentes que pasan por el sistema. */
    @Volatile
    private var ctx: Context? = null

    /** Lo llama el servicio al arrancar. Sin esto solo queda sysfs. */
    fun iniciar(context: Context) {
        ctx = context.applicationContext
    }

    /**
     * Zonas termicas a probar **por ruta directa**, sin listar el directorio.
     *
     * Listar `/sys/class/thermal` es lo primero que SELinux le niega a un
     * `untrusted_app`, y sin embargo leer el archivo concreto muchas veces si
     * se permite. Enumerar a ciegas del 0 al 15 cuesta unas pocas
     * comprobaciones por vuelta y encuentra la zona alli donde listar
     * fracasa.
     */
    private val BASES = listOf(
        "/sys/class/thermal/thermal_zone%d/temp",
        "/sys/devices/virtual/thermal/thermal_zone%d/temp",
    )

    private const val ZONAS = 16

    @Volatile
    var gradosC: Int = -1
        private set

    @Volatile
    var nivel: Nivel = Nivel.Fresco
        private set

    @Volatile
    var vecesQueBajo: Int = 0
        private set

    /**
     * De donde salio la ultima lectura buena. Se publica para poder
     * diagnosticarlo en frio desde el puente, sin shell en el radio.
     */
    @Volatile
    var fuente: String = "ninguna"
        private set

    /**
     * Cierto cuando NINGUNA fuente contesto.
     *
     * Es el estado peligroso y por eso tiene nombre propio: se pinta en
     * pantalla, porque si la maquina no puede vigilar el calor, entonces el
     * unico que puede es el dueño.
     */
    @Volatile
    var ciego: Boolean = true
        private set

    enum class Nivel {
        /** Todo corriendo. */
        Fresco,

        /** Se espacia el sondeo y se baja el repintado. */
        Tibio,

        /** Solo el TPMS y el puente. Se sueltan OBD y bateria. */
        Caliente,
    }

    /**
     * Relee la temperatura. Devuelve el nivel resultante.
     *
     * Con histeresis: se sube de nivel antes de lo que se baja. Sin ella, un
     * aparato oscilando en el umbral encenderia y apagaria el OBD cada pocos
     * segundos, que gasta mas que dejarlo quieto en cualquiera de los dos
     * estados.
     */
    fun medir(): Nivel {
        val grados = leerGrados()
        if (grados != null) {
            ciego = false
            gradosC = grados
            aplicar(nivelPara(grados, nivel))
            return nivel
        }

        // Sin numero, pero el sistema puede tener una opinion igualmente.
        val porPlataforma = nivelPorPlataforma()
        if (porPlataforma != null) {
            ciego = false
            gradosC = -1
            fuente = "PowerManager (sin grados)"
            aplicar(porPlataforma)
            return nivel
        }

        // Ciego de verdad. No se toca el nivel —no hay con que decidir— pero
        // queda declarado, y quien dibuja se encarga de gritarlo.
        ciego = true
        gradosC = -1
        fuente = "ninguna"
        return nivel
    }

    private fun aplicar(nuevo: Nivel) {
        if (nuevo == nivel) return
        if (nuevo.ordinal > nivel.ordinal) vecesQueBajo++
        Log.i(TAG, "${if (gradosC > 0) "$gradosC C" else fuente}: $nivel -> $nuevo")
        nivel = nuevo
    }

    /** ¿Puede correr el sondeo del motor? Es lo que mas cuesta. */
    fun permiteObd(): Boolean = nivel == Nivel.Fresco

    /** ¿Puede el vigilante conectarse a la bateria? */
    fun permiteBateria(): Boolean = nivel != Nivel.Caliente

    /**
     * ¿Puede correr el receptor de GPS?
     *
     * Le faltaba la palanca. El OBD y la bateria se apagaban con el calor y el
     * repintado bajaba a un cuadro por segundo, pero el GPS seguia pidiendo
     * posiciones a cualquier temperatura, de arranque a muerte del servicio.
     *
     * Y es caro: medido en el radio a 85 C, `android.hardware.gnss@2.0-service`
     * estaba al 8,8% de CPU —el segundo consumidor de la maquina— cazando
     * satelites con el carro APAGADO, o sea calentando el radio para acumular
     * cero kilometros.
     */
    fun permiteGps(): Boolean = nivel != Nivel.Caliente

    /**
     * Milisegundos entre cuadros segun lo caliente que este.
     *
     * Sin termometro se repinta a 2 cuadros por segundo y no a 5. Es la
     * unica concesion que se hace al estar ciego: no apaga el OBD —eso
     * dejaria el tablero sin su razon de ser justo en el radio donde no
     * sabemos medir— pero si recorta a la mitad el consumo que SI conocemos.
     * Un tablero de vigilancia se lee igual de bien a 2 fps.
     */
    fun msEntreCuadros(): Long = when {
        ciego -> 500L
        nivel == Nivel.Tibio -> 500L
        nivel == Nivel.Caliente -> 1_000L
        else -> 200L
    }

    // --- Las tres fuentes ---------------------------------------------------

    /** Grados por sysfs primero; si no, por la bateria. Null si ninguna. */
    private fun leerGrados(): Int? {
        val porSysfs = leerSysfs()
        if (porSysfs != null) return porSysfs

        val porBateria = leerBateria()
        if (porBateria != null) {
            fuente = "bateria (no es el SoC)"
            return porBateria
        }
        return null
    }

    /**
     * La zona mas caliente de las que se dejen leer.
     *
     * Se toma el MAXIMO y no la primera: en un SoC hay varias zonas —CPU,
     * GPU, DDR— y la que decide si el aparato se corta es la que va arriba,
     * no la que salga antes en la lista.
     */
    private fun leerSysfs(): Int? {
        var mejor: Int? = null
        var deDonde = ""
        for (base in BASES) {
            for (i in 0 until ZONAS) {
                val ruta = String.format(base, i)
                val grados = runCatching {
                    val f = File(ruta)
                    if (!f.canRead()) null else interpretarCrudo(f.readText())
                }.getOrNull() ?: continue
                if (mejor == null || grados > mejor!!) {
                    mejor = grados
                    deDonde = ruta
                }
            }
        }
        if (mejor != null) fuente = deDonde
        return mejor
    }

    /**
     * Temperatura de la bateria, que en un head unit no hay.
     *
     * Se usa igual como ultimo recurso porque en varias de estas ROMs el
     * driver reporta ahi la del propio aparato. Se etiqueta como lo que es
     * —no es el SoC— para que nadie afine umbrales creyendo otra cosa.
     */
    private fun leerBateria(): Int? {
        val c = ctx ?: return null
        val intent: Intent? = runCatching {
            c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val decimas = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        if (decimas <= 0) return null
        val grados = decimas / 10
        return if (esPlausible(grados)) grados else null
    }

    /**
     * Lo que opina el propio Android. No da grados, pero no pide permisos y
     * sobrevive a que sysfs este cerrado a cal y canto.
     */
    private fun nivelPorPlataforma(): Nivel? {
        if (Build.VERSION.SDK_INT < 29) return null
        val pm = runCatching {
            ctx?.getSystemService(Context.POWER_SERVICE) as? PowerManager
        }.getOrNull() ?: return null
        val estado = runCatching { pm.currentThermalStatus }.getOrNull() ?: return null
        return when {
            estado >= PowerManager.THERMAL_STATUS_SEVERE -> Nivel.Caliente
            estado >= PowerManager.THERMAL_STATUS_MODERATE -> Nivel.Tibio
            else -> Nivel.Fresco
        }
    }

    // --- Piezas puras, para poder probarlas ---------------------------------

    /**
     * Convierte lo que hay en el archivo a grados enteros.
     *
     * Unas ROMs dan milesimas de grado y otras grados enteros, y alguna mete
     * un decimal. Se descarta lo que no sea plausible en vez de creerselo:
     * una zona que reporta 0 o 4.000 grados apagaria el tablero por nada.
     */
    fun interpretarCrudo(texto: String): Int? {
        val limpio = texto.trim()
        if (limpio.isEmpty()) return null
        val numero = limpio.toIntOrNull()
            ?: limpio.toDoubleOrNull()?.toInt()
            ?: return null
        val grados = if (numero > 1000) numero / 1000 else numero
        return if (esPlausible(grados)) grados else null
    }

    /**
     * Un SoC encendido nunca esta a cero.
     *
     * El limite de abajo no es 0 ni un numero negativo a proposito: una zona
     * termica que existe pero no esta implementada reporta **0**, y con un
     * rango generoso esa cifra pasaria por lectura buena. El tablero diria
     * "0 grados, fresco" y volveria a no proteger nada — el mismo fallo en
     * abierto que se esta arreglando, entrando por otra puerta.
     *
     * El precio de cortar en 5 es una falsa alarma si el carro amanece
     * helado: se veria "SIN TERMOMETRO" hasta que el aparato se temple. Ese
     * error es barato y visible. Creerle a un cero es caro e invisible.
     */
    fun esPlausible(grados: Int): Boolean = grados in 5..150

    /**
     * La histeresis, aparte para poder probarla sin tocar archivos.
     *
     * Se sube de nivel antes de lo que se baja: en la franja intermedia se
     * conserva el nivel actual, y eso es lo que evita el parpadeo.
     */
    fun nivelPara(t: Int, actual: Nivel): Nivel = when {
        t >= UMBRAL_CALIENTE -> Nivel.Caliente
        t >= UMBRAL_TIBIO -> Nivel.Tibio
        t <= UMBRAL_VUELTA_FRESCO -> Nivel.Fresco
        else -> if (actual == Nivel.Caliente) Nivel.Tibio else actual
    }

    fun diagnostico(): List<String> = listOf(
        "temperatura del SoC: ${if (gradosC > 0) "$gradosC C" else "no legible"}",
        "fuente: $fuente",
        "ciego: ${if (ciego) "SI — el guardian no esta protegiendo" else "no"}",
        "nivel: $nivel",
        "ms entre cuadros: ${msEntreCuadros()}",
        "veces que hubo que ceder: $vecesQueBajo",
        "umbrales: tibio $UMBRAL_TIBIO C, caliente $UMBRAL_CALIENTE C, " +
            "vuelta a fresco $UMBRAL_VUELTA_FRESCO C",
    )

    /**
     * Todo lo que se pudo leer, zona por zona. Solo para el puente.
     *
     * Existe porque "no legible" no dice si el directorio no esta, si esta
     * pero SELinux lo niega, o si contesta una cifra absurda. Cada caso se
     * arregla distinto, y sin shell en el radio esta es la unica forma de
     * distinguirlos.
     */
    fun zonas(): List<String> {
        val salida = mutableListOf<String>()
        for (base in BASES) {
            for (i in 0 until ZONAS) {
                val ruta = String.format(base, i)
                val f = File(ruta)
                val existe = runCatching { f.exists() }.getOrDefault(false)
                val legible = runCatching { f.canRead() }.getOrDefault(false)
                if (!existe && !legible) continue
                val crudo = runCatching { f.readText().trim() }.getOrNull()
                salida += "$ruta  existe=$existe legible=$legible " +
                    "crudo=${crudo ?: "-"} -> ${crudo?.let { interpretarCrudo(it) } ?: "-"}"
            }
        }
        if (salida.isEmpty()) salida += "ninguna zona termica visible en sysfs"

        val c = ctx
        salida += if (c == null) {
            "PowerManager: sin contexto (el servicio no llamo a iniciar)"
        } else if (Build.VERSION.SDK_INT < 29) {
            "PowerManager: API ${Build.VERSION.SDK_INT}, getCurrentThermalStatus pide 29"
        } else {
            val pm = runCatching { c.getSystemService(Context.POWER_SERVICE) as? PowerManager }
                .getOrNull()
            val estado = runCatching { pm?.currentThermalStatus }.getOrNull()
            "PowerManager: estado termico = ${estado ?: "no contesta"} " +
                "(0=ninguno 1=leve 2=moderado 3=severo 4=critico)"
        }
        salida += "bateria: ${leerBateria()?.let { "$it C" } ?: "no reporta"}"
        salida += "API del radio: ${Build.VERSION.SDK_INT} (Android ${Build.VERSION.RELEASE})"
        return salida
    }

    /**
     * Frecuencia y gobernador de cada nucleo. Solo para el puente.
     *
     * Existe porque la pantalla de fabrica del radio nuevo mostro los cuatro
     * nucleos clavados a 1512 MHz —el tope del rk3326— con el tablero ya
     * rendido a un cuadro por segundo y el OBD apagado. Si el SoC sigue a 85
     * grados en esas condiciones, el calor no lo pone la app, y hay que poder
     * demostrarlo con el gobernador delante en vez de discutirlo.
     */
    fun cpu(): List<String> {
        val salida = mutableListOf<String>()
        for (i in 0 until 8) {
            val base = "/sys/devices/system/cpu/cpu$i/cpufreq"
            val actual = leerTexto("$base/scaling_cur_freq") ?: continue
            val gob = leerTexto("$base/scaling_governor") ?: "?"
            val tope = leerTexto("$base/cpuinfo_max_freq") ?: "?"
            val minimo = leerTexto("$base/scaling_min_freq") ?: "?"
            salida += "cpu$i: ${kHzAMHz(actual)} MHz  (min ${kHzAMHz(minimo)}, " +
                "tope ${kHzAMHz(tope)})  gobernador=$gob"
        }
        if (salida.isEmpty()) salida += "cpufreq no legible para esta app"

        leerTexto("/sys/class/devfreq/ff400000.gpu/cur_freq")?.let {
            salida += "gpu: ${kHzAMHz(it)} MHz (crudo=$it)"
        }
        leerTexto("/proc/loadavg")?.let { salida += "carga: $it" }
        return salida
    }

    private fun leerTexto(ruta: String): String? = runCatching {
        val f = File(ruta)
        if (!f.canRead()) null else f.readText().trim().ifEmpty { null }
    }.getOrNull()

    /** cpufreq habla en kHz; el hercio suelto de la GPU se deja como esta. */
    private fun kHzAMHz(crudo: String): String {
        val n = crudo.toLongOrNull() ?: return crudo
        return when {
            n > 10_000_000L -> (n / 1_000_000L).toString()
            n > 10_000L -> (n / 1_000L).toString()
            else -> n.toString()
        }
    }

    /**
     * Umbrales, elegidos sobre lo MEDIDO en este radio y no sobre una tabla.
     *
     * En reposo y sin app marca 59. Con el tablero entero corriendo a 5 fps
     * marco 64. Se apago por encima de eso. Asi que 70 es donde hay que
     * empezar a ceder y 78 donde hay que soltarlo casi todo — con margen para
     * reaccionar antes de que la proteccion del fabricante corte la corriente,
     * que es lo que deja al conductor sin radio.
     */
    private const val UMBRAL_TIBIO = 70
    private const val UMBRAL_CALIENTE = 78
    private const val UMBRAL_VUELTA_FRESCO = 66
}
