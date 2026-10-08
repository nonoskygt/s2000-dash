package com.nonosky.carsoc.ui.lienzo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las pruebas del repartidor de cajas.
 *
 * Existen por una razon concreta y documentada: el tablero Canvas viejo se
 * rompio al cambiar de pantalla —los numeros se pisaban a 1024x600— y **nadie
 * pudo verlo hasta que llego una foto desde el carro**. La geometria del
 * reparto es codigo puro, sin Android, y por tanto se puede comprobar aqui,
 * en la JVM, en un segundo y antes de compilar un APK.
 *
 * Lo que estas pruebas SI cubren: que los pesos reparten el largo entero, que
 * los huecos se restan una sola vez por junta, que subdividir no pierde
 * pixeles y que un reparto imposible se detecta en vez de devolver cajas del
 * reves.
 *
 * Lo que NO cubren, y hay que decirlo: la tipografia. Medir texto necesita un
 * `Paint` de verdad y aqui el android.jar es un maniqui que devuelve cero. El
 * encogido de [Pincel] se verifica en pantalla, no aqui.
 */
class RepartoTest {

    /** Media decima de pixel. Por debajo de esto no hay nada que ver. */
    private val EPS = 0.01f

    // --- 1 -------------------------------------------------------------------

    @Test
    fun `los pesos reparten el largo entero y en proporcion`() {
        // Los tres anchos de la fila 1 del tablero HTML, tal cual, en la
        // caja para la que se dibujaron: 396+264+320 = 980.
        val caja = Caja.pantalla(980f, 600f)
        val cols = Reparto.columnas(caja, floatArrayOf(396f, 264f, 320f))

        assertEquals(3, cols.size)
        assertEquals(396f, cols[0].ancho, EPS)
        assertEquals(264f, cols[1].ancho, EPS)
        assertEquals(320f, cols[2].ancho, EPS)

        // Y suman EXACTAMENTE el ancho dado, no "casi".
        assertEquals(caja.ancho, cols.sumOf { it.ancho.toDouble() }.toFloat(), EPS)

        // Los mismos pesos en OTRA caja dan otros anchos, en la misma
        // proporcion. Eso es lo que hace que el reparto sea responsive y no
        // solo "relativo": los pesos no llevan pixeles dentro.
        val doble = Reparto.columnas(Caja.pantalla(1960f, 600f), floatArrayOf(396f, 264f, 320f))
        assertEquals(792f, doble[0].ancho, EPS)
        assertEquals(528f, doble[1].ancho, EPS)
        assertEquals(640f, doble[2].ancho, EPS)

        // Los extremos se clavan: sin esto queda una rendija de fondo
        // asomando por el canto, que de reojo se lee como un fallo de pintado.
        assertEquals(caja.x0, cols.first().x0, 0f)
        assertEquals(caja.x1, cols.last().x1, 0f)
    }

    // --- 2 -------------------------------------------------------------------

    @Test
    fun `los huecos se restan una vez por junta, no una por caja`() {
        val caja = Caja.pantalla(1000f, 600f)
        val hueco = 10f
        val cols = Reparto.columnas(caja, floatArrayOf(1f, 1f, 1f, 1f), hueco)

        // 4 cajas => 3 juntas => 30 px de aire, 970 repartidos.
        val util = cols.sumOf { it.ancho.toDouble() }.toFloat()
        assertEquals(1000f - 3 * hueco, util, EPS)
        for (c in cols) assertEquals(970f / 4f, c.ancho, EPS)

        // Y el aire esta ENTRE ellas, con el ancho pedido.
        for (i in 0 until cols.size - 1) {
            assertEquals(hueco, cols[i + 1].x0 - cols[i].x1, EPS)
        }
    }

    // --- 3 -------------------------------------------------------------------

