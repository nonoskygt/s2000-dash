package com.nonosky.carsoc.ui.lienzo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Las pruebas del trazado de LLANTAS Y ACEITE.
 *
 * Existen por la razon de siempre: el tablero Canvas viejo se rompio al pasar
 * de 1280x480 a 1024x600 —los numeros se pisaban— y nadie pudo verlo hasta que
 * llego una foto desde el carro. Aqui las tres pantallas del encargo se
 * comprueban en la JVM, en un segundo, antes de compilar un APK.
 *
 * Lo que SI cubren: que las cajas salen validas, dentro de su padre, sin
 * solaparse entre ellas y en el orden correcto de las ruedas, en las tres
 * pantallas.
 *
 * Lo que NO cubren, y hay que decirlo: la tipografia. Medir texto necesita un
 * `Paint` de verdad y el android.jar de las pruebas devuelve cero. El encogido
 * y la marca de "no cabe" se verifican en pantalla, no aqui.
 */
class TrazadoLlantasTest {

    @Before
    fun carro() = com.nonosky.carsoc.CarroDePrueba.instalar()

    private val EPS = 0.01f

    /**
     * La caja que la vista le dara a esta seccion: la tercera columna del
     * cuerpo, debajo de la cabecera, con el presupuesto del tablero HTML.
     *
     * Se calcula con el propio [Reparto] a proposito: es exactamente el camino
     * por el que la caja va a llegar de verdad.
     */
    private fun seccionDe(ancho: Float, alto: Float): Caja {
        val marco = Caja.pantalla(ancho, alto).margen(ancho * 0.0117f)
        val cuerpo = Reparto.filas(marco, floatArrayOf(44f, 542f), 10f)[1]
        return Reparto.columnas(cuerpo, floatArrayOf(396f, 264f, 320f), 10f)[2]
    }

    private fun dentroDe(hijo: Caja, padre: Caja): Boolean =
        hijo.x0 >= padre.x0 - EPS && hijo.y0 >= padre.y0 - EPS &&
            hijo.x1 <= padre.x1 + EPS && hijo.y1 <= padre.y1 + EPS

    private fun seSolapan(a: Caja, b: Caja): Boolean =
        a.x0 < b.x1 - EPS && b.x0 < a.x1 - EPS &&
            a.y0 < b.y1 - EPS && b.y0 < a.y1 - EPS

    // --- 1 -------------------------------------------------------------------

