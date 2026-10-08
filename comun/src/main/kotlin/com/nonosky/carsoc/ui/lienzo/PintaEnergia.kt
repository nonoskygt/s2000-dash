package com.nonosky.carsoc.ui.lienzo

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.nonosky.carsoc.Carro

/**
 * LA SECCION DE ENERGIA de la variante Canvas: los bancos de bateria.
 *
 * Pinta UNO o DOS bancos segun [Carro.perfil.tieneBancoVivienda]. Hay
 * carros con dos —vivienda y arranque, cada uno con su BMS JBD— y carros con
 * solo el de arranque; en estos no aparece ni un hueco reservado para el que
 * no existe.
 *
 * Por banco, replicando la tarjeta del tablero HTML:
 * rotulo de region con el NOMBRE y la MAC que manda el servicio, el estado de
 * carga en grande, tension, potencia con signo y color, temperatura de
 * celdas, y la barra de nivel. La tarjeta de vivienda añade la autonomia,
 * dentro del recinto de DEDUCIDO porque nadie la mide.
 *
 * ## Como se coloca (la leccion del encargo)
 *
 * Aqui no hay ni una division de la pantalla. Todo sale de [Reparto] sobre la
 * [Caja] que llega, y toda la tipografia sale de [Pincel], que la deriva del
 * alto de la caja que recibe. Esta clase nunca ve el alto de pantalla: no
 * puede, no lo recibe.
 *
 * Y va un paso mas alla de repartir por pesos: **la forma de la caja decide
 * la forma del contenido.** Tres decisiones, todas por relacion de aspecto de
 * la caja concreta y ninguna por resolucion de pantalla:
 *
 * 1. Dos bancos LADO A LADO si la seccion es ancha; uno encima del otro si es
 *    alta.
 * 2. El estado de carga AL LADO de las tres metricas si la franja es
 *    apaisada; ENCIMA si es vertical.
 * 3. Las tres metricas en TRES COLUMNAS (rotulito arriba, cifra debajo, como
 *    el HTML) si la tarjeta tiene ancho; en TRES FILAS de `filaGrande`
 *    —etiqueta a la izquierda, numero a la derecha— si no lo tiene. La
 *    decision se toma sobre la TARJETA y no sobre la caja de metricas, para
 *    que las dos tarjetas, que miden lo mismo, elijan lo mismo.
 *
 * Eso es lo que no hacia el tablero viejo. El viejo tambien era "relativo",
 * pero siempre pintaba la MISMA forma: tres columnas de `w / 3` con la letra
 * sacada de `h`. Al pasar de 2,67:1 a 1,71:1 la columna se estrecho un 20 %
 * mientras la letra crecio un 25 %, y "ALTERNADOR 13.8 V" quedo una palabra
 * encima de otra. Una caja estrecha aqui no estruja el mismo dibujo: cambia
 * de dibujo.
 *
 * ## El techo de alto, que es la otra mitad de la regla 2
 *
 * Que la tipografia salga de la caja arregla el defecto viejo, pero abre uno
 * nuevo si se aplica a ciegas: [Pincel] deriva el tamaño del ALTO y luego
 * tiene que meter el texto en el ANCHO. En una caja mas alta que ancha eso
 * pide una letra que no cabe, el pincel la encoge por debajo de su tolerancia
 * y **pinta un aspa por un fallo que no existe**. Medido aqui antes de
 * corregirlo: "100 %" en una caja de 141x119 salia a 80 px y necesitaba 185
 * de ancho.
 *
 * Por eso las cajas de las que va a salir una cifra pasan antes por [achica],
 * que le pone TECHO al alto en fraccion del ancho y centra lo que sobra —que
 * es, literalmente, lo que hace el HTML: su rejilla de metricas mide 50 px
 * dentro de una franja de 112 y se centra—. La franja es sitio; no es tamaño
 * de letra. **Derivar de la caja no es derivar de una sola de sus dos
 * medidas.**
 *
 * ## Sin asignar memoria por cuadro
 *
 * [Reparto] fabrica listas, asi que **el reparto se hace UNA vez** y se
 * guarda: [pinta] compara la caja que le llega con la del reparto anterior y
 * solo vuelve a repartir si cambio. Un cuadro normal no llama a Reparto ni
 * una vez.
 *
 * Los numeros son el otro sitio donde se cuela basura: formatear un `Float` a
 * "13.2" fabrica una cadena. Por eso cada cifra pasa por un [Texto], que
 * guarda lo ya formateado y solo lo rehace **cuando el valor cambia** — y
 * formatea a mano sobre un `StringBuilder` propio, sin `String.format`, que
 * ademas arrastra un `Formatter` y el `Locale` (con el que "13.2" se
 * convertiria en "13,2" y dejaria de coincidir con la variante HTML).
 *
 * ## La disciplina, intacta
 *
 * - Un valor ausente o rancio se pinta [Pincel.SIN_DATO] y en [Pincel.APAGADO],
 *   nunca 0. El servicio ya manda `null` en cuanto un dato se pasa de viejo,
 *   asi que aqui "null" y "rancio" son la misma cosa y se pintan igual.
 * - La autonomia va dentro de [Pincel.recintoDeducido]: se infiere, no se mide.
 * - **Una sola alerta puede gritar.** Se elige UNA en toda la seccion —
 *   temperatura critica antes que carga critica, vivienda antes que arranque—
 *   y solo esa parpadea. Las demas condiciones graves se pintan en oxido fijo,
 *   que se ve igual pero no compite.
 * - Cifras tabulares y "no cabe" marcado a la vista: los pone [Pincel].
 *
 * ## Lo que NO pinta, y por que
 *
 * `dcdc` e `inversorW` estan en el contrato y aqui no se pintan. `dcdc` es una
 * FRASE ("Sin enlace al motor"), no una cifra: metida en una fila de dato
 * encogeria hasta disparar la marca de "no cabe" por un fallo que no existe.
 * El propio tablero HTML les quito el bloque hace tiempo —"los tres numeros
 * ocupan el hueco que dejo el cuadro de deducido"— y esta variante replica lo
 * que se ve, no lo que quedo en el JSON.
 */