    @Test
    fun `las cajas van en orden, sin solaparse ni dejar rendija`() {
        val caja = Caja(20f, 40f, 1020f, 640f)
        val filas = Reparto.filas(caja, floatArrayOf(44f, 296f, 216f), hueco = 10f)

        var anterior = filas[0]
        assertTrue(anterior.valida)
        for (i in 1 until filas.size) {
            val actual = filas[i]
            assertTrue(actual.valida)
            // Solaparse es el defecto que rompio el tablero viejo. Aqui es
            // imposible por construccion, y esta prueba lo sostiene.
            assertTrue("la fila $i empieza antes de que acabe la anterior",
                actual.y0 >= anterior.y1 - EPS)
            assertEquals(10f, actual.y0 - anterior.y1, EPS)
            anterior = actual
        }
        assertEquals(caja.y0, filas.first().y0, 0f)
        assertEquals(caja.y1, filas.last().y1, 0f)
        // El ancho no se toca al repartir filas.
        for (f in filas) {
            assertEquals(caja.x0, f.x0, 0f)
            assertEquals(caja.x1, f.x1, 0f)
        }
    }

    // --- 4 -------------------------------------------------------------------

    @Test
    fun `subdividir dos veces no pierde pixeles`() {
        val pantalla = Caja.pantalla(1024f, 600f)
        val filas = Reparto.filas(pantalla, floatArrayOf(44f, 296f, 216f), hueco = 10f)
        val cols = Reparto.columnas(filas[1], floatArrayOf(396f, 264f, 320f), hueco = 10f)
        val dentro = Reparto.filas(cols[1], floatArrayOf(1f, 2f, 1f), hueco = 6f)

        // El nieto empieza y acaba EXACTAMENTE donde su padre.
        assertEquals(cols[1].y0, dentro.first().y0, 0f)
        assertEquals(cols[1].y1, dentro.last().y1, 0f)
        assertEquals(filas[1].y0, cols[1].y0, 0f)
        assertEquals(filas[1].y1, cols[1].y1, 0f)

        // Y el area se conserva en cada nivel: lo repartido mas el aire es
        // exactamente lo que habia. Tres niveles de division sin fugas.
        assertEquals(
            filas[1].ancho,
            cols.sumOf { it.ancho.toDouble() }.toFloat() + 2 * 10f,
            EPS,
        )
        assertEquals(
            cols[1].alto,
            dentro.sumOf { it.alto.toDouble() }.toFloat() + 2 * 6f,
            EPS,
        )
        // Los pesos 1:2:1 se respetan tras dos subdivisiones.
        assertEquals(dentro[0].alto * 2f, dentro[1].alto, EPS)
        assertEquals(dentro[0].alto, dentro[2].alto, EPS)
    }

    // --- 5 -------------------------------------------------------------------

    @Test
    fun `una caja imposible se detecta y no devuelve cajas del reves`() {
        val estrecha = Caja(0f, 0f, 100f, 40f)

        // Cuatro columnas con 40 px de aire entre ellas: 120 de hueco en 100
        // de ancho. No cabe, y lo honesto es decirlo.
        assertFalse(Reparto.cabe(estrecha.ancho, 4, 40f))
        val cols = Reparto.columnas(estrecha, floatArrayOf(1f, 1f, 1f, 1f), hueco = 40f)
        assertEquals(4, cols.size)
        for (c in cols) {
            assertFalse("un reparto imposible no puede devolver cajas pintables", c.valida)
            // Ni negativas: una caja de ancho negativo se pinta al reves y
            // acaba encima de la vecina sin que nada avise.
            assertTrue(c.ancho >= 0f)
            assertEquals(Caja.NADA, c)
        }

        // Una caja de area cero tampoco es pintable.
        assertFalse(Caja(10f, 10f, 10f, 50f).valida)
        assertFalse(Caja(10f, 10f, 50f, 10f).valida)
        // Ni una del reves.
        assertFalse(Caja(50f, 10f, 10f, 50f).valida)
        // Ni una con coordenadas que no son numeros.
        assertFalse(Caja(0f, 0f, Float.NaN, 50f).valida)

        // Repartir sobre algo invalido devuelve NADA, no una excepcion: esto
        // corre mientras alguien maneja y un tablero que se cae es peor que
        // un tablero con un aspa pintada.
        val deNada = Reparto.filas(Caja.NADA, floatArrayOf(1f, 1f))
        assertEquals(listOf(Caja.NADA, Caja.NADA), deNada)
    }

