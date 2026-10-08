package com.nonosky.carsoc.selfupdate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Recibe el desenlace de una sesion de instalacion.
 *
 * El caso interesante es [PackageInstaller.STATUS_PENDING_USER_ACTION]: el
 * sistema no instala sin que alguien confirme, y nos entrega el intent del
 * dialogo. Lo lanzamos nosotros; el toque de "Instalar" lo da el servicio
 * de accesibilidad, asi que nadie tiene que estar delante del radio.
 */
class InstallStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
        )
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    Log.w(TAG, "Falta el intent de confirmacion")
                    return
                }
                Log.i(TAG, "Confirmacion pedida; abriendo dialogo")
                UpdateState.note("Confirmando instalacion")
                // Desde un receiver no hay actividad de origen: hace falta
                // NEW_TASK o el sistema rechaza el lanzamiento.
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { Log.w(TAG, "No se pudo abrir el dialogo: ${it.message}") }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Instalacion completada")
                UpdateState.note("Instalacion completada")
            }

            else -> {
                Log.w(TAG, "Instalacion fallida ($status): $msg")
                UpdateState.note("Instalacion fallida ($status): $msg")
            }
        }
    }

    private companion object {
        const val TAG = "InstallStatus"
    }
}
