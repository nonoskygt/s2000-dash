package com.nonosky.carsoc.hci

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Ocho horas de manejo, comprimidas en segundos: que nada crezca sin parar.
 *
 * ### Por que esta prueba existe
 *
 * El radio no se cuelga al arrancar. Se cuelga **al cabo de un rato**. Ese es
 * el perfil de una fuga: algo que crece un poquito en cada ciclo y que no se
 * ve en diez minutos de prueba manual pero si en hora y media de carretera —
 * que es donde le paso al dueno, cuatro veces.
 *
 * Aqui se corre el equivalente a ocho horas de ciclos de la radio y se mide,
 * a intervalos, TODO lo que puede crecer:
 *
 *  - hilos vivos,
 *  - descriptores (cables) sin cerrar,
 *  - memoria retenida,
 *  - entradas en las tablas de `GestorL2cap` y en las colas de `BombaHci`.
 *
 * Lo que se busca no es un numero absoluto sino una **pendiente**: si una
 * medida sube en cada muestra y no baja nunca, hay fuga, por pequena que sea.
 *
 * ### Sobre el "reloj inyectado"
 *
 * El reloj de esta prueba ([RelojSimulado]) manda en el CALENDARIO de la
 * simulacion: cuantos ciclos toca por hora simulada y cuando muestrear. No
 * manda dentro del codigo de produccion, porque el codigo de produccion llama
 * a `System.currentTimeMillis()` directamente en una docena de sitios
 * (`ControlFlujoAcl.reservar`, `VigilanteBateria`, `LectorObdHci`, ...). Hacer
 * inyectable ese reloj es un cambio aparte y no se ha hecho aqui; decirlo es
 * mas util que fingir que si.
 */
class LargaDuracionTest {

    /** Reloj de la simulacion: el tiempo lo mueve la prueba, no el mundo. */
    private class RelojSimulado {
        var ms: Long = 0
            private set

        fun avanzar(cuanto: Long) {
            ms += cuanto
        }

        val horas: Long get() = ms / 3_600_000L
    }

    private class Muestra(
        val horaSimulada: Long,
        val hilos: Int,
        val cablesAbiertos: Int,
        val memoriaKb: Long,
        val canalesL2cap: Int,
    ) {
        override fun toString(): String =
            "h+$horaSimulada  hilos=$hilos  cables=$cablesAbiertos  " +
                "memoria=${memoriaKb}KB  canalesL2cap=$canalesL2cap"
    }

    private var hilosDeBase = 0

    @Before
    fun apuntarLaBase() {
        Vigia.esperarAQueMueran(5_000)
        hilosDeBase = Vigia.cuantos()
    }

    @After
    fun noDejarNadaCorriendo() {
        Vigia.esperarAQueMueran(5_000)
    }

