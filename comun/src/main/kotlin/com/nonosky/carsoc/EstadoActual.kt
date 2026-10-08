package com.nonosky.carsoc

import android.view.View
import java.lang.ref.WeakReference

/**
 * Ultimo estado conocido del vehiculo, accesible desde todo el proceso.
 *
 * Hace falta porque el puente de diagnostico vive en un servicio y el
 * sondeo vive en la pantalla: sin un punto comun, el servicio no tendria
 * nada que contar cuando la pantalla no esta.
 */
object EstadoActual {

    @Volatile
    private var ultimoInterno: VehicleState = VehicleState()

    /**
     * El estado se publica por aqui para poder CONTARLO al pasar.
     *
     * El fondo rojo del VTEC dura dos segundos y pide 5850 rpm con el pedal a
     * fondo: para cuando alguien pregunta por HTTP, ya paso. Sin contar al
     * vuelo, "no vi el rojo" y "la condicion no se cumplio nunca" son la misma
     * respuesta, y una se arregla mirando mejor y la otra pisando mas.
     *
     * Este es ademas el unico embudo por el que pasan los cuatro publicadores
     * (radio interna, dongle y pantalla), asi que contar aqui no obliga a
     * tocar ni el sondeo ni la vista.
     */
    var ultimo: VehicleState
        get() = ultimoInterno
        set(nuevo) {
            ultimoInterno = nuevo
            anotarVtec(nuevo)
        }

    // --- Lo que se aprende del motor al pasar --------------------------------

    /** Maximas revoluciones vistas desde que arranco el proceso. */
    @Volatile
    var rpmMaximasVistas = 0
        private set

    /** La carga que habia en ese pico: dice si se subio a fondo o levantando. */
    @Volatile
    var cargaEnRpmMaximas = 0
        private set

    /**
     * Maximas rpm alcanzadas CON carga suficiente.
     *
     * Es el numero que de verdad falta hoy: `sessionMaxRpm` dice a cuanto se
     * subio, pero no con que pedal, y la mitad de la condicion del VTEC es el
     * pedal. Con este se sabe cuantas rpm faltaron para el enganche real.
     */
    @Volatile
    var rpmMaximasConCarga = 0
        private set

    /** Veces que la condicion COMPLETA se cumplio, con la carga fresca. */
    @Volatile
    var vecesVtec = 0L
        private set

    /** Cuando fue la ultima. Cero = no ha pasado nunca. */
    @Volatile
    var ultimoVtecMs = 0L
        private set

    /**
     * La misma muestra se republica cuando solo cambia el enlace
     * (`copy(connection = ...)` de la pantalla). Contarla otra vez inflaria
     * `vecesVtec` sin que el motor hubiera hecho nada, asi que se contabiliza
     * por el sello de tiempo de las revoluciones, que es lo unico que cambia
     * con cada lectura nueva de verdad.
     */
    @Volatile
    private var rpmYaContadaMs = 0L

    private fun anotarVtec(st: VehicleState) {
        val rpm = st.rpm ?: return
        if (st.rpmAtMs == rpmYaContadaMs) return
        rpmYaContadaMs = st.rpmAtMs
        val carga = st.loadPct ?: 0
        if (rpm > rpmMaximasVistas) {
            rpmMaximasVistas = rpm
            cargaEnRpmMaximas = carga
        }
        if (carga >= Carro.motor.vtecMinLoadPct && rpm > rpmMaximasConCarga) {
            rpmMaximasConCarga = rpm
        }
        // Se exige la carga FRESCA igual que la exige el fondo rojo. Si el
        // contador aceptara carga rancia diria que engancho en un instante en
        // que el tablero no pinto nada, y entonces no estaria midiendo lo que
        // se quiere verificar sino otra cosa parecida.
        val ahora = System.currentTimeMillis()
        if (st.vtecActive && !st.isStale(st.loadAtMs, ahora)) {
            vecesVtec++
            ultimoVtecMs = ahora
        }
    }

    /**
     * Nombre y MAC del adaptador elegido, para poder diagnosticarlo en
     * remoto. Sin esto no habia forma de saber si el tablero estaba
     * intentando hablar con el adaptador correcto o con otra cosa.
     */
    @Volatile
    var adaptadorElegido: String? = null

