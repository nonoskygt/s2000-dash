package com.nonosky.s2000dash

import com.nonosky.carsoc.PerfilVehiculo

/**
 * Quien es este carro: lo que la libreria `:comun` necesita saber del
 * S2000 y que esta app le entrega al arrancar (`Carro.instalar`).
 *
 * Ninguna clase de `:comun` sabe en que carro corre: pregunta a
 * `Carro.perfil`.
 *
 * Aqui no hay ni una MAC: la bateria y el adaptador OBD se eligen en el menu
 * de emparejamiento.
 */
object PerfilS2000 : PerfilVehiculo {

    override val clave = "s2000"
    override val nombre = "S2000 Dash"
    override val vehiculo = "Honda S2000 AP1"
    override val motor = "F20C"

    /** ISO 9141-2, confirmado en el carro por `ATDP`. */
    override val protocoloEsperado = "ISO 9141-2"

    /** Es un roadster: aqui manda el motor. */
    override val esCasaRodante = false

    /** Un solo banco de litio, el de arranque. */
    override val tieneBancoVivienda = false

    /**
     * ⚠️ NO. Este carro lleva sonda de banda ESTRECHA (`0114`): un voltaje
     * que solo dice de que lado de la estequiometrica esta. Sacarle un AFR
     * seria inventarlo. Y su mapa de PIDs se corta en 0x20, asi que el
     * `0134` de banda ancha tampoco existe — se comprobo preguntando `0100`
     * en vez de suponerlo.
     *
     * Con esto en false, el reloj de mezcla se dibuja apagado y manda la
     * fila de ajustes de combustible, que es lo unico que este motor mide.
     */
    override val tieneAfrReal = false

    /** Sin nevera: es un descapotable de dos plazas. */
    override val tieneNevera = false

    /** Receptor TPMS por USB (CH340). */
    override val tieneTpms = true

    /**
     * En el AP1 el VTEC ES un acontecimiento: engancha a 5.850 con el pedal
     * a fondo, una vez por marcha. Aqui el aviso puede permitirse ser
     * espectacular sin volverse ruido.
     */
    override val vtecEsAcontecimiento = true



    /** Que tema de dibujo usa. Ver el paquete `ui/tema`. */
    override val tema = "cyberpunk"

    /** Ver [PerfilVehiculo.tokenDescubrimiento]. Distinto del otro carro, siempre. */
    override val tokenDescubrimiento = "S2000DASH"

    override val tablaDtc = R.raw.dtc

    /** Sin tabla de la bolsa de aire todavia: el diagnostico no enseña el boton. */
    override val tablaSrs: Int? = null
}
