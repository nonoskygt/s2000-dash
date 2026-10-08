package com.nonosky.carsoc.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Que puede medir este carro, dicho por el carro.
 *
 * El `0100` es la unica fuente de verdad sobre lo que soporta esta ECU, y de
 * el cuelga todo lo demas: que PIDs se piden en el reparto de turnos y cuales
 * ni se intentan. El proyecto ya pago el precio de suponerlo — se gastaron
 * turnos pidiendo el AFR de banda ancha `0134` a una centralita cuyo mapa se
 * corta en el `0x20`, y la respuesta era vacio SIEMPRE.
 *
 * La mascara se verifico una vez a mano contra el carro y luego nadie la
 * volvio a mirar. Un bit corrido al descodificar no se nota: la lista sigue
 * pareciendo una lista razonable, solo que con los PIDs equivocados. Estas
 * pruebas clavan la respuesta real, bit por bit.
 */
class PidSoportadosTest {

    /**
     * Lo que una ECU real contesto de verdad al `0100`.
     *
     * Mascara `BE 3E F8 10`. Desglosada, con el bit mas alto del primer byte
     * en el PID 0x01:
     *
     *     BE = 1011 1110 -> 01 . 03 04 05 06 07 .
     *     3E = 0011 1110 -> .  .  0B 0C 0D 0E 0F .
     *     F8 = 1111 1000 -> 11 12 13 14 15 .  .  .
     *     10 = 0001 0000 -> .  .  .  1C .  .  .  .
     */
    /**
     * Una mascara REAL: se midio preguntandole `0100` a la ECU de un carro.
     * Prueba el descodificador contra algo que de verdad salio de un carro,
     * que es mejor fixture que una cadena inventada.
     *
     * No describe a TODOS los carros: cada ECU declara la suya, y lo que una
     * no declara no se le pregunta.
     */
    private val MASCARA_MEDIDA = "4100BE3EF810"

    /** Los diecisiete PIDs que declara esa mascara medida. */
    private val PIDS_MEDIDOS = listOf(
        0x01, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
        0x11, 0x12, 0x13, 0x14, 0x15,
        0x1C,
    )

    // --- La respuesta real medida en una ECU ---------------------------------

    @Test
    fun `la mascara medida en una ECU real es BE3EF810 y son diecisiete PIDs`() {
        assertEquals(PIDS_MEDIDOS, PidDecoder.soportados(MASCARA_MEDIDA, 0x00))
    }

    @Test
    fun `la velocidad y el acelerador estan en la mascara medida`() {
        // Se afirma porque el tablero los pide en cada vuelta del reparto: si
        // un dia el descodificador dejara de sacarlos de esta mascara, la
        // pantalla se quedaria con dos huecos y nadie sabria si es la ECU o
        // el descodificador. OJO: prueba el DESCODIFICADOR, no afirma nada
        // sobre lo que tenga otra ECU.
        val lista = PidDecoder.soportados(MASCARA_MEDIDA, 0x00)
        assertTrue("el 0D (velocidad) tiene que estar", lista.contains(0x0D))
        assertTrue("el 11 (acelerador) tiene que estar", lista.contains(0x11))
        // Y los otros cuatro de los que vive la pantalla.
        assertTrue(lista.contains(0x0C))   // rpm
        assertTrue(lista.contains(0x05))   // agua
        assertTrue(lista.contains(0x0F))   // aire de admision
        assertTrue(lista.contains(0x04))   // carga
    }

    @Test
    fun `lo que este carro NO tiene, y por eso no hay que pedirlo`() {
        val lista = PidDecoder.soportados(MASCARA_MEDIDA, 0x00)
        // El complemento exacto dentro del bloque 01-20. Se enumera entero en
        // vez de mirar solo dos o tres porque un bit corrido mueve PIDs de un
        // lado al otro, y el fallo se ve justo aqui.
        val ausentes = listOf(
            0x02, 0x08, 0x09, 0x0A, 0x10,
            0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B,
            0x1D, 0x1E, 0x1F, 0x20,
        )
        for (pid in ausentes) {
            assertFalse("el %02X no lo soporta este carro".format(pid), lista.contains(pid))
        }
        // Dos que valen la pena decir en voz alta:
        // - el 0x10 (MAF) no esta y el 0x0B (MAP) si: este motor es de
        //   densidad-velocidad, no lleva caudalimetro. Pedir MAF es tirar turno.
        // - el 0x08/0x09 (banco 2) no estan porque este motor es de un solo banco.
        assertTrue(lista.contains(0x0B))
        assertFalse(lista.contains(0x10))
    }

