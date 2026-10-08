package com.nonosky.carsoc.hci

/**
 * El cable: lo unico que una bomba necesita saber hacer con el dongle USB.
 *
 * ### Por que existe esta interfaz
 *
 * No es arquitectura por gusto. Es la costura que hace **comprobable** el
 * fallo que cuelga el radio.
 *
 * El aparato de verdad ([HciUsb]) habla con `UsbDeviceConnection`, que es
 * codigo NATIVO de Android: no existe en la JVM, no existe en el emulador
 * (que no tiene host USB), y no se puede instrumentar. O sea que la capa
 * donde vive el bug —un `bulkTransfer` corriendo sobre un descriptor que
 * otro hilo acaba de cerrar— es justo la unica capa que ninguna prueba podia
 * tocar. Se probaba todo menos lo que rompia.
 *
 * Con esta interfaz de por medio, una prueba puede meter un cable FALSO que
 * tarda a proposito en cada transferencia y que **grita** si alguien
 * transfiere despues de `cerrar()`. El fallo deja de necesitar un carro, un
 * dongle y cuatro reinicios para manifestarse: aparece en la JVM en
 * milisegundos.
 *
 * Los tamanos y plazos por defecto viven aqui y no en las implementaciones
 * porque en Kotlin una funcion que sobrescribe NO puede declarar valores por
 * defecto: los hereda de esta declaracion.
 */
interface CanalUsbHci {

    /** Hay conexion reclamada. Si es false, cualquier transferencia falla. */
    val abierto: Boolean

    /** Hay camino de datos (los dos BULK), no solo comandos y eventos. */
    val tieneAcl: Boolean

    /** `maxPacketSize` del BULK de salida. */
    val tamBloqueSalida: Int

    /** `maxPacketSize` del BULK de entrada. */
    val tamBloqueEntrada: Int

    /** Manda un comando HCI por el endpoint de control. Negativo si fallo. */
    fun mandarComando(opcode: Int, parametros: ByteArray = ByteArray(0)): Int

    /** Escribe un paquete ACL ya armado. Negativo si fallo. */
    fun escribirAclCrudo(paquete: ByteArray, timeoutMs: Int = PLAZO_MS): Int

    /** Lee del BULK de entrada. <=0 si no llego nada. */
    fun leerAclCrudo(buffer: ByteArray, timeoutMs: Int = PLAZO_MS): Int

    /** Lee un evento HCI completo, o null si no llego nada. */
    fun leerEvento(timeoutMs: Int = PLAZO_MS): ByteArray?

    /**
     * Suelta la interfaz y cierra el descriptor.
     *
     * **Despues de esto ninguna transferencia puede seguir en vuelo.** Quien
     * cierre tiene que haber esperado (con `join`) a los hilos que transfieren;
     * `interrupt()` no basta porque no aborta una llamada nativa.
     */
    fun cerrar()

    companion object {
        /** Mismo plazo que usaba [HciUsb] antes de existir esta interfaz. */
        const val PLAZO_MS = 1_500
    }
}