object PintaEnergia {

    /**
     * Pinta la seccion de energia dentro de [caja].
     *
     * [ahora] es la hora de este cuadro y sirve para UNA cosa: el parpadeo de
     * la alerta. La frescura de los datos no se mira aqui — llega decidida en
     * forma de `null`.
     *
     * Usa un [Pincel] propio. Si la vista ya tiene uno, mejor la otra firma:
     * un pincel por vista es un juego de `Paint` menos.
     */
    fun pinta(canvas: Canvas, caja: Caja, d: DatosTablero, ahora: Long) {
        pinta(canvas, caja, d, ahora, pincelPropio)
    }

    /** La misma, compartiendo el [Pincel] de la vista. */
    fun pinta(canvas: Canvas, caja: Caja, d: DatosTablero, ahora: Long, pincel: Pincel) {
        // Sobre una caja sin area no se puede ni marcar el fallo. Quien la
        // repartio asi es quien tiene que verlo, y lo vera en SU caja padre.
        if (!caja.valida) return

        reparte(caja)
        if (repartoImposible) {
            pincel.marcaDeQueNoCabe(canvas, caja)
            return
        }

        // ---- Quien grita. UNA sola cosa en toda la seccion ----
        // Prioridad: una celda a 45 grados degrada la bateria AHORA; un banco
        // al 10 % da horas de margen. Y entre bancos manda la vivienda, que es
        // la que deja sin nevera y sin luz.
        var bancoQueGrita = NADIE
        var campoQueGrita = SIN_ALARMA
        if (hayVivienda && critica(d.vivT)) {
            bancoQueGrita = VIVIENDA; campoQueGrita = ALARMA_TEMP
        } else if (critica(d.arrT)) {
            bancoQueGrita = ARRANQUE; campoQueGrita = ALARMA_TEMP
        } else if (hayVivienda && agotado(d.vivSoc)) {
            bancoQueGrita = VIVIENDA; campoQueGrita = ALARMA_SOC
        } else if (agotado(d.arrSoc)) {
            bancoQueGrita = ARRANQUE; campoQueGrita = ALARMA_SOC
        }
        // Se pregunta la hora SOLO si hay algo que parpadear. Preguntarla
        // "por si acaso" dejaria el tablero repintando cinco veces por segundo
        // para siempre, que es el defecto que `Latido` viene a arreglar.
        val atenua = bancoQueGrita != NADIE && Latido.parpadeo(ahora)

        if (hayVivienda) {
            banco(
                canvas, pincel, cajasVivienda, cifrasVivienda, d, esVivienda = true,
                campoAlarma = if (bancoQueGrita == VIVIENDA) campoQueGrita else SIN_ALARMA,
                atenua = atenua,
            )
        }
        banco(
            canvas, pincel, cajasArranque, cifrasArranque, d, esVivienda = false,
            campoAlarma = if (bancoQueGrita == ARRANQUE) campoQueGrita else SIN_ALARMA,
            atenua = atenua,
        )
    }

    // ========================================================================
    // EL REPARTO. Se ejecuta al cambiar de tamaño, no por cuadro.
    // ========================================================================

    private var cajaRepartida: Caja? = null
    private var repartoImposible = false

    private fun reparte(seccion: Caja) {
        // La comparacion es por valor: dos cuadros seguidos con la misma caja
        // no vuelven a repartir, y ahi esta la regla 7.
        if (seccion == cajaRepartida) return
        cajaRepartida = seccion
        repartoImposible = false

        if (!hayVivienda) {
            // Un solo banco: la seccion entera es su tarjeta. Sin partir en
            // dos y dejar media vacia por un banco que este carro no lleva.
            // Si el reparto de DENTRO no cupo, no se marca la seccion entera:
            // se marca su tarjeta, que ya lleva fondo y filete, y asi se ve que
            // el sitio existe y lo que fallo es lo de dentro.
            reparteBanco(cajasArranque, seccion, esVivienda = false)
            return
        }

        val hueco = seccion.menor * HUECO_TARJETAS
        // Ancha -> lado a lado. Alta -> uno encima del otro. La forma de la
        // caja decide, no la resolucion de la pantalla.
        val dos = if (seccion.ancho >= seccion.alto * UMBRAL_LADO_A_LADO) {
            Reparto.columnasIguales(seccion, 2, hueco)
        } else {
            Reparto.filasIguales(seccion, 2, hueco)
        }
        if (!dos[0].valida || !dos[1].valida) {
            repartoImposible = true
            return
        }
        // La vivienda primero —arriba o a la izquierda— como en el HTML: es
        // la que manda en una casa rodante.
        reparteBanco(cajasVivienda, dos[0], esVivienda = true)
        reparteBanco(cajasArranque, dos[1], esVivienda = false)
    }

