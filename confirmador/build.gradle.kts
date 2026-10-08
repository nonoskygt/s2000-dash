plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * APK aparte, minusculo, con el unico trabajo de confirmar el dialogo de
 * instalacion del tablero.
 *
 * Existe porque Android DESACTIVA el servicio de accesibilidad de una app
 * cuando esa app se actualiza — es una proteccion deliberada, para que
 * nadie gane accesibilidad por la puerta de atras de una actualizacion.
 * Con el confirmador dentro del tablero, la cadena de auto-actualizacion
 * servia exactamente una vez y luego habia que volver al carro a
 * reactivarla a mano.
 *
 * Este modulo NO se actualiza nunca. Se instala una vez, se le da permiso
 * de accesibilidad una vez, y de ahi en adelante el tablero puede
 * actualizarse cuantas veces haga falta.
 */
android {
    namespace = "com.nonosky.s2000dash.confirmador"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nonosky.s2000dash.confirmador"
        minSdk = 21
        targetSdk = 34
        versionCode = 8
        versionName = "1.6"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Misma firma que el tablero: de eso depende que el permiso de
            // nivel "signature" deje que solo el tablero lo arme.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
