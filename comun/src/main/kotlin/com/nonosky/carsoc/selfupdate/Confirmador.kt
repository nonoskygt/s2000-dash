package com.nonosky.carsoc.selfupdate

/**
 * El APK compañero que pulsa "Instalar" y teclea el PIN del adaptador por el
 * dueño, SI el carro lo usa.
 *
 * No todos lo usan, y por eso no vive escrito aqui: su paquete y sus acciones
 * los pone la app del carro que lo tiene, al arrancar ([delCarro]). Una app
 * que no lo pone ni lo busca ni le habla, y su APK no lleva nada de el.
 *
 * Las acciones son el prefijo del compañero mas un nombre fijo, que es lo que
 * el compañero escucha.
 */
data class Confirmador(
    /** Paquete del APK compañero. */
    val paquete: String,
    /** Prefijo de las acciones que escucha y de las que emite. */
    val prefijo: String,
) {
    val armarInstalacion: String get() = "$prefijo.ARMAR_INSTALACION"
    val armarPin: String get() = "$prefijo.ARMAR_PIN"
    val mando: String get() = "$prefijo.MANDO"

    companion object {
        /** El de este carro, o null si no usa ninguno. Lo pone su Application. */
        @Volatile
        var delCarro: Confirmador? = null
    }
}
