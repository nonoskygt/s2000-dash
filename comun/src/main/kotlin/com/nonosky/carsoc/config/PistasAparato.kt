package com.nonosky.carsoc.config

import com.nonosky.carsoc.bt.ObdPairing

/**
 * Que pinta tiene cada aparato que aparece en el selector Bluetooth.
 *
 * Es solo una PISTA para ordenar la lista y ponerle una etiqueta: el que
 * decide es el dueño tocando la fila. El proyecto ya se quemo una vez
 * eligiendo solo —el vigilante viejo se quedaba con el primer BMS que veia—
 * y no se repite.
 *
 * Vive aparte de la pantalla para poder probarlo sin radio.
 */
object PistasAparato {

    /** Un aparato visto en el aire o en la lista de emparejados. */
    data class Aparato(
        val mac: String,
        val nombre: String?,
        /** CLASICO, BLE, DUAL o DESCONOCIDO. */
        val tipo: String,
        /** Null si no se midio (los emparejados que no estan cerca). */
        val rssi: Int?,
        val emparejado: Boolean,
        /** UUID de servicio anunciados, en minusculas y completos. */
        val uuids: List<String> = emptyList(),
    )

    private const val SPP = "00001101-"
    private const val ALPICOOL = "00001234-"
    private const val ALPICOOL_ALT = "0000fff0-"
    private const val JBD = "0000ff00-"

    /**
     * Una frase corta si el aparato tiene pinta de servir para [papel], o
     * null si nada lo delata. Nunca dice "es": dice "parece".
     */
    fun pista(papel: Emparejados.Papel, a: Aparato): String? {
        val nombre = a.nombre?.uppercase().orEmpty()
        val uuids = a.uuids.map { it.lowercase() }
        return when (papel) {
            Emparejados.Papel.AdaptadorObd -> when {
                ObdPairing.nombrePareceObd(a.nombre) -> "parece un adaptador OBD"
                uuids.any { it.startsWith(SPP) } -> "ofrece puerto serie (podría ser OBD)"
                else -> null
            }
            Emparejados.Papel.Nevera -> when {
                uuids.any { it.startsWith(ALPICOOL) } -> "parece una nevera Alpicool"
                nombre.contains("ALPICOOL") || nombre.startsWith("A1-") ->
                    "parece una nevera Alpicool"
                uuids.any { it.startsWith(ALPICOOL_ALT) } -> "servicio fff0 (podría ser la nevera)"
                else -> null
            }
            Emparejados.Papel.BancoArranque, Emparejados.Papel.BancoVivienda -> when {
                uuids.any { it.startsWith(JBD) } -> "parece un BMS JBD"
                nombre.contains("JBD") || nombre.contains("XIAOXIANG") -> "parece un BMS JBD"
                else -> null
            }
        }
    }

    /**
     * Orden de la lista: primero lo que tiene pista para este papel, luego
     * lo emparejado, y dentro de cada grupo el mas cercano arriba. En una
     * pantalla de carro la primera fila es la que se toca sin pensar.
     */
    fun ordenar(papel: Emparejados.Papel, lista: Collection<Aparato>): List<Aparato> =
        lista.sortedWith(
            compareByDescending<Aparato> { pista(papel, it) != null }
                .thenByDescending { it.emparejado }
                .thenByDescending { it.rssi ?: Int.MIN_VALUE }
                .thenBy { it.nombre ?: "￿" },
        )

    /** Barras de señal 0..4 a partir del RSSI. Null = no se midio. */
    fun barras(rssi: Int?): Int? = when {
        rssi == null -> null
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        rssi >= -90 -> 1
        else -> 0
    }

    /**
     * Direccion BLE ALEATORIA ESTATICA: los dos bits altos del primer byte a 1.
     *
     * Importa porque Android 9 no deja decirle a connectGatt el tipo de
     * direccion: si un aparato asi no salio antes en un barrido, la pila lo
     * llama como publico y nunca conecta. La nevera Alpicool es de estas.
     */
    fun esAleatoriaEstatica(mac: String): Boolean {
        val b = mac.trim().take(2).toIntOrNull(16) ?: return false
        return (b and 0xC0) == 0xC0
    }
}