    /**
     * Ultimo fallo del enlace, con su causa.
     *
     * Un tablero que solo dice "conectando" no permite diagnosticar
     * nada en remoto: hay que saber SI fallo y POR QUE.
     */
    @Volatile
    var ultimoErrorEnlace: String? = null

    /**
     * Lista los adaptadores Bluetooth que el radio ya tiene emparejados.
     *
     * Lo pone la pantalla, que es quien tiene los permisos. Sirve para
     * poder configurar el adaptador EN REMOTO: sin esto habia que ir al
     * carro a tocar el selector, que es justo lo que se quiere evitar.
     */
    @Volatile
    var listarAdaptadores: (() -> List<String>)? = null

    /** Elige un adaptador YA emparejado por MAC y arranca el sondeo. */
    @Volatile
    var elegirAdaptador: ((String) -> Boolean)? = null

    /**
     * Barre el aire en busca de adaptadores. Bloquea hasta terminar.
     *
     * En API 30 el barrido exige permiso de ubicacion; el resultado lo dice
     * en vez de devolver una lista vacia sin explicacion, que es la forma
     * mas facil de perder una tarde.
     */
    @Volatile
    var buscarAdaptadores: (() -> List<String>)? = null

    /**
     * Cuando contesto por ultima vez el adaptador OBD a la inicializacion,
     * haya contestado o no la ECU. 0 = nunca. Si contesto hace poco y no hay
     * RPM, el motor esta parado: lo usa la regla del GPS ([ReglaGps]).
     */
    @Volatile
    var adaptadorContestoMs: Long = 0L

    /**
     * La MAC del adaptador OBD asignado en el menu. Cadena vacia = ninguno.
     * La pone el servicio, que es quien tiene contexto.
     */
    @Volatile
    var macObd: (() -> String)? = null

    /**
     * Vuelve a conectar AHORA el aparato de un papel, tras cambiarlo en el
     * menu de emparejamiento. Antes el cambio esperaba a reiniciar el
     * tablero, y desde el carro no hay forma de saber cuando eso ha pasado.
     */
    @Volatile
    var reconectar: ((com.nonosky.carsoc.config.Emparejados.Papel) -> String)? = null

    /**
     * Cerrojo de la lectura de codigos: mientras este tomado, el servicio no
     * vuelve a levantar el sondeo OBD. `tomar` devuelve false si ya hay una
     * lectura en marcha.
     */
    @Volatile
    var tomarObdParaDtc: (() -> Boolean)? = null

    @Volatile
    var soltarObdTrasDtc: (() -> Unit)? = null

    /** Empareja por MAC (contestando el PIN) y luego lo elige. */
    @Volatile
    var emparejarAdaptador: ((String) -> String)? = null

    /** Olvida el adaptador guardado y detiene el sondeo. */
    @Volatile
    var olvidarAdaptador: (() -> Unit)? = null

    /**
     * Borra el vinculo de un aparato en la pila de Android (`removeBond`).
     *
     * El Steren se habla por el dongle USB, no por la radio del carro. Pero
     * mientras siguiera vinculado ahi, la pila interna intentaba tomarlo — y
     * dos pilas peleando por el mismo ELM327 es justo lo que dejaba al motor
     * sin datos. Esto lo saca del Bluetooth del carro sin apagar la radio,
     * que se sigue necesitando para Android Auto.
     */
    var desvincularAdaptador: ((String) -> String)? = null

    /** Descarga, verifica la firma e instala un APK acompanante. */
    @Volatile
    var instalarCompanero: ((String, String) -> String)? = null

    /** Arma el confirmador para que teclee el PIN del emparejamiento. */
    @Volatile
    var armarPin: ((String) -> Unit)? = null

    /**
     * Barrido Bluetooth LE, con el anuncio crudo de cada hallazgo.
     *
     * Aparte del barrido clasico a proposito: son dos radios distintas. Un
     * `startDiscovery()` no ve un aparato BLE por mucho que este ahi, que es
     * exactamente por que la bateria de litio nunca aparecio en la lista.
     */
    @Volatile
    var barrerBle: ((Int) -> List<String>)? = null

