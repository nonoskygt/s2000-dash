package com.nonosky.carsoc.tpms

import com.nonosky.carsoc.config.AlertasLlantas
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del decodificador TPMS.
 *
 * Los vectores no son inventados: son las cinco tramas que llegaron de verdad
 * por el CH340 a 19200 baudios, con el carro parado. Si un cambio futuro deja
 * de decodificarlas, estas pruebas lo cazan antes de que llegue al radio.
 *
 * La regla dura del modulo, igual que en PidDecoderTest: el aparato puede
 * escupir cualquier cosa y el parser NUNCA lanza. Lo que no cuadra se tira.
 */
class TpmsDecoderTest {

    // Las cinco tramas reales, por separado y con nombre.
    private val T_10 = "55AA08103E4B0092"   // id 10, A=62, B=75
    private val T_11 = "55AA08113E4C0094"   // id 11, A=62, B=76
    private val T_05 = "55AA0805005100A3"   // id 05, A=0,  B=81  <- la rara
    private val T_00 = "55AA0800404D00FA"   // id 00, A=64, B=77
    private val T_01 = "55AA0801404D00FB"   // id 01, A=64, B=77

    private val AHORA = 1_000_000L

    private fun bytes(vararg hex: String) = TpmsDecoder.hexABytes(hex.joinToString(""))

    private fun decodificar(vararg hex: String): List<TramaTpms> =
        TpmsDecoder().alimentar(bytes(*hex), ahoraMs = AHORA)

    // ========================================================================
    // SEGURO: framing y XOR. Esto esta medido, no supuesto.
    // ========================================================================

    @Test
    fun `las cinco tramas reales pasan el XOR y se decodifican`() {
        val d = TpmsDecoder()
        val tramas = d.alimentar(bytes(T_10, T_11, T_05, T_00, T_01), ahoraMs = AHORA)

        assertEquals(5, tramas.size)
        assertEquals(listOf(0x10, 0x11, 0x05, 0x00, 0x01), tramas.map { it.id })
        assertEquals(5L, d.diagnostico().tramasBuenas)
        assertEquals(0L, d.diagnostico().tramasXorMalo)
        assertEquals(0L, d.diagnostico().bytesDescartados)
    }

    @Test
    fun `cada trama real conserva sus bytes crudos intactos`() {
        // El crudo es lo unico que no es hipotesis. Si un dia la escala
        // resulta estar mal, con esto se recalcula sin volver al carro.
        val t = decodificar(T_10).single()
        assertEquals(0x10, t.id)
        assertEquals(62, t.crudoA)
        assertEquals(75, t.crudoB)
        assertEquals(0, t.crudoC)
        assertEquals(AHORA, t.recibidaMs)
    }

    @Test
    fun `el XOR se recalcula igual al reconstruir la trama en hexadecimal`() {
        // Ida y vuelta: si hex() no reprodujera el byte de XOR original, el
        // calculo del parser y el del serializador estarian en desacuerdo.
        for (real in TpmsDecoder.CAPTURA_REAL) {
            assertEquals(real, decodificar(real).single().hex())
        }
    }

    @Test
    fun `el byte de largo declarado es 8 en las cinco`() {
        // byte2 = 0x08 = la trama entera, cabecera y XOR incluidos.
        for (real in TpmsDecoder.CAPTURA_REAL) {
            assertEquals(0x08, TpmsDecoder.hexABytes(real)[2].toInt() and 0xFF)
        }
    }

    // ========================================================================
    // HIPOTESIS: escalas. Si el experimento del carro dice otra cosa, se
    // cambia la constante en Escalas Y se cambian estos numeros.
    // ========================================================================

    @Test
    fun `MEDIDO presion a media libra por unidad, contra calibrador digital`() {
        // El dueño midio las cuatro llantas con un manometro digital: 32 psi
        // delante y 31 detras. Eso es crudoA/2 exacto para 0x40 y 0x3E.
        //
        // Hubo un rodeo: se comparo antes contra la app TPMS nativa del radio,
        // que muestra 29,7 y 28,7, y se cambio la escala para cuadrar con
        // ella. Estaba mal — la descalibrada es la nativa. Una segunda
        // implementacion es una segunda opinion, no una fuente de verdad; la
        // verdad es un instrumento contra el mundo fisico.
        assertEquals(32.0f, decodificar(T_00).single().presionPsi!!, 0.001f)
        assertEquals(32.0f, decodificar(T_01).single().presionPsi!!, 0.001f)
        assertEquals(31.0f, decodificar(T_10).single().presionPsi!!, 0.001f)
        assertEquals(31.0f, decodificar(T_11).single().presionPsi!!, 0.001f)
    }

