package com.nonosky.carsoc.ui.lienzo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LA RED QUE IMPIDE QUE VUELVA A PASAR LO DEL DASHVIEW.
 *
 * ## Que se rompio la vez pasada, y por que nadie lo vio
 *
 * El tablero viejo estaba escrito "sin pixeles fijos" —lo exigia su
 * diseño por escrito— y aun asi, al pasar de 1280x480 a 1024x600, los numeros
 * se pisaron unos encima de otros. La causa, medida sobre aquel codigo: 57
 * medidas colgaban del ALTO de pantalla y solo 10 del ANCHO. Y lo peor no fue
 * el defecto: fue que **no habia forma de preguntarle nada**. El reparto vivia
 * suelto dentro de `onDraw`, asi que el fallo se descubrio cuando el dueño
 * mando una foto del carro.
 *
 * Este fichero existe para que eso no pueda repetirse en silencio.
 * [RepartoTablero] es codigo puro —ni un `import android.*`— y aqui se le
 * pregunta, en la JVM y en un segundo, por las tres pantallas del encargo:
 * 1280x480 (donde nacio el tablero viejo), 1024x600 (donde se rompio) y
 * 800x480 (la mas estrecha que hay que aguantar).
 *
 * ## Lo que se comprueba
 *
 * Que las cajas **no se solapan** y que **suman la pantalla**: cada nivel del
 * reparto cubre a su padre de borde a borde, con el hueco declarado entre
 * hermanas y ni un pixel de mas. Si alguien vuelve a meter un `w / 3` a mano, o
 * cambia un peso sin cuadrar la suma, o se lleva la mitad de la pantalla a una
 * seccion, esto se pone rojo antes de que el APK salga del taller.
 *
 * ## Lo que NO se comprueba, y hay que decirlo
 *
 * La tipografia. Medir texto necesita un `Paint` de verdad y
 * `unitTests.isReturnDefaultValues = true` hace que `measureText` devuelva 0 en
 * la JVM. Que un rotulo quepa dentro de su caja se ve en pantalla, no aqui.
 * Esta prueba cubre la mitad geometrica del defecto — que es la mitad que
 * decide si dos secciones pueden llegar a tocarse.
 */
class RepartoTableroTest {

    private val EPS = 0.01f

    /** Las tres del encargo. La cuarta columna es como se llama en el informe. */
    private val PANTALLAS = arrayOf(
        floatArrayOf(1280f, 480f),
        floatArrayOf(1024f, 600f),
        floatArrayOf(800f, 480f),
    )

    private fun nombre(p: FloatArray) = "${p[0].toInt()}x${p[1].toInt()}"

    /** Las cajas que se PINTAN. Las intermedias no cuentan: son andamio. */
    private fun hojas(r: RepartoTablero): List<Pair<String, Caja>> {
        val l = mutableListOf(
            "cabecera" to r.cabecera,
            "botonAverias" to r.botonAverias,
            "botonAjustes" to r.botonAjustes,
            "botonCerrar" to r.botonCerrar,
            "energia" to r.energia,
            "motor" to r.motor,
            "llantas" to r.llantas,
        )
        if (r.conNevera) l.add("nevera" to r.nevera)
        return l
    }

    private fun seSolapan(a: Caja, b: Caja): Boolean =
        a.x0 < b.x1 - EPS && b.x0 < a.x1 - EPS &&
            a.y0 < b.y1 - EPS && b.y0 < a.y1 - EPS

    private fun dentroDe(hijo: Caja, padre: Caja): Boolean =
        hijo.x0 >= padre.x0 - EPS && hijo.y0 >= padre.y0 - EPS &&
            hijo.x1 <= padre.x1 + EPS && hijo.y1 <= padre.y1 + EPS

    private fun area(c: Caja) = c.ancho * c.alto

    // --- 1 -------------------------------------------------------------------

