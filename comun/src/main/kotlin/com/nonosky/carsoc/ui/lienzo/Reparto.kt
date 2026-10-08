package com.nonosky.carsoc.ui.lienzo

/**
 * EL REPARTIDOR DE CAJAS de la variante Canvas.
 *
 * ## Por que existe (la leccion que ordena todo este fichero)
 *
 * El tablero viejo ya estaba escrito "sin pixeles fijos" —lo exigia
 * su diseño por escrito— y aun asi se rompio al pasar de 1280x480 a 1024x600:
 * los numeros se pisaban unos encima de otros. La causa, medida sobre el
 * codigo: 57 medidas colgaban del ALTO de pantalla y solo 10 del ANCHO. Las
 * columnas salian de `w / 3` escrito a mano, pero TODA la tipografia salia de
 * `h`. A 2,67:1 cuadraba de milagro; a 1,71:1 la columna se estrecha un 20 %
 * mientras la letra crece un 25 %, y lo que antes se rozaba ya se solapa.
 *
 * **SER RELATIVO NO ES SER RESPONSIVE.** Una medida relativa a la pantalla
 * entera sigue siendo una medida ciega: no sabe cuanto sitio tiene de verdad
 * el bloque que va a pintar. Lo unico que arregla eso es que cada pintor
 * reciba SU rectangulo y saque de el —y solo de el— sus tamaños.
 *
 * Este fichero es la mitad de ese arreglo: reparte la pantalla en cajas por
 * PESOS. La otra mitad esta en [Pincel], que deriva la tipografia de la caja
 * que recibe y nunca del alto de pantalla.
 *
 * ## Reglas de la casa
 *
 * - **Codigo PURO.** Ni un import de Android. Se prueba en la JVM
 *   (`RepartoTest`), que es como se comprueba la regla 5 —1280x480, 1024x600
 *   y 800x480— sin encender un radio.
 * - **El reparto NO se hace en `onDraw`.** [filas] y [columnas] devuelven
 *   listas nuevas, y eso es asignar memoria. La vista reparte UNA vez en
 *   `onSizeChanged`, guarda las cajas en campos, y `onDraw` solo lee. Un
 *   tablero que repinta durante horas no puede fabricar basura por cuadro.
 * - **No se inventa sitio.** Si el reparto pedido no cabe, las cajas salen
 *   [Caja.NADA] —invalidas y detectables— en vez de salir negativas y
 *   pintarse encima unas de otras en silencio. El pintor lo ve con
 *   [Caja.valida] y marca el fallo con `Pincel.marcaDeQueNoCabe`.
 */
object Reparto {

    /**
     * Corta [caja] en columnas verticales con anchos proporcionales a [pesos].
     *
     * Los pesos son numeros sin unidad: `[396, 264, 320]` reparte igual que
     * `[3.96, 2.64, 3.20]`. Se puede pegar tal cual el presupuesto horizontal
     * del tablero HTML sin traducirlo a fracciones a mano.
     *
     * [hueco] es el aire ENTRE columnas, en pixeles, y se resta del reparto
     * antes de proporcionar: con 3 columnas hay 2 huecos. Un hueco negativo o
     * NaN se trata como cero — dos cajas montadas una encima de otra es
     * justamente el fallo que este fichero existe para impedir.
     *
     * La primera caja empieza EXACTAMENTE en `caja.x0` y la ultima termina
     * EXACTAMENTE en `caja.x1`: los bordes se calculan por fraccion acumulada
     * y los extremos se clavan, asi que subdividir no pierde pixeles por el
     * camino ni deja una raya de fondo asomando en el borde.
     *
     * @return una caja por peso, en orden. Todas [Caja.NADA] si no cabe.
     */
    fun columnas(caja: Caja, pesos: FloatArray, hueco: Float = 0f): List<Caja> {
        val bordes = repartir(caja.x0, caja.x1, pesos, hueco, caja.valida)
            ?: return List(pesos.size) { Caja.NADA }
        return List(pesos.size) { i -> Caja(bordes[i * 2], caja.y0, bordes[i * 2 + 1], caja.y1) }
    }

    /**
     * Corta [caja] en filas horizontales. Lo mismo que [columnas] en el otro
     * eje, con las mismas garantias de extremos y de hueco.
     */
    fun filas(caja: Caja, pesos: FloatArray, hueco: Float = 0f): List<Caja> {
        val bordes = repartir(caja.y0, caja.y1, pesos, hueco, caja.valida)
            ?: return List(pesos.size) { Caja.NADA }
        return List(pesos.size) { i -> Caja(caja.x0, bordes[i * 2], caja.x1, bordes[i * 2 + 1]) }
    }

