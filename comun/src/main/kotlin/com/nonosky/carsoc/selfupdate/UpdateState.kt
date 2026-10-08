package com.nonosky.carsoc.selfupdate

/**
 * Bitacora corta y en memoria de lo que va pasando con las actualizaciones.
 *
 * Existe porque en este radio `logcat` no es accesible desde fuera de la app
 * (sin root solo se ven los logs del propio UID), asi que sin esto no habria
 * forma de saber en que se atoro una actualizacion remota.
 */
object UpdateState {

    private const val MAX = 60
    private val lines = ArrayDeque<String>()

    @Volatile
    var lastCheckMs: Long = 0
        private set

    @Volatile
    var remoteVersionCode: Int = -1
        private set

    @Synchronized
    fun note(line: String) {
        if (lines.size >= MAX) lines.removeFirst()
        lines.addLast("${System.currentTimeMillis()} $line")
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    /**
     * Ventana en la que se espera un dialogo de instalacion nuestro.
     *
     * El servicio de accesibilidad NO decide por el texto de la pantalla:
     * ese texto lo pone el APK que se esta instalando, asi que un APK ajeno
     * etiquetado con el nombre de la app pasaria cualquier filtro por
     * nombre. En vez de eso, el instalador arma esta ventana justo antes de pedir la
     * confirmacion, y fuera de ella el servicio no toca nada.
     */
    @Volatile
    private var armadoHastaMs: Long = 0

    @Volatile
    var versionArmada: Int = -1
        private set

    fun armar(versionCode: Int, duracionMs: Long = 120_000) {
        versionArmada = versionCode
        armadoHastaMs = System.currentTimeMillis() + duracionMs
        note("Instalacion armada para v$versionCode")
    }

    /** Duracion por defecto de la ventana de confirmacion. */
    const val DURACION_ARMADO_MS = 120_000L

    fun desarmar() {
        armadoHastaMs = 0
        versionArmada = -1
    }

    /** true solo si nosotros pedimos una instalacion hace poco. */
    fun estaArmado(): Boolean = System.currentTimeMillis() < armadoHastaMs

    @Synchronized
    fun recordCheck(remote: Int) {
        lastCheckMs = System.currentTimeMillis()
        remoteVersionCode = remote
    }
}
