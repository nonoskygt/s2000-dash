package com.nonosky.carsoc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Contesta el PIN del emparejamiento Bluetooth, declarado en el MANIFIESTO.
 *
 * Existe porque el emparejamiento del adaptador OBD fallaba una y otra vez con
 * "no se puede vincular con Steren Scan porque el PIN es incorrecto" — un
 * mensaje que el dueño veia en bucle en la pantalla del radio.
 *
 * Ese mensaje es una PISTA, no un fallo cualquiera: significa que el Bluetooth
 * del radio SI alcanza al adaptador y llega hasta negociar. No falla por no
 * verlo. Falla en el PIN. Durante horas se dio por muerta la pila entera
 * cuando lo unico roto era este escalon.
 *
 * ¿Por que no lo contestaba el codigo que ya existia? Porque
 * [com.nonosky.carsoc.bt.ObdPairing] registra su receptor **en tiempo de
 * ejecucion**, y lo hace desde la Activity. Dos agujeros:
 *
 *   1. Con la pantalla en segundo plano, ese receptor **no existe**, asi que
 *      nadie contesta y el sistema saca su propio dialogo.
 *   2. Sin prioridad declarada, la UI del sistema atiende antes la difusion
 *      ordenada — y una vez que ella la toma, ya no hay nada que hacer.
 *
 * Declarado aqui, el receptor vive **siempre** y con prioridad alta, asi que
 * llega antes que los Ajustes y puede contestar y cortar la difusion.
 */
@SuppressLint("MissingPermission")
class EmparejadorReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Envuelto entero: esto corre en el hilo principal del proceso y una
        // excepcion suelta aqui se lleva el tablero.
        runCatching { atender(context, intent) }
            .onFailure { Log.w(TAG, "fallo atendiendo el emparejamiento: ${it.message}") }
    }

    private fun atender(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return

        val device: BluetoothDevice =
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
        val variante = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
        val mac = runCatching { device.address }.getOrNull() ?: "?"
        val nombre = runCatching { device.name }.getOrNull() ?: "?"

        EstadoActual.anotarConfirmador("PAIRING_REQUEST de $nombre ($mac) variante=$variante")

        // Solo se toca lo que el tablero pidio emparejar. Contestar el PIN de
        // CUALQUIER aparato que pida vincularse seria abrirle la puerta a
        // quien pase por la calle.
        val objetivo = EstadoActual.macAEmparejar
        if (objetivo != null && !mac.equals(objetivo, ignoreCase = true)) {
            EstadoActual.anotarConfirmador("no es el que buscamos ($objetivo); no se toca")
            return
        }

        when (variante) {
            VARIANTE_PIN, VARIANTE_PASSKEY -> {
                val pin = EstadoActual.pinDeEmparejamiento
                val ok = runCatching {
                    device.setPin(pin.toByteArray(Charsets.US_ASCII))
                }.getOrDefault(false)
                EstadoActual.anotarConfirmador("setPin('$pin') -> $ok")
                if (ok) callarAlSistema()
            }

            VARIANTE_CONSENTIMIENTO, VARIANTE_COMPARAR_NUMEROS -> {
                // "Just Works" y comparacion numerica: no hay PIN que teclear,
                // solo hay que decir que si.
                val ok = runCatching { device.setPairingConfirmation(true) }.getOrDefault(false)
                EstadoActual.anotarConfirmador("setPairingConfirmation(true) -> $ok")
                if (ok) callarAlSistema()
            }

            else -> {
                // Variante que no sabemos contestar: se deja al sistema en vez
                // de estorbar. Y se anota, que es como se descubre cual es.
                EstadoActual.anotarConfirmador("variante $variante desconocida; la atiende el sistema")
            }
        }
    }

    /**
     * Corta la difusion para que los Ajustes no saquen su dialogo encima.
     *
     * Si la difusion no es ordenada esto lanza, y no pasa nada: lo importante
     * —el PIN— ya se contesto.
     */
    private fun callarAlSistema() {
        runCatching {
            abortBroadcast()
            EstadoActual.anotarConfirmador("difusion cortada: el sistema no sacara dialogo")
        }
    }

    private companion object {
        const val TAG = "Emparejador"

        /** Emparejamiento heredado: hay que teclear un PIN. */
        const val VARIANTE_PIN = 0

        /** Passkey: tambien es un numero que se teclea. */
        const val VARIANTE_PASSKEY = 1

        /** Comparacion numerica de SSP: solo hay que confirmar. */
        const val VARIANTE_COMPARAR_NUMEROS = 2

        /** Consentimiento de SSP ("Just Works"): solo hay que confirmar. */
        const val VARIANTE_CONSENTIMIENTO = 3
    }
}
