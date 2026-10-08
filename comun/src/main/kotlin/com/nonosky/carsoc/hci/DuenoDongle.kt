package com.nonosky.carsoc.hci

import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Dueño unico del dongle Bluetooth USB.
 *
 * Existe porque hay **cuatro** cosas que lo quieren y solo hay uno: el
 * vigilante de la bateria (barre cada 30 s), la conexion GATT al BMS, el
 * enlace clasico del adaptador OBD, y las rutas de diagnostico por HTTP.
 *
 * Dos de ellas abriendo el mismo aparato a la vez no da un error limpio:
 * `claimInterface` le quita la interfaz al otro, los dos hilos leen del mismo
 * endpoint y se roban paquetes, y lo que se ve es "el BMS dejo de contestar"
 * o "el emparejamiento se queda a medias" — sintomas que apuntan a cualquier
 * sitio menos al verdadero. Peor aun: una excepcion en el hilo perdedor se
 * lleva el proceso entero, y con el el tablero, el puente y el actualizador.
 * Eso ya paso hoy y hubo que arrancar el carro para recuperarlo.
 *
 * La regla es simple y no tiene excepciones: **nadie abre el dongle sin pasar
 * por aqui**.
 *
 * Reentrante a proposito: el vigilante barre y, sin soltar, se conecta al BMS
 * en el mismo hilo. Con un candado no reentrante eso seria un abrazo mortal
 * consigo mismo.
 */
object DuenoDongle {

    private const val TAG = "DuenoDongle"

    private val candado = ReentrantLock(true)

    @Volatile
    private var quienLoTiene: String? = null

    @Volatile
    private var desdeMs: Long = 0

    /** Cuantas veces alguien se quedo sin el dongle por estar ocupado. */
    @Volatile
    var rechazos: Long = 0
        private set

    /** Quien lo tiene ahora, o null. Para la ruta de diagnostico. */
    fun ocupadoPor(): String? = quienLoTiene

    fun segundosOcupado(): Long =
        if (desdeMs == 0L) 0 else (System.currentTimeMillis() - desdeMs) / 1000

    /**
     * Ejecuta [bloque] con el dongle en exclusiva.
     *
     * Devuelve null si no se pudo tomar en [esperaMs]. Devolver null y no
     * lanzar es deliberado: quien no consigue el dongle debe seguir vivo y
     * volver a intentarlo luego, no morirse. Un vigilante que se cae porque
     * el OBD estaba usando la radio es peor que un vigilante que espera.
     *
     * [bloque] va envuelto: una excepcion dentro no puede escapar al hilo que
     * llamo, porque muchos de esos hilos son de fondo y en Android una
     * excepcion suelta en un hilo mata el proceso.
     */
    fun <T> usar(quien: String, esperaMs: Long = 2_000, bloque: () -> T): T? {
        val tomado = runCatching {
            candado.tryLock(esperaMs, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)

        if (!tomado) {
            rechazos++
            Log.i(TAG, "'$quien' no pudo tomar el dongle; lo tiene '$quienLoTiene'")
            return null
        }

        val anterior = quienLoTiene
        if (anterior == null) {
            quienLoTiene = quien
            desdeMs = System.currentTimeMillis()
        }
        return try {
            runCatching { bloque() }
                .onFailure { Log.w(TAG, "'$quien' fallo con el dongle: ${it.message}") }
                .getOrNull()
        } finally {
            if (anterior == null) {
                quienLoTiene = null
                desdeMs = 0
            }
            runCatching { candado.unlock() }
        }
    }

    /** ¿Esta libre ahora mismo? Orientativo: puede cambiar al instante. */
    fun libre(): Boolean = !candado.isLocked

    fun diagnostico(): List<String> = listOf(
        "dongle: " + (quienLoTiene?.let { "ocupado por '$it' desde ${segundosOcupado()} s" }
            ?: "libre"),
        "esperas en cola: ${candado.queueLength}",
        "veces que alguien se quedo sin el: $rechazos",
    )
}
