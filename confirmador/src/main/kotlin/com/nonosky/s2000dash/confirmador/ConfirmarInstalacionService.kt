package com.nonosky.s2000dash.confirmador

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Pulsa "Instalar" en el dialogo del sistema cuando el tablero se actualiza.
 *
 * Vive en un APK aparte porque Android desactiva el servicio de
 * accesibilidad de una app en cuanto esa app se actualiza. Estando aqui,
 * el tablero puede actualizarse cuantas veces quiera sin perder el permiso.
 */
class ConfirmarInstalacionService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instancia = this
        Log.i(TAG, "Confirmador conectado")
        reportar("confirmador CONECTADO y listo para mandos")
        levantarElTablero()
    }

    /**
     * Enciende el tablero al arrancar el carro.
     *
     * ## Por que lo hace ESTE modulo y no el BootReceiver del tablero
     *
     * Se midio con un reinicio de verdad: tras arrancar, el confirmador
     * aparecio en el log —"Start proc ... for service
     * ConfirmarInstalacionService"— y el `BootReceiver` del tablero NO
     * aparecio por ningun lado, mientras otras apps del fabricante si
     * recibian el suyo. Esta ROM tiene lista blanca de autoarranque y el
     * tablero no esta en ella; el `BOOT_COMPLETED` sencillamente no le llega.
     * Se probo tambien con `QUICKBOOT_POWERON` y tampoco.
     *
     * Pero un **servicio de accesibilidad** lo arranca Android por su cuenta
     * en cada arranque, antes que cualquier lista del fabricante, porque el
     * sistema tiene que garantizar que quien depende de accesibilidad para
     * usar el aparato pueda usarlo. Es el unico componente de este proyecto
     * que la ROM no puede ignorar — y ya estaba instalado.
     *
     * Asi que el confirmador, que nacio para pulsar "Instalar", hereda un
     * segundo trabajo: encender el tablero. Cuesta un intent y resuelve algo
     * que el dueño pidio explicitamente — que la app este SIEMPRE al girar la
     * llave, y que cerrarla sea decision suya y no del fabricante.
     *
     * Nunca lanza: si la ROM bloquea abrir pantallas desde segundo plano, se
     * queda al menos el servicio, que es el que sostiene el TPMS y la alerta
     * de presion baja.
     */
    private fun levantarElTablero() {
        runCatching {
            val servicio = android.content.Intent()
                .setClassName(PAQUETE_TABLERO, "$PAQUETE_TABLERO.DashService")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(servicio)
            } else {
                startService(servicio)
            }
            reportar("tablero: servicio levantado desde el confirmador")
        }.onFailure { reportar("tablero: no se pudo levantar el servicio: ${it.message}") }

        runCatching {
            startActivity(
                android.content.Intent()
                    .setClassName(PAQUETE_TABLERO, "$PAQUETE_TABLERO.DashActivity")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { reportar("tablero: no se pudo abrir la pantalla: ${it.message}") }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // Sin limpiar esto, un mando posterior actuaria sobre un servicio ya
        // desconectado y fallaria sin decir por que. Android desactiva la
        // accesibilidad al actualizar el APK, asi que esto pasa de verdad.
        if (instancia === this) instancia = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    /**
     * Ejecuta un mando que llego del tablero por difusion.
     *
     * Se envuelve entero: una excepcion aqui viaja por el hilo principal del
     * servicio de accesibilidad y se lleva el proceso, dejando al radio sin
     * mando y sin manera de recuperarlo en remoto.
     */
    fun ejecutarMando(comando: String, a: String?, b: String?, c: String?, d: String?) {
        runCatching {
            when (comando) {
                "volcar" -> Mando.volcar(this).forEach { reportar(it) }
                "tocar" -> reportar(
                    Mando.tocar(this, a?.toIntOrNull() ?: 0, b?.toIntOrNull() ?: 0)
                )
                "arrastrar" -> reportar(
                    Mando.arrastrar(
                        this,
                        a?.toIntOrNull() ?: 0, b?.toIntOrNull() ?: 0,
                        c?.toIntOrNull() ?: 0, d?.toIntOrNull() ?: 0,
                    )
                )
                "pulsar" -> reportar(Mando.pulsarTexto(this, a ?: ""))
                "escribir" -> reportar(Mando.escribir(this, a ?: ""))
                "accion" -> reportar(Mando.accionGlobal(this, a ?: ""))
                "abrir" -> reportar(Mando.abrir(this, a ?: ""))
                "apps" -> Mando.listarApps(this, a).forEach { reportar(it) }
                else -> reportar("mando desconocido: $comando")
            }
        }.onFailure { reportar("ERROR en mando '$comando': ${it.javaClass.simpleName}: ${it.message}") }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString()

        // El dialogo de emparejamiento Bluetooth va antes: la ROM de este
        // radio lo muestra pero no deja escribir en el, asi que sin esto el
        // emparejamiento muere de tiempo y no hay forma de conectar el
        // adaptador OBD.
        //
        // No se filtra por paquete: en esta ROM el dialogo no vive en
        // ninguno de los de AOSP. Lo que acota el alcance es la ventana de
        // armado, que dura segundos y la abre el tablero.
        if (Armado.pinActivo()) {
            // Android 11 no siempre muestra un dialogo para el
            // emparejamiento: cuando lo pide una app puede publicarlo como
            // NOTIFICACION, y ahi se queda hasta que alguien la toca. Sin
            // mirar tambien las notificaciones el confirmador es ciego a
            // justo el caso que nos bloquea.
            if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                val texto = event.text?.joinToString(" ") ?: ""
                reportar("NOTIFICACION [$pkg]: $texto")
                if (esNotificacionDeEmparejamiento(texto)) {
                    reportar("es de emparejamiento -> abriendo")
                    abrirNotificacion(event)
                }
                return
            }
            reportar("ventana: $pkg")
            if (rellenarPin()) return
        }

        if (!Reglas.esInstalador(pkg)) return
        // Sin ventana armada por el tablero no se toca nada, diga lo que
        // diga la pantalla.
        if (!Armado.activo()) return

        val root = rootInActiveWindow ?: return
        try {
            val textos = ArrayList<String>()
            recogerTextos(root, textos)
            if (!Reglas.puedeConfirmar(textos, armado = true)) return

            val pulsado = pulsarPrimerBoton(root)
            if (pulsado != null) {
                Log.i(TAG, "Confirmado con '$pulsado'")
                // Una sola confirmacion por ventana: si no, seguiria
                // pulsando en cualquier dialogo posterior.
                Armado.desarmar()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallo revisando la ventana: ${e.message}")
        } finally {
            runCatching { root.recycle() }
        }
    }

    /**
     * Escribe el PIN en el dialogo de emparejamiento y acepta.
     *
     * @return true si se pudo teclear y confirmar.
     */
    private fun rellenarPin(): Boolean {
        val pin = Armado.pin ?: return false
        val root = rootInActiveWindow ?: return false
        try {
            val campo = buscarCampoTexto(root)

            if (campo != null) {
                val args = android.os.Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin
                    )
                }
                if (campo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    reportar("PIN '$pin' escrito")
                } else {
                    reportar("no se pudo escribir el PIN")
                }
            } else {
                // Muchos adaptadores usan emparejamiento "Just Works": el
                // sistema no pide PIN, solo un "¿Emparejar?" con un boton.
                // Antes se exigia un campo de texto y se abandonaba sin
                // pulsar nada, que es justo lo que dejaba el vinculo colgado.
                val textos = ArrayList<String>()
                recogerTextos(root, textos)
                reportar("sin campo; ventana dice: " + textos.take(8).joinToString(" / "))
            }

            // El boton puede habilitarse solo al haber texto, asi que se
            // vuelve a leer la ventana en vez de reutilizar el arbol viejo.
            val fresco = rootInActiveWindow ?: root
            val pulsado = pulsarBotonEmparejar(fresco)
            if (pulsado != null) {
                Log.i(TAG, "Emparejamiento confirmado con '$pulsado'")
                reportar("PIN escrito y confirmado con '$pulsado'")
                Armado.desarmarPin()
                return true
            }
            val textos = ArrayList<String>()
            recogerTextos(fresco, textos)
            reportar("no se hallo boton; ventana dice: " + textos.take(10).joinToString(" / "))
            return false
        } catch (e: Exception) {
            Log.w(TAG, "Fallo rellenando el PIN: ${e.message}")
            return false
        } finally {
            runCatching { root.recycle() }
        }
    }

    private fun buscarCampoTexto(nodo: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        nodo ?: return null
        if (nodo.isEditable && nodo.isEnabled) return nodo
        for (i in 0 until nodo.childCount) {
            val hijo = nodo.getChild(i) ?: continue
            val encontrado = buscarCampoTexto(hijo)
            if (encontrado != null) return encontrado
            runCatching { hijo.recycle() }
        }
        return null
    }

    private fun pulsarBotonEmparejar(nodo: AccessibilityNodeInfo?): String? {
        nodo ?: return null
        val txt = (nodo.text?.toString() ?: "").trim()
        val desc = (nodo.contentDescription?.toString() ?: "").trim()
        val etiqueta = if (txt.isNotEmpty()) txt else desc

        if (etiqueta.isNotEmpty() && nodo.isClickable && nodo.isEnabled &&
            Reglas.esBotonDeEmparejar(etiqueta)
        ) {
            if (nodo.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return etiqueta
        }
        for (i in 0 until nodo.childCount) {
            val hijo = nodo.getChild(i) ?: continue
            try {
                pulsarBotonEmparejar(hijo)?.let { return it }
            } finally {
                runCatching { hijo.recycle() }
            }
        }
        return null
    }

    private fun esNotificacionDeEmparejamiento(t: String): Boolean {
        val n = t.lowercase()
        return listOf("emparej", "pairing", "vincul", "bluetooth").any { n.contains(it) }
    }

    /** Abre la notificacion: eso hace salir el dialogo real. */
    private fun abrirNotificacion(event: AccessibilityEvent) {
        val p = event.parcelableData
        if (p is android.app.Notification) {
            runCatching {
                p.contentIntent?.send()
                reportar("notificacion abierta")
            }.onFailure { reportar("no se pudo abrir: ${it.message}") }
        } else {
            // Sin PendingIntent: desplegar la bandeja para que salga.
            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            reportar("bandeja de notificaciones desplegada")
        }
    }

    /**
     * Cuenta al tablero lo que ve.
     *
     * Sin esto, depurar el confirmador es imposible: no tiene pantalla, sus
     * logs no se pueden leer sin root, y el unico sintoma es que no pasa
     * nada.
     */
    private fun reportar(t: String) {
        runCatching {
            sendBroadcast(
                android.content.Intent("com.nonosky.s2000dash.CONFIRMADOR_DICE")
                    .setPackage("com.nonosky.s2000dash")
                    .putExtra("texto", t)
            )
        }
    }

    private fun recogerTextos(nodo: AccessibilityNodeInfo?, destino: MutableList<String>) {
        nodo ?: return
        if (destino.size > MAX_NODOS) return
        nodo.text?.toString()?.takeIf { it.isNotBlank() }?.let { destino += it }
        nodo.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { destino += it }
        for (i in 0 until nodo.childCount) {
            val hijo = nodo.getChild(i) ?: continue
            try {
                recogerTextos(hijo, destino)
            } finally {
                runCatching { hijo.recycle() }
            }
        }
    }

    private fun pulsarPrimerBoton(nodo: AccessibilityNodeInfo?): String? {
        nodo ?: return null
        val txt = (nodo.text?.toString() ?: "").trim()
        val desc = (nodo.contentDescription?.toString() ?: "").trim()
        val etiqueta = if (txt.isNotEmpty()) txt else desc

        if (etiqueta.isNotEmpty() && nodo.isClickable && nodo.isEnabled &&
            Reglas.esBotonDeConfirmar(etiqueta)
        ) {
            if (nodo.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return etiqueta
        }
        for (i in 0 until nodo.childCount) {
            val hijo = nodo.getChild(i) ?: continue
            try {
                pulsarPrimerBoton(hijo)?.let { return it }
            } finally {
                runCatching { hijo.recycle() }
            }
        }
        return null
    }

    companion object {

        private const val TAG = "Confirmador"

        /** El tablero al que este modulo sirve. */
        private const val PAQUETE_TABLERO = "com.nonosky.s2000dash"

        /**
         * El servicio vivo, para que la difusion del mando pueda actuar.
         *
         * Un BroadcastReceiver no puede alcanzar de otra forma al servicio de
         * accesibilidad: no hay binder publico ni manera de instanciarlo. La
         * pone onServiceConnected y la quita onUnbind.
         */
        @Volatile
        var instancia: ConfirmarInstalacionService? = null

        /** Tope de nodos: una ventana rara no nos cuelga. */
        private const val MAX_NODOS = 400
    }
}
