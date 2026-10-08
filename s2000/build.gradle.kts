plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * "S2000 DASH" — el tablero del Honda S2000 AP1 (F20C). Es SU aplicacion:
 * su paquete, su nombre, su actualizador. Lo compartido viene de `:comun`.
 *
 * ⚠️ applicationId FIJO. Es el que ya esta instalado en el radio del S2000:
 * cambiarlo instalaria una app nueva al lado de la vieja en vez de
 * actualizarla. El confirmador tambien lo trae escrito.
 */
android {
    namespace = "com.nonosky.s2000dash"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nonosky.s2000dash"
        minSdk = 21
        targetSdk = 34
        versionCode = 216
        versionName = "1.5-s2000"
    }

    buildTypes {
        release {
            // Sin ofuscar: el APK se instala a mano y la reflexion del
            // fallback RFCOMM no vale la pena arriesgarla por unos KB.
            isMinifyEnabled = false
            // Firma de debug tambien en release: no hay Play Store de por
            // medio, y es la MISMA firma de siempre, asi que actualiza encima.
            signingConfig = signingConfigs.getByName("debug")
        }
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
    implementation(project(":comun"))

    testImplementation("junit:junit:4.13.2")
}
