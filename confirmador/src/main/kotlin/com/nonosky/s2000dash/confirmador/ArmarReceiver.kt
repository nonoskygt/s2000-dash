package com.nonosky.s2000dash.confirmador

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Recibe del tablero el aviso de "voy a instalar, prepara el clic".
 *
 * El receptor exige un permiso de nivel `signature`, asi que solo puede
 * armarlo una app firmada con el mismo certificado. Sin eso, cualquier app
 * del radio podria abrir la ventana de confirmacion a voluntad.
 */
class ArmarReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACCION_ARMAR -> {
                val version = intent.getIntExtra(EXTRA_VERSION, -1)
                val duracion = intent.getLongExtra(EXTRA_DURACION, 120_000L)
                Armado.armar(version, duracion)
                Log.i(TAG, "Armado para v$version durante $duracion ms")
            }
            ACCION_DESARMAR -> {
                Armado.desarmar()
                Armado.desarmarPin()
                Log.i(TAG, "Desarmado")
            }
            ACCION_MANDO -> {
                val servicio = ConfirmarInstalacionService.instancia
                if (servicio == null) {
                    // Decirlo en vez de callar: "no pasa nada" no distingue
                    // entre accesibilidad apagada y mando mal escrito.
                    Reportero.decir(
                        context,
                        "ERROR: el servicio de accesibilidad NO esta activo; " +
                            "hay que encenderlo en Ajustes"
                    )
                } else {
                    servicio.ejecutarMando(
                        intent.getStringExtra(EXTRA_COMANDO) ?: "",
                        intent.getStringExtra(EXTRA_A),
                        intent.getStringExtra(EXTRA_B),
                        intent.getStringExtra(EXTRA_C),
                        intent.getStringExtra(EXTRA_D),
                    )
                }
            }
            ACCION_ARMAR_PIN -> {
                val pin = intent.getStringExtra(EXTRA_PIN) ?: return
                val duracion = intent.getLongExtra(EXTRA_DURACION, 90_000L)
                Armado.armarPin(pin, duracion)
                Log.i(TAG, "PIN armado durante $duracion ms")
            }
        }
    }

    companion object {
        private const val TAG = "ArmarReceiver"
        const val ACCION_ARMAR = "com.nonosky.s2000dash.ARMAR_INSTALACION"
        const val ACCION_DESARMAR = "com.nonosky.s2000dash.DESARMAR_INSTALACION"
        const val ACCION_ARMAR_PIN = "com.nonosky.s2000dash.ARMAR_PIN"
        const val ACCION_MANDO = "com.nonosky.s2000dash.MANDO"
        const val EXTRA_COMANDO = "comando"
        const val EXTRA_A = "a"
        const val EXTRA_B = "b"
        const val EXTRA_C = "c"
        const val EXTRA_D = "d"
        const val EXTRA_PIN = "pin"
        const val EXTRA_VERSION = "versionCode"
        const val EXTRA_DURACION = "duracionMs"
    }
}
