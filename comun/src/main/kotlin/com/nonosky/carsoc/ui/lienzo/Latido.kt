package com.nonosky.carsoc.ui.lienzo

/**
 * EL RELOJ DE LOS PARPADEOS, Y QUIEN LLEVA LA CUENTA DE SI ALGUIEN PARPADEA.
 *
 * ## Para que existe
 *
 * La variante Canvas nacio con un argumento: que es mas ligera que el WebView
 * y que por eso cuida el radio, que ya se apago dos veces por calor. Al
 * medirlo por fin —en el emulador, con el carro parado y sin un solo sensor
 * conectado— salio esto:
 *
 * ```
 *            hilos     PSS      CPU (30 s)
 *   HTML       56    83,6 MB      0,5 %
 *   Canvas     32    29,6 MB      3,4 %
 * ```
 *
 * Memoria: la mitad de hilos y **un tercio** de la huella. Eso se cumplio.
 * CPU: **siete veces mas**. Y la CPU es justo lo que calienta el radio.
 *
 * La causa no era el dibujo, sino cuando se dibuja: el lienzo repintaba la
 * pantalla entera cinco veces por segundo PASARA LO QUE PASARA, mientras que
 * el HTML solo recompone cuando algun nodo cambia — y con el carro parado no
 * cambia ninguno. El tablero estaba repintando cuarenta veces seguidas
 * exactamente los mismos guiones.
 *
 * Asi que el lienzo repinta cuando hay MOTIVO: o los datos cambiaron, o hay
 * algo parpadeando.
 *
 * ## Por que la cuenta la llevan los pintores y no una lista de condiciones
 *
 * Lo obvio habria sido preguntarle a los datos "¿hay alguna alarma?" en un
 * solo sitio. Y habria vuelto a pasar lo de siempre en este proyecto: el dia
 * que un pintor empiece a parpadear algo nuevo, esa lista se queda vieja y el
 * parpadeo nuevo **se congela sin que nadie lo note** — un aviso que no se
 * mueve no parece un fallo, parece un aviso.
 *
 * Aqui no puede pasar: quien parpadea es quien pide la hora, y pedirla deja
 * huella. Un pintor que empiece a parpadear queda apuntado por el mismo acto
 * de hacerlo.
 *
 * ⚠️ De ahi la unica regla de este fichero: **llamar a [parpadeo] solo cuando
 * el resultado va a cambiar lo que se pinta.** Preguntarlo "por si acaso" y
 * luego no usarlo mantiene el tablero repintando para siempre, que es
 * exactamente el defecto que esto viene a arreglar.
 *
 * No es seguro entre hilos a proposito: se escribe y se lee en el hilo de
 * interfaz, dentro del mismo cuadro, y un `@Volatile` aqui solo pagaria una
 * barrera por parpadeo sin comprar nada.
 */
object Latido {

    /**
     * Medio segundo encendido, medio apagado.
     *
     * Dos hercios es el ritmo de un aviso que se lee de reojo sin marear. Y
     * es UNO para todo el tablero: si cada pintor tuviera el suyo, dos avisos
     * a la vez parpadearian a destiempo y pareceria que dicen cosas distintas.
     */
    const val MS = 500L

    private var alguienPidio = false

    /** ¿Toca fase encendida? Llamarlo APUNTA que hay algo que parpadea. */
    fun parpadeo(ahora: Long): Boolean {
        alguienPidio = true
        return (ahora / MS) % 2L == 0L
    }

    /** Lo llama la vista al empezar cada cuadro. */
    fun nuevoCuadro() {
        alguienPidio = false
    }

    /**
     * ¿Alguien parpadeo en el ULTIMO cuadro pintado?
     *
     * Se lee entre cuadros, asi que sigue valiendo aunque se salten muchos:
     * mientras el ultimo dibujo tuviera algo parpadeando, hay que seguir
     * dibujando.
     */
    val alguienParpadea: Boolean get() = alguienPidio
}