    @Test
    fun `las tres pantallas reparten sin solaparse, en los dos carros`() {
        for (conNevera in booleanArrayOf(true, false)) {
            for (p in PANTALLAS) {
                val r = RepartoTablero()
                r.reparte(p[0], p[1], conNevera)
                val caso = "${nombre(p)} nevera=$conNevera"

                assertTrue("$caso: el tablero tendria que caber", r.valido)

                val hojas = hojas(r)
                for ((nom, c) in hojas) {
                    assertTrue("$caso: $nom sale invalida", c.valida)
                    assertTrue("$caso: $nom se sale del marco", dentroDe(c, r.marco))
                    // Por debajo de esto no se puede dibujar nada, y una caja
                    // asi seria un fallo de reparto disfrazado de tarjeta.
                    assertTrue("$caso: $nom es un hilo (${c.ancho}x${c.alto})", c.menor > 20f)
                }

                for (i in hojas.indices) {
                    for (j in i + 1 until hojas.size) {
                        assertFalse(
                            "$caso: ${hojas[i].first} pisa a ${hojas[j].first}",
                            seSolapan(hojas[i].second, hojas[j].second),
                        )
                    }
                }
            }
        }
    }

    // --- 2 -------------------------------------------------------------------

    @Test
    fun `cada nivel cubre a su padre de borde a borde`() {
        // Esta es la mitad "suman la pantalla" del encargo, dicha de la unica
        // forma que no se puede falsear: no basta con que las cajas quepan —
        // tienen que TOCARSE con el hueco declarado y clavarse en los extremos
        // del padre. Una seccion que se queda corta deja una franja muerta que
        // nadie ve hasta que se compara con la otra variante.
        val aire = RepartoTablero.AIRE
        for (conNevera in booleanArrayOf(true, false)) {
            for (p in PANTALLAS) {
                val r = RepartoTablero()
                r.reparte(p[0], p[1], conNevera)
                val caso = "${nombre(p)} nevera=$conNevera"

                filasCubren(caso, r.marco, listOf(r.bandaAlta, r.cuerpo), aire)
                columnasCubren(caso, r.bandaAlta, listOf(r.cabecera, r.botonera), aire)
                columnasCubren(
                    caso, r.botonera, listOf(r.botonAverias, r.botonAjustes, r.botonCerrar), aire,
                )
                columnasCubren(
                    caso, r.cuerpo,
                    listOf(r.columnaIzquierda, r.columnaCentro, r.columnaDerecha), aire,
                )
                if (conNevera) {
                    filasCubren(caso, r.columnaCentro, listOf(r.nevera, r.motor), aire)
                } else {
                    assertEquals("$caso: sin nevera el motor es la columna entera",
                        r.columnaCentro, r.motor)
                }
            }
        }
    }

    private fun columnasCubren(caso: String, padre: Caja, hijos: List<Caja>, aire: Float) {
        assertEquals("$caso: la primera no arranca en el padre",
            padre.x0, hijos.first().x0, EPS)
        assertEquals("$caso: la ultima no termina en el padre",
            padre.x1, hijos.last().x1, EPS)
        for (h in hijos) {
            assertEquals("$caso: una columna no llega arriba", padre.y0, h.y0, EPS)
            assertEquals("$caso: una columna no llega abajo", padre.y1, h.y1, EPS)
        }
        for (i in 1 until hijos.size) {
            assertEquals(
                "$caso: el hueco entre columnas no es el declarado",
                aire, hijos[i].x0 - hijos[i - 1].x1, EPS,
            )
        }
    }

    private fun filasCubren(caso: String, padre: Caja, hijos: List<Caja>, aire: Float) {
        assertEquals("$caso: la primera fila no arranca en el padre",
            padre.y0, hijos.first().y0, EPS)
        assertEquals("$caso: la ultima fila no termina en el padre",
            padre.y1, hijos.last().y1, EPS)
        for (h in hijos) {
            assertEquals("$caso: una fila no llega a la izquierda", padre.x0, h.x0, EPS)
            assertEquals("$caso: una fila no llega a la derecha", padre.x1, h.x1, EPS)
        }
        for (i in 1 until hijos.size) {
            assertEquals(
                "$caso: el hueco entre filas no es el declarado",
                aire, hijos[i].y0 - hijos[i - 1].y1, EPS,
            )
        }
    }

