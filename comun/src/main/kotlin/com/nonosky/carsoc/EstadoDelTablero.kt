package com.nonosky.carsoc

import com.nonosky.carsoc.config.AlertasLlantas
import com.nonosky.carsoc.obd.PidDecoder
import com.nonosky.carsoc.tpms.Rueda
import com.nonosky.carsoc.ui.lienzo.DatosTablero

/**
 * DE DONDE SALEN LOS DATOS DEL TABLERO. Uno solo, para las dos variantes.
 *
 * ## Por que existe
 *
 * Antes esto vivia dentro de `TableroActivity.Puente.estado()`, que leia
 * [EstadoActual] y escribia JSON en el mismo bucle. Con la variante Canvas eso
 * ya no vale: si el Canvas se lee los sensores por su cuenta, las dos pantallas
 * empiezan iguales y acaban distintas —una tratando un dato como rancio y la
 * otra no, una redondeando y la otra no— y no habria forma de saber cual de las
 * dos miente. Un tablero que se contradice consigo mismo es peor que uno feo.
 *
 * Asi que hay un solo camino y dos salidas:
 *
 * ```
 *   EstadoActual ──leer()──> DatosTablero ──┬── aJson() ──> WebView (HTML)
 *                                           └── tal cual ──> TableroLienzo (Canvas)
 * ```
 *
 * [DatosTablero] es el contrato, y el JSON es una **serializacion de el**, no
 * una lectura paralela. Si mañana entra un dato nuevo se añade en un sitio y
 * las dos variantes lo ven; y si alguien cambia una regla de frescura, cambia
 * para las dos a la vez porque es la misma linea de codigo.
 *
 * ## La regla dura, y donde se aplica
 *
 * **AQUI**, no en la pantalla: un valor ausente o rancio sale `null`, jamas 0.
 * Un cero y un "no lo se" significan cosas opuestas —"0.0 V" es una bateria
 * muerta, "no lo se" es que el BMS todavia no ha hablado— y confundirlos ya
 * costo caro una vez en este proyecto: MEZCLA llego a pintar "+3 %" en verde
 * sumando un ajuste que faltaba. Los pintores heredan la disciplina gratis: si
 * les llega un numero, es fresco.
 *
 * ## Hilo
 *
 * [leer] se llama desde el hilo del JavaScript (variante HTML) o desde el hilo
 * de interfaz (variante Canvas), nunca desde los dos a la vez porque solo hay
 * una variante viva. El unico estado que se guarda entre llamadas es la
 * histeresis del VTEC, y va `@Volatile` por si acaso.
 */
object EstadoDelTablero {

    /**
     * El VTEC se deduce CON HISTERESIS, asi que hay que acordarse de si estaba
     * enganchado. Es el unico estado que sobrevive entre lecturas.
     *
     * Vive aqui y no en la Activity a proposito: si viviera en la vista, al
     * cambiar de variante el VTEC se "olvidaria" y volveria a enganchar en la
     * siguiente cuesta aunque no hubiera soltado nunca.
     */
    @Volatile
    private var vtecEnganchado = false

    /** ¿Esa lectura sigue valiendo, o ya es un recuerdo? */
    private fun fresco(atMs: Long, ahora: Long) =
        atMs > 0 && (ahora - atMs) < Carro.motor.staleAfterMs

    /**
     * ¿Sigue valiendo esta lectura de [pid]? Con el enlace leyendo, segun el
     * ritmo al que se pide ese dato (`Plan.plazoMs`): no caduca entre una
     * lectura y la siguiente, que es lo que lo hacia parpadear en blanco. Sin
     * enlace, el plazo corto de siempre: un dato que ya no se va a renovar no
     * se sigue enseñando como si fuera de ahora.
     */
    private fun vale(pid: String, atMs: Long, ahora: Long, enlaceVivo: Boolean): Boolean {
        if (atMs <= 0) return false
        val plazo = if (enlaceVivo) {
            com.nonosky.carsoc.obd.PollScheduler.Plan.plazoMs(pid, Carro.motor.staleAfterMs)
        } else {
            Carro.motor.staleAfterMs
        }
        return (ahora - atMs) < plazo
    }