    /** Vuelca servicios y caracteristicas de un aparato BLE por GATT. */
    @Volatile
    var volcarGatt: ((String, Int) -> List<String>)? = null

    /** Lo que hay colgado del USB, con VID, PID, interfaces y endpoints. */
    @Volatile
    var listarUsb: (() -> List<String>)? = null

    /** Abre una pantalla del sistema por intent. No necesita accesibilidad. */
    var abrirAjustes: ((String?, String?) -> List<String>)? = null

    /** Interruptores del sistema: ADB, desarrollador, accesibilidad. */
    var interruptores: (() -> List<String>)? = null

    /**
     * Suelta la radio Bluetooth entera. Lo llama el boton de cerrar del
     * tablero, para que Android Auto la reciba libre.
     */
    var soltarBluetooth: (() -> String)? = null

    /**
     * Dispara una alerta de prueba de llanta.
     *
     * Existe porque la alternativa para saber si la alerta suena de verdad
     * —y si se oye por encima de la musica— es esperar a tener un pinchazo.
     */
    var probarAlertaLlanta: (() -> String)? = null
    /**
     * Que sabe el detector de pinchazo de UNA rueda, en una linea.
     *
     * Va por gancho y no abriendo los mapas del servicio: un mapa mutable
     * publicado es un mapa que alguien acaba escribiendo, y estos los escribe
     * el hilo del TPMS y nadie mas.
     *
     * Recibe el nombre de la rueda y la presion de ahora mismo. La presion se
     * le pasa porque quien pregunta ya la tiene en la mano; si no, el servicio
     * volveria a pedirle el estado al lector una vez por rueda y por consulta.
     */
    @Volatile
    var detectorPinchazo: ((String, Float?) -> String)? = null

    /**
     * Los dos numeros con los que corre el detector, ya escritos.
     *
     * Escalar y no gancho: son constantes de compilacion, no cambian en
     * caliente, y una indireccion para leer algo fijo no paga. Se publica
     * porque un umbral que solo vive en el codigo no se consulta desde la
     * carretera, y porque un documento que lo repita se queda viejo el dia
     * que alguien toque la constante. Esto no.
     */
    @Volatile
    var umbralPinchazo: String? = null

    /**
     * Si el receptor de GPS esta pedido ahora mismo.
     *
     * Hace falta para no volver a quedarse a ciegas: desde que el GPS se
     * enciende y se apaga solo, un `fijas=0` puede significar "la antena no
     * ve el cielo" o "lo apagamos nosotros a proposito", y son cosas muy
     * distintas.
     */
    @Volatile
    var gpsEncendido: (() -> Boolean)? = null


    /**
     * Fuerza el aviso de VTEC hasta este instante. SOLO para verlo.
     *
     * Existe porque comprobar el efecto de verdad exige subir a 5850 rpm con
     * el pedal a fondo, y eso no se hace en un parqueo para revisar un color.
     * No falsea ningun dato del motor: solo enciende el aviso.
     */
    @Volatile
    var vtecForzadoHastaMs: Long = 0L

    /** Quien declara poder dibujar por encima de todo. */
    var listarOverlays: (() -> List<String>)? = null

    /**
     * El lector del TPMS, vivo mientras viva el servicio.
     *
     * Lo expone el servicio y lo consultan la pantalla y el puente. Va aqui y
     * no en la Activity porque las llantas deben seguir midiendose con el
     * tablero cerrado: si colgara de la pantalla, cambiar de app dejaria de
     * vigilar las presiones — justo cuando el carro esta andando.
     */
    @Volatile
    var lectorTpms: com.nonosky.carsoc.tpms.TpmsReader? = null

    /**
     * El vigilante de la bateria de litio, que barre BLE por el dongle USB.
     *
     * No usa la pila Bluetooth del radio porque esa no sirve: le habla HCI
     * directo al dongle. Vive en el servicio por lo mismo que el TPMS — hay
     * que seguir vigilando con el tablero cerrado.
     */
    @Volatile
    var vigilanteBateria: com.nonosky.carsoc.bateria.VigilanteBateria? = null