    @Test
    fun `la mascara real dice que no hay bloque 21-40`() {
        // Este es el hallazgo que zanjo la discusion del AFR de banda ancha:
        // el mapa de esta ECU se corta en el 0x20, asi que el 0134 no es que
        // no conteste, es que no existe y nunca existio.
        assertFalse(PidDecoder.hayMasBloques(MASCARA_MEDIDA, 0x00))
    }

    // --- El bit que anuncia el bloque siguiente ------------------------------

    @Test
    fun `el bit de mas abajo es el que anuncia el bloque siguiente`() {
        // 0x00000001 = solo el ultimo bit -> solo el PID 0x20, que no es un
        // sensor sino el indice del bloque 21-40.
        assertEquals(listOf(0x20), PidDecoder.soportados("410000000001", 0x00))
        assertTrue(PidDecoder.hayMasBloques("410000000001", 0x00))

        // 0x00000002 es el bit de al lado (PID 0x1F). Si alguien confundiera
        // uno con otro, el recorrido de bloques se cortaria o se iria de largo.
        assertEquals(listOf(0x1F), PidDecoder.soportados("410000000002", 0x00))
        assertFalse(PidDecoder.hayMasBloques("410000000002", 0x00))
    }

    @Test
    fun `cada extremo de la mascara cae en el PID que le toca`() {
        // Los cuatro bits que fijan el orden sin ambiguedad: si alguien
        // invirtiera la mascara, o cambiara shr por shl, estos cuatro cambian.
        assertEquals(listOf(0x01), PidDecoder.soportados("410080000000", 0x00))
        assertEquals(listOf(0x08), PidDecoder.soportados("410001000000", 0x00))
        assertEquals(listOf(0x09), PidDecoder.soportados("410000800000", 0x00))
        assertEquals(listOf(0x20), PidDecoder.soportados("410000000001", 0x00))
    }

    // --- Fronteras ----------------------------------------------------------

    @Test
    fun `mascara a cero no soporta nada y no hay bloque siguiente`() {
        assertEquals(emptyList<Int>(), PidDecoder.soportados("410000000000", 0x00))
        assertFalse(PidDecoder.hayMasBloques("410000000000", 0x00))
    }

    @Test
    fun `mascara a FFFFFFFF soporta los treinta y dos y anuncia el siguiente`() {
        // (0x01..0x20) enteros. Vale como frontera y ademas confirma que el
        // ultimo elemento es exactamente 0x20 y no 0x21: un off-by-one aqui
        // desplazaria la lista entera un PID.
        assertEquals((0x01..0x20).toList(), PidDecoder.soportados("4100FFFFFFFF", 0x00))
        assertTrue(PidDecoder.hayMasBloques("4100FFFFFFFF", 0x00))
    }

    // --- El segundo bloque --------------------------------------------------

    @Test
    fun `el segundo bloque se pide con 0120 y responde con 4120`() {
        // Este carro no llega aqui, pero el recorrido de DashService si lo
        // intenta hasta cuatro veces y la numeracion tiene que desplazarse.
        assertEquals(listOf(0x21), PidDecoder.soportados("412080000000", 0x20))
        assertEquals(listOf(0x40), PidDecoder.soportados("412000000001", 0x20))
        assertTrue(PidDecoder.hayMasBloques("412000000001", 0x20))
        assertFalse(PidDecoder.hayMasBloques("412080000000", 0x20))
    }