    @Test
    fun `HIPOTESIS todas las presiones reales caen dentro de lo fisicamente posible`() {
        // Este es el test que avisa si la escala esta equivocada: con psi
        // directo o kPa directo, alguna de las cinco se saldria del rango.
        for (real in TpmsDecoder.CAPTURA_REAL) {
            val t = decodificar(real).single()
            assertFalse("presion absurda en $real", t.presionFueraDeRango)
            assertFalse("temperatura absurda en $real", t.temperaturaFueraDeRango)
        }
    }

    @Test
    fun `MEDIDO temperatura con offset 50, calibrado contra la app nativa`() {
        // Estos numeros ya no son una apuesta: son los que muestra la app
        // TPMS del propio radio (com.syt.tmps) para estas MISMAS tramas,
        // leidos de su pantalla con el servicio de accesibilidad.
        //
        // Antes este test fijaba offset 40 y pasaba, porque comprobaba la
        // suposicion contra si misma. Daba 37/35 grados donde la nativa dice
        // 27/25: diez de mas en las cuatro. Una prueba solo vale lo que vale
        // su referencia, y hasta hoy no habia ninguna.
        assertEquals(25, decodificar(T_10).single().temperaturaC)
        assertEquals(26, decodificar(T_11).single().temperaturaC)
        assertEquals(27, decodificar(T_00).single().temperaturaC)
        assertEquals(27, decodificar(T_01).single().temperaturaC)
        // La trama rara sale mas caliente que las cuatro ruedas, que es lo
        // propio de una cajita en el tablero al sol.
        assertEquals(31, decodificar(T_05).single().temperaturaC)
    }

    @Test
    fun `HIPOTESIS ninguna rueda capturada da alerta con los umbrales de fabrica`() {
        // 31 y 32 psi contra la alerta de baja en 24 y la de alta en 40.
        val d = TpmsDecoder()
        d.alimentar(bytes(T_10, T_11, T_00, T_01), ahoraMs = AHORA)
        for (l in d.instantanea().ruedas.values) {
            assertNull(l.rueda.corta, AlertasLlantas.evaluar(null, l.rueda.ordinal, l.trama))
        }
    }

    @Test
    fun `una presion por debajo de la alerta de baja si la dispara`() {
        // 46 unidades = 23.0 psi, justo debajo de los 24 de fabrica.
        val t = decodificar(tramaCon(id = 0x00, a = 46, b = 77, c = 0)).single()
        assertEquals(23.0f, t.presionPsi!!, 0.001f)
        assertEquals(AlertasLlantas.Tipo.Baja, AlertasLlantas.evaluar(null, 0, t))
    }

    // ========================================================================
    // HIPOTESIS: el mapa de ruedas. Los datos NO distinguen eje de lado.
    // ========================================================================

    @Test
    fun `los cuatro ID conocidos se reparten las cuatro esquinas sin repetir`() {
        // Lo unico que los datos SI sostienen: cuatro ID distintos, cuatro
        // posiciones distintas, ninguna colision. Cual es cual lo decide el
        // experimento de desinflado, no esta prueba.
        val ids = listOf(0x00, 0x01, 0x10, 0x11)
        val ruedas = ids.map { Escalas.RUEDA_POR_ID[it] }
        assertEquals(4, ruedas.filterNotNull().toSet().size)
    }

    @Test
    fun `el mapa alternativo tambien cubre las cuatro esquinas sin repetir`() {
        // Si el experimento dice que la hipotesis buena es POR_LADO, cambiar
        // Escalas.RUEDA_POR_ID debe seguir dando un tablero coherente.
        assertEquals(4, Escalas.POR_LADO.values.toSet().size)
        assertEquals(Escalas.POR_EJE.keys, Escalas.POR_LADO.keys)
    }

