package com.nonosky.carsoc.tpms

/**
 * Estado del ENLACE con el receptor — no de las llantas.
 *
 * Va aparte del estado de las ruedas ([EstadoTpms]) y aparte del
 * `ConnectionState` del OBD, y las tres separaciones son deliberadas: son tres
 * aparatos distintos que fallan por motivos distintos y en momentos distintos.
 * Meterlos en un solo "estado de conexion" es justo el defecto que este
 * trabajo viene a corregir — hoy, sin OBD, no hay tablero.
 *
 * Distinguir estos casos importa porque cada uno se arregla de otra manera:
 * `SinReceptor` es un cable; `SinPermiso` es Android; `Fallo` es el aparato.
 * Un solo "SIN TPMS" para los tres deja al usuario sin saber que hacer, que
 * es la misma leccion que ya dejo `ConnectionState.SinAdaptador`.
 */
enum class EnlaceTpms {
    /** No hay ningun USB-serial colgado del puerto. */
    SinReceptor,

    /** Esta el aparato, pero Android no nos deja abrirlo. */
    SinPermiso,

    /** Abriendo y fijando la velocidad. */
    Abriendo,

    /**
     * Abierto y leyendo.
     *
     * Ojo: que no lleguen tramas estando aqui **no** es un fallo de enlace.
     * Los sensores callan durante decenas de segundos con el carro parado.
     * Eso lo dice la frescura de cada rueda, no este estado.
     */
    Leyendo,

    /** Se abrio y se cayo. El detalle dice por que. */
    Fallo,
}

/**
 * Que tan de fiar es la lectura de una rueda.
 *
 * Tres niveles y no un booleano. [TpmsDecoder] ya trae
 * `LecturaRueda.rancia()` con el techo prudente de 15 minutos de
 * `Escalas.RANCIA_TRAS_MS`, y ese techo esta bien para decidir *cuando dejar
 * de mostrar el numero*. Pero como unico umbral deja un hueco peligroso: una
 * rueda que dejo de reportar hace catorce minutos se sigue pintando nitida,
 * como si el dato fuera de ahora.
 *
 * [Tibia] cubre ese hueco: a los tres minutos el numero se apaga —sigue
 * visible, porque un hueco a media curva es peor que un dato viejo señalado
 * como viejo— y a los quince desaparece del todo, porque a esa altura ya no
 * es un dato viejo sino un sensor que no esta.
 */
enum class Frescura {
    /** Recien recibida. Se pinta normal. */
    Fresca,

    /** Vieja pero creible. Se pinta apagada. */
    Tibia,

    /** Tan vieja que no se muestra el numero, o nunca llego. */
    SinReportar,
}

/**
 * Los numeros del ENLACE, todos en un sitio — misma regla que
 * `EngineConstants` y que [Escalas]: si un valor resulta estar mal en el
 * carro, se corrige una linea y nada mas.
 *
 * Las escalas de presion y temperatura NO viven aqui: viven en [Escalas], que
 * es su sitio. Aqui solo esta lo que tiene que ver con el cable y el hilo.
 */
object TpmsEnlaceConstantes {

    /**
     * MEDIDO en vivo: a 9600 los bytes bailaban entre capturas (bits sueltos
     * = velocidad mala), a 19200 salen identicos, y de 38400 en adelante es
     * basura.
     */
    const val BAUDIOS = 19200

    /**
     * A partir de aqui la lectura se pinta apagada. Ver [Frescura.Tibia].
     *
     * **No** son los 3 s de `Carro.motor.staleAfterMs`, y meter ese
     * numero aqui seria un error grave: un sensor TPMS transmite cada ~15-60 s
     * con el carro parado. Con el umbral del OBD las cuatro llantas estarian
     * permanentemente en gris, y el conductor aprenderia a ignorar el gris —
     * que es exactamente lo que no puede pasar el dia que si importe.
     */
    const val TIBIA_TRAS_MS = 180_000L

    /**
     * Silencio TOTAL de bytes que se toma por enlace caido.
     *
     * Aqui esta la trampa que se lleva por delante a cualquiera que escriba
     * esto rapido: `bulkTransfer` devuelve **negativo cuando vence su timeout
     * sin que haya pasado nada malo**, y con el carro parado ese es el caso
     * normal casi todo el tiempo. Un lector que reabriera el aparato con cada
     * retorno negativo estaria reabriendo el USB cinco veces por segundo para
     * siempre, y ademas perderia tramas en cada reapertura.
     *
     * Lo que distingue "no habia nada que transmitir" de "el enlace murio" es
     * el tiempo sin recibir NI UN BYTE, no el resultado de una lectura suelta.
     *
     * 90 s es holgado a proposito: el receptor parece reenviar su tabla en
     * bucle (por eso dos capturas separadas salieron identicas), pero ese
     * periodo **no esta medido** — ver `COMO_CONFIRMAR` punto 6. Mientras no
     * se mida, mas vale esperar de mas que reabrir de menos.
     */
    const val SIN_BYTES_MS = 90_000L

    /** Cada cuanto se comprueba que el aparato sigue colgado del USB. */
    const val REVISAR_PRESENCIA_MS = 2_000L

    /**
     * Timeout de cada lectura BULK.
     *
     * NO retrasa la alerta de pinchazo: `bulkTransfer` vuelve en cuanto llegan
     * bytes, el plazo solo cuenta cuando NO llega nada. Con 200 ms el hilo
     * despertaba cinco veces por segundo para no leer nada; con 1 s, una.
     */
    const val LECTURA_TIMEOUT_MS = 1_000

    /** Retroceso exponencial de reconexion: 1 s, 2 s, 4 s... */
    const val RESPALDO_BASE_MS = 1_000L

    /**
     * Techo del retroceso: 30 s, no los 10 s del OBD.
     *
     * El OBD baja el techo porque la aguja tiene que volver rapido. Aqui una
     * trama llega cada medio minuto de todos modos, asi que esperar 30 s
     * cuesta como mucho una trama; y a cambio no se despierta el USB cada
     * diez segundos toda la noche con el carro apagado y el radio encendido.
     */
    const val RESPALDO_MAX_MS = 30_000L

    /** Cuantos bloques crudos se guardan para `/tpms`. */
    const val ANILLO_CRUDO = 60

    /** Frescura de una lectura, calculada AL MIRARLA. Nunca almacenada. */
    fun frescuraDe(medidaMs: Long, ahoraMs: Long): Frescura = when {
        medidaMs == 0L -> Frescura.SinReportar
        ahoraMs - medidaMs > Escalas.RANCIA_TRAS_MS -> Frescura.SinReportar
        ahoraMs - medidaMs > TIBIA_TRAS_MS -> Frescura.Tibia
        else -> Frescura.Fresca
    }
}

/** Frescura de esta lectura. Ver [TpmsEnlaceConstantes.frescuraDe]. */
fun LecturaRueda.frescura(ahoraMs: Long): Frescura =
    TpmsEnlaceConstantes.frescuraDe(medidaMs, ahoraMs)

/**
 * Edad en milisegundos, o -1 si nunca reporto.
 *
 * Se expone para `/tpms`: al depurar en remoto, "hace 4 min" dice mucho mas
 * que "rancia".
 */
fun LecturaRueda.edadMs(ahoraMs: Long): Long =
    if (medidaMs == 0L) -1 else ahoraMs - medidaMs
