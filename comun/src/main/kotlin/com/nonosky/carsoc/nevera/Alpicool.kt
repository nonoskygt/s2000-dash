package com.nonosky.carsoc.nevera

/**
 * El protocolo de la refrigeradora Alpicool, en funciones puras.
 *
 * Sin I/O, sin estado, sin Android: aqui vive el grueso de lo que se puede
 * equivocar, y es lo unico del enlace que se puede probar sin la nevera
 * delante. Mismo reparto que `PidDecoder` frente a `Elm327Session`.
 *
 * ORIGEN DE ESTO. Cuatro implementaciones independientes coinciden byte a
 * byte (klightspeed/BrassMonkeyFridgeMonitor, johnelliott/alpicoold,
 * Gruni22/alpicool_ha_ble y jakub-hajek/alpicool-esp32-mqtt), y el checksum
 * se verifico aritmeticamente contra capturas reales publicadas. Que LA
 * NEVERA DE ESTE CARRO anuncie el servicio 0x1234 en el barrido —medido en
 * el radio, `ED:67:39:96:50:9B  A1-4XXXXXXXXXXX  uuids=00001234`— es lo que
 * confirma que le aplica esta familia y no otra.
 *
 * Las marcas Alpicool, BrassMonkey, Iceco, BougeRV y Setpower comparten el
 * mismo modulo BLE, que se anuncia como `WT-0001` o con prefijo `A1-`,
 * `AK1-`, `AK2-` o `AK3-`.
 */
object Alpicool {

    /** Cabecera de toda trama, en los dos sentidos. */
    const val CAB_A = 0xFE
    const val CAB_B = 0xFE

    const val CMD_VINCULAR = 0x00
    const val CMD_CONSULTAR = 0x01
    const val CMD_AJUSTES = 0x02
    const val CMD_CONSIGNA_IZQ = 0x05

    /**
     * Checksum: SUMA de 16 bits, no un CRC.
     *
     * Se suman TODOS los bytes desde la cabecera hasta el ultimo de datos —o
     * sea, todo lo anterior al propio checksum—, se enmascara con 0xFFFF y se
     * escribe en big-endian. `alpicoold` llama CRC() a esta funcion, lo cual
     * despista: no lo es.
     */
    fun checksum(bytes: ByteArray, hasta: Int = bytes.size): Int {
        var s = 0
        for (i in 0 until hasta) s += bytes[i].toInt() and 0xFF
        return s and 0xFFFF
    }

    /**
     * Arma una trama: `FE FE <largo> <cmd> <datos...> <ck_hi> <ck_lo>`.
     *
     * El largo cuenta lo que sigue AL PROPIO largo: 1 del comando, N de
     * datos y 2 del checksum.
     *
     * ⚠️ RAREZA VERIFICADA. En la captura de la app fijando consigna, la
     * trama es `fe fe 03 05 ec 02 f1`: el largo dice 0x03 pero el checksum
     * esta calculado como si fuera 0x04, que ademas es el correcto por la
     * formula. O sea que el firmware de fabrica emite un largo incoherente
     * con su propio checksum. Aqui se emite la version COHERENTE (0x04),
     * que es lo que hacen las dos implementaciones modernas y funciona.
     */
    fun trama(cmd: Int, datos: ByteArray = ByteArray(0)): ByteArray {
        val largo = 1 + datos.size + 2
        val out = ByteArray(3 + largo)
        out[0] = CAB_A.toByte()
        out[1] = CAB_B.toByte()
        out[2] = largo.toByte()
        out[3] = cmd.toByte()
        datos.copyInto(out, 4)
        val ck = checksum(out, out.size - 2)
        out[out.size - 2] = ((ck shr 8) and 0xFF).toByte()
        out[out.size - 1] = (ck and 0xFF).toByte()
        return out
    }

    /** `fe fe 03 01 02 00` — pedir estado. */
    fun consulta(): ByteArray = trama(CMD_CONSULTAR)

    /**
     * Fijar la consigna del compartimento unico.
     *
     * ⚠️ El valor va en LA UNIDAD QUE TENGA PUESTA LA NEVERA, no siempre en
     * grados Celsius. Hay que leer primero [Estado.unidadCelsius] del ultimo
     * estado; escribir a ciegas en un aparato configurado en Fahrenheit pone
     * el compresor donde nadie queria.
     */
    fun fijarConsigna(valor: Int): ByteArray =
        trama(CMD_CONSIGNA_IZQ, byteArrayOf(valor.toByte()))

    /**
     * Comando de ajustes (0x02), a partir del estado actual.
     *
     * ⚠️ LEER-MODIFICAR-ESCRIBIR. Se reenvian los 14 parametros del estado
     * cambiando solo el pedido; mandar el comando con ceros apagaria la
     * nevera, le pondria la consigna a 0 y le borraria el corte por bajo
     * voltaje de una sola vez.
     *
     * Los 14 bytes son: bloqueo, encendida, modo, corte de bateria, consigna,
     * maxima, minima, histeresis, retardo, unidad y cuatro correcciones. Los
     * sensores NO van: no se escriben.
     *
     * La trama sale de 20 bytes justos, que es lo que cabe en un ATT de MTU
     * 23 — no hay que partirla.
     */
    fun ajustes(
        estado: Estado,
        encendida: Boolean? = null,
        modoEco: Boolean? = null,
        consigna: Int? = null,
    ): ByteArray? {
        val p = estado.crudo
        if (p.size < 14) return null
        val datos = p.copyOfRange(0, 14)
        encendida?.let { datos[0x01] = (if (it) 1 else 0).toByte() }
        modoEco?.let { datos[0x02] = (if (it) 1 else 0).toByte() }
        consigna?.let { datos[0x04] = it.toByte() }
        return trama(CMD_AJUSTES, datos)
    }

