package com.nonosky.carsoc

/**
 * ¿Hace falta el GPS ahora mismo? La regla, aparte del servicio para poder
 * probarla sin radio.
 *
 * El odometro solo cuenta cuando el carro se mueve, asi que tener el receptor
 * cazando satelites con el carro parado es calor a cambio de nada —y hubo un
 * radio que se estaba yendo a 85 C—. Pero apagarlo de mas pierde kilometros.
 *
 * ## El defecto que esto arregla
 *
 * La regla vieja solo encendia el GPS si el motor giraba (dato del OBD) o si el
 * propio GPS habia visto movimiento hace poco. Sin OBD la primera no se cumple
 * nunca, y la segunda necesita el GPS encendido para cumplirse: el latido lo
 * apagaba a los cinco segundos de arrancar, antes de la primera fija en frio, y
 * ya no volvia. En un carro sin adaptador OBD los kilometros no contaban.
 *
 * ## La regla
 *
 * Por orden, y basta una:
 *  1. el guardian termico lo prohibe: apagado, pase lo que pase;
 *  2. el motor gira AHORA, con lectura fresca del OBD;
 *  3. alguien vio movimiento hace menos de [GRACIA_MS] —nuestro GPS o el de
 *     otra app del radio—, que es lo que salva un viaje si el OBD se cae;
 *  4. el motor se apago hace menos de [GRACIA_MS] (un semaforo, repostar);
 *  5. si el OBD SABE que el motor esta parado —el adaptador contesta y la ECU
 *     no—, apagado: acampado con el radio encendido horas no hay que escuchar;
 *  6. si el OBD no sabe nada, se escucha a ratos: [VENTANA_ESCUCHA_MS] cada
 *     [CICLO_ESCUCHA_MS]. Si en una ventana se ve movimiento, manda la 3.
 */
object ReglaGps {

    /**
     * Cuanto se sigue escuchando tras el ultimo motor vivo o el ultimo
     * movimiento. Un semaforo largo o repostar no deben soltar el receptor
     * —reengancharlo en frio tarda y esos metros se pierden—, pero un carro
     * aparcado no tiene por que calentar el radio toda la tarde.
     */
    const val GRACIA_MS = 10 * 60_000L

    /**
     * Lo que dura cada escucha sin OBD. Da para una fija en tibio y para
     * buena parte de una en frio; si el carro se mueve, con una basta.
     */
    const val VENTANA_ESCUCHA_MS = 90_000L

    /**
     * Cada cuanto se abre una escucha sin OBD: el GPS queda encendido un 15 %
     * del tiempo con el carro parado. El precio es que, sin OBD, el arranque
     * de un viaje puede tardar hasta ~8 minutos en empezar a contar — salvo
     * que otra app del radio ya tenga el GPS encendido, que se oye gratis.
     */
    const val CICLO_ESCUCHA_MS = 10 * 60_000L

    /** Velocidad a partir de la cual una fija cuenta como "se mueve". */
    const val VELOCIDAD_MOVIMIENTO_MS = Mantenimiento.VELOCIDAD_MINIMA_MS

    data class Senales(
        val ahora: Long,
        val permiteGps: Boolean,
        /** RPM fresco del OBD por encima de cero. */
        val motorGirandoAhora: Boolean,
        /** Ultima vez que se vio el motor girando. 0 = nunca. */
        val ultimoMotorVivoMs: Long,
        /** Ultima fija, propia o ajena, con el carro en movimiento. 0 = nunca. */
        val ultimoMovimientoMs: Long,
        /** El adaptador contesto hace poco: si no hay RPM es que el motor esta parado. */
        val obdSabeDelMotor: Boolean,
        /** Cuando empezo la ultima escucha sin OBD. 0 = nunca. */
        val escuchaDesdeMs: Long,
    )

    /**
     * @property abrirEscucha quien llama debe apuntar `escuchaDesdeMs = ahora`.
     * @property motivo para el registro: por que esta encendido o apagado.
     */
    data class Decision(val encender: Boolean, val abrirEscucha: Boolean, val motivo: String)

    fun decidir(s: Senales): Decision {
        if (!s.permiteGps) return Decision(false, false, "radio caliente")
        if (s.motorGirandoAhora) return Decision(true, false, "motor girando")
        if (reciente(s.ultimoMovimientoMs, s.ahora, GRACIA_MS)) {
            return Decision(true, false, "el carro se movio hace poco")
        }
        if (reciente(s.ultimoMotorVivoMs, s.ahora, GRACIA_MS)) {
            return Decision(true, false, "el motor se apago hace poco")
        }
        if (s.obdSabeDelMotor) return Decision(false, false, "el OBD dice motor parado")
        if (reciente(s.escuchaDesdeMs, s.ahora, VENTANA_ESCUCHA_MS)) {
            return Decision(true, false, "sin OBD: escuchando por si se mueve")
        }
        if (s.escuchaDesdeMs == 0L || s.ahora - s.escuchaDesdeMs >= CICLO_ESCUCHA_MS) {
            return Decision(true, true, "sin OBD: abre una escucha")
        }
        return Decision(false, false, "sin OBD: esperando la proxima escucha")
    }

    private fun reciente(desdeMs: Long, ahora: Long, plazo: Long) =
        desdeMs > 0L && ahora - desdeMs < plazo
}