    /**
     * Una foto del carro en el instante [ahora].
     *
     * Se construye un objeto por lectura, y es a proposito: inmutable, nadie lo
     * cambia a media pintada. A cinco lecturas por segundo —una con el radio
     * caliente— es mas barato que el `StringBuilder` de 1 KB que la variante
     * HTML fabrica en cada vuelta, que es lo que se hacia antes.
     */
    /**
     * @param contexto hace falta SOLO para la calibracion de las llantas.
     *   Es opcional a proposito: sin el, las presiones salen tal cual las
     *   da el sensor y nada mas cambia. Un tablero que se cae por no poder
     *   leer una preferencia seria mucho peor que uno sin calibrar.
     */
    fun leer(ahora: Long, contexto: android.content.Context? = null): DatosTablero {
        val v = EstadoActual.ultimo
        // Con el sondeo leyendo, cada dato caduca a su ritmo (ver vale()).
        val enlace = v.connection == ConnectionState.Polling

        // ---------- bancos ----------
        // Cada banco viene fijado por su MAC. Ya no se adivina cual es cual: el
        // vigilante viejo se quedaba con el primer JBD que veia, y con dos
        // bancos iguales el rotulo de la pantalla era una suposicion — el dueño
        // vio la de arranque bajo el rotulo de vivienda, y tenia razon.
        val bancos = EstadoActual.bancos
        val viv = bancos?.vivienda
        val arr = bancos?.arranque
        val vivViva = viv != null && viv.vivo(ahora)
        val arrViva = arr != null && arr.vivo(ahora)

        // ---------- deducidos del banco de vivienda ----------
        // El inversor y el cargador DC-DC no tienen Bluetooth. Se infieren del
        // signo de la corriente del banco; si no hay banco, no se inventa nada.
        val amp = if (vivViva) viv!!.corrienteA else null
        // ⚠️ SE DISTINGUE "el motor esta parado" de "no se si lo esta". Sin
        // enlace con la ECU no hay rpm, y traducir esa ausencia a "Motor
        // parado" es afirmar algo que nadie ha medido — el mismo error que
        // hacia decir "SIN AVERIAS" sin haber hablado con la computadora.
        val sabemosDelMotor = fresco(v.rpmAtMs, ahora)
        val motorGirando = sabemosDelMotor && (v.rpm ?: 0) > 300

        // ---------- nevera ----------
        val nev = EstadoActual.nevera
        val nevViva = nev != null && nev.vivoAhora(ahora)
        val ne = if (nevViva) nev!!.estado else null

        // ---------- ajustes de combustible ----------
        // Los DOS ajustes o ninguno, y manda la edad del mas viejo. Rellenar
        // con cero el que falte diria "corrige perfecto", que es la respuesta
        // contraria a "no lo se".
        val corto = v.trimCortoPct
        val largo = v.trimLargoPct
        val ajusteVale = corto != null && largo != null &&
            vale(PidDecoder.PID_TRIM_CORTO, v.trimCortoAtMs, ahora, enlace) &&
            vale(PidDecoder.PID_TRIM_LARGO, v.trimLargoAtMs, ahora, enlace)

        // ---------- VTEC, deducido con histeresis ----------
        val rpmFresco = if (fresco(v.rpmAtMs, ahora)) v.rpm else null
        val cargaFresca = if (vale(PidDecoder.PID_LOAD, v.loadAtMs, ahora, enlace)) v.loadPct else null
        val vtec: Boolean? = if (rpmFresco == null || cargaFresca == null) {
            vtecEnganchado = false
            null
        } else {
            vtecEnganchado = Carro.motor.vtecActive(rpmFresco, cargaFresca, vtecEnganchado)
            vtecEnganchado
        }

        // ---------- llantas ----------
        val tp = EstadoActual.lectorTpms?.estado()
        val t0 = tp?.de(Rueda.DelanteraIzquierda)?.trama
        val t1 = tp?.de(Rueda.DelanteraDerecha)?.trama
        val t2 = tp?.de(Rueda.TraseraIzquierda)?.trama
        val t3 = tp?.de(Rueda.TraseraDerecha)?.trama
        // La alerta de cada rueda, con los umbrales de Ajustes y la presion
        // ya calibrada: la MISMA cuenta que hace la notificacion del servicio.
        val reglas = AlertasLlantas.reglas(contexto)
        val a0 = AlertasLlantas.evaluar(contexto, 0, t0, reglas)
        val a1 = AlertasLlantas.evaluar(contexto, 1, t1, reglas)
        val a2 = AlertasLlantas.evaluar(contexto, 2, t2, reglas)
        val a3 = AlertasLlantas.evaluar(contexto, 3, t3, reglas)

        // ---------- aceite ----------
        // Sin ancla de odometro puesta a mano, el contador de kilometros no
        // significa nada: diria "faltan 0 km" junto a un "100 %", que es una
        // contradiccion en pantalla. Hasta que el dueño ancle con
        // /aceite?odometro=, la tarjeta va en hueco.
        val aceiteConfigurado = Mantenimiento.proximoCambioKm > 0f &&
            Mantenimiento.odometroAnclaKm > 0f

        // ---------- luz de averia ----------
        // Solo si el 0101 llego fresco: sin el, "no lo se" — nunca "sin
        // averia", que es la respuesta tranquilizadora y falsa.
        val estadoFresco = vale(PidDecoder.PID_ESTADO, v.estadoAtMs, ahora, enlace)

        return DatosTablero(
            vivSoc = if (vivViva) viv!!.soc else null,
            vivV = if (vivViva) viv!!.voltaje else null,
            vivA = if (vivViva) viv!!.corrienteA else null,
            vivW = if (vivViva) viv!!.potenciaW?.let { Math.round(it) } else null,
            vivT = if (vivViva) viv!!.temperaturaC else null,
            // Autonomia: pendiente de historial de consumo.
            vivH = null,
            // El rotulo y la MAC salen del banco, no de la pantalla. Asi la
            // tarjeta no puede volver a decir que es una bateria que no es.
            vivNom = viv?.rotulo,
            vivMac = viv?.mac,
            arrNom = arr?.rotulo,
            arrMac = arr?.mac,

            dcdc = when {
                amp == null -> null
                !sabemosDelMotor -> "Sin enlace al motor"
                motorGirando && amp > 0.5f -> "Cargando"
                motorGirando -> "Sin carga"
                else -> "Motor parado"
            },
            inversorW = if (amp != null && amp < -0.5f && vivViva)
                viv!!.potenciaW?.let { Math.round(-it) } else null,

            arrSoc = if (arrViva) arr!!.soc else null,
            arrV = if (arrViva) arr!!.voltaje else null,
            arrA = if (arrViva) arr!!.corrienteA else null,
            arrW = if (arrViva) arr!!.potenciaW?.let { Math.round(it) } else null,
            arrT = if (arrViva) arr!!.temperaturaC else null,

            nevT = ne?.actual,
            nevSet = ne?.consigna,
            nevV = ne?.voltaje,
            nevEco = ne?.modoEco,
            nevOn = when (ne?.encendida) {
                true -> "Encendida"; false -> "Apagada"; null -> null
            },
            // El compresor NO existe en el protocolo: se deduce comparando
            // temperatura contra consigna mas histeresis. Por eso la tarjeta lo
            // enseña dentro del recinto de "deducido".
            nevComp = when (ne?.compresorEnMarcha()) {
                true -> "En marcha"; false -> "Parado"; null -> null
            },
            // Para colocar el punto y la marca en el carril termico.
            nevMin = ne?.minima,
            nevMax = ne?.maxima,

            agua = if (vale(PidDecoder.PID_COOLANT, v.coolantAtMs, ahora, enlace)) v.coolantC else null,
            rpm = if (fresco(v.rpmAtMs, ahora)) v.rpm else null,
            aire = if (vale(PidDecoder.PID_IAT, v.iatAtMs, ahora, enlace)) v.iatC else null,
            carga = if (vale(PidDecoder.PID_LOAD, v.loadAtMs, ahora, enlace)) v.loadPct else null,
            avance = if (vale(PidDecoder.PID_AVANCE, v.avanceAtMs, ahora, enlace)) v.avanceGrados else null,
            // kPa -> PSI, por peticion del dueño.
            mapPsi = if (vale(PidDecoder.PID_MAP, v.mapAtMs, ahora, enlace)) v.mapKpa?.let { it * 0.145038f } else null,
            trim = if (ajusteVale) corto!! + largo!! else null,
            // El reloj de mezcla: lambda REAL del 0134 por la estequiometrica.
            // Sin lectura que valga, null y la esfera vacia. Nunca 14.7 por
            // defecto: eso seria pintar "mezcla perfecta" sin haber medido.
            afr = if (Carro.perfil.tieneAfrReal &&
                vale(PidDecoder.PID_LAMBDA, v.lambdaAtMs, ahora, enlace)
            ) v.lambda?.let { it * Carro.motor.afrEstequiometrica } else null,
            vtec = vtec,
            rpmMax = Carro.motor.rpmMax,
            rpmVtec = Carro.motor.rpmVtec,
            rpmCambio = Carro.motor.rpmShiftAmber,
            rpmRoja = Carro.motor.rpmRedline,
            rpmCorte = Carro.motor.rpmFuelCut,

            // `llNbaja` cae a false —y no a null— cuando no hay trama: es lo
            // que hacia el puente y lo que espera el HTML. Una alarma solo se
            // enciende con dato que la respalde; el hueco lo dicen la presion
            // y la temperatura, que si van a null.
            // ⚠️ LA CALIBRACION SE APLICA AQUI, Y LAS ALERTAS SE CALCULAN
            // CONTRA EL VALOR CORREGIDO. Una rueda calibrada a -3 PSI que
            // avisara con la trama cruda daria la alarma tres libras antes de
            // tiempo, todos los dias, hasta que el dueño aprendiera a
            // ignorarla.
            ll0psi = psiCal(contexto, 0, t0?.presionPsi), ll0t = t0?.temperaturaC,
            ll0baja = a0 == AlertasLlantas.Tipo.Baja, ll0al = a0?.texto,
            ll1psi = psiCal(contexto, 1, t1?.presionPsi), ll1t = t1?.temperaturaC,
            ll1baja = a1 == AlertasLlantas.Tipo.Baja, ll1al = a1?.texto,
            ll2psi = psiCal(contexto, 2, t2?.presionPsi), ll2t = t2?.temperaturaC,
            ll2baja = a2 == AlertasLlantas.Tipo.Baja, ll2al = a2?.texto,
            ll3psi = psiCal(contexto, 3, t3?.presionPsi), ll3t = t3?.temperaturaC,
            ll3baja = a3 == AlertasLlantas.Tipo.Baja, ll3al = a3?.texto,

            acePct = if (aceiteConfigurado) Mantenimiento.vidaPct else null,
            aceKm = if (aceiteConfigurado) Math.round(Mantenimiento.kmRestantes) else null,
            aceH = if (aceiteConfigurado) Math.round(Mantenimiento.horasRestantes) else null,
            radioC = if (Termometro.gradosC > 0) Termometro.gradosC else null,

            mil = if (estadoFresco) v.milEncendida else null,
            codigos = if (estadoFresco) v.codigosGuardados else null,

            // Los puntos de enlace no son adorno. Si el receptor de las llantas
            // muere, las cuatro presiones se quedan congeladas en su ultimo
            // valor bueno y siguen pareciendo correctas: el punto es lo unico
            // que lo delata. Cada uno dice si ESA fuente esta dando datos ahora.
            okViv = vivViva,
            okArr = arrViva,
            okNev = nevViva,
            okTpms = tp != null && tp.ruedas.isNotEmpty(),
            okObd = fresco(v.rpmAtMs, ahora) || fresco(v.coolantAtMs, ahora),
        )
    }