    /** [n] columnas iguales. Atajo de [columnas] con todos los pesos a 1. */
    fun columnasIguales(caja: Caja, n: Int, hueco: Float = 0f): List<Caja> =
        columnas(caja, unos(n), hueco)

    /** [n] filas iguales. */
    fun filasIguales(caja: Caja, n: Int, hueco: Float = 0f): List<Caja> =
        filas(caja, unos(n), hueco)

    /**
     * Rejilla de [filas] x [columnas] iguales, en orden de LECTURA: primero
     * la fila de arriba de izquierda a derecha, luego la siguiente.
     *
     * Es el reparto de las cuatro llantas, y el orden importa: la posicion en
     * pantalla ES el dato —arriba-izquierda es la delantera izquierda— asi
     * que el indice tiene que corresponder con la esquina del carro sin que
     * nadie tenga que acordarse de una convencion.
     */
    fun rejilla(caja: Caja, filas: Int, columnas: Int, hueco: Float = 0f): List<Caja> {
        if (filas <= 0 || columnas <= 0) return emptyList()
        val bandas = filasIguales(caja, filas, hueco)
        val salida = ArrayList<Caja>(filas * columnas)
        for (banda in bandas) salida.addAll(columnasIguales(banda, columnas, hueco))
        return salida
    }

    /**
     * ¿Cabe partir un largo de [largo] en [partes] con [hueco] entre ellas?
     *
     * Se pregunta ANTES de repartir cuando el pintor quiere avisar por su
     * cuenta; si no se pregunta, el aviso llega igual en forma de cajas
     * [Caja.NADA]. Las dos puertas llevan al mismo sitio: nunca a una caja de
     * ancho negativo pintada en silencio.
     */
    fun cabe(largo: Float, partes: Int, hueco: Float): Boolean {
        if (partes <= 0 || !largo.isFinite()) return false
        val h = if (hueco.isFinite() && hueco > 0f) hueco else 0f
        return largo - h * (partes - 1) > 0f
    }

    // --- Cocina -------------------------------------------------------------

    private val UNO = floatArrayOf(1f)

    private fun unos(n: Int): FloatArray =
        if (n == 1) UNO else FloatArray(if (n < 0) 0 else n) { 1f }

    /**
     * El unico sitio del proyecto donde se divide un largo en trozos.
     *
     * Devuelve pares `[ini0, fin0, ini1, fin1, ...]`, o `null` si el reparto
     * es imposible. Que sea UNO solo no es estetica: mientras hubo aritmetica
     * de reparto suelta por la vista —`w / 3` aqui, `ancho * 0.42f` alla— no
     * habia forma de probarla ni de arreglarla en un sitio.
     */
    private fun repartir(
        inicio: Float,
        fin: Float,
        pesos: FloatArray,
        hueco: Float,
        cajaValida: Boolean,
    ): FloatArray? {
        val n = pesos.size
        if (n == 0 || !cajaValida) return null
        if (!inicio.isFinite() || !fin.isFinite()) return null

        val aire = if (hueco.isFinite() && hueco > 0f) hueco else 0f
        val largo = fin - inicio
        val util = largo - aire * (n - 1)
        if (util <= 0f) return null

        var total = 0f
        for (p in pesos) if (p.isFinite() && p > 0f) total += p
        if (total <= 0f) return null

        val bordes = FloatArray(n * 2)
        var acumulado = 0f
        for (i in 0 until n) {
            val antes = acumulado
            val peso = pesos[i]
            if (peso.isFinite() && peso > 0f) acumulado += peso
            // Fraccion ACUMULADA sobre el total, no suma de anchos: sumando
            // trozo a trozo el error de coma flotante se acumula y la ultima
            // caja acaba desalineada con el borde por medio pixel. De reojo
            // eso es una raya de fondo asomando en el canto de una tarjeta.
            val ini = inicio + (antes / total) * util + i * aire
            val termina = inicio + (acumulado / total) * util + i * aire
            // Los extremos se clavan: el primero y el ultimo son los del
            // padre, exactos, sin depender de como redondee la division.
            bordes[i * 2] = if (i == 0) inicio else ini
            bordes[i * 2 + 1] = if (i == n - 1) fin else termina
        }
        return bordes
    }
}

