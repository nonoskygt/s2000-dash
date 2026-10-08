plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

/**
 * Lo que comparten las dos aplicaciones: Bluetooth, OBD, baterias de litio,
 * llantas, nevera, diagnostico y los dos tableros.
 *
 * NO sabe en que carro corre, y no debe saberlo: no hay aqui ni un nombre de
 * carro, ni una MAC, ni una cifra de motor. Todo eso lo entrega cada app al
 * arrancar con `Carro.instalar(...)`. Ver `Carro.kt`.
 */
android {
    namespace = "com.nonosky.carsoc"
    compileSdk = 34

    defaultConfig {
        // Los radios corren Android 9 y 11. minSdk 21 deja
        // margen por si el tablero acaba en otro head unit mas viejo.
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Sin androidx: no hace falta. El tema es el Material del sistema y no hay
    // Fragment ni AppCompat en ninguna pantalla. Lo unico que lo usaba era un
    // tablero viejo en Canvas que ya no existe. Menos APK, menos memoria.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