    /**
     * Los bancos de litio del carro, cada uno fijado por su MAC.
     *
     * Convive con [vigilanteBateria] a proposito y no lo sustituye: aquel
     * barre y se queda con el primer JBD que ve, que con dos bancos iguales
     * significa que cual te toca es cuestion de suerte. El tablero lee de
     * aqui; las rutas de diagnostico del puente siguen usando el vigilante.
     */
    var bancos: com.nonosky.carsoc.bateria.BancosBateria? = null

    /** La refrigeradora Alpicool. Conecta, pregunta y suelta. */
    var nevera: com.nonosky.carsoc.nevera.LectorNevera? = null

    /**
     * Conecta con el BMS y lo lee AHORA, devolviendo la traza paso a paso.
     *
     * Es para diagnosticar: una pila Bluetooth escrita a mano falla en algun
     * escalon concreto —conexion, MTU, descubrimiento, CCCD, checksum— y sin
     * ver cual, "no lee la bateria" no se puede arreglar.
     */
    @Volatile
    var leerBmsAhora: ((String) -> List<String>)? = null

    /**
     * Intenta el OBD por HCI crudo contra el dongle USB, y cuenta cada paso.
     *
     * Es la via que esquiva la pila Bluetooth rota del radio. Devuelve la
     * traza completa porque cada escalon —emparejar, L2CAP, SABM, MSC— falla
     * distinto, y sin ver cual, "no conecta" no se puede arreglar.
     */
    @Volatile
    var probarObdHci: ((String) -> List<String>)? = null

    /**
     * Lo mismo pero por el Bluetooth INTERNO del radio (RFCOMM/SPP).
     *
     * Existe porque el head unit nuevo trae una pila que si funciona: empareja
     * de verdad (BOND_BONDED) donde el viejo moria en BOND_NONE. Si el enlace
     * clasico habla ELM327, el dongle USB deja de ser obligatorio.
     */
    var probarSpp: ((String) -> List<String>)? = null

    /** Le pregunta a la ECU que PIDs soporta, en vez de suponerlo. */
    var pidsSoportados: (() -> List<String>)? = null
    /**
     * Lee los codigos de averia. Con `true` los BORRA (modo 04).
     *
     * Vive aqui y no en la pantalla de averias por dos razones. La pantalla es
     * `exported=false`, asi que la unica forma de probar el diagnostico desde
     * fuera del carro era tocar coordenadas a ciegas por ADB. Y sobre todo:
     * quien lea codigos tiene que apagar antes el sondeo del tablero, porque
     * el lector abre su PROPIA conexion al ELM327 y el adaptador solo atiende
     * a un enlace. Eso solo lo sabe hacer el servicio, que es el dueño del
     * sondeo — una Activity no puede pararlo.
     */
    @Volatile
    var leerDtc: ((Boolean) -> List<String>)? = null

    /** La pantalla se registra aqui para repintar cuando llega dato del motor. */
    @Volatile
    var alCambiarObd: (() -> Unit)? = null

    /**
     * Manda comandos crudos al ELM327 usando el enlace que ya este vivo.
     *
     * Reutiliza la sesion del lector en vez de abrir otra: montar un segundo
     * enlace contra el mismo adaptador lo tumbaria, y ademas tardaria los diez
     * segundos del emparejamiento por cada pregunta.
     */
    @Volatile
    var comandoObd: ((List<String>) -> List<String>)? = null

    /**
     * Enciende o apaga una fuente en caliente.
     *
     * El tablero arranca en minimo —solo llantas— y las fuentes caras se
     * encienden a mano midiendo la temperatura entre cada una. Es la respuesta
     * a que el radio se apagara tres veces por calor.
     */
    @Volatile
    var encenderFuente: ((String, Boolean) -> String)? = null

    /**
     * A quien se le esta intentando emparejar ahora mismo.
     *
     * El contestador de PIN solo toca ESTE aparato. Contestar el PIN de
     * cualquiera que pida vincularse seria abrirle la puerta a quien pase por
     * la calle con un telefono.
     */
    @Volatile
    var macAEmparejar: String? = null