    // --- 3 -------------------------------------------------------------------

    @Test
    fun `el area de las secciones mas los huecos es el marco entero`() {
        // La misma afirmacion que la prueba 2, dicha con numeros en vez de con
        // bordes. Va aparte porque caza otra cosa: si alguien añadiera una
        // seccion nueva SIN quitarle sitio a nadie, los bordes podrian seguir
        // cuadrando por parejas y el area no.
        val aire = RepartoTablero.AIRE
        for (conNevera in booleanArrayOf(true, false)) {
            for (p in PANTALLAS) {
                val r = RepartoTablero()
                r.reparte(p[0], p[1], conNevera)
                val caso = "${nombre(p)} nevera=$conNevera"

                var huecos = aire * r.marco.ancho +          // cabecera / cuerpo
                    aire * r.bandaAlta.alto +                // cabecera / botonera
                    2f * aire * r.botonera.alto +            // tres botones
                    2f * aire * r.cuerpo.alto                // tres columnas
                if (conNevera) huecos += aire * r.columnaCentro.ancho

                val secciones = hojas(r).sumOf { area(it.second).toDouble() }.toFloat()

                assertEquals(
                    "$caso: falta o sobra superficie",
                    area(r.marco), secciones + huecos, area(r.marco) * 0.001f,
                )
            }
        }
    }

    // --- 4 -------------------------------------------------------------------

    @Test
    fun `los dos carros ponen las llantas en el mismo sitio`() {
        // No es coqueteria: `TrazadoLlantasTest` calcula por su cuenta la caja
        // que la vista le da a la seccion de llantas, y si los dos repartos no
        // sumaran lo mismo, esa prueba estaria midiendo una caja imaginaria en
        // uno de los dos sabores — verde y mintiendo, que es lo peor que puede
        // hacer una prueba.
        for (p in PANTALLAS) {
            val conNevera = RepartoTablero().also { it.reparte(p[0], p[1], true) }
            val sinNevera = RepartoTablero().also { it.reparte(p[0], p[1], false) }
            assertEquals(
                "${nombre(p)}: la columna de llantas se movio entre carros",
                conNevera.llantas, sinNevera.llantas,
            )
            // Y el resto SI cambia: el carro sin nevera no reserva sitio para
            // lo que no lleva, y su motor se lleva la columna del medio entera.
            assertNotEquals(
                "${nombre(p)}: el motor deberia repartirse distinto sin nevera",
                conNevera.motor, sinNevera.motor,
            )
            assertFalse("sin nevera no se reserva caja de nevera", sinNevera.nevera.valida)
            assertTrue("con nevera hay caja de nevera", conNevera.nevera.valida)
        }
    }

    // --- 5 -------------------------------------------------------------------

    @Test
    fun `la caja de llantas es la que la seccion de llantas da por hecha`() {
        // La misma cuenta que hace `TrazadoLlantasTest.seccionDe`, escrita aqui
        // a mano contra el reparto de verdad. Si alguien cambia los pesos de la
        // maqueta, esta prueba lo dice en vez de dejar que las dos se separen
        // sin que ninguna falle.
        for (p in PANTALLAS) {
            val marco = Caja.pantalla(p[0], p[1]).margen(p[0] * 0.0117f)
            val cuerpo = Reparto.filas(marco, floatArrayOf(44f, 542f), 10f)[1]
            val esperada = Reparto.columnas(cuerpo, floatArrayOf(396f, 264f, 320f), 10f)[2]

            val r = RepartoTablero()
            r.reparte(p[0], p[1], true)
            assertEquals("${nombre(p)}: la seccion de llantas cambio de sitio",
                esperada, r.llantas)
        }
    }

    // --- 6 -------------------------------------------------------------------