    // --- 6 -------------------------------------------------------------------

    @Test
    fun `el tablero entero sale bien en las tres pantallas del encargo`() {
        // 1280x480 es donde nacio el tablero viejo; 1024x600 el del otro radio
        // —donde se rompio—; 800x480 el peor caso barato. Se verifica, no se
        // supone.
        for ((ancho, alto) in listOf(1280f to 480f, 1024f to 600f, 800f to 480f)) {
            val pantalla = Caja.pantalla(ancho, alto)
            // Margen y huecos en fraccion de la pantalla: en 800x480 un
            // margen fijo de 12 px se come proporcionalmente mas sitio.
            val aire = alto * 0.0167f
            val marco = pantalla.margen(alto * 0.02f)
            assertTrue("$ancho x $alto: no cabe ni el marco", marco.valida)

            val filas = Reparto.filas(marco, floatArrayOf(44f, 296f, 216f), aire)
            val fila1 = Reparto.columnas(filas[1], floatArrayOf(396f, 264f, 320f), aire)
            val fila2 = Reparto.columnas(filas[2], floatArrayOf(500f, 220f, 260f), aire)

            for (c in filas + fila1 + fila2) {
                assertTrue("$ancho x $alto: caja invalida $c", c.valida)
                assertTrue("$ancho x $alto: $c se sale por la izquierda", c.x0 >= -EPS)
                assertTrue("$ancho x $alto: $c se sale por arriba", c.y0 >= -EPS)
                assertTrue("$ancho x $alto: $c se sale por la derecha", c.x1 <= ancho + EPS)
                assertTrue("$ancho x $alto: $c se sale por abajo", c.y1 <= alto + EPS)
            }

            // Las cuatro llantas, en su rejilla, tambien tienen que caber.
            val ruedas = Reparto.rejilla(fila1[2], filas = 2, columnas = 2, hueco = aire)
            assertEquals(4, ruedas.size)
            for (r in ruedas) assertTrue("$ancho x $alto: rueda invalida $r", r.valida)

            // Y la relacion de aspecto de un bloque no puede dispararse: es
            // justo lo que descuadro al tablero viejo. Con la letra derivada
            // de la caja no seria fatal, pero si es la señal de que el
            // reparto quedo raro para esa pantalla.
            val motor = fila2[0]
            assertTrue(
                "$ancho x $alto: el bloque de motor sale desproporcionado " +
                    "(${motor.ancho} x ${motor.alto})",
                motor.ancho / motor.alto in 1.0f..6.0f,
            )
        }
    }

    // --- 7 -------------------------------------------------------------------

    @Test
    fun `las filas hacen en vertical exactamente lo mismo que las columnas`() {
        val caja = Caja(0f, 0f, 300f, 900f)
        val filas = Reparto.filas(caja, floatArrayOf(1f, 2f, 3f), hueco = 12f)

        val util = 900f - 24f
        assertEquals(util / 6f, filas[0].alto, EPS)
        assertEquals(util / 3f, filas[1].alto, EPS)
        assertEquals(util / 2f, filas[2].alto, EPS)
        assertEquals(util, filas.sumOf { it.alto.toDouble() }.toFloat(), EPS)

        // Iguales por atajo: mismo resultado que pesos todos a uno.
        val porAtajo = Reparto.filasIguales(caja, 3, 12f)
        val porPesos = Reparto.filas(caja, floatArrayOf(1f, 1f, 1f), 12f)
        assertEquals(porPesos, porAtajo)
    }

    // --- 8 -------------------------------------------------------------------