    private fun reparteBanco(c: CajasBanco, tarjeta: Caja, esVivienda: Boolean) {
        c.limpia()
        c.tarjeta = tarjeta
        if (!tarjeta.valida) {
            c.roto = true
            return
        }

        val dentro = tarjeta.margenRelativo(MARGEN_TARJETA)
        if (!dentro.valida) {
            c.roto = true
            return
        }

        // ---- la banda del rotulo ----
        // NO sale del reparto por pesos, y es a proposito. Un rotulo es una
        // linea de texto que cruza la tarjeta a lo ancho: su altura tiene que
        // ver con el ancho que cruza. Repartido por peso pasaban dos cosas
        // malas, las dos medidas: en una tarjeta baja y ancha el titulo caia a
        // 6,7 px, ilegible; y la tarjeta de vivienda —que cede alto a la
        // autonomia— sacaba un rotulo mas chico que la de arranque de al lado,
        // siendo las dos del mismo tamaño. Calculado del ancho, las dos
        // tarjetas dan lo mismo porque las dos miden lo mismo.
        val altoRotulo = (dentro.ancho * ROTULO_DEL_ANCHO)
            .coerceIn(dentro.alto * ROTULO_MINIMO, dentro.alto * ROTULO_MAXIMO)
        c.rotulo = dentro.bandaSuperior(altoRotulo)
        val resto = dentro.bajo(altoRotulo + dentro.alto * HUECO_FILAS)
        if (!c.rotulo.valida || !resto.valida) {
            c.roto = true
            return
        }

        // La autonomia es OPCIONAL: solo si la tarjeta tiene alto de verdad.
        // Es contenido que se cae con elegancia antes que contenido que se
        // pinta apretado y hay que marcar.
        val conPie = esVivienda && dentro.alto >= dentro.ancho * UMBRAL_PIE
        val filas = Reparto.filas(
            resto,
            if (conPie) PESOS_CON_PIE else PESOS_SIN_PIE,
            dentro.alto * HUECO_FILAS,
        )
        for (f in filas) if (!f.valida) {
            c.roto = true
            return
        }

        c.barra = adelgaza(filas[1])
        c.pie = if (conPie) filas[2] else Caja.NADA

        // ---- la franja del dato grande ----
        val hero = filas[0]
        val huecoHero = hero.menor * HUECO_HERO
        val partes = if (hero.ancho < hero.alto * UMBRAL_SOC_AL_LADO) {
            // Franja vertical: la carga ENCIMA de las metricas.
            Reparto.filas(hero, PESOS_SOC_ARRIBA, huecoHero)
        } else {
            Reparto.columnas(hero, PESOS_SOC_AL_LADO, huecoHero)
        }
        if (!partes[0].valida || !partes[1].valida) {
            c.roto = true
            return
        }
        // ⚠️ EL TECHO QUE FALTABA. `Pincel.cifraGrande` saca el tamaño del
        // ALTO de su caja pero lo tiene que meter en el ANCHO. En una caja
        // mas alta que ancha eso pide una letra que no cabe, y `cifraGrande`
        // la encoge por debajo de su tolerancia y PINTA UN ASPA por un fallo
        // que no existe: la caja estaba bien, lo que estaba mal era pedirle a
        // la altura un tamaño que solo el ancho puede pagar. Medido: "100 %"
        // en una caja de 141x119 salia a 80 px y necesitaba 185 de ancho.
        //
        // Que la letra salga de la caja (regla 2) no significa que salga de
        // UNA sola de sus dos medidas.
        c.soc = achica(partes[0], partes[0].ancho * SOC_MANDA_EL_ANCHO)

        // ---- las tres metricas ----
        // La forma la decide la TARJETA, no la caja de metricas: las dos
        // tarjetas son iguales, asi que asi las dos deciden igual. Decidiendo
        // cada una por su caja, la de vivienda —que cede alto a la autonomia—
        // salia en columnas y la de arranque en filas, una al lado de la otra,
        // y eso no parece adaptarse: parece un descuido.
        c.enColumnas = dentro.ancho >= dentro.alto * UMBRAL_METRICAS_EN_COLUMNAS
        val met = achica(
            partes[1],
            partes[1].ancho * if (c.enColumnas) METRICAS_EN_COLUMNAS_DEL_ANCHO
            else METRICAS_EN_FILAS_DEL_ANCHO,
        )
        if (!met.valida) {
            c.roto = true
            return
        }
        val huecoMet = met.menor * HUECO_METRICAS
        val tres = if (c.enColumnas) Reparto.columnasIguales(met, 3, huecoMet)
        else Reparto.filasIguales(met, 3, huecoMet)
        for (i in 0..2) {
            if (!tres[i].valida) {
                c.roto = true
                return
            }
            c.metrica[i] = tres[i]
        }
        if (c.enColumnas) {
            // Rotulito arriba y cifra debajo, como la rejilla `.mg` del HTML.
            for (i in 0..2) {
                val par = Reparto.filas(c.metrica[i], PESOS_METRICA, 0f)
                if (!par[0].valida || !par[1].valida) {
                    c.roto = true
                    return
                }
                c.metricaEtiqueta[i] = par[0]
                c.metricaValor[i] = par[1]
            }
        }
    }

    /**
     * La barra de nivel no se estira con la fila que le toco.
     *
     * Su fila crece con la tarjeta, pero una barra de 40 px de grosor deja de
     * parecer una escala y parece un bloque de color. Se le da un grosor
     * proporcional al ANCHO —que es la dimension que recorre— y se centra en
     * su fila. El suelo de 35 % de la fila evita el extremo contrario: una
     * raya invisible en una tarjeta muy ancha y baja.
     */
    private fun adelgaza(fila: Caja): Caja {
        if (!fila.valida) return Caja.NADA
        val grueso = minOf(fila.alto, maxOf(fila.alto * BARRA_SUELO, fila.ancho * BARRA_GRUESO))
        val sobra = (fila.alto - grueso) * 0.5f
        return Caja(fila.x0, fila.y0 + sobra, fila.x1, fila.y1 - sobra)
    }

    /**
     * Le pone TECHO al alto de una caja y la centra en el hueco que tenia.
     *
     * Es el contrapeso de la regla 2. La tipografia sale del alto de la caja,
     * pero una caja mas alta que ancha pide entonces una letra que no cabe a
     * lo ancho; el pincel la encoge, cruza su tolerancia y marca un fallo que
     * no existe. Recortando el alto ANTES, el tamaño que se derive de el ya
     * nace cabiendo.
     *
     * Lo que sobra no se rellena con nada: aire arriba y abajo. Es lo mismo
     * que hace el HTML, donde la rejilla de metricas mide 50 px dentro de una
     * franja de 112 y se centra — la franja es sitio, no es tamaño de letra.
     */
    private fun achica(caja: Caja, altoMaximo: Float): Caja {
        if (!caja.valida) return Caja.NADA
        if (!altoMaximo.isFinite() || altoMaximo <= 0f || caja.alto <= altoMaximo) return caja
        val sobra = (caja.alto - altoMaximo) * 0.5f
        return Caja(caja.x0, caja.y0 + sobra, caja.x1, caja.y1 - sobra)
    }

    // ========================================================================
    // EL PINTADO
    // ========================================================================

