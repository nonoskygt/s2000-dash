package com.nonosky.s2000dash

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ningun aparato (bateria, nevera, adaptador OBD) escrito en el codigo de
 * esta app: se eligen en el menu de emparejamiento. Si el dueño cambia de
 * adaptador, elige el nuevo y funciona; una MAC de fabrica aqui es como una
 * app acabo llamando durante meses a un adaptador que no era el suyo.
 */
class SinAparatosEnElCodigoTest {

    private val mac = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

    @Test
    fun ningunaMacEnElCodigo() {
        val raiz = File("src/main/kotlin")
        assertTrue("no encuentro el codigo en ${raiz.absolutePath}", raiz.isDirectory)
        val culpables = raiz.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().withIndex()
                .filter { (_, l) -> !l.trimStart().startsWith("*") && !l.trimStart().startsWith("//") }
                .filter { (_, l) -> mac.containsMatchIn(l) }
                .map { (i, l) -> "${f.name}:${i + 1}: ${l.trim()}" }
        }.toList()
        assertTrue("MAC escritas en el codigo:\n" + culpables.joinToString("\n"), culpables.isEmpty())
    }
}