    @Test
    fun `no confunde la respuesta de un bloque con la de otro`() {
        // El peor escenario del recorrido: la respuesta del bloque anterior
        // sigue en el buffer cuando ya se pregunto por el siguiente. Si colara,
        // el tablero creeria soportados PIDs del 0x21 al 0x40 que no existen.
        assertEquals(emptyList<Int>(), PidDecoder.soportados(MASCARA_MEDIDA, 0x20))
        assertEquals(emptyList<Int>(), PidDecoder.soportados("4120BE3EF810", 0x00))
        assertFalse(PidDecoder.hayMasBloques(MASCARA_MEDIDA, 0x20))
    }

    // --- Tolerancia de formato ----------------------------------------------

    @Test
    fun `tolera espacios, minusculas, eco, banner y prompt`() {
        // El `0100` es la PRIMERA peticion que se le hace a la ECU en cada
        // conexion, asi que es justo la que siempre trae delante el `BUS INIT`
        // de ISO 9141-2 y el eco del comando si `ATE0` aun no tomo efecto.
        // Rechazar cualquiera de estas formas dejaria al tablero sin mapa.
        val variantes = listOf(
            "41 00 BE 3E F8 10",
            "4100be3ef810",
            "0100\r4100BE3EF810",
            "SEARCHING...\r4100BE3EF810\r\r>",
            "BUS INIT: ...OK\r41 00 BE 3E F8 10\r\r>",
            "\r\r4100BE3EF810\r\r>",
            "0100\rSEARCHING...\r41 00 BE 3E F8 10\r\r>",
        )
        for (v in variantes) {
            assertEquals("fallo con '$v'", PIDS_MEDIDOS, PidDecoder.soportados(v, 0x00))
        }
    }

    @Test
    fun `dos tramas del mismo bloque se suman con OR, no se pisan`() {
        // Esta prueba decia antes lo contrario: que se queda con la PRIMERA
        // trama y tira el resto. Se cambio a conciencia, porque eso no es lo
        // que manda la norma: si varios modulos contestan al mismo PID de
        // indice, el conjunto soportado es el OR de TODAS las mascaras.
        //
        // Con ATH0 y una sola ECU en el bus el fallo nunca mordio. El dia que
        // se encienda ATH1 o entre otro modulo, el mapa saldria recortado y
        // sin avisar, que es el peor modo de fallar: el tablero dejaria de
        // pedir sensores que el carro si tiene y nadie veria un sintoma.
        assertEquals(
            (0x01..0x20).toList(),
            PidDecoder.soportados("4100BE3EF810\r4100FFFFFFFF\r\r>", 0x00),
        )

        // Dos mascaras disjuntas: cada una aporta su mitad y ninguna borra a
        // la otra. Quedandose con la primera saldria solo el 0x01; con la
        // ultima, solo el 0x20. El OR es el unico resultado correcto.
        assertEquals(
            listOf(0x01, 0x20),
            PidDecoder.soportados("410080000000\r410000000001\r\r>", 0x00),
        )
        // Y el bit que anuncia el bloque siguiente lo puede poner la segunda
        // trama: si se perdiera, el recorrido se cortaria antes de tiempo.
        assertTrue(PidDecoder.hayMasBloques("410080000000\r410000000001", 0x00))

        // El OR no se cruza entre bloques: una trama del 4120 no puede meter
        // bits en el mapa del 4100 por muy llena que venga.
        assertEquals(
            listOf(0x01),
            PidDecoder.soportados("410080000000\r4120FFFFFFFF\r\r>", 0x00),
        )
    }

    @Test
    fun `los bytes de relleno del final se ignoran`() {
        // Un ELM327 en modo CAN rellena la trama hasta ocho bytes. Solo los
        // cuatro primeros son la mascara.
        assertEquals(PIDS_MEDIDOS, PidDecoder.soportados("4100BE3EF8100000", 0x00))
        // Y un nibble suelto al final (respuesta cortada a mitad de byte) no
        // puede tirar los cuatro bytes que si llegaron enteros.
        assertEquals(PIDS_MEDIDOS, PidDecoder.soportados("4100BE3EF8100", 0x00))
    }

    // --- Basura -------------------------------------------------------------

