package com.nonosky.carsoc.diag

import android.content.Context
import com.nonosky.carsoc.Carro

/**
 * Que significa cada codigo, en español y para el dueño del carro.
 *
 * ## Por que esto NO es un mapa en el codigo
 *
 * Son un centenar de codigos con titulo, explicacion y causas: decenas de KB. Un
 * `mapOf(...)` con eso dentro vive en el heap **desde que arranca la app y
 * hasta que muere**, aunque el dueño no abra el diagnostico nunca — y el
 * tablero corre en un rk3326 que ya se apago tres veces por calor, con el
 * TPMS, el aceite y el puente encima.
 *
 * Asi que la tabla vive en `res/raw`, se lee cuando se abre el diagnostico y
 * se SUELTA al cerrarlo. Mientras se maneja, esto ocupa cero.
 *
 * ## Y por que se carga entera y no por codigo
 *
 * Porque leer un archivo de 36 KB de una vez cuesta milisegundos, y la
 * alternativa —recorrerlo buscando cada codigo— haria una pasada por cada
 * averia encontrada. Se carga una vez, se usa, se tira.
 */
object TablaDtc {

    data class Entrada(
        val codigo: String,
        val gravedad: Gravedad,
        val titulo: String,
        val explicacion: String,
        val causas: String,
    )

    enum class Gravedad { LEVE, ATENCION, GRAVE }

    @Volatile
    private var tabla: Map<String, Entrada>? = null

    /** Cuantos codigos conoce. 0 si no esta cargada. */
    val cargados: Int get() = tabla?.size ?: 0

    /**
     * Carga la tabla si no lo estaba. Devuelve cuantos codigos quedaron.
     *
     * Envuelto entero: si el recurso faltara o viniera corrupto, el
     * diagnostico tiene que seguir leyendo codigos del carro y enseñarlos
     * sin explicacion. Perder el diccionario es una molestia; perder la
     * lectura de averias es quedarse sin la funcion.
     */
    fun cargar(context: Context): Int {
        tabla?.let { return it.size }
        val mapa = leer(context)
        tabla = mapa
        return mapa.size
    }

    /**
     * La tabla entera, leida de nuevo y SIN quedarse en este objeto: para la
     * pantalla que la enseña completa. Asi cerrar una pantalla no le vacia la
     * tabla a la otra. Vacia si el recurso falta o viene roto.
     */
    fun leer(context: Context): Map<String, Entrada> = runCatching {
        val salida = LinkedHashMap<String, Entrada>(128)
        context.resources.openRawResource(Carro.perfil.tablaDtc).bufferedReader().useLines { lineas ->
            for (linea in lineas) {
                if (linea.isBlank() || linea.startsWith("#")) continue
                val p = linea.split('\t')
                if (p.size < 5) continue
                salida[p[0]] = Entrada(
                    codigo = p[0],
                    gravedad = when (p[1]) {
                        "grave" -> Gravedad.GRAVE
                        "atencion" -> Gravedad.ATENCION
                        else -> Gravedad.LEVE
                    },
                    titulo = p[2],
                    explicacion = p[3],
                    causas = p[4],
                )
            }
        }
        salida
    }.getOrElse { emptyMap() }

    /** Suelta la tabla. Lo llama el diagnostico al cerrarse. */
    fun soltar() {
        tabla = null
    }

    /**
     * Que significa este codigo. Null si no esta en la tabla.
     *
     * Que falte NO es un error a ocultar: la computadora fijo algo que la
     * tabla de este carro no preveia. La pantalla lo dice con todas las letras
     * y explica al menos su grupo ([GrupoDtc]).
     */
    fun de(codigo: String): Entrada? = tabla?.get(codigo)
}