    @Test
    fun `las tres pantallas del encargo salen enteras y sin pisarse`() {
        // 1280x480 es donde nacio el tablero viejo, 1024x600 es donde se
        // rompio, y 800x480 es la pantalla mas estrecha que hay que aguantar.
        val pantallas = arrayOf(
            floatArrayOf(1280f, 480f),
            floatArrayOf(1024f, 600f),
            floatArrayOf(800f, 480f),
        )

        for (p in pantallas) {
            val donde = "${p[0].toInt()}x${p[1].toInt()}"
            val seccion = seccionDe(p[0], p[1])
            val t = TrazadoLlantas()
            t.reparte(seccion)

            assertTrue("$donde: el reparto tendria que salir", t.valido)
            assertTrue("$donde: tarjeta de llantas", t.tarjetaLlantas.valida)
            assertTrue("$donde: tarjeta de aceite", t.tarjetaAceite.valida)

            // Las dos tarjetas dentro de la seccion y sin tocarse.
            assertTrue("$donde: llantas dentro", dentroDe(t.tarjetaLlantas, seccion))
            assertTrue("$donde: aceite dentro", dentroDe(t.tarjetaAceite, seccion))
            assertFalse(
                "$donde: las dos tarjetas se pisan",
                seSolapan(t.tarjetaLlantas, t.tarjetaAceite),
            )

            // El rotulo y su aviso: repartidos, no superpuestos. Si se
            // pisaran, "LLANTAS" y "TD BAJA" saldrian una encima de otra.
            assertFalse("$donde: rotulo y aviso se pisan", seSolapan(t.tituloCorto, t.aviso))
            assertTrue("$donde: aviso dentro del rotulo", dentroDe(t.aviso, t.tituloLlantas))
            assertTrue(
                "$donde: el texto del aviso dentro del aviso",
                dentroDe(t.avisoTextoCaja, t.aviso),
            )

            // Las cuatro casillas: validas, dentro, y sin solaparse entre si.
            for (i in 0..3) {
                assertTrue("$donde: casilla $i", t.casilla[i].valida)
                assertTrue("$donde: casilla $i dentro", dentroDe(t.casilla[i], t.tarjetaLlantas))
            }
            for (i in 0..3) {
                for (j in i + 1..3) {
                    assertFalse(
                        "$donde: las casillas $i y $j se pisan",
                        seSolapan(t.casilla[i], t.casilla[j]),
                    )
                }
            }

            // Y dentro de cada casilla, las dos variantes enteras.
            for (i in 0..3) {
                for (v in 0..1) {
                    val k = i * 2 + v
                    val celda = t.casilla[i]
                    assertTrue("$donde: psi $i v$v", t.psi[k].valida)
                    assertTrue("$donde: temp $i v$v", t.temp[k].valida)
                    assertTrue("$donde: marca $i v$v", t.marca[k].valida)
                    assertTrue("$donde: dibujo $i v$v", t.dibujo[k].valida)
                    assertTrue("$donde: psi $i v$v dentro", dentroDe(t.psi[k], celda))
                    assertTrue("$donde: temp $i v$v dentro", dentroDe(t.temp[k], celda))
                    assertTrue("$donde: marca $i v$v dentro", dentroDe(t.marca[k], celda))
                    assertTrue("$donde: dibujo $i v$v dentro", dentroDe(t.dibujo[k], celda))
                    // Presion encima de temperatura, y ninguna encima del
                    // rotulo ni del dibujo.
                    assertFalse("$donde: psi y temp $i v$v", seSolapan(t.psi[k], t.temp[k]))
                    assertFalse("$donde: psi y marca $i v$v", seSolapan(t.psi[k], t.marca[k]))
                    assertFalse("$donde: temp y dibujo $i v$v", seSolapan(t.temp[k], t.dibujo[k]))
                    assertFalse("$donde: marca y dibujo $i v$v", seSolapan(t.marca[k], t.dibujo[k]))
                }
            }

            // El aceite entero, dentro de su tarjeta y sin pisarse.
            val piezas = arrayOf(
                t.tituloAceite, t.vida, t.faltanEtiqueta, t.faltanValor, t.barra, t.horas,
            )
            for (c in piezas) {
                assertTrue("$donde: pieza de aceite invalida", c.valida)
                assertTrue("$donde: pieza de aceite fuera", dentroDe(c, t.tarjetaAceite))
            }
            assertFalse("$donde: vida y faltan se pisan", seSolapan(t.vida, t.faltanValor))
            assertFalse("$donde: barra y horas se pisan", seSolapan(t.barra, t.horas))
            assertFalse("$donde: titulo y vida se pisan", seSolapan(t.tituloAceite, t.vida))
        }
    }

    // --- 2 -------------------------------------------------------------------

    @Test
    fun `las ruedas caen en la esquina del carro que les toca`() {
        // La posicion en pantalla ES el dato: arriba-izquierda es la
        // delantera izquierda. Si alguien cambia el orden sin querer, el
        // tablero avisaria de la rueda equivocada y esta prueba lo canta.
        val t = TrazadoLlantas()
        t.reparte(seccionDe(1024f, 600f))

        val di = t.casilla[0]
        val dd = t.casilla[1]
        val ti = t.casilla[2]
        val td = t.casilla[3]

        assertTrue("DI a la izquierda de DD", di.cx < dd.cx)
        assertTrue("TI a la izquierda de TD", ti.cx < td.cx)
        assertTrue("DI encima de TI", di.cy < ti.cy)
        assertTrue("DD encima de TD", dd.cy < td.cy)
        // Las delanteras a la misma altura, y las traseras tambien.
        assertEquals(di.cy, dd.cy, EPS)
        assertEquals(ti.cy, td.cy, EPS)
    }

    // --- 3 -------------------------------------------------------------------

