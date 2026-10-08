package com.nonosky.s2000dash.confirmador

import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Control remoto de la pantalla del radio.
 *
 * Existe por una limitacion muy concreta: este head unit no tiene root, su
 * shell no puede `input tap` ni `am start` ni `dumpsys`, y su pantalla esta
 * dentro de un carro al que hay que ir fisicamente. Un
 * [AccessibilityService] es la UNICA via que Android deja abierta para leer
 * y tocar la interfaz de otras apps sin ser sistema — y es deliberado que
 * solo se abra con un interruptor que pulse una persona.
 *
 * O sea: esto no es un atajo, es la puerta oficial. Y una vez abierta,
 * permite leer la app nativa del TPMS, abrir Ajustes, o pulsar cualquier
 * cosa, sin volver al carro.
 *
 * Todo lo que se hace se REPORTA de vuelta al tablero, porque nadie va a
 * estar mirando la pantalla cuando pase: un mando que no cuenta lo que hizo
 * es indistinguible de un mando roto.
 */
object Mando {

    /** Tope de nodos por volcado: un arbol entero puede tener cientos. */
    private const val MAX_NODOS = 220

    /** Profundidad maxima. Mas abajo ya no hay nada legible para un humano. */
    private const val MAX_HONDURA = 14

    /**
     * Vuelca el arbol de la ventana activa.
     *
     * Se incluyen las coordenadas del centro de cada nodo, y no solo su
     * texto, porque son lo que hace falta para tocarlo despues. Un volcado
     * sin coordenadas obliga a adivinar donde pulsar.
     */
    fun volcar(servicio: AccessibilityService): List<String> {
        val salida = mutableListOf<String>()

        val ventanas = runCatching { servicio.windows }.getOrNull()
        salida += "ventanas visibles: ${ventanas?.size ?: 0}"

        val raiz = runCatching { servicio.rootInActiveWindow }.getOrNull()
            ?: return salida + "sin ventana activa (la pantalla puede estar apagada)"

        salida += "paquete activo: ${raiz.packageName}"
        val contador = intArrayOf(0)
        recorrer(raiz, 0, salida, contador)
        if (contador[0] >= MAX_NODOS) {
            salida += "... cortado en $MAX_NODOS nodos"
        }
        runCatching { raiz.recycle() }
        return salida
    }

    private fun recorrer(
        nodo: AccessibilityNodeInfo,
        hondura: Int,
        salida: MutableList<String>,
        contador: IntArray,
    ) {
        if (contador[0] >= MAX_NODOS || hondura > MAX_HONDURA) return
        contador[0]++

        val texto = nodo.text?.toString()
        val desc = nodo.contentDescription?.toString()
        val clase = nodo.className?.toString()?.substringAfterLast('.')

        // Solo se imprimen los nodos que aportan: con texto, con descripcion,
        // o pulsables. Un arbol completo de ViewGroups vacios es ruido que
        // tapa justo lo que se busca.
        val interesa = !texto.isNullOrBlank() || !desc.isNullOrBlank() ||
            nodo.isClickable || nodo.isCheckable || nodo.isEditable

        if (interesa) {
            val r = Rect().also { runCatching { nodo.getBoundsInScreen(it) } }
            val sb = StringBuilder()
            sb.append("  ".repeat(hondura.coerceAtMost(6)))
            sb.append(clase ?: "?")
            texto?.takeIf { it.isNotBlank() }?.let { sb.append(" \"").append(it).append("\"") }
            desc?.takeIf { it.isNotBlank() }?.let { sb.append(" [desc: ").append(it).append("]") }
            val marcas = buildList {
                if (nodo.isClickable) add("pulsable")
                if (nodo.isEditable) add("editable")
                if (nodo.isCheckable) add(if (nodo.isChecked) "marcado" else "sin marcar")
                if (!nodo.isEnabled) add("desactivado")
            }
            if (marcas.isNotEmpty()) sb.append(" (").append(marcas.joinToString(",")).append(")")
            sb.append("  @").append(r.centerX()).append(",").append(r.centerY())
            salida += sb.toString()
        }

        for (i in 0 until nodo.childCount) {
            val hijo = runCatching { nodo.getChild(i) }.getOrNull() ?: continue
            recorrer(hijo, hondura + 1, salida, contador)
            runCatching { hijo.recycle() }
        }
    }

    /**
     * Toca un punto de la pantalla.
     *
     * `dispatchGesture` existe desde API 24 y exige
     * `android:canPerformGestures="true"` en el XML del servicio. Sin esa
     * bandera devuelve false sin explicar nada, que es la clase de fallo que
     * cuesta una tarde.
     */
    fun tocar(servicio: AccessibilityService, x: Int, y: Int, duracionMs: Long = 60): String {
        if (Build.VERSION.SDK_INT < 24) return "ERROR: se necesita Android 7 o mas para gestos"
        return runCatching {
            val camino = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val trazo = android.accessibilityservice.GestureDescription.StrokeDescription(
                camino, 0, duracionMs.coerceIn(20, 2_000)
            )
            val gesto = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(trazo)
                .build()
            val ok = servicio.dispatchGesture(gesto, null, null)
            if (ok) "toque enviado a $x,$y" else "dispatchGesture devolvio false"
        }.getOrElse { "ERROR: ${it.javaClass.simpleName}: ${it.message}" }
    }