    @Test
    fun `el ID 05 NO es una rueda y jamas llega al tablero`() {
        // Su byte4 es cero, y una llanta montada a cero psi sale del rin. Sea
        // lo que sea el 05, no se pinta como llanta: se guarda aparte.
        val d = TpmsDecoder()
        d.alimentar(bytes(T_05), ahoraMs = AHORA)

        val estado = d.instantanea()
        assertTrue("el 05 no debe ocupar ninguna esquina", estado.ruedas.isEmpty())
        assertNotNull(estado.otras[0x05])
        assertEquals(1, d.diagnostico().idsDesconocidos[0x05])
    }

    @Test
    fun `las cuatro ruedas se llenan y el 05 queda fuera, con las cinco juntas`() {
        val d = TpmsDecoder()
        d.alimentar(bytes(T_10, T_11, T_05, T_00, T_01), ahoraMs = AHORA)

        val estado = d.instantanea()
        assertEquals(4, estado.ruedas.size)
        assertEquals(1, estado.otras.size)
        for (r in Rueda.values()) assertNotNull("falta la $r", estado.de(r))
    }

    // ========================================================================
    // Presion cero: hueco, no reventon.
    // ========================================================================

    @Test
    fun `presion cruda cero da null y no cero psi`() {
        // Pintar "0.0 psi" haria creer en un reventon que no existe. Cero
        // significa que el receptor no tiene dato de ese sensor.
        val t = decodificar(T_05).single()
        assertNull(t.presionPsi)
        assertTrue(t.sinReporte)
        assertNull("un hueco no es un aviso de presion baja", AlertasLlantas.evaluar(null, 0, t))
    }

    @Test
    fun `una rueda conocida que reporta cero tampoco inventa un cero psi`() {
        val t = decodificar(tramaCon(id = 0x00, a = 0, b = 77, c = 0)).single()
        assertEquals(Escalas.RUEDA_POR_ID[0x00], t.rueda)
        assertNull(t.presionPsi)
        assertTrue(t.sinReporte)
        assertFalse(t.presionFueraDeRango)
    }

    // ========================================================================
    // byte6: banderas de significado desconocido.
    // ========================================================================

    @Test
    fun `las banderas se exponen crudas y en cero se dicen en cero`() {
        val t = decodificar(T_00).single()
        assertEquals(0, t.banderas)
        assertTrue(t.describirBanderas().contains("sin novedad"))
    }

    @Test
    fun `una bandera puesta se enumera sin bautizarla`() {
        // No se le inventa nombre a un bit que no se ha visto moverse en el
        // carro. Se dice que bit es y se admite que no se sabe que significa.
        val t = decodificar(tramaCon(id = 0x00, a = 64, b = 77, c = 0x09)).single()
        assertEquals(0x09, t.banderas)
        val texto = t.describirBanderas()
        assertTrue(texto.contains("bits 0,3"))
        assertTrue(texto.contains("DESCONOCIDO"))
    }

    // ========================================================================
    // Flujo real: cortado, con basura, pegado, vacio.
    // ========================================================================

    @Test
    fun `una trama partida en dos trozos se arma cuando llega el segundo`() {
        val crudo = bytes(T_10)
        val d = TpmsDecoder()

        assertTrue(d.alimentar(crudo.copyOfRange(0, 5), ahoraMs = AHORA).isEmpty())
        val salida = d.alimentar(crudo.copyOfRange(5, 8), ahoraMs = AHORA)

        assertEquals(1, salida.size)
        assertEquals(0x10, salida.single().id)
        assertEquals(0L, d.diagnostico().bytesDescartados)
    }

    @Test
    fun `una trama partida justo entre el 55 y el AA no se pierde`() {
        // El caso mas facil de romper: el trozo TERMINA en 0x55. Si ese byte
        // se descarta por no ver todavia el 0xAA, se pierde una trama de cada
        // tantas, de forma intermitente — el peor fallo de diagnosticar.
        val crudo = bytes(T_11)
        val d = TpmsDecoder()

        assertTrue(d.alimentar(crudo.copyOfRange(0, 1), ahoraMs = AHORA).isEmpty())
        assertEquals(1, d.diagnostico().bytesPendientes)

        val salida = d.alimentar(crudo.copyOfRange(1, 8), ahoraMs = AHORA)
        assertEquals(1, salida.size)
        assertEquals(0x11, salida.single().id)
        assertEquals(0L, d.diagnostico().bytesDescartados)
    }

