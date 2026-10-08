package com.nonosky.carsoc.hci

/**
 * Las dos capacidades que necesita cualquiera que hable HCI: escuchar
 * eventos y mandar comandos.
 *
 * Existen como interfaces porque hay DOS formas de bombear el mismo endpoint
 * y no se pueden usar a la vez: [BombaEventos], que solo lee eventos y sirve
 * cuando la radio es de un solo dueño, y [BombaHci], que lee eventos y datos
 * ACL y es la que usa la radio compartida.
 *
 * Montar las dos sobre el mismo endpoint hace que se roben los paquetes, y el
 * sintoma es de los que engañan: los comandos dejan de recibir respuesta y el
 * error dice "no se pudo preparar el controlador", que apunta al controlador
 * cuando el problema es tener dos lectores. Ya paso dos veces en este
 * proyecto. Con estas interfaces, quien habla HCI recibe la que haya y no
 * puede crear una segunda por su cuenta.
 */
interface EventosHci {
    /** Suscribe un oyente y devuelve como darse de baja. */
    fun suscribir(oyente: (ByteArray) -> Unit): () -> Unit
}

interface ComandosHci {
    /** Manda un comando y devuelve su evento de respuesta, o null. */
    fun ejecutar(opcode: Int, parametros: ByteArray = ByteArray(0), timeoutMs: Long = 3_000): ByteArray?
}

/**
 * Adaptador de [BombaHci] a las dos interfaces.
 *
 * La bomba compartida ya sabe hacer las dos cosas; esto solo le pone la forma
 * que espera quien la consume, sin que nadie tenga que arrancar otra.
 */
class BombeoCompartido(private val bomba: BombaHci) : EventosHci, ComandosHci {

    override fun suscribir(oyente: (ByteArray) -> Unit): () -> Unit {
        val o = object : BombaHci.Oyente {
            override fun alEvento(evento: ByteArray) {
                runCatching { oyente(evento) }
            }
        }
        bomba.suscribir(o)
        return { runCatching { bomba.quitar(o) } }
    }

    override fun ejecutar(opcode: Int, parametros: ByteArray, timeoutMs: Long): ByteArray? =
        runCatching { bomba.comando(opcode, parametros, timeoutMs) }.getOrNull()
}