    private fun banco(
        canvas: Canvas,
        pincel: Pincel,
        c: CajasBanco,
        cif: Cifras,
        d: DatosTablero,
        esVivienda: Boolean,
        campoAlarma: Int,
        atenua: Boolean,
    ) {
        if (!c.tarjeta.valida) return
        tarjetaDeMapa(canvas, c.tarjeta)
        if (c.roto) {
            // No cupo el reparto de dentro. Se ve, en su tarjeta.
            pincel.marcaDeQueNoCabe(canvas, c.tarjeta)
            return
        }

        val vivo = if (esVivienda) d.okViv == true else d.okArr == true
        val nom = if (esVivienda) d.vivNom else d.arrNom
        val mac = if (esVivienda) d.vivMac else d.arrMac
        val soc = if (esVivienda) d.vivSoc else d.arrSoc
        val volt = if (esVivienda) d.vivV else d.arrV
        val vatios = if (esVivienda) d.vivW else d.arrW
        val celdas = if (esVivienda) d.vivT else d.arrT

        rotuloDeBanco(
            canvas, pincel, c,
            if (esVivienda) TITULO_VIVIENDA else TITULO_ARRANQUE,
            nom, mac, vivo,
        )

        // ---- el estado de carga, en grande ----
        pincel.cifraGrande(
            canvas, c.soc, cif.soc.entero(soc), UNIDAD_PORCENTAJE,
            if (soc == null) Pincel.APAGADO else Pincel.TINTA,
        )

        // ---- las tres metricas ----
        metrica(
            canvas, pincel, c, 0, ETIQUETA_TENSION,
            cif.tension.decimal(volt), UNIDAD_VOLTIO,
            if (volt == null) Pincel.APAGADO else Pincel.TINTA,
        )
        metrica(
            canvas, pincel, c, 1, ETIQUETA_POTENCIA,
            cif.potencia.conSigno(vatios), UNIDAD_VATIO,
            colorPotencia(vatios),
        )
        metrica(
            canvas, pincel, c, 2, ETIQUETA_CELDAS,
            cif.celdas.entero(celdas), UNIDAD_GRADO,
            colorCeldas(celdas, campoAlarma == ALARMA_TEMP && atenua),
        )

        // ---- la barra de nivel ----
        // `null` no enciende nada: un banco del que no sabemos nada no puede
        // parecerse a uno medido al 0 %.
        pincel.barra(
            canvas, c.barra, cif.nivel.fraccionDePorciento(soc),
            colorNivel(soc, campoAlarma == ALARMA_SOC && atenua),
        )

        // ---- la autonomia, DEDUCIDA ----
        if (c.pie.valida) {
            val dentro = pincel.recintoDeducido(canvas, c.pie)
            if (dentro.valida) {
                val horas = if (esVivienda) d.vivH else null
                pincel.filaGrande(
                    canvas, dentro, ETIQUETA_AUTONOMIA,
                    horas ?: Pincel.SIN_DATO, UNIDAD_HORA,
                    if (horas == null) Pincel.APAGADO else Pincel.TINTA,
                )
            } else {
                pincel.marcaDeQueNoCabe(canvas, c.pie)
            }
        }
    }

    /**
     * Una metrica, en la forma que pida la caja.
     *
     * En columnas: rotulito arriba y cifra centrada debajo, que es la rejilla
     * del HTML. En filas: `filaGrande`, que reparte por prioridad —el numero
     * se queda con lo suyo y la palabra encoge o se corta— y es lo unico que
     * aguanta una caja estrecha sin solapar.
     */
    private fun metrica(
        canvas: Canvas,
        pincel: Pincel,
        c: CajasBanco,
        i: Int,
        etiqueta: String,
        valor: String,
        unidad: String,
        color: Int,
    ) {
        if (c.enColumnas) {
            pintaChico(
                canvas, c.metricaEtiqueta[i], etiqueta, Pincel.APAGADO,
                Paint.Align.CENTER, FRACCION_ETIQUETA_COLUMNA, letras, 0f,
            )
            pincel.cifraGrande(canvas, c.metricaValor[i], valor, unidad, color)
        } else {
            pincel.filaGrande(canvas, c.metrica[i], etiqueta, valor, unidad, color)
        }
    }

    /**
     * El rotulo de region: NOMBRE de la seccion, rotulo del banco, guia de
     * puntos y MAC, como la franja `.rl` del HTML.
     *
     * El nombre y la MAC **vienen del servicio**. No son adorno: con dos
     * bancos de litio iguales, escritos a mano, la tarjeta llego a decir que
     * era una bateria que no era.
     *
     * Se reparte de DERECHA A IZQUIERDA y midiendo: primero se reserva la MAC,
     * luego el rotulo del banco en lo que quede, y el titulo se queda con el
     * resto. Asi ninguno de los tres puede pisar a otro. Los dos de la derecha
     * son metadato y **se caen enteros** si no caben ni encogidos — cortar una
     * MAC por la mitad no identifica nada, y marcar la fila por eso seria
     * gritar por una arruga cuando el dato de verdad esta intacto.
     */
    private fun rotuloDeBanco(
        canvas: Canvas,
        pincel: Pincel,
        c: CajasBanco,
        titulo: String,
        nom: String?,
        mac: String?,
        vivo: Boolean,
    ) {
        val fila = c.rotulo
        if (!fila.valida) return
        val aire = fila.alto * AIRE_ROTULO
        var reservado = 0f

        // 1) la MAC, en monoespaciada y pegada al borde derecho
        val textoMac = mac ?: Pincel.SIN_DATO
        letras.typeface = monoespaciada
        letras.letterSpacing = 0.02f
        letras.textAlign = Paint.Align.RIGHT
        val anchoMac = ajusta(
            letras, textoMac, fila.ancho * MAXIMO_MAC, fila.alto * FRACCION_MAC,
        )
        if (anchoMac >= 0f) {
            dibuja(canvas, letras, textoMac, fila.x1, fila, Pincel.APAGADO, SUBE_ROTULO)
            reservado = anchoMac + aire
        }

        // 2) el rotulo del banco, a la izquierda de la MAC
        val textoNom = nom ?: Pincel.SIN_DATO
        letras.typeface = negrita
        letras.letterSpacing = 0.14f
        letras.textAlign = Paint.Align.RIGHT
        val xNom = fila.x1 - reservado
        val anchoNom = ajusta(
            letras, textoNom, (xNom - fila.x0) * MAXIMO_NOMBRE, fila.alto * FRACCION_NOMBRE,
        )
        if (anchoNom >= 0f) {
            dibuja(canvas, letras, textoNom, xNom, fila, Pincel.ARENA, SUBE_ROTULO)
            reservado += anchoNom + aire
        }

        // 3) el titulo, con lo que quede. La caja se guarda: solo se fabrica
        //    otra cuando cambia lo reservado, o sea cuando cambia el nombre o
        //    la MAC del banco, que es casi nunca.
        if (reservado != c.reservadoAnterior) {
            c.reservadoAnterior = reservado
            c.cajaTitulo = Caja(fila.x0, fila.y0, fila.x1 - reservado, fila.y1)
        }
        val cajaTitulo = c.cajaTitulo
        if (!cajaTitulo.valida) {
            // Ni el nombre de la seccion cabe: eso si es un fallo de reparto.
            pincel.marcaDeQueNoCabe(canvas, fila)
            return
        }
        // Apagado cuando la fuente NO esta dando datos. Es el "punto hueco"
        // de la cabecera del HTML, traido a la propia tarjeta: sin el, un
        // banco desconectado se distingue de uno vivo solo por los guiones.
        pincel.tituloDeSeccion(
            canvas, cajaTitulo, titulo, if (vivo) Pincel.MUSGO else Pincel.APAGADO,
        )

        // La linea de base del rotulo la pinta `tituloDeSeccion` hasta SU
        // borde; el resto, bajo el nombre y la MAC, lo continua esta.
        val grosor = maxOf(1f, cajaTitulo.menor * 0.03f)
        if (cajaTitulo.x1 < fila.x1) {
            relleno.color = Pincel.LINEA
            canvas.drawRect(cajaTitulo.x1, fila.y1 - grosor, fila.x1, fila.y1, relleno)
        }
    }

