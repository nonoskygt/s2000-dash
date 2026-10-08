package com.nonosky.carsoc

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Abre pantallas del sistema y averigua quien puede dibujar encima.
 *
 * Nace de un atasco muy concreto. Para conceder Accesibilidad al confirmador
 * hay que pulsar "Permitir" en un dialogo del sistema, y Android **descarta
 * ese toque** si alguna app esta dibujando por encima —proteccion contra
 * tapjacking—. El aviso que sale es "Ajustes no puede verificar tu
 * respuesta", el interruptor parece quedar puesto, y no lo esta. Sin
 * Accesibilidad no hay toque remoto; sin toque remoto no se puede apagar el
 * overlay desde aqui. Es una pescadilla.
 *
 * Se rompe por el lado que NO necesita accesibilidad: esta app si puede
 * lanzar intents del sistema y si puede leer que declara cada paquete
 * instalado. O sea, puede **decir quien es el culpable** y **abrir la
 * pantalla exacta** donde se le quita el permiso. Lo unico que queda para el
 * dueño es un toque, en vez de una caceria por menus que no listan nada.
 */
object Ajustes {

    /** Pantallas que sabemos abrir, por nombre corto. */
    private val DESTINOS = mapOf(
        "desarrollo" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        "adb" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        "accesibilidad" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "overlay" to Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        "encima" to Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        "fuentes" to Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        "apps" to Settings.ACTION_APPLICATION_SETTINGS,
        "ajustes" to Settings.ACTION_SETTINGS,
        "info" to Settings.ACTION_DEVICE_INFO_SETTINGS,
    )

    fun abrir(context: Context, que: String?, paquete: String? = null): List<String> {
        val clave = que?.lowercase()?.trim().orEmpty()
        if (clave == "appinfo") {
            if (paquete.isNullOrBlank()) return listOf("appinfo necesita &paquete=")
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:$paquete"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { context.startActivity(i); listOf("abierta la ficha de $paquete") }
                .getOrElse { listOf("ERROR: ${it.message}") }
        }

        val accion = DESTINOS[clave]
            ?: return listOf(
                "no se que abrir: '$que'",
                "destinos: ${DESTINOS.keys.sorted().joinToString(", ")}, appinfo",
            )

        val intent = Intent(accion).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Con paquete, el overlay salta directo al interruptor de ESA app en
        // vez de a una lista de 16 donde hay que buscarla.
        if (!paquete.isNullOrBlank() && accion == Settings.ACTION_MANAGE_OVERLAY_PERMISSION) {
            intent.data = android.net.Uri.parse("package:$paquete")
        }

        // Si nadie atiende la accion, decirlo en vez de fallar en silencio:
        // estas ROMs recortan pantallas de Ajustes a capricho, y saber que
        // NO existe es tan util como abrirla.
        val atiende = runCatching {
            context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        }.getOrDefault(false)
        if (!atiende) return listOf("'$clave' -> $accion", "ERROR: ninguna actividad atiende esa accion en este radio")

        return runCatching {
            context.startActivity(intent)
            listOf("abierto: $clave -> $accion")
        }.getOrElse {
            // Android 10+ restringe abrir pantallas desde segundo plano. Si
            // el tablero esta al frente suele permitirse; si no, hay que
            // tocarlo una vez y repetir.
            listOf(
                "'$clave' -> $accion",
                "ERROR al abrir: ${it.message}",
                "prueba con el tablero en pantalla y repite",
            )
        }
    }

    /**
     * Quien declara poder dibujar por encima de todo.
     *
     * No se puede saber a quien se lo CONCEDIERON —eso vive en AppOps y pide
     * privilegios que esta app no tiene— pero si quien lo pide, que es la
     * lista corta donde esta el culpable. Se separan las del sistema de las
     * instaladas porque el boton flotante de estos radios casi siempre es
     * una app del fabricante, no de AOSP.
     */
    fun overlays(context: Context): List<String> {
        val pm = context.packageManager
        val paquetes = runCatching {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }.getOrNull() ?: return listOf("ERROR: no se pudo listar paquetes")

        val sistema = mutableListOf<String>()
        val instaladas = mutableListOf<String>()

        for (p in paquetes) {
            val pide = p.requestedPermissions?.any {
                it == android.Manifest.permission.SYSTEM_ALERT_WINDOW
            } ?: false
            if (!pide) continue

            val app: ApplicationInfo = p.applicationInfo ?: continue
            val etiqueta = runCatching { pm.getApplicationLabel(app).toString() }
                .getOrDefault(p.packageName)
            val linea = "$etiqueta  [${p.packageName}]"
            if ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) sistema += linea
            else instaladas += linea
        }

        val salida = mutableListOf<String>()
        salida += "Apps que PIDEN dibujar encima (${instaladas.size + sistema.size})."
        salida += "El que bloquea el permiso de Accesibilidad es uno de estos."
        salida += ""
        salida += "--- instaladas (mira aqui primero) ---"
        salida += if (instaladas.isEmpty()) listOf("  ninguna") else instaladas.sorted().map { "  $it" }
        salida += ""
        salida += "--- del sistema / fabricante ---"
        salida += if (sistema.isEmpty()) listOf("  ninguna") else sistema.sorted().map { "  $it" }
        salida += ""
        salida += "Para apagarlos: /ajustes?que=overlay"
        return salida
    }

    /**
     * Los interruptores del sistema que deciden si podemos entrar.
     *
     * LEER Settings no pide ningun permiso; solo escribirlos lo pide. Asi
     * que aunque esta app no pueda encender ADB ni conceder accesibilidad,
     * si puede decir con certeza si estan puestos — y eso convierte
     * "parece que no abrio" en un dato.
     */
    fun interruptores(context: Context): List<String> {
        val cr = context.contentResolver
        fun global(clave: String): String = runCatching {
            Settings.Global.getString(cr, clave) ?: "(sin valor)"
        }.getOrElse { "ERROR: ${it.message}" }
        fun segura(clave: String): String = runCatching {
            Settings.Secure.getString(cr, clave) ?: "(sin valor)"
        }.getOrElse { "ERROR: ${it.message}" }

        val dev = global("development_settings_enabled")
        val adb = global("adb_enabled")
        return listOf(
            "development_settings_enabled = $dev" +
                if (dev != "1") "   <-- por esto se rebota la pantalla" else "",
            "adb_enabled = $adb" +
                if (adb == "1") "   <-- ADB ENCENDIDO, probar por USB" else "",
            "adb_wifi_enabled = ${global("adb_wifi_enabled")}",
            "install_non_market_apps = ${segura("install_non_market_apps")}",
            "",
            "accessibility_enabled = ${segura("accessibility_enabled")}",
            "enabled_accessibility_services = ${segura("enabled_accessibility_services")}",
            "accessibility_display_magnification_enabled = " +
                segura("accessibility_display_magnification_enabled"),
        )
    }
}