    @Test
    fun `la banda del aviso se abre debajo del cuerpo, no encima`() {
        val t = TrazadoLlantas()
        t.reparte(seccionDe(1024f, 600f))

        for (i in 0..3) {
            val bandera = t.bandera[i]
            assertTrue("bandera $i", bandera.valida)
            assertTrue("bandera $i dentro", dentroDe(bandera, t.casilla[i]))

            // La variante 1 —la que grita— tiene que caber ENCIMA de la
            // banda. Si se solaparan, la palabra "BAJA" saldria escrita sobre
            // el numero de la temperatura justo cuando mas hay que leerlo.
            val k = i * 2 + 1
            assertFalse("bandera $i pisa la temperatura", seSolapan(bandera, t.temp[k]))
            assertFalse("bandera $i pisa el dibujo", seSolapan(bandera, t.dibujo[k]))
            assertTrue("la banda va abajo", t.temp[k].y1 <= bandera.y0 + EPS)
        }
    }

    // --- 4 -------------------------------------------------------------------

    @Test
    fun `al gritar, la casilla se reorganiza sin salirse`() {
        // Las dos variantes ocupan la MISMA casilla: cambiar de estado no
        // puede empujar nada fuera. Y la de aviso tiene que ser mas baja, que
        // para eso cede la banda.
        val t = TrazadoLlantas()
        t.reparte(seccionDe(800f, 480f))

        for (i in 0..3) {
            val calma = t.psi[i * 2]
            val grito = t.psi[i * 2 + 1]
            assertTrue("v1 $i mas baja que v0", grito.alto < calma.alto)
            assertTrue("v1 $i dentro de la casilla", dentroDe(grito, t.casilla[i]))
        }
    }

    // --- 5 -------------------------------------------------------------------

    @Test
    fun `una caja imposible se declara invalida en vez de inventarse sitio`() {
        // Un tablero que se cae manejando es peor que uno con un aspa
        // pintada, pero un tablero que dibuja cajas del reves es peor que los
        // dos: no se ve, y encima miente.
        val t = TrazadoLlantas()

        t.reparte(Caja.NADA)
        assertFalse("la caja que no existe no se reparte", t.valido)

        t.reparte(Caja.pantalla(6f, 3f))
        assertFalse("seis por tres no da para esta seccion", t.valido)

        t.reparte(Caja(0f, 0f, Float.NaN, 100f))
        assertFalse("un NaN no se reparte", t.valido)
    }

    // --- 6 -------------------------------------------------------------------

    @Test
    fun `repartir dos veces la misma caja no cambia nada`() {
        // Es lo que permite llamar a `reparte` desde el camino de dibujo sin
        // fabricar basura por cuadro: si la caja no cambio, no se toca nada.
        val caja = seccionDe(1024f, 600f)
        val t = TrazadoLlantas()
        t.reparte(caja)

        val antes = t.casilla[3]
        val aire = t.aire
        t.reparte(caja)

        assertTrue("la misma caja da la misma casilla", antes === t.casilla[3])
        assertEquals(aire, t.aire, 0f)
    }

    // --- 7 -------------------------------------------------------------------

    @Test
    fun `una seccion apaisada se reparte en columnas y no se estruja`() {
        // Si la vista coloca esta seccion en una banda ancha —900x160— dos
        // filas no caben de ninguna manera. Girar el reparto si.
        val ancha = Caja.pantalla(900f, 160f)
        val t = TrazadoLlantas()
        t.reparte(ancha)

        assertTrue("la banda ancha se reparte", t.valido)
        assertTrue(
            "llantas y aceite tendrian que quedar lado a lado",
            t.tarjetaLlantas.x1 <= t.tarjetaAceite.x0 + EPS,
        )
        assertFalse("y sin pisarse", seSolapan(t.tarjetaLlantas, t.tarjetaAceite))
        for (i in 0..3) {
            assertTrue("casilla $i en la banda ancha", t.casilla[i].valida)
            assertTrue("casilla $i dentro", dentroDe(t.casilla[i], t.tarjetaLlantas))
        }
    }
}
