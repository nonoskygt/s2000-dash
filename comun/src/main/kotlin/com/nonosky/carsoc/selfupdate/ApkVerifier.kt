package com.nonosky.carsoc.selfupdate

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Comprueba que un APK descargado es de verdad una version nueva de ESTA
 * app antes de instalarlo.
 *
 * Es la defensa principal del auto-instalador. El descubrimiento del
 * servidor va por difusion UDP sin autenticar y la descarga por HTTP en
 * claro: cualquiera en la Wi-Fi puede anunciar un servidor falso. Lo que
 * impide que eso acabe en ejecucion de codigo arbitrario en el carro es
 * esto — un APK ajeno no esta firmado con nuestro certificado y no pasa.
 *
 * Se comprueban tres cosas, y las tres tienen que cuadrar:
 *  1. el paquete es exactamente el nuestro,
 *  2. el certificado de firma es identico al de la app instalada,
 *  3. el versionCode es el que el manifiesto prometio, y es mayor que el
 *     instalado (asi no se puede reinstalar en bucle ni degradar).
 */
object ApkVerifier {

    private const val TAG = "ApkVerifier"

    sealed class Result {
        object Ok : Result()
        data class Rechazado(val motivo: String) : Result()
    }

    /**
     * Verifica un APK acompanante (p. ej. el confirmador).
     *
     * No se exige que sea nuestro propio paquete, pero SI que lleve nuestra
     * firma: eso es lo que impide que alguien de la red nos cuele un APK
     * cualquiera aprovechando que el descubrimiento va sin autenticar.
     */
    fun verifyCompanion(context: Context, apk: File, paqueteEsperado: String): Result {
        val pm = context.packageManager
        val info = leer(pm, apk) ?: return Result.Rechazado("el archivo no es un APK legible")

        if (info.packageName != paqueteEsperado) {
            return Result.Rechazado("es otro paquete: ${info.packageName}")
        }
        val huellasApk = huellas(info)
        if (huellasApk.isEmpty()) return Result.Rechazado("el APK no trae firma")

        val propias = runCatching { huellas(pm.getPackageInfo(context.packageName, flags())) }
            .getOrDefault(emptySet())
        if (propias.isEmpty()) return Result.Rechazado("no se pudo leer la firma propia")
        if (huellasApk.intersect(propias).isEmpty()) {
            Log.w(TAG, "Firma distinta en acompanante")
            return Result.Rechazado("la firma no es la nuestra")
        }
        return Result.Ok
    }

    /**
     * Se piden LAS DOS formas de firma, no solo la nueva.
     *
     * En Android 9 (API 28) `getPackageArchiveInfo` con solo
     * GET_SIGNING_CERTIFICATES devuelve `signingInfo` nulo para un APK suelto:
     * el fallo esta en la plataforma y se arreglo en Android 10. Asi que en el
     * radio con Android 9 TODA actualizacion salia "el APK no trae firma" —
     * medido el 2026-10-08 con un APK firmado v1+v2 que apksigner da por bueno—
     * y la auto-actualizacion de ese carro no habia funcionado nunca.
     * Pidiendo tambien GET_SIGNATURES, el archivo trae `signatures` y
     * [huellas] tira de ahi cuando `signingInfo` viene vacio.
     */
    @Suppress("DEPRECATION")
    private fun flags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            PackageManager.GET_SIGNATURES
        }

    private fun leer(pm: PackageManager, apk: File): PackageInfo? =
        runCatching { pm.getPackageArchiveInfo(apk.absolutePath, flags()) }.getOrNull()

    fun verify(context: Context, apk: File, versionCodeEsperado: Int): Result {
        val pm = context.packageManager

        val flags = flags()

        val info = try {
            pm.getPackageArchiveInfo(apk.absolutePath, flags)
        } catch (e: Exception) {
            null
        } ?: return Result.Rechazado("el archivo no es un APK legible")

        if (info.packageName != context.packageName) {
            return Result.Rechazado("es otro paquete: ${info.packageName}")
        }

        val code = versionCodeOf(info)
        if (code != versionCodeEsperado) {
            return Result.Rechazado("versionCode $code no es el prometido $versionCodeEsperado")
        }

        val propio = try {
            versionCodeOf(pm.getPackageInfo(context.packageName, 0))
        } catch (e: Exception) {
            -1
        }
        if (code <= propio) {
            return Result.Rechazado("no es mas nuevo que el instalado ($code <= $propio)")
        }

        val huellasApk = huellas(info)
        if (huellasApk.isEmpty()) return Result.Rechazado("el APK no trae firma")

        val huellasPropias = try {
            huellas(pm.getPackageInfo(context.packageName, flags))
        } catch (e: Exception) {
            emptySet<String>()
        }
        if (huellasPropias.isEmpty()) return Result.Rechazado("no se pudo leer la firma propia")

        if (huellasApk.intersect(huellasPropias).isEmpty()) {
            // El caso que de verdad importa: alguien nos colo otro APK.
            Log.w(TAG, "Firma distinta. APK=$huellasApk propia=$huellasPropias")
            return Result.Rechazado("la firma no es la nuestra")
        }

        return Result.Ok
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            info.versionCode
        }

    @Suppress("DEPRECATION")
    private fun huellas(info: PackageInfo): Set<String> {
        val nuevas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let {
                if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
            }
        } else {
            null
        }
        // Ver flags(): en Android 9 un APK suelto solo trae las viejas.
        val firmas = nuevas?.takeIf { it.isNotEmpty() } ?: info.signatures ?: return emptySet()

        val md = MessageDigest.getInstance("SHA-256")
        return firmas.mapNotNull { f ->
            runCatching { md.digest(f.toByteArray()).joinToString("") { "%02x".format(it) } }
                .getOrNull()
        }.toSet()
    }
}