    /**
     * La tarjeta: fondo, filete y la esquina de hoja de mapa del HTML.
     *
     * El filete se mete media pluma hacia dentro. Pintado sobre el borde justo,
     * la mitad del trazo cae fuera de la caja y se come el hueco que separa
     * esta tarjeta de la de al lado.
     */
    private fun tarjetaDeMapa(canvas: Canvas, caja: Caja) {
        val pluma = maxOf(1f, caja.menor * 0.004f)
        val m = pluma * 0.5f
        val radio = maxOf(2f, caja.menor * 0.012f)

        rect.set(caja.x0 + m, caja.y0 + m, caja.x1 - m, caja.y1 - m)
        relleno.color = Pincel.TARJETA
        canvas.drawRoundRect(rect, radio, radio, relleno)
        trazo.color = Pincel.LINEA
        trazo.strokeWidth = pluma
        canvas.drawRoundRect(rect, radio, radio, trazo)

        val lado = caja.menor * ESQUINA_MAPA
        trazo.color = Pincel.LINEA2
        canvas.drawLine(rect.right - lado, rect.bottom, rect.right, rect.bottom, trazo)
        canvas.drawLine(rect.right, rect.bottom - lado, rect.right, rect.bottom, trazo)
    }

    // ========================================================================
    // COLOR POR UMBRAL
    // ========================================================================

    /**
     * La temperatura de una LiFePO4 no es decoracion: cargar por encima de
     * 45 grados degrada las celdas y muchos BMS cortan ahi. Ambar antes, oxido
     * despues, para que se vea sin leer.
     *
     * [atenua] solo llega true si ESTA es la alerta elegida de toda la seccion
     * y el cuadro cae en la mitad apagada del parpadeo.
     */
    private fun colorCeldas(t: Int?, atenua: Boolean): Int = when {
        t == null -> Pincel.APAGADO
        t >= TEMPERATURA_CRITICA -> if (atenua) Pincel.OCRE else Pincel.OXIDO
        t >= TEMPERATURA_AVISO -> Pincel.OCRE
        else -> Pincel.TINTA
    }

    /**
     * Umbrales de una **LiFePO4** de 4 celdas a 3,3 V, que es lo que llevan
     * los dos carros. La quimica importa: una Li-ion daria 14,8 V nominales y
     * estos mismos numeros avisarian sin motivo todo el rato.
     */
    private fun colorNivel(soc: Int?, atenua: Boolean): Int = when {
        soc == null -> Pincel.REBAJE
        soc <= SOC_CRITICO -> if (atenua) Pincel.OCRE else Pincel.OXIDO
        soc <= SOC_AVISO -> Pincel.OCRE
        else -> Pincel.MUSGO
    }

    /**
     * Verde entrando, oxido saliendo. Es el UNICO sitio del tablero donde el
     * color codifica un signo, y por eso el signo tambien se escribe: quien no
     * distinga los dos tonos sigue leyendo "+" o "-".
     *
     * Que un banco descargando salga en oxido —el color de alarma— es decision
     * heredada del HTML, y no rompe "una sola alerta puede gritar": el oxido
     * fijo informa, lo que grita es el PARPADEO, y de ese solo hay uno.
     */
    private fun colorPotencia(w: Int?): Int = when {
        w == null -> Pincel.APAGADO
        w > 0 -> Pincel.MUSGO
        w < 0 -> Pincel.OXIDO
        else -> Pincel.TINTA
    }

    private fun critica(t: Int?): Boolean = t != null && t >= TEMPERATURA_CRITICA

    private fun agotado(soc: Int?): Boolean = soc != null && soc <= SOC_CRITICO

    /**
     * Medio segundo encendido, medio apagado.
     *
     * ⚠️ Depende de que el cuadro llegue a tiempo. Con `Termometro` bajando el
     * repintado a 1 fps por radio caliente, esto se muestrea una vez por
     * segundo y el parpadeo se ve irregular. Sigue viendose que algo cambia,
     * que es lo que tiene que conseguir; no se ve bonito.
     */

    // ========================================================================
    // TEXTO CHICO. Medido, encogido y —si hace falta— caido.
    // ========================================================================