    @Test
    fun `la basura nunca lanza y nunca inventa PIDs`() {
        val basura = listOf<String?>(
            null, "", "   ", "\r\n>",
            "SEARCHING...", "BUS INIT: ...OK", "NO DATA", "NODATA", "STOPPED",
            "?", "UNABLE TO CONNECT", "CAN ERROR", "DATA ERROR", "ERROR",
            "ZZZZ",
            "7F0012",                            // respuesta negativa de la ECU
            "410C1AF8",                          // la respuesta de OTRO pid
            "410D64",
            "BUS INIT: ERROR\r\r>",
            "SEARCHING...\rUNABLE TO CONNECT\r\r>",
        )
        for (s in basura) {
            assertEquals("debio ser vacio para '$s'", emptyList<Int>(), PidDecoder.soportados(s, 0x00))
            assertFalse("debio ser false para '$s'", PidDecoder.hayMasBloques(s, 0x00))
        }
    }

    @Test
    fun `una mascara corta se descarta entera, no a medias`() {
        // Media mascara es peor que ninguna: daria una lista corta con pinta
        // de buena y el tablero dejaria de pedir sensores que el carro si
        // tiene, sin ningun sintoma visible.
        assertEquals(emptyList<Int>(), PidDecoder.soportados("4100", 0x00))
        assertEquals(emptyList<Int>(), PidDecoder.soportados("4100BE", 0x00))
        assertEquals(emptyList<Int>(), PidDecoder.soportados("4100BE3E", 0x00))
        assertEquals(emptyList<Int>(), PidDecoder.soportados("4100BE3EF8", 0x00))
        // Tres bytes y medio: el medio se cae y quedan tres, que no bastan.
        assertEquals(emptyList<Int>(), PidDecoder.soportados("4100BE3EF81", 0x00))
    }

    // --- Respuesta o silencio: la trampa que ya mordio tres veces -----------
    //
    // Primero fue `Dtc.leerLista` buscando el prefijo con indexOf. Luego
    // `esFalloDeEnlace` dando por sano todo lo que no sonara a error conocido,
    // y la pantalla pinto SIN AVERIAS EN VERDE con el bus caido. Luego el
    // borrado comprobando `contains("44")`. Aqui vive la cuarta cara de lo
    // mismo: una lista vacia de PIDs que se lee como "este carro no mide
    // nada" cuando lo cierto es que nadie contesto.

    @Test
    fun `mascara a cero es una RESPUESTA, no un silencio`() {
        // El caso que obliga a separar las dos cosas: la ECU contesto y dijo
        // "no soporto nada de este bloque". La lista sale vacia igual que con
        // el silencio, pero aqui el enlace funciono y el dato es bueno.
        assertTrue(PidDecoder.huboMascara("410000000000", 0x00))
        assertEquals(emptyList<Int>(), PidDecoder.soportados("410000000000", 0x00))
        // Y la respuesta buena del carro, obviamente, tambien es respuesta.
        assertTrue(PidDecoder.huboMascara(MASCARA_MEDIDA, 0x00))
    }

    @Test
    fun `el silencio y el SEARCHING no son una respuesta`() {
        // `SEARCHING...` es literalmente lo que contesto el ELM327 con el
        // carro apagado el dia que la pantalla de averias dio el carro por
        // sano. Ninguna lista de errores lo reconocia y ninguna linea empezaba
        // por el prefijo, asi que salia vacio y el vacio se leia como
        // "ninguna averia". Aqui saldria como "ningun sensor".
        val mudos = listOf<String?>(
            null, "", "   ", "\r\n>",
            "SEARCHING...", "BUS INIT: ...OK", "NO DATA", "NODATA", "STOPPED",
            "?", "UNABLE TO CONNECT", "CAN ERROR", "DATA ERROR", "ERROR",
            "ZZZZ",
            "7F0012",                            // respuesta negativa de la ECU
            "410C1AF8",                          // la respuesta de OTRO pid
            "410D64",
            "BUS INIT: ERROR\r\r>",
            "SEARCHING...\rUNABLE TO CONNECT\r\r>",
        )
        for (s in mudos) {
            assertFalse("no hubo mascara para '$s'", PidDecoder.huboMascara(s, 0x00))
            assertEquals("debio ser vacio para '$s'", emptyList<Int>(), PidDecoder.soportados(s, 0x00))
        }
    }

