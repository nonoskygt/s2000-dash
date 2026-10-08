package com.nonosky.carsoc.diag

import android.bluetooth.BluetoothAdapter
import com.nonosky.carsoc.obd.Dtc
import com.nonosky.carsoc.obd.Elm327Session
import com.nonosky.carsoc.obd.PidDecoder
import com.nonosky.carsoc.obd.SppTransport

/**
 * Lee y borra los codigos de averia hablando con la ECU.
 *
 * Abre su PROPIA conexion y la cierra al terminar, en vez de colgarse del
 * sondeo del tablero. Dos razones: el sondeo puede no estar encendido cuando
 * el dueño abre el diagnostico, y sobre todo el modo 03 necesita cambiar la
 * temporizacion del adaptador (ver [prepararParaDtc]) — tocarsela al sondeo
 * en marcha le estropearia el ritmo a la mitad de una lectura.
 */
class LectorDtc(
    private val adapter: BluetoothAdapter?,
    private val mac: String,
) {

    data class Resultado(
        val guardados: List<Dtc.Codigo> = emptyList(),
        val pendientes: List<Dtc.Codigo> = emptyList(),
        val permanentes: List<Dtc.Codigo> = emptyList(),
        /** Luz de averia encendida segun el PID 0101. */
        val luzEncendida: Boolean = false,
        /** Cuantos dice la ECU que hay. Sirve para cazar tramas perdidas. */
        val cuantosDiceLaEcu: Int = -1,
        val error: String? = null,
        val traza: List<String> = emptyList(),
    ) {
        val hayAlgo: Boolean get() = guardados.isNotEmpty() || pendientes.isNotEmpty() ||
            permanentes.isNotEmpty()
    }

    /**
     * Lo hace todo: conecta, pregunta y cierra. Nunca lanza.
     *
     * Corre en un hilo de fondo; quien llama se encarga de no bloquear la UI.
     */
    fun leer(): Resultado {
        val traza = mutableListOf<String>()
        val dev = runCatching { adapter?.getRemoteDevice(mac) }.getOrNull()
            ?: return Resultado(error = "no se pudo resolver el adaptador OBD", traza = traza)

        val t = SppTransport(dev, adapter)
        return try {
            t.connect()
            traza += "enlace abierto"
            val s = Elm327Session(t)
            s.initialize()
            traza += "adaptador listo"

            prepararParaDtc(s, traza)

            // El 0101 PRIMERO: dice si la luz esta encendida y CUANTOS codigos
            // confirmados hay. Sin eso no se puede saber si el modo 03 devolvio
            // todos o se perdio una trama por el camino — y perder codigos en
            // silencio es el peor fallo posible en una pantalla de averias.
            val estado = s.queryRaw(PidDecoder.PID_ESTADO, MS_ESPERA_DTC)
            traza += "0101 -> ${estado ?: "sin respuesta"}"
            val mil = PidDecoder.decodeMil(estado)

            val fallos = mutableListOf<String>()
            val guardados = pedirLista(s, Dtc.Tipo.GUARDADOS, traza, fallos)
            val pendientes = pedirLista(s, Dtc.Tipo.PENDIENTES, traza, fallos)
            // El modo 0A se hizo obligatorio en 2010: en un carro del 2000 lo
            // normal es que no exista. Se pregunta igual —cuesta una lectura—
            // y su silencio no se trata como fallo.
            val permanentes = pedirLista(s, Dtc.Tipo.PERMANENTES, traza, fallos)

            val esperados = mil?.second ?: -1
            if (esperados >= 0 && esperados != guardados.size) {
                traza += "OJO: la ECU dice $esperados guardados y llegaron ${guardados.size}"
            }

            // Si el modo 03 —el principal— no contesto, NO se puede decir que
            // el carro este sano. Los otros dos pueden callar legitimamente:
            // el 0A no existe en un carro del 2000 y el 07 puede no tener
            // nada. El 03 callando es que no hablamos con la ECU.
            val falloElPrincipal = fallos.any { it.startsWith(Dtc.Tipo.GUARDADOS.modo) }
            Resultado(
                guardados = guardados,
                pendientes = pendientes,
                permanentes = permanentes,
                luzEncendida = mil?.first ?: false,
                cuantosDiceLaEcu = esperados,
                error = if (falloElPrincipal) {
                    "no se pudo leer la computadora: ${fallos.first()}"
                } else null,
                traza = traza,
            )
        } catch (e: Exception) {
            Resultado(error = "${e.javaClass.simpleName}: ${e.message}", traza = traza)
        } finally {
            runCatching { t.close() }
        }
    }

    /**
     * Borra los codigos con el modo 04 y comprueba que de verdad se fueron.
     *
     * Comprobar importa: la ECU puede aceptar el 04 y no borrar nada si el
     * motor esta girando o si la averia sigue activa. Decir "borrado" sin
     * mirar seria mentirle al dueño sobre lo unico que vino a hacer.
     */
    fun borrar(): Resultado {
        val traza = mutableListOf<String>()
        val dev = runCatching { adapter?.getRemoteDevice(mac) }.getOrNull()
            ?: return Resultado(error = "no se pudo resolver el adaptador OBD", traza = traza)

        val t = SppTransport(dev, adapter)
        return try {
            t.connect()
            val s = Elm327Session(t)
            s.initialize()
            prepararParaDtc(s, traza)

            val r = s.queryRaw("04", MS_ESPERA_DTC)
            traza += "04 -> ${r ?: "sin respuesta"}"
            // La respuesta correcta al 04 es "44", ANCLADA al principio de su
            // linea. Buscarla con contains() es la misma trampa que el modo
            // 03: `44` aparece dentro de codigos legitimos —P0044 se codifica
            // `0044`— asi que un `contains` podria dar por bueno un borrado
            // que la ECU nunca hizo, leyendo el eco de un codigo.
            val acepto = r?.lines()?.any { linea ->
                linea.uppercase().filter { it.isDigit() || it in 'A'..'F' }.startsWith("44")
            } == true
            traza += if (acepto) "la ECU acepto el borrado" else "la ECU NO confirmo el borrado"

            // Esperar un momento: la ECU necesita tiempo para reescribir su
            // memoria, y preguntar de inmediato devuelve lo de antes.
            runCatching { Thread.sleep(1_500) }

            val fallos = mutableListOf<String>()
            val quedan = pedirLista(s, Dtc.Tipo.GUARDADOS, traza, fallos)
            val estado = s.queryRaw(PidDecoder.PID_ESTADO)
            val mil = PidDecoder.decodeMil(estado)
            traza += "tras borrar quedan ${quedan.size} guardados; luz=${mil?.first}"

            Resultado(
                guardados = quedan,
                luzEncendida = mil?.first ?: false,
                cuantosDiceLaEcu = mil?.second ?: -1,
                error = when {
                    fallos.isNotEmpty() -> "no se pudo comprobar el borrado: ${fallos.first()}"
                    !acepto && quedan.isNotEmpty() -> "la ECU no borro"
                    else -> null
                },
                traza = traza,
            )
        } catch (e: Exception) {
            Resultado(error = "${e.javaClass.simpleName}: ${e.message}", traza = traza)
        } finally {
            runCatching { t.close() }
        }
    }

    /**
     * Pide una lista y **avisa si no hubo respuesta**.
     *
     * Antes devolvia `List<Codigo>` a secas: un fallo de enlace se anotaba en
     * la traza y salia como lista vacia, indistinguible de un carro sano. La
     * pantalla acababa pintando SIN AVERIAS en verde con el bus caido. Ahora
     * los fallos se acumulan en [fallos] y `leer()` los convierte en error.
     */
    private fun pedirLista(
        s: Elm327Session,
        tipo: Dtc.Tipo,
        traza: MutableList<String>,
        fallos: MutableList<String>,
    ): List<Dtc.Codigo> {
        // Plazo LARGO, y esto es la otra mitad del arreglo del ATAT0.
        //
        // `ATST FF` le dice al ELM327 que espere hasta ~1020 ms por la
        // respuesta, pero el plazo por omision de esta capa son 350 ms: nos
        // rendiamos antes que el adaptador y nos quedabamos con un buffer a
        // medias, que es EXACTAMENTE la perdida de tramas que el ATAT0 venia
        // a evitar. Fijar uno sin el otro no arregla nada.
        val crudo = runCatching { s.queryRaw(tipo.modo, MS_ESPERA_DTC) }.getOrNull()
        traza += "${tipo.modo} (${tipo.etiqueta}) -> ${crudo ?: "sin respuesta"}"

        if (crudo == null) {
            fallos += "${tipo.modo} no contesto"
            return emptyList()
        }
        // Un "NO DATA" aqui NO es un fallo: es la ECU diciendo que no tiene
        // nada de ese tipo. Silencio o error de bus SI lo son.
        if (Dtc.esFalloDeEnlace(crudo)) {
            val motivo = if (Dtc.huboRespuesta(crudo)) "error del bus" else "sin respuesta"
            traza += "  ($motivo en ${tipo.modo}, NO es que no haya codigos)"
            fallos += "${tipo.modo}: $motivo"
            return emptyList()
        }
        return Dtc.leerLista(crudo, tipo)
    }

    private companion object {
        /**
         * Lo que se espera por cada peticion de codigos.
         *
         * Tiene que ser MAYOR que lo que se le fija al ELM327 con `ATST FF`
         * (~1020 ms) o nos rendimos antes que el, y nos quedamos con media
         * respuesta sin enterarnos.
         */
        const val MS_ESPERA_DTC = 3_000L
    }

    /**
     * Apaga la temporizacion adaptativa antes de pedir codigos.
     *
     * Esta es la trampa que cuesta codigos SIN AVISAR. Con `ATAT1` —que es
     * como viene el adaptador— el ELM327 aprende cuanto tarda la ECU en
     * contestar y corta la escucha en cuanto cree que termino. Para el modo
     * 01, donde cada respuesta es una sola trama, va perfecto. Para el modo
     * 03 con mas de tres codigos, la ECU manda VARIAS tramas seguidas y el
     * adaptador corta despues de la primera: se leen tres averias, existen
     * seis, y nada en la respuesta dice que faltan.
     *
     * `ATAT0` fija la temporizacion y `ATST FF` le da el plazo maximo para
     * esperar. Se paga con lecturas mas lentas, que aqui no importan: esto
     * se abre a mano y una vez.
     */
    private fun prepararParaDtc(s: Elm327Session, traza: MutableList<String>) {
        runCatching {
            s.queryRaw("ATAT0")
            s.queryRaw("ATST FF")
            traza += "temporizacion fija (ATAT0 + ATST FF) para no perder tramas"
        }.onFailure { traza += "no se pudo fijar la temporizacion: ${it.message}" }
    }
}