    /**
     * Ajusta [p] para que [texto] quepa en [anchoMax] partiendo de [ideal].
     *
     * @return el ancho que ocupa, o **-1 si no cabe** ni al suelo de
     *   legibilidad. Se devuelve -1 y no 0 a proposito: un ancho de 0 es un
     *   texto vacio, que si cabe.
     */
    private fun ajusta(p: Paint, texto: String, anchoMax: Float, ideal: Float): Float {
        if (texto.isEmpty()) return 0f
        // Ni siquiera a su tamaño ideal seria legible: la caja es demasiado
        // baja para este rotulito y encogerlo solo lo empeoraria.
        if (anchoMax <= 0f || ideal < MINIMO_LEGIBLE) return -1f
        p.textSize = ideal
        val medido = p.measureText(texto)
        if (medido <= anchoMax) return medido
        val proporcional = ideal * (anchoMax / medido)
        if (proporcional < maxOf(ideal * Pincel.SUELO, MINIMO_LEGIBLE)) return -1f
        p.textSize = proporcional
        return p.measureText(texto)
    }

    /**
     * Dibuja con la linea base CENTRADA en la caja segun las metricas de la
     * fuente, no a ojo. [sube] la levanta una fraccion del alto de la caja,
     * para casar con el rotulo de [Pincel], que deja sitio a su linea inferior.
     */
    private fun dibuja(
        canvas: Canvas,
        p: Paint,
        texto: String,
        x: Float,
        caja: Caja,
        color: Int,
        sube: Float,
    ) {
        p.color = color
        p.getFontMetrics(metricas)
        canvas.drawText(
            texto, x,
            caja.cy - (metricas.ascent + metricas.descent) * 0.5f - caja.alto * sube,
            p,
        )
    }

    /**
     * Texto chico completo: mide, encoge y, si no cabe, **no lo pinta**.
     *
     * Se usa solo para rotulitos. Un rotulito que se cae deja el numero sin
     * nombre pero legible; medio rotulito encima de un numero deja los dos
     * inservibles, que es exactamente como se rompio el tablero viejo.
     */
    private fun pintaChico(
        canvas: Canvas,
        caja: Caja,
        texto: String,
        color: Int,
        align: Paint.Align,
        fraccion: Float,
        p: Paint,
        sube: Float,
    ) {
        if (!caja.valida) return
        p.typeface = negrita
        p.letterSpacing = 0.15f
        p.textAlign = align
        if (ajusta(p, texto, caja.ancho, caja.alto * fraccion) < 0f) return
        val x = when (align) {
            Paint.Align.LEFT -> caja.x0
            Paint.Align.RIGHT -> caja.x1
            else -> caja.cx
        }
        dibuja(canvas, p, texto, x, caja, color, sube)
    }

    // ========================================================================
    // CACHES. Lo unico que separa esta seccion de asignar por cuadro.
    // ========================================================================

    /**
     * Una cifra ya formateada, que se rehace **solo cuando el valor cambia**.
     *
     * Un `Float?` a "13.2" fabrica una cadena, y a 5 cuadros por segundo por
     * cuatro cifras por dos bancos son 40 cadenas por segundo durante horas.
     * Los valores llegan del servicio cada 700 ms y casi siempre repetidos:
     * con esto, un cuadro normal no fabrica ni una.
     *
     * Formatea a mano y sin `String.format`, que ademas arrastra el `Locale`
     * del radio: en español "13.2" saldria "13,2" y dejaria de coincidir con
     * la variante HTML del mismo carro.
     */
    private class Texto {
        private val sb = StringBuilder(16)
        private var clave = SIN_CLAVE
        private var salida = Pincel.SIN_DATO

        /** Un entero tal cual. Estados de carga, grados. */
        fun entero(v: Int?): String {
            val k = if (v == null) SIN_CLAVE else v.toLong()
            if (k == clave) return salida
            clave = k
            salida = if (v == null) {
                Pincel.SIN_DATO
            } else {
                sb.setLength(0)
                sb.append(v)
                sb.toString()
            }
            return salida
        }

        /**
         * Con signo explicito y miles separados por espacio FINO.
         *
         * El signo se escribe aunque el color ya lo diga; el color es un
         * refuerzo, no el dato. Y "2 270" se lee de reojo mejor que "2270",
         * mientras que la coma se confundiria con el decimal. Los dos son
         * acuerdos del tablero HTML y aqui se respetan.
         */
        fun conSigno(v: Int?): String {
            val k = if (v == null) SIN_CLAVE else v.toLong()
            if (k == clave) return salida
            clave = k
            if (v == null) {
                salida = Pincel.SIN_DATO
                return salida
            }
            sb.setLength(0)
            val n = v.toLong()
            if (n > 0L) sb.append('+') else if (n < 0L) sb.append('-')
            miles(if (n < 0L) -n else n)
            salida = sb.toString()
            return salida
        }

        /** Un decimal, como el `toFixed(1)` del HTML. Tensiones. */
        fun decimal(v: Float?): String {
            val k = if (v == null || !v.isFinite()) SIN_CLAVE else Math.round(v * 10f).toLong()
            if (k == clave) return salida
            clave = k
            if (k == SIN_CLAVE) {
                salida = Pincel.SIN_DATO
                return salida
            }
            sb.setLength(0)
            val a = if (k < 0L) { sb.append('-'); -k } else k
            sb.append(a / 10L)
            sb.append('.')
            sb.append(a % 10L)
            salida = sb.toString()
            return salida
        }

        /** Recursiva y de tres niveles como mucho para cualquier vatiaje real. */
        private fun miles(n: Long) {
            if (n < 1000L) {
                sb.append(n)
                return
            }
            miles(n / 1000L)
            sb.append(ESPACIO_FINO)
            val r = (n % 1000L).toInt()
            if (r < 100) sb.append('0')
            if (r < 10) sb.append('0')
            sb.append(r)
        }
    }

    /**
     * La fraccion de la barra, ya en su caja de `Float`.
     *
     * `Pincel.barra` recibe `Float?`, y meter un `Float` en un `Float?` lo
     * ENCAJONA: un objeto nuevo por cuadro. Guardando el encajonado mientras
     * el porcentaje no cambie, ese objeto se fabrica una vez por cambio real.
     */
    private class Fraccion {
        private var clave = Int.MIN_VALUE
        private var salida: Float? = null

