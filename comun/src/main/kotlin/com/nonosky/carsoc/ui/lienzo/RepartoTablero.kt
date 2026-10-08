package com.nonosky.carsoc.ui.lienzo

import com.nonosky.carsoc.Carro

/**
 * DONDE VA CADA SECCION DE LA PANTALLA. El reparto maestro de la variante
 * Canvas, y el unico sitio del proyecto donde se decide la maqueta del
 * tablero.
 *
 * ## Por que es una clase PURA y no cuatro divisiones dentro de la vista
 *
 * Porque asi se puede COMPROBAR sin encender un radio. El tablero viejo se
 * rompio al pasar de 1280x480 a 1024x600 y nadie pudo verlo hasta que el dueño
 * mando una foto: su reparto vivia suelto dentro de `onDraw` (`w / 3` aqui,
 * `h * 0.42f` alla) y no habia forma de preguntarle nada. Este fichero no tiene
 * ni un `import android.*`, asi que `RepartoTableroTest` lo corre en la JVM en
 * las tres pantallas del encargo y falla ANTES de que el APK salga del taller.
 *
 * ## El presupuesto sale del tablero HTML, no de mi criterio
 *
 * Los pesos son los del CSS de `tablero.html`, pegados tal cual:
 *
 * ```
 *   vertical:   12 pad + 44 cabecera + 10 + 296 fila1 + 10 + 216 fila2 + 12 pad = 600
 *   horizontal: 12 pad + 396 + 10 + 264 + 10 + 320 + 12 pad = 1024
 * ```
 *
 * Son numeros SIN UNIDAD: `[396, 264, 320]` reparte igual un cuerpo de 1000 px
 * que uno de 500. Nadie los divide a mano; los reparte [Reparto], que resta el
 * hueco una vez por junta y clava los extremos al padre.
 *
 * ## En que se aparta del HTML, y por que
 *
 * El HTML pinta seis tarjetas en dos filas. Los pintores de la variante Canvas
 * agrupan de otra forma —`PintaEnergia` lleva LOS DOS bancos, `PintaLlantas`
 * lleva llantas Y aceite— asi que aqui las columnas son de ALTO COMPLETO y cada
 * pintor parte la suya por dentro. El resultado en pantalla es el mismo dibujo;
 * lo que cambia es quien corta que.
 *
 * Y los botones de AVERIAS y AJUSTES, que en el HTML viven en el rotulo de la
 * tarjeta de motor, aqui se sacan a la cabecera: `PintaMotor` es dueño de su
 * tarjeta entera y meterle dos botones dentro seria pintar encima de lo suyo.
 *
 * ## Los dos carros reparten DISTINTO
 *
 * A un carro sin nevera ni banco de vivienda no se le reserva ni un
 * pixel para lo que no tiene: su columna del medio es del motor entera —"aqui
 * manda el motor", dice su perfil— y la de energia se estrecha porque solo
 * enseña un banco.
 *
 * Los dos repartos suman **980**, y no por casualidad: asi la COLUMNA DE LAS
 * LLANTAS cae exactamente en el mismo sitio en los dos carros (660/980 a
 * 980/980), y `TrazadoLlantasTest` —que calcula esa caja por su cuenta— sigue
 * midiendo la caja de verdad en los dos sabores. Si alguien cambia estos pesos,
 * que los cambie manteniendo la suma.
 *
 * ## Cuando se llama
 *
 * En `onSizeChanged`, NUNCA en `onDraw`: [Reparto] devuelve listas nuevas y eso
 * es asignar memoria. [reparte] ademas sale por la puerta si le vuelven a pedir
 * el mismo tamaño, asi que llamarlo de mas es barato — pero no es una excusa
 * para llamarlo por cuadro.
 */
class RepartoTablero {

    // --- Lo que se pidio la ultima vez --------------------------------------

    private var ultimoAncho = Float.NaN
    private var ultimoAlto = Float.NaN
    private var ultimaNevera = false

    /**
     * ¿Cabe el tablero en la pantalla que dieron?
     *
     * Falso si algun corte salio imposible. Entonces todas las cajas son
     * [Caja.NADA] y la vista pinta el aspa de la regla 4 sobre la pantalla
     * entera, en vez de dibujar seis tarjetas del reves.
     */
    var valido: Boolean = false
        private set

