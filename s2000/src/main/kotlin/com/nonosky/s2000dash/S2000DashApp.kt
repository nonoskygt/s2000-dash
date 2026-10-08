package com.nonosky.s2000dash

import android.app.Application
import com.nonosky.carsoc.Carro
import com.nonosky.carsoc.selfupdate.Confirmador

/**
 * Lo primero que corre en "S2000 Dash": le dice a la libreria comun en que
 * carro esta. Android crea la Application antes que cualquier pantalla,
 * servicio o receptor, asi que nada llega a preguntar por el carro antes.
 */
class S2000DashApp : Application() {
    override fun onCreate() {
        Carro.instalar(PerfilS2000, MotorF20C)
        // Este carro si usa el APK confirmador; la libreria no lo conoce.
        Confirmador.delCarro = Confirmador(
            paquete = "com.nonosky.s2000dash.confirmador",
            prefijo = "com.nonosky.s2000dash",
        )
        super.onCreate()
    }
}
