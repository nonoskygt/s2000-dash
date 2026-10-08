package com.nonosky.s2000dash

import com.nonosky.carsoc.Motor

/**
 * Constantes del F20C (Honda S2000 AP1, 1999-2003).
 *
 * Expone la API de motor que la libreria `:comun` le pide a cada app; lo
 * propio de este carro son los numeros.
 *
 * Todo lo ajustable del comportamiento del tablero vive aqui. Si un valor
 * resulta estar mal en el carro, se corrige una linea y nada mas: ni la
 * vista ni el scheduler traen numeros magicos propios.
 */
object MotorF20C : Motor {

    /** Maximo del tacometro. La carátula se dibuja de 0 a este valor. */
    override val rpmMax = 9_000

    /**
     * Enganche del VTEC.
     *
     * En el AP1 esto ES un acontecimiento: pasa una vez por marcha, con el
     * pedal a fondo, y por eso el tablero enciende el tacometro entero
     * cuando engancha.
     */
    override val rpmVtec = 5_850

    /**
     * Umbral de suelta.
     *
     * El F20C no necesita histeresis de verdad —engancha una vez y se queda
     * hasta el cambio de marcha— pero la API es comun a los dos carros, asi
     * que se define un margen pequeño en lugar de dejar el hueco.
     */
    override val rpmVtecSuelta = 5_750

    /** Inicio de la zona roja pintada en la carátula. */
    override val rpmRedline = 8_300

    /** Corte de combustible. Cerca de aqui el shift light parpadea. */
    override val rpmFuelCut = 9_000

    /** Umbral ambar del shift light. Debajo de esto el arco va verde. */
    override val rpmShiftAmber = 7_500

    /** Carga minima (%) para considerar el VTEC enganchado. */
    override val vtecMinLoadPct = 60

    /** Antiguedad (ms) a partir de la cual un valor se dibuja en gris. */
    override val staleAfterMs = 3_000L

    /** Zona normal de temperatura de refrigerante (°C), para la barra. */
    override val coolantHighC = 105

    /**
     * Escala de color del agua, pensada para el F20C.
     *
     * El termostato del AP1 abre sobre los 82 grados y la temperatura de
     * trabajo se asienta entre 85 y 95. Por encima de 100 el ventilador ya
     * deberia estar corriendo, y 105 es donde empieza el problema de verdad.
     */
    override val coolantTibioC = 82
    override val coolantAvisoC = 100

    /**
     * Estequiometrica de la gasolina, para el reloj de mezcla.
     *
     * ⚠️ EN ESTE CARRO EL RELOJ NO TIENE FUENTE. El AP1 lleva sonda de banda
     * ESTRECHA: da un voltaje que solo dice de que lado de la
     * estequiometrica esta, no una relacion. Su mapa de PIDs se corta en
     * 0x20, asi que tampoco existe el 0134.
     *
     * Se definen las constantes para que la API sea comun, pero el perfil de
     * este carro declara `tieneAfrReal = false` y la esfera va apagada. La
     * fila de MEZCLA se calcula con la suma de los ajustes de combustible,
     * que es lo unico que este motor mide de verdad.
     */
    override val afrEstequiometrica = 14.7f
    override val afrMin = 10.0f
    override val afrMax = 20.0f
}