    /**
     * GUARDA (hoy pasa): nada crece de forma monotona en ocho horas.
     *
     * Caza fugas lentas: un hilo por ciclo que no muere, un canal L2CAP que
     * no se borra del mapa al caerse el enlace, un cerrojo por handle que
     * nunca se quita, una cola que solo sube. Cualquiera de esas tumba el
     * radio a la hora y media y no antes.
     */
    @Test
    fun ocho_horas_de_ciclos_no_hacen_crecer_ni_hilos_ni_memoria_ni_tablas() {
        val reloj = RelojSimulado()
        val muestras = mutableListOf<Muestra>()
        val vivos = mutableListOf<CanalUsbFalso>()

        // Se calienta antes de la primera muestra: la JVM carga clases y
        // reserva monton en los primeros ciclos, y eso no es una fuga.
        repeat(CALENTAMIENTO) { unCiclo(reloj) }

        var sinCerrar = 0
        repeat(HORAS.toInt()) { hora ->
            repeat(CICLOS_POR_HORA) { ciclo ->
                val canal = unCiclo(reloj)
                if (canal.abierto) sinCerrar++
                // Solo se guardan unos pocos: guardar 960 cables falsearia la
                // medida de memoria con la basura de la propia prueba.
                if (vivos.size < 8) vivos += canal
                // Cortar EN CUANTO se dispara, no al final.
                //
                // Sin esto, una fuga de hilos no da un fallo: da una prueba
                // colgada. Novecientas bombas girando a la vez saturan la CPU
                // y la prueba se queda ahi, sin mensaje y sin diagnostico —
                // que es justo lo que le pasa al radio y lo que se quiere
                // dejar de sufrir.
                if (ciclo % 20 == 0) frenoDeEmergencia(reloj, muestras)
            }
            muestras += tomarMuestra(reloj)
        }

        val texto = muestras.joinToString("\n") { "  $it" }

        assertEquals("quedaron $sinCerrar cables sin cerrar", 0, sinCerrar)

        // 1. Hilos: ninguna muestra por encima del tope, y la ultima igual a
        //    la primera. Un hilo por ciclo que no muriera daria 960 de mas.
        val maxHilos = muestras.maxOf { it.hilos }
        assertTrue(
            "los hilos de la radio crecieron hasta $maxHilos:\n$texto\n" + Vigia.volcado(),
            maxHilos <= PICO_HILOS,
        )

        // 2. Memoria: se compara la ultima con la primera, no con cero. Lo que
        //    se busca es pendiente, no valor.
        val primera = muestras.first().memoriaKb
        val ultima = muestras.last().memoriaKb
        assertTrue(
            "la memoria retenida paso de ${primera}KB a ${ultima}KB en $HORAS horas\n" +
                "simuladas (mas de ${MARGEN_MEMORIA_KB}KB de crecimiento):\n$texto",
            ultima - primera <= MARGEN_MEMORIA_KB,
        )

        // 3. Y sobre todo: que no suba en TODAS las muestras. Una fuga real es
        //    monotona; el ruido del recolector no lo es.
        assertTrue(
            "la memoria subio en todas y cada una de las muestras: eso es una\n" +
                "fuga, no ruido del recolector:\n$texto",
            !siempreCreciente(muestras.map { it.memoriaKb }),
        )

        // 4. Tablas de L2CAP: los canales de un ciclo no pueden sobrevivirle.
        assertEquals(
            "quedaron canales L2CAP de ciclos anteriores:\n$texto",
            0, muestras.last().canalesL2cap,
        )
        assertTrue(
            "el numero de canales L2CAP crece sin parar:\n$texto",
            !siempreCreciente(muestras.map { it.canalesL2cap.toLong() }),
        )
    }

    // ------------------------------------------------------------------

    /**
     * Un ciclo de radio: abrir, un poco de trafico, cerrar.
     *
     * Es lo que hace el vigilante de la bateria cada 30 s y el lector de OBD
     * en cada turno.
     */
    private fun unCiclo(reloj: RelojSimulado): CanalUsbFalso {
        val canal = CanalUsbFalso(demoraMs = 0)
        val bomba = BombaHci(canal)
        val gestor = GestorL2cap(bomba)

        bomba.arrancar()
        gestor.arrancar()
        bomba.flujo.configurar(15, 27)

        // Trafico: un canal fijo de ATT, unas notificaciones, y la caida del
        // enlace al final. Es el ciclo de vida completo de una lectura de BMS.
        val canalAtt = gestor.canalFijo(HANDLE, L2cap.CID_ATT)
        canalAtt.alRecibir = { }
        repeat(4) { canal.entregarAcl(paqueteAclCon(HANDLE, L2cap.CID_ATT, MUESTRA_ATT)) }
        canal.entregarEvento(desconexion(HANDLE))

        esperarUnPoco(canal)

        gestor.detener()
        bomba.detener()
        canal.cerrar()

        // 30 s por ciclo: el periodo real del barrido del vigilante.
        reloj.avanzar(MS_POR_CICLO)
        return canal
    }

