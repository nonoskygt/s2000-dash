pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "s2000-dash"

// "S2000 Dash" — tablero del Honda S2000 AP1.
//   :s2000        la app: su perfil, su motor, su tablero, su tabla de averias
//   :comun        la libreria: Bluetooth, OBD, bateria, llantas, tablero
//   :confirmador  APK acompañante que pulsa "Instalar" al actualizarse
include(":comun")
include(":s2000")
include(":confirmador")
