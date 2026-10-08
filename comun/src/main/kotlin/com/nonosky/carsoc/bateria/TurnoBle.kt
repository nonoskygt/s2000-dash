package com.nonosky.carsoc.bateria

import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * El turno para hablar por BLE. UNO a la vez, en todo el proceso.
 *
 * ⚠️ POR QUE EXISTE, medido en el carro.
 *
 * Con tres lectores independientes —banco de vivienda, banco de arranque y
 * nevera— cada uno abriendo su GATT cuando le tocaba, el de vivienda dejo de
 * leer del todo mientras el de arranque seguia contestando, y el barrido de
 * `/buscar` empezo a devolver CERO aparatos con los tres BMS a metro y medio.
 * No es que los aparatos se apagaran: es que la radio estaba saturada.
 *
 * La especificacion de Bluetooth solo admite **un `LE Create Connection`
 * pendiente a la vez**; lanzar el segundo antes de que llegue el
 * `LE Connection Complete` del primero devuelve `Command Disallowed`. Es la
 * causa mas citada de "solo me conecta a N aparatos", y la solucion conocida
 * es serializar el establecimiento en vez de subir limites.
 *
 * Asi que aqui no se compite: se hace cola. Cuesta unos segundos de latencia
 * en datos que cambian despacio —un banco de litio y una nevera— y a cambio
 * los tres leen siempre.
 */
object TurnoBle {

    private val cerrojo = ReentrantLock(true)   // justo: el que lleva mas esperando, entra

    /**
     * Ejecuta [bloque] con la radio para el solo.
     *
     * Si el turno no llega en [esperaMaxMs], NO se ejecuta y se devuelve
     * null: mas vale saltarse una lectura que encolar hilos sin fin cuando
     * algo se atasca. El dato se pierde y el siguiente ciclo lo recupera.
     */
    fun <T> conLaRadio(quien: String, esperaMaxMs: Long = ESPERA_MAX_MS, bloque: () -> T): T? {
        if (!cerrojo.tryLock(esperaMaxMs, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "$quien se quedo sin turno tras ${esperaMaxMs}ms")
            return null
        }
        return try {
            bloque()
        } finally {
            cerrojo.unlock()
            // Respiro entre enlaces. Sin el, el siguiente Create Connection
            // sale antes de que el controlador haya terminado de cerrar el
            // anterior, que es justo la carrera que se quiere evitar.
            runCatching { Thread.sleep(RESPIRO_MS) }
        }
    }

    private const val TAG = "TurnoBle"
    const val ESPERA_MAX_MS = 45_000L
    const val RESPIRO_MS = 900L
}
