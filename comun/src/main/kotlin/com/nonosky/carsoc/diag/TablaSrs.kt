package com.nonosky.carsoc.diag

import android.content.Context
import com.nonosky.carsoc.Carro

/**
 * Los codigos de la BOLSA DE AIRE (SRS) de este carro, en palabras simples.
 *
 * ## Por que es una tabla aparte de [TablaDtc]
 *
 * Porque la computadora de las bolsas de aire NO es la del motor. Los modos
 * 03, 07 y 0A del OBD-II genérico solo le preguntan a la del motor; la luz
 * SRS la enciende OTRA unidad, que en este carro habla un protocolo propio de
 * Honda. Mezclar las dos tablas haria creer que "LEER CODIGOS" tambien mira
 * las bolsas de aire, y no las mira: un "SIN AVERIAS" ahi con la luz SRS
 * encendida seria la peor mentira posible de este tablero.
 *
 * ## El formato de `res/raw/srs.txt`
 *
 *     # comentario
 *     > un paso del procedimiento para leer el codigo con la luz
 *     ! una advertencia de seguridad
 *     parpadeo<TAB>hds<TAB>gravedad<TAB>componente<TAB>titulo<TAB>explicacion<TAB>que revisar
 *
 * Se carga al abrir la pantalla y se suelta al cerrarla, como [TablaDtc].
 */
object TablaSrs {

    data class Codigo(
        /**
         * Lo que se cuenta con la luz: "1-1", o varios separados por coma
         * cuando significan lo mismo ("5-1,5-2,..."). Vacio si no tiene.
         */
        val parpadeo: String,
        /** El codigo que da el HDS de Honda: "11-01"... Vacio si no se sabe. */
        val hds: String,
        val gravedad: TablaDtc.Gravedad,
        val componente: String,
        val titulo: String,
        val explicacion: String,
        val queRevisar: String,
    ) {
        val parpadeos: List<String>
            get() = parpadeo.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    data class Tabla(
        val pasos: List<String>,
        val advertencias: List<String>,
        val codigos: List<Codigo>,
    ) {
        val vacia: Boolean get() = codigos.isEmpty()

        /**
         * Busca por lo que el dueño conto o leyo: "9-3", "9 3", "93", o solo
         * el principal "9" (devuelve todos los 9-x). Con un solo numero se
         * aceptan las dos lecturas —"11" es el 1-1 o cualquier 11-x— y se
         * enseñan las dos: adivinar cual quiso decir seria peor que mostrar
         * ambas.
         */
        fun buscar(texto: String): List<Codigo> {
            val partes = texto.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }
            if (partes.isEmpty()) return emptyList()
            return codigos.filter { c ->
                c.parpadeos.any { coincide(it, partes) } ||
                    (c.hds.isNotEmpty() && coincide(c.hds, partes))
            }
        }
    }

    /** "9-3" contra lo escrito. Compara como numeros: "11-01" es el 11-1. */
    private fun coincide(codigo: String, partes: List<String>): Boolean {
        val pri = codigo.substringBefore('-').trim()
        val sub = codigo.substringAfter('-', "").trim()
        fun igual(a: String, b: String) = a.toIntOrNull() != null && a.toIntOrNull() == b.toIntOrNull()
        return if (partes.size == 1) {
            val q = partes[0]
            igual(pri, q) || (sub.isNotEmpty() && (pri + sub) == q) ||
                (sub.isNotEmpty() && (pri + sub.padStart(2, '0')) == q)
        } else {
            igual(pri, partes[0]) && igual(sub, partes[1])
        }
    }

    @Volatile
    private var tabla: Tabla? = null

    fun cargar(context: Context): Tabla {
        tabla?.let { return it }
        val id = Carro.perfil.tablaSrs
        val t = if (id == null) Tabla(emptyList(), emptyList(), emptyList()) else runCatching {
            context.resources.openRawResource(id).bufferedReader().useLines { parsear(it) }
        }.getOrElse { Tabla(emptyList(), emptyList(), emptyList()) }
        tabla = t
        return t
    }

    fun soltar() {
        tabla = null
    }

    /** El parser, aparte para probarlo sin Android. */
    fun parsear(lineas: Sequence<String>): Tabla {
        val pasos = mutableListOf<String>()
        val advertencias = mutableListOf<String>()
        val codigos = mutableListOf<Codigo>()
        for (cruda in lineas) {
            val linea = cruda.trimEnd()
            when {
                linea.isBlank() || linea.startsWith("#") -> Unit
                linea.startsWith(">") -> pasos += linea.drop(1).trim()
                linea.startsWith("!") -> advertencias += linea.drop(1).trim()
                else -> {
                    val p = linea.split('\t')
                    if (p.size < 7) continue
                    codigos += Codigo(
                        parpadeo = p[0].trim(),
                        hds = p[1].trim(),
                        gravedad = when (p[2].trim()) {
                            "grave" -> TablaDtc.Gravedad.GRAVE
                            "atencion" -> TablaDtc.Gravedad.ATENCION
                            else -> TablaDtc.Gravedad.LEVE
                        },
                        componente = p[3].trim(),
                        titulo = p[4].trim(),
                        explicacion = p[5].trim(),
                        queRevisar = p[6].trim(),
                    )
                }
            }
        }
        return Tabla(pasos, advertencias, codigos)
    }
}