    @Test
    fun `NO DATA no cuenta como mascara, aunque en los codigos de averia si cuente`() {
        // Diferencia deliberada, y esta prueba existe para que nadie "unifique"
        // las dos funciones mas adelante creyendo que arregla algo:
        //
        // - Al modo 03, una ECU sana puede callarse porque no tiene averias, y
        //   ahi `NO DATA` es una respuesta legitima.
        // - El `0100` es obligatorio para cualquier OBD-II. Si no lo contesta,
        //   el que esta mudo es el enlace, no el mapa de PIDs.
        assertFalse(PidDecoder.huboMascara("NO DATA", 0x00))
        assertTrue(Dtc.huboRespuesta("NO DATA"))
    }

    @Test
    fun `media mascara es un fallo, no una lista corta`() {
        // Con menos de cuatro bytes enteros la lista saldria corta y con pinta
        // de buena: el tablero dejaria de pedir sensores que el carro si tiene
        // y no habria un solo sintoma visible. Por eso media mascara no es
        // media respuesta, es ninguna.
        for (corta in listOf("4100", "4100BE", "4100BE3E", "4100BE3EF8", "4100BE3EF81")) {
            assertFalse("media mascara no es respuesta: '$corta'", PidDecoder.huboMascara(corta, 0x00))
            assertEquals(emptyList<Int>(), PidDecoder.soportados(corta, 0x00))
        }
        // Cuatro bytes justos si lo son, y un nibble suelto detras (respuesta
        // cortada a mitad de byte) no puede tirar los cuatro que si llegaron.
        assertTrue(PidDecoder.huboMascara("4100BE3EF810", 0x00))
        assertTrue(PidDecoder.huboMascara("4100BE3EF8100", 0x00))
    }

    @Test
    fun `la respuesta de otro bloque no vale como respuesta de este`() {
        // El recorrido pregunta bloque a bloque y la respuesta anterior puede
        // seguir en el buffer. Que no cuente como mascara del bloque nuevo es
        // lo que impide dar por leido un mapa que no se leyo.
        assertFalse(PidDecoder.huboMascara(MASCARA_MEDIDA, 0x20))
        assertFalse(PidDecoder.huboMascara("4120BE3EF810", 0x00))
        assertTrue(PidDecoder.huboMascara("4120BE3EF810", 0x20))
    }

    @Test
    fun `hayMasBloques sigue diciendo que no sin respuesta, y por eso hay que preguntar antes`() {
        // Esta prueba pedia por escrito que se cambiara el dia que se separara
        // "no hay mas" de "no se pudo leer". Ya esta separado, y se fija como
        // quedo: `hayMasBloques` NO cambia de firma —sigue contestando `false`
        // sin respuesta, que sigue siendo mentira por si sola— pero ya no es
        // lo unico que hay. Quien recorre bloques pregunta primero por
        // `huboMascara`, igual que `sinCodigos` pregunta por `huboRespuesta`
        // antes de declarar sano un carro con el que no ha hablado.
        for (mudo in listOf<String?>(null, "SEARCHING...", "NO DATA")) {
            assertFalse("hayMasBloques dice que no para '$mudo'", PidDecoder.hayMasBloques(mudo, 0x00))
            assertFalse("...y huboMascara explica por que: '$mudo'", PidDecoder.huboMascara(mudo, 0x00))
        }
        // Con respuesta de verdad, las dos coinciden y ninguna miente.
        assertTrue(PidDecoder.huboMascara("410000000001", 0x00))
        assertTrue(PidDecoder.hayMasBloques("410000000001", 0x00))
        assertTrue(PidDecoder.huboMascara("410000000000", 0x00))
        assertFalse(PidDecoder.hayMasBloques("410000000000", 0x00))
    }
}