    /** ¿Este reparto lleva sitio para la nevera? */
    var conNevera: Boolean = false
        private set

    /** La pantalla entera, con el respiro de fuera ya quitado. */
    var marco: Caja = Caja.NADA
        private set

    /** La franja de arriba: cabecera + los botones. */
    var bandaAlta: Caja = Caja.NADA
        private set

    /** Lo que recibe `PintaNevera.cabecera`. */
    var cabecera: Caja = Caja.NADA
        private set

    /** Los botones juntos, para marcarlos de una si no caben. */
    var botonera: Caja = Caja.NADA
        private set

    var botonAverias: Caja = Caja.NADA
        private set

    var botonAjustes: Caja = Caja.NADA
        private set

    /** La X de cerrar el tablero, la ultima a la derecha. */
    var botonCerrar: Caja = Caja.NADA
        private set

    /** Todo lo que queda debajo de la cabecera. */
    var cuerpo: Caja = Caja.NADA
        private set

    var columnaIzquierda: Caja = Caja.NADA
        private set

    var columnaCentro: Caja = Caja.NADA
        private set

    var columnaDerecha: Caja = Caja.NADA
        private set

    /** `PintaEnergia`: uno o dos bancos, segun el carro. */
    var energia: Caja = Caja.NADA
        private set

    /** `PintaNevera.pintar`. [Caja.NADA] en el carro que no lleva nevera. */
    var nevera: Caja = Caja.NADA
        private set

    /** `PintaMotor`. */
    var motor: Caja = Caja.NADA
        private set

    /** `PintaLlantas`: llantas ARRIBA y aceite debajo, que las parte el. */
    var llantas: Caja = Caja.NADA
        private set

    /**
     * Reparte la pantalla. Idempotente: si el tamaño no cambio, no toca nada
     * ni asigna nada.
     *
     * @param conNevera si hay que reservarle sitio a la nevera. Sale de
     *   `Carro.perfil.tieneNevera`, pero entra por parametro para que la
     *   prueba pueda comprobar LOS DOS repartos desde cualquier sabor.
     */
    fun reparte(ancho: Float, alto: Float, conNevera: Boolean) {
        if (ancho == ultimoAncho && alto == ultimoAlto && conNevera == ultimaNevera) return
        ultimoAncho = ancho
        ultimoAlto = alto
        ultimaNevera = conNevera
        this.conNevera = conNevera

        if (!ancho.isFinite() || !alto.isFinite() || ancho <= 0f || alto <= 0f) {
            rendirse()
            return
        }

        // El respiro de fuera sale del ANCHO en los cuatro lados, igual que el
        // `padding:12px` del HTML sobre 1024. Que salga de una sola medida no
        // es descuido: es lo que hace que el marco conserve la forma de la
        // pantalla en vez de estrujarse mas por un lado que por otro.
        marco = Caja.pantalla(ancho, alto).margen(ancho * FRACCION_MARGEN)
        if (!marco.valida) {
            rendirse()
            return
        }

        val alturas = Reparto.filas(marco, PESOS_ALTURA, AIRE)
        bandaAlta = alturas[0]
        cuerpo = alturas[1]

        val arriba = Reparto.columnas(bandaAlta, PESOS_CABECERA, AIRE)
        cabecera = arriba[0]
        botonera = arriba[1]
        val botones = Reparto.columnas(botonera, PESOS_BOTONES, AIRE)
        botonAverias = botones[0]
        botonAjustes = botones[1]
        botonCerrar = botones[2]

        val columnas = Reparto.columnas(
            cuerpo,
            if (conNevera) PESOS_COLUMNAS_CON_NEVERA else PESOS_COLUMNAS_SIN_NEVERA,
            AIRE,
        )
        columnaIzquierda = columnas[0]
        columnaCentro = columnas[1]
        columnaDerecha = columnas[2]

        energia = columnaIzquierda
        llantas = columnaDerecha

        if (conNevera) {
            // La columna del medio se parte con el presupuesto vertical del
            // HTML: la nevera se lleva la fila de 296 y el motor la de 216.
            val centro = Reparto.filas(columnaCentro, PESOS_CENTRO, AIRE)
            nevera = centro[0]
            motor = centro[1]
        } else {
            nevera = Caja.NADA
            motor = columnaCentro
        }

        valido = marco.valida && bandaAlta.valida && cuerpo.valida &&
            cabecera.valida && botonAverias.valida && botonAjustes.valida && botonCerrar.valida &&
            energia.valida && motor.valida && llantas.valida &&
            (!conNevera || nevera.valida)
    }