    @Test
    fun `byte a byte, de uno en uno, salen las cinco igual`() {
        // El caso extremo del flujo serie. Si funciona asi, funciona con
        // cualquier troceado.
        val crudo = bytes(T_10, T_11, T_05, T_00, T_01)
        val d = TpmsDecoder()
        val vistas = mutableListOf<TramaTpms>()

        for (b in crudo) vistas += d.alimentar(byteArrayOf(b), ahoraMs = AHORA)

        assertEquals(5, vistas.size)
        assertEquals(listOf(0x10, 0x11, 0x05, 0x00, 0x01), vistas.map { it.id })
        assertEquals(0L, d.diagnostico().bytesDescartados)
    }

    @Test
    fun `dos tramas pegadas en el mismo trozo salen las dos`() {
        val salida = decodificar(T_00, T_01)
        assertEquals(2, salida.size)
        assertEquals(0x00, salida[0].id)
        assertEquals(0x01, salida[1].id)
    }

    @Test
    fun `las cinco pegadas de golpe salen las cinco`() {
        assertEquals(5, decodificar(T_10, T_11, T_05, T_00, T_01).size)
    }

    @Test
    fun `basura antes de la cabecera se salta y se cuenta`() {
        val d = TpmsDecoder()
        val salida = d.alimentar(bytes("DEADBEEF13", T_00), ahoraMs = AHORA)

        assertEquals(1, salida.size)
        assertEquals(0x00, salida.single().id)
        assertEquals(5L, d.diagnostico().bytesDescartados)
    }

    @Test
    fun `basura entre dos tramas no impide decodificar la segunda`() {
        val salida = decodificar(T_10, "FF00FFAA55", T_11)
        assertEquals(2, salida.size)
        assertEquals(listOf(0x10, 0x11), salida.map { it.id })
    }

    @Test
    fun `un flujo que empieza a media trama tira el trozo y toma la siguiente`() {
        // Es lo que pasa de verdad al abrir el puerto: el receptor ya venia
        // hablando y se cae en medio de una trama.
        val d = TpmsDecoder()
        val mediaTrama = bytes(T_10).copyOfRange(4, 8)  // 3E 4B 00 92
        val salida = d.alimentar(mediaTrama + bytes(T_01), ahoraMs = AHORA)

        assertEquals(1, salida.size)
        assertEquals(0x01, salida.single().id)
        assertEquals(4L, d.diagnostico().bytesDescartados)
    }

    @Test
    fun `flujo vacio no lanza y no devuelve nada`() {
        val d = TpmsDecoder()
        assertTrue(d.alimentar(ByteArray(0), ahoraMs = AHORA).isEmpty())
        assertTrue(d.alimentar(ByteArray(0), largo = 0, ahoraMs = AHORA).isEmpty())
        assertTrue(d.instantanea().ruedas.isEmpty())
        assertEquals(0L, d.diagnostico().bytesDescartados)
    }

    @Test
    fun `un largo mayor que el buffer no se sale del arreglo`() {
        // Por si quien llama pasa el largo de un buffer reutilizado.
        val d = TpmsDecoder()
        assertEquals(1, d.alimentar(bytes(T_00), largo = 9999, ahoraMs = AHORA).size)
    }

    // ========================================================================
    // XOR malo: se tira. No es opcional.
    // ========================================================================

    @Test
    fun `una trama con XOR malo se descarta entera`() {
        val d = TpmsDecoder()
        // T_00 con el ultimo byte cambiado de FA a FB.
        val salida = d.alimentar(bytes("55AA0800404D00FB"), ahoraMs = AHORA)

        assertTrue(salida.isEmpty())
        assertEquals(1L, d.diagnostico().tramasXorMalo)
        assertEquals(0L, d.diagnostico().tramasBuenas)
        assertTrue("una trama mala no puede tocar el tablero", d.instantanea().ruedas.isEmpty())
    }