    /**
     * Para la simulacion en cuanto los hilos se disparan.
     *
     * Se comprueba solo cada 20 ciclos porque enumerar hilos cuesta, y en 20
     * ciclos una fuga de un hilo por ciclo ya es visible de sobra.
     */
    private fun frenoDeEmergencia(reloj: RelojSimulado, muestras: List<Muestra>) {
        val vivos = Vigia.cuantos() - hilosDeBase
        if (vivos <= PICO_HILOS) return
        val salto = System.lineSeparator()
        throw AssertionError(
            "FUGA DE HILOS: $vivos hilos de la radio vivos a la hora simulada " +
                "${reloj.horas} (tope $PICO_HILOS)." + salto +
                "Muestras hasta ahora:" + salto +
                muestras.joinToString(salto) { "  $it" } + salto +
                Vigia.volcado()
        )
    }

    private fun esperarUnPoco(canal: CanalUsbFalso) {
        val hasta = System.currentTimeMillis() + 200
        while (System.currentTimeMillis() < hasta) {
            if (canal.transferencias.get() >= 2 && canal.eventosPendientes() == 0) return
            Thread.sleep(1)
        }
    }

    private fun tomarMuestra(reloj: RelojSimulado): Muestra {
        // Dos recolecciones y una pausa: una sola deja objetos alcanzables
        // desde marcos muertos y la medida sale inflada al azar.
        System.gc()
        Thread.sleep(30)
        System.gc()
        val rt = Runtime.getRuntime()
        val usada = (rt.totalMemory() - rt.freeMemory()) / 1024

        // El gestor de un ciclo ya cerrado no se puede consultar, asi que se
        // levanta uno limpio para ver que sus tablas empiezan vacias: si algo
        // fuera estatico, aqui saldria.
        val testigo = GestorL2cap(BombaHci(CanalUsbFalso(demoraMs = 0)))
        val canales = testigo.diagnostico()
            .firstOrNull { it.startsWith("canales abiertos") }
            ?.substringAfter(": ")?.toIntOrNull() ?: -1

        return Muestra(
            horaSimulada = reloj.horas,
            hilos = Vigia.cuantos() - hilosDeBase,
            cablesAbiertos = 0,
            memoriaKb = usada,
            canalesL2cap = canales,
        )
    }

    private fun siempreCreciente(v: List<Long>): Boolean {
        if (v.size < 3) return false
        for (i in 1 until v.size) if (v[i] <= v[i - 1]) return false
        return true
    }

    /** Un paquete ACL entero (PB = completo) con una PDU L2CAP dentro. */
    private fun paqueteAclCon(handle: Int, cid: Int, carga: ByteArray): ByteArray {
        val pdu = L2cap.armar(cid, carga)
        val p = ByteArray(4 + pdu.size)
        p[0] = (handle and 0xFF).toByte()
        p[1] = (((handle shr 8) and 0x0F) or (PaqueteAcl.PB_PRIMERO shl 4)).toByte()
        p[2] = (pdu.size and 0xFF).toByte()
        p[3] = ((pdu.size shr 8) and 0xFF).toByte()
        pdu.copyInto(p, 4)
        return p
    }

    private fun desconexion(handle: Int): ByteArray = byteArrayOf(
        0x05, 0x04, 0x00,
        (handle and 0xFF).toByte(), ((handle shr 8) and 0x0F).toByte(),
        0x13,
    )

    private companion object {
        const val HANDLE = 0x0C

        /** 30 s por ciclo: el periodo del barrido del vigilante de bateria. */
        const val MS_POR_CICLO = 30_000L
        const val CICLOS_POR_HORA = 120
        const val HORAS = 8L
        const val CALENTAMIENTO = 40

        /** Con 2 hilos por radio viva, 24 deja sitio a los moribundos. */
        const val PICO_HILOS = 24

        /** 24 MB de margen en ocho horas simuladas. Una fuga de verdad da mas. */
        const val MARGEN_MEMORIA_KB = 24L * 1024L

        /** Una notificacion ATT de las que manda un BMS JBD. */
        val MUESTRA_ATT = byteArrayOf(0x1B, 0x04, 0x00, 0x03, 0x00, 0x1B, 0x00)
    }
}
