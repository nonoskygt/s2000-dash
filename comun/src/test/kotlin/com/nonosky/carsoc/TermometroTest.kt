package com.nonosky.carsoc

import com.nonosky.carsoc.Termometro.Nivel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del guardian termico.
 *
 * Cada una corresponde a una forma concreta de quemarse el radio que ya
 * ocurrio o que estuvo a punto. No son ejemplos: son las trampas.
 */
class TermometroTest {

    // --- Interpretacion de lo que hay en el archivo -------------------------

    @Test
    fun `milesimas de grado se convierten a grados`() {
        // El formato mas comun en sysfs.
        assertEquals(45, Termometro.interpretarCrudo("45000"))
    }

    @Test
    fun `grados enteros se toman tal cual`() {
        // Otras ROMs escriben el grado pelado.
        assertEquals(62, Termometro.interpretarCrudo("62"))
    }

    @Test
    fun `se admite un decimal`() {
        assertEquals(58, Termometro.interpretarCrudo("58.7"))
    }

    @Test
    fun `los saltos de linea y espacios no estorban`() {
        assertEquals(51, Termometro.interpretarCrudo("  51000 \n"))
    }

    @Test
    fun `un archivo vacio no es una lectura`() {
        assertNull(Termometro.interpretarCrudo(""))
        assertNull(Termometro.interpretarCrudo("   \n"))
    }

    @Test
    fun `texto que no es un numero no es una lectura`() {
        assertNull(Termometro.interpretarCrudo("N/A"))
    }

    /**
     * ESTE es el que importa. Una zona termica declarada pero no implementada
     * reporta 0, y creersela deja el regulador en `Fresco` para siempre
     * creyendo que midio — el mismo fallo en abierto que se estaba
     * arreglando, entrando por la puerta de atras.
     */
    @Test
    fun `un cero no cuenta como temperatura`() {
        assertNull(Termometro.interpretarCrudo("0"))
        assertNull(Termometro.interpretarCrudo("0000"))
    }

    @Test
    fun `una cifra absurda se descarta en vez de apagar el tablero`() {
        // 4.000.000 milesimas son 4000 grados. Creerselo mandaria el tablero
        // a Caliente y soltaria OBD y bateria sin motivo.
        assertNull(Termometro.interpretarCrudo("4000000"))
        assertNull(Termometro.interpretarCrudo("-45000"))
    }

    @Test
    fun `el rango plausible excluye el cero y admite lo que de verdad mide`() {
        assertFalse(Termometro.esPlausible(0))
        assertFalse(Termometro.esPlausible(-5))
        assertFalse(Termometro.esPlausible(200))
        assertTrue(Termometro.esPlausible(59))
        assertTrue(Termometro.esPlausible(78))
    }

    // --- Histeresis ---------------------------------------------------------

    @Test
    fun `fresco mientras esta por debajo del umbral tibio`() {
        assertEquals(Nivel.Fresco, Termometro.nivelPara(64, Nivel.Fresco))
    }

    @Test
    fun `sube a tibio al llegar al umbral`() {
        assertEquals(Nivel.Tibio, Termometro.nivelPara(70, Nivel.Fresco))
    }

    @Test
    fun `sube a caliente al llegar al umbral`() {
        assertEquals(Nivel.Caliente, Termometro.nivelPara(78, Nivel.Tibio))
    }

    /**
     * La franja intermedia conserva el nivel. Sin esto, un aparato oscilando
     * en el umbral encenderia y apagaria el OBD cada pocos segundos, que
     * gasta mas que dejarlo quieto en cualquiera de los dos estados.
     */
    @Test
    fun `en la franja intermedia se conserva el nivel`() {
        assertEquals(Nivel.Tibio, Termometro.nivelPara(68, Nivel.Tibio))
        assertEquals(Nivel.Fresco, Termometro.nivelPara(67, Nivel.Fresco))
    }

    @Test
    fun `de caliente se baja a tibio y no de golpe a fresco`() {
        // Bajar dos escalones de una vez devolveria el OBD justo cuando el
        // aparato todavia esta evacuando calor.
        assertEquals(Nivel.Tibio, Termometro.nivelPara(68, Nivel.Caliente))
    }

    @Test
    fun `solo se vuelve a fresco al bajar del umbral de vuelta`() {
        assertEquals(Nivel.Fresco, Termometro.nivelPara(66, Nivel.Caliente))
    }

    // --- Lo que se hace estando ciego ---------------------------------------

    /**
     * Arrancando sin haber medido nunca, el termometro se declara ciego. Es
     * el estado en el que quedo el radio nuevo, y el que antes se disfrazaba
     * de "Fresco".
     */
    @Test
    fun `de fabrica se declara ciego`() {
        assertTrue(Termometro.ciego)
        assertEquals("ninguna", Termometro.fuente)
    }

    /**
     * Ciego no repinta a 5 fps. No apaga el OBD —eso dejaria el tablero sin
     * su razon de ser— pero si recorta a la mitad el consumo que SI se
     * conoce.
     */
    @Test
    fun `ciego repinta a la mitad de ritmo`() {
        assertEquals(500L, Termometro.msEntreCuadros())
    }

    @Test
    fun `el diagnostico dice en voz alta que no esta protegiendo`() {
        val texto = Termometro.diagnostico().joinToString("\n")
        assertTrue(texto.contains("ciego: SI"))
        assertTrue(texto.contains("no esta protegiendo"))
    }
}
