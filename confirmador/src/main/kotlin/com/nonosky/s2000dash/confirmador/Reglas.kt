package com.nonosky.s2000dash.confirmador

/**
 * Que se toca y que no en el dialogo del instalador.
 *
 * Se guarda una copia propia aqui, en vez de compartir codigo con el
 * tablero, a proposito: este APK no se actualiza nunca, y no debe cambiar
 * de comportamiento porque alguien toque el otro modulo.
 */
object Reglas {

    private val INSTALADORES = listOf("packageinstaller", "permissioncontroller")

    /**
     * Solo se pulsa un boton que diga literalmente instalar o actualizar.
     * "Aceptar", "OK" y "Listo" estan fuera: son los del dialogo de
     * DESINSTALAR y los de permisos, y confundirlos significaria borrar el
     * tablero o conceder permisos que nadie vio.
     */
    private val CONFIRMA = setOf("instalar", "install", "actualizar", "update")

    private val NUNCA = setOf(
        "cancelar", "cancel",
        "no instalar", "dont install", "don't install",
        "rechazar", "deny", "denegar",
        "desinstalar", "uninstall",
        "aceptar", "ok", "listo", "done",
        "atras", "back",
        "permitir", "allow", "conceder", "grant",
        "configuracion", "settings", "ajustes",
    )

    /** Si la ventana dice algo de esto, el confirmador se aparta. */
    private val PELIGRO = listOf(
        "desinstalar", "uninstall",
        "permitir que", "allow ", "conceder", "grant ",
        "acceso a", "access to",
    )

    /** Paquetes donde vive el dialogo de emparejamiento Bluetooth. */
    private val DIALOGOS_BT = listOf(
        "com.android.settings",
        "com.android.bluetooth",
        "com.android.systemui",
    )

    fun esDialogoBluetooth(pkg: String?): Boolean {
        val p = pkg?.lowercase() ?: return false
        return DIALOGOS_BT.any { p == it || p.startsWith("$it.") }
    }

    /** Botones que aceptan un emparejamiento. */
    private val EMPAREJA = setOf(
        "emparejar", "pair", "vincular", "aceptar", "ok", "conectar", "connect",
    )

    fun esBotonDeEmparejar(etiqueta: String?): Boolean {
        val n = normalizar(etiqueta) ?: return false
        // Aqui SI vale "aceptar"/"ok": el dialogo de emparejamiento los usa
        // como confirmacion. La lista de instalacion sigue siendo estricta.
        if (n in NUNCA_BT) return false
        return n in EMPAREJA
    }

    private val NUNCA_BT = setOf(
        "cancelar", "cancel", "rechazar", "deny", "denegar", "atras", "back",
    )

    fun esInstalador(pkg: String?): Boolean {
        val p = pkg?.lowercase() ?: return false
        return INSTALADORES.any { p.contains(it) }
    }

    fun esBotonDeConfirmar(etiqueta: String?): Boolean {
        val n = normalizar(etiqueta) ?: return false
        if (n in NUNCA) return false
        return n in CONFIRMA
    }

    /**
     * Se confirma unicamente si el tablero armo la instalacion hace poco.
     *
     * Deliberadamente NO se mira el nombre de la app en pantalla: ese texto
     * es el `android:label` del APK que se esta instalando, o sea una cadena
     * que controla quien fabrico ese APK. Fiarse de el permitia que
     * cualquier APK llamado "S2000 Dash" se auto-instalara.
     */
    fun puedeConfirmar(textos: List<String>, armado: Boolean): Boolean {
        if (!armado) return false
        return textos.none { t ->
            val n = t.lowercase()
            PELIGRO.any { n.contains(it) }
        }
    }

    private fun normalizar(bruto: String?): String? {
        val s = bruto?.trim()?.lowercase() ?: return null
        if (s.isEmpty()) return null
        val sinAcentos = s
            .replace('á', 'a').replace('é', 'e').replace('í', 'i')
            .replace('ó', 'o').replace('ú', 'u').replace('ñ', 'n')
        return sinAcentos.filter { it.isLetterOrDigit() || it == ' ' || it == '\'' }
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }
}