    /**
     * Separa las tramas de un flujo de notificaciones.
     *
     * ⚠️ NO se puede interpretar notificacion a notificacion. Con el MTU por
     * defecto caben 20 bytes utiles, asi que las tramas llegan PARTIDAS; y al
     * mandar un SET la nevera contesta con DOS tramas PEGADAS en una sola
     * notificacion —el eco del comando y despues el estado completo—. Hay que
     * acumular, buscar `FE FE` y cortar por el largo, en bucle.
     *
     * Devuelve las tramas completas y cuantos bytes del principio se pueden
     * descartar del acumulador.
     */
    fun partir(buf: ByteArray, largoUtil: Int): Pair<List<ByteArray>, Int> {
        val tramas = mutableListOf<ByteArray>()
        var i = 0
        while (i + 3 <= largoUtil) {
            if ((buf[i].toInt() and 0xFF) != CAB_A || (buf[i + 1].toInt() and 0xFF) != CAB_B) {
                i++   // basura antes de una cabecera: se tira byte a byte
                continue
            }
            val largo = buf[i + 2].toInt() and 0xFF
            val total = 3 + largo
            if (largo <= 0 || i + total > largoUtil) break   // incompleta: esperar mas
            tramas += buf.copyOfRange(i, i + total)
            i += total
        }
        return tramas to i
    }

    /** Un byte de temperatura viene con SIGNO: 0xF3 son -13, no 243. */
    private fun conSigno(b: Byte): Int {
        val v = b.toInt() and 0xFF
        return if (v > 127) v - 256 else v
    }

    /**
     * Lo que dice la nevera. Todo anulable: si un campo no cuadra, `null`.
     *
     * ⚠️ NO HAY ESTADO DEL COMPRESOR. Ninguna de las cuatro implementaciones
     * lo expone en modelos de una zona, y el propio autor original documenta
     * el unico byte candidato de los de doble zona como "significado
     * desconocido". Inventarse un bit seria exactamente lo que este proyecto
     * no hace: se DEDUCE comparando temperatura contra consigna e histeresis,
     * y se marca como deducido en pantalla.
     */
    data class Estado(
        val encendida: Boolean,
        val consigna: Int,
        val actual: Int,
        val minima: Int,
        val maxima: Int,
        val histeresis: Int,
        val unidadCelsius: Boolean,
        val voltaje: Float?,
        /** 0 = Max, 1 = Eco. */
        val modoEco: Boolean,
        /**
         * El payload tal cual vino.
         *
         * Hace falta porque el comando de ajustes (0x02) es
         * LEER-MODIFICAR-ESCRIBIR: hay que reenviar TODOS los parametros
         * cambiando solo el que se toca. Sin el original, encender la nevera
         * le pisaria la consigna, el modo y el corte por bajo voltaje.
         */
        val crudo: ByteArray = ByteArray(0),
    ) {
        /**
         * El compresor, DEDUCIDO. Arranca cuando la temperatura sube por
         * encima de la consigna mas la histeresis, y para al alcanzarla.
         * Devuelve null si la nevera esta apagada: ahi no hay nada que
         * deducir.
         */
        fun compresorEnMarcha(): Boolean? {
            if (!encendida) return false
            return actual > consigna + histeresis
        }
    }

    /**
     * Decodifica una trama de estado. Devuelve null ante cualquier duda.
     *
     * El payload empieza en el byte 4, justo tras el codigo de comando.
     * Offsets, dentro del payload:
     *   0x01 encendida   0x04 consigna   0x05 max   0x06 min
     *   0x07 histeresis  0x09 unidad (0=C)  0x0E temperatura actual
     *   0x10 voltios enteros   0x11 decimas de voltio
     */
    fun decodificar(t: ByteArray): Estado? {
        if (t.size < 3) return null
        if ((t[0].toInt() and 0xFF) != CAB_A || (t[1].toInt() and 0xFF) != CAB_B) return null

        // El checksum se comprueba SIEMPRE. Una trama que no cuadra no se
        // interpreta: es la unica defensa contra pintar una temperatura que
        // la nevera no dijo.
        val esperado = checksum(t, t.size - 2)
        val trae = ((t[t.size - 2].toInt() and 0xFF) shl 8) or (t[t.size - 1].toInt() and 0xFF)
        if (esperado != trae) return null

        val cmd = t[3].toInt() and 0xFF
        if (cmd != CMD_CONSULTAR && cmd != CMD_AJUSTES) return null

        val p = t.copyOfRange(4, t.size - 2)
        if (p.size < 0x12) return null   // no llega ni al voltaje

        val consigna = conSigno(p[0x04])
        val actual = conSigno(p[0x0E])
        val minima = conSigno(p[0x06])
        val maxima = conSigno(p[0x05])

        // Guarda de plausibilidad. Una nevera portatil no baja de -40 ni
        // sube de 60 en ninguna unidad razonable; fuera de eso es basura.
        if (actual !in -40..60 || consigna !in -40..60) return null

        val vEnt = p[0x10].toInt() and 0xFF
        val vDec = p[0x11].toInt() and 0xFF
        val volt = if (vEnt in 1..30 && vDec in 0..9) vEnt + vDec / 10f else null

        return Estado(
            encendida = (p[0x01].toInt() and 0xFF) != 0,
            consigna = consigna,
            actual = actual,
            minima = minima,
            maxima = maxima,
            histeresis = p[0x07].toInt() and 0xFF,
            unidadCelsius = (p[0x09].toInt() and 0xFF) == 0,
            voltaje = volt,
            modoEco = (p[0x02].toInt() and 0xFF) != 0,
            crudo = p,
        )
    }
}