    @Test
    fun `repartir dos veces el mismo tamaño no fabrica nada`() {
        // El reparto asigna listas y Cajas. Si se hiciera por cuadro, un
        // tablero que repinta durante horas fabricaria basura sin parar — la
        // regla 7 del encargo. La prueba compara por IDENTIDAD, que es lo unico
        // que demuestra que no se rehizo.
        val r = RepartoTablero()
        r.reparte(1024f, 600f, true)
        val antes = r.motor
        val antesLl = r.llantas
        r.reparte(1024f, 600f, true)
        assertTrue("el mismo tamaño rehizo el reparto", antes === r.motor)
        assertTrue("el mismo tamaño rehizo el reparto", antesLl === r.llantas)

        // Pero un tamaño distinto SI tiene que rehacerlo, o la pantalla se
        // quedaria con la maqueta de antes de girar el radio.
        r.reparte(800f, 480f, true)
        assertFalse("un tamaño nuevo no se repartio", antes === r.motor)
    }

    // --- 7 -------------------------------------------------------------------

    @Test
    fun `una pantalla imposible se declara invalida en vez de inventarse sitio`() {
        // Un tablero que dibuja cajas del reves es peor que uno con un aspa
        // pintada: el aspa se ve y el otro se cree.
        for (mala in arrayOf(
            floatArrayOf(0f, 0f),
            floatArrayOf(40f, 30f),
            floatArrayOf(-100f, 200f),
            floatArrayOf(Float.NaN, 600f),
        )) {
            val r = RepartoTablero()
            r.reparte(mala[0], mala[1], true)
            assertFalse("${mala[0]}x${mala[1]} deberia rendirse", r.valido)
            for ((nom, c) in hojas(r)) {
                assertTrue("$nom salio con area negativa en vez de NADA",
                    c.ancho >= 0f && c.alto >= 0f)
            }
        }
    }

    // --- 8 -------------------------------------------------------------------

    @Test
    fun `ninguna seccion degenera en una tira`() {
        // Una comprobacion de FORMA, no de tamaño, y la que mas cerca esta del
        // defecto original. Ahora que la letra sale de la caja —regla 2—, la
        // forma de la caja ES la tipografia: una seccion que en una pantalla
        // sale cuadrada y en otra sale como una tira de papel no encoge, cambia
        // de cara. Los pintores estan escritos para adaptarse dentro de una
        // banda razonable (dos tarjetas al lado o apiladas, tres columnas o
        // tres filas); fuera de ella nadie ha mirado nunca como queda.
        //
        // ⚠️ La banda [0,40 · 2,50] no es una ley de la naturaleza: sale de
        // MEDIR las tres pantallas del encargo, donde lo mas extremo que se da
        // es, en el reparto sin nevera, la energia a 1024x600 (0,46) y el
        // motor a 1280x480 (1,29). Deja holgura por los dos lados. Si una
        // pantalla futura la rompe, lo que hay que revisar es el reparto, no
        // subir el numero.
        //
        // La cabecera y los dos botones quedan FUERA a proposito: son franjas
        // por diseño —una etiqueta de mapa y dos pastillas con una palabra
        // dentro— y no llevan cifras que puedan pisarse.
        for (conNevera in booleanArrayOf(true, false)) {
            for (p in PANTALLAS) {
                val r = RepartoTablero()
                r.reparte(p[0], p[1], conNevera)
                val caso = "${nombre(p)} nevera=$conNevera"

                assertTrue("$caso: la cabecera no es una franja",
                    r.cabecera.ancho > r.cabecera.alto * 4f)
                assertTrue("$caso: el boton de averias no es una pastilla",
                    r.botonAverias.ancho > r.botonAverias.alto)

                val conCifras = mutableListOf("energia" to r.energia, "motor" to r.motor,
                    "llantas" to r.llantas)
                if (conNevera) conCifras.add("nevera" to r.nevera)
                for ((nom, c) in conCifras) {
                    val forma = c.ancho / c.alto
                    assertTrue(
                        "$caso: $nom sale como una tira ($forma)",
                        forma in 0.40f..2.50f,
                    )
                }
            }
        }
    }
}