    /** Arrastre, para listas que hay que desplazar. */
    fun arrastrar(
        servicio: AccessibilityService,
        x1: Int, y1: Int, x2: Int, y2: Int, duracionMs: Long = 300,
    ): String {
        if (Build.VERSION.SDK_INT < 24) return "ERROR: se necesita Android 7 o mas para gestos"
        return runCatching {
            val camino = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            }
            val trazo = android.accessibilityservice.GestureDescription.StrokeDescription(
                camino, 0, duracionMs.coerceIn(50, 3_000)
            )
            val gesto = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(trazo).build()
            val ok = servicio.dispatchGesture(gesto, null, null)
            if (ok) "arrastre $x1,$y1 -> $x2,$y2" else "dispatchGesture devolvio false"
        }.getOrElse { "ERROR: ${it.javaClass.simpleName}: ${it.message}" }
    }

    /**
     * Pulsa el primer nodo cuyo texto contenga [aguja].
     *
     * Preferible a tocar coordenadas cuando se sabe la etiqueta: sobrevive a
     * que la ventana se mueva o cambie de tamaño, y no depende de que el
     * volcado sea reciente.
     */
    fun pulsarTexto(servicio: AccessibilityService, aguja: String): String {
        val raiz = runCatching { servicio.rootInActiveWindow }.getOrNull()
            ?: return "sin ventana activa"
        try {
            val objetivo = buscarPorTexto(raiz, aguja.lowercase())
                ?: return "no hay ningun nodo que diga '$aguja'"
            // Muchos textos viven en una etiqueta no pulsable dentro de un
            // contenedor que si lo es; subir hasta el primer ancestro pulsable
            // es lo que hace que esto funcione en la practica.
            var n: AccessibilityNodeInfo? = objetivo
            var saltos = 0
            while (n != null && !n.isClickable && saltos < 6) {
                n = n.parent
                saltos++
            }
            val destino = n ?: objetivo
            val ok = destino.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return if (ok) "pulsado '$aguja'" else "el nodo '$aguja' rechazo el clic"
        } catch (e: Exception) {
            return "ERROR: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            runCatching { raiz.recycle() }
        }
    }

    private fun buscarPorTexto(nodo: AccessibilityNodeInfo, aguja: String): AccessibilityNodeInfo? {
        val t = nodo.text?.toString()?.lowercase()
        val d = nodo.contentDescription?.toString()?.lowercase()
        if (t?.contains(aguja) == true || d?.contains(aguja) == true) return nodo
        for (i in 0 until nodo.childCount) {
            val hijo = runCatching { nodo.getChild(i) }.getOrNull() ?: continue
            buscarPorTexto(hijo, aguja)?.let { return it }
        }
        return null
    }

    /** Escribe en el primer campo editable que haya. */
    fun escribir(servicio: AccessibilityService, texto: String): String {
        val raiz = runCatching { servicio.rootInActiveWindow }.getOrNull()
            ?: return "sin ventana activa"
        try {
            val campo = buscarEditable(raiz) ?: return "no hay campo editable en pantalla"
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto)
            }
            val ok = campo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            return if (ok) "escrito '$texto'" else "el campo rechazo el texto"
        } catch (e: Exception) {
            return "ERROR: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            runCatching { raiz.recycle() }
        }
    }

    private fun buscarEditable(nodo: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (nodo.isEditable) return nodo
        for (i in 0 until nodo.childCount) {
            val hijo = runCatching { nodo.getChild(i) }.getOrNull() ?: continue
            buscarEditable(hijo)?.let { return it }
        }
        return null
    }

    /** Atras, inicio, recientes y notificaciones. */
    fun accionGlobal(servicio: AccessibilityService, cual: String): String {
        val codigo = when (cual.lowercase()) {
            "atras", "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "inicio", "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recientes", "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notificaciones", "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> return "accion desconocida: $cual"
        }
        val ok = runCatching { servicio.performGlobalAction(codigo) }.getOrDefault(false)
        return if (ok) "accion '$cual' hecha" else "accion '$cual' rechazada"
    }

    /**
     * Abre una app por su paquete.
     *
     * Lo hace el confirmador y no el tablero porque en este radio `am start`
     * desde el shell esta vetado, y un `startActivity` desde el tablero
     * funciona igual — pero teniendolo aqui, el mando entero vive en un solo
     * sitio y no hay que coordinar dos apps para una accion.
     */
    fun abrir(servicio: AccessibilityService, paquete: String): String = runCatching {
        val intent = servicio.packageManager.getLaunchIntentForPackage(paquete)
            ?: return "el paquete '$paquete' no tiene actividad de arranque"
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        servicio.startActivity(intent)
        "abriendo $paquete"
    }.getOrElse { "ERROR abriendo $paquete: ${it.message}" }

    /** Lista lo instalado, para saber como se llama la app del TPMS. */
    fun listarApps(servicio: AccessibilityService, filtro: String?): List<String> = runCatching {
        val pm = servicio.packageManager
        pm.getInstalledPackages(0)
            .asSequence()
            .map { p ->
                val etiqueta = runCatching {
                    p.applicationInfo?.let { pm.getApplicationLabel(it).toString() }
                }.getOrNull() ?: ""
                "${p.packageName}  ($etiqueta)"
            }
            .filter { filtro.isNullOrBlank() || it.contains(filtro, ignoreCase = true) }
            .sorted()
            .take(120)
            .toList()
    }.getOrElse { listOf("ERROR listando apps: ${it.message}") }
}

