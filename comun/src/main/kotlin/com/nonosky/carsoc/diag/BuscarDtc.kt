package com.nonosky.carsoc.diag

import java.text.Normalizer

/**
 * El buscador de la base de codigos: por codigo ("P0730", "p07") o por
 * palabras ("caja", "sensor de oxigeno").
 *
 * Sin tildes ni mayusculas que estorben: en el radio se teclea rapido y nadie
 * va a escribir "oxígeno" con su tilde. Todas las palabras tienen que estar,
 * en cualquier orden. Lo que empieza por lo escrito va primero: quien teclea
 * "p07" busca los P07xx, no los que lo citan de pasada.
 *
 * Codigo puro, sin Android: se prueba en la JVM.
 */
object BuscarDtc {

    private val MARCAS = Regex("\\p{Mn}+")
    private val ESPACIOS = Regex("\\s+")

    fun normal(t: String): String =
        MARCAS.replace(Normalizer.normalize(t, Normalizer.Form.NFD), "").lowercase()

    fun filtrar(entradas: List<TablaDtc.Entrada>, texto: String): List<TablaDtc.Entrada> {
        val q = normal(texto.trim())
        if (q.isEmpty()) return entradas
        val palabras = q.split(ESPACIOS)
        return entradas
            .filter { e ->
                val todo = normal("${e.codigo} ${e.titulo} ${e.explicacion} ${e.causas}")
                palabras.all { todo.contains(it) }
            }
            .sortedWith(compareBy({ !normal(it.codigo).startsWith(q) }, { it.codigo }))
    }
}