        fun fraccionDePorciento(pct: Int?): Float? {
            val k = pct ?: Int.MIN_VALUE
            if (k == clave) return salida
            clave = k
            salida = if (pct == null) null
            else (pct / 100f).coerceIn(0f, 1f)
            return salida
        }
    }

    private class Cifras {
        val soc = Texto()
        val tension = Texto()
        val potencia = Texto()
        val celdas = Texto()
        val nivel = Fraccion()
    }

    /**
     * Las cajas de un banco, calculadas al repartir y solo leidas al pintar.
     *
     * Mutable a proposito y con `Caja` inmutables dentro: repartir fabrica
     * cajas nuevas, pero eso pasa al cambiar de tamaño, no por cuadro.
     */
    private class CajasBanco {
        var tarjeta = Caja.NADA
        var rotulo = Caja.NADA
        var soc = Caja.NADA
        var barra = Caja.NADA

        /** [Caja.NADA] cuando la tarjeta no da alto para la autonomia. */
        var pie = Caja.NADA

        /** Tres metricas: tension, potencia y celdas. */
        val metrica = arrayOf(Caja.NADA, Caja.NADA, Caja.NADA)

        /** Solo con [enColumnas]: el rotulito arriba y la cifra debajo. */
        val metricaEtiqueta = arrayOf(Caja.NADA, Caja.NADA, Caja.NADA)
        val metricaValor = arrayOf(Caja.NADA, Caja.NADA, Caja.NADA)

        var enColumnas = true

        /** El reparto de dentro no cupo. Se marca sobre [tarjeta]. */
        var roto = false

        /** Caja del titulo, guardada porque depende del ancho del nombre. */
        var cajaTitulo = Caja.NADA
        var reservadoAnterior = -1f

        fun limpia() {
            tarjeta = Caja.NADA
            rotulo = Caja.NADA
            soc = Caja.NADA
            barra = Caja.NADA
            pie = Caja.NADA
            for (i in 0..2) {
                metrica[i] = Caja.NADA
                metricaEtiqueta[i] = Caja.NADA
                metricaValor[i] = Caja.NADA
            }
            enColumnas = true
            roto = false
            cajaTitulo = Caja.NADA
            // Fuerza a recalcular la caja del titulo con el reparto nuevo.
            reservadoAnterior = -1f
        }
    }

    // ========================================================================
    // ESTADO Y CONSTANTES
    // ========================================================================

    /**
     * ⚠️ `object`, o sea UNA disposicion guardada para todo el proceso.
     *
     * Lo pide la firma del encargo, y en este proyecto no hay dos tableros a
     * la vez. Si algun dia los hubiera, seguiria pintando bien —compara la
     * caja y vuelve a repartir— pero repartiria en cada cuadro al alternar
     * entre dos tamaños distintos, que es justo lo que no se quiere. Ese dia,
     * esto pasa a ser una clase con una instancia por vista.
     */
    private val pincelPropio = Pincel()

    private val hayVivienda = Carro.perfil.tieneBancoVivienda

    private val cajasVivienda = CajasBanco()
    private val cajasArranque = CajasBanco()
    private val cifrasVivienda = Cifras()
    private val cifrasArranque = Cifras()

    private val negrita: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val monoespaciada: Typeface = Typeface.MONOSPACE