/**
 * Un rectangulo con coordenadas ABSOLUTAS de pantalla, en pixeles.
 *
 * Es la moneda de toda la variante Canvas: cada pintor recibe una Caja y de
 * ella —de su [alto], nunca del alto de pantalla— saca sus tamaños de letra.
 * Esa es la regla 2 del encargo y el arreglo de una linea del defecto que
 * rompio el tablero viejo.
 *
 * Inmutable a proposito. Las cajas se calculan al cambiar de tamaño la vista
 * y se guardan; si fueran mutables acabarian recalculandose por cuadro "por
 * si acaso", que es como se llega a asignar memoria en `onDraw`.
 */
class Caja(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
) {

    val ancho: Float get() = x1 - x0
    val alto: Float get() = y1 - y0
    val cx: Float get() = (x0 + x1) * 0.5f
    val cy: Float get() = (y0 + y1) * 0.5f

    /** El lado corto. Para lo que tiene que ser cuadrado (iconos, aspas). */
    val menor: Float get() = if (ancho < alto) ancho else alto

    /**
     * ¿Se puede pintar aqui?
     *
     * Falso si el rectangulo esta del reves, si tiene area cero o si alguna
     * coordenada no es un numero. Un pintor comprueba esto ANTES de pintar y,
     * si sale falso, llama a `Pincel.marcaDeQueNoCabe` sobre la caja padre.
     * Es la regla 4: el fallo se ve, no se esconde.
     */
    val valida: Boolean =
        x0.isFinite() && y0.isFinite() && x1.isFinite() && y1.isFinite() &&
            x1 > x0 && y1 > y0

    fun contiene(x: Float, y: Float): Boolean = x >= x0 && x <= x1 && y >= y0 && y <= y1

    /** La misma caja con [m] pixeles menos por los cuatro lados. */
    fun margen(m: Float): Caja = margen(m, m)

    fun margen(mx: Float, my: Float): Caja = Caja(x0 + mx, y0 + my, x1 - mx, y1 - my)

    /**
     * Margen en FRACCION del lado menor.
     *
     * Se usa para el aire interior de una tarjeta: asi el respiro encoge con
     * la tarjeta en vez de comerse media caja en una pantalla pequeña.
     */
    fun margenRelativo(f: Float): Caja = margen(menor * f)

    /**
     * Subcaja por fracciones del propio rectangulo, con (0,0) arriba a la
     * izquierda y (1,1) abajo a la derecha.
     *
     * Para el detalle fino DENTRO de un bloque ya repartido —donde cae una
     * aguja, donde va un icono—, no para repartir secciones: eso es [filas] y
     * [columnas], que respetan los huecos y detectan lo imposible.
     */
    fun sub(fx0: Float, fy0: Float, fx1: Float, fy1: Float): Caja = Caja(
        x0 + ancho * fx0, y0 + alto * fy0,
        x0 + ancho * fx1, y0 + alto * fy1,
    )

    /** La banda de [px] pixeles pegada al borde de arriba. Un rotulo, p.ej. */
    fun bandaSuperior(px: Float): Caja = Caja(x0, y0, x1, y0 + px)

    /** Lo que queda debajo de esa banda. */
    fun bajo(px: Float): Caja = Caja(x0, y0 + px, x1, y1)

    /** Azucar de [Reparto.columnas] para escribir el reparto en una linea. */
    fun columnas(vararg pesos: Float, hueco: Float = 0f): List<Caja> =
        Reparto.columnas(this, pesos, hueco)

    /** Azucar de [Reparto.filas]. */
    fun filas(vararg pesos: Float, hueco: Float = 0f): List<Caja> =
        Reparto.filas(this, pesos, hueco)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Caja) return false
        return x0 == other.x0 && y0 == other.y0 && x1 == other.x1 && y1 == other.y1
    }

    override fun hashCode(): Int {
        var r = x0.toRawBits()
        r = 31 * r + y0.toRawBits()
        r = 31 * r + x1.toRawBits()
        r = 31 * r + y1.toRawBits()
        return r
    }

    override fun toString(): String = "Caja($x0, $y0, $x1, $y1)"

    companion object {
        /**
         * La caja que NO existe: lo que devuelve un reparto imposible.
         *
         * No es un rectangulo degenerado cualquiera — es el aviso de que lo
         * que se pidio no cabia. Se reconoce con [valida] y se pinta con
         * `Pincel.marcaDeQueNoCabe` sobre el padre.
         */
        val NADA = Caja(0f, 0f, 0f, 0f)

        /** La pantalla entera. El punto de partida de cualquier reparto. */
        fun pantalla(ancho: Float, alto: Float): Caja = Caja(0f, 0f, ancho, alto)
    }
}