    @Test
    fun `cualquiera de los 64 bits de la trama, cambiado, la tumba`() {
        // Un bit suelto es justo lo que produce una velocidad de puerto mal
        // fijada — a 9600 los bytes bailaban entre capturas y a 19200 salieron
        // identicos. Ese es el fallo que el XOR tiene que atrapar SIEMPRE.
        //
        // Se recorren los 8 bytes por 8 bits: la cabecera cae por no ser 55 AA,
        // el largo por no ser 8, y los cinco restantes por el XOR. Ninguna
        // combinacion puede producir una lectura.
        val base = bytes(T_00)
        for (pos in 0 until 8) {
            for (bit in 0 until 8) {
                val roto = base.copyOf()
                roto[pos] = (roto[pos].toInt() xor (1 shl bit)).toByte()
                val salida = TpmsDecoder().alimentar(roto, ahoraMs = AHORA)
                assertTrue("byte $pos bit $bit debio tumbar la trama", salida.isEmpty())
            }
        }
    }

    @Test
    fun `un XOR malo no desincroniza la trama buena que viene detras`() {
        // Cuando una trama se rechaza se avanza UN byte, no ocho: la cabecera
        // rechazada pudo ser un 55 AA que cayo dentro de basura, y saltar ocho
        // se comeria la trama buena de detras.
        val d = TpmsDecoder()
        val salida = d.alimentar(bytes("55AA0800000000" + "00", T_10), ahoraMs = AHORA)

        assertEquals(1, salida.size)
        assertEquals(0x10, salida.single().id)
        assertEquals(1L, d.diagnostico().tramasXorMalo)
        assertEquals(8L, d.diagnostico().bytesDescartados)
    }

    @Test
    fun `un largo declarado distinto de 8 se rechaza y se anota`() {
        // No se ha visto nunca otro largo. Se rechaza por prudencia, pero se
        // cuenta: si un dia aparece, el diagnostico lo delata en vez de que el
        // formato quede a medias en silencio.
        val d = TpmsDecoder()
        // 55 AA 0C 00 40 4D 00 + XOR correcto para ese cuerpo.
        val cuerpo = intArrayOf(0x55, 0xAA, 0x0C, 0x00, 0x40, 0x4D, 0x00)
        var x = 0
        for (v in cuerpo) x = x xor v
        val hex = (cuerpo.toList() + x).joinToString("") { "%02X".format(it) }

        assertTrue(d.alimentar(TpmsDecoder.hexABytes(hex), ahoraMs = AHORA).isEmpty())
        assertEquals(1L, d.diagnostico().tramasLargoRaro)
        assertEquals(1, d.diagnostico().largosDesconocidos[0x0C])
    }

    // ========================================================================
    // Estado acumulado y antiguedad.
    // ========================================================================

    @Test
    fun `la rueda se queda con la lectura mas nueva y su hora`() {
        // Se afirma sobre el byte CRUDO y la hora, no sobre psi: esto prueba
        // que el estado se refresca, y no debe romperse el dia que se corrija
        // una escala. Y se pregunta el mapa en vez de nombrar la esquina, para
        // que tampoco dependa de si la hipotesis buena es POR_EJE o POR_LADO.
        val esquina = Escalas.RUEDA_POR_ID[0x00]!!
        val d = TpmsDecoder()

        d.alimentar(bytes(T_00), ahoraMs = 1_000L)
        assertEquals(64, d.instantanea().de(esquina)!!.trama.crudoA)
        assertEquals(1_000L, d.instantanea().de(esquina)!!.medidaMs)

        d.alimentar(bytes(tramaCon(id = 0x00, a = 30, b = 77, c = 0)), ahoraMs = 60_000L)

        val l = d.instantanea().de(esquina)!!
        assertEquals(30, l.trama.crudoA)
        assertEquals(60_000L, l.medidaMs)
    }

    @Test
    fun `una lectura vieja se marca como rancia`() {
        val d = TpmsDecoder()
        d.alimentar(bytes(T_00), ahoraMs = 1_000L)
        val l = d.instantanea().de(Rueda.DelanteraIzquierda)!!

        assertFalse(l.rancia(1_000L + Escalas.RANCIA_TRAS_MS))
        assertTrue(l.rancia(1_000L + Escalas.RANCIA_TRAS_MS + 1))
    }

