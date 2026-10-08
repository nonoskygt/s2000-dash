package com.nonosky.carsoc.hci

/**
 * La trama L2CAP en modo basico (B-frame), bit a bit.
 *
 * L2CAP es la capa que multiplexa: sobre un mismo enlace ACL conviven la
 * senalizacion, ATT, RFCOMM y lo que haga falta, cada uno con su CID. Es lo
 * unico que hay entre el sobre ACL y los protocolos utiles.
 *
 * ESTANDAR VERIFICABLE (Core Spec Vol 3 Part A, seccion 3.1):
 *
 * ```
 *  byte 0-1 : Length  (little endian) = tamano SOLO de la carga
 *  byte 2-3 : CID     (little endian) = canal destino
 *  byte 4.. : la carga
 * ```
 *
 * El `Length` **no** cuenta los 4 bytes de cabecera. Es el error clasico:
 * sumarlos hace que el otro lado espere 4 bytes que nunca llegan y el canal
 * se queda mudo sin ningun mensaje de error.
 *
 * Los CID importantes, y por que:
 *
 *  - `0x0004` **ATT, fijo en todo enlace LE**. No se negocia, no se abre, no
 *    se configura: existe desde el instante en que hay enlace. Es lo que hace
 *    viable leer la bateria sin implementar la senalizacion completa.
 *  - `0x0001` senalizacion de un enlace CLASICO (BR/EDR). Por aqui van
 *    CONNECTION REQUEST y CONFIGURATION REQUEST, que es el camino obligado
 *    para llegar a RFCOMM y por tanto al ELM327.
 *  - `0x0005` senalizacion de un enlace LE. No hace falta para ATT; sirve
 *    para pedir cambios de parametros de conexion.
 *  - `0x0006` SMP (emparejamiento LE). Solo hace falta si el aparato exige
 *    cifrado. Un BMS JBD no lo exige.
 *  - `0x0040` y arriba: canales dinamicos, los que reparte la senalizacion.
 */
object L2cap {

    const val CABECERA = 4

    const val CID_NULO = 0x0000
    const val CID_SENAL_CLASICO = 0x0001
    const val CID_SIN_CONEXION = 0x0002
    const val CID_ATT = 0x0004
    const val CID_SENAL_LE = 0x0005
    const val CID_SMP = 0x0006

    const val CID_DINAMICO_MIN = 0x0040
    const val CID_DINAMICO_MAX = 0xFFFF

    /** PSM del servicio de descubrimiento. */
    const val PSM_SDP = 0x0001

    /** PSM de RFCOMM: el puerto serie sobre el que habla un ELM327. */
    const val PSM_RFCOMM = 0x0003

    /**
     * MTU minima que hay que aceptar en un canal clasico por especificacion.
     * Si el otro lado no dice nada, esto es lo que vale.
     */
    const val MTU_MINIMA_CLASICA = 48

    /** MTU minima de la senalizacion clasica. Tampoco se negocia por debajo. */
    const val MTU_SENAL_CLASICA = 48

    /**
     * MTU de ATT por defecto en LE: 23 bytes.
     *
     * Cabe justo en un paquete ACL LE de 27 (23 + 4 de cabecera L2CAP), y no
     * es casualidad: la especificacion lo eligio asi para que una PDU ATT
     * quepa en un paquete sin trocear.
     */
    const val MTU_ATT_POR_DEFECTO = 23

    fun armar(cid: Int, carga: ByteArray): ByteArray {
        require(cid in 0..0xFFFF) { "CID fuera de rango: $cid" }
        require(carga.size <= 0xFFFF) { "carga demasiado grande: ${carga.size}" }
        val pdu = ByteArray(CABECERA + carga.size)
        pdu[0] = (carga.size and 0xFF).toByte()
        pdu[1] = ((carga.size shr 8) and 0xFF).toByte()
        pdu[2] = (cid and 0xFF).toByte()
        pdu[3] = ((cid shr 8) and 0xFF).toByte()
        carga.copyInto(pdu, CABECERA)
        return pdu
    }

    fun largoDe(pdu: ByteArray): Int =
        if (pdu.size < CABECERA) -1
        else (pdu[0].toInt() and 0xFF) or ((pdu[1].toInt() and 0xFF) shl 8)

    fun cidDe(pdu: ByteArray): Int =
        if (pdu.size < CABECERA) -1
        else (pdu[2].toInt() and 0xFF) or ((pdu[3].toInt() and 0xFF) shl 8)

    /** La carga, recortada a lo que declara la cabecera. */
    fun cargaDe(pdu: ByteArray): ByteArray {
        if (pdu.size <= CABECERA) return ByteArray(0)
        val declarado = largoDe(pdu)
        val hay = pdu.size - CABECERA
        val n = if (declarado in 0..hay) declarado else hay
        return pdu.copyOfRange(CABECERA, CABECERA + n)
    }

    fun completa(pdu: ByteArray): Boolean =
        pdu.size >= CABECERA && pdu.size - CABECERA >= largoDe(pdu)

    fun nombreCid(cid: Int): String = when (cid) {
        CID_NULO -> "nulo"
        CID_SENAL_CLASICO -> "senalizacion clasica"
        CID_SIN_CONEXION -> "sin conexion"
        CID_ATT -> "ATT"
        CID_SENAL_LE -> "senalizacion LE"
        CID_SMP -> "SMP"
        else -> if (cid >= CID_DINAMICO_MIN) "dinamico" else "reservado"
    }

    fun describir(pdu: ByteArray): String {
        if (pdu.size < CABECERA) return "L2CAP truncado (${pdu.size} bytes)"
        return "L2CAP cid=0x${"%04X".format(cidDe(pdu))} (${nombreCid(cidDe(pdu))}) " +
            "largo=${largoDe(pdu)} (llegaron ${pdu.size - CABECERA})"
    }
}