    @Test
    fun `un peso muerto se queda sin caja pero no arrastra a los demas`() {
        val caja = Caja.pantalla(600f, 100f)
        // La del medio con peso cero: reservada, pero hoy sin contenido.
        val cols = Reparto.columnas(caja, floatArrayOf(1f, 0f, 1f))

        assertTrue(cols[0].valida)
        assertFalse("un peso cero no puede dar una caja pintable", cols[1].valida)
        assertTrue(cols[2].valida)
        assertEquals(300f, cols[0].ancho, EPS)
        assertEquals(0f, cols[1].ancho, EPS)
        assertEquals(300f, cols[2].ancho, EPS)
        // Sigue cubriendose el ancho entero.
        assertEquals(caja.x1, cols.last().x1, 0f)

        // Un peso negativo cuenta como cero, no resta sitio a los vecinos.
        val conNegativo = Reparto.columnas(caja, floatArrayOf(1f, -5f, 1f))
        assertEquals(300f, conNegativo[0].ancho, EPS)
        assertEquals(300f, conNegativo[2].ancho, EPS)

        // Todos los pesos muertos: no hay nada que repartir, y se dice.
        val nada = Reparto.columnas(caja, floatArrayOf(0f, 0f))
        for (c in nada) assertFalse(c.valida)
    }

    // --- 9 -------------------------------------------------------------------

    @Test
    fun `la rejilla de las llantas sale en orden de lectura`() {
        val caja = Caja.pantalla(400f, 400f)
        val r = Reparto.rejilla(caja, filas = 2, columnas = 2, hueco = 8f)

        assertEquals(4, r.size)
        // La POSICION es el dato: arriba-izquierda es la delantera izquierda.
        // Si el orden se cambia sin querer, el tablero avisa de la rueda
        // equivocada — y eso es peor que no avisar.
        assertTrue("0 debe ser la de arriba a la izquierda", r[0].x0 < r[1].x0 && r[0].y0 < r[2].y0)
        assertTrue("1 debe ser la de arriba a la derecha", r[1].y0 == r[0].y0)
        assertTrue("2 debe ser la de abajo a la izquierda", r[2].x0 == r[0].x0)
        assertTrue("3 debe ser la de abajo a la derecha", r[3].x0 == r[1].x0 && r[3].y0 == r[2].y0)
        for (c in r) assertTrue(c.valida)

        // Un hueco absurdo no se cuela: se detecta antes de pintar.
        val imposible = Reparto.rejilla(caja, filas = 2, columnas = 2, hueco = 500f)
        for (c in imposible) assertFalse(c.valida)
    }

    // --- 10 ------------------------------------------------------------------

    @Test
    fun `los helpers de la caja no inventan sitio`() {
        val caja = Caja(100f, 200f, 400f, 340f)   // 300 x 140, la del encargo
        assertEquals(300f, caja.ancho, EPS)
        assertEquals(140f, caja.alto, EPS)
        assertEquals(140f, caja.menor, EPS)
        assertEquals(250f, caja.cx, EPS)
        assertEquals(270f, caja.cy, EPS)
        assertTrue(caja.contiene(250f, 270f))
        assertFalse(caja.contiene(250f, 341f))

        // Un margen mas grande que la caja la deja invalida, no del reves con
        // pinta de pintable.
        assertFalse(caja.margen(200f).valida)
        assertTrue(caja.margen(10f).valida)
        assertEquals(280f, caja.margen(10f).ancho, EPS)

        // Las subcajas por fraccion caen dentro del padre.
        val mitad = caja.sub(0f, 0f, 0.5f, 1f)
        assertEquals(caja.x0, mitad.x0, EPS)
        assertEquals(caja.cx, mitad.x1, EPS)
        assertEquals(caja.alto, mitad.alto, EPS)

        // Banda y resto encajan sin pisarse.
        val banda = caja.bandaSuperior(20f)
        val resto = caja.bajo(20f)
        assertEquals(banda.y1, resto.y0, EPS)
        assertEquals(caja.alto, banda.alto + resto.alto, EPS)

        // El azucar de la Caja da lo mismo que el Reparto.
        assertEquals(
            Reparto.columnas(caja, floatArrayOf(2f, 1f), 4f),
            caja.columnas(2f, 1f, hueco = 4f),
        )
    }
}
