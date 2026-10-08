package com.nonosky.s2000dash.confirmador

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Pantalla unica del confirmador: abre los ajustes de accesibilidad.
 *
 * Existe porque este head unit trae unos Ajustes recortados por el
 * fabricante y no muestra la entrada de Accesibilidad por ningun lado. La
 * pantalla del sistema si existe — lo que falta es el acceso — asi que se
 * lanza por intent directamente y santas pascuas.
 *
 * Ademas dice si el confirmador ya esta activo, que de otro modo no hay
 * forma de saberlo sin root.
 */
class AbrirAccesibilidadActivity : Activity() {

    private lateinit var estado: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#07090C"))
            setPadding(48, 32, 48, 32)
        }

        raiz.addView(texto("S2000 Dash — Confirmador", 22f, Color.WHITE))
        estado = texto("", 17f, Color.parseColor("#8A96A3"))
        raiz.addView(estado)

        raiz.addView(Button(this).apply {
            text = "ABRIR AJUSTES DE ACCESIBILIDAD"
            textSize = 18f
            setOnClickListener { abrirAccesibilidad() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 32 }
        })

        raiz.addView(texto(
            "Activa «Confirmar actualizaciones de S2000 Dash». " +
                "Se hace una sola vez: este APK no se actualiza nunca, " +
                "así que Android no vuelve a desactivarlo.",
            14f, Color.parseColor("#8A96A3"),
        ))

        setContentView(raiz)
    }

    override fun onResume() {
        super.onResume()
        val activo = estaActivo()
        estado.text = if (activo) "✓ Activo. Ya no tienes que hacer nada más."
        else "○ Todavía no está activo."
        estado.setTextColor(
            if (activo) Color.parseColor("#35D07F") else Color.parseColor("#FFB020")
        )
    }

    private fun abrirAccesibilidad() {
        // La pantalla del sistema existe aunque el menu del fabricante no la
        // liste; el intent llega igual.
        val intentos = listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in intentos) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(i); true }.getOrDefault(false)) return
        }
    }

    /** Lee si nuestro servicio esta en la lista de activos del sistema. */
    private fun estaActivo(): Boolean {
        val activos = runCatching {
            Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull() ?: return false

        val nuestro = "$packageName/${ConfirmarInstalacionService::class.java.name}"
        val split = TextUtils.SimpleStringSplitter(':')
        split.setString(activos)
        while (split.hasNext()) {
            if (split.next().equals(nuestro, ignoreCase = true)) return true
        }
        return false
    }

    private fun texto(t: String, tamano: Float, color: Int) = TextView(this).apply {
        text = t
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, tamano)
        gravity = Gravity.CENTER
        setPadding(0, 12, 0, 12)
    }
}
