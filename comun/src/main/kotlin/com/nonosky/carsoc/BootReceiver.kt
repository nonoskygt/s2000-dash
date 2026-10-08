package com.nonosky.carsoc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Vuelve a abrir el tablero cuando el radio arranca o cuando la propia app
 * acaba de actualizarse.
 *
 * Sin esto la cadena de "se actualiza solo" se rompia en el primer apagado
 * del carro: el radio reinicia, queda el lanzador al frente, y como el
 * unico punto de entrada al buscador de actualizaciones es la pantalla del
 * tablero, ya no se revisa nada hasta que alguien vaya al carro y toque el
 * icono — justo lo que este trabajo quiere evitar.
 *
 * Lo mismo tras una actualizacion: el sistema mata el proceso al
 * reemplazar el APK y nadie lo vuelve a levantar.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val accion = intent.action ?: return
        Log.i(TAG, "Recibido $accion; levantando el tablero")

        // El servicio primero: aunque el fabricante bloquee abrir pantallas
        // desde segundo plano, el puente y las actualizaciones quedan vivos.
        DashService.arrancar(context)

        // La alarma periodica SOLO revive el servicio. Abrir la pantalla cada
        // diez minutos le quitaria el radio al conductor a media curva, que
        // seria mucho peor que el problema que resuelve.
        if (accion == DashService.ACCION_RESUCITAR) return
        // Tras ACTUALIZARSE solo vuelve el servicio: una actualizacion no le
        // quita la pantalla a lo que el dueño este viendo (un video, el mapa).
        // El tablero se abre solo al ENCENDER el radio.
        if (accion == Intent.ACTION_MY_PACKAGE_REPLACED) return

        // El dueño puede decir en Ajustes que el tablero NO se abra solo. El
        // servicio ya quedo arrancado arriba: alertas y lecturas siguen.
        if (!Arranque.abrirAlEncender(context)) {
            Log.i(TAG, "abrir al encender: apagado en Ajustes; solo el servicio")
            return
        }

        val abrir = Intent(context, TableroActivity::class.java).apply {
            // Desde un receiver no hay actividad de origen.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(abrir) }
            .onFailure {
                // Android 10+ restringe abrir pantallas desde segundo plano.
                // En un head unit suele permitirse, pero si el fabricante lo
                // bloquea no hay que caerse: el usuario abrira la app y el
                // resto del flujo sigue funcionando igual.
                Log.w(TAG, "No se pudo abrir el tablero: ${it.message}")
            }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
