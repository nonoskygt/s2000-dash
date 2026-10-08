package com.nonosky.carsoc.diag

/**
 * Lo que se puede decir de un codigo que NO esta en la tabla del carro.
 *
 * La tabla trae los codigos que de verdad le pasan a este motor y a esta caja,
 * cada uno explicado a mano. Pero el estandar tiene miles, y antes uno fuera
 * de la tabla salia pelado: el numero y "no catalogado". Eso deja al dueño a
 * oscuras justo cuando la computadora encontro algo raro.
 *
 * Un codigo OBD no es un numero al azar: la letra dice el area del carro, la
 * primera cifra si es del estandar o del fabricante, y la siguiente el
 * sistema (SAE J2012). Con eso se explica DE QUE trata y que conviene hacer,
 * sin inventar la pieza exacta — eso solo lo dice su ficha, y aqui se avisa
 * con todas las letras que no la hay.
 *
 * Codigo puro, sin Android: se prueba en la JVM.
 */
object GrupoDtc {

    data class Grupo(
        /** De que sistema es, en pocas palabras. */
        val titulo: String,
        /** Que vigila ese sistema y si el numero es del estandar o del fabricante. */
        val explicacion: String,
        /** Que hacer mientras tanto. */
        val queHacer: String,
    )

    private val FORMA = Regex("^[PBCU][0-3][0-9A-F]{3}$")

    /** El grupo de [codigo], o null si no tiene forma de codigo OBD. */
    fun de(codigo: String): Grupo? {
        val c = codigo.trim().uppercase()
        if (!FORMA.matches(c)) return null
        val origen = origen(c)
        return when (c[0]) {
            'P' -> motorYCaja(c, origen)
            'B' -> Grupo(
                "Carrocería",
                "Es de la carrocería: bolsas de aire, aire acondicionado, luces, " +
                    "seguros o tablero, no del motor. $origen",
                "Si es de bolsas de aire, se lee con el parpadeo de la luz SRS. " +
                    "Lo demás no impide manejar.",
            )
            'C' -> Grupo(
                "Chasis: frenos, dirección y suspensión",
                "Es del chasis, normalmente de los frenos ABS o de la dirección, " +
                    "no del motor. $origen",
                "Si la luz de ABS está encendida, los frenos funcionan pero sin " +
                    "antibloqueo: frena con más distancia y revísalo pronto.",
            )
            else -> Grupo(
                "Comunicación entre computadoras",
                "Una computadora del carro dejó de oír a otra por la red interna. " +
                    "$origen",
                "Una batería baja o un borne flojo provoca muchos de estos: revisa " +
                    "el voltaje de arranque y los conectores antes de cambiar piezas.",
            )
        }
    }

    private fun origen(c: String): String = when (c[1]) {
        '0', '2' -> "El número es del estándar: significa lo mismo en cualquier carro."
        '1' -> "El número es propio del fabricante: su significado exacto está en " +
            "el manual de servicio, aquí solo se sabe el sistema."
        else -> "El número es de un rango reservado, del fabricante o del estándar " +
            "según el caso: aquí solo se sabe el sistema."
    }

    private fun motorYCaja(c: String, origen: String): Grupo {
        // La tercera posicion dice el sistema, igual en P0, P1 y P2. El P3 no
        // la sigue: alli solo se sabe que es del motor.
        if (c[1] == '3') return Grupo(
            "Motor (rango reservado)",
            "Es del motor o de la caja, en un rango poco usado. $origen",
            CONSEJO_GENERAL,
        )
        return when (c[2]) {
            '0', '1', '2' -> Grupo(
                if (c[2] == '2' && c[1] == '0') "Inyectores y combustible"
                else "Mezcla de aire y combustible",
                "Tiene que ver con cuánto aire entra y cuánta gasolina se " +
                    "inyecta: sensores de aire, de temperatura, mariposa, sondas " +
                    "de oxígeno, inyectores o el sistema de levas. $origen",
                "Si el motor anda normal, puedes seguir con cuidado. Si tironea, " +
                    "huele a gasolina o gasta de más, revísalo pronto: una mezcla " +
                    "mal hecha termina dañando el catalizador.",
            )
            '3' -> Grupo(
                "Encendido",
                "Es del encendido: bobinas, bujías, sensores de cigüeñal o de " +
                    "levas, o fallas de encendido en algún cilindro. $origen",
                "Si la luz del motor PARPADEA hay fallas fuertes: no aceleres a " +
                    "fondo y llévalo pronto, porque la gasolina sin quemar " +
                    "recalienta el catalizador.",
            )
            '4' -> Grupo(
                "Control de emisiones",
                "Es de lo que limpia los gases: catalizador, recirculación de " +
                    "gases (EGR) o el sistema de vapores del tanque (EVAP). $origen",
                "Rara vez cambia cómo anda el carro y no es urgente. Si es de " +
                    "vapores, revisa primero que la tapa del tanque cierre bien.",
            )
            '5' -> Grupo(
                "Velocidad, ralentí y entradas auxiliares",
                "Es del sensor de velocidad, del control de ralentí, del aire " +
                    "acondicionado o del sistema de carga (alternador). $origen",
                "Puede afectar el ralentí, el velocímetro o la carga de la " +
                    "batería: mira el voltaje de arranque en el tablero. Si baja " +
                    "de 12,5 V con el motor encendido, el alternador no carga.",
            )
            '6' -> Grupo(
                "Computadora y sus salidas",
                "Es de la computadora del motor o de un circuito que ella " +
                    "maneja (relés, alimentación de sensores). $origen",
                "Antes de culpar a la computadora revisa fusibles, masas y el " +
                    "voltaje de la batería: un borne flojo provoca estos códigos.",
            )
            // P27 y P28 son caja; P29 solo en el rango P0.
            '7', '8', '9' -> if (c[2] == '9' && c[1] != '0') Grupo(
                "Motor y caja", "Es del motor o de la caja. $origen", CONSEJO_GENERAL,
            ) else Grupo(
                "Caja de cambios",
                "Es de la caja de cambios: sensores de giro, solenoides de " +
                    "cambio, presión del aceite o el convertidor. $origen",
                "Si la caja se queda en una sola marcha, cambia de golpe o " +
                    "patina, maneja suave y despacio hasta el taller. Lo primero " +
                    "es revisar el nivel y el color del aceite de la caja.",
            )
            'A' -> if (c[1] == '2') Grupo(
                "Mezcla de aire y combustible",
                "Tiene que ver con las sondas que miden la mezcla. $origen",
                "Si el motor anda normal, puedes seguir con cuidado y revisarlo pronto.",
            ) else Grupo(
                "Sistema híbrido",
                "Es del sistema de propulsión híbrida, que un carro sin motor " +
                    "eléctrico no tiene. $origen",
                "En un carro que no es híbrido suele ser una lectura falsa: " +
                    "bórralo y mira si vuelve.",
            )
            else -> Grupo("Motor y caja", "Es del motor o de la caja. $origen", CONSEJO_GENERAL)
        }
    }

    private const val CONSEJO_GENERAL =
        "Si el motor y la caja andan normal, puedes seguir con cuidado y hacerlo " +
            "revisar. Si la luz parpadea o el carro anda raro, no lo fuerces."
}
