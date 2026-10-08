package com.nonosky.carsoc

/**
 * Un carro para las pruebas de la libreria comun.
 *
 * La libreria no trae carro de fabrica —a proposito, ver [Carro]— asi que sus
 * pruebas instalan este. Sus cifras NO son de ningun motor real: las pruebas
 * que afirman numeros de un motor viven en la app de ese carro.
 */
object CarroDePrueba {

    object Perfil : PerfilVehiculo {
        override val clave = "prueba"
        override val nombre = "Carro de prueba"
        override val vehiculo = "Carro de prueba"
        override val motor = "M0"
        override val protocoloEsperado = "ISO 9141-2"
        override val esCasaRodante = false
        override val tieneBancoVivienda = true
        override val tieneAfrReal = true
        override val tieneNevera = true
        override val tieneTpms = true
        override val vtecEsAcontecimiento = false
        override val tokenDescubrimiento = "PRUEBA"
        override val tablaDtc = 0
        override val tablaSrs: Int? = null
        override val tema = "topografico"
    }

    object Motor : com.nonosky.carsoc.Motor {
        override val rpmMax = 7_000
        override val rpmVtec = 5_000
        override val rpmVtecSuelta = 4_900
        override val rpmRedline = 6_500
        override val rpmFuelCut = 6_800
        override val rpmShiftAmber = 6_000
        override val vtecMinLoadPct = 60
        override val staleAfterMs = 3_000L
        override val coolantHighC = 105
        override val coolantTibioC = 80
        override val coolantAvisoC = 100
        override val afrEstequiometrica = 14.7f
        override val afrMin = 10.0f
        override val afrMax = 20.0f
    }

    fun instalar() = Carro.instalar(Perfil, Motor)
}