    /**
     * PIN con el que se contesta. Los clones de ELM327 usan casi siempre 1234.
     *
     * Se puede cambiar por HTTP para probar los otros sin desplegar: el
     * emparejamiento no dice cual es el bueno, solo si acerto.
     */
    @Volatile
    var pinDeEmparejamiento: String = "1234"

    /** El lector del motor, para exponer su traza por HTTP. */
    @Volatile
    var lectorObd: com.nonosky.carsoc.obd.LectorObdHci? = null

    /** La pantalla se registra aqui para repintar cuando cambia la bateria. */
    @Volatile
    var alCambiarBateria: (() -> Unit)? = null

    /** La pantalla se registra aqui para repintar cuando llega una trama. */
    @Volatile
    var alCambiarTpms: (() -> Unit)? = null

    /**
     * Interroga por HCI crudo a un dongle Bluetooth USB, sin el kernel.
     *
     * Hace falta porque el kernel de esta ROM no trae `btusb`
     * (`/sys/class/bluetooth/` esta vacio) y el dongle quedo enganchado al
     * driver USB generico: Android jamas lo va a usar como su radio. Pero si
     * nos concede permiso sobre el aparato USB, y un dongle Bluetooth es un
     * transporte HCI simple: comandos por control, eventos por interrupcion.
     */
    @Volatile
    var interrogarHci: ((Int?, Int?) -> List<String>)? = null

    /** Barrido BLE hablandole al dongle por HCI, saltandose la pila rota. */
    @Volatile
    var barrerBleHci: ((Int, Int?, Int?, Boolean, Boolean) -> List<String>)? = null

    /**
     * Abre el USB-serial y vuelca lo que llegue, en crudo.
     *
     * El formato de trama del receptor TPMS es propietario: hay que ver bytes
     * reales antes de escribir un decodificador, no al reves.
     */
    @Volatile
    var volcarUsbSerial: ((Int, Int) -> List<String>)? = null

    /**
     * Enciende o apaga la radio Bluetooth del head unit.
     *
     * No es un lujo: tras reiniciar el carro queda apagada, y se ha visto
     * apagarse sola tras varios emparejamientos fallidos. Sin esto hay que
     * ir fisicamente al carro.
     */
    @Volatile
    var encenderBluetooth: ((Boolean) -> String)? = null

    /** Lo ultimo que conto el confirmador sobre lo que ve en pantalla. */
    private val dichos = java.util.concurrent.CopyOnWriteArrayList<String>()

    fun anotarConfirmador(t: String) {
        if (dichos.size > 40) dichos.removeAt(0)
        dichos.add(t)
    }

    fun loQueDiceElConfirmador(): List<String> = dichos.toList()

    /**
     * Vacia lo que dijo el confirmador.
     *
     * Hace falta antes de cada mando: sin esto la respuesta de un volcado
     * viene mezclada con lo que quedaba de mandos anteriores, y no hay forma
     * de saber que linea contesta a que pregunta.
     */
    fun olvidarLoDelConfirmador() = dichos.clear()

    /**
     * Manda al confirmador tocar, volcar o abrir algo.
     *
     * Es la unica via de controlar la pantalla de este radio: sin root, el
     * shell no puede `input tap` ni `am start`, y un AccessibilityService es
     * lo unico que Android deja hacerlo. Lo registra la Activity, que es
     * quien puede difundir con el permiso de firma.
     */
    @Volatile
    var mandarAlConfirmador: ((String, String?, String?, String?, String?) -> Unit)? = null

    /**
     * Referencia debil a la vista del tablero, para poder fotografiarla.
     *
     * Debil a proposito: si la pantalla se destruye, esto no debe impedir
     * que se libere. Cuando no hay vista, el puente contesta que no hay
     * nada que dibujar en vez de mentir con una imagen vieja.
     */
    @Volatile
    private var vistaRef: WeakReference<View>? = null

    var vista: View?
        get() = vistaRef?.get()
        set(v) { vistaRef = if (v == null) null else WeakReference(v) }
}