    /** Un solo pincel para todo el texto chico. Se reconfigura, no se crea. */
    private val letras = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = negrita
        textAlign = Paint.Align.LEFT
    }
    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val metricas = Paint.FontMetrics()
    private val rect = RectF()

    // --- Pesos del reparto. Sacados del presupuesto vertical del HTML -------
    //
    // Tarjeta de vivienda, 296 px con 12 de aire: franja del dato 112, barra
    // 16, autonomia 64. Son PESOS, no pixeles: la misma proporcion en
    // cualquier caja. El rotulo no esta aqui — se calcula del ancho, ver
    // `reparteBanco`.

    private val PESOS_CON_PIE = floatArrayOf(112f, 16f, 64f)
    private val PESOS_SIN_PIE = floatArrayOf(112f, 16f)

    /**
     * Carga y metricas, lado a lado.
     *
     * En el HTML la cifra grande es `flex:0 0 auto`: se lleva lo que necesita
     * —unos 152 de 368— y la rejilla se queda con el resto. Aqui no hay flex,
     * asi que se le da ese 38 % por escrito. Con el 30 % que tenia antes,
     * "100 %" no cabia y `cifraGrande` marcaba la caja.
     */
    private val PESOS_SOC_AL_LADO = floatArrayOf(38f, 62f)

    /** Y apilados, cuando la franja es vertical. */
    private val PESOS_SOC_ARRIBA = floatArrayOf(42f, 58f)

    /**
     * Dentro de una metrica en columna: rotulito y cifra, como el HTML.
     *
     * El rotulito se lleva el 22 % y lo LLENA (ver
     * [FRACCION_ETIQUETA_COLUMNA]). Con el 18 % de antes y una letra al 0,72
     * de su casilla, los tres rotulitos salian a 5–7 px en casi todas las
     * disposiciones anchas y se caian los tres: quedaban tres numeros sin
     * nombre. En el HTML esa etiqueta mide 8,5 px en una linea de 9 —o sea,
     * la llena— y esto es lo mismo escrito en proporciones.
     */
    private val PESOS_METRICA = floatArrayOf(22f, 78f)

    // --- Umbrales de FORMA. Ninguno mira la pantalla, todos su propia caja --

    /** Dos tarjetas lado a lado a partir de aqui; por debajo, apiladas. */
    private const val UMBRAL_LADO_A_LADO = 1.5f

    /** La autonomia solo si la tarjeta tiene alto de verdad. */
    private const val UMBRAL_PIE = 0.40f

    /** Carga al lado de las metricas si la franja es apaisada. */
    private const val UMBRAL_SOC_AL_LADO = 1.2f

    /**
     * Tres columnas si hay ancho; si no, tres filas de `filaGrande`.
     *
     * Se mide sobre la TARJETA entera, no sobre la caja de metricas, para que
     * las dos tarjetas —que son iguales— decidan igual.
     *
     * El numero sale de comparar que forma da la letra mas grande. En
     * columnas, la cifra acaba valiendo ~0,10 del ancho de la caja de
     * metricas; en filas, ~0,207 de su alto. Se cruzan en 2,07:1 sobre la caja
     * de metricas, que es ~1,9:1 sobre la tarjeta porque la caja de metricas
     * se lleva el 62 % del ancho.
     */
    private const val UMBRAL_METRICAS_EN_COLUMNAS = 1.9f

    // --- Aire, todo en fraccion de la caja que lo contiene ------------------

    private const val HUECO_TARJETAS = 0.02f
    private const val HUECO_FILAS = 0.035f
    private const val HUECO_HERO = 0.03f
    private const val HUECO_METRICAS = 0.04f
    private const val MARGEN_TARJETA = 0.05f
    private const val AIRE_ROTULO = 0.35f
    private const val ESQUINA_MAPA = 0.05f

    /** Grosor de la barra en fraccion de su ANCHO, y suelo en fraccion del alto. */
    private const val BARRA_GRUESO = 0.045f
    private const val BARRA_SUELO = 0.35f

    // --- Techos de alto. El ancho manda sobre el tamaño de la letra --------
    //
    // Todos dicen lo mismo: hasta aqui puede crecer el alto de esta caja,
    // porque de su alto sale la letra y la letra tiene que caber a lo ANCHO.
    // Sin ellos, una caja alta y estrecha hace que el pincel encoja la cifra
    // por debajo de su tolerancia y marque un fallo inexistente.

    /** "100 %" ocupa ~2,3 veces su tamaño; con 0,68 de la caja, cabe en 0,58. */
    private const val SOC_MANDA_EL_ANCHO = 0.58f

    /**
     * En columnas, la banda de las tres metricas no llena la franja: se centra
     * en ella, como la rejilla `.mg` del HTML —50 px dentro de 112—. Con 0,18
     * del ancho, la cifra sale a ~0,10 del ancho y "+1 234 W" entra en su
     * tercio sin cruzar la tolerancia.
     */
    private const val METRICAS_EN_COLUMNAS_DEL_ANCHO = 0.18f

    /** En filas basta con impedir que la caja sea mucho mas alta que ancha. */
    private const val METRICAS_EN_FILAS_DEL_ANCHO = 1.15f

    /**
     * La banda del rotulo, en fraccion del ANCHO de la tarjeta, con suelo y
     * techo en fraccion de su alto para que no se descuadre en los extremos.
     * En el HTML son 28 px de banda en una tarjeta de 396 de ancho: 0,071 —
     * aqui algo menos, porque la banda del HTML incluye su propio relleno.
     */
    private const val ROTULO_DEL_ANCHO = 0.055f
    private const val ROTULO_MINIMO = 0.10f
    private const val ROTULO_MAXIMO = 0.30f

    // --- Tipografia del texto chico, en fraccion de SU caja -----------------

    private const val FRACCION_MAC = 0.34f
    private const val FRACCION_NOMBRE = 0.34f
    /** El rotulito LLENA su casilla, como el `.k` de 8,5 px en 9 del HTML. */
    private const val FRACCION_ETIQUETA_COLUMNA = 0.88f

    /**
     * Por debajo de esto no se lee, y punto.
     *
     * Es el UNICO numero en pixeles absolutos de todo el fichero, y esta a
     * proposito: la legibilidad no es proporcional a nada — es una propiedad
     * fisica del ojo a medio metro de la pantalla del radio. Un rotulito por
     * debajo NO se pinta mas chico (a 4 px solo ensucia): se cae entero. Todas
     * las demas medidas de aqui son fracciones de una caja.
     *
     * El 6 sale de la propia variante HTML, que es la referencia: su MAC mide
     * 8 px diseñada a 1024x600, y en la pantalla mas chica que hay que
     * aguantar —800x480— se escala por 0,78 y acaba en 6,2 px reales. Poner el
     * suelo por encima de eso seria tirar en Canvas un dato que el tablero de
     * al lado, en el mismo carro, si enseña.
     */
    private const val MINIMO_LEGIBLE = 6f

    /** Levanta el rotulo para casar con la linea inferior de `tituloDeSeccion`. */
    private const val SUBE_ROTULO = 0.10f

    /** Cuanto ancho de la fila puede llevarse cada metadato antes de caerse. */
    private const val MAXIMO_MAC = 0.34f
    private const val MAXIMO_NOMBRE = 0.45f

    // --- Umbrales de dato ---------------------------------------------------

    private const val TEMPERATURA_AVISO = 40
    private const val TEMPERATURA_CRITICA = 45
    private const val SOC_AVISO = 35
    private const val SOC_CRITICO = 15


    // --- Quien grita ---------------------------------------------------------

    private const val NADIE = -1
    private const val VIVIENDA = 0
    private const val ARRANQUE = 1
    private const val SIN_ALARMA = 0
    private const val ALARMA_TEMP = 1
    private const val ALARMA_SOC = 2

    // --- Cadenas fijas. Constantes para no fabricarlas por cuadro -----------

    private const val TITULO_VIVIENDA = "VIVIENDA"
    private const val TITULO_ARRANQUE = "ARRANQUE"
    private const val ETIQUETA_TENSION = "TENSIÓN"
    private const val ETIQUETA_POTENCIA = "POTENCIA"
    private const val ETIQUETA_CELDAS = "CELDAS"
    private const val ETIQUETA_AUTONOMIA = "AUTONOMÍA"
    private const val UNIDAD_PORCENTAJE = "%"
    private const val UNIDAD_VOLTIO = "V"
    private const val UNIDAD_VATIO = "W"
    private const val UNIDAD_GRADO = "°C"
    private const val UNIDAD_HORA = "h"

    /**
     * El separador de miles del HTML: espacio FINO, no coma.
     *
     * Escrito con su punto de codigo y no con el caracter literal, que en un
     * fichero fuente no se distingue de un espacio normal y el primero que
     * "arregle" el formato lo cambiaria sin enterarse.
     */
    private const val ESPACIO_FINO = ' '

    private const val SIN_CLAVE = Long.MIN_VALUE
}