    /** El reparto de ESTE carro. La vista llama a esta. */
    fun reparteEsteCarro(ancho: Float, alto: Float) =
        reparte(ancho, alto, Carro.perfil.tieneNevera)

    /**
     * Todo a [Caja.NADA].
     *
     * No se devuelven cajas negativas ni se "arregla" el reparto estirando
     * algo: un tablero que dibuja cajas del reves es peor que uno con un aspa
     * pintada, porque el aspa se ve y el otro se cree.
     */
    private fun rendirse() {
        valido = false
        marco = Caja.NADA
        bandaAlta = Caja.NADA
        cabecera = Caja.NADA
        botonera = Caja.NADA
        botonAverias = Caja.NADA
        botonAjustes = Caja.NADA
        botonCerrar = Caja.NADA
        cuerpo = Caja.NADA
        columnaIzquierda = Caja.NADA
        columnaCentro = Caja.NADA
        columnaDerecha = Caja.NADA
        energia = Caja.NADA
        nevera = Caja.NADA
        motor = Caja.NADA
        llantas = Caja.NADA
    }

    companion object {

        /**
         * El aire entre tarjetas. **El unico numero en pixeles de la maqueta**,
         * y va con su razon escrita: es el `gap:10px` del HTML, y un hueco es
         * separacion, no tipografia. La leccion del tablero viejo era que la
         * LETRA no puede colgar de la pantalla; una junta de 10 px se ve igual
         * de bien en 800 que en 1280 —del 1,3 % al 0,8 % del ancho— y hacerla
         * relativa solo conseguiria que en la pantalla chica se comiera sitio
         * que hace falta para los numeros.
         */
        const val AIRE = 10f

        /** El `padding:12px` sobre 1024 del HTML, en fraccion del ancho. */
        const val FRACCION_MARGEN = 0.0117f

        /** Cabecera y cuerpo: `44` y `296 + 10 + 216 = 542` del HTML. */
        val PESOS_ALTURA = floatArrayOf(44f, 542f)

        /**
         * Cabecera y botonera. Los botones se llevan 234 de 1000: AVERIAS y
         * AJUSTES unos 86 px cada uno a 1024 —el ancho que tiene el `.diagbtn`
         * del HTML con su texto y su relleno— y la X de cerrar, casi cuadrada.
         */
        val PESOS_CABECERA = floatArrayOf(766f, 234f)

        /** Averias, ajustes y la X de cerrar, de izquierda a derecha. */
        val PESOS_BOTONES = floatArrayOf(86f, 86f, 46f)

        /**
         * CON NEVERA: energia (dos bancos), nevera + motor, llantas + aceite.
         * Es el presupuesto horizontal del HTML tal cual.
         */
        val PESOS_COLUMNAS_CON_NEVERA = floatArrayOf(396f, 264f, 320f)

        /**
         * SIN NEVERA: energia (un banco), motor, llantas + aceite.
         *
         * ⚠️ SUMA 980, LA MISMA QUE EL OTRO, y tiene que seguir sumandola: es
         * lo que hace que la columna de las llantas caiga en el mismo sitio en
         * los dos carros. Lo que cambia es el reparto de los otros 660: sin
         * nevera que alojar, el motor se lleva 420 y la energia se estrecha a
         * 240 porque solo enseña un banco.
         */
        val PESOS_COLUMNAS_SIN_NEVERA = floatArrayOf(240f, 420f, 320f)

        /** Dentro de la columna del medio: nevera 296, motor 216. Del HTML. */
        val PESOS_CENTRO = floatArrayOf(296f, 216f)
    }
}