    @Test
    fun `la instantanea es una copia y no cambia a espaldas de quien la tiene`() {
        // La vista la lee desde el hilo de UI mientras el lector del CH340
        // sigue alimentando desde otro hilo.
        val d = TpmsDecoder()
        d.alimentar(bytes(T_00), ahoraMs = AHORA)
        val antes = d.instantanea()

        d.alimentar(bytes(T_01), ahoraMs = AHORA)

        assertEquals(1, antes.ruedas.size)
        assertEquals(2, d.instantanea().ruedas.size)
    }

    @Test
    fun `reiniciar tira lo que hubiera a medias`() {
        // Al reabrir el puerto, media trama vieja pegada a una nueva daria
        // basura que ademas pasaria a veces el XOR.
        val d = TpmsDecoder()
        d.alimentar(bytes(T_10).copyOfRange(0, 5), ahoraMs = AHORA)
        assertEquals(5, d.diagnostico().bytesPendientes)

        d.reiniciar()
        assertEquals(0, d.diagnostico().bytesPendientes)
        assertEquals(1, d.alimentar(bytes(T_11), ahoraMs = AHORA).size)
    }

    // ========================================================================
    // Nada de lo que llegue puede lanzar. Misma regla que PidDecoder.
    // ========================================================================

    @Test
    fun `la basura del cable nunca lanza`() {
        val basura = listOf(
            ByteArray(0),
            byteArrayOf(0x55),
            byteArrayOf(0x55.toByte(), 0xAA.toByte()),
            byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x08),
            byteArrayOf(0xAA.toByte(), 0x55),
            ByteArray(64),                      // ceros
            ByteArray(64) { 0xFF.toByte() },    // unos
            ByteArray(64) { 0x55 },             // cabeceras a medias en cadena
            ByteArray(64) { if (it % 2 == 0) 0x55 else 0xAA.toByte() },
        )
        for (b in basura) {
            val d = TpmsDecoder()
            d.alimentar(b, ahoraMs = AHORA)   // no debe lanzar
            d.diagnostico()
            d.instantanea()
        }
    }

    @Test
    fun `mil trozos aleatorios no lanzan, no acumulan memoria y cuadran las cuentas`() {
        // Semilla fija: si un dia falla, falla siempre igual y se puede depurar.
        val rnd = Random(20260824)
        val d = TpmsDecoder()
        var emitidas = 0L
        var bytesEntrados = 0L

        repeat(1000) {
            val trozo = ByteArray(rnd.nextInt(0, 40)) { rnd.nextInt(256).toByte() }
            bytesEntrados += trozo.size
            for (t in d.alimentar(trozo, ahoraMs = AHORA)) {
                emitidas++
                // Una trama al azar puede colar: son 8 bits de XOR, o sea una
                // entre 256. Lo que no puede pasar es que salga incoherente —
                // si vuelve a entrar por si sola, hex() y el parser calculan el
                // XOR igual; si no, uno de los dos esta mal y no se sabria cual.
                assertEquals(
                    1,
                    TpmsDecoder().alimentar(TpmsDecoder.hexABytes(t.hex()), ahoraMs = AHORA).size,
                )
            }
        }

        val dg = d.diagnostico()
        assertEquals(emitidas, dg.tramasBuenas)
        // Cada byte que entro esta contado: o formo parte de una trama buena, o
        // se descarto, o sigue esperando. Si la cuenta no cierra hay bytes
        // desapareciendo en silencio, que es como se pierde una trama de cada
        // tantas sin que nadie sepa por que.
        assertEquals(
            bytesEntrados,
            dg.tramasBuenas * Escalas.LARGO_TRAMA + dg.bytesDescartados + dg.bytesPendientes,
        )
        // El resto es siempre una trama a medias: la memoria no crece.
        assertTrue(dg.bytesPendientes < Escalas.LARGO_TRAMA)
    }

    @Test
    fun `basura y tramas buenas mezcladas, troceadas al azar, salen las buenas enteras`() {
        // El caso realista de un cable con ruido en un carro: basura entre
        // tramas Y las tramas partidas donde caiga la lectura BULK. Se juntan
        // los dos porque por separado ya pasaban y juntos es donde se rompe.
        //
        // A la basura se le quita el 0x55 a proposito: asi no puede arrancar
        // una cabecera falsa y el resultado es exacto y repetible. Las
        // cabeceras falsas ya se prueban aparte, con tramas construidas a mano.
        val rnd = Random(20260824)
        val esperados = mutableListOf<Int>()
        val flujo = ArrayList<Byte>()

        repeat(50) {
            for (b in ByteArray(rnd.nextInt(0, 12)) { rnd.nextInt(256).toByte() }) {
                flujo += if (b.toInt() and 0xFF == Escalas.CABECERA_1) 0x56 else b
            }
            for (real in TpmsDecoder.CAPTURA_REAL) {
                for (b in TpmsDecoder.hexABytes(real)) flujo += b
                esperados += TpmsDecoder.hexABytes(real)[Escalas.POS_ID].toInt() and 0xFF
            }
        }

        val crudo = flujo.toByteArray()
        val d = TpmsDecoder()
        val vistas = mutableListOf<Int>()
        var i = 0
        while (i < crudo.size) {
            val corte = minOf(rnd.nextInt(1, 13), crudo.size - i)
            for (t in d.alimentar(crudo.copyOfRange(i, i + corte), ahoraMs = AHORA)) vistas += t.id
            i += corte
        }

        assertEquals(250, esperados.size)
        assertEquals(esperados, vistas)
        assertEquals(0L, d.diagnostico().tramasXorMalo)
        assertEquals(250L, d.diagnostico().tramasBuenas)
        assertEquals(4, d.instantanea().ruedas.size)
    }

    @Test
    fun `una presion imposible se marca en vez de colarse como normal`() {
        // 200 unidades = 100 psi. Si esto aparece de verdad, lo que esta mal
        // es la escala, no la llanta — y hay que verlo, no esconderlo.
        val t = decodificar(tramaCon(id = 0x00, a = 200, b = 77, c = 0)).single()
        assertEquals(100.0f, t.presionPsi!!, 0.001f)
        assertTrue(t.presionFueraDeRango)
        assertNull("fuera de rango no es una alerta", AlertasLlantas.evaluar(null, 0, t))
    }

    // ========================================================================
    // Utilidad de hexadecimal, para replicar capturas por el puente HTTP.
    // ========================================================================

    @Test
    fun `el hexadecimal se pega a mano y aguanta espacios y minusculas`() {
        val esperado = bytes(T_00)
        assertTrue(esperado.contentEquals(TpmsDecoder.hexABytes("55 aa 08 00 40 4d 00 fa")))
        assertTrue(esperado.contentEquals(TpmsDecoder.hexABytes("55AA:08,00-40 4D 00 FA")))
    }

    @Test
    fun `un hexadecimal impar o vacio no lanza`() {
        assertEquals(0, TpmsDecoder.hexABytes(null).size)
        assertEquals(0, TpmsDecoder.hexABytes("").size)
        assertEquals(0, TpmsDecoder.hexABytes("   ").size)
        assertEquals(0, TpmsDecoder.hexABytes("Z").size)
        assertEquals(1, TpmsDecoder.hexABytes("55A").size)   // se cae el medio byte
    }

    @Test
    fun `la captura real guardada en el codigo sigue siendo decodificable`() {
        // Centinela: si alguien toca el framing, esto truena antes del radio.
        val d = TpmsDecoder()
        val todo = TpmsDecoder.hexABytes(TpmsDecoder.CAPTURA_REAL.joinToString(""))
        assertEquals(5, d.alimentar(todo, ahoraMs = AHORA).size)
        assertEquals(4, d.instantanea().ruedas.size)
    }

    @Test
    fun `la lista de que falta por confirmar no se queda vacia`() {
        // Mientras haya hipotesis, tiene que haber experimento escrito.
        assertTrue(COMO_CONFIRMAR.size >= 7)
        assertTrue(COMO_CONFIRMAR.all { it.isNotBlank() })
    }

    // --- Ayudante -----------------------------------------------------------

    /** Arma una trama con el XOR ya bien puesto, para probar casos que no se capturaron. */
    private fun tramaCon(id: Int, a: Int, b: Int, c: Int): String {
        val cuerpo = intArrayOf(
            Escalas.CABECERA_1, Escalas.CABECERA_2, Escalas.LARGO_TRAMA, id, a, b, c,
        )
        var x = 0
        for (v in cuerpo) x = x xor v
        return (cuerpo.toList() + x).joinToString("") { "%02X".format(it) }
    }
}