    /**
     * El mismo dato, en JSON, para el `<script>` del tablero HTML.
     *
     * Las claves y el ORDEN son los que ya leia `tablero.html`. Los numeros
     * salen por `toString()` —sin `String.format`, que arrastraria el Locale y
     * convertiria "13.2" en "13,2" en un radio en español— y los textos escapan
     * la comilla doble cambiandola por una simple, que es lo que se hacia antes
     * y lo unico que puede romper este JSON a mano.
     */
    fun aJson(d: DatosTablero): String {
        val j = StringBuilder(1024)
        j.append('{')

        fun num(clave: String, valor: Any?) {
            if (j.length > 1) j.append(',')
            j.append('"').append(clave).append("\":")
            j.append(valor?.toString() ?: "null")
        }
        fun txt(clave: String, valor: String?) {
            if (j.length > 1) j.append(',')
            j.append('"').append(clave).append("\":")
            if (valor == null) j.append("null")
            else j.append('"').append(valor.replace("\"", "'")).append('"')
        }

        num("vivSoc", d.vivSoc); num("vivV", d.vivV); num("vivA", d.vivA)
        num("vivW", d.vivW); num("vivT", d.vivT)
        txt("vivH", d.vivH); txt("vivNom", d.vivNom); txt("vivMac", d.vivMac)
        txt("arrNom", d.arrNom); txt("arrMac", d.arrMac)

        txt("dcdc", d.dcdc); num("inversorW", d.inversorW)

        num("arrSoc", d.arrSoc); num("arrV", d.arrV); num("arrA", d.arrA)
        num("arrW", d.arrW); num("arrT", d.arrT)

        num("nevT", d.nevT); num("nevSet", d.nevSet); num("nevV", d.nevV)
        num("nevEco", d.nevEco)
        txt("nevOn", d.nevOn); txt("nevComp", d.nevComp)
        num("nevMin", d.nevMin); num("nevMax", d.nevMax)

        num("agua", d.agua); num("rpm", d.rpm); num("aire", d.aire)
        num("carga", d.carga); num("avance", d.avance); num("mapPsi", d.mapPsi)
        num("trim", d.trim); num("afr", d.afr); num("vtec", d.vtec)
        num("rpmMax", d.rpmMax); num("rpmVtec", d.rpmVtec); num("rpmCambio", d.rpmCambio)
        num("rpmRoja", d.rpmRoja); num("rpmCorte", d.rpmCorte)

        num("ll0psi", d.ll0psi); num("ll0t", d.ll0t); num("ll0baja", d.ll0baja); txt("ll0al", d.ll0al)
        num("ll1psi", d.ll1psi); num("ll1t", d.ll1t); num("ll1baja", d.ll1baja); txt("ll1al", d.ll1al)
        num("ll2psi", d.ll2psi); num("ll2t", d.ll2t); num("ll2baja", d.ll2baja); txt("ll2al", d.ll2al)
        num("ll3psi", d.ll3psi); num("ll3t", d.ll3t); num("ll3baja", d.ll3baja); txt("ll3al", d.ll3al)

        num("acePct", d.acePct); num("aceKm", d.aceKm); num("aceH", d.aceH)
        num("radioC", d.radioC)

        num("mil", d.mil); num("codigos", d.codigos)

        num("okViv", d.okViv); num("okArr", d.okArr); num("okNev", d.okNev)
        num("okTpms", d.okTpms); num("okObd", d.okObd)

        j.append('}')
        return j.toString()
    }

/** La presion de esa rueda, ya corregida. null entra, null sale. */
private fun psiCal(
    contexto: android.content.Context?,
    rueda: Int,
    psi: Float?,
): Float? {
    val c = contexto ?: return psi
    return com.nonosky.carsoc.config.CalibracionLlantas.corregir(c, rueda, psi)
}

}
