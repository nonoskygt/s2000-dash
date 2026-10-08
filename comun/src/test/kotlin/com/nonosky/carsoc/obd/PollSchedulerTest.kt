package com.nonosky.carsoc.obd

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica que el reparto del presupuesto de K-line sea el de §5.
 *
 * El presupuesto es real: ~9-14 lecturas por segundo entre TODO. Si un PID
 * se lleva mas de lo suyo, se lo quita a la aguja.
 */
class PollSchedulerTest {

    private val plan = PollScheduler.Plan

    @org.junit.Before
    fun carro() = com.nonosky.carsoc.CarroDePrueba.instalar()

    private fun countsOver(cycles: Int): Map<String?, Int> =
        (0 until cycles).map { plan.secondaryFor(it) }.groupingBy { it }.eachCount()

    @Test
    fun `las proporciones sobre un periodo completo son las de la seccion 5`() {
        val c = countsOver(PollScheduler.Plan.PERIOD)

        // La velocidad ya NO se pide: la tiene el cuadro original del carro
        // y gastaba un tercio del presupuesto en un dato duplicado.
        assertNull(c[PidDecoder.PID_SPEED])

        // Carga cada 10 -> 6 de 60. Sostiene ademas la deteccion del VTEC.
        assertEquals(6, c[PidDecoder.PID_LOAD])
        // Refrigerante cada 20 -> 3 de 60
        assertEquals(3, c[PidDecoder.PID_COOLANT])
        // Aire de admision cada 20 -> 3 de 60
        assertEquals(3, c[PidDecoder.PID_IAT])
        // Voltaje cada 20 -> 3 de 60
        assertEquals(3, c[PollScheduler.PID_VOLTAGE])

        // La columna de ADMISION, pagada con los turnos de la velocidad.
        assertEquals(6, c[PidDecoder.PID_MAP])
        assertNull(c[PidDecoder.PID_ACELERADOR])
        assertEquals(4, c[PidDecoder.PID_AVANCE])
        // El O2 ya no se pide: su voltaje no lo pinta nadie desde que MEZCLA
        // sale de los ajustes de combustible, y sus cuatro turnos volvieron al
        // presupuesto en vez de repartirse.
        assertNull(c[PidDecoder.PID_O2_V])

        // Los ajustes de combustible y la luz de averia.
        assertEquals(2, c[PidDecoder.PID_TRIM_CORTO])
        assertEquals(2, c[PidDecoder.PID_TRIM_LARGO])
        assertEquals(1, c[PidDecoder.PID_ESTADO])
        // La mezcla de verdad (0134), tantos como el colector.
        assertEquals(6, c[PidDecoder.PID_LAMBDA])
    }

    @Test
    fun `ningun ciclo pide dos datos secundarios`() {
        // La tabla devuelve un solo PID por ciclo por construccion; esto
        // atrapa que un slot pise a otro al editar las listas.
        val c = countsOver(PollScheduler.Plan.PERIOD)
        val asignados = c.filterKeys { it != null }.values.sum()
        // La velocidad se fue (eran 20) y sus turnos pagaron la ADMISION.
        // 6 carga + 3 agua + 3 aire + 3 voltaje
        // + 6 colector + 6 acelerador + 4 avance + 4 mezcla
        // + 2 ajuste corto + 2 ajuste largo + 1 estado + 6 lambda
        assertEquals(6 + 3 + 3 + 3 + 6 + 4 + 2 + 2 + 1 + 6, asignados)
    }

    @Test
    fun `el agua y el aire nunca caen en el mismo ciclo`() {
        // Comparten frecuencia; si coincidieran, uno se quedaria sin turno.
        for (i in 0 until PollScheduler.Plan.PERIOD) {
            val pid = plan.secondaryFor(i)
            if (pid == PidDecoder.PID_COOLANT) {
                assertTrue(plan.secondaryFor(i) != PidDecoder.PID_IAT)
            }
        }
        val agua = (0 until 60).filter { plan.secondaryFor(it) == PidDecoder.PID_COOLANT }
        val aire = (0 until 60).filter { plan.secondaryFor(it) == PidDecoder.PID_IAT }
        assertTrue("agua y aire se traslapan", agua.intersect(aire.toSet()).isEmpty())
    }

    @Test
    fun `el patron se repite y aguanta ciclos negativos o desbordados`() {
        assertEquals(plan.secondaryFor(0), plan.secondaryFor(60))
        assertEquals(plan.secondaryFor(14), plan.secondaryFor(74))
        // Si el contador desborda a negativo, la tabla no debe reventar.
        assertEquals(plan.secondaryFor(0), plan.secondaryFor(-60))
        assertEquals(plan.secondaryFor(59), plan.secondaryFor(-1))
    }

