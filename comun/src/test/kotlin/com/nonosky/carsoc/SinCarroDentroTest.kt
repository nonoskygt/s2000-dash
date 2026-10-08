package com.nonosky.carsoc

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LA LIBRERIA COMUN NO SABE EN QUE CARRO CORRE. Esta prueba lo vigila.
 *
 * Lo que es de un carro va en la app de ese carro y entra por [Carro]. Aqui
 * no puede aparecer el nombre del carro, su motor, su chasis ni una MAC: ni
 * en una cadena del codigo ni en un comentario. Asi se colaron antes las
 * cosas de un carro en otro: una app llamaba al adaptador OBD de otro carro
 * porque su MAC estaba escrita aqui.
 */
class SinCarroDentroTest {

    /** Nombres de carro, motor y chasis. Las MARCAS de aparatos (Alpicool, JBD) no. */
    private val carros = Regex("""(?i)s2000|f20c|\bap[12]\b""")

    /** Una MAC escrita a mano. */
    private val mac = Regex("""([0-9A-F]{2}:){5}[0-9A-F]{2}""")

    @Test
    fun ningunaCadenaDelCodigoNombraUnCarroNiTraeUnaMac() {
        val raiz = File("src/main/kotlin")
        assertTrue("no encuentro el codigo en ${raiz.absolutePath}", raiz.isDirectory)
        val culpables = raiz.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            cadenas(sinComentarios(f.readText()))
                .filter { carros.containsMatchIn(it) || mac.containsMatchIn(it) }
                .map { "${f.name}: \"$it\"" }
        }.toList()
        assertTrue(
            "Estas cadenas nombran un carro o traen una MAC dentro de :comun. Muevelas " +
                "a la app del carro y que entren por Carro:\n" + culpables.joinToString("\n"),
            culpables.isEmpty(),
        )
    }

    @Test
    fun niLosComentariosNombranUnCarro() {
        val raiz = File("src/main")
        assertTrue("no encuentro el codigo en ${raiz.absolutePath}", raiz.isDirectory)
        val culpables = raiz.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "xml", "html", "txt") }
            .flatMap { f ->
                f.readLines().withIndex()
                    .filter { carros.containsMatchIn(it.value) }
                    .map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
            }.toList()
        assertTrue("Esto nombra un carro dentro de :comun:\n" + culpables.joinToString("\n"),
            culpables.isEmpty())
    }

    private fun sinComentarios(t: String): String =
        t.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .lines().joinToString("\n") { linea ->
                // Un // dentro de una cadena ("http://") no es comentario.
                var dentro = false
                var i = 0
                var corte = linea.length
                while (i < linea.length) {
                    val c = linea[i]
                    if (c == '\\' && dentro) {
                        i += 2
                        continue
                    }
                    if (c == '"') dentro = !dentro
                    if (!dentro && c == '/' && i + 1 < linea.length && linea[i + 1] == '/') {
                        corte = i
                        break
                    }
                    i++
                }
                linea.substring(0, corte)
            }

    private fun cadenas(t: String): Sequence<String> =
        Regex(""""((?:[^"\\\n]|\\.)*)"""").findAll(t).map { it.groupValues[1] }
}
