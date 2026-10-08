package com.nonosky.carsoc

import com.nonosky.carsoc.ui.lienzo.DatosTablero
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EL CONTRATO ENTRE LAS DOS VARIANTES, sujeto por una prueba.
 *
 * La variante HTML lee el JSON que sale de aqui; la de Canvas lee el
 * [DatosTablero] del que ese JSON se serializa. Es la misma lectura, y ese es
 * justo el punto: dos pantallas que se leen los sensores por su cuenta empiezan
 * iguales y acaban contradiciendose, y entonces no hay forma de saber cual
 * miente.
 *
 * Lo que sujeta esta prueba es la **junta**: que el JSON siga teniendo las
 * mismas claves, en el mismo orden, y que ningun campo nuevo del contrato se
 * quede sin salir por el. El dia que alguien añada un dato a [DatosTablero] y
 * se olvide del serializador, el tablero HTML pintaria un hueco para siempre y
 * en silencio —el `<script>` leeria `undefined` y pintaria "--"— que es
 * exactamente la clase de fallo que este proyecto no puede permitirse: parece
 * un dato que falta, no un programa que falla.
 *
 * `aJson` es codigo puro, asi que esto corre en la JVM sin encender un radio.
 * `leer()` no: toca `EstadoActual`, y eso ya es Android.
 */
class EstadoDelTableroTest {

    /**
     * Las claves del JSON, en orden, sacadas del propio texto.
     *
     * Se leen con una expresion tonta a proposito: si el serializador se
     * volviera listo y empezara a omitir claves nulas, esta prueba lo veria.
     */
    private fun clavesDe(json: String): List<String> =
        Regex("\"([a-zA-Z0-9]+)\":").findAll(json).map { it.groupValues[1] }.toList()

    // --- 1 -------------------------------------------------------------------

    @Test
    fun `el JSON lleva las mismas claves y en el mismo orden que antes`() {
        // Esta lista NO se genera: esta copiada del puente que ya funcionaba en
        // el carro, tal cual, incluidos el orden y los nombres. El tablero HTML
        // lleva meses leyendo estos nombres; cambiarlos sin darse cuenta es
        // dejar la pantalla en blanco a 80 km/h.
        val esperadas = listOf(
            "vivSoc", "vivV", "vivA", "vivW", "vivT", "vivH", "vivNom", "vivMac",
            "arrNom", "arrMac",
            "dcdc", "inversorW",
            "arrSoc", "arrV", "arrA", "arrW", "arrT",
            "nevT", "nevSet", "nevV", "nevEco", "nevOn", "nevComp", "nevMin", "nevMax",
            "agua", "rpm", "aire", "carga", "avance", "mapPsi", "trim", "afr", "vtec",
            "rpmMax", "rpmVtec", "rpmCambio", "rpmRoja", "rpmCorte",
            "ll0psi", "ll0t", "ll0baja", "ll0al",
            "ll1psi", "ll1t", "ll1baja", "ll1al",
            "ll2psi", "ll2t", "ll2baja", "ll2al",
            "ll3psi", "ll3t", "ll3baja", "ll3al",
            "acePct", "aceKm", "aceH", "radioC",
            "mil", "codigos",
            "okViv", "okArr", "okNev", "okTpms", "okObd",
        )
        assertEquals(esperadas, clavesDe(EstadoDelTablero.aJson(DatosTablero.VACIO)))
    }

    // --- 2 -------------------------------------------------------------------

    @Test
    fun `ningun campo del contrato se queda fuera del JSON`() {
        // La red de verdad: no compara contra una lista escrita a mano, sino
        // contra los campos que EXISTEN. Si mañana entra un dato nuevo en
        // DatosTablero y nadie lo mete en el serializador, la variante Canvas
        // lo pintaria y la de HTML no — y las dos pantallas dirian cosas
        // distintas del mismo carro.
        val campos = DatosTablero::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
        val claves = clavesDe(EstadoDelTablero.aJson(DatosTablero.VACIO)).toSet()

        for (c in campos) {
            assertTrue("el campo '$c' del contrato no sale en el JSON", claves.contains(c))
        }
        assertEquals("hay claves en el JSON que no son campos del contrato",
            campos.size, claves.size)
    }

    // --- 3 -------------------------------------------------------------------

    @Test
    fun `un hueco se escribe null y nunca cero`() {
        // La regla dura del proyecto, dicha en el formato de salida. Un cero y
        // un "no lo se" significan cosas opuestas —"0.0 V" es una bateria
        // muerta— y confundirlos ya costo caro aqui una vez.
        val json = EstadoDelTablero.aJson(DatosTablero.VACIO)
        assertTrue("el tablero vacio deberia ser todo null", !json.contains(":0"))
        assertTrue(json.contains("\"vivV\":null"))
        assertTrue(json.contains("\"agua\":null"))
        assertTrue(json.contains("\"okObd\":null"))
    }

    // --- 4 -------------------------------------------------------------------

    @Test
    fun `los numeros y los textos se escriben como los espera el tablero HTML`() {
        val d = DatosTablero(
            vivSoc = 84, vivV = 13.2f, vivW = -320, vivNom = "Elementos 300AH",
            mapPsi = 4.35f, ll0baja = false, ll3baja = true, ll3al = "baja",
            mil = true, codigos = 2, okTpms = true,
        )
        val json = EstadoDelTablero.aJson(d)

        // Enteros sin decimales, decimales con punto. Ojo con el punto: si
        // alguien mete un String.format aqui, en un radio en español "13.2" se
        // convierte en "13,2" y JSON.parse revienta la pantalla entera.
        assertTrue(json, json.contains("\"vivSoc\":84"))
        assertTrue(json, json.contains("\"vivV\":13.2"))
        assertTrue(json, json.contains("\"vivW\":-320"))
        assertTrue(json, json.contains("\"mapPsi\":4.35"))
        // Los textos entre comillas; los booleanos sin ellas.
        assertTrue(json, json.contains("\"vivNom\":\"Elementos 300AH\""))
        assertTrue(json, json.contains("\"ll0baja\":false"))
        assertTrue(json, json.contains("\"ll3baja\":true"))
        assertTrue(json, json.contains("\"ll3al\":\"baja\""))
        assertTrue(json, json.contains("\"ll0al\":null"))
        assertTrue(json, json.contains("\"mil\":true"))
        assertTrue(json, json.contains("\"codigos\":2"))
    }

    // --- 5 -------------------------------------------------------------------

    @Test
    fun `una comilla en el nombre de un aparato no rompe el JSON`() {
        // Los rotulos vienen del BMS y de la nevera, o sea de fuera. Un aparato
        // que se llame `Banco "grande"` partiria el JSON en dos y dejaria el
        // tablero congelado en la ultima lectura buena — pareciendo que el
        // carro sigue ahi.
        val json = EstadoDelTablero.aJson(DatosTablero(vivNom = "Banco \"grande\""))
        assertTrue(json, json.contains("\"vivNom\":\"Banco 'grande'\""))
        assertEquals("el escape dejo comillas sueltas",
            0, Regex("\"vivNom\":\"[^\"]*\"[^,}]").findAll(json).count())
    }
}