    @Test
    fun `la aguja se queda con su parte del presupuesto`() {
        // 60 ciclos de RPM + 35 secundarios = 95 lecturas por periodo. A
        // ~100 ms de round-trip el periodo dura ~9.5 s, asi que el RPM sale
        // a ~6.3 Hz: el ~6 Hz que §5 le reserva a la aguja.
        val secundarios = countsOver(PollScheduler.Plan.PERIOD)
            .filterKeys { it != null }.values.sum()
        val lecturasPorPeriodo = PollScheduler.Plan.PERIOD + secundarios
        val segundosPorPeriodo = lecturasPorPeriodo * 0.1
        val rpmHz = PollScheduler.Plan.PERIOD / segundosPorPeriodo

        assertTrue("la aguja baja de 6 Hz: $rpmHz", rpmHz >= 6.0)
        val totalHz = lecturasPorPeriodo / segundosPorPeriodo
        assertTrue("se pasa del techo de §4: $totalHz", totalHz <= 14.0)
    }

    @Test
    fun `el backoff sube y se topa en 10 segundos`() {
        // Scope propio y no GlobalScope: el backoff es una funcion pura, no
        // queremos que el test deje corrutinas sueltas.
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val s = PollScheduler({ FakeTransport(emptyMap()) }, scope)
        assertEquals(1_000L, s.backoffMs(0))
        assertEquals(2_000L, s.backoffMs(1))
        assertEquals(4_000L, s.backoffMs(2))
        assertEquals(8_000L, s.backoffMs(3))
        assertEquals(10_000L, s.backoffMs(4))
        assertEquals(10_000L, s.backoffMs(99))
        // Con la ECU muda el techo es mas alto: cada vuelta cuesta un enlace
        // y ~20 s de K-line, y no hay prisa por repetirla.
        assertEquals(1_000L, s.backoffEcuMudaMs(0))
        assertEquals(16_000L, s.backoffEcuMudaMs(4))
        assertEquals(20_000L, s.backoffEcuMudaMs(99))
        scope.cancel()
    }

    @Test(timeout = 10_000)
    fun `el sondeo no corre en el hilo del scope que lo lanza`() {
        // Regresion de un defecto critico: el scope que recibe PollScheduler
        // es lifecycleScope, que despacha en el hilo PRINCIPAL. Como todo el
        // sondeo es I/O bloqueante (BluetoothSocket, sleeps), correrlo ahi
        // congelaba la UI y daba ANR en cuanto conectaba el adaptador.
        val hiloDelScope = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "falso-main")
        }
        val scope = kotlinx.coroutines.CoroutineScope(
            hiloDelScope.asCoroutineDispatcher()
        )

        val visto = java.util.concurrent.atomic.AtomicReference<String>()
        val llego = java.util.concurrent.CountDownLatch(1)

        val s = PollScheduler(
            transportFactory = {
                object : ObdTransport {
                    override val isConnected = true
                    override fun connect() {
                        visto.compareAndSet(null, Thread.currentThread().name)
                        llego.countDown()
                        throw java.io.IOException("basta")
                    }
                    override fun write(bytes: ByteArray) {}
                    override fun readUntilPrompt(timeoutMs: Long) = ""
                    override fun drain() {}
                    override fun close() {}
                }
            },
            scope = scope,
        )

        s.start()
        assertTrue("nunca intento conectar", llego.await(8, java.util.concurrent.TimeUnit.SECONDS))
        s.stop()
        scope.cancel()
        hiloDelScope.shutdownNow()

        assertTrue(
            "el I/O bloqueante corrio en el hilo del scope (seria el Main real): ${visto.get()}",
            visto.get() != null && visto.get() != "falso-main",
        )
    }

    /**
     * Un dato no caduca entre una lectura suya y la siguiente. Antes todo
     * caducaba a los 3 s y el agua —que se lee cada ~5 s— parpadeaba en
     * blanco con el enlace perfecto.
     */
    @Test
    fun `cada dato caduca a su ritmo y el RPM a los 3 s`() {
        val min = 3_000L
        assertEquals(min, plan.plazoMs(PidDecoder.PID_RPM, min))
        val lecturasPeriodo = PollScheduler.Plan.PERIOD +
            (0 until PollScheduler.Plan.PERIOD).count { plan.secondaryFor(it) != null }
        val periodoMs = lecturasPeriodo * 1000.0 / PollScheduler.Plan.LECTURAS_POR_SEGUNDO_PRUDENTE
        for (pid in listOf(PidDecoder.PID_COOLANT, PidDecoder.PID_IAT, PidDecoder.PID_AVANCE,
            PidDecoder.PID_TRIM_CORTO, PidDecoder.PID_ESTADO, PidDecoder.PID_LAMBDA)) {
            val entreLecturas = periodoMs / plan.vecesPorPeriodo(pid)
            assertTrue("$pid caduca antes de su siguiente lectura",
                plan.plazoMs(pid, min) > entreLecturas * 2)
        }
    }
}
